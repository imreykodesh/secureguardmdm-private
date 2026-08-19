package com.secureguard.mdm.firewall.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.secureguard.mdm.firewall.model.ConnectionDecision
import com.secureguard.mdm.firewall.model.ConnectionHistory
import com.secureguard.mdm.firewall.model.FirewallPolicyMode
import com.secureguard.mdm.firewall.model.FirewallProtocol
import com.secureguard.mdm.firewall.model.FirewallRuleAction
import com.secureguard.mdm.firewall.model.FirewallRuleType
import java.text.DateFormat
import java.util.Date

private enum class FirewallSection { CONFIGURATION, HISTORY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallScreen(
    onNavigateBack: () -> Unit,
    viewModel: FirewallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showSimpleWizard by remember { mutableStateOf(false) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(FirewallSection.CONFIGURATION) }
    var pendingVpnAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val action = pendingVpnAction
        pendingVpnAction = null
        if (result.resultCode == Activity.RESULT_OK) {
            action?.invoke()
        } else {
            viewModel.reportVpnPermissionDenied()
        }
    }
    val runWithVpnPermission: (() -> Unit) -> Unit = { action ->
        val permissionIntent = VpnService.prepare(context)
        if (permissionIntent == null) {
            action()
        } else {
            pendingVpnAction = action
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("סינון רשת לפי אפליקציה") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
                    }
                },
                actions = {
                    if (section == FirewallSection.CONFIGURATION) {
                        IconButton(
                            onClick = { showSimpleWizard = true },
                            enabled = !state.loading && !state.hasDraftChanges,
                        ) { Icon(Icons.Default.Add, contentDescription = "חסום אתר באפליקציה") }
                    } else {
                        IconButton(
                            onClick = { showClearHistoryDialog = true },
                            enabled = state.history.isNotEmpty(),
                        ) { Icon(Icons.Default.Delete, contentDescription = "נקה היסטוריה") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (section == FirewallSection.CONFIGURATION) {
                FloatingActionButton(onClick = viewModel::saveSelection) {
                    Icon(Icons.Default.Save, contentDescription = "שמור והפעל")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = viewModel::clearMessage).padding(12.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { section = FirewallSection.CONFIGURATION },
                    enabled = section != FirewallSection.CONFIGURATION,
                    modifier = Modifier.weight(1f),
                ) { Text("אפליקציות וכללים") }
                Button(
                    onClick = { section = FirewallSection.HISTORY },
                    enabled = section != FirewallSection.HISTORY,
                    modifier = Modifier.weight(1f),
                ) { Text("יעדים אחרונים (${state.history.size})") }
            }

            when (section) {
                FirewallSection.CONFIGURATION -> FirewallConfiguration(
                    state = state,
                    viewModel = viewModel,
                    onSimpleBlock = { showSimpleWizard = true },
                    onAddRule = { showRuleDialog = true },
                )
                FirewallSection.HISTORY -> RecentDestinations(
                    state = state,
                    onSearch = viewModel::setHistorySearch,
                    onFilter = viewModel::setHistoryDecisionFilter,
                    onQuickBlock = viewModel::quickBlock,
                )
            }
        }
    }

    if (showSimpleWizard) {
        SimpleBlockSiteWizard(
            apps = state.apps,
            history = state.history,
            capturePackageName = state.capturePackageName,
            captureStartedAt = state.captureStartedAt,
            isCapturePreparing = state.isCapturePreparing,
            onDismiss = { showSimpleWizard = false },
            onStartCapture = { packageName ->
                runWithVpnPermission { viewModel.startSimpleCapture(packageName) }
            },
            onManualBlock = { packageName, site ->
                runWithVpnPermission {
                    viewModel.blockSiteManually(packageName, site)
                    showSimpleWizard = false
                }
            },
            onCapturedBlock = { target ->
                viewModel.blockCapturedSite(target)
                showSimpleWizard = false
            },
            onLaunchApp = { packageName ->
                context.packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                    runCatching { context.startActivity(launchIntent) }
                }
            },
        )
    }

