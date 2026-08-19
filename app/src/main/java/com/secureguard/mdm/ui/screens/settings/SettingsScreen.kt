package com.secureguard.mdm.ui.screens.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.secureguard.mdm.R
import com.secureguard.mdm.settingsfeatures.api.*
import com.secureguard.mdm.settingsfeatures.impl.LockSettingsAction
import com.secureguard.mdm.settingsfeatures.impl.RemovalOptionsAction
import com.secureguard.mdm.settingsfeatures.impl.UpdateChannelAction
import com.secureguard.mdm.ui.components.InfoDialog
import com.secureguard.mdm.ui.components.PasswordPromptDialog
import com.secureguard.mdm.ui.screens.updatesettings.UpdateChannel
import com.secureguard.mdm.ui.screens.updatesettings.UpdateSettingsViewModel
import kotlinx.coroutines.flow.collectLatest

private sealed interface SettingsListEntry {
    val favoriteKey: String

    data class Modular(val model: SettingItemModel) : SettingsListEntry {
        override val favoriteKey: String = FavoriteKey.setting(model.feature.id)
    }

    data class Protection(val toggle: FeatureToggle) : SettingsListEntry {
        override val favoriteKey: String = FavoriteKey.protection(toggle.feature.id)
    }
}

@RequiresApi(Build.VERSION_CODES.P)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateTo: (String) -> Unit // Generic navigation callback
) {
    val uiState by viewModel.uiState.collectAsState()
    val passwordPromptState by viewModel.passwordPromptState.collectAsState()
    val removalOptionsDialogState by viewModel.removalOptionsDialogState.collectAsState()
    val deviceAdminSelectionState by viewModel.deviceAdminSelectionState.collectAsState()
    val errorDialogState by viewModel.errorDialogState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showUnsupportedDialogFor by remember { mutableStateOf<FeatureToggle?>(null) }
    var showUnsupportedSettingFor by remember { mutableStateOf<SettingItemModel?>(null) }
    var showLockConfirmationDialog by remember { mutableStateOf(false) }
    var showInfoDialogFor by remember { mutableStateOf<FeatureToggle?>(null) }
    var showUpdateChannelDialog by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    val normalizedSearchQuery = searchQuery.trim()
    val isSearching = normalizedSearchQuery.isNotEmpty()
    val filteredSettingItemsByCategory = uiState.settingItemsByCategory
        .mapValues { (_, items) ->
            items.filter { item ->
                !isSearching ||
                    context.getString(item.feature.titleRes).contains(normalizedSearchQuery, ignoreCase = true) ||
                    context.getString(item.feature.descriptionRes).contains(normalizedSearchQuery, ignoreCase = true)
            }
        }
        .filterValues { it.isNotEmpty() }
    val filteredProtectionCategoryToggles = uiState.protectionCategoryToggles.mapNotNull { category ->
        val matchingToggles = category.toggles.filter { toggle ->
            !isSearching ||
                context.getString(toggle.feature.titleRes).contains(normalizedSearchQuery, ignoreCase = true) ||
                context.getString(toggle.feature.descriptionRes).contains(normalizedSearchQuery, ignoreCase = true) ||
                toggle.conflictReasonResId?.let { reasonResId ->
                    context.getString(reasonResId).contains(normalizedSearchQuery, ignoreCase = true)
                } == true
        }
        category.takeIf { matchingToggles.isNotEmpty() }?.copy(toggles = matchingToggles)
    }
    val hasSearchResults = filteredSettingItemsByCategory.isNotEmpty() ||
        filteredProtectionCategoryToggles.isNotEmpty()
    val allEntries: List<SettingsListEntry> =
        uiState.settingItemsByCategory.values.flatten().map { SettingsListEntry.Modular(it) } +
            uiState.protectionCategoryToggles.flatMap { it.toggles }.map { SettingsListEntry.Protection(it) }
    val favoriteEntries = allEntries.filter { it.favoriteKey in uiState.favoriteKeys }
    val activeEntries = allEntries.filter { entry ->
        when (entry) {
            is SettingsListEntry.Modular ->
                entry.model.feature is ToggleSetting && entry.model.isChecked
            is SettingsListEntry.Protection -> entry.toggle.isEnabled
        }
    }
    val useCheckbox = uiState.settingItemsByCategory[SettingCategory.UI_AND_BEHAVIOR]
        ?.find { it.feature.id == "toggle_ui_control_type" }?.isChecked ?: false
    val isControlOnStart = uiState.settingItemsByCategory[SettingCategory.UI_AND_BEHAVIOR]
        ?.find { it.feature.id == "toggle_ui_position" }?.isChecked ?: false
    val allCategoryKeys = buildSet {
        uiState.settingItemsByCategory.keys.forEach { add(modularCategoryKey(it)) }
        uiState.protectionCategoryToggles.forEach { add(protectionCategoryKey(it)) }
    }
    val allCategoriesCollapsed = allCategoryKeys.isNotEmpty() &&
        allCategoryKeys.all { it in uiState.collapsedCategoryKeys }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onEvent(SettingsEvent.OnVpnPermissionResult(result.resultCode == Activity.RESULT_OK))
    }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collectLatest { effect ->
            when (effect) {
                is SettingsSideEffect.NavigateBack -> onNavigateBack()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.vpnPermissionRequestEvent.collectLatest {
            val intent = VpnService.prepare(context)
            if (intent != null) vpnPermissionLauncher.launch(intent)
            else viewModel.onEvent(SettingsEvent.OnVpnPermissionResult(true))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiState.collectLatest { state ->
            state.snackbarMessage?.let { message ->
                snackbarHostState.showSnackbar(message)
                viewModel.onEvent(SettingsEvent.OnSnackbarShown)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.triggerUninstallEvent.collectLatest {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(id = R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                viewModel.onEvent(
                                    SettingsEvent.OnAllCategoriesCollapsedChanged(
                                        categoryKeys = allCategoryKeys,
                                        collapsed = !allCategoriesCollapsed
                                    )
                                )
                            },
                            enabled = !uiState.isLoading && !isSearching && allCategoryKeys.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = if (allCategoriesCollapsed) {
                                    Icons.Default.ExpandMore
                                } else {
                                    Icons.Default.ExpandLess
                                },
                                contentDescription = stringResource(
                                    id = if (allCategoriesCollapsed) {
                                        R.string.settings_expand_all_categories
                                    } else {
                                        R.string.settings_collapse_all_categories
                                    }
                                )
                            )
                        }
                        IconButton(
                            onClick = {
                                if (searchExpanded) {
                                    searchQuery = ""
                                    searchExpanded = false
                                } else {
                                    searchExpanded = true
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = stringResource(
                                    id = if (searchExpanded) {
                                        R.string.settings_search_clear
                                    } else {
                                        R.string.settings_search_label
                                    }
                                )
                            )
                        }
                    }
                )
                if (searchExpanded) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(id = R.string.settings_search_placeholder)) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(id = R.string.settings_search_clear)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                }
            }
        },
        floatingActionButton = {
            Surface(
                onClick = { viewModel.onEvent(SettingsEvent.OnSaveClick) },
                enabled = uiState.hasUnsavedChanges,
                modifier = Modifier.size(56.dp),
                shape = MaterialTheme.shapes.large,
                color = if (uiState.hasUnsavedChanges) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (uiState.hasUnsavedChanges) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                shadowElevation = if (uiState.hasUnsavedChanges) 6.dp else 0.dp
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = stringResource(id = R.string.settings_button_save)
                    )
                }
            }
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (uiState.hasUnsavedChanges) {
                    UnsavedChangesBanner(
                        count = uiState.unsavedChangeCount,
                        canUndo = uiState.canUndo,
                        onUndo = { viewModel.onEvent(SettingsEvent.OnUndoClick) }
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp) // Space for FAB
                ) {
                    // --- FILTERED RENDERING ---
                    if (!isSearching) {
                    if (favoriteEntries.isNotEmpty()) {
                        item(key = "quick_header:favorites") {
                            QuickSectionHeader(R.string.settings_section_favorites)
                        }
                        items(favoriteEntries, key = { "favorite:${it.favoriteKey}" }) { entry ->
                            SettingsListEntryRow(
                                entry = entry,
                                uiState = uiState,
                                useCheckbox = useCheckbox,
                                isControlOnStart = isControlOnStart,
                                onNavigate = onNavigateTo,
                                onToggleSetting = { id, checked ->
                                    viewModel.onEvent(SettingsEvent.OnToggleSettingChanged(id, checked))
                                },
                                onToggleProtection = { id, enabled ->
                                    viewModel.onEvent(SettingsEvent.OnToggleProtectionFeature(id, enabled))
                                },
                                onFavorite = { viewModel.onEvent(SettingsEvent.OnFavoriteToggled(it)) },
                                onInfo = { showInfoDialogFor = it },
                                onUnsupportedProtection = { showUnsupportedDialogFor = it },
                                onSpecialAction = { model, featureId ->
                                    if (!model.isSupported) showUnsupportedSettingFor = model
                                    else when (featureId) {
                                        LockSettingsAction.id -> showLockConfirmationDialog = true
                                        RemovalOptionsAction.id -> viewModel.onEvent(SettingsEvent.OnActionSettingClicked(featureId))
                                        UpdateChannelAction.id -> showUpdateChannelDialog = true
                                    }
                                }
                            )
                        }
                    }
                    if (activeEntries.isNotEmpty()) {
                        item(key = "quick_header:active") {
                            QuickSectionHeader(R.string.settings_section_active)
                        }
                        items(activeEntries, key = { "active:${it.favoriteKey}" }) { entry ->
                            SettingsListEntryRow(
                                entry = entry,
                                uiState = uiState,
                                useCheckbox = useCheckbox,
                                isControlOnStart = isControlOnStart,
                                onNavigate = onNavigateTo,
                                onToggleSetting = { id, checked ->
                                    viewModel.onEvent(SettingsEvent.OnToggleSettingChanged(id, checked))
                                },
                                onToggleProtection = { id, enabled ->
                                    viewModel.onEvent(SettingsEvent.OnToggleProtectionFeature(id, enabled))
                                },
                                onFavorite = { viewModel.onEvent(SettingsEvent.OnFavoriteToggled(it)) },
                                onInfo = { showInfoDialogFor = it },
                                onUnsupportedProtection = { showUnsupportedDialogFor = it },
                                onSpecialAction = { model, featureId ->
                                    if (!model.isSupported) showUnsupportedSettingFor = model
                                    else when (featureId) {
                                        LockSettingsAction.id -> showLockConfirmationDialog = true
                                        RemovalOptionsAction.id -> viewModel.onEvent(SettingsEvent.OnActionSettingClicked(featureId))
                                        UpdateChannelAction.id -> showUpdateChannelDialog = true
                                    }
                                }
                            )
                        }
                    }
                }

                if (!hasSearchResults) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 220.dp)
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(id = R.string.settings_search_no_results),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {

                // 1. Render App Management Category
                filteredSettingItemsByCategory[SettingCategory.APP_MANAGEMENT]?.let { items ->
                    val categoryKey = modularCategoryKey(SettingCategory.APP_MANAGEMENT)
                    val expanded = isSearching || categoryKey !in uiState.collapsedCategoryKeys
                    item(key = "header:$categoryKey") {
                        SettingsCategoryHeader(
                            titleRes = SettingCategory.APP_MANAGEMENT.titleRes,
                            expanded = expanded,
                            collapsible = !isSearching,
                            onClick = {
                                viewModel.onEvent(SettingsEvent.OnCategoryCollapsedToggled(categoryKey))
                            }
                        )
                    }
                    if (expanded) {
                        items(items = items, key = { "full:setting:${it.feature.id}" }) { itemModel ->
                            SettingsItemRenderer(
                                uiState = uiState,
                                itemModel = itemModel,
                                onNavigate = onNavigateTo,
                                onToggle = { id, checked ->
                                    viewModel.onEvent(SettingsEvent.OnToggleSettingChanged(id, checked))
                                },
                                onFavorite = { viewModel.onEvent(SettingsEvent.OnFavoriteToggled(it)) },
                                onSpecialAction = { model, featureId ->
                                    if (!model.isSupported) showUnsupportedSettingFor = model
                                }
                            )
                        }
                    }
                }

                // 2. Render UI and Behavior Category
                filteredSettingItemsByCategory[SettingCategory.UI_AND_BEHAVIOR]?.let { items ->
                    val categoryKey = modularCategoryKey(SettingCategory.UI_AND_BEHAVIOR)
                    val expanded = isSearching || categoryKey !in uiState.collapsedCategoryKeys
                    item(key = "header:$categoryKey") {
                        SettingsCategoryHeader(
                            titleRes = SettingCategory.UI_AND_BEHAVIOR.titleRes,
                            expanded = expanded,
                            collapsible = !isSearching,
                            onClick = {
                                viewModel.onEvent(SettingsEvent.OnCategoryCollapsedToggled(categoryKey))
                            }
                        )
                    }
                    if (expanded) {
                        items(items = items, key = { "full:setting:${it.feature.id}" }) { itemModel ->
                            SettingsItemRenderer(
                                uiState = uiState,
                                itemModel = itemModel,
                                onNavigate = onNavigateTo,
                                onToggle = { id, checked ->
                                    viewModel.onEvent(SettingsEvent.OnToggleSettingChanged(id, checked))
                                },
                                onFavorite = { viewModel.onEvent(SettingsEvent.OnFavoriteToggled(it)) },
                                onSpecialAction = { model, _ ->
                                    if (!model.isSupported) showUnsupportedSettingFor = model
                                }
                            )
                        }
                    }
                }

                // 3. Render all Protection Feature Toggles
                filteredProtectionCategoryToggles.forEach { category ->
                    val categoryKey = protectionCategoryKey(category)
                    val expanded = isSearching || categoryKey !in uiState.collapsedCategoryKeys
                    item(key = "header:$categoryKey") {
                        SettingsCategoryHeader(
                            titleRes = category.titleResId,
                            expanded = expanded,
                            collapsible = !isSearching,
                            onClick = {
                                viewModel.onEvent(SettingsEvent.OnCategoryCollapsedToggled(categoryKey))
                            }
                        )
                    }
                    if (expanded) {
                        items(items = category.toggles, key = { "full:protection:${it.feature.id}" }) { toggle ->
                            FeatureToggleRow(
                                toggle = toggle,
                                useCheckbox = useCheckbox,
                                isControlOnStart = isControlOnStart,
                                isFavorite = FavoriteKey.protection(toggle.feature.id) in uiState.favoriteKeys,
                                onToggle = { isEnabled ->
                                    viewModel.onEvent(SettingsEvent.OnToggleProtectionFeature(toggle.feature.id, isEnabled))
                                },
                                onFavorite = {
                                    viewModel.onEvent(
                                        SettingsEvent.OnFavoriteToggled(FavoriteKey.protection(toggle.feature.id))
                                    )
                                },
                                onInfoClick = { showInfoDialogFor = toggle },
                                onRowClick = { if (!toggle.isSupported) showUnsupportedDialogFor = toggle }
                            )
                        }
                    }
                }

                // 4. Render Advanced Actions Category LAST
                filteredSettingItemsByCategory[SettingCategory.ADVANCED_ACTIONS]?.let { items ->
                    val categoryKey = modularCategoryKey(SettingCategory.ADVANCED_ACTIONS)
                    val expanded = isSearching || categoryKey !in uiState.collapsedCategoryKeys
                    item(key = "header:$categoryKey") {
                        SettingsCategoryHeader(
                            titleRes = SettingCategory.ADVANCED_ACTIONS.titleRes,
                            expanded = expanded,
                            collapsible = !isSearching,
                            onClick = {
                                viewModel.onEvent(SettingsEvent.OnCategoryCollapsedToggled(categoryKey))
                            }
                        )
                    }
                    if (expanded) {
                        items(items = items, key = { "full:setting:${it.feature.id}" }) { itemModel ->
                            SettingsItemRenderer(
                                uiState = uiState,
                                itemModel = itemModel,
                                onNavigate = onNavigateTo,
                                onToggle = { id, checked ->
                                    viewModel.onEvent(SettingsEvent.OnToggleSettingChanged(id, checked))
                                },
                                onFavorite = { viewModel.onEvent(SettingsEvent.OnFavoriteToggled(it)) },
                                onSpecialAction = { model, featureId ->
                                    if (!model.isSupported) {
                                        showUnsupportedSettingFor = model
                                    } else {
                                        when (featureId) {
                                            LockSettingsAction.id -> showLockConfirmationDialog = true
                                            RemovalOptionsAction.id -> viewModel.onEvent(SettingsEvent.OnActionSettingClicked(featureId))
                                            UpdateChannelAction.id -> showUpdateChannelDialog = true
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                }
            }
        }
        }
    }

    if (passwordPromptState.isVisible) {
        PasswordPromptDialog(
            passwordError = passwordPromptState.error,
            onConfirm = { viewModel.onPasswordPromptEvent(PasswordPromptEvent.OnPasswordEntered(it)) },
            onDismiss = { viewModel.onPasswordPromptEvent(PasswordPromptEvent.OnDismiss) }
        )
    }

    if (showLockConfirmationDialog) {
        LockSettingsConfirmationDialog(
            onDismiss = { showLockConfirmationDialog = false },
            onConfirm = { allowManualUpdate ->
                showLockConfirmationDialog = false
                viewModel.onEvent(SettingsEvent.OnLockSettingsConfirmed(allowManualUpdate))
            }
        )
    }

    showUnsupportedDialogFor?.let { item ->
        InfoDialog(
            title = stringResource(id = R.string.dialog_title_unsupported_feature),
            message = context.getString(R.string.dialog_description_unsupported_feature, 
                context.getString(item.feature.titleRes), 
                item.requiredApi, 
                getAndroidVersionName(item.requiredApi), 
                Build.VERSION.SDK_INT, 
                Build.VERSION.RELEASE
            ),
            onDismiss = { showUnsupportedDialogFor = null }
        )
    }

    showUnsupportedSettingFor?.let { item ->
        InfoDialog(
            title = stringResource(id = R.string.dialog_title_unsupported_feature),
            message = context.getString(
                R.string.dialog_description_unsupported_feature,
                context.getString(item.feature.titleRes),
                item.requiredApi,
                getAndroidVersionName(item.requiredApi),
                Build.VERSION.SDK_INT,
                Build.VERSION.RELEASE
            ),
            onDismiss = { showUnsupportedSettingFor = null }
        )
    }

    showInfoDialogFor?.let { toggle ->
        InfoDialog(
            title = stringResource(id = toggle.feature.titleRes),
            message = stringResource(id = toggle.feature.descriptionRes),
            onDismiss = { showInfoDialogFor = null }
        )
    }

    // FRP warning dialog removed by request

    if (removalOptionsDialogState.isVisible) {
        RemovalOptionsDialog(
            onRegularRemovalSelected = { viewModel.onEvent(SettingsEvent.OnRegularRemovalSelected) },
            onTransferOwnershipSelected = { viewModel.onEvent(SettingsEvent.OnTransferOwnershipSelected) },
            onDismiss = { viewModel.onEvent(SettingsEvent.OnDismissRemovalOptionsDialog) }
        )
    }

    if (deviceAdminSelectionState.isVisible) {
        DeviceAdminSelectionDialog(
            deviceAdmins = deviceAdminSelectionState.deviceAdmins,
            onDeviceAdminSelected = { viewModel.onEvent(SettingsEvent.OnDeviceAdminSelected(it)) },
            onDismiss = { viewModel.onEvent(SettingsEvent.OnDeviceAdminSelectionDismissed) }
        )
    }

    deviceAdminSelectionState.selectedAdmin?.let { selectedAdmin ->
        if (deviceAdminSelectionState.showConfirmationDialog) {
            DeviceAdminTransferConfirmationDialog(
                selectedAdmin = selectedAdmin,
                onConfirm = { viewModel.onEvent(SettingsEvent.OnDeviceAdminTransferConfirmed) },
                onCancel = { viewModel.onEvent(SettingsEvent.OnDeviceAdminTransferCancelled) }
            )
        }
    }

    if (errorDialogState.isVisible) {
        ErrorDialog(
            title = errorDialogState.title,
            message = errorDialogState.message,
            onDismiss = { viewModel.onEvent(SettingsEvent.OnErrorDialogDismissed) }
        )
    }

    if (showUpdateChannelDialog) {
        UpdateChannelDialog(
            onDismiss = { showUpdateChannelDialog = false }
        )
    }
}

@RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun SettingsItemRenderer(
    uiState: SettingsUiState,
    itemModel: SettingItemModel,
    onNavigate: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onFavorite: (String) -> Unit,
    onSpecialAction: (SettingItemModel, String) -> Unit
) {
    val useCheckbox = uiState.settingItemsByCategory[SettingCategory.UI_AND_BEHAVIOR]
        ?.find { it.feature.id == "toggle_ui_control_type" }?.isChecked ?: false
    val tint = if (itemModel.isSupported) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }
    val favoriteKey = FavoriteKey.setting(itemModel.feature.id)
    val isFavorite = favoriteKey in uiState.favoriteKeys

    when (val feature = itemModel.feature) {
        is NavigationalSetting -> SettingsActionItem(
            title = stringResource(id = feature.titleRes),
            description = stringResource(id = feature.descriptionRes),
            iconRes = feature.iconRes,
            onClick = {
                if (itemModel.isSupported) onNavigate(feature.route)
                else onSpecialAction(itemModel, feature.id)
            },
            isFavorite = isFavorite,
            onFavorite = { onFavorite(favoriteKey) },
            tint = tint
        )
        is DestructiveActionSetting -> SettingsActionItem(
            title = stringResource(id = feature.titleRes),
            description = stringResource(id = feature.descriptionRes),
            iconRes = feature.iconRes,
            onClick = { onSpecialAction(itemModel, feature.id) },
            isFavorite = isFavorite,
            onFavorite = { onFavorite(favoriteKey) },
            isDestructive = true,
            tint = tint
        )
        is ActionSetting -> SettingsActionItem(
            title = stringResource(id = feature.titleRes),
            description = stringResource(id = feature.descriptionRes),
            iconRes = feature.iconRes,
            onClick = { onSpecialAction(itemModel, feature.id) },
            isFavorite = isFavorite,
            onFavorite = { onFavorite(favoriteKey) },
            tint = tint
        )
        is ToggleSetting -> SettingsToggleItem(
            title = stringResource(id = feature.titleRes),
            description = stringResource(id = feature.descriptionRes),
            isChecked = itemModel.isChecked,
            onCheckedChange = { checked ->
                if (itemModel.isSupported) onToggle(feature.id, checked)
            },
            useCheckbox = useCheckbox,
            iconRes = feature.iconRes,
            isEnabled = itemModel.isSupported,
            isFavorite = isFavorite,
            onFavorite = { onFavorite(favoriteKey) }
        )
    }
}

@RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun SettingsListEntryRow(
    entry: SettingsListEntry,
    uiState: SettingsUiState,
    useCheckbox: Boolean,
    isControlOnStart: Boolean,
    onNavigate: (String) -> Unit,
    onToggleSetting: (String, Boolean) -> Unit,
    onToggleProtection: (String, Boolean) -> Unit,
    onFavorite: (String) -> Unit,
    onInfo: (FeatureToggle) -> Unit,
    onUnsupportedProtection: (FeatureToggle) -> Unit,
    onSpecialAction: (SettingItemModel, String) -> Unit
) {
    when (entry) {
        is SettingsListEntry.Modular -> SettingsItemRenderer(
            uiState = uiState,
            itemModel = entry.model,
            onNavigate = onNavigate,
            onToggle = onToggleSetting,
            onFavorite = onFavorite,
            onSpecialAction = onSpecialAction
        )
        is SettingsListEntry.Protection -> FeatureToggleRow(
            toggle = entry.toggle,
            useCheckbox = useCheckbox,
            isControlOnStart = isControlOnStart,
            isFavorite = entry.favoriteKey in uiState.favoriteKeys,
            onToggle = { onToggleProtection(entry.toggle.feature.id, it) },
            onFavorite = { onFavorite(entry.favoriteKey) },
            onInfoClick = { onInfo(entry.toggle) },
            onRowClick = { if (!entry.toggle.isSupported) onUnsupportedProtection(entry.toggle) }
        )
    }
}

@Composable
private fun QuickSectionHeader(titleRes: Int) {
    Text(
        text = stringResource(id = titleRes),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCategoryHeader(
    titleRes: Int,
    expanded: Boolean,
    collapsible: Boolean,
    onClick: () -> Unit
) {
    val actionDescription = stringResource(
        id = if (expanded) R.string.settings_collapse_category else R.string.settings_expand_category
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .clickable(enabled = collapsible, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = titleRes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (collapsible) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = actionDescription
                )
            }
        }
    }
}

@Composable
private fun UnsavedChangesBanner(count: Int, canUndo: Boolean, onUndo: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.settings_unsaved_changes, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onUndo, enabled = canUndo) {
                Text(stringResource(id = R.string.settings_button_undo))
            }
        }
    }
}

