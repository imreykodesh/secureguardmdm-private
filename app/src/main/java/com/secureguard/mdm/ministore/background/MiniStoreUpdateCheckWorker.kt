package com.secureguard.mdm.ministore.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.secureguard.mdm.ministore.domain.MiniStoreUpdateCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class MiniStoreUpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val packageName = inputData.getString(KEY_PACKAGE_NAME)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val coordinator = EntryPointAccessors.fromApplication(
            applicationContext,
            MiniStoreWorkerEntryPoint::class.java,
        ).coordinator()
        return when (coordinator.checkPackage(packageName)) {
            MiniStoreUpdateCoordinator.CheckOutcome.COMPLETE -> Result.success()
            MiniStoreUpdateCoordinator.CheckOutcome.RETRY -> {
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.success()
            }
        }
    }

    companion object {
        const val KEY_PACKAGE_NAME = "package_name"
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MiniStoreWorkerEntryPoint {
    fun coordinator(): MiniStoreUpdateCoordinator
}
