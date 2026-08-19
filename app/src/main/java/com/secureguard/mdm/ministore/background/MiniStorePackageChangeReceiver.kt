package com.secureguard.mdm.ministore.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class MiniStorePackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        val packageName = intent.data?.schemeSpecificPart?.takeIf(String::isNotBlank) ?: return
        if (packageName == context.packageName) return
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        if (action == Intent.ACTION_PACKAGE_REMOVED && replacing) return

        EntryPointAccessors.fromApplication(
            context.applicationContext,
            MiniStorePackageChangeEntryPoint::class.java,
        ).scheduler().enqueuePackageChanged(packageName, action, replacing)
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REMOVED,
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MiniStorePackageChangeEntryPoint {
    fun scheduler(): MiniStoreUpdateCheckScheduler
}
