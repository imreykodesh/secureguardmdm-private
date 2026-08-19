package com.secureguard.mdm.appblocker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.secureguard.mdm.R
import com.secureguard.mdm.appblocker.AppBlockerEvent
import com.secureguard.mdm.appblocker.AppBlockerViewModel
import com.secureguard.mdm.appblocker.AppFilterType
import com.secureguard.mdm.appblocker.AppInfo
import com.secureguard.mdm.appblocker.AppStatusFilter
import com.secureguard.mdm.ui.components.AppCenterTab
import com.secureguard.mdm.ui.components.AppCenterTabs
import com.secureguard.mdm.ui.components.PasswordPromptDialog

/**
 * Single protection screen: choose what to block or suspend, review what is
 * already restricted, release it, and remove apps.
 *
 * Blocking and "blocked apps" used to be two screens with almost the same list,
 * which made it unclear where a change would land. They are now one list with a
 * status filter. Removal lives here rather than next to the updates, so a
 * destructive tap is not one pixel away from a maintenance tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen(
    viewModel: AppBlockerViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToUpdates: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddPackageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.onEvent(AppBlockerEvent.OnClearMessage)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_center_tab_protection)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.dialog_button_cancel),
                        )
                    }
                },
                actions = {
                    if (uiState.accessGranted) {
                        IconButton(onClick = { showAddPackageDialog = true }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(id = R.string.app_selection_desc_add_manual),
                            )
                        }
                        if (uiState.statusFilter == AppStatusFilter.ALL) {
                            FilterMenu(
                                currentFilter = uiState.currentFilter,
                                onFilterSelected = { viewModel.onEvent(AppBlockerEvent.OnFilterChanged(it)) },
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            AppCenterTabs(
                selected = AppCenterTab.PROTECTION,
                onSelect = { tab ->
                    when (tab) {
                        AppCenterTab.UPDATES -> onNavigateToUpdates()
                        AppCenterTab.PROTECTION -> Unit
                    }
                },
            )

            if (!uiState.accessGranted) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(id = R.string.app_center_protection_locked),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
            StatusFilterRow(
                selected = uiState.statusFilter,
                blockedCount = uiState.blockedCount,
                suspendedCount = uiState.suspendedCount,
                onSelect = { viewModel.onEvent(AppBlockerEvent.OnStatusFilterChanged(it)) },
            )

            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onEvent(AppBlockerEvent.OnSearchQueryChanged(it)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text(stringResource(id = R.string.app_selection_label_search)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(id = R.string.app_selection_label_search),
                    )
                },
                singleLine = true,
            )

            val apps = uiState.displayedAppsForSelection
            when {
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                apps.isEmpty() -> {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(id = R.string.app_center_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(items = apps, key = { it.packageName }) { appInfo ->
                            ProtectionAppCard(
                                appInfo = appInfo,
                                onBlock = {
                                    viewModel.onEvent(
                                        AppBlockerEvent.OnAppSelectionChanged(appInfo.packageName, true)
                                    )
                                    viewModel.onEvent(AppBlockerEvent.OnSaveRequest)
                                },
                                onReleaseBlock = {
                                    viewModel.onEvent(
                                        AppBlockerEvent.OnToggleUnblockSelection(appInfo.packageName)
                                    )
                                    viewModel.onEvent(AppBlockerEvent.OnUnblockSelectedRequest)
                                },
                                onSuspend = {
                                    viewModel.onEvent(
                                        AppBlockerEvent.OnAppSuspensionChanged(appInfo.packageName, true)
                                    )
                                    viewModel.onEvent(AppBlockerEvent.OnSaveRequest)
                                },
                                onReleaseSuspend = {
                                    viewModel.onEvent(
                                        AppBlockerEvent.OnToggleUnsuspendSelection(appInfo.packageName)
                                    )
                                    viewModel.onEvent(AppBlockerEvent.OnUnsuspendSelectedRequest)
                                },
                                onUninstall = { viewModel.onEvent(AppBlockerEvent.OnRequestUninstall(appInfo)) },
                            )
                        }
                    }
                }
            }
            }
        }
    }

    if (!uiState.accessGranted) {
        PasswordPromptDialog(
            passwordError = uiState.passwordError,
            enabled = !uiState.isAuthenticating,
            onConfirm = { viewModel.onEvent(AppBlockerEvent.OnSubmitPassword(it)) },
            onDismiss = onNavigateBack,
        )
    }

    if (showAddPackageDialog) {
        var manualPackageName by remember { mutableStateOf("") }
        var errorText by remember { mutableStateOf<String?>(null) }

        AddPackageDialog(
            packageName = manualPackageName,
            onPackageNameChange = { manualPackageName = it },
            error = errorText,
            onDismiss = { showAddPackageDialog = false },
            onConfirm = {
                val error = viewModel.addPackageManually(manualPackageName)
                if (error == null) {
                    viewModel.onEvent(AppBlockerEvent.OnSaveRequest)
                    showAddPackageDialog = false
                } else {
                    errorText = error
                }
            },
        )
    }

    if (uiState.showCriticalAppsWarning && uiState.criticalAppsDetected.isNotEmpty()) {
        CriticalAppsWarningDialog(
            criticalApps = uiState.criticalAppsDetected,
            onDismiss = { viewModel.onEvent(AppBlockerEvent.OnDismissCriticalAppsWarning) },
        )
    }

    uiState.pendingUninstall?.let { app ->
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(AppBlockerEvent.OnCancelUninstall) },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(id = R.string.app_center_uninstall_title, app.appName)) },
            text = { Text(stringResource(id = R.string.app_center_uninstall_message)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.onEvent(AppBlockerEvent.OnConfirmUninstall) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(id = R.string.app_center_action_uninstall)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onEvent(AppBlockerEvent.OnCancelUninstall) }) {
                    Text(stringResource(id = R.string.dialog_button_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusFilterRow(
    selected: AppStatusFilter,
    blockedCount: Int,
    suspendedCount: Int,
    onSelect: (AppStatusFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == AppStatusFilter.ALL,
            onClick = { onSelect(AppStatusFilter.ALL) },
            label = { Text(stringResource(id = R.string.app_center_filter_all)) },
        )
        FilterChip(
            selected = selected == AppStatusFilter.BLOCKED,
            onClick = { onSelect(AppStatusFilter.BLOCKED) },
            label = { Text(stringResource(id = R.string.app_center_filter_blocked, blockedCount)) },
            leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        FilterChip(
            selected = selected == AppStatusFilter.SUSPENDED,
            onClick = { onSelect(AppStatusFilter.SUSPENDED) },
            label = { Text(stringResource(id = R.string.app_center_filter_suspended, suspendedCount)) },
            leadingIcon = { Icon(Icons.Default.PauseCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun FilterMenu(currentFilter: AppFilterType, onFilterSelected: (AppFilterType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.FilterList, contentDescription = stringResource(id = R.string.app_selection_desc_filter))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(id = R.string.app_selection_filter_user)) }, onClick = { onFilterSelected(AppFilterType.USER_ONLY); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(id = R.string.app_selection_filter_launcher)) }, onClick = { onFilterSelected(AppFilterType.LAUNCHER_ONLY); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(id = R.string.app_selection_filter_all_except_core)) }, onClick = { onFilterSelected(AppFilterType.ALL_EXCEPT_CORE); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(id = R.string.app_selection_filter_all)) }, onClick = { onFilterSelected(AppFilterType.ALL); expanded = false })
        }
    }
}

@Composable
fun AddPackageDialog(
    packageName: String,
    onPackageNameChange: (String) -> Unit,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.dialog_add_manual_package_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = packageName,
                    onValueChange = onPackageNameChange,
                    label = { Text("com.example.app") },
                    singleLine = true,
                    isError = error != null
                )
                if (error != null) {
                    Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(id = R.string.dialog_add_manual_package_button_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.dialog_button_cancel)) } }
    )
}

/**
 * Same card layout as the update list, so moving between the two tabs does not
 * feel like moving between two applications.
 *
 * Three primary actions, each stating what it does rather than relying on a
 * checkbox whose meaning changes with the active filter.
 */
