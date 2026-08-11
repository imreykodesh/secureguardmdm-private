package com.secureguard.mdm.firewall.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
    var showRuleDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(FirewallSection.CONFIGURATION) }

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
                            onClick = { showRuleDialog = true },
                            enabled = state.persistedPackages.isNotEmpty(),
                        ) { Icon(Icons.Default.Add, contentDescription = "הוסף כלל") }
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

    if (showRuleDialog) {
        AddRuleDialog(
            packages = state.persistedPackages.sorted(),
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
    val apps = state.apps.filter {
        query.isEmpty() || it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "בחר אפליקציות שייכנסו ל-VPN. אפליקציות שלא נבחרו אינן מושפעות.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(apps, key = { "app:${it.packageName}" }) { app ->
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
        if (state.rules.isNotEmpty()) {
            item {
                Text("כללים פעילים", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
            items(state.rules, key = { "rule:${it.id}" }) { rule ->
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

@Composable
private fun AddRuleDialog(
    packages: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, FirewallRuleType, FirewallRuleAction, String, FirewallProtocol, Int?) -> Unit,
) {
    var packageName by remember { mutableStateOf(packages.firstOrNull().orEmpty()) }
    var type by remember { mutableStateOf(FirewallRuleType.DOMAIN_SUFFIX) }
    var action by remember { mutableStateOf(FirewallRuleAction.BLOCK) }
    var protocol by remember { mutableStateOf(FirewallProtocol.ANY) }
    var value by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הוספת כלל רשת") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EnumSelector("אפליקציה", packageName, packages) { packageName = it }
                EnumSelector("סוג", type.name, listOf("DOMAIN_EXACT", "DOMAIN_SUFFIX", "IP_EXACT", "CIDR", "PORT", "IP_PORT", "DOMAIN_PORT")) {
                    type = FirewallRuleType.valueOf(it)
                }
                EnumSelector("פעולה", action.name, FirewallRuleAction.entries.map { it.name }) {
                    action = FirewallRuleAction.valueOf(it)
                }
                EnumSelector("פרוטוקול", protocol.name, FirewallProtocol.entries.map { it.name }) {
                    protocol = FirewallProtocol.valueOf(it)
                }
                OutlinedTextField(value, { value = it }, label = { Text("domain / IP / CIDR") }, singleLine = true)
                if (type in setOf(FirewallRuleType.PORT, FirewallRuleType.IP_PORT, FirewallRuleType.DOMAIN_PORT)) {
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("פורט") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            val validPort = type !in setOf(FirewallRuleType.PORT, FirewallRuleType.IP_PORT, FirewallRuleType.DOMAIN_PORT) ||
                port.toIntOrNull() in 1..65535
            Button(
                onClick = { onSave(packageName, type, action, value, protocol, port.toIntOrNull()) },
                enabled = value.isNotBlank() && validPort,
            ) {
                Text("שמור כלל")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

@Composable
private fun EnumSelector(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("$label: $selected") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}
