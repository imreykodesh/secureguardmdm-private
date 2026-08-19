package com.secureguard.mdm.appblocker

enum class AppFilterType {
    USER_ONLY,
    ALL_EXCEPT_CORE,
    LAUNCHER_ONLY,
    ALL
}

data class AppBlockerUiState(
    val displayedAppsForSelection: List<AppInfo> = emptyList(),
    val displayedBlockedApps: List<AppInfo> = emptyList(),
    val displayedSuspendedApps: List<AppInfo> = emptyList(),
    val isLoading: Boolean = true,
    val currentFilter: AppFilterType = AppFilterType.USER_ONLY,
    val searchQuery: String = "",
    val selectionForUnblock: Set<String> = emptySet(),
    val selectionForUnsuspend: Set<String> = emptySet(),
    val showCriticalAppsWarning: Boolean = false,
    val criticalAppsDetected: List<AppInfo> = emptyList(),
    // Protection actions are sensitive, so this screen is always behind the
    // management password regardless of the Mini Store entry setting.
    val accessGranted: Boolean = false,
    val isAuthenticating: Boolean = false,
    val passwordError: String? = null,
    val statusFilter: AppStatusFilter = AppStatusFilter.ALL,
    val blockedCount: Int = 0,
    val suspendedCount: Int = 0,
    val pendingUninstall: AppInfo? = null,
    val message: String? = null,
)

/** Narrows the single app list to what is currently blocked or suspended. */
enum class AppStatusFilter { ALL, BLOCKED, SUSPENDED }

sealed class AppBlockerEvent {
    data class OnFilterChanged(val newFilter: AppFilterType) : AppBlockerEvent()
    data class OnAppSelectionChanged(val packageName: String, val isBlocked: Boolean) : AppBlockerEvent()
    data class OnAppSuspensionChanged(val packageName: String, val isSuspended: Boolean) : AppBlockerEvent()
    object OnSaveRequest : AppBlockerEvent() // יישמר מיידית
    object OnDismissPasswordPrompt : AppBlockerEvent() // נשאר למקרה שימוש עתידי, לא מזיק
    data class OnAddPackageManually(val packageName: String) : AppBlockerEvent()
    data class OnToggleUnblockSelection(val packageName: String) : AppBlockerEvent()
    data class OnToggleUnsuspendSelection(val packageName: String) : AppBlockerEvent()
    object OnUnblockSelectedRequest : AppBlockerEvent() // ישוחרר מיידית
    object OnUnsuspendSelectedRequest : AppBlockerEvent() // ישוחרר מיידית
    data class OnSearchQueryChanged(val query: String) : AppBlockerEvent()
    object OnDismissCriticalAppsWarning : AppBlockerEvent()
    data class OnStatusFilterChanged(val filter: AppStatusFilter) : AppBlockerEvent()
    data class OnSubmitPassword(val password: String) : AppBlockerEvent()
    data class OnRequestUninstall(val app: AppInfo) : AppBlockerEvent()
    object OnConfirmUninstall : AppBlockerEvent()
    object OnCancelUninstall : AppBlockerEvent()
    object OnClearMessage : AppBlockerEvent()
}
