package com.secureguard.mdm.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.secureguard.mdm.R

/**
 * The two faces of app management, reachable from one entry point.
 *
 * Maintenance and policy stay on separate tabs rather than mixed into one row of
 * buttons: excluding an app from updates and denying its use look similar but
 * differ greatly in consequence.
 *
 * Icons are vector assets from the Material set, not emoji, so they scale and
 * follow the theme colour.
 */
enum class AppCenterTab { UPDATES, PROTECTION }

@Composable
fun AppCenterTabs(
    selected: AppCenterTab,
    onSelect: (AppCenterTab) -> Unit,
) {
    TabRow(selectedTabIndex = selected.ordinal) {
        AppCenterTabItem(
            selected = selected == AppCenterTab.UPDATES,
            icon = Icons.Default.SystemUpdate,
            labelRes = R.string.app_center_tab_updates,
            onClick = { onSelect(AppCenterTab.UPDATES) },
        )
        AppCenterTabItem(
            selected = selected == AppCenterTab.PROTECTION,
            icon = Icons.Default.Shield,
            labelRes = R.string.app_center_tab_protection,
            onClick = { onSelect(AppCenterTab.PROTECTION) },
        )
    }
}

@Composable
private fun AppCenterTabItem(
    selected: Boolean,
    icon: ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(stringResource(labelRes)) },
    )
}
