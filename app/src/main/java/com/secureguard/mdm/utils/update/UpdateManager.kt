package com.secureguard.mdm.utils.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.secureguard.mdm.R
import com.secureguard.mdm.data.local.PreferencesManager
import com.secureguard.mdm.receivers.InstallReceiver
import com.secureguard.mdm.utils.InstallRestrictionGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

private const val TAG = "UpdateManager"
private const val UPDATE_FILE_NAME = "mafteach_self_update.apk"
private const val INSTALL_SESSION_NAME = "base.apk"

sealed class UpdateResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateResult()
    object NoUpdate : UpdateResult()
    data class Failure(val message: String) : UpdateResult()
}

sealed class DownloadProgress {
    data class Downloading(val progress: Int) : DownloadProgress()
    object Installing : DownloadProgress()
    object Completed : DownloadProgress()
    data class Error(val message: String) : DownloadProgress()
}

private data class UpdateManifest(
    val schemaVersion: Int = 0,
    val channel: String = "",
    val packageName: String = "",
    val versionCode: Long = 0,
    val versionName: String = "",
    val releaseNotes: String = "",
    val publishedAt: String = "",
    val downloadUrl: String = "",
    val apkSha256: String = "",
    val apkSize: Long = 0,
)

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val devicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val gson = Gson()

    private fun selectedChannel(): String =
        if (preferencesManager.loadString(PreferencesManager.KEY_UPDATE_CHANNEL, null) == "PREBUILD") {
            "prebuild"
        } else {
            "stable"
        }

    @SuppressLint("MissingPermission")
    private fun hasActiveNetwork(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        connectivityManager.activeNetwork != null
    } else {
        @Suppress("DEPRECATION")
        connectivityManager.activeNetworkInfo?.isConnected == true
    }

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        if (!hasActiveNetwork()) {
            Log.d(TAG, "Self-update check failed: no active network")
            return@withContext UpdateResult.Failure(context.getString(R.string.netfree_no_active_network))
        }

        try {
            val channel = selectedChannel()
            val manifest = fetchManifest(channel) ?: return@withContext UpdateResult.NoUpdate
            val updateInfo = validateManifest(manifest, channel)
            val installedVersion = versionCode(installedPackageInfo(context.packageName))
            if (updateInfo.versionCode <= installedVersion) {
                Log.d(TAG, "Self-update is current: installed=$installedVersion remote=${updateInfo.versionCode}")
                UpdateResult.NoUpdate
            } else {
                Log.i(TAG, "Self-update available: $installedVersion -> ${updateInfo.versionCode} ($channel)")
                UpdateResult.UpdateAvailable(updateInfo)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Self-update check failed: ${error.javaClass.simpleName}: ${error.message}")
            UpdateResult.Failure(error.message ?: "בדיקת העדכון נכשלה")
        }
    }

    private fun fetchManifest(channel: String): UpdateManifest? {
        val url = URL("$UPDATE_API_URL?channel=$channel")
        require(url.protocol == "https" && url.host.equals(UPDATE_API_HOST, ignoreCase = true)) {
            "כתובת שירות העדכונים אינה מורשית"
        }
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            return when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NO_CONTENT -> null
                HttpURLConnection.HTTP_OK -> {
                    val declaredLength = connection.contentLengthLong
                    require(declaredLength == -1L || declaredLength <= MAX_MANIFEST_BYTES) {
                        "תגובת שירות העדכונים גדולה מדי"
                    }
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            require(output.size() + read <= MAX_MANIFEST_BYTES) {
                                "תגובת שירות העדכונים גדולה מדי"
                            }
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                    gson.fromJson(bytes.toString(Charsets.UTF_8), UpdateManifest::class.java)
                        ?: error("שירות העדכונים החזיר תשובה ריקה")
                }
                else -> error("שירות העדכונים החזיר HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun validateManifest(manifest: UpdateManifest, expectedChannel: String): UpdateInfo {
        require(manifest.schemaVersion == UPDATE_SCHEMA_VERSION) { "גרסת חוזה העדכון אינה נתמכת" }
        require(manifest.channel == expectedChannel) { "ערוץ העדכון שהוחזר אינו תואם לבקשה" }
        require(manifest.packageName == context.packageName) { "העדכון מיועד לאפליקציה אחרת" }
        require(manifest.versionCode > 0) { "versionCode בעדכון אינו תקין" }
        require(VERSION_NAME_REGEX.matches(manifest.versionName)) { "versionName בעדכון אינו תקין" }
        require(manifest.releaseNotes.length <= MAX_RELEASE_NOTES_LENGTH) { "הערות העדכון ארוכות מדי" }
        require(manifest.apkSize in 1..MAX_APK_BYTES) { "גודל ה-APK בעדכון אינו תקין" }
        require(SHA256_REGEX.matches(manifest.apkSha256)) { "SHA-256 בעדכון אינו תקין" }

        val download = URL(manifest.downloadUrl)
        require(download.protocol == "https" && download.host.equals(DOWNLOAD_HOST, ignoreCase = true)) {
            "שרת הורדת העדכון אינו מורשה"
        }
        require(download.port == -1 || download.port == 443) { "פורט הורדת העדכון אינו מורשה" }
        require(download.userInfo == null && download.query == null && download.ref == null) {
            "כתובת הורדת העדכון אינה תקינה"
        }
        val expectedPath = Regex(
            "^/downloads/mafteach/${Regex.escape(expectedChannel)}/${manifest.versionCode}/" +
                "$UPLOAD_ID_PATH_PATTERN/${manifest.apkSha256}\\.apk$"
        )
        require(expectedPath.matches(download.path)) {
            "נתיב הורדת העדכון אינו תואם למטא-דאטה"
        }

        return UpdateInfo(
            versionCode = manifest.versionCode,
            versionName = manifest.versionName,
            changelog = manifest.releaseNotes,
            downloadUrl = manifest.downloadUrl,
            apkSize = manifest.apkSize,
            apkSha256 = manifest.apkSha256,
        )
    }

    fun downloadAndInstallUpdate(updateInfo: UpdateInfo): Flow<DownloadProgress> = flow {
        val outputFile = File(context.cacheDir, UPDATE_FILE_NAME)
        try {
            outputFile.delete()
            downloadVerified(updateInfo, outputFile) { downloaded ->
                emit(DownloadProgress.Downloading(((downloaded * 100L) / updateInfo.apkSize).toInt()))
            }
            emit(DownloadProgress.Downloading(100))
            verifyDownloadedApk(outputFile, updateInfo)
            emit(DownloadProgress.Installing)
            installApkSilently(outputFile, updateInfo)
            // The explicit InstallReceiver reports Android's final result. A self-update
            // can replace this process immediately after commit, so this state means
            // the verified session was handed to PackageInstaller successfully.
            emit(DownloadProgress.Completed)
        } catch (error: Exception) {
            Log.w(TAG, "Self-update failed: ${error.javaClass.simpleName}: ${error.message}", error)
            emit(DownloadProgress.Error(error.message ?: "הורדת העדכון או התקנתו נכשלה"))
        } finally {
            outputFile.delete()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadVerified(
        updateInfo: UpdateInfo,
        destination: File,
        onProgress: suspend (Long) -> Unit,
    ) {
        val connection = openDownloadConnection(updateInfo.downloadUrl)
        try {
            val declaredLength = connection.contentLengthLong
            require(declaredLength == -1L || declaredLength == updateInfo.apkSize) {
                "גודל הקובץ בשרת אינו תואם לעדכון"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= updateInfo.apkSize) { "קובץ העדכון גדול מהגודל שפורסם" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(total)
                    }
                    output.fd.sync()
                }
            }
            require(total == updateInfo.apkSize) { "הורדת קובץ העדכון לא הושלמה" }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == updateInfo.apkSha256) { "SHA-256 של קובץ העדכון אינו תואם" }
        } finally {
            connection.disconnect()
        }
    }

    private fun requireApprovedDownloadTarget(target: URL, approved: URL) {
        require(target.protocol.equals(approved.protocol, ignoreCase = true) &&
            target.host.equals(approved.host, ignoreCase = true)) {
            "שרת הורדת העדכון אינו מורשה"
        }
        val targetPort = if (target.port == -1) target.defaultPort else target.port
        val approvedPort = if (approved.port == -1) approved.defaultPort else approved.port
        require(targetPort == approvedPort && targetPort == 443) { "פורט הורדת העדכון אינו מורשה" }
        require(target.path == approved.path &&
            target.query == approved.query &&
            target.ref == approved.ref &&
            target.userInfo == approved.userInfo) {
            "הפניית הורדת העדכון שינתה את כתובת הקובץ המאומתת"
        }
    }

    private fun openDownloadConnection(downloadUrl: String): HttpsURLConnection {
        val approved = URL(downloadUrl)
        require(approved.protocol == "https" && approved.host.equals(DOWNLOAD_HOST, ignoreCase = true)) {
            "שרת הורדת העדכון אינו מורשה"
        }
        require(approved.userInfo == null && approved.query == null && approved.ref == null) {
            "כתובת הורדת העדכון אינה תקינה"
        }

        var target = approved
        repeat(MAX_DOWNLOAD_REDIRECTS + 1) { redirectCount ->
            requireApprovedDownloadTarget(target, approved)
            val connection = (target.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 90_000
            }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_OK) return connection
            if (status !in REDIRECT_STATUS_CODES) {
                connection.disconnect()
                error("שרת הורדת העדכון החזיר HTTP $status")
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(redirectCount < MAX_DOWNLOAD_REDIRECTS && !location.isNullOrBlank()) {
                "שרת העדכון החזיר יותר מדי הפניות"
            }
            target = URL(target, location)
        }
        error("לא ניתן לפתוח את הורדת העדכון")
    }

    private fun verifyDownloadedApk(apkFile: File, updateInfo: UpdateInfo) {
        val archive = archivePackageInfo(apkFile) ?: error("קובץ העדכון אינו APK קריא")
        require(archive.packageName == context.packageName) { "קובץ העדכון שייך לאפליקציה אחרת" }
        require(versionCode(archive) == updateInfo.versionCode) { "versionCode בתוך ה-APK אינו תואם" }
        val minSdk = archive.applicationInfo?.minSdkVersion
        require(minSdk == null || minSdk <= Build.VERSION.SDK_INT) { "העדכון דורש גרסת Android חדשה יותר" }

        val installed = installedPackageInfo(context.packageName)
        require(updateInfo.versionCode > versionCode(installed)) { "האפליקציה כבר מעודכנת" }
        verifySigningContinuity(installed, archive)
    }

    private suspend fun installApkSilently(apkFile: File, updateInfo: UpdateInfo) {
        require(devicePolicyManager.isDeviceOwnerApp(context.packageName)) {
            "מפתח חייבת להישאר Device Owner כדי להתקין עדכון בשקט"
        }
        val installedImmediatelyBeforeSession = installedPackageInfo(context.packageName)
        require(updateInfo.versionCode > versionCode(installedImmediatelyBeforeSession)) {
            "האפליקציה כבר מעודכנת"
        }

        InstallRestrictionGuard.withInstallAllowed(context) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            var committed = false
            try {
                installer.openSession(sessionId).use { session ->
                    session.openWrite(INSTALL_SESSION_NAME, 0, apkFile.length()).use { sessionOutput ->
                        apkFile.inputStream().use { input -> input.copyTo(sessionOutput) }
                        session.fsync(sessionOutput)
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.toast_installing_update), Toast.LENGTH_LONG).show()
                    }
                    val resultIntent = Intent(context, InstallReceiver::class.java).apply {
                        putExtra(EXTRA_OPERATION, OPERATION_SELF_UPDATE)
                        putExtra(EXTRA_EXPECTED_PACKAGE, context.packageName)
                        putExtra(EXTRA_EXPECTED_VERSION, updateInfo.versionCode)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pendingIntent.intentSender)
                    committed = true
                }
            } catch (error: Exception) {
                if (!committed) runCatching { installer.abandonSession(sessionId) }
                throw error
            }
        }
    }

    private fun verifySigningContinuity(installed: PackageInfo, archive: PackageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val installedInfo = installed.signingInfo ?: error("לא נמצאה חתימה לאפליקציה המותקנת")
            val archiveInfo = archive.signingInfo ?: error("לא נמצאה חתימה בקובץ העדכון")
            val installedCurrent = installedInfo.apkContentsSigners.orEmpty().map(::digest).toSet()
            val archiveCurrent = archiveInfo.apkContentsSigners.orEmpty().map(::digest).toSet()
            val archiveHistory = archiveInfo.signingCertificateHistory.orEmpty().map(::digest).toSet()
            if (installedInfo.hasMultipleSigners() || archiveInfo.hasMultipleSigners()) {
                require(installedCurrent.isNotEmpty() && installedCurrent == archiveCurrent) {
                    "חתימת קובץ העדכון אינה תואמת לאפליקציה המותקנת"
                }
            } else {
                require(installedCurrent.isNotEmpty() && installedCurrent.all { it in archiveHistory }) {
                    "שרשרת החתימה של קובץ העדכון אינה ממשיכה את האפליקציה המותקנת"
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val installedCurrent = installed.signatures.orEmpty().map(::digest).toSet()
            @Suppress("DEPRECATION")
            val archiveCurrent = archive.signatures.orEmpty().map(::digest).toSet()
            require(installedCurrent.isNotEmpty() && installedCurrent == archiveCurrent) {
                "חתימת קובץ העדכון אינה תואמת לאפליקציה המותקנת"
            }
        }
    }

    private fun digest(signature: Signature): String = MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .lowercase(Locale.ROOT)

    private fun archivePackageInfo(file: File): PackageInfo? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        else -> {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        }
    }

    private fun installedPackageInfo(packageName: String): PackageInfo = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        else -> {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    }

    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    companion object {
        const val EXTRA_OPERATION = "com.secureguard.mdm.extra.INSTALL_OPERATION"
        const val EXTRA_EXPECTED_PACKAGE = "com.secureguard.mdm.extra.EXPECTED_PACKAGE"
        const val EXTRA_EXPECTED_VERSION = "com.secureguard.mdm.extra.EXPECTED_VERSION"
        const val OPERATION_SELF_UPDATE = "self_update"

        private const val UPDATE_API_URL =
            "https://imreykodesh.com/.netlify/functions/get-mafteach-update"
        private const val UPDATE_API_HOST = "imreykodesh.com"
        private const val DOWNLOAD_HOST = "downloads.imreykodesh.com"
        private const val UPDATE_SCHEMA_VERSION = 1
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_RELEASE_NOTES_LENGTH = 8000
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
        private const val MAX_DOWNLOAD_REDIRECTS = 3
        private const val UPLOAD_ID_PATH_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")
        private val VERSION_NAME_REGEX = Regex("^[0-9A-Za-z][0-9A-Za-z._+\\-]{0,63}$")
    }
}
