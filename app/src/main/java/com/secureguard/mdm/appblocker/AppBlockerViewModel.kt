package com.secureguard.mdm.appblocker

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.R
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import com.secureguard.mdm.data.db.BlockedAppCache
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.ministore.domain.MiniStoreAccessGate
import com.secureguard.mdm.ministore.install.MiniStorePackageOperator
import com.secureguard.mdm.security.PasswordManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

private const val TAG = "AppBlockerViewModel"
private const val OWN_PACKAGE = "com.secureguard.mdm"
private const val APP_LOAD_CHUNK = 40

// Critical system apps that should not be blocked as they may cause device instability
private val CRITICAL_SYSTEM_PACKAGES = setOf(
    "com.android.settings",           // Settings app
    "com.android.systemui",          // System UI
    "android",                       // Android System
    "com.google.android.gsf",         // Google Services Framework
    "com.android.launcher3",          // Default launcher
    "com.google.android.launcher",    // Google launcher
)

@HiltViewModel
class AppBlockerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val dpm: DevicePolicyManager,
    private val passwordManager: PasswordManager,
    private val accessGate: MiniStoreAccessGate,
    private val packageOperator: MiniStorePackageOperator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppBlockerUiState())
    val uiState = _uiState.asStateFlow()

    private var allAppsMasterList: List<AppInfo> = emptyList()
    private var allBlockedAppsMasterList: List<AppInfo> = emptyList()
    private var allSuspendedAppsMasterList: List<AppInfo> = emptyList()

    private val adminComponentName by lazy { SecureGuardDeviceAdminReceiver.getComponentName(context) }
    private val iconsDir by lazy { File(context.filesDir, "app_icons").apply { mkdirs() } }

    init {
        checkAccess()
    }

    /**
     * Blocking, suspending and removing apps are policy decisions, so this
     * screen always asks for the management password. An already open Mini Store
     * authorisation window counts, so the user is not asked twice in a row.
     */
    private fun checkAccess() {
        viewModelScope.launch {
            val privileged = accessGate.isPrivileged()
            val passwordSet = passwordManager.isPasswordSet()
            val granted = privileged || !passwordSet
            Log.i(TAG, "access check: privileged=$privileged passwordSet=$passwordSet granted=$granted")
            _uiState.update { it.copy(accessGranted = granted, isLoading = granted) }
            if (granted) loadAllData()
        }
    }

    private fun submitPassword(password: String) {
        if (_uiState.value.isAuthenticating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthenticating = true, passwordError = null) }
            val accepted = passwordManager.verifyPassword(password)
            Log.i(TAG, "password submitted: accepted=$accepted")
            if (accepted) {
                accessGate.grant()
                _uiState.update {
                    it.copy(isAuthenticating = false, accessGranted = true, passwordError = null)
                }
                loadAllData()
            } else {
                _uiState.update {
                    it.copy(
                        isAuthenticating = false,
                        passwordError = context.getString(R.string.dialog_error_wrong_password),
                    )
                }
            }
        }
    }

    fun onEvent(event: AppBlockerEvent) {
        when (event) {
            is AppBlockerEvent.OnFilterChanged -> onFilterChanged(event.newFilter)
            is AppBlockerEvent.OnAppSelectionChanged -> onAppSelectionChanged(event.packageName, event.isBlocked)
            is AppBlockerEvent.OnAppSuspensionChanged -> onAppSuspensionChanged(event.packageName, event.isSuspended)
            is AppBlockerEvent.OnSaveRequest -> saveAndApplyChanges() // קריאה ישירה
            is AppBlockerEvent.OnUnblockSelectedRequest -> unblockSelectedApps() // קריאה ישירה
            is AppBlockerEvent.OnUnsuspendSelectedRequest -> unsuspendSelectedApps() // קריאה ישירה
            is AppBlockerEvent.OnAddPackageManually -> addPackageManually(event.packageName)
            is AppBlockerEvent.OnToggleUnblockSelection -> toggleUnblockSelection(event.packageName)
            is AppBlockerEvent.OnToggleUnsuspendSelection -> toggleUnsuspendSelection(event.packageName)
            is AppBlockerEvent.OnSearchQueryChanged -> onSearchQueryChanged(event.query)
            is AppBlockerEvent.OnDismissPasswordPrompt -> { /* No longer needed */ }
            is AppBlockerEvent.OnDismissCriticalAppsWarning -> dismissCriticalAppsWarning()
            is AppBlockerEvent.OnStatusFilterChanged -> onStatusFilterChanged(event.filter)
            is AppBlockerEvent.OnSubmitPassword -> submitPassword(event.password)
            is AppBlockerEvent.OnRequestUninstall -> requestUninstall(event.app)
            is AppBlockerEvent.OnConfirmUninstall -> confirmUninstall()
            is AppBlockerEvent.OnCancelUninstall -> _uiState.update { it.copy(pendingUninstall = null) }
            is AppBlockerEvent.OnClearMessage -> _uiState.update { it.copy(message = null) }
        }
    }

    /**
     * One pass over the package manager for all three lists.
     *
     * Loading each list separately meant three `queryIntentActivities` calls and
     * a second round of label and icon decoding for every blocked or suspended
     * app, which is what made the screen slow to appear on a device with a few
     * hundred packages. Labels and icons are now decoded in parallel, and the
     * blocked and suspended lists are derived from the same snapshot; the
     * package manager is consulted again only for apps that are no longer
     * installed and exist in the cache alone.
     */
    fun loadAllData() {
        if (!_uiState.value.accessGranted) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val started = SystemClock.elapsedRealtime()
            allAppsMasterList = getInstalledApps()
            applyFilter()
            // The list is published before the derived lists are built, so the
            // user is not kept waiting on work they cannot see yet.
            _uiState.update { it.copy(isLoading = false) }

            val installedByPackage = allAppsMasterList.associateBy { it.packageName }
            allBlockedAppsMasterList = derivedList(
                packages = settingsRepository.getBlockedAppPackages(),
                installedByPackage = installedByPackage,
                blocked = true,
            )
            allSuspendedAppsMasterList = derivedList(
                packages = settingsRepository.getSuspendedAppPackages(),
                installedByPackage = installedByPackage,
                blocked = false,
            )
            applyBlockedAppsFilter()
            applySuspendedAppsFilter()
            if (_uiState.value.statusFilter != AppStatusFilter.ALL) applyFilter()
            _uiState.update {
                it.copy(
                    blockedCount = allBlockedAppsMasterList.size,
                    suspendedCount = allSuspendedAppsMasterList.size,
                )
            }
            Log.i(
                TAG,
                "loaded ${allAppsMasterList.size} apps in " +
                    "${SystemClock.elapsedRealtime() - started}ms",
            )
        }
    }

    /**
     * Blocked and suspended entries for the given packages, reusing what was
     * already resolved for the installed list.
     */
    private suspend fun derivedList(
        packages: Set<String>,
        installedByPackage: Map<String, AppInfo>,
        blocked: Boolean,
    ): List<AppInfo> {
        val missing = packages.filter { it !in installedByPackage }
        val cached = if (missing.isEmpty()) {
            emptyMap()
        } else {
            withContext(Dispatchers.IO) {
                settingsRepository.getBlockedAppsCache().associateBy { it.packageName }
            }
        }
        return packages.mapNotNull { packageName ->
            installedByPackage[packageName]?.copy(
                isBlocked = blocked,
                isSuspended = !blocked,
            ) ?: cached[packageName]?.let { entry ->
                AppInfo(
                    appName = entry.appName,
                    packageName = packageName,
                    icon = loadIconFromFile(entry.iconPath) ?: createDefaultIcon(),
                    isBlocked = blocked,
                    isSystemApp = false,
                    isLauncherApp = false,
                    isSuspended = !blocked,
                    isInstalled = false,
                )
            }
        }.sortedBy { it.appName.lowercase() }
    }

    private fun onStatusFilterChanged(filter: AppStatusFilter) {
        if (filter == _uiState.value.statusFilter) return
        _uiState.update {
            it.copy(
                statusFilter = filter,
                selectionForUnblock = emptySet(),
                selectionForUnsuspend = emptySet(),
            )
        }
        applyFilter()
    }

    private fun requestUninstall(app: AppInfo) {
        if (!_uiState.value.accessGranted || !app.isInstalled) return
        _uiState.update { it.copy(pendingUninstall = app) }
    }

    /**
     * Removal reuses the Mini Store operator so the same guards apply: no system
     * apps, nothing the device depends on, and never A Bloq itself.
     */
    private fun confirmUninstall() {
        val app = _uiState.value.pendingUninstall ?: return
        _uiState.update { it.copy(pendingUninstall = null, isLoading = true) }
        viewModelScope.launch {
            val result = runCatching { packageOperator.uninstall(app.packageName) }
            val message = result.fold(
                onSuccess = { "${app.appName} הוסרה" },
                onFailure = { error ->
                    Log.w(TAG, "uninstall failed for ${app.packageName}", error)
                    "ההסרה נכשלה: ${error.message ?: "שגיאה לא ידועה"}"
                },
            )
            _uiState.update { it.copy(message = message, isLoading = false) }
            loadAllData()
        }
    }

    private fun onFilterChanged(newFilter: AppFilterType) {
        if (newFilter != _uiState.value.currentFilter) {
            _uiState.update { it.copy(currentFilter = newFilter) }
            applyFilter()
        }
    }

    private fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFilter()
        applyBlockedAppsFilter()
        applySuspendedAppsFilter()
    }

    private fun applyFilter() {
        val query = _uiState.value.searchQuery.lowercase()
        // The blocked and suspended views are served from their own master lists
        // so that apps already uninstalled, or system apps hidden by the source
        // filter, stay visible and releasable.
        var filteredList = when (_uiState.value.statusFilter) {
            AppStatusFilter.BLOCKED -> allBlockedAppsMasterList
            AppStatusFilter.SUSPENDED -> allSuspendedAppsMasterList
            AppStatusFilter.ALL -> when (_uiState.value.currentFilter) {
                AppFilterType.USER_ONLY -> allAppsMasterList.filter { it.isInstalled && !it.isSystemApp }
                AppFilterType.ALL_EXCEPT_CORE -> allAppsMasterList.filter { it.isInstalled && !it.packageName.startsWith("com.android.") }
                AppFilterType.LAUNCHER_ONLY -> allAppsMasterList.filter { it.isLauncherApp }
                AppFilterType.ALL -> allAppsMasterList.filter { it.isInstalled }
            }
        }
        if (query.isNotBlank()) {
            filteredList = filteredList.filter {
                it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
        _uiState.update { it.copy(displayedAppsForSelection = filteredList) }
    }

    private fun applyBlockedAppsFilter() {
        val query = _uiState.value.searchQuery.lowercase()
        val filteredList = if(query.isNotBlank()) {
            allBlockedAppsMasterList.filter {
                it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        } else {
            allBlockedAppsMasterList
        }
        _uiState.update { it.copy(displayedBlockedApps = filteredList) }
    }

    private fun applySuspendedAppsFilter() {
        val query = _uiState.value.searchQuery.lowercase()
        val filteredList = if (query.isNotBlank()) {
            allSuspendedAppsMasterList.filter {
                it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        } else {
            allSuspendedAppsMasterList
        }
        _uiState.update { it.copy(displayedSuspendedApps = filteredList) }
    }

    private suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherPackages = pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }.toSet()

        val allInstalledAppsInfo = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val blockedAppPackages = settingsRepository.getBlockedAppPackages()
        val suspendedAppPackages = settingsRepository.getSuspendedAppPackages()
        // Label and icon decoding is the expensive part, a few milliseconds per
        // package, so the list is built on several threads instead of one.
        coroutineScope {
            allInstalledAppsInfo
                .filter { it.packageName != OWN_PACKAGE }
                .chunked(APP_LOAD_CHUNK)
                .map { chunk ->
                    async {
                        chunk.map { appInfo ->
                            AppInfo(
                                appName = appInfo.loadLabel(pm).toString(),
                                packageName = appInfo.packageName,
                                icon = appInfo.loadIcon(pm),
                                isBlocked = blockedAppPackages.contains(appInfo.packageName),
                                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                isLauncherApp = launcherPackages.contains(appInfo.packageName),
                                isSuspended = suspendedAppPackages.contains(appInfo.packageName),
                                isInstalled = true,
                            )
                        }
                    }
                }
                .awaitAll()
                .flatten()
                .sortedBy { it.appName.lowercase() }
        }
    }

    private fun onAppSelectionChanged(packageName: String, isBlocked: Boolean) {
        allAppsMasterList = allAppsMasterList.map {
            if (it.packageName == packageName) it.copy(isBlocked = isBlocked, isSuspended = if (isBlocked) false else it.isSuspended) else it
        }
        applyFilter()
    }

    private fun onAppSuspensionChanged(packageName: String, isSuspended: Boolean) {
        if (isSuspended && CRITICAL_SYSTEM_PACKAGES.contains(packageName)) {
            val criticalAppInfos = allAppsMasterList.filter { it.packageName == packageName }
            _uiState.update { it.copy(showCriticalAppsWarning = true, criticalAppsDetected = criticalAppInfos) }
            return
        }
        allAppsMasterList = allAppsMasterList.map {
            if (it.packageName == packageName) it.copy(isSuspended = isSuspended, isBlocked = if (isSuspended) false else it.isBlocked) else it
        }
        applyFilter()
    }

    private fun saveAndApplyChanges() {
        viewModelScope.launch(Dispatchers.IO) {
            val oldBlockedSet = settingsRepository.getBlockedAppPackages()
            val oldSuspendedSet = settingsRepository.getSuspendedAppPackages()
            val newlySelectedApps = allAppsMasterList.filter { it.isBlocked }.map { it.packageName }.toSet()
            val newlySuspendedApps = allAppsMasterList.filter { it.isSuspended }.map { it.packageName }.toSet()
            val finalBlockedSet = oldBlockedSet + newlySelectedApps

            val finalSuspendedSet = (oldSuspendedSet + newlySuspendedApps) - finalBlockedSet

            val combinedSet = finalBlockedSet + finalSuspendedSet
            if (combinedSet.contains(OWN_PACKAGE)) {
                Log.w(TAG, "Skipping own package from block/suspend lists")
            }

            if (finalBlockedSet == oldBlockedSet && finalSuspendedSet == oldSuspendedSet) {
                Log.d(TAG, "No changes to save.")
                return@launch
            }

            // Check for critical system apps (block/suspend)
            val criticalApps = (newlySelectedApps + newlySuspendedApps).filter { CRITICAL_SYSTEM_PACKAGES.contains(it) }
            if (criticalApps.isNotEmpty()) {
                val criticalAppInfos = allAppsMasterList.filter { criticalApps.contains(it.packageName) }
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(showCriticalAppsWarning = true, criticalAppsDetected = criticalAppInfos) }
                }
                return@launch
            }

            settingsRepository.setBlockedAppPackages(finalBlockedSet - OWN_PACKAGE)
            settingsRepository.setSuspendedAppPackages(finalSuspendedSet - OWN_PACKAGE)

            val appsToCache = allAppsMasterList.filter {
                combinedSet.contains(it.packageName) && !oldBlockedSet.contains(it.packageName) && !oldSuspendedSet.contains(it.packageName)
            }
            appsToCache.forEach { cacheAppInfo(it) }

            val packagesToUnsuspend = oldSuspendedSet - finalSuspendedSet
            packagesToUnsuspend.forEach { packageName ->
                try {
                    dpm.setPackagesSuspended(adminComponentName, arrayOf(packageName), false)
                    Log.d(TAG, "Set suspended state for $packageName to false (auto)")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not unsuspend $packageName", e)
                }
            }

            newlySelectedApps.forEach { packageName ->
                try {
                    dpm.setApplicationHidden(adminComponentName, packageName, true)
                    Log.d(TAG, "Set hidden state for $packageName to true")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not change hidden state for $packageName", e)
                }
            }

            newlySuspendedApps.forEach { packageName ->
                try {
                    dpm.setPackagesSuspended(adminComponentName, arrayOf(packageName), true)
                    Log.d(TAG, "Set suspended state for $packageName to true")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not change suspended state for $packageName", e)
                }
            }

            withContext(Dispatchers.Main) { loadAllData() }
        }
    }

    private fun toggleUnblockSelection(packageName: String) {
        val currentSelection = _uiState.value.selectionForUnblock.toMutableSet()
        if (currentSelection.contains(packageName)) currentSelection.remove(packageName) else currentSelection.add(packageName)
        _uiState.update { it.copy(selectionForUnblock = currentSelection) }
    }

    private fun toggleUnsuspendSelection(packageName: String) {
        val currentSelection = _uiState.value.selectionForUnsuspend.toMutableSet()
        if (currentSelection.contains(packageName)) currentSelection.remove(packageName) else currentSelection.add(packageName)
        _uiState.update { it.copy(selectionForUnsuspend = currentSelection) }
    }

    private fun unblockSelectedApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val packagesToUnblock = _uiState.value.selectionForUnblock
            if (packagesToUnblock.isEmpty()) return@launch
            val currentBlocked = settingsRepository.getBlockedAppPackages().toMutableSet()
            currentBlocked.removeAll(packagesToUnblock)
            settingsRepository.setBlockedAppPackages(currentBlocked)
            removeAppsFromCache(packagesToUnblock.toList())
            packagesToUnblock.forEach { packageName ->
                try {
                    dpm.setApplicationHidden(adminComponentName, packageName, false)
                    Log.d(TAG, "Set hidden state for $packageName to false (unblocked)")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not unhide $packageName", e)
                }
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(selectionForUnblock = emptySet()) }
                loadAllData()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun unsuspendSelectedApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val packagesToUnsuspend = _uiState.value.selectionForUnsuspend
            if (packagesToUnsuspend.isEmpty()) return@launch
            val currentSuspended = settingsRepository.getSuspendedAppPackages().toMutableSet()
            currentSuspended.removeAll(packagesToUnsuspend)
            settingsRepository.setSuspendedAppPackages(currentSuspended)
            removeAppsFromCache(packagesToUnsuspend.toList())
            packagesToUnsuspend.forEach { packageName ->
                try {
                    dpm.setPackagesSuspended(adminComponentName, arrayOf(packageName), false)
                    Log.d(TAG, "Set suspended state for $packageName to false (unsuspend)")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not unsuspend $packageName", e)
                }
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(selectionForUnsuspend = emptySet()) }
                loadAllData()
            }
        }
    }

    fun addPackageManually(packageName: String): String? {
        if (packageName.isBlank()) return "שם החבילה לא יכול להיות ריק."
        if (packageName == OWN_PACKAGE) return "לא ניתן להוסיף את אפליקציית המערכת לרשימה."

        // Check if it's a critical system app
        if (CRITICAL_SYSTEM_PACKAGES.contains(packageName)) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val criticalApp = AppInfo(
                    appName = appInfo.loadLabel(pm).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(pm),
                    isBlocked = false,
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isLauncherApp = false,
                    isSuspended = false,
                    isInstalled = true
                )
                _uiState.update { it.copy(showCriticalAppsWarning = true, criticalAppsDetected = listOf(criticalApp)) }
            } catch (e: PackageManager.NameNotFoundException) {
                return "החבילה '$packageName' לא נמצאה במכשיר."
            }
            return null // Don't show error, show warning instead
        }

        viewModelScope.launch {
            val currentlyBlockedPackages = settingsRepository.getBlockedAppPackages()
            val currentlySuspendedPackages = settingsRepository.getSuspendedAppPackages()
            allAppsMasterList = allAppsMasterList.map {
                it.copy(
                    isBlocked = currentlyBlockedPackages.contains(it.packageName),
                    isSuspended = currentlySuspendedPackages.contains(it.packageName)
                )
            }
        }

        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appAlreadyInList = allAppsMasterList.any { it.packageName == packageName }

            val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val isLauncher = pm.queryIntentActivities(launcherIntent, 0).any { it.activityInfo.packageName == packageName }

            if (!appAlreadyInList) {
                val newApp = AppInfo(
                    appName = appInfo.loadLabel(pm).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(pm),
                    isBlocked = true,
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isLauncherApp = isLauncher,
                    isSuspended = false,
                    isInstalled = true
                )
                allAppsMasterList = (allAppsMasterList + newApp).sortedBy { it.appName.lowercase() }
            }

            onAppSelectionChanged(packageName, true)
            return null
        } catch (e: PackageManager.NameNotFoundException) {
            return "החבילה '$packageName' לא נמצאה במכשיר."
        }
    }

    private suspend fun cacheAppInfo(appInfo: AppInfo) {
        if (!appInfo.isInstalled) return
        val iconPath = saveIconToFile(appInfo.packageName, appInfo.icon) ?: return
        val cacheEntry = BlockedAppCache(
            packageName = appInfo.packageName, appName = appInfo.appName, iconPath = iconPath
        )
        settingsRepository.addAppToCache(cacheEntry)
    }

    private suspend fun removeAppsFromCache(packageNames: List<String>) {
        settingsRepository.removeAppsFromCache(packageNames)
        packageNames.forEach {
            try {
                File(iconsDir, "$it.png").delete()
            } catch (e: Exception) { Log.e(TAG, "Failed to delete icon for $it") }
        }
    }

    private fun saveIconToFile(packageName: String, drawable: Drawable): String? {
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
            val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
            try {
                val bmp = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create bitmap from drawable for $packageName", e)
                return null
            }
        }

        try {
            val file = File(iconsDir, "$packageName.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                return file.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving icon to file for $packageName", e)
        }
        return null
    }

    private fun dismissCriticalAppsWarning() {
        _uiState.update { it.copy(showCriticalAppsWarning = false, criticalAppsDetected = emptyList()) }
        // Also reset the blocked selections for critical apps
        val criticalPackages = CRITICAL_SYSTEM_PACKAGES
        allAppsMasterList = allAppsMasterList.map {
            if (criticalPackages.contains(it.packageName)) it.copy(isBlocked = false, isSuspended = false) else it
        }
        applyFilter()
    }

    private fun loadIconFromFile(path: String): Drawable? = Drawable.createFromPath(path)
    private fun createDefaultIcon(): Drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!
}
