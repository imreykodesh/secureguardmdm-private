package com.secureguard.mdm.ministore.play

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import com.aurora.gplayapi.R as GPlayApiR
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.google.gson.Gson
import com.secureguard.mdm.BuildConfig
import com.secureguard.mdm.R
import com.secureguard.mdm.ministore.data.UpdateArtifact
import com.secureguard.mdm.ministore.data.UpdateArtifactRole
import com.secureguard.mdm.ministore.data.UpdateCandidate
import com.secureguard.mdm.ministore.data.UpdateLocator
import com.secureguard.mdm.ministore.data.UpdatePlan
import com.secureguard.mdm.ministore.data.UpdateSource
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.ProtocolException
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PlayUpdateSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: GPlayHttpClient,
    private val accountSession: PlayAccountSession,
    private val audit: PlaySessionAudit,
) {
    private val playOperationMutex = Mutex()

    @Volatile
    private var consecutiveCredentialRejections = 0

    /** Google Play is available only once this device has a signed-in account. */
    fun isConfigured(): Boolean = accountSession.isSignedIn()

    /**
     * Looks for newer versions of the installed packages.
     *
     * A credential verdict from Google is treated as "the derived Play tokens
     * expired" before it is treated as "the account is gone": the session is
     * refreshed from the stored AAS token and the pass is retried once. Only a
     * rejection that survives that refresh counts against the sign-out
     * threshold.
     */
    suspend fun discover(installedVersions: Map<String, Long>): PlayDiscoveryResult =
        withContext(Dispatchers.IO) {
            playOperationMutex.withLock {
                try {
                    discoverOnce(ensureAuth(), installedVersions).also {
                        consecutiveCredentialRejections = 0
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!isCredentialRejection(error)) {
                        throw PlayDiscoveryException(isRetryableFailureLocked(error), error)
                    }
                    recordRejection("discovery", error)
                    val refreshed = accountSession.refresh("play_discovery_rejected")
                    if (refreshed == null) {
                        registerRejection("discovery: refresh unavailable or rejected")
                        throw PlayDiscoveryException(isRetryableFailureLocked(error), error)
                    }
                    try {
                        discoverOnce(refreshed, installedVersions).also {
                            consecutiveCredentialRejections = 0
                        }
                    } catch (retryError: CancellationException) {
                        throw retryError
                    } catch (retryError: Exception) {
                        if (isCredentialRejection(retryError)) {
                            recordRejection("discovery after refresh", retryError)
                            registerRejection("discovery rejected again after refresh")
                        }
                        throw PlayDiscoveryException(isRetryableFailureLocked(retryError), retryError)
                    }
                }
            }
        }

    private fun discoverOnce(
        authData: AuthData,
        installedVersions: Map<String, Long>,
    ): PlayDiscoveryResult {
        val helper = AppDetailsHelper(authData).using(httpClient)
        val candidates = LinkedHashMap<String, UpdateCandidate>()
        val failedPackages = linkedSetOf<String>()
        val retryableFailedPackages = linkedSetOf<String>()

        installedVersions.keys.chunked(DISCOVERY_BATCH_SIZE).forEach { packageBatch ->
            val apps = runCatching { helper.getAppByPackageName(packageBatch) }.getOrElse { batchError ->
                if (isCredentialRejection(batchError)) throw batchError
                packageBatch.mapNotNull { packageName ->
                    runCatching { helper.getAppByPackageName(packageName) }
                        .onFailure { error ->
                            if (isCredentialRejection(error)) throw error
                            failedPackages += packageName
                            if (isRetryableFailureLocked(error)) {
                                retryableFailedPackages += packageName
                            }
                        }
                        .getOrNull()
                }
            }
            apps.forEach { app ->
                val installedVersion = installedVersions[app.packageName] ?: return@forEach
                if (app.versionCode > installedVersion && app.displayName.isNotBlank()) {
                    candidates[app.packageName] = app.toCandidate()
                }
            }
        }
        return PlayDiscoveryResult(candidates, failedPackages, retryableFailedPackages)
    }

    /** Writes the exact verdict to the durable audit trail. */
    private fun recordRejection(stage: String, error: Throwable) {
        audit.record(
            PlaySessionEvent.CREDENTIAL_REJECTED,
            "stage=$stage marker=${rejectionMarker(error)} " +
                "http=${httpClient.responseCode.value} cause=${error.javaClass.simpleName}",
        )
    }

    /**
     * Counts a rejection that survived a refresh, and signs the device out only
     * when it repeats. One bad answer must not discard a working account.
     */
    private fun registerRejection(reason: String) {
        consecutiveCredentialRejections++
        if (consecutiveCredentialRejections >= MAX_CREDENTIAL_REJECTIONS) {
            Log.w(TAG, "signing out after repeated credential rejections")
            accountSession.invalidate(
                "$reason; consecutive=$consecutiveCredentialRejections",
            )
            consecutiveCredentialRejections = 0
        } else {
            Log.w(TAG, "credential rejected once; session kept for another attempt")
        }
    }

    private fun rejectionMarker(error: Throwable): String =
        generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { candidate ->
                val description = candidate.message.orEmpty()
                CREDENTIAL_REJECTION_MARKERS.firstOrNull {
                    description.contains(it, ignoreCase = true)
                }
            }
            .firstOrNull() ?: "unknown"

    /**
     * True only when Google explicitly rejected the stored credentials.
     *
     * A plain authentication error is not enough: the same exception type is
     * raised for throttling and for server-side hiccups, and treating those as a
     * rejection silently signed the device out. Only an explicit credential
     * verdict counts.
     */
    private fun isCredentialRejection(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }
            .filter { it.javaClass.simpleName == "AuthException" }
            .any { authError ->
                val description = authError.message.orEmpty()
                CREDENTIAL_REJECTION_MARKERS.any { description.contains(it, ignoreCase = true) }
            }

    private fun isRetryableFailureLocked(error: Throwable): Boolean {
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        return causes.any { it is IOException && it !is ProtocolException } ||
            httpClient.responseCode.value in RETRYABLE_RESPONSE_CODES
    }

    suspend fun resolve(candidate: UpdateCandidate): UpdatePlan = withContext(Dispatchers.IO) {
        playOperationMutex.withLock {
            require(candidate.source == UpdateSource.GOOGLE_PLAY) { "Not a Google Play update" }
            val locator = candidate.locator as? UpdateLocator.GooglePlay
                ?: error("Missing Google Play delivery metadata")
            try {
                resolveWithAuth(candidate, locator, ensureAuth())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isCredentialRejection(error)) throw error
                // An update that was already approved should not fail because the
                // derived tokens expired between discovery and download.
                recordRejection("resolve", error)
                val refreshed = accountSession.refresh("play_resolve_rejected")
                    ?: run {
                        registerRejection("resolve: refresh unavailable or rejected")
                        throw error
                    }
                try {
                    resolveWithAuth(candidate, locator, refreshed)
                } catch (retryError: Exception) {
                    if (isCredentialRejection(retryError)) {
                        recordRejection("resolve after refresh", retryError)
                        registerRejection("resolve rejected again after refresh")
                    }
                    throw retryError
                }
            }
        }
    }

    private fun resolveWithAuth(
        candidate: UpdateCandidate,
        locator: UpdateLocator.GooglePlay,
        authData: AuthData,
    ): UpdatePlan {
        val purchaseHelper = PurchaseHelper(authData).using(httpClient)
        val certificateHash = installedCertificateHash(candidate.packageName)
        val files = purchaseHelper.purchase(
            packageName = candidate.packageName,
            versionCode = candidate.versionCode,
            offerType = locator.offerType,
            certificateHash = certificateHash,
        )
        require(files.isNotEmpty()) { "Google Play did not return update files" }
        val unsupported = files.filter { it.type == PlayFile.Type.OBB || it.type == PlayFile.Type.PATCH }
        require(unsupported.isEmpty()) { "This update requires unsupported expansion or patch files" }
        val installFiles = files.filter { it.type == PlayFile.Type.BASE || it.type == PlayFile.Type.SPLIT }
        require(installFiles.count { it.type == PlayFile.Type.BASE } == 1) {
            "Google Play delivery must contain exactly one base APK"
        }
        val artifacts = installFiles.mapIndexed { index, file ->
            require(file.url.startsWith("https://")) { "Google Play returned a non-HTTPS artifact" }
            require(file.size > 0) { "Google Play returned an invalid artifact size" }
            val sha256 = file.sha256.lowercase(Locale.ROOT)
            require(SHA256_REGEX.matches(sha256)) { "Google Play artifact has no valid SHA-256" }
            UpdateArtifact(
                role = if (file.type == PlayFile.Type.BASE) UpdateArtifactRole.BASE else UpdateArtifactRole.SPLIT,
                name = file.name.ifBlank { "split_$index.apk" },
                url = file.url,
                size = file.size,
                sha256 = sha256,
            )
        }
        return UpdatePlan(
            packageName = candidate.packageName,
            versionCode = candidate.versionCode,
            versionName = candidate.versionName,
            source = UpdateSource.GOOGLE_PLAY,
            artifacts = artifacts,
        )
    }

    private suspend fun ensureAuth(): AuthData = accountSession.currentSession()
        ?: error(context.getString(R.string.mini_store_play_not_signed_in))

    private fun installedCertificateHash(packageName: String): String? {
        val packageInfo = installedPackageInfo(packageName)
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners?.lastOrNull()
            } else {
                signingInfo.signingCertificateHistory?.lastOrNull()
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.lastOrNull()
        } ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun installedPackageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                packageName,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                },
            )
        }

    private fun App.toCandidate() = UpdateCandidate(
        packageName = packageName,
        displayName = displayName,
        versionCode = versionCode,
        versionName = versionName.ifBlank { versionCode.toString() },
        minSdk = null,
        releaseNotes = "",
        source = UpdateSource.GOOGLE_PLAY,
        locator = UpdateLocator.GooglePlay(offerType),
    )

    class PlayDiscoveryException(
        val retryable: Boolean,
        cause: Throwable,
    ) : Exception("Google Play discovery failed", cause)

    data class PlayDiscoveryResult(
        val candidates: Map<String, UpdateCandidate>,
        val failedPackages: Set<String>,
        val retryableFailedPackages: Set<String>,
    )

    companion object {
        private const val TAG = "MiniStorePlaySource"
        private const val MAX_CREDENTIAL_REJECTIONS = 2
        private val CREDENTIAL_REJECTION_MARKERS = listOf(
            "BadAuthentication",
            "NeedsBrowser",
            "Unauthorized",
        )
        private const val DISCOVERY_BATCH_SIZE = 30
        private val RETRYABLE_RESPONSE_CODES = setOf(408, 429, 500, 502, 503, 504)
        private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")
    }
}
