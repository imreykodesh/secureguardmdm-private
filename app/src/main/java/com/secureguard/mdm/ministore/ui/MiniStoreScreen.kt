package com.secureguard.mdm.ministore.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.secureguard.mdm.R
import com.secureguard.mdm.ministore.data.CatalogSourceState
import com.secureguard.mdm.ministore.data.ManagedInstalledApp
import com.secureguard.mdm.ministore.data.PlaySourceState
import com.secureguard.mdm.ministore.data.UpdateOperationStage
import com.secureguard.mdm.ministore.data.UpdateSource
import com.secureguard.mdm.ui.components.AppCenterTab
import com.secureguard.mdm.ui.components.AppCenterTabs
import com.secureguard.mdm.ui.components.PasswordPromptDialog
import java.util.Locale

private val miniStoreCategories = listOf(
    MiniStoreCategory.ALL,
    MiniStoreCategory.USER,
    MiniStoreCategory.SYSTEM,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniStoreScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPlayLogin: () -> Unit = {},
    onNavigateToProtection: () -> Unit = {},
    viewModel: MiniStoreViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val controlsEnabled = state.accessGranted && !state.isBusy
    val displayedApps = state.displayedApps

    // Returning from the Google sign-in screen reuses this screen, so the account
    // state is re-read on resume and the list reloads when it changed.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Observes the process lifecycle rather than this screen's navigation entry.
    // Navigating to the Google sign-in screen stops the entry, which previously
    // looked like the app leaving the foreground: the management authorisation
    // was dropped and the password dialog flashed on the way out.
    DisposableEffect(Unit) {
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onBackgrounded()
        }
        processLifecycle.addObserver(observer)
        onDispose { processLifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mini_store_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dialog_button_cancel),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::requestBlacklistEditor,
                        enabled = controlsEnabled,
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = stringResource(R.string.mini_store_blacklist),
                        )
                    }
                    IconButton(
                        onClick = {
                            if (state.playSignedInEmail != null) {
                                viewModel.signOutOfPlay()
                            } else {
                                onNavigateToPlayLogin()
                            }
                        },
                        enabled = controlsEnabled,
                    ) {
                        Icon(
                            imageVector = if (state.playSignedInEmail != null) {
                                Icons.Default.CloudDone
                            } else {
                                Icons.Default.CloudOff
                            },
                            contentDescription = stringResource(
                                if (state.playSignedInEmail != null) {
                                    R.string.mini_store_play_sign_out
                                } else {
                                    R.string.mini_store_play_sign_in
                                },
                            ),
                        )
                    }
                    IconButton(onClick = viewModel::refresh, enabled = controlsEnabled) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.mini_store_refresh),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        AppCenterTabs(
            selected = AppCenterTab.UPDATES,
            onSelect = { tab ->
                when (tab) {
                    AppCenterTab.UPDATES -> Unit
                    AppCenterTab.PROTECTION -> onNavigateToProtection()
                }
            },
        )
        // Pinned above the list: an operation must stay visible while the user
        // scrolls, otherwise a long download looks like a hang.
        state.operationStage?.let { stage ->
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OperationProgressCard(
                    stage = stage,
                    completedBytes = state.operationCompletedBytes,
                    totalBytes = state.operationTotalBytes,
                    appName = state.allApps
                        .firstOrNull { it.packageName == state.operationPackage }
                        ?.displayName
                        ?: state.operationPackage.orEmpty(),
                    queuedCount = state.queuedPackages.size,
                    onCancelCurrent = {
                        state.operationPackage?.let(viewModel::cancelUpdate)
                    },
                    onCancelAll = viewModel::cancelAllUpdates,
                )
            }
        }
        when {
            !state.accessGranted -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.mini_store_waiting_for_access))
            }
            state.isLoading && state.allApps.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.playSignedInEmail == null) {
                    item {
                        PlaySignInBanner(
                            deviceAccount = state.deviceGoogleAccount,
                            enabled = controlsEnabled,
                            onSignIn = onNavigateToPlayLogin,
                        )
                    }
                }
                item {
                    MiniStoreHeader(
                        appCount = state.visibleApps.size,
                        updateCount = state.updateCount,
                        controlsEnabled = state.canQueueUpdates,
                        onUpdateAll = viewModel::updateAll,
                        searchQuery = state.searchQuery,
                        onSearchQueryChange = viewModel::setSearchQuery,
                    )
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (state.isLoading) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                text = stringResource(
                                    when {
                                        state.isLoading -> R.string.mini_store_source_checking
                                        // Being signed out is reported as an
                                        // incomplete check, because the Play apps
                                        // were never asked about.
                                        state.playSourceState == PlaySourceState.SIGNED_OUT ->
                                            R.string.mini_store_source_signed_out
                                        state.catalogSourceState == CatalogSourceState.CHECKED &&
                                            state.playSourceState == PlaySourceState.CHECKED ->
                                            R.string.mini_store_source_checked
                                        state.catalogSourceState == CatalogSourceState.CHECKED ->
                                            R.string.mini_store_source_catalog_fallback
                                        state.playSourceState == PlaySourceState.CHECKED ->
                                            R.string.mini_store_source_play_only
                                        else -> R.string.mini_store_source_unavailable
                                    },
                                ) + state.playSignedInEmail?.let {
                                    "\n" + stringResource(R.string.mini_store_play_signed_in_as, it)
                                }.orEmpty() + state.playSessionIssue?.let { "\n$it" }.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                state.sourceWarning?.let { warning ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.mini_store_source_warning, warning),
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                // Offered only when the pending updates actually span both user and
                // system apps. With updates on one side only, the chips would be
                // three buttons that all lead to the same list.
                if (state.categoryFilterVisible) {
                    item {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(miniStoreCategories, key = { it.name }) { category ->
                                val count = when (category) {
                                    MiniStoreCategory.ALL -> state.updateCount
                                    MiniStoreCategory.USER -> state.userAppCount
                                    MiniStoreCategory.SYSTEM -> state.systemAppCount
                                }
                                FilterChip(
                                    selected = state.selectedCategory == category,
                                    onClick = { viewModel.setCategory(category) },
                                    label = {
                                        Text(miniStoreCategoryLabel(category, count))
                                    },
                                )
                            }
                        }
                    }
                }
                // Pinned above everything else and never filtered: updating the
                // app itself comes before updating what it manages.
                state.selfUpdate?.let { self ->
                    item(key = "self-update") {
                        MiniStoreAppCard(
                            app = self,
                            busy = !controlsEnabled,
                            active = state.operationPackage == self.packageName,
                            operationStage = state.operationStage.takeIf {
                                state.operationPackage == self.packageName
                            },
                            operationCompletedBytes = state.operationCompletedBytes,
                            operationTotalBytes = state.operationTotalBytes,
                            queued = self.packageName in state.queuedPackages,
                            canQueueUpdate = state.canQueueUpdates,
                            playSourceState = state.playSourceState,
                            preferred = true,
                            onUpdate = { viewModel.update(self) },
                            onCancelUpdate = { viewModel.cancelUpdate(self.packageName) },
                            onBlacklist = {},
                        )
                    }
                }
                when {
                    state.updatableApps.isEmpty() -> item {
                        // Not shown as "nothing to do" when the app itself has an
                        // update pinned above.
                        if (state.selfUpdate == null) EmptyStoreMessage(R.string.mini_store_no_updates)
                    }
                    displayedApps.isEmpty() -> item {
                        EmptyStoreMessage(R.string.mini_store_no_filtered_results)
                    }
                    else -> items(displayedApps, key = { it.packageName }) { app ->
                        MiniStoreAppCard(
                            app = app,
                            busy = !controlsEnabled,
                            active = state.operationPackage == app.packageName,
                            operationStage = state.operationStage.takeIf {
                                state.operationPackage == app.packageName
                            },
                            operationCompletedBytes = state.operationCompletedBytes,
                            operationTotalBytes = state.operationTotalBytes,
                            queued = app.packageName in state.queuedPackages,
                            canQueueUpdate = state.canQueueUpdates,
                            playSourceState = state.playSourceState,
                            onUpdate = { viewModel.update(app) },
                            onCancelUpdate = { viewModel.cancelUpdate(app.packageName) },
                            onBlacklist = { viewModel.blacklistApp(app) },
                        )
                    }
                }
            }
        }
        }
    }

    state.passwordPurpose?.let { purpose ->
        PasswordPromptDialog(
            passwordError = state.passwordError,
            enabled = !state.isAuthenticating,
            onConfirm = viewModel::submitPassword,
            onDismiss = {
                viewModel.dismissPasswordPrompt()
                if (purpose == MiniStorePasswordPurpose.ENTRY) onNavigateBack()
            },
        )
    }

    if (state.blacklistEditorVisible) {
        AlertDialog(
            onDismissRequest = {
                if (controlsEnabled) viewModel.closeBlacklistEditor()
            },
            title = {
                Text(
                    stringResource(R.string.mini_store_blacklist_title) +
                        " · " +
                        stringResource(R.string.mini_store_blacklist_count, state.blacklist.size),
                )
            },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    item {
                        Text(
                            text = stringResource(R.string.mini_store_blacklist_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    // Excluded apps first, so the current state is visible without
                    // scrolling through every installed package.
                    val sorted = state.allApps.sortedByDescending { it.packageName in state.blacklist }
                    items(sorted, key = { it.packageName }) { app ->
                        val checked = app.packageName in state.blacklist
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = controlsEnabled) {
                                    viewModel.setBlacklisted(app.packageName, !checked)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                enabled = controlsEnabled,
                                onCheckedChange = {
                                    viewModel.setBlacklisted(app.packageName, it)
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(app.displayName)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::closeBlacklistEditor,
                    enabled = controlsEnabled,
                ) {
                    Text(stringResource(R.string.dialog_button_confirm))
                }
            },
        )
    }

}

/**
 * Header card, with search folded into it.
 *
 * A permanent search field took a full row above the list for something used
 * occasionally, so it is now an icon in the corner of this card that expands
 * into a field when tapped and collapses when emptied.
 */
@Composable
private fun MiniStoreHeader(
    appCount: Int,
    updateCount: Int,
    controlsEnabled: Boolean,
    onUpdateAll: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
) {
    var searchExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.mini_store_header_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                IconButton(onClick = { searchExpanded = !searchExpanded }) {
                    Icon(
                        imageVector = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = stringResource(R.string.mini_store_search_hint),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (searchExpanded || searchQuery.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.mini_store_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.mini_store_clear_search),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.mini_store_header_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
            ) {
                Text(
                    text = stringResource(R.string.mini_store_summary, appCount, updateCount),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (updateCount > 0) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onUpdateAll,
                    enabled = controlsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mini_store_update_all, updateCount))
                }
            }
        }
    }
}

