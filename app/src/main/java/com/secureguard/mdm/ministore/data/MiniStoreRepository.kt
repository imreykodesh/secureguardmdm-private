package com.secureguard.mdm.ministore.data

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import com.secureguard.mdm.R
import com.secureguard.mdm.data.local.PreferencesManager
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.ministore.inventory.InstalledPackageInventoryProvider
import com.secureguard.mdm.ministore.inventory.InstalledPackageRecord
import com.secureguard.mdm.ministore.play.PlayUpdateSource
import com.secureguard.mdm.utils.update.UpdateManager
import com.secureguard.mdm.utils.update.UpdateResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MiniStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogClient: MiniStoreCatalogClient,
    private val playUpdateSource: PlayUpdateSource,
    private val miniStorePreferences: MiniStorePreferences,
    private val preferencesManager: PreferencesManager,
    private val devicePolicyManager: DevicePolicyManager,
    private val inventoryProvider: InstalledPackageInventoryProvider,
    private val updateCheckDao: MiniStoreUpdateCheckDao,
    private val updateManager: UpdateManager,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun loadInstalledApps(): MiniStoreLoadResult = withContext(Dispatchers.IO) {
        coroutineScope {
        // Started first and awaited last: it is an independent HTTPS request to
        // the app's own update channel, and it should not add its latency on top
        // of the catalog and Play checks.
        val selfUpdateCheck: Deferred<SelfUpdateResult> = async { loadSelfUpdate() }
        val warnings = mutableListOf<String>()
        var catalogSourceState = CatalogSourceState.FAILED
        val catalogByPackage = try {
            catalogClient.fetchVerifiedCatalog().apps.associateBy { it.packageName }.also {
                catalogSourceState = CatalogSourceState.CHECKED
            }
        } catch (error: Exception) {
            Log.w(TAG, "Signed catalog check failed: ${error.javaClass.simpleName}: ${error.message}", error)
            warnings += error.message ?: "Signed catalog check failed"
            emptyMap()
        }

        val installedEntries = inventoryProvider.getAll()
            .filterNot { it.packageName == context.packageName }
            .mapNotNull { record ->
                applicationInfo(record.packageName)?.let { InstalledEntry(record, it) }
            }
        val blacklisted = miniStorePreferences.getBlacklist()
        updateCheckDao.retainOnly(
            installedEntries.mapTo(linkedSetOf()) { it.record.packageName } - blacklisted,
        )
        var playFailedPackages = emptySet<String>()
        var playSourceState = if (playUpdateSource.isConfigured()) {
            PlaySourceState.FAILED
        } else {
            PlaySourceState.SIGNED_OUT
        }
        val playCandidates = if (!playUpdateSource.isConfigured()) {
            emptyMap()
        } else try {
            val result = playUpdateSource.discover(
                installedEntries
                    .filterNot { it.record.packageName in blacklisted }
                    .associate { it.record.packageName to it.record.versionCode },
            )
            playSourceState = PlaySourceState.CHECKED
            playFailedPackages = result.failedPackages
            if (result.failedPackages.isNotEmpty()) {
                warnings += "Google Play could not check ${result.failedPackages.size} installed apps"
            }
            result.candidates
        } catch (error: Exception) {
            // Diagnostics only: the message and type, never credentials or URLs.
            Log.w(TAG, "Google Play update check failed: ${error.javaClass.simpleName}: ${error.message}", error)
            warnings += error.message ?: "Google Play update check failed"
            emptyMap()
        }

        val protectedPackages = protectedPackages()
        val apps = installedEntries.map { entry ->
            val applicationInfo = entry.applicationInfo
            val record = entry.record
            val packageName = record.packageName
            val catalogCandidate = catalogByPackage[packageName]?.takeIf { candidate ->
                candidate.versionCode.toLongOrNull()?.let { it > record.versionCode } == true &&
                    (candidate.minSdk == null || candidate.minSdk <= Build.VERSION.SDK_INT)
            }?.toCandidate()
            val updateCandidate = catalogCandidate ?: playCandidates[packageName]
            val protected = packageName in protectedPackages
            ManagedInstalledApp(
                packageName = packageName,
                displayName = applicationInfo.loadLabel(context.packageManager).toString(),
                installedVersionName = record.versionName ?: record.versionCode.toString(),
                installedVersionCode = record.versionCode,
                icon = applicationInfo.loadIcon(context.packageManager),
                update = updateCandidate,
                // A check counts as complete only when every source that could
                // hold an update actually answered for this package. Without a
                // Google Play session most installed apps were never checked.
                updateCheckComplete = updateCandidate != null ||
                    (catalogSourceState == CatalogSourceState.CHECKED &&
                        playSourceState == PlaySourceState.CHECKED &&
                        packageName !in playFailedPackages),
                isSystemApp = record.isSystemApp,
                canUninstall = !record.isSystemApp && !protected,
                protectionReason = when {
                    record.isSystemApp -> context.getString(R.string.mini_store_system_protected)
                    protected -> context.getString(R.string.mini_store_device_protected)
                    else -> null
                },
            )
        }.sortedBy { it.displayName.lowercase() }
        val selfUpdate = selfUpdateCheck.await()
        selfUpdate.warning?.let { warnings += it }
        MiniStoreLoadResult(
            apps = apps,
            sourceWarning = warnings.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n"),
            catalogSourceState = catalogSourceState,
            playSourceState = playSourceState,
            selfUpdate = selfUpdate.app,
        )
        }
    }

    /**
     * Checks the app's own update channel and turns a pending update into a list
     * entry.
     *
     * The host package stays excluded from the installed inventory above, so this
     * entry is built separately and the mini-store guards that forbid touching
     * the host package are left exactly as they are.
     */
    private suspend fun loadSelfUpdate(): SelfUpdateResult {
        // Same policy the dashboard applies, so the store cannot become a way
        // around a device where updates were switched off.
        val allUpdatesDisabled = settingsRepository.areAllUpdatesDisabled()
        val settingsLocked = settingsRepository.isSettingsLocked()
        val manualAllowed = settingsRepository.allowManualUpdateWhenLocked()
        if (allUpdatesDisabled && !(settingsLocked && manualAllowed)) {
            return SelfUpdateResult(app = null, warning = null)
        }

        val info = when (val result = updateManager.checkForUpdate()) {
            is UpdateResult.UpdateAvailable -> result.info
            is UpdateResult.NoUpdate -> return SelfUpdateResult(app = null, warning = null)
            is UpdateResult.Failure -> return SelfUpdateResult(app = null, warning = result.message)
        }

        val record = inventoryProvider.get(context.packageName)
            ?: return SelfUpdateResult(app = null, warning = null)
        val applicationInfo = applicationInfo(context.packageName)
            ?: return SelfUpdateResult(app = null, warning = null)
        // The manager compares versions too; repeated here so a stale answer
        // cannot present a downgrade as an update.
        if (info.versionCode <= record.versionCode) {
            return SelfUpdateResult(app = null, warning = null)
        }

        val displayName = applicationInfo.loadLabel(context.packageManager).toString()
        return SelfUpdateResult(
            app = ManagedInstalledApp(
                packageName = record.packageName,
                displayName = displayName,
                installedVersionName = record.versionName ?: record.versionCode.toString(),
                installedVersionCode = record.versionCode,
                icon = applicationInfo.loadIcon(context.packageManager),
                update = UpdateCandidate(
                    packageName = record.packageName,
                    displayName = displayName,
                    versionCode = info.versionCode,
                    versionName = info.versionName,
                    minSdk = null,
                    releaseNotes = info.changelog,
                    source = UpdateSource.SELF_UPDATE,
                    locator = UpdateLocator.SelfUpdate(info),
                ),
                updateCheckComplete = true,
                isSystemApp = false,
                canUninstall = false,
                protectionReason = null,
            ),
            warning = null,
        )
    }

    fun isPlaySourceConfigured(): Boolean = playUpdateSource.isConfigured()

    fun blacklist(): Set<String> = miniStorePreferences.getBlacklist()
    fun isBlacklisted(packageName: String): Boolean = packageName in miniStorePreferences.getBlacklist()

    suspend fun setBlacklisted(packageName: String, blacklisted: Boolean) {
        val updated = miniStorePreferences.getBlacklist().toMutableSet().apply {
            if (blacklisted) add(packageName) else remove(packageName)
        }
        miniStorePreferences.setBlacklist(updated)
        if (blacklisted) updateCheckDao.delete(packageName)
    }

    fun isPasswordRequired(): Boolean = miniStorePreferences.isPasswordRequired()
    fun setPasswordRequired(required: Boolean) = miniStorePreferences.setPasswordRequired(required)

    fun assertUpdateAllowed(packageName: String) {
        require(!isBlacklisted(packageName)) { "This app is blacklisted from mini-store management" }
        require(packageName != context.packageName) { "A Bloq cannot be updated from the mini-store" }
        requireNotNull(inventoryProvider.get(packageName)) { "The app is not installed" }
    }

    fun assertUninstallAllowed(packageName: String) {
        require(!isBlacklisted(packageName)) { "This app is blacklisted from mini-store management" }
        require(packageName != context.packageName) { "Cannot uninstall A Bloq from the mini-store" }
        val installed = requireNotNull(inventoryProvider.get(packageName)) { "The app is not installed" }
        require(!installed.isSystemApp) { "System apps cannot be removed from the mini-store" }
        require(packageName !in protectedPackages()) { "This app is protected because the device is using it" }
    }

    private fun applicationInfo(packageName: String): ApplicationInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        }
    }.getOrNull()

    private fun protectedPackages(): Set<String> = buildSet {
        add(context.packageName)
        devicePolicyManager.activeAdmins?.forEach { add(it.packageName) }
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let(::add)
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage?.let(::add)
        addAll(preferencesManager.loadStringSet(PreferencesManager.KEY_KIOSK_APP_PACKAGES, emptySet()))
    }

    private fun MiniStoreCatalogApp.toCandidate() = UpdateCandidate(
        packageName = packageName,
        displayName = displayName,
        versionCode = versionCode.toLong(),
        versionName = versionName,
        minSdk = minSdk,
        releaseNotes = releaseNotes,
        source = UpdateSource.SIGNED_CATALOG,
        locator = UpdateLocator.SignedCatalog(
            downloadUrl = downloadUrl,
            apkSha256 = apkSha256,
            apkSize = apkSize,
            apkSignerSha256 = apkSignerSha256,
        ),
    )

    private data class InstalledEntry(
        val record: InstalledPackageRecord,
        val applicationInfo: ApplicationInfo,
    )

    private data class SelfUpdateResult(
        val app: ManagedInstalledApp?,
        val warning: String?,
    )

    private companion object {
        const val TAG = "MiniStore"
    }
}