@Composable
private fun ProtectionAppCard(
    appInfo: AppInfo,
    onBlock: () -> Unit,
    onReleaseBlock: () -> Unit,
    onSuspend: () -> Unit,
    onReleaseSuspend: () -> Unit,
    onUninstall: () -> Unit,
) {
    val canUninstall = appInfo.isInstalled && !appInfo.isSystemApp
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = rememberDrawablePainter(drawable = appInfo.icon),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.Unspecified,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = appInfo.appName,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                    )
                    if (appInfo.isSystemApp) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = stringResource(id = R.string.mini_store_system_badge),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Text(appInfo.packageName, style = MaterialTheme.typography.bodySmall)
                when {
                    appInfo.isBlocked -> Text(
                        text = stringResource(id = R.string.app_center_status_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    appInfo.isSuspended -> Text(
                        text = stringResource(id = R.string.app_center_status_suspended),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (!appInfo.isInstalled) {
                    Text(
                        text = stringResource(id = R.string.app_center_not_installed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                } else if (appInfo.isSystemApp) {
                    // The note lives next to the action it explains.
                    Text(
                        text = stringResource(id = R.string.mini_store_system_protected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        // Three actions of equal width. Hebrew labels such as "שחרר חסימה" do not
        // fit next to icons in a third of the card, so the label carries the
        // meaning and the icons stay out of the row.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onUninstall,
                modifier = Modifier.weight(1f),
                enabled = canUninstall,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                ActionLabel(stringResource(id = R.string.app_center_action_uninstall))
            }
            OutlinedButton(
                onClick = if (appInfo.isSuspended) onReleaseSuspend else onSuspend,
                modifier = Modifier.weight(1f),
                enabled = appInfo.isSuspended || !appInfo.isBlocked,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                ActionLabel(
                    stringResource(
                        id = if (appInfo.isSuspended) {
                            R.string.app_center_release_suspend
                        } else {
                            R.string.app_center_action_suspend
                        },
                    ),
                )
            }
            Button(
                onClick = if (appInfo.isBlocked) onReleaseBlock else onBlock,
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                ActionLabel(
                    stringResource(
                        id = if (appInfo.isBlocked) {
                            R.string.app_center_release_block
                        } else {
                            R.string.app_center_action_block
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ActionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun CriticalAppsWarningDialog(
    criticalApps: List<AppInfo>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.app_selection_warning_critical_title)) },
        text = {
            Column {
                Text(stringResource(id = R.string.app_selection_warning_critical_message))
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(id = R.string.app_selection_warning_critical_coming_soon))
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(id = R.string.app_selection_warning_critical_list_label), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                criticalApps.forEach { app ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painter = rememberDrawablePainter(drawable = app.icon), contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(app.appName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dashboard_button_understood))
            }
        }
    )
}