/**
 * Explicit call to action. The cloud icon alone did not communicate that
 * signing in is what makes Google Play updates appear.
 */
@Composable
private fun PlaySignInBanner(
    deviceAccount: String?,
    enabled: Boolean,
    onSignIn: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.mini_store_play_banner_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = if (deviceAccount == null) {
                    stringResource(R.string.mini_store_play_banner_no_account)
                } else {
                    stringResource(R.string.mini_store_play_banner_body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(
                // Without a Google account on the device there is nothing to sign
                // in with, so the action is blocked rather than failing later.
                onClick = onSignIn,
                enabled = enabled && deviceAccount != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                // Neutral label on purpose. Naming the device account here made it
                // look like that account is the one that gets connected, while the
                // account is actually chosen on Google's own screen.
                Text(stringResource(R.string.mini_store_play_banner_button))
            }
        }
    }
}

@Composable
private fun OperationProgressCard(
    stage: UpdateOperationStage,
    completedBytes: Long,
    totalBytes: Long,
    appName: String,
    queuedCount: Int,
    onCancelCurrent: () -> Unit,
    onCancelAll: () -> Unit,
) {
    val progress = if (totalBytes > 0 && stage == UpdateOperationStage.DOWNLOADING) {
        (completedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = appName,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancelCurrent) {
                    Text(stringResource(R.string.mini_store_cancel_update))
                }
                // Offered only when more than one app is waiting.
                if (queuedCount > 1) {
                    TextButton(onClick = onCancelAll) {
                        Text(stringResource(R.string.mini_store_cancel_all_updates))
                    }
                }
            }
            Text(
                text = when (stage) {
                    UpdateOperationStage.RESOLVING -> stringResource(R.string.mini_store_stage_resolving)
                    UpdateOperationStage.DOWNLOADING -> stringResource(R.string.mini_store_stage_downloading)
                    UpdateOperationStage.VERIFYING -> stringResource(R.string.mini_store_stage_verifying)
                    UpdateOperationStage.INSTALLING -> stringResource(R.string.mini_store_stage_installing)
                },
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.labelLarge,
            )
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                // Megabytes as well as percent, so a slow download is clearly
                // distinguishable from a stalled one.
                Text(
                    text = stringResource(
                        R.string.mini_store_download_progress_detail,
                        (progress * 100).toInt(),
                        formatMegabytes(completedBytes),
                        formatMegabytes(totalBytes),
                    ),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0)

@Composable
private fun miniStoreCategoryLabel(category: MiniStoreCategory, count: Int): String = when (category) {
    MiniStoreCategory.ALL -> stringResource(R.string.mini_store_filter_all, count)
    MiniStoreCategory.USER -> stringResource(R.string.mini_store_filter_user, count)
    MiniStoreCategory.SYSTEM -> stringResource(R.string.mini_store_filter_system, count)
}

@Composable
private fun EmptyStoreMessage(messageRes: Int) {
    Text(
        text = stringResource(messageRes),
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MiniStoreAppCard(
    app: ManagedInstalledApp,
    busy: Boolean,
    active: Boolean,
    operationStage: UpdateOperationStage?,
    operationCompletedBytes: Long,
    operationTotalBytes: Long,
    queued: Boolean,
    canQueueUpdate: Boolean,
    playSourceState: PlaySourceState,
    onUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
    onBlacklist: () -> Unit,
    preferred: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (preferred) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = rememberDrawablePainter(app.icon),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.Unspecified,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.displayName,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                    )
                    if (preferred) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.mini_store_preferred_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                    if (app.isSystemApp) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.mini_store_system_badge),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        R.string.mini_store_installed_version,
                        app.installedVersionName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                app.update?.let {
                    // The app's own update names no source: there is only one, and
                    // naming it would explain plumbing the user cannot act on.
                    val source = when (it.source) {
                        UpdateSource.GOOGLE_PLAY -> stringResource(R.string.mini_store_source_google_play)
                        UpdateSource.SIGNED_CATALOG -> stringResource(R.string.mini_store_source_signed_catalog)
                        UpdateSource.SELF_UPDATE -> null
                    }
                    Text(
                        text = if (source == null) {
                            stringResource(R.string.mini_store_available_version, it.versionName)
                        } else {
                            stringResource(
                                R.string.mini_store_available_version_with_source,
                                it.versionName,
                                source,
                            )
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // The removal restriction is not shown here: removal moved to the
                // protection tab, so this note would explain a button that is
                // not on this screen.
            }
            if (active && operationStage == null) CircularProgressIndicator(Modifier.size(28.dp))
        }
        // Progress belongs on the app being worked on, not only in a summary bar.
        if (active && operationStage != null) {
            val fraction = if (operationTotalBytes > 0 &&
                operationStage == UpdateOperationStage.DOWNLOADING
            ) {
                (operationCompletedBytes.toFloat() / operationTotalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = fraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = when (operationStage) {
                        UpdateOperationStage.RESOLVING ->
                            stringResource(R.string.mini_store_stage_resolving)
                        UpdateOperationStage.DOWNLOADING -> stringResource(
                            R.string.mini_store_download_progress_detail,
                            ((fraction ?: 0f) * 100).toInt(),
                            formatMegabytes(operationCompletedBytes),
                            formatMegabytes(operationTotalBytes),
                        )
                        UpdateOperationStage.VERIFYING ->
                            stringResource(R.string.mini_store_stage_verifying)
                        UpdateOperationStage.INSTALLING ->
                            stringResource(R.string.mini_store_stage_installing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            if (queued) {
                OutlinedButton(onClick = onCancelUpdate) {
                    Text(stringResource(R.string.mini_store_cancel_update))
                }
            } else if (!preferred) {
                // Excluding an app directly from its card, without opening the panel.
                // The app itself cannot be excluded from its own update.
                IconButton(onClick = onBlacklist, enabled = !busy) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = stringResource(R.string.mini_store_blacklist_add),
                    )
                }
            }
            Button(
                onClick = onUpdate,
                // Enabled while other updates run, so several can be queued.
                enabled = canQueueUpdate && app.update != null && !queued,
            ) {
                Text(
                    when {
                        queued && !active -> stringResource(R.string.mini_store_queued)
                        app.update != null -> stringResource(R.string.mini_store_update)
                        !app.updateCheckComplete -> stringResource(R.string.mini_store_not_checked)
                        playSourceState == PlaySourceState.SIGNED_OUT ->
                            stringResource(R.string.mini_store_no_managed_update)
                        else -> stringResource(R.string.mini_store_no_update)
                    },
                )
            }
        }
    }
}