@Composable
private fun FavoriteButton(isFavorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
            contentDescription = stringResource(
                id = if (isFavorite) R.string.settings_remove_favorite else R.string.settings_add_favorite
            ),
            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
private fun LockSettingsConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit
) {
    var allowManualUpdate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_lock_dialog_title)) },
        text = {
            Column {
                Text(stringResource(id = R.string.settings_lock_dialog_message))
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { allowManualUpdate = !allowManualUpdate }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allowManualUpdate,
                        onCheckedChange = { allowManualUpdate = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(id = R.string.settings_lock_dialog_allow_manual_update))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(allowManualUpdate) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(id = R.string.dialog_button_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_button_cancel))
            }
        }
    )
}

@Composable
private fun FeatureToggleRow(
    toggle: FeatureToggle,
    useCheckbox: Boolean,
    isControlOnStart: Boolean,
    isFavorite: Boolean,
    onToggle: (Boolean) -> Unit,
    onFavorite: () -> Unit,
    onInfoClick: () -> Unit,
    onRowClick: () -> Unit
) {
    val tint = if (toggle.isSupported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

    @Composable
    fun Control() {
        if (useCheckbox) {
            Checkbox(
                checked = toggle.isEnabled,
                onCheckedChange = onToggle,
                enabled = toggle.isSupported
            )
        } else {
            Switch(
                checked = toggle.isEnabled,
                onCheckedChange = onToggle,
                enabled = toggle.isSupported
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(enabled = !toggle.isSupported, onClick = onRowClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isControlOnStart) {
                    Control()
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Icon(
                    painter = painterResource(id = toggle.feature.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = tint
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = toggle.feature.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = tint
                    )
                    Text(
                        text = stringResource(id = toggle.feature.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = tint.copy(alpha = 0.75f)
                    )
                }

                FavoriteButton(isFavorite = isFavorite, onClick = onFavorite)

                IconButton(onClick = onInfoClick, enabled = toggle.isSupported) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(id = R.string.settings_desc_more_info),
                        tint = tint.copy(alpha = 0.7f)
                    )
                }

                if (!isControlOnStart) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Control()
                }
            }
            toggle.conflictReasonResId?.let { reasonResId ->
                Text(
                    text = stringResource(id = reasonResId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 48.dp, top = 4.dp)
                )
            }
        }
    }
}


