package com.secureguard.mdm.firewall.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.features.impl.BlockInternetVpnFeature
import com.secureguard.mdm.firewall.data.FirewallPolicyRepository
import com.secureguard.mdm.firewall.model.FirewallPolicyMode
import com.secureguard.mdm.firewall.model.FirewallProtocol
import com.secureguard.mdm.firewall.model.FirewallRule
import com.secureguard.mdm.firewall.model.FirewallRuleAction
import com.secureguard.mdm.firewall.model.FirewallRuleType
import com.secureguard.mdm.services.BlockerVpnService
import com.secureguard.mdm.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class FirewallAppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val isSystemApp: Boolean,
)

data class FirewallUiState(
    val apps: List<FirewallAppItem> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val persistedPackages: Set<String> = emptySet(),
    val hasDraftChanges: Boolean = false,
    val modes: Map<String, FirewallPolicyMode> = emptyMap(),
    val blockQuic: Set<String> = emptySet(),
    val blockDot: Set<String> = emptySet(),
    val rules: List<FirewallRule> = emptyList(),
    val search: String = "",
    val loading: Boolean = true,
    val message: String? = null,
)

@HiltViewModel
class FirewallViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FirewallPolicyRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FirewallUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadApps()
        viewModelScope.launch {
            repository.observeSnapshot().collectLatest { snapshot ->
                _uiState.update { state ->
                    val persistedPackages = snapshot.selectedPackages
                    if (state.hasDraftChanges) {
                        state.copy(
                            persistedPackages = persistedPackages,
                            rules = snapshot.rulesByPackage.values.flatten(),
                        )
                    } else {
                        state.copy(
                            selectedPackages = persistedPackages,
                            persistedPackages = persistedPackages,
                            modes = snapshot.policies.mapValues { it.value.policyMode },
                            blockQuic = snapshot.policies.values.filter { it.blockQuic }.mapTo(mutableSetOf()) { it.packageName },
                            blockDot = snapshot.policies.values.filter { it.blockDot }.mapTo(mutableSetOf()) { it.packageName },
                            rules = snapshot.rulesByPackage.values.flatten(),
                        )
                    }
                }
            }
        }
    }

    fun setSearch(value: String) = _uiState.update { it.copy(search = value) }

    fun setSelected(packageName: String, selected: Boolean) = _uiState.update { state ->
        val selectedPackages = state.selectedPackages.toMutableSet().apply {
            if (selected) add(packageName) else remove(packageName)
        }
        val modes = state.modes.toMutableMap().apply {
            if (selected) putIfAbsent(packageName, FirewallPolicyMode.MONITOR_ONLY) else remove(packageName)
        }
        state.copy(selectedPackages = selectedPackages, modes = modes, hasDraftChanges = true)
    }

    fun setMode(packageName: String, mode: FirewallPolicyMode) = _uiState.update {
        it.copy(modes = it.modes + (packageName to mode), hasDraftChanges = true)
    }

    fun setBlockQuic(packageName: String, enabled: Boolean) = _uiState.update {
        it.copy(blockQuic = it.blockQuic.toggled(packageName, enabled), hasDraftChanges = true)
    }

    fun setBlockDot(packageName: String, enabled: Boolean) = _uiState.update {
        it.copy(blockDot = it.blockDot.toggled(packageName, enabled), hasDraftChanges = true)
    }

    fun saveSelection() {
        val state = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            repository.replaceSelectedPackages(state.selectedPackages)
            state.selectedPackages.forEach { packageName ->
                repository.updatePolicyMode(packageName, state.modes[packageName] ?: FirewallPolicyMode.MONITOR_ONLY)
                repository.updateTransportOptions(
                    packageName,
                    packageName in state.blockQuic,
                    packageName in state.blockDot,
                )
            }
            _uiState.update {
                it.copy(
                    persistedPackages = state.selectedPackages,
                    hasDraftChanges = false,
                )
            }
            val firewallEnabled = settingsRepository.getFeatureState(BlockInternetVpnFeature.id)
            if (!firewallEnabled) {
                _uiState.update { it.copy(message = "המדיניות נשמרה. הפעל את חומת האש מהמתג הראשי") }
            } else if (VpnService.prepare(context) == null) {
                val intent = Intent(context, BlockerVpnService::class.java).apply {
                    action = BlockerVpnService.ACTION_REBUILD_INTERFACE
                }
                ContextCompat.startForegroundService(context, intent)
                _uiState.update { it.copy(message = "המדיניות נשמרה וה-VPN נבנה מחדש") }
            } else {
                _uiState.update { it.copy(message = "המדיניות נשמרה. יש לאשר הרשאת VPN") }
            }
        }
    }

    fun addRule(
        packageName: String,
        type: FirewallRuleType,
        action: FirewallRuleAction,
        value: String,
        protocol: FirewallProtocol,
        port: Int?,
    ) {
        if (packageName.isBlank() || value.isBlank()) return
        if (packageName !in _uiState.value.persistedPackages) {
            _uiState.update { it.copy(message = "שמור תחילה את בחירת האפליקציה") }
            return
        }
        val requiresPort = type in setOf(FirewallRuleType.PORT, FirewallRuleType.IP_PORT, FirewallRuleType.DOMAIN_PORT)
        if (requiresPort && port !in 1..65535) {
            _uiState.update { it.copy(message = "יש להזין פורט חוקי בין 1 ל-65535") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            runCatching {
                repository.upsertRule(
                    FirewallRule(
                        packageName = packageName,
                        ruleType = type,
                        action = action,
                        value = value,
                        protocol = protocol,
                        portStart = port,
                        portEnd = port,
                        priority = 100,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                requestRuleReload()
            }.onSuccess {
                _uiState.update { it.copy(message = "הכלל נשמר והופעל") }
            }.onFailure { error ->
                _uiState.update { it.copy(message = "כלל לא תקין: ${error.message}") }
            }
        }
    }

    fun deleteRule(ruleId: Long) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteRule(ruleId)
        requestRuleReload()
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    private suspend fun requestRuleReload() {
        if (!settingsRepository.getFeatureState(BlockInternetVpnFeature.id)) return
        if (VpnService.prepare(context) != null) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, BlockerVpnService::class.java).apply {
                action = BlockerVpnService.ACTION_RELOAD_RULES
            },
        )
    }

    private fun loadApps() = viewModelScope.launch {
        val installed = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filterNot { it.packageName == context.packageName }
                .map { app ->
                    FirewallAppItem(
                        name = app.loadLabel(pm).toString(),
                        packageName = app.packageName,
                        icon = app.loadIcon(pm),
                        isSystemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }
                .sortedBy { it.name.lowercase() }
                .toList()
        }
        _uiState.update { it.copy(apps = installed, loading = false) }
    }

    private fun Set<String>.toggled(value: String, enabled: Boolean): Set<String> =
        toMutableSet().apply { if (enabled) add(value) else remove(value) }
}
