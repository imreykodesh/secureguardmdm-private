package com.secureguard.mdm.ministore.play

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Every state change of the Google Play session, with the reason it happened. */
enum class PlaySessionEvent {
    SIGN_IN_OK,
    SESSION_LOADED,
    SESSION_ABSENT,
    SESSION_UNREADABLE_CLEARED,
    SESSION_LOAD_TRANSIENT_KEPT,
    KEYSTORE_KEY_MISSING_CLEARED,
    CREDENTIAL_REJECTED,
    REFRESH_ATTEMPT,
    REFRESH_OK,
    REFRESH_FAILED,
    REFRESH_UNAVAILABLE,
    SESSION_INVALIDATED,
    USER_SIGN_OUT,
}

/**
 * Last known session status, kept so the UI can state what happened instead of
 * silently showing a signed-out store.
 */
data class PlaySessionStatus(
    val signedInAtMillis: Long,
    val lastRefreshAtMillis: Long,
    val refreshCount: Int,
    val lastFailureAtMillis: Long,
    val lastFailureEvent: String?,
    val lastFailureDetail: String?,
)

/**
 * Durable, on-device audit trail for the Play session.
 *
 * Until now a lost session left no evidence: `logcat` is a ring buffer that is
 * empty hours later, and the two code paths that dropped the session did so
 * without recording anything. That made every explanation a guess. This log
 * survives the session being cleared, the app being restarted and the device
 * being rebooted, so the next disconnection can be read as a fact.
 *
 * The file never receives tokens or account addresses. Values that look like a
 * credential or an address are replaced before anything is written.
 */
@Singleton
class PlaySessionAudit @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val logFile = File(context.filesDir, FILE_NAME)
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Synchronized
    fun record(event: PlaySessionEvent, detail: String = "") {
        val now = System.currentTimeMillis()
        val sanitizedDetail = sanitize(detail)
        when (event) {
            PlaySessionEvent.SIGN_IN_OK -> preferences.edit()
                .putLong(KEY_SIGNED_IN_AT, now)
                .putLong(KEY_LAST_REFRESH_AT, 0L)
                .putInt(KEY_REFRESH_COUNT, 0)
                .remove(KEY_LAST_FAILURE_AT)
                .remove(KEY_LAST_FAILURE_EVENT)
                .remove(KEY_LAST_FAILURE_DETAIL)
                .apply()

            PlaySessionEvent.REFRESH_OK -> preferences.edit()
                .putLong(KEY_LAST_REFRESH_AT, now)
                .putInt(KEY_REFRESH_COUNT, preferences.getInt(KEY_REFRESH_COUNT, 0) + 1)
                .apply()

            PlaySessionEvent.USER_SIGN_OUT -> preferences.edit()
                .remove(KEY_SIGNED_IN_AT)
                .remove(KEY_LAST_REFRESH_AT)
                .remove(KEY_REFRESH_COUNT)
                .remove(KEY_LAST_FAILURE_AT)
                .remove(KEY_LAST_FAILURE_EVENT)
                .remove(KEY_LAST_FAILURE_DETAIL)
                .apply()

            PlaySessionEvent.CREDENTIAL_REJECTED,
            PlaySessionEvent.REFRESH_FAILED,
            PlaySessionEvent.REFRESH_UNAVAILABLE,
            PlaySessionEvent.SESSION_INVALIDATED,
            PlaySessionEvent.SESSION_UNREADABLE_CLEARED,
            PlaySessionEvent.SESSION_LOAD_TRANSIENT_KEPT,
            PlaySessionEvent.KEYSTORE_KEY_MISSING_CLEARED -> preferences.edit()
                .putLong(KEY_LAST_FAILURE_AT, now)
                .putString(KEY_LAST_FAILURE_EVENT, event.name)
                .putString(KEY_LAST_FAILURE_DETAIL, sanitizedDetail)
                .apply()

            else -> Unit
        }

        val sessionAge = preferences.getLong(KEY_SIGNED_IN_AT, 0L)
            .takeIf { it > 0L }
            ?.let { now - it }
        val line = buildString {
            append(timestampFormat.format(Date(now)))
            append(" | uptime=").append(SystemClock.elapsedRealtime())
            append(" | ").append(event.name)
            if (sessionAge != null) {
                append(" | sessionAge=").append(formatDuration(sessionAge))
            }
            if (sanitizedDetail.isNotBlank()) {
                append(" | ").append(sanitizedDetail)
            }
        }
        append(line)
    }

    /** Machine-readable summary for the UI. */
    fun status(): PlaySessionStatus = PlaySessionStatus(
        signedInAtMillis = preferences.getLong(KEY_SIGNED_IN_AT, 0L),
        lastRefreshAtMillis = preferences.getLong(KEY_LAST_REFRESH_AT, 0L),
        refreshCount = preferences.getInt(KEY_REFRESH_COUNT, 0),
        lastFailureAtMillis = preferences.getLong(KEY_LAST_FAILURE_AT, 0L),
        lastFailureEvent = preferences.getString(KEY_LAST_FAILURE_EVENT, null),
        lastFailureDetail = preferences.getString(KEY_LAST_FAILURE_DETAIL, null),
    )

    private fun append(line: String) {
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.appendText(line + "\n")
            if (logFile.length() > MAX_BYTES) {
                val kept = logFile.readLines().takeLast(KEEP_LINES)
                logFile.writeText(kept.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /**
     * Removes anything that could be a credential or an account address.
     * Diagnostics must stay readable without becoming a place where a token
     * can leak into a file, a bug report or a screenshot.
     */
    private fun sanitize(detail: String): String = detail
        .replace(EMAIL_PATTERN, "<account>")
        .replace(TOKEN_PATTERN, "<redacted>")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_DETAIL_CHARS)

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h${minutes}m" else "${minutes}m"
    }

    private companion object {
        const val FILE_NAME = "mini_store_play_audit.log"
        const val PREFERENCES_NAME = "mini_store_play_diagnostics"
        const val KEY_SIGNED_IN_AT = "signed_in_at"
        const val KEY_LAST_REFRESH_AT = "last_refresh_at"
        const val KEY_REFRESH_COUNT = "refresh_count"
        const val KEY_LAST_FAILURE_AT = "last_failure_at"
        const val KEY_LAST_FAILURE_EVENT = "last_failure_event"
        const val KEY_LAST_FAILURE_DETAIL = "last_failure_detail"
        const val MAX_BYTES = 32 * 1024L
        const val KEEP_LINES = 120
        const val MAX_DETAIL_CHARS = 240
        val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val TOKEN_PATTERN = Regex("[A-Za-z0-9_\\-/=]{24,}")
    }
}
