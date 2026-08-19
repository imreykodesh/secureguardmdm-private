package com.secureguard.mdm.utils.update

/**
 * Immutable metadata for a self-update published by the Mafteach update service.
 * versionCode is the only field used to decide whether an update is newer.
 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val apkSize: Long,
    val apkSha256: String,
)