@Composable
fun SettingsActionItem(
    title: String,
    description: String,
    iconRes: Int,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    isDestructive: Boolean = false,
    tint: Color? = null
) {
    val color = tint ?: if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconRes != 0) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = color
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = color)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = color.copy(alpha = 0.75f)
                )
            }
            FavoriteButton(isFavorite = isFavorite, onClick = onFavorite)
        }
    }
}

@Composable
fun SettingsToggleItem(
    title: String,
    description: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    useCheckbox: Boolean,
    iconRes: Int? = null,
    isEnabled: Boolean = true,
    isFavorite: Boolean,
    onFavorite: () -> Unit
) {
    val tint = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(enabled = isEnabled) { onCheckedChange(!isChecked) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            iconRes?.let {
                if (it != 0) {
                    Icon(painter = painterResource(id = it), contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
                    Spacer(modifier = Modifier.width(16.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = tint)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = tint.copy(alpha = 0.75f)
                )
            }
            FavoriteButton(isFavorite = isFavorite, onClick = onFavorite)
            if (useCheckbox) {
                Checkbox(checked = isChecked, onCheckedChange = onCheckedChange, enabled = isEnabled)
            } else {
                Switch(checked = isChecked, onCheckedChange = onCheckedChange, enabled = isEnabled)
            }
        }
    }
}

@Composable
private fun RemovalOptionsDialog(
    onRegularRemovalSelected: () -> Unit,
    onTransferOwnershipSelected: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.removal_options_dialog_title)) },
        text = {
            Column {
                Text(stringResource(id = R.string.removal_options_dialog_message))
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onRegularRemovalSelected,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(id = R.string.removal_options_button_regular))
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onTransferOwnershipSelected,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(id = R.string.removal_options_button_transfer))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_button_cancel))
            }
        }
    )
}

