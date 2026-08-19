package com.secureguard.mdm.ui.screens.devicehealth

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secureguard.mdm.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceHealthScreen(
    viewModel: DeviceHealthViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.device_health_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.isRefreshing,
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(id = R.string.device_health_refresh),
                            )
                        }
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        if (state.isRefreshing && state.protections.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            DeviceHealthContent(
                state = state,
                modifier = Modifier.padding(scaffoldPadding),
            )
        }
    }
}

@Composable
private fun DeviceHealthContent(
    state: DeviceHealthState,
    modifier: Modifier = Modifier,
) {
    val deviceChecks = listOf(
        HealthCheck(R.string.device_health_device_owner, state.deviceOwnerStatus),
        HealthCheck(R.string.device_health_admin_active, state.adminActiveStatus),
    )
    val vpnChecks = listOf(
        HealthCheck(R.string.device_health_vpn_permission, state.vpnPermissionStatus),
        HealthCheck(R.string.device_health_vpn_always_on, state.vpnAlwaysOnStatus),
        HealthCheck(R.string.device_health_vpn_active, state.vpnActiveStatus),
    )
    val coreAttention = (deviceChecks + vpnChecks).filter { it.status != DeviceHealthStatus.ACTIVE }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SummaryCard(isHealthy = state.isHealthy)
        }

        item {
            CheckGroupCard(
                titleRes = R.string.device_health_device_admin,
                checks = deviceChecks,
            )
        }

        item {
            CheckGroupCard(
                titleRes = R.string.device_health_vpn,
                checks = vpnChecks,
            )
        }

        item {
            SectionTitle(R.string.device_health_active_protections)
        }

        if (state.activeProtections.isEmpty()) {
            item {
                EmptyStateCard(R.string.device_health_no_active_protections)
            }
        } else {
            items(
                items = state.activeProtections,
                key = { "active_${it.id}" },
            ) { protection ->
                ProtectionStatusRow(
                    protection = protection,
                    status = DeviceHealthStatus.ACTIVE,
                )
            }
        }

        if (coreAttention.isNotEmpty() || state.protectionsNeedingAttention.isNotEmpty()) {
            item {
                SectionTitle(R.string.device_health_needs_attention)
            }

            items(
                items = coreAttention,
                key = { "check_${it.labelRes}" },
            ) { check ->
                HealthCheckRow(check)
            }

            items(
                items = state.protectionsNeedingAttention,
                key = { "attention_${it.id}" },
            ) { protection ->
                ProtectionStatusRow(
                    protection = protection,
                    status = protection.displayStatus,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(4.dp)) }
    }
}

@Composable
private fun SummaryCard(isHealthy: Boolean) {
    val status = if (isHealthy) DeviceHealthStatus.ACTIVE else DeviceHealthStatus.INACTIVE
    val colors = statusColors(status)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isHealthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = colors.content,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(
                    id = if (isHealthy) {
                        R.string.device_health_summary_ok
                    } else {
                        R.string.device_health_summary_attention
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.content,
            )
        }
    }
}

@Composable
private fun CheckGroupCard(
    @StringRes titleRes: Int,
    checks: List<HealthCheck>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(id = titleRes),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            checks.forEachIndexed { index, check ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                HealthCheckRow(check)
            }
        }
    }
}

@Composable
private fun HealthCheckRow(check: HealthCheck) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(id = check.labelRes),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.width(12.dp))
        StatusBadge(check.status)
    }
}

@Composable
private fun ProtectionStatusRow(
    protection: ProtectionHealth,
    status: DeviceHealthStatus,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = protection.iconRes),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = protection.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(id = protection.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            StatusBadge(status)
        }
    }
}

@Composable
private fun StatusBadge(status: DeviceHealthStatus) {
    val colors = statusColors(status)
    val icon = when (status) {
        DeviceHealthStatus.ACTIVE -> Icons.Default.CheckCircle
        DeviceHealthStatus.INACTIVE,
        DeviceHealthStatus.CHECK_FAILED,
        -> Icons.Default.Warning
        DeviceHealthStatus.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.content,
        )
        Text(
            text = stringResource(id = status.labelRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
        )
    }
}

@Composable
private fun SectionTitle(@StringRes titleRes: Int) {
    Text(
        text = stringResource(id = titleRes),
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun EmptyStateCard(@StringRes messageRes: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = stringResource(id = messageRes),
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class HealthCheck(
    @StringRes val labelRes: Int,
    val status: DeviceHealthStatus,
)

private data class StatusColors(
    val content: Color,
    val container: Color,
)

@Composable
private fun statusColors(status: DeviceHealthStatus): StatusColors = when (status) {
    DeviceHealthStatus.ACTIVE -> StatusColors(
        content = Color(0xFF2E7D32),
        container = Color(0xFFE8F5E9),
    )
    DeviceHealthStatus.INACTIVE,
    DeviceHealthStatus.CHECK_FAILED,
    -> StatusColors(
        content = MaterialTheme.colorScheme.error,
        container = MaterialTheme.colorScheme.errorContainer,
    )
    DeviceHealthStatus.UNKNOWN -> StatusColors(
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        container = MaterialTheme.colorScheme.surfaceVariant,
    )
}

private val DeviceHealthStatus.labelRes: Int
    @StringRes get() = when (this) {
        DeviceHealthStatus.ACTIVE -> R.string.device_health_status_active
        DeviceHealthStatus.INACTIVE -> R.string.device_health_status_inactive
        DeviceHealthStatus.UNKNOWN -> R.string.device_health_status_unknown
        DeviceHealthStatus.CHECK_FAILED -> R.string.device_health_status_check_failed
    }
