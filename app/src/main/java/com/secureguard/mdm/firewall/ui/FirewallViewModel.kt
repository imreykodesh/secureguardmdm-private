package com.secureguard.mdm.firewall.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.features.impl.BlockInternetVpnFeature
import com.secureguard.mdm.firewall.data.ConnectionHistoryRepository
import com.secureguard.mdm.firewall.data.FirewallPolicyRepository
import com.secureguard.mdm.firewall.model.ConnectionDecision
import com.secureguard.mdm.firewall.model.ConnectionHistory
import com.secureguard.mdm.firewall.model.FirewallPolicyMode
import com.secureguard.mdm.firewall.model.FirewallProtocol
import com.secureguard.mdm.firewall.model.FirewallRule
import com.secureguard.mdm.firewall.model.FirewallRuleAction
import com.secureguard.mdm.firewall.model.FirewallRuleType
import com.secureguard.mdm.services.BlockerVpnService
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
    val isFirewallEligible: Boolean,
    val ineligibleReason: String? = null,
)

enum class HistoryDecisionFilter {
    ALL,
    ALLOWED,
    BLOCKED,
    MONITORED,
}

data class FirewallUiState(
    val apps: List<FirewallAppItem> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val persistedPackages: Set<String> = emptySet(),
    val hasDraftChanges: Boolean = false,
    val modes: Map<String, FirewallPolicyMode> = emptyMap(),
    val blockQuic: Set<String> = emptySet(),
    val blockDot: Set<String> = emptySet(),
    val rules: List<FirewallRule> = emptyList(),
    val history: List<ConnectionHistory> = emptyList(),
    val search: String = "",
    val historySearch: String = "",
    val historyDecisionFilter: HistoryDecisionFilter = HistoryDecisionFilter.ALL,
    val appsLoaded: Boolean = false,
    val policySnapshotLoaded: Boolean = false,
    val loading: Boolean = true,
    val capturePackageName: String? = null,
    val captureStartedAt: Long? = null,
    val isCapturePreparing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class FirewallViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FirewallPolicyRepository,
    private val historyRepository: ConnectionHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val devicePolicyManager: DevicePolicyManager,
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
                            policySnapshotLoaded = true,
                            loading = !state.appsLoaded,
                        )
                    } else {
                        state.copy(
                            selectedPackages = persistedPackages,
                            persistedPackages = persistedPackages,
                            modes = snapshot.policies.mapValues { it.value.policyMode },
                            blockQuic = snapshot.policies.values.filter { it.blockQuic }.mapTo(mutableSetOf()) { it.packageName },
                            blockDot = snapshot.policies.values.filter { it.blockDot }.mapTo(mutableSetOf()) { it.packageName },
                            rules = snapshot.rulesByPackage.values.flatten(),
                            policySnapshotLoaded = true,
                            loading = !state.appsLoaded,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            historyRepository.observeRecent().collectLatest { history ->
                _uiState.update { it.copy(history = history) }
            }
        }
    }

    fun setSearch(value: String) = _uiState.update { it.copy(search = value) }

    fun setHistorySearch(value: String) = _uiState.update { it.copy(historySearch = value) }

    fun setHistoryDecisionFilter(value: HistoryDecisionFilter) =
        _uiState.update { it.copy(historyDecisionFilter = value) }

    fun setSelected(packageName: String, selected: Boolean) {
        val currentState = _uiState.value
        if (!currentState.appsLoaded || !currentState.policySnapshotLoaded) {
            _uiState.update { it.copy(message = "רשימת האפליקציות והמדיניות עדיין נטענות") }
            return
        }
        val app = currentState.apps.firstOrNull { it.packageName == packageName }
        if (selected && app?.isFirewallEligible != true) {
            _uiState.update {
                it.copy(message = app?.ineligibleReason ?: "לא ניתן לשייך את האפליקציה באופן בטוח לחומת האש")
            }
            return
        }
        _uiState.update { state ->
            val selectedPackages = state.selectedPackages.toMutableSet().apply {
                if (selected) add(packageName) else remove(packageName)
            }
            val modes = state.modes.toMutableMap().apply {
                if (selected) putIfAbsent(packageName, FirewallPolicyMode.MONITOR_ONLY) else remove(packageName)
            }
            state.copy(selectedPackages = selectedPackages, modes = modes, hasDraftChanges = true)
        }
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

    fun startSimpleCapture(packageName: String) {
        val state = _uiState.value
        if (state.hasDraftChanges) {
            _uiState.update { it.copy(message = "יש לשמור תחילה את השינויים במסך המתקדם") }
            return
        }
        val app = state.apps.firstOrNull { it.packageName == packageName }
        if (app?.isFirewallEligible != true) {
            _uiState.update {
                it.copy(message = app?.ineligibleReason ?: "לא ניתן לצרף את האפליקציה ל-VPN")
            }
            return
        }
        _uiState.update { it.copy(isCapturePreparing = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                require(isPackageEligible(packageName)) { "לא ניתן לזהות את תעבורת האפליקציה בנפרד" }
                repository.ensurePackageForCapture(packageName)
                settingsRepository.setFeatureState(BlockInternetVpnFeature.id, true)
                BlockInternetVpnFeature.applyPolicy(
                    context,
                    devicePolicyManager,
                    SecureGuardDeviceAdminReceiver.getComponentName(context),
                    true,
                )
                System.currentTimeMillis()
            }.onSuccess { startedAt ->
                _uiState.update { current ->
                    val existingMode = current.modes[packageName]
                    current.copy(
                        selectedPackages = current.selectedPackages + packageName,
                        persistedPackages = current.persistedPackages + packageName,
                        modes = current.modes + (packageName to if (existingMode == FirewallPolicyMode.DISABLED || existingMode == null) {
                            FirewallPolicyMode.MONITOR_ONLY
                        } else {
                            existingMode
                        }),
                        capturePackageName = packageName,
                        captureStartedAt = startedAt,
                        isCapturePreparing = false,
                        message = "הלכידה הופעלה. פתח את האפליקציה והשתמש באתר שברצונך לחסום.",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isCapturePreparing = false,
                        message = "לא ניתן להתחיל לכידה: ${error.message}",
                    )
                }
            }
        }
    }

    fun blockSiteManually(packageName: String, input: String) {
        val domain = extractDomain(input)
        if (domain == null) {
            _uiState.update { it.copy(message = "יש להזין שם אתר, לדוגמה example.com") }
            return
        }
        blockSimpleSite(packageName, domain, source = "WIZARD_MANUAL")
    }

    fun blockCapturedSite(history: ConnectionHistory) {
        val domain = history.domain?.trim()?.takeIf { it.isNotEmpty() }
        if (domain == null) {
            _uiState.update { it.copy(message = "לא זוהה שם אתר בטוח לחסימה") }
            return
        }
        blockSimpleSite(history.packageName, domain, source = "WIZARD_CAPTURED")
    }

    fun reportVpnPermissionDenied() {
        _uiState.update { it.copy(message = "ללא אישור VPN לא ניתן ללכוד או לחסום את האתר") }
    }

    private fun blockSimpleSite(packageName: String, domain: String, source: String) {
        val state = _uiState.value
        if (state.hasDraftChanges) {
            _uiState.update { it.copy(message = "יש לשמור תחילה את השינויים במסך המתקדם") }
            return
        }
        val app = state.apps.firstOrNull { it.packageName == packageName }
        if (app?.isFirewallEligible != true) {
            _uiState.update { it.copy(message = app?.ineligibleReason ?: "לא ניתן להגן על האפליקציה") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            runCatching {
                require(isPackageEligible(packageName)) { "לא ניתן לזהות את תעבורת האפליקציה בנפרד" }
                repository.upsertSimpleBlockRule(
                    FirewallRule(
                        packageName = packageName,
                        ruleType = FirewallRuleType.DOMAIN_SUFFIX,
                        action = FirewallRuleAction.BLOCK,
                        value = domain,
                        protocol = FirewallProtocol.ANY,
                        priority = 200,
                        source = source,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                settingsRepository.setFeatureState(BlockInternetVpnFeature.id, true)
                BlockInternetVpnFeature.applyPolicy(
                    context,
                    devicePolicyManager,
                    SecureGuardDeviceAdminReceiver.getComponentName(context),
                    true,
                )
            }.onSuccess {
                _uiState.update { current ->
                    val currentMode = current.modes[packageName]
                    val effectiveMode = if (currentMode == FirewallPolicyMode.ALLOWLIST || currentMode == FirewallPolicyMode.BLOCKLIST) {
                        currentMode
                    } else {
                        FirewallPolicyMode.BLOCKLIST
                    }
                    current.copy(
                        selectedPackages = current.selectedPackages + packageName,
                        persistedPackages = current.persistedPackages + packageName,
                        modes = current.modes + (packageName to effectiveMode),
                        capturePackageName = null,
                        captureStartedAt = null,
                        message = "האתר $domain נחסם עבור ${app.name}",
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(message = "החסימה נכשלה: ${error.message}") }
            }
        }
    }

    private fun extractDomain(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        return Uri.parse(candidate).host
            ?.trim()
            ?.trimEnd('.')
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isPackageEligible(packageName: String): Boolean = runCatching {
        val packageManager = context.packageManager
        val app = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getPackagesForUid(app.uid).orEmpty().toSet() == setOf(packageName)
    }.getOrDefault(false)

    fun saveSelection() {
        val state = _uiState.value
        if (state.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            val packagesToPersist = state.selectedPackages.filterTo(mutableSetOf()) { packageName ->
                val app = state.apps.firstOrNull { it.packageName == packageName }
                app?.isFirewallEligible == true || packageName in state.persistedPackages
            }
            val skippedCount = state.selectedPackages.size - packagesToPersist.size
            repository.replaceSelectedPackages(packagesToPersist)
            packagesToPersist.forEach { packageName ->
                repository.updatePolicyMode(packageName, state.modes[packageName] ?: FirewallPolicyMode.MONITOR_ONLY)
                repository.updateTransportOptions(
                    packageName,
                    packageName in state.blockQuic,
                    packageName in state.blockDot,
                )
            }
            _uiState.update {
                it.copy(
                    selectedPackages = packagesToPersist,
                    persistedPackages = packagesToPersist,
                    modes = it.modes.filterKeys(packagesToPersist::contains),
                    hasDraftChanges = false,
                )
            }
            val skippedSuffix = if (skippedCount > 0) " $skippedCount אפליקציות עם UID משותף הוסרו מהבחירה." else ""
            val firewallEnabled = settingsRepository.getFeatureState(BlockInternetVpnFeature.id)
            if (!firewallEnabled) {
                _uiState.update { it.copy(message = "המדיניות נשמרה. הפעל את חומת האש מהמתג הראשי.$skippedSuffix") }
            } else if (VpnService.prepare(context) == null) {
                val intent = Intent(context, BlockerVpnService::class.java).apply {
                    action = BlockerVpnService.ACTION_REBUILD_INTERFACE
                }
                ContextCompat.startForegroundService(context, intent)
                val message = if (packagesToPersist.isEmpty()) {
                    "לא נבחרה אפליקציה כשירה; שירות חומת האש יכובה.$skippedSuffix"
                } else {
                    "המדיניות נשמרה וה-VPN נבנה מחדש.$skippedSuffix"
                }
                _uiState.update { it.copy(message = message) }
            } else {
                _uiState.update { it.copy(message = "המדיניות נשמרה. יש לאשר הרשאת VPN.$skippedSuffix") }
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

    fun quickBlock(history: ConnectionHistory) {
        val packageName = history.packageName
        if (packageName.isBlank() || packageName == "UNKNOWN") {
            _uiState.update { it.copy(message = "לא ניתן לחסום יעד ללא שיוך ודאי לאפליקציה") }
            return
        }
        if (packageName !in _uiState.value.persistedPackages) {
            _uiState.update { it.copy(message = "האפליקציה אינה חלק ממדיניות חומת האש") }
            return
        }

        val domain = history.domain?.trim()?.takeIf { it.isNotEmpty() }
        val ruleType = if (domain != null) FirewallRuleType.DOMAIN_EXACT else FirewallRuleType.IP_EXACT
        val value = domain ?: history.destinationIp
        if (value.isBlank()) {
            _uiState.update { it.copy(message = "לא נמצא domain או IP חוקי לחסימה") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            runCatching {
                repository.upsertRule(
                    FirewallRule(
                        packageName = packageName,
                        ruleType = ruleType,
                        action = FirewallRuleAction.BLOCK,
                        value = value,
                        protocol = FirewallProtocol.ANY,
                        priority = 200,
                        source = "RECENT_DESTINATION",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                if (_uiState.value.modes[packageName] == FirewallPolicyMode.MONITOR_ONLY) {
                    repository.updatePolicyMode(packageName, FirewallPolicyMode.BLOCKLIST)
                    _uiState.update { state ->
                        state.copy(modes = state.modes + (packageName to FirewallPolicyMode.BLOCKLIST))
                    }
                }
                requestRuleReload()
            }.onSuccess {
                _uiState.update { it.copy(message = "היעד $value נחסם עבור $packageName") }
            }.onFailure { error ->
                _uiState.update { it.copy(message = "החסימה נכשלה: ${error.message}") }
            }
        }
    }

    fun clearHistory() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { historyRepository.clear() }
            .onSuccess { _uiState.update { it.copy(message = "היסטוריית היעדים נמחקה") } }
            .onFailure { error ->
                _uiState.update { it.copy(message = "מחיקת ההיסטוריה נכשלה: ${error.message}") }
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
                    val packagesForUid = pm.getPackagesForUid(app.uid).orEmpty().toSet()
                    val isEligible = packagesForUid == setOf(app.packageName)
                    FirewallAppItem(
                        name = app.loadLabel(pm).toString(),
                        packageName = app.packageName,
                        icon = app.loadIcon(pm),
                        isSystemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        isFirewallEligible = isEligible,
                        ineligibleReason = if (isEligible) null else
                            "לא ניתן להגן על אפליקציה זו בנפרד משום שה-UID שלה משותף עם אפליקציות נוספות",
                    )
                }
                .sortedBy { it.name.lowercase() }
                .toList()
        }
        _uiState.update {
            it.copy(
                apps = installed,
                appsLoaded = true,
                loading = !it.policySnapshotLoaded,
            )
        }
    }

    private fun Set<String>.toggled(value: String, enabled: Boolean): Set<String> =
        toMutableSet().apply { if (enabled) add(value) else remove(value) }
}
