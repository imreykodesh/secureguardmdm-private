package com.secureguard.mdm.ministore.inventory

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledPackageRecord(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signerSha256: Set<String>,
    val isSystemApp: Boolean,
)

interface InstalledPackageInventoryProvider {
    fun get(packageName: String): InstalledPackageRecord?
    fun getAll(): List<InstalledPackageRecord>
}

@Singleton
class AndroidInstalledPackageInventoryProvider @Inject constructor(
    @ApplicationContext context: Context,
) : InstalledPackageInventoryProvider {
    private val packageManager = context.packageManager

    override fun get(packageName: String): InstalledPackageRecord? = runCatching {
        val packageInfo = packageInfo(packageName)
        val applicationInfo = packageInfo.applicationInfo ?: return@runCatching null
        if (!isApkBacked(applicationInfo)) return@runCatching null
        packageInfo.toRecord(applicationInfo)
    }.getOrNull()

    override fun getAll(): List<InstalledPackageRecord> = installedApplications()
        .mapNotNull { applicationInfo ->
            runCatching {
                packageInfo(applicationInfo.packageName).toRecord(applicationInfo)
            }.getOrNull()
        }

    private fun installedApplications(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        }.filter(::isApkBacked)

    private fun packageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                packageName,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                },
            )
        }

    private fun PackageInfo.toRecord(applicationInfo: ApplicationInfo): InstalledPackageRecord =
        InstalledPackageRecord(
            packageName = packageName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode
            } else {
                @Suppress("DEPRECATION")
                versionCode.toLong()
            },
            versionName = versionName,
            signerSha256 = signerDigests(this),
            isSystemApp = applicationInfo.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
        )

    private fun signerDigests(packageInfo: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptySet()
            val signatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners.orEmpty()
            } else {
                signingInfo.signingCertificateHistory.orEmpty().ifEmpty {
                    signingInfo.apkContentsSigners.orEmpty()
                }
            }
            signatures.map(::digest).toSet()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty().map(::digest).toSet()
        }

    private fun digest(signature: Signature): String = MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun isApkBacked(info: ApplicationInfo): Boolean =
        sequenceOf(info.sourceDir, info.publicSourceDir)
            .filterNotNull()
            .any { it.endsWith(".apk", ignoreCase = true) }
}
