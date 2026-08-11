package com.secureguard.mdm.features.impl

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.secureguard.mdm.R
import com.secureguard.mdm.features.api.ProtectionFeature
import com.secureguard.mdm.services.BlockerVpnService
import com.secureguard.mdm.services.NetfreeMonitorService
import com.secureguard.mdm.utils.JobSchedulerHelper

/** Enables the internal per-app firewall. Global lockdown is intentionally disabled. */
object BlockInternetVpnFeature : ProtectionFeature {
    override val id = "block_internet_vpn"
    override val titleRes = R.string.feature_vpn_title_new
    override val descriptionRes = R.string.feature_vpn_description_new
    override val iconRes = R.drawable.ic_cloud_off
    override val requiredSdkVersion = Build.VERSION_CODES.N

    override fun applyPolicy(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        enable: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (enable) {
                // NetFree's legacy global lockdown is mutually exclusive with per-app fail-open.
                JobSchedulerHelper.cancelWatchdog(context)
                NetfreeMonitorService.setServiceActive(context, false)
                context.stopService(Intent(context, NetfreeMonitorService::class.java))
                dpm.setAlwaysOnVpnPackage(admin, context.packageName, false)
            } else if (dpm.getAlwaysOnVpnPackage(admin) == context.packageName) {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
            }
        }
        val serviceIntent = Intent(context, BlockerVpnService::class.java).apply {
            action = if (enable) BlockerVpnService.ACTION_START else BlockerVpnService.ACTION_STOP
        }
        if (enable) ContextCompat.startForegroundService(context, serviceIntent) else context.startService(serviceIntent)
    }

    override fun isPolicyActive(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
    ): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
        context.packageName == dpm.getAlwaysOnVpnPackage(admin)
}
