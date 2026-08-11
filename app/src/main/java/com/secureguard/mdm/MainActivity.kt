package com.secureguard.mdm

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.kiosk.ui.KioskActivity
import com.secureguard.mdm.ui.navigation.AppNavigation
import com.secureguard.mdm.ui.theme.SecureGuardTheme
import com.secureguard.mdm.utils.SecureUpdateHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var secureUpdateHelper: SecureUpdateHelper

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var dpm: DevicePolicyManager

    private val writeSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        Log.d(TAG, "Returned from WRITE_SETTINGS screen.")
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "FOREGROUND_SERVICE_SPECIAL_USE permission granted.")
            } else {
                Log.w(TAG, "FOREGROUND_SERVICE_SPECIAL_USE permission was denied.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            runLegacyNetGuardRemovalMigration()

            val startDestinationOverride = intent.getStringExtra("start_destination")
            if (startDestinationOverride == null && settingsRepository.isKioskModeEnabled()) {
                val kioskIntent = Intent(this@MainActivity, KioskActivity::class.java)
                startActivity(kioskIntent)
                finish()
                return@launch
            }

            if (!secureUpdateHelper.coreComponentExists()) {
                throw RuntimeException("Core validation component is missing or corrupted. Halting execution.")
            }

            requestSpecialUsePermission()

            setContent {
                SecureGuardTheme {
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            !Settings.System.canWrite(this@MainActivity) &&
                            dpm.isDeviceOwnerApp(packageName)
                        ) {
                            val settingsIntent = Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:$packageName"),
                            )
                            writeSettingsLauncher.launch(settingsIntent)
                        }
                    }

                    val isFromKiosk = intent.getBooleanExtra("is_from_kiosk", false)
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        AppNavigation(
                            startDestinationOverride = startDestinationOverride,
                            isFromKiosk = isFromKiosk,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.System.canWrite(this) &&
            dpm.isDeviceOwnerApp(packageName)
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName"),
            )
            writeSettingsLauncher.launch(settingsIntent)
        }
    }

    private fun requestSpecialUsePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val permission = Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Special use foreground service permission already granted.")
                }
                else -> {
                    Log.d(TAG, "Requesting special use foreground service permission.")
                    requestPermissionLauncher.launch(permission)
                }
            }
        }
    }

    private suspend fun runLegacyNetGuardRemovalMigration() {
        if (settingsRepository.getFeatureState(NETGUARD_REMOVAL_MIGRATION_KEY)) return
        if (!dpm.isDeviceOwnerApp(packageName)) {
            Log.w(TAG, "Deferring legacy NetGuard cleanup until device-owner privileges are available.")
            return
        }

        val installed = try {
            packageManager.getPackageInfo(LEGACY_NETGUARD_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        runCatching {
            if (installed) {
                val admin = SecureGuardDeviceAdminReceiver.getComponentName(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    dpm.getAlwaysOnVpnPackage(admin) == LEGACY_NETGUARD_PACKAGE
                ) {
                    dpm.setAlwaysOnVpnPackage(admin, null, false)
                }
                dpm.setUninstallBlocked(admin, LEGACY_NETGUARD_PACKAGE, false)
            }
            settingsRepository.setFeatureState(LEGACY_INSTALL_FEATURE_KEY, false)
            settingsRepository.setFeatureState(LEGACY_ALWAYS_ON_FEATURE_KEY, false)
            settingsRepository.setFeatureState(NETGUARD_REMOVAL_MIGRATION_KEY, true)
        }.onSuccess {
            Log.i(TAG, "Legacy NetGuard policy cleanup completed; the app was not uninstalled.")
        }.onFailure { error ->
            Log.w(TAG, "Legacy NetGuard policy cleanup failed and will be retried.", error)
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val LEGACY_NETGUARD_PACKAGE = "eu.faircode.netguard"
        const val LEGACY_INSTALL_FEATURE_KEY = "install_protect_netguard"
        const val LEGACY_ALWAYS_ON_FEATURE_KEY = "force_netguard_vpn"
        const val NETGUARD_REMOVAL_MIGRATION_KEY = "netguard_removal_migration_v1"
    }
}

@Composable
private fun WriteSettingsPermissionDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.permission_dialog_title)) },
        text = { Text(stringResource(id = R.string.permission_dialog_message)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(id = R.string.permission_dialog_button_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.permission_dialog_button_cancel))
            }
        },
    )
}
