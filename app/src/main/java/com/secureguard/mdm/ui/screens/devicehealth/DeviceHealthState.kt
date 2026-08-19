package com.secureguard.mdm.ui.screens.devicehealth

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

enum class DeviceHealthStatus {
    ACTIVE,
    INACTIVE,
    UNKNOWN,
    CHECK_FAILED,
}

data class ProtectionHealth(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @DrawableRes val iconRes: Int,
    val isConfigured: Boolean?,
    val status: DeviceHealthStatus,
) {
    val displayStatus: DeviceHealthStatus
        get() = if (isConfigured == null) DeviceHealthStatus.CHECK_FAILED else status

    val needsAttention: Boolean
        get() = when {
            isConfigured == null -> true
            status == DeviceHealthStatus.CHECK_FAILED -> true
            status == DeviceHealthStatus.UNKNOWN -> isConfigured
            else -> isConfigured != (status == DeviceHealthStatus.ACTIVE)
        }
}

data class DeviceHealthState(
    val isRefreshing: Boolean = true,
    val deviceOwnerStatus: DeviceHealthStatus = DeviceHealthStatus.UNKNOWN,
    val adminActiveStatus: DeviceHealthStatus = DeviceHealthStatus.UNKNOWN,
    val vpnPermissionStatus: DeviceHealthStatus = DeviceHealthStatus.UNKNOWN,
    val vpnAlwaysOnStatus: DeviceHealthStatus = DeviceHealthStatus.UNKNOWN,
    val vpnActiveStatus: DeviceHealthStatus = DeviceHealthStatus.UNKNOWN,
    val protections: List<ProtectionHealth> = emptyList(),
) {
    val activeProtections: List<ProtectionHealth>
        get() = protections.filter {
            it.status == DeviceHealthStatus.ACTIVE && !it.needsAttention
        }

    val protectionsNeedingAttention: List<ProtectionHealth>
        get() = protections.filter(ProtectionHealth::needsAttention)

    val isHealthy: Boolean
        get() = listOf(
            deviceOwnerStatus,
            adminActiveStatus,
            vpnPermissionStatus,
            vpnAlwaysOnStatus,
            vpnActiveStatus,
        ).all { it == DeviceHealthStatus.ACTIVE } && protectionsNeedingAttention.isEmpty()
}
