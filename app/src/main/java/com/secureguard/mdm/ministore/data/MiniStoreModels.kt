package com.secureguard.mdm.ministore.data

import android.graphics.drawable.Drawable

data class MiniStoreCatalogEnvelope(
    val schemaVersion: Int,
    val keyId: String,
    val algorithm: String,
    val payload: String,
    val signature: String,
)

data class MiniStoreCatalogPayload(
    val schemaVersion: Int,
    val revision: Long,
    val publishedAt: String,
    val apps: List<MiniStoreCatalogApp>,
)

data class MiniStoreCatalogApp(
    val packageName: String,
    val displayName: String,
    val versionCode: String,
    val versionName: String,
    val minSdk: Int?,
    val downloadUrl: String,
    val apkSha256: String,
    val apkSize: Long,
    val apkSignerSha256: String,
    val releaseNotes: String,
)

data class ManagedInstalledApp(
    val packageName: String,
    val displayName: String,
    val installedVersionName: String,
    val installedVersionCode: Long,
    val icon: Drawable,
    val update: UpdateCandidate?,
    val updateCheckComplete: Boolean,
    val isSystemApp: Boolean,
    val canUninstall: Boolean,
    val protectionReason: String?,
)

enum class CatalogSourceState {
    CHECKED,
    FAILED,
}

/**
 * State of the Google Play source for the last load.
 *
 * `SIGNED_OUT` used to be called `DISABLED`, and the store treated it as "there
 * is nothing to check here". That produced a completed-check status while the
 * Play apps had in fact not been checked at all. Being signed out is a missing
 * check, not a finished one.
 */
enum class PlaySourceState {
    SIGNED_OUT,
    CHECKED,
    FAILED,
}

data class MiniStoreLoadResult(
    val apps: List<ManagedInstalledApp>,
    val sourceWarning: String? = null,
    val catalogSourceState: CatalogSourceState = CatalogSourceState.FAILED,
    val playSourceState: PlaySourceState = PlaySourceState.SIGNED_OUT,
    /**
     * The app's own pending update, kept apart from [apps] so it can be pinned
     * above the list and excluded from bulk actions. Null when no update is
     * pending, when the check failed, or when the update policy forbids it.
     */
    val selfUpdate: ManagedInstalledApp? = null,
)
