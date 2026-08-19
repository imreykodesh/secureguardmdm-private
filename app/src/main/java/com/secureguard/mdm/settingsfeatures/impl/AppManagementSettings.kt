package com.secureguard.mdm.settingsfeatures.impl

import com.secureguard.mdm.R
import com.secureguard.mdm.settingsfeatures.api.NavigationalSetting
import com.secureguard.mdm.settingsfeatures.api.SettingCategory
import com.secureguard.mdm.settingsfeatures.api.ToggleSetting
import com.secureguard.mdm.ui.navigation.Routes

object NavigateToAppSelectionSetting : NavigationalSetting {
    override val id: String = "navigate_app_selection"
    override val titleRes: Int = R.string.settings_item_select_apps_to_block
    override val descriptionRes: Int = R.string.settings_description_select_apps_to_block
    override val iconRes: Int = R.drawable.ic_manage_apps
    override val category: SettingCategory = SettingCategory.APP_MANAGEMENT
    override val route: String = Routes.APP_SELECTION
}

object NavigateToFirewallSetting : NavigationalSetting {
    override val id: String = "navigate_internal_firewall"
    override val titleRes: Int = R.string.settings_item_firewall
    override val descriptionRes: Int = R.string.settings_description_firewall
    override val iconRes: Int = R.drawable.ic_firewall_shield
    override val category: SettingCategory = SettingCategory.APP_MANAGEMENT
    override val route: String = Routes.FIREWALL_OVERVIEW
}

object NavigateToDeviceHealthSetting : NavigationalSetting {
    override val id: String = "navigate_device_health"
    override val titleRes: Int = R.string.device_health_title
    override val descriptionRes: Int = R.string.device_health_setting_description
    override val iconRes: Int = R.drawable.ic_firewall_shield
    override val category: SettingCategory = SettingCategory.APP_MANAGEMENT
    override val route: String = Routes.DEVICE_HEALTH
}

object ToggleMiniStorePasswordSetting : ToggleSetting {
    override val id: String = "toggle_mini_store_password"
    override val titleRes: Int = R.string.settings_item_mini_store_password
    override val descriptionRes: Int = R.string.settings_description_mini_store_password
    override val iconRes: Int = R.drawable.ic_key
    override val category: SettingCategory = SettingCategory.APP_MANAGEMENT
}