    if (showRuleDialog) {
        AddRuleDialog(
            apps = state.persistedPackages.sorted().map { packageName ->
                RuleAppOption(
                    packageName = packageName,
                    displayName = state.apps.firstOrNull { it.packageName == packageName }?.name ?: packageName,
                )
            },
            onDismiss = { showRuleDialog = false },
            onSave = { packageName, type, action, value, protocol, port ->
                viewModel.addRule(packageName, type, action, value, protocol, port)
                showRuleDialog = false
            },
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("מחיקת היסטוריית יעדים") },
            text = { Text("למחוק לצמיתות את כל היעדים שנקלטו במכשיר?") },
            confirmButton = {
                Button(onClick = {
                    viewModel.clearHistory()
                    showClearHistoryDialog = false
                }) { Text("מחק הכול") }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text("ביטול") }
            },
        )
    }
}

@Composable
private fun FirewallConfiguration(
    state: FirewallUiState,
    viewModel: FirewallViewModel,
    onSimpleBlock: () -> Unit,
    onAddRule: () -> Unit,
) {
    OutlinedTextField(
        value = state.search,
        onValueChange = viewModel::setSearch,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        label = { Text("חיפוש אפליקציה או package") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
    )
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val query = state.search.trim().lowercase()
    val matchingApps = state.apps.filter {
        query.isEmpty() || it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
    }
    val configuredApps = matchingApps.filter { it.packageName in state.persistedPackages }
    val availableApps = matchingApps.filterNot { it.packageName in state.persistedPackages }
    val installedPackages = state.apps.mapTo(mutableSetOf()) { it.packageName }
    val unavailableConfiguredPackages = state.persistedPackages
        .filterNot(installedPackages::contains)
        .filter { query.isEmpty() || it.lowercase().contains(query) }
        .sorted()
    val matchingRules = state.rules.filter { rule ->
        query.isEmpty() ||
            rule.packageName.lowercase().contains(query) ||
            rule.value.lowercase().contains(query)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "בחר אפליקציות שייכנסו ל-VPN. אפליקציות שלא נבחרו אינן מושפעות.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            Button(
                onClick = onSimpleBlock,
                enabled = !state.hasDraftChanges,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text("חסום אתר באפליקציה – תהליך פשוט")
            }
        }
        item {
            TextButton(
                onClick = onAddRule,
                enabled = state.persistedPackages.isNotEmpty(),
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("הוסף כלל מתקדם")
            }
        }
        if (configuredApps.isNotEmpty() || unavailableConfiguredPackages.isNotEmpty()) {
            item {
                Text(
                    "אפליקציות מוגדרות (${state.persistedPackages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(configuredApps, key = { "configured:${it.packageName}" }) { app ->
                val selected = app.packageName in state.selectedPackages
                FirewallAppRow(
                    app = app,
                    selected = selected,
                    mode = state.modes[app.packageName] ?: FirewallPolicyMode.MONITOR_ONLY,
                    blockQuic = app.packageName in state.blockQuic,
                    blockDot = app.packageName in state.blockDot,
                    onSelected = { viewModel.setSelected(app.packageName, it) },
                    onMode = { viewModel.setMode(app.packageName, it) },
                    onBlockQuic = { viewModel.setBlockQuic(app.packageName, it) },
                    onBlockDot = { viewModel.setBlockDot(app.packageName, it) },
                )
                HorizontalDivider()
            }
            items(unavailableConfiguredPackages, key = { "unavailable:$it" }) { packageName ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(packageName)
                    Text(
                        "המדיניות שמורה, אך האפליקציה אינה זמינה כרגע בפרופיל זה",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
        }
        if (matchingRules.isNotEmpty()) {
            item {
                Text(
                    "כללים פעילים (${matchingRules.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(matchingRules, key = { "rule:${it.id}" }) { rule ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${rule.action}: ${rule.value}")
                        Text(
                            "${rule.packageName} · ${rule.ruleType} · ${rule.protocol}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = { viewModel.deleteRule(rule.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "מחק כלל")
                    }
                }
            }
        } else if (query.isNotEmpty()) {
            item {
                Text(
                    "לא נמצאו כללים פעילים התואמים לחיפוש",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (availableApps.isNotEmpty()) {
            item {
                Text(
                    if (query.isEmpty()) "אפליקציות נוספות" else "אפליקציות תואמות נוספות",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(availableApps, key = { "app:${it.packageName}" }) { app ->
                val selected = app.packageName in state.selectedPackages
                FirewallAppRow(
                    app = app,
                    selected = selected,
                    mode = state.modes[app.packageName] ?: FirewallPolicyMode.MONITOR_ONLY,
                    blockQuic = app.packageName in state.blockQuic,
                    blockDot = app.packageName in state.blockDot,
                    onSelected = { viewModel.setSelected(app.packageName, it) },
                    onMode = { viewModel.setMode(app.packageName, it) },
                    onBlockQuic = { viewModel.setBlockQuic(app.packageName, it) },
                    onBlockDot = { viewModel.setBlockDot(app.packageName, it) },
                )
                HorizontalDivider()
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun RecentDestinations(
    state: FirewallUiState,
    onSearch: (String) -> Unit,
    onFilter: (HistoryDecisionFilter) -> Unit,
    onQuickBlock: (ConnectionHistory) -> Unit,
) {
    OutlinedTextField(
        value = state.historySearch,
        onValueChange = onSearch,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        label = { Text("חיפוש אפליקציה, domain או IP") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
    )
    HistoryFilterSelector(state.historyDecisionFilter, onFilter)

    val query = state.historySearch.trim().lowercase()
    val filtered = state.history.filter { history ->
        val matchesDecision = when (state.historyDecisionFilter) {
            HistoryDecisionFilter.ALL -> true
            HistoryDecisionFilter.ALLOWED -> history.lastDecision == ConnectionDecision.ALLOWED
            HistoryDecisionFilter.BLOCKED -> history.lastDecision == ConnectionDecision.BLOCKED
            HistoryDecisionFilter.MONITORED -> history.lastDecision == ConnectionDecision.MONITORED
        }
        val matchesQuery = query.isEmpty() ||
            history.packageName.lowercase().contains(query) ||
            history.domain?.lowercase()?.contains(query) == true ||
            history.destinationIp.lowercase().contains(query) ||
            history.normalizedDestination.lowercase().contains(query)
        matchesDecision && matchesQuery
    }

    if (filtered.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (state.history.isEmpty()) "טרם נקלטו יעדי רשת" else "לא נמצאו יעדים תואמים")
        }
        return
    }

    val appNames = remember(state.apps) { state.apps.associate { it.packageName to it.name } }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "נשמרים metadata בלבד: יעד, פורט, פרוטוקול והחלטה. תוכן התעבורה אינו נשמר.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(filtered, key = { it.id }) { history ->
            RecentDestinationRow(
                history = history,
                appName = appNames[history.packageName],
                lastSeen = dateFormat.format(Date(history.lastSeenAt)),
                onQuickBlock = { onQuickBlock(history) },
            )
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HistoryFilterSelector(
    selected: HistoryDecisionFilter,
    onSelect: (HistoryDecisionFilter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        HistoryDecisionFilter.ALL to "הכול",
        HistoryDecisionFilter.ALLOWED to "מותר",
        HistoryDecisionFilter.BLOCKED to "חסום",
        HistoryDecisionFilter.MONITORED to "נוטר",
    )
    Box(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { expanded = true }) { Text("החלטה: ${labels.getValue(selected)}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            HistoryDecisionFilter.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labels.getValue(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RecentDestinationRow(
    history: ConnectionHistory,
    appName: String?,
    lastSeen: String,
    onQuickBlock: () -> Unit,
) {
    val destination = history.domain ?: history.destinationIp
    val decisionLabel = when (history.lastDecision) {
        ConnectionDecision.ALLOWED -> "מותר"
        ConnectionDecision.BLOCKED -> "חסום"
        ConnectionDecision.MONITORED -> "נוטר"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(destination, style = MaterialTheme.typography.titleSmall)
            if (history.domain != null && history.destinationIp.isNotBlank()) {
                Text(history.destinationIp, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "${appName ?: history.packageName} · ${history.packageName}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "פורט ${history.destinationPort} · ${history.protocol} · $decisionLabel · ${history.metadataSource}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${history.networkType} · ${history.connectionCount} חיבורים · נראה לאחרונה $lastSeen",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = onQuickBlock,
            enabled = history.packageName.isNotBlank() && history.packageName != "UNKNOWN" &&
                history.lastDecision != ConnectionDecision.BLOCKED,
        ) { Text("חסום") }
    }
}

@Composable
private fun FirewallAppRow(
    app: FirewallAppItem,
    selected: Boolean,
    mode: FirewallPolicyMode,
    blockQuic: Boolean,
    blockDot: Boolean,
    onSelected: (Boolean) -> Unit,
    onMode: (FirewallPolicyMode) -> Unit,
    onBlockQuic: (Boolean) -> Unit,
    onBlockDot: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(rememberDrawablePainter(app.icon), null, Modifier.size(40.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(app.name)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                app.ineligibleReason?.let { reason ->
                    Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            Checkbox(
                checked = selected,
                onCheckedChange = onSelected,
                enabled = app.isFirewallEligible || selected,
            )
        }
        if (selected && app.isFirewallEligible) {
            ModeSelector(mode, onMode)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("חסום QUIC (UDP/443)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = blockQuic, onCheckedChange = onBlockQuic)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("חסום DNS מוצפן (פורט 853)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = blockDot, onCheckedChange = onBlockDot)
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: FirewallPolicyMode, onMode: (FirewallPolicyMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (mode) {
        FirewallPolicyMode.MONITOR_ONLY -> "ניטור בלבד"
        FirewallPolicyMode.BLOCKLIST -> "חסום רק לפי כללי BLOCK"
        FirewallPolicyMode.ALLOWLIST -> "חסום הכול מלבד כללי ALLOW"
        FirewallPolicyMode.DISABLED -> "מושבת"
    }
    Box {
        TextButton(onClick = { expanded = true }) { Text("מצב: $label") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FirewallPolicyMode.entries.filterNot { it == FirewallPolicyMode.DISABLED }.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = { onMode(option); expanded = false },
                )
            }
        }
    }
}

private enum class SimpleWizardStep { CHOOSE_APP, CHOOSE_METHOD, MANUAL, CAPTURE }

@Composable
private fun SimpleBlockSiteWizard(
    apps: List<FirewallAppItem>,
    history: List<ConnectionHistory>,
    capturePackageName: String?,
    captureStartedAt: Long?,
    isCapturePreparing: Boolean,
    onDismiss: () -> Unit,
    onStartCapture: (String) -> Unit,
    onManualBlock: (String, String) -> Unit,
    onCapturedBlock: (ConnectionHistory) -> Unit,
    onLaunchApp: (String) -> Unit,
) {
    var step by remember { mutableStateOf(SimpleWizardStep.CHOOSE_APP) }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var siteInput by remember { mutableStateOf("") }
    val selectedApp = apps.firstOrNull { it.packageName == selectedPackage }
    val normalizedQuery = query.trim().lowercase()
    val filteredApps = apps
        .filter { app ->
            normalizedQuery.isEmpty() ||
                app.name.lowercase().contains(normalizedQuery) ||
                app.packageName.lowercase().contains(normalizedQuery)
        }
        .sortedWith(compareBy<FirewallAppItem> { it.isSystemApp }.thenBy { it.name.lowercase() })
    val capturedTargets = if (
        selectedPackage != null &&
        capturePackageName == selectedPackage &&
        captureStartedAt != null
    ) {
        history.asSequence()
            .filter { it.packageName == selectedPackage && it.lastSeenAt >= captureStartedAt }
            .filter { !it.domain.isNullOrBlank() }
            .sortedByDescending { it.lastSeenAt }
            .distinctBy { it.domain?.lowercase() }
            .take(50)
            .toList()
    } else {
        emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (step) {
                    SimpleWizardStep.CHOOSE_APP -> "באיזו אפליקציה לחסום אתר?"
                    SimpleWizardStep.CHOOSE_METHOD -> selectedApp?.name ?: "חסימת אתר"
                    SimpleWizardStep.MANUAL -> "איזה אתר לחסום?"
                    SimpleWizardStep.CAPTURE -> "זיהוי האתר באופן אוטומטי"
                },
            )
        },
        text = {
            when (step) {
                SimpleWizardStep.CHOOSE_APP -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("בחר אפליקציה. אין צורך להבין VPN או כללי רשת.")
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("חיפוש אפליקציה") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(390.dp)) {
                        items(filteredApps, key = { "wizard-app:${it.packageName}" }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = app.isFirewallEligible) {
                                        selectedPackage = app.packageName
                                        step = SimpleWizardStep.CHOOSE_METHOD
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(
                                    painter = rememberDrawablePainter(app.icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                )
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(app.name)
                                    if (app.isSystemApp) {
                                        Text("אפליקציית מערכת", style = MaterialTheme.typography.bodySmall)
                                    }
                                    app.ineligibleReason?.let { reason ->
                                        Text(
                                            text = reason,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                Text(
                                    text = if (app.isFirewallEligible) "בחר" else "לא זמין",
                                    color = if (app.isFirewallEligible) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }

                SimpleWizardStep.CHOOSE_METHOD -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("איך תרצה לבחור את האתר שייחסם ב-${selectedApp?.name.orEmpty()}?")
                    Button(
                        onClick = { step = SimpleWizardStep.MANUAL },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("אני יודע את שם האתר")
                    }
                    Button(
                        onClick = { step = SimpleWizardStep.CAPTURE },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("אני לא יודע – זהה את האתר עבורי")
                    }
                    Text(
                        "באפשרות השנייה האפליקציה תזהה את האתרים שאליהם נעשה חיבור, ואתה תבחר מהרשימה.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SimpleWizardStep.MANUAL -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("אפשר לכתוב רק שם, או להדביק כתובת מלאה. לדוגמה: youtube.com")
                    OutlinedTextField(
                        value = siteInput,
                        onValueChange = { siteInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("שם האתר") },
                        singleLine = true,
                    )
                    Text(
                        "המערכת תצרף את האפליקציה ל-VPN, תבחר את מצב החסימה המתאים ותשמור הכול אוטומטית.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SimpleWizardStep.CAPTURE -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (capturePackageName != selectedPackage || captureStartedAt == null) {
                        Text("לחץ על התחלת לכידה. לאחר מכן פתח את האפליקציה והיכנס לאתר שברצונך לחסום.")
                        Button(
                            onClick = { selectedPackage?.let(onStartCapture) },
                            enabled = selectedPackage != null && !isCapturePreparing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (isCapturePreparing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text("התחל לכידה")
                            }
                        }
                    } else {
                        Text("הלכידה פעילה ומתעדכנת אוטומטית.")
                        Button(
                            onClick = { selectedPackage?.let(onLaunchApp) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("פתח את ${selectedApp?.name.orEmpty()}")
                        }
                        Text(
                            "גלוש באפליקציה למקום הרצוי, חזור לכאן ובחר את האתר מהרשימה. אם האפליקציה אינה נפתחת, פתח אותה ממסך הבית.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (capturedTargets.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(120.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("עדיין לא זוהה אתר. פתח את האפליקציה, השתמש בה וחזור לכאן.")
                            }
                        } else {
                            Text("אתרים שזוהו – לחץ על האתר שברצונך לחסום:")
                            LazyColumn(modifier = Modifier.fillMaxWidth().height(250.dp)) {
                                items(capturedTargets, key = { "captured:${it.id}" }) { target ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onCapturedBlock(target) }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(target.domain.orEmpty())
                                            Text(
                                                "זוהה ${target.connectionCount} פעמים",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        Text("חסום", color = MaterialTheme.colorScheme.primary)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (step == SimpleWizardStep.MANUAL) {
                Button(
                    onClick = {
                        val packageName = selectedPackage
                        if (packageName != null) onManualBlock(packageName, siteInput)
                    },
                    enabled = selectedPackage != null && siteInput.isNotBlank(),
                ) {
                    Text("חסום עכשיו")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("סגור") }
            }
        },
        dismissButton = {
            if (step != SimpleWizardStep.CHOOSE_APP) {
                TextButton(
                    onClick = {
                        step = when (step) {
                            SimpleWizardStep.CHOOSE_METHOD -> SimpleWizardStep.CHOOSE_APP
                            SimpleWizardStep.MANUAL, SimpleWizardStep.CAPTURE -> SimpleWizardStep.CHOOSE_METHOD
                            SimpleWizardStep.CHOOSE_APP -> SimpleWizardStep.CHOOSE_APP
                        }
                    },
                ) {
                    Text("חזרה")
                }
            }
        },
    )
}

private data class RuleAppOption(
    val packageName: String,
    val displayName: String,
)

private data class LabeledOption(
    val value: String,
    val label: String,
)

@Composable
private fun AddRuleDialog(
    apps: List<RuleAppOption>,
    onDismiss: () -> Unit,
    onSave: (String, FirewallRuleType, FirewallRuleAction, String, FirewallProtocol, Int?) -> Unit,
) {
    var packageName by remember { mutableStateOf(apps.firstOrNull()?.packageName.orEmpty()) }
    var type by remember { mutableStateOf(FirewallRuleType.DOMAIN_SUFFIX) }
    var action by remember { mutableStateOf(FirewallRuleAction.BLOCK) }
    var protocol by remember { mutableStateOf(FirewallProtocol.ANY) }
    var value by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    val appOptions = apps.map { LabeledOption(it.packageName, it.displayName) }
    val typeOptions = listOf(
        LabeledOption(FirewallRuleType.DOMAIN_SUFFIX.name, "אתר וכל כתובות המשנה שלו"),
        LabeledOption(FirewallRuleType.DOMAIN_EXACT.name, "כתובת אתר מדויקת בלבד"),
        LabeledOption(FirewallRuleType.IP_EXACT.name, "כתובת IP"),
        LabeledOption(FirewallRuleType.CIDR.name, "טווח כתובות IP (CIDR)"),
        LabeledOption(FirewallRuleType.PORT.name, "פורט"),
        LabeledOption(FirewallRuleType.IP_PORT.name, "כתובת IP ופורט"),
        LabeledOption(FirewallRuleType.DOMAIN_PORT.name, "אתר ופורט"),
    )
    val actionOptions = listOf(
        LabeledOption(FirewallRuleAction.BLOCK.name, "חסום"),
        LabeledOption(FirewallRuleAction.ALLOW.name, "אפשר"),
    )
    val protocolOptions = listOf(
        LabeledOption(FirewallProtocol.ANY.name, "הכול (מומלץ)"),
        LabeledOption(FirewallProtocol.TCP.name, "TCP"),
        LabeledOption(FirewallProtocol.UDP.name, "UDP"),
    )
    val requiresPort = type in setOf(
        FirewallRuleType.PORT,
        FirewallRuleType.IP_PORT,
        FirewallRuleType.DOMAIN_PORT,
    )
    val valueLabel = when (type) {
        FirewallRuleType.DOMAIN_EXACT,
        FirewallRuleType.DOMAIN_SUFFIX,
        FirewallRuleType.DOMAIN_PORT -> "כתובת אתר, לדוגמה example.com"
        FirewallRuleType.IP_EXACT,
        FirewallRuleType.IP_PORT -> "כתובת IP"
        FirewallRuleType.CIDR -> "טווח IP, לדוגמה 192.168.1.0/24"
        FirewallRuleType.PORT -> "שם קצר לכלל"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הוספת חסימה לאפליקציה") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "בחר אפליקציה והקלד את האתר שברצונך לחסום.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LabeledSelector("אפליקציה", packageName, appOptions) { packageName = it }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(valueLabel) },
                    supportingText = {
                        if (!showAdvanced) Text("יש להזין כתובת ללא https:// וללא נתיב")
                    },
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        if (showAdvanced) {
                            type = FirewallRuleType.DOMAIN_SUFFIX
                            action = FirewallRuleAction.BLOCK
                            protocol = FirewallProtocol.ANY
                            port = ""
                        }
                        showAdvanced = !showAdvanced
                    },
                ) {
                    Text(if (showAdvanced) "הסתר אפשרויות מתקדמות" else "אפשרויות מתקדמות")
                }
                if (showAdvanced) {
                    Text(
                        "שינוי ההגדרות הבאות מיועד לכללי רשת מיוחדים.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LabeledSelector("פעולה", action.name, actionOptions) {
                        action = FirewallRuleAction.valueOf(it)
                    }
                    LabeledSelector("סוג יעד", type.name, typeOptions) {
                        type = FirewallRuleType.valueOf(it)
                        if (type !in setOf(FirewallRuleType.PORT, FirewallRuleType.IP_PORT, FirewallRuleType.DOMAIN_PORT)) {
                            port = ""
                        }
                    }
                    LabeledSelector("פרוטוקול", protocol.name, protocolOptions) {
                        protocol = FirewallProtocol.valueOf(it)
                    }
                    if (requiresPort) {
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit) },
                            label = { Text("פורט, 1–65535") },
                            singleLine = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            val validPort = !requiresPort || port.toIntOrNull() in 1..65535
            Button(
                onClick = { onSave(packageName, type, action, value.trim(), protocol, port.toIntOrNull()) },
                enabled = packageName.isNotBlank() && value.isNotBlank() && validPort,
            ) {
                Text(if (showAdvanced) "שמור כלל" else "חסום אתר")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

@Composable
private fun LabeledSelector(
    label: String,
    selectedValue: String,
    options: List<LabeledOption>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label ?: selectedValue
    Box {
        TextButton(onClick = { expanded = true }) { Text("$label: $selectedLabel") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}
