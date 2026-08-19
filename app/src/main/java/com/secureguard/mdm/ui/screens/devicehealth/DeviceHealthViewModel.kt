package com.secureguard.mdm.ui.screens.devicehealth

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.features.registry.CategoryRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class DeviceHealthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val devicePolicyManager: DevicePolicyManager,
    private val connectivityManager: ConnectivityManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceHealthState())
    val uiState = _uiState.asStateFlow()

    private val adminComponent by lazy {
        SecureGuardDeviceAdminReceiver.getComponentName(context)
    }

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }

            val refreshedState = withContext(Dispatchers.IO) {
                val deviceOwnerStatus = statusOf {
                    devicePolicyManager.isDeviceOwnerApp(context.packageName)
                }
                val adminActiveStatus = statusOf {
                    devicePolicyManager.isAdminActive(adminComponent)
                }
                val vpnPermissionStatus = statusOf {
                    VpnService.prepare(context) == null
                }
                val vpnAlwaysOnStatus = checkAlwaysOnVpn()
                val vpnActiveStatus = checkActiveVpn()
                val protections = loadProtectionHealth()

                DeviceHealthState(
                    isRefreshing = false,
                    deviceOwnerStatus = deviceOwnerStatus,
                    adminActiveStatus = adminActiveStatus,
                    vpnPermissionStatus = vpnPermissionStatus,
                    vpnAlwaysOnStatus = vpnAlwaysOnStatus,
                    vpnActiveStatus = vpnActiveStatus,
                    protections = protections,
                )
            }

            _uiState.value = refreshedState
        }
    }

    private fun checkAlwaysOnVpn(): DeviceHealthStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return DeviceHealthStatus.UNKNOWN
        }
        return statusOf {
            devicePolicyManager.getAlwaysOnVpnPackage(adminComponent) == context.packageName
        }
    }

    private fun checkActiveVpn(): DeviceHealthStatus = runCatching {
        val vpnCapabilities = connectivityManager.allNetworks.mapNotNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        }

        when {
            vpnCapabilities.isEmpty() -> DeviceHealthStatus.INACTIVE
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> DeviceHealthStatus.UNKNOWN
            vpnCapabilities.any { it.ownerUid == context.applicationInfo.uid } ->
                DeviceHealthStatus.ACTIVE
            vpnCapabilities.all { it.ownerUid < 0 } -> DeviceHealthStatus.UNKNOWN
            else -> DeviceHealthStatus.INACTIVE
        }
    }.getOrElse { DeviceHealthStatus.CHECK_FAILED }

    private suspend fun loadProtectionHealth(): List<ProtectionHealth> {
        return CategoryRegistry.allCategories.flatMap { category ->
            category.features.map { feature ->
                val configured = try {
                    settingsRepository.getFeatureState(feature.id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    null
                }

                val policyStatus = runCatching {
                    feature.isPolicyActive(context, devicePolicyManager, adminComponent)
                }.fold(
                    onSuccess = ::booleanStatus,
                    onFailure = { DeviceHealthStatus.CHECK_FAILED },
                )

                ProtectionHealth(
                    id = feature.id,
                    titleRes = feature.titleRes,
                    descriptionRes = feature.descriptionRes,
                    iconRes = feature.iconRes,
                    isConfigured = configured,
                    status = policyStatus,
                )
            }
        }
    }

    private inline fun statusOf(check: () -> Boolean): DeviceHealthStatus =
        runCatching(check).fold(
            onSuccess = ::booleanStatus,
            onFailure = { DeviceHealthStatus.CHECK_FAILED },
        )

    private fun booleanStatus(active: Boolean): DeviceHealthStatus =
        if (active) DeviceHealthStatus.ACTIVE else DeviceHealthStatus.INACTIVE
}
