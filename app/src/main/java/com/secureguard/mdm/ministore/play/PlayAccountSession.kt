package com.secureguard.mdm.ministore.play

import android.content.Context
import android.os.Build
import com.aurora.gplayapi.R as GPlayApiR
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.helpers.AuthHelper
import com.secureguard.mdm.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Holds the Google Play session for the account signed in on this device.
 *
 * The session is minted on the device itself: the web sign-in flow yields a
 * short-lived `oauth_token`, and gplayapi exchanges it for an AAS token and a
 * Play session using this device's own check-in identity. Minting the token
 * off-device produces a mismatched identity and Google answers
 * `BadAuthentication`, which is why no shared or externally issued token is
 * accepted here.
 */
@Singleton
class PlayAccountSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: GPlayHttpClient,
    private val credentialStore: PlayCredentialStore,
    private val audit: PlaySessionAudit,
) {
    private val sessionMutex = Mutex()

    @Volatile
    private var cachedAuth: AuthData? = null

    @Volatile
    private var cacheLoaded = false

    /** Signed-in account address, or null when no session is stored. */
    fun signedInEmail(): String? = storedAuth()?.email?.takeIf { it.isNotBlank() }

    fun isSignedIn(): Boolean = signedInEmail() != null

    /**
     * Exchanges a freshly captured web sign-in token for a Play session.
     * The `oauth_token` value is single-use and expires within minutes.
     */
    suspend fun signIn(email: String, oauthToken: String): String = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim()
        val normalizedToken = oauthToken.trim()
        require(normalizedEmail.contains('@')) {
            context.getString(R.string.mini_store_play_login_invalid_email)
        }
        require(normalizedToken.isNotEmpty()) {
            context.getString(R.string.mini_store_play_login_missing_token)
        }

        sessionMutex.withLock {
            val authData = AuthHelper.using(httpClient).build(
                normalizedEmail,
                normalizedToken,
                AuthHelper.Token.AUTH,
                false,
                deviceProperties(),
                Locale.getDefault(),
            )
            require(authData.authToken.isNotBlank() && authData.deviceConfigToken.isNotBlank()) {
                context.getString(R.string.mini_store_play_login_session_failed)
            }
            credentialStore.save(SCOPE, authData)
            cachedAuth = authData
            cacheLoaded = true
            audit.record(
                PlaySessionEvent.SIGN_IN_OK,
                "aasToken=${if (authData.aasToken.isNotBlank()) "stored" else "absent"}",
            )
            authData.email
        }
    }

    /**
     * Rebuilds the Play session from the stored AAS token, without asking the
     * user for anything.
     *
     * The session that Google returns contains two kinds of credential: the AAS
     * token, which is long lived, and the Play tokens derived from it, which are
     * not. Once the derived tokens expire, Google answers a normal request with a
     * credential verdict, and the previous behaviour read that verdict as "the
     * account is gone" and deleted everything, including the AAS token that was
     * still perfectly valid. That is why the store asked for a new sign-in after
     * a few hours. Refreshing first means a sign-in is requested only when the
     * long-lived token itself is no longer accepted.
     *
     * @return the refreshed session, or null when a refresh is impossible or was
     *   rejected. A null result never clears the stored session by itself.
     */
    suspend fun refresh(reason: String): AuthData? = withContext(Dispatchers.IO) {
        sessionMutex.withLock { refreshLocked(reason) }
    }

    private fun refreshLocked(reason: String): AuthData? {
        val existing = storedAuth()
        if (existing == null) {
            audit.record(PlaySessionEvent.REFRESH_UNAVAILABLE, "no stored session; reason=$reason")
            return null
        }
        val aasToken = existing.aasToken
        if (aasToken.isBlank()) {
            audit.record(
                PlaySessionEvent.REFRESH_UNAVAILABLE,
                "stored session has no AAS token; reason=$reason",
            )
            return null
        }

        audit.record(PlaySessionEvent.REFRESH_ATTEMPT, "reason=$reason")
        return runCatching {
            val refreshed = AuthHelper.using(httpClient).build(
                existing.email,
                aasToken,
                AuthHelper.Token.AAS,
                false,
                deviceProperties(),
                Locale.getDefault(),
            )
            require(refreshed.authToken.isNotBlank() && refreshed.deviceConfigToken.isNotBlank()) {
                "Refreshed Play session is incomplete"
            }
            credentialStore.save(SCOPE, refreshed)
            cachedAuth = refreshed
            cacheLoaded = true
            refreshed
        }.onSuccess {
            audit.record(PlaySessionEvent.REFRESH_OK, "rebuilt from stored AAS token")
        }.getOrElse { error ->
            audit.record(
                PlaySessionEvent.REFRESH_FAILED,
                "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}",
            )
            null
        }
    }

    /**
     * Returns the stored session, or null when the device is not signed in.
     *
     * The session is deliberately not probed for validity here. A probe that
     * fails for any reason, including a transient network fault, would discard a
     * perfectly good session and force the user through sign-in again. Instead
     * [invalidate] is called only when Google actually rejects a real request.
     */
    suspend fun currentSession(): AuthData? = sessionMutex.withLock { storedAuth() }

    /** Drops the session after Google rejected it, so the user is asked to sign in again. */
    @Synchronized
    fun invalidate(reason: String) {
        audit.record(PlaySessionEvent.SESSION_INVALIDATED, reason)
        clearLocked()
    }

    fun signOut() {
        audit.record(PlaySessionEvent.USER_SIGN_OUT, "requested from the app")
        cachedAuth = null
        cacheLoaded = true
        credentialStore.clear()
    }

    /** Last recorded session status, for honest reporting in the UI. */
    fun status(): PlaySessionStatus = audit.status()

    private fun clearLocked() {
        cachedAuth = null
        cacheLoaded = true
        credentialStore.clear()
    }

    private fun storedAuth(): AuthData? {
        cachedAuth?.let { return it }
        if (cacheLoaded) return null
        val loaded = credentialStore.load(SCOPE)
        cachedAuth = loaded
        cacheLoaded = true
        return loaded
    }

    /**
     * Device profile sent to Google. Based on the profile bundled with
     * gplayapi, with the real device characteristics applied so that delivery
     * targeting matches this hardware.
     */
    fun deviceProperties(): Properties {
        val properties = Properties()
        context.resources.openRawResource(GPlayApiR.raw.gplayapi_sm_s25u).use(properties::load)
        val configuration = context.resources.configuration
        val metrics = context.resources.displayMetrics
        properties["Build.VERSION.SDK_INT"] = Build.VERSION.SDK_INT.toString()
        properties["Build.VERSION.RELEASE"] = Build.VERSION.RELEASE
        properties["Platforms"] = Build.SUPPORTED_ABIS.joinToString(",")
        properties["Screen.Density"] = metrics.densityDpi.toString()
        properties["Screen.Width"] = metrics.widthPixels.toString()
        properties["Screen.Height"] = metrics.heightPixels.toString()
        properties["TouchScreen"] = configuration.touchscreen.toString()
        properties["Keyboard"] = configuration.keyboard.toString()
        properties["Navigation"] = configuration.navigation.toString()
        properties["ScreenLayout"] = (configuration.screenLayout and 15).toString()
        properties["Locales"] = context.resources.assets.locales
            .joinToString(",") { it.replace('-', '_') }
        properties["Features"] = context.packageManager.systemAvailableFeatures
            .mapNotNull { it.name }
            .joinToString(",")
        properties["SharedLibraries"] = context.packageManager.systemSharedLibraryNames
            ?.joinToString(",").orEmpty()
        return properties
    }

    private companion object {
        // Bound to the on-device account flow so a session issued for any other
        // source cannot be decrypted and reused here.
        const val SCOPE = "device-google-account/v1"
    }
}
