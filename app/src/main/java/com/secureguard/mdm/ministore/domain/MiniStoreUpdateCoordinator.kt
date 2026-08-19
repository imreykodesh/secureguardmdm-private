package com.secureguard.mdm.ministore.domain

import android.content.Context
import com.secureguard.mdm.ministore.data.MiniStorePreferences
import com.secureguard.mdm.ministore.data.MiniStoreUpdateCheckDao
import com.secureguard.mdm.ministore.data.MiniStoreUpdateCheckEntity
import com.secureguard.mdm.ministore.data.MiniStoreUpdateCheckStatus
import com.secureguard.mdm.ministore.inventory.InstalledPackageInventoryProvider
import com.secureguard.mdm.ministore.play.PlayUpdateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MiniStoreUpdateCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inventoryProvider: InstalledPackageInventoryProvider,
    private val playUpdateSource: PlayUpdateSource,
    private val preferences: MiniStorePreferences,
    private val updateCheckDao: MiniStoreUpdateCheckDao,
) {
    suspend fun checkPackage(packageName: String): CheckOutcome {
        if (packageName == context.packageName || packageName in preferences.getBlacklist()) {
            updateCheckDao.delete(packageName)
            return CheckOutcome.COMPLETE
        }

        val installed = inventoryProvider.get(packageName)
        if (installed == null) {
            updateCheckDao.delete(packageName)
            return CheckOutcome.COMPLETE
        }

        if (!playUpdateSource.isConfigured()) {
            save(
                packageName = packageName,
                installedVersionCode = installed.versionCode,
                status = MiniStoreUpdateCheckStatus.SOURCE_DISABLED,
                failureCode = null,
            )
            return CheckOutcome.COMPLETE
        }

        return try {
            val discovery = playUpdateSource.discover(mapOf(packageName to installed.versionCode))
            if (packageName in discovery.failedPackages) {
                val retryable = packageName in discovery.retryableFailedPackages
                save(
                    packageName = packageName,
                    installedVersionCode = installed.versionCode,
                    status = MiniStoreUpdateCheckStatus.CHECK_FAILED,
                    failureCode = if (retryable) "TRANSIENT_SOURCE" else "PLAY_DISCOVERY_FAILED",
                )
                if (retryable) CheckOutcome.RETRY else CheckOutcome.COMPLETE
            } else {
                val candidate = discovery.candidates[packageName]
                save(
                    packageName = packageName,
                    installedVersionCode = installed.versionCode,
                    status = if (candidate == null) {
                        MiniStoreUpdateCheckStatus.NO_UPDATE
                    } else {
                        MiniStoreUpdateCheckStatus.UPDATE_AVAILABLE
                    },
                    availableVersionCode = candidate?.versionCode,
                    availableVersionName = candidate?.versionName,
                    source = candidate?.source?.name,
                    failureCode = null,
                )
                CheckOutcome.COMPLETE
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PlayUpdateSource.PlayDiscoveryException) {
            save(
                packageName = packageName,
                installedVersionCode = installed.versionCode,
                status = MiniStoreUpdateCheckStatus.CHECK_FAILED,
                failureCode = if (error.retryable) "TRANSIENT_SOURCE" else "AUTH_OR_SOURCE_FAILED",
            )
            if (error.retryable) CheckOutcome.RETRY else CheckOutcome.COMPLETE
        } catch (_: Exception) {
            save(
                packageName = packageName,
                installedVersionCode = installed.versionCode,
                status = MiniStoreUpdateCheckStatus.CHECK_FAILED,
                failureCode = "AUTH_OR_SOURCE_FAILED",
            )
            CheckOutcome.COMPLETE
        }
    }

    private suspend fun save(
        packageName: String,
        installedVersionCode: Long,
        status: MiniStoreUpdateCheckStatus,
        availableVersionCode: Long? = null,
        availableVersionName: String? = null,
        source: String? = null,
        failureCode: String?,
    ) {
        val current = inventoryProvider.get(packageName)
        if (packageName in preferences.getBlacklist() || current?.versionCode != installedVersionCode) {
            updateCheckDao.delete(packageName)
            return
        }
        updateCheckDao.upsert(
            MiniStoreUpdateCheckEntity(
                packageName = packageName,
                installedVersionCode = installedVersionCode,
                availableVersionCode = availableVersionCode,
                availableVersionName = availableVersionName,
                source = source,
                status = status.name,
                checkedAtEpochMillis = System.currentTimeMillis(),
                failureCode = failureCode,
            ),
        )
    }

    enum class CheckOutcome {
        COMPLETE,
        RETRY,
    }
}
