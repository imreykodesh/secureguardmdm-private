package com.secureguard.mdm.ministore.background

import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MiniStoreUpdateCheckScheduler @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueuePackageChanged(packageName: String, action: String, replacing: Boolean) {
        if (packageName.isBlank()) return
        val removed = action == Intent.ACTION_PACKAGE_REMOVED && !replacing
        val constraints = Constraints.Builder().apply {
            if (!removed) setRequiredNetworkType(NetworkType.CONNECTED)
        }.build()
        val request = OneTimeWorkRequestBuilder<MiniStoreUpdateCheckWorker>()
            .setInputData(
                Data.Builder()
                    .putString(MiniStoreUpdateCheckWorker.KEY_PACKAGE_NAME, packageName)
                    .build(),
            )
            .setConstraints(constraints)
            .setInitialDelay(if (removed) 0 else PACKAGE_CHANGE_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(
            "$UNIQUE_WORK_PREFIX${packageHash(packageName)}",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun packageHash(packageName: String): String = MessageDigest.getInstance("SHA-256")
        .digest(packageName.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val UNIQUE_WORK_PREFIX = "mini_store_check_"
        private const val WORK_TAG = "mini_store_update_check"
        private const val PACKAGE_CHANGE_DEBOUNCE_SECONDS = 15L
        private const val RETRY_BACKOFF_SECONDS = 30L
    }
}
