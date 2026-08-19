package com.secureguard.mdm.ministore.data

import com.secureguard.mdm.utils.update.UpdateInfo

enum class UpdateSource {
    SIGNED_CATALOG,
    GOOGLE_PLAY,

    /**
     * The app's own update channel. It never travels through the mini-store
     * install pipeline: that pipeline refuses to install the host app on
     * purpose, and [com.secureguard.mdm.utils.update.UpdateManager] already owns
     * manifest validation, redirect pinning and signing continuity for this one
     * package.
     */
    SELF_UPDATE,
}

enum class UpdateArtifactRole {
    BASE,
    SPLIT,
}

data class UpdateCandidate(
    val packageName: String,
    val displayName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int?,
    val releaseNotes: String,
    val source: UpdateSource,
    internal val locator: UpdateLocator,
)

sealed interface UpdateLocator {
    data class SignedCatalog(
        val downloadUrl: String,
        val apkSha256: String,
        val apkSize: Long,
        val apkSignerSha256: String,
    ) : UpdateLocator

    data class GooglePlay(
        val offerType: Int,
    ) : UpdateLocator

    /**
     * Carries the already validated manifest of the app's own update, so the
     * store does not re-derive or re-validate it.
     */
    data class SelfUpdate(
        val info: UpdateInfo,
    ) : UpdateLocator
}

data class UpdatePlan(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val source: UpdateSource,
    val artifacts: List<UpdateArtifact>,
    val expectedSignerSha256: String? = null,
)

data class UpdateArtifact(
    val role: UpdateArtifactRole,
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

enum class UpdateOperationStage {
    RESOLVING,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
}
