package com.secureguard.mdm.ministore.play

import android.Manifest
import android.accounts.AccountManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the Google accounts already present on the device.
 *
 * This is used to name the account in the sign-in prompt and to pre-select it in
 * the web flow. It cannot replace that flow: Google does not expose a Play
 * protocol token for a system account to third-party apps, so the account still
 * has to authorise A Bloq once through the web sign-in.
 */
@Singleton
class DeviceGoogleAccounts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val devicePolicyManager: DevicePolicyManager,
) {
    fun primaryAccount(): String? = accounts().firstOrNull()

    fun accounts(): List<String> {
        ensureAccountPermission()
        if (!hasAccountPermission()) {
            Log.i(TAG, "account read permission not granted")
            return emptyList()
        }
        return runCatching {
            AccountManager.get(context)
                .getAccountsByType(GOOGLE_ACCOUNT_TYPE)
                .mapNotNull { it.name?.takeIf(String::isNotBlank) }
                .distinct()
        }.onFailure { Log.w(TAG, "could not read device accounts: ${it.javaClass.simpleName}") }
            .onSuccess { Log.i(TAG, "device Google accounts visible: ${it.size}") }
            .getOrDefault(emptyList())
    }

    private fun hasAccountPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.GET_ACCOUNTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Grants the account read permission to A Bloq itself. Allowed because
     * A Bloq is Device Owner, and it avoids prompting the operator for a
     * permission the product requires to name the account.
     */
    private fun ensureAccountPermission() {
        if (hasAccountPermission()) return
        if (!devicePolicyManager.isDeviceOwnerApp(context.packageName)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            devicePolicyManager.setPermissionGrantState(
                SecureGuardDeviceAdminReceiver.getComponentName(context),
                context.packageName,
                Manifest.permission.GET_ACCOUNTS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }.onFailure { Log.w(TAG, "could not self-grant account permission: ${it.message}") }
    }

    private companion object {
        const val TAG = "MiniStoreAccounts"
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
