package com.secureguard.mdm.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.telecom.TelecomManager
import android.widget.Toast
import com.secureguard.mdm.R
import com.secureguard.mdm.utils.update.UpdateManager

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val installedPackageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(UpdateManager.EXTRA_EXPECTED_PACKAGE)
            ?: intent.getStringExtra(LEGACY_PACKAGE_NAME_EXTRA)
        val operation = intent.getStringExtra(UpdateManager.EXTRA_OPERATION)

        if (status == PackageInstaller.STATUS_SUCCESS) {
            if (operation == UpdateManager.OPERATION_SELF_UPDATE) {
                showSelfUpdateResult(context, installedPackageName, intent)
            } else if (installedPackageName == NO_PHONE_PACKAGE) {
                Toast.makeText(context, R.string.toast_nophone_installed, Toast.LENGTH_LONG).show()
                val changeDialerIntent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, installedPackageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(changeDialerIntent)
            } else {
                Toast.makeText(context, R.string.update_toast_success, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?: if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                "ההתקנה דרשה אישור משתמש ולכן לא הושלמה בשקט"
            } else {
                "status $status"
            }
        Toast.makeText(
            context,
            context.getString(R.string.update_toast_failed) + ": " + message,
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun showSelfUpdateResult(context: Context, packageName: String?, intent: Intent) {
        val expectedPackage = intent.getStringExtra(UpdateManager.EXTRA_EXPECTED_PACKAGE)
        val expectedVersion = intent.getLongExtra(UpdateManager.EXTRA_EXPECTED_VERSION, -1L)
        val actualVersion = packageName?.takeIf { it == expectedPackage }?.let { installedVersion(context, it) }
        val verified = expectedPackage == context.packageName &&
            expectedVersion > 0 &&
            actualVersion == expectedVersion
        Toast.makeText(
            context,
            if (verified) R.string.update_toast_success else R.string.update_toast_failed,
            if (verified) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
        ).show()
    }

    private fun installedVersion(context: Context, packageName: String): Long? = runCatching {
        versionCode(context.packageManager.getPackageInfo(packageName, 0))
    }.getOrNull()

    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    companion object {
        private const val LEGACY_PACKAGE_NAME_EXTRA = "package_name"
        private const val NO_PHONE_PACKAGE = "org.fossify.phone"
    }
}