@Composable
private fun DeviceAdminSelectionDialog(
    deviceAdmins: List<DeviceAdminItem>,
    onDeviceAdminSelected: (DeviceAdminItem) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.device_admin_selection_dialog_title)) },
        text = {
            if (deviceAdmins.isEmpty()) {
                Text(stringResource(id = R.string.device_admin_selection_dialog_empty))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(deviceAdmins) { deviceAdmin ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onDeviceAdminSelected(deviceAdmin) }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_apps_control_off),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp)
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = deviceAdmin.displayName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = deviceAdmin.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_button_cancel))
            }
        }
    )
}

@Composable
private fun DeviceAdminTransferConfirmationDialog(
    selectedAdmin: DeviceAdminItem,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(id = R.string.device_admin_transfer_dialog_title)) },
        text = {
            Column {
                val message = stringResource(
                    id = R.string.device_admin_transfer_dialog_message,
                    selectedAdmin.displayName,
                    selectedAdmin.packageName
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(id = R.string.device_admin_transfer_dialog_button_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(id = R.string.device_admin_transfer_dialog_button_cancel))
            }
        }
    )
}

@Composable
private fun ErrorDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_button_confirm))
            }
        }
    )
}

@Composable
private fun UpdateChannelDialog(
    onDismiss: () -> Unit
) {
    val updateSettingsViewModel: UpdateSettingsViewModel = hiltViewModel()
    val uiState by updateSettingsViewModel.uiState.collectAsState()
    var showExplanationDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_settings_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.update_channel_stable),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            updateSettingsViewModel.onChannelSelected(UpdateChannel.STABLE)
                        }
                        .padding(vertical = 12.dp)
                )
                RadioButton(
                    selected = uiState.selectedChannel == UpdateChannel.STABLE,
                    onClick = { updateSettingsViewModel.onChannelSelected(UpdateChannel.STABLE) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.update_channel_prebuild),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            updateSettingsViewModel.onChannelSelected(UpdateChannel.PREBUILD)
                        }
                        .padding(vertical = 12.dp)
                )
                RadioButton(
                    selected = uiState.selectedChannel == UpdateChannel.PREBUILD,
                    onClick = { updateSettingsViewModel.onChannelSelected(UpdateChannel.PREBUILD) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = { showExplanationDialog = true },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.update_channel_explanation_title))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                updateSettingsViewModel.onSaveClicked()
                onDismiss()
            }) {
                Text(stringResource(R.string.settings_button_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_button_cancel))
            }
        }
    )

    if (showExplanationDialog) {
        InfoDialog(
            title = stringResource(R.string.update_channel_explanation_title),
            message = stringResource(R.string.update_channel_explanation_message),
            onDismiss = { showExplanationDialog = false }
        )
    }
}

private fun getAndroidVersionName(sdkInt: Int): String {
    return when (sdkInt) {
        Build.VERSION_CODES.LOLLIPOP_MR1 -> "5.1"; Build.VERSION_CODES.M -> "6.0"; Build.VERSION_CODES.N -> "7.0"; Build.VERSION_CODES.N_MR1 -> "7.1"; Build.VERSION_CODES.O -> "8.0"; Build.VERSION_CODES.O_MR1 -> "8.1"; Build.VERSION_CODES.P -> "9"; Build.VERSION_CODES.Q -> "10"; Build.VERSION_CODES.R -> "11"; Build.VERSION_CODES.S -> "12"; Build.VERSION_CODES.S_V2 -> "12L"; Build.VERSION_CODES.TIRAMISU -> "13"; 34 -> "14"; else -> sdkInt.toString()
    }
}
