package com.secureguard.mdm.ministore.domain

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the Mini Store management authorisation.
 *
 * The window lives outside the screen's ViewModel on purpose. Navigating to the
 * Google sign-in screen and back recreates that ViewModel, and keeping the
 * window there meant the user was asked for the management password again even
 * though they had just entered it. The window is still time limited and is
 * dropped as soon as the app leaves the foreground.
 */
@Singleton
class MiniStoreAccessGate @Inject constructor() {
    @Volatile
    private var privilegedUntilElapsed = 0L

    fun grant() {
        privilegedUntilElapsed = SystemClock.elapsedRealtime() + PRIVILEGED_SESSION_MILLIS
    }

    fun isPrivileged(): Boolean = SystemClock.elapsedRealtime() < privilegedUntilElapsed

    /** Remaining validity, used to re-arm the expiry timer after recreation. */
    fun remainingMillis(): Long =
        (privilegedUntilElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    fun revoke() {
        privilegedUntilElapsed = 0L
    }

    private companion object {
        const val PRIVILEGED_SESSION_MILLIS = 5 * 60 * 1000L
    }
}
