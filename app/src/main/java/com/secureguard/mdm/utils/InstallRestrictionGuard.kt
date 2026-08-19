package com.secureguard.mdm.utils

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import com.secureguard.mdm.features.impl.BlockInstallAppsFeature

/**
 * DISALLOW_INSTALL_APPS is enforced by the platform inside
 * PackageInstallerService.createSessionInternal, and it applies to the Device
 * Owner as well: a session created by A Bloq itself fails with
 * "SecurityException: User restriction prevents installing". Verified on
 * SM_A145P (Android 14) while the "block app installation" toggle was on.
 *
 * So a device that blocks new installs cannot update anything either, unless the
 * restriction is lifted for the duration of the install session and put back
 * immediately afterwards. That is what this guard does, mirroring the existing
 * [com.secureguard.mdm.ministore.install.MiniStorePackageOperator]
 * withTemporaryUninstallRestriction pattern in the opposite direction.
 *
 * The window is deliberately as narrow as possible: it is opened around the
 * session only, never around a download, and the Mini Store holds its operation
 * mutex for the whole update, so two windows cannot overlap. Other install
 * defences (unknown sources, hidden Play Store, blocked debugging) stay in place
 * throughout.
 *
 * The stored feature state is never touched, so the toggle keeps reporting
 * "enabled" and a restore failure can be corrected by re-applying the feature.
 */
object InstallRestrictionGuard {

    private const val TAG = "InstallGuard"

    /** Runs [block] with app installation temporarily permitted. */
    suspend fun <T> withInstallAllowed(context: Context, block: suspend () -> T): T {
        val restore = lift(context)
        return try {
            block()
        } finally {
            restore()
        }
    }

    /** Blocking variant for call sites that are not coroutines. */
    fun <T> withInstallAllowedBlocking(context: Context, block: () -> T): T {
        val restore = lift(context)
        return try {
            block()
        } finally {
            restore()
        }
    }

    /**
     * Clears the restriction when it is active and returns the action that puts
     * it back. When nothing had to be changed the returned action is a no-op.
     */
    private fun lift(context: Context): () -> Unit {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return NO_OP
        if (!dpm.isDeviceOwnerApp(context.packageName)) return NO_OP

        val admin = SecureGuardDeviceAdminReceiver.getComponentName(context)
        val wasRestricted = runCatching {
            BlockInstallAppsFeature.isPolicyActive(context, dpm, admin)
        }.getOrDefault(false)
        if (!wasRestricted) return NO_OP

        val cleared = runCatching {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        }
        if (cleared.isFailure) {
            Log.w(TAG, "could not lift DISALLOW_INSTALL_APPS; the install will fail", cleared.exceptionOrNull())
            return NO_OP
        }

        Log.i(TAG, "DISALLOW_INSTALL_APPS lifted for a single install session")
        return {
            val restored = runCatching {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            }
            if (restored.isFailure) {
                Log.e(TAG, "FAILED to restore DISALLOW_INSTALL_APPS", restored.exceptionOrNull())
            } else {
                Log.i(TAG, "DISALLOW_INSTALL_APPS restored")
            }
        }
    }

    private val NO_OP: () -> Unit = {}
}
