package com.secureguard.mdm.ui.screens.settings

import com.secureguard.mdm.R
import com.secureguard.mdm.features.api.ProtectionFeature
import com.secureguard.mdm.settingsfeatures.api.SettingCategory
import com.secureguard.mdm.settingsfeatures.api.SettingsFeature

// Data class to hold a category of protection features (the main toggles)
data class ProtectionCategoryToggle(
    val titleResId: Int,
    val toggles: List<FeatureToggle>
)

// Data class to hold the UI state for a single protection feature toggle
data class FeatureToggle(
    val feature: ProtectionFeature,
    val isEnabled: Boolean,
    val isSupported: Boolean = true,
    val requiredApi: Int = 0,
    val conflictReasonResId: Int? = null
)

// Data class to hold a settings feature and its current state (e.g., isChecked for toggles)
data class SettingItemModel(
    val feature: SettingsFeature,
    val isChecked: Boolean = false,
    val isSupported: Boolean = true,
    val requiredApi: Int = 0
)

object FavoriteKey {
    private const val SETTING_PREFIX = "setting:"
    private const val PROTECTION_PREFIX = "protection:"

    fun setting(id: String): String = "$SETTING_PREFIX$id"
    fun protection(id: String): String = "$PROTECTION_PREFIX$id"
}

internal fun modularCategoryKey(category: SettingCategory): String =
    "modular_category:${category.name}"

internal fun protectionCategoryKey(category: ProtectionCategoryToggle): String = when (category.titleResId) {
    R.string.category_device_management -> "protection_category:device_management"
    R.string.category_hardware -> "protection_category:hardware"
    R.string.category_network -> "protection_category:network"
    R.string.category_apps -> "protection_category:apps"
    R.string.category_vpn -> "protection_category:vpn"
    R.string.category_calls_sms -> "protection_category:calls_sms"
    R.string.category_ui -> "protection_category:ui"
    R.string.category_advanced -> "protection_category:advanced"
    else -> "protection_category:${category.titleResId}"
}

data class SettingsUiState(
    // State for the main protection features
    val protectionCategoryToggles: List<ProtectionCategoryToggle> = emptyList(),

    // State for the new modular settings items, grouped by category
    val settingItemsByCategory: Map<SettingCategory, List<SettingItemModel>> = emptyMap(),

    val favoriteKeys: Set<String> = emptySet(),
    val collapsedCategoryKeys: Set<String> = emptySet(),
    val hasUnsavedChanges: Boolean = false,
    val unsavedChangeCount: Int = 0,
    val canUndo: Boolean = false,
    val isLoading: Boolean = true,
    val snackbarMessage: String? = null,
    val isAutoUpdateEnabled: Boolean = true // Kept for the main save logic
)

sealed class SettingsEvent {
    // Events for main protection features
    data class OnToggleProtectionFeature(val featureId: String, val isEnabled: Boolean) : SettingsEvent()
    data class OnVpnPermissionResult(val granted: Boolean) : SettingsEvent()

    // Generic events for the new settings system
    data class OnToggleSettingChanged(val settingId: String, val isChecked: Boolean) : SettingsEvent()
    data class OnFavoriteToggled(val favoriteKey: String) : SettingsEvent()
    data class OnCategoryCollapsedToggled(val categoryKey: String) : SettingsEvent()
    data class OnAllCategoriesCollapsedChanged(
        val categoryKeys: Set<String>,
        val collapsed: Boolean
    ) : SettingsEvent()
    object OnUndoClick : SettingsEvent()
    data class OnActionSettingClicked(val settingId: String) : SettingsEvent()
    data class OnLockSettingsConfirmed(val allowManualUpdate: Boolean) : SettingsEvent()

    // Removal options dialog events
    object OnRegularRemovalSelected : SettingsEvent()
    object OnTransferOwnershipSelected : SettingsEvent()
    object OnDismissRemovalOptionsDialog : SettingsEvent()

    // Device admin selection events
    object OnDeviceAdminSelectionDismissed : SettingsEvent()
    data class OnDeviceAdminSelected(val deviceAdminItem: DeviceAdminItem) : SettingsEvent()
    object OnDeviceAdminTransferConfirmed : SettingsEvent()
    object OnDeviceAdminTransferCancelled : SettingsEvent()

    // Error dialog events
    object OnErrorDialogDismissed : SettingsEvent()

    // General events
    object OnSaveClick : SettingsEvent()
    object OnSnackbarShown : SettingsEvent()
}
