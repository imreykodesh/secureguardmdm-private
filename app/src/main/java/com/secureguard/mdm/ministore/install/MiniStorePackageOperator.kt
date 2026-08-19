package com.secureguard.mdm.ministore.install

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import com.secureguard.mdm.features.impl.BlockUninstallAppsFeature
import com.secureguard.mdm.ministore.data.UpdateArtifact
import com.secureguard.mdm.ministore.data.UpdateArtifactRole
import com.secureguard.mdm.ministore.data.UpdateCandidate
import com.secureguard.mdm.ministore.data.UpdateLocator
import com.secureguard.mdm.ministore.data.UpdateOperationStage
import com.secureguard.mdm.ministore.data.UpdatePlan
import com.secureguard.mdm.ministore.data.UpdateSource
import com.secureguard.mdm.ministore.data.MiniStoreRepository
import com.secureguard.mdm.ministore.play.PlayUpdateSource
import com.secureguard.mdm.utils.InstallRestrictionGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Singleton
class MiniStorePackageOperator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val devicePolicyManager: DevicePolicyManager,
    private val repository: MiniStoreRepository,
    private val playUpdateSource: PlayUpdateSource,
) {
    private val operationMutex = Mutex()

    suspend fun update(
        candidate: UpdateCandidate,
        onProgress: (UpdateOperationStage, Long, Long) -> Unit = { _, _, _ -> },
    ) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            // Diagnostics for the whole operation; never includes URLs or tokens.
            try {
            requireDeviceOwner()
            repository.assertUpdateAllowed(candidate.packageName)
            val installedBeforeDownload = installedPackageInfo(candidate.packageName)
            require(candidate.versionCode > versionCode(installedBeforeDownload)) {
                "The update is not newer than the installed app"
            }

            Log.i(
                TAG,
                "update requested: ${candidate.packageName} " +
                    "${versionCode(installedBeforeDownload)} -> ${candidate.versionCode} " +
                    "via ${candidate.source}",
            )

            withTemporaryUninstallRestriction {
                onProgress(UpdateOperationStage.RESOLVING, 0, 0)
                val plan = resolvePlan(candidate)
                Log.i(
                    TAG,
                    "resolved ${plan.artifacts.size} artifact(s) for ${plan.packageName}: " +
                        plan.artifacts.joinToString { "${it.role}:${it.size}B" },
                )
                require(plan.packageName == candidate.packageName && plan.versionCode == candidate.versionCode) {
                    "Resolved update does not match the selected app"
                }
                validatePlan(plan)

                val updateDirectory = File(
                    context.cacheDir.resolve("mini_store_updates"),
                    UUID.randomUUID().toString(),
                ).apply { require(mkdirs()) { "Could not create the update staging directory" } }
                try {
                    val totalBytes = plan.artifacts.fold(0L) { total, artifact ->
                        Math.addExact(total, artifact.size)
                    }
                    var completedBytes = 0L
                    // Artifact URLs are short lived. If one expires or the content
                    // host answers with a transient error, the plan is resolved
                    // again to obtain fresh URLs for the same version.
                    var activePlan = plan
                    val staged = plan.artifacts.indices.map { index ->
                        val destination = updateDirectory.resolve("artifact_$index.apk")
                        val artifact = downloadArtifactWithRetry(
                            index = index,
                            artifactCount = plan.artifacts.size,
                            candidate = candidate,
                            planProvider = { activePlan },
                            onPlanRefreshed = { activePlan = it },
                            destination = destination,
                        ) { artifactBytes ->
                            onProgress(
                                UpdateOperationStage.DOWNLOADING,
                                completedBytes + artifactBytes,
                                totalBytes,
                            )
                        }
                        completedBytes += artifact.size
                        StagedArtifact(artifact, destination)
                    }

                    onProgress(UpdateOperationStage.VERIFYING, totalBytes, totalBytes)

                    // Every artifact was already matched against the size and
                    // SHA-256 published by the delivery metadata; that is what
                    // guarantees the bytes. Manifest checks apply to the base
                    // APK, which carries the package identity. A split is not a
                    // standalone package and need not parse on its own, so when
                    // it does parse its package and signer must agree with the
                    // base, and when it does not that is not treated as failure.
                    val baseStaged = staged.single { it.artifact.role == UpdateArtifactRole.BASE }
                    val baseArchive = archivePackageInfo(baseStaged.file)
                        ?: error("Downloaded base APK is not readable")
                    require(baseArchive.packageName == plan.packageName) {
                        "Downloaded APK package does not match the requested app"
                    }
                    require(versionCode(baseArchive) == plan.versionCode) {
                        "Downloaded APK version does not match the selected update"
                    }
                    val minSdk = baseArchive.applicationInfo?.minSdkVersion
                    require(minSdk == null || minSdk <= Build.VERSION.SDK_INT) {
                        "The update requires a newer Android version"
                    }

                    repository.assertUpdateAllowed(plan.packageName)
                    requireDeviceOwner()
                    val installedImmediatelyBeforeSession = installedPackageInfo(plan.packageName)
                    require(versionCode(installedImmediatelyBeforeSession) < plan.versionCode) {
                        "The app is already up to date"
                    }

                    verifySigningContinuity(
                        installedImmediatelyBeforeSession,
                        baseArchive,
                        plan.expectedSignerSha256,
                    )
                    val baseSigners = currentSignerDigests(baseArchive)
                    require(baseSigners.isNotEmpty()) { "Base APK has no signing certificate" }

                    var inspectedSplits = 0
                    staged.filter { it.artifact.role == UpdateArtifactRole.SPLIT }
                        .forEach { splitArtifact ->
                            val splitArchive = archivePackageInfo(splitArtifact.file) ?: return@forEach
                            inspectedSplits++
                            require(splitArchive.packageName == plan.packageName) {
                                "Update split belongs to a different app"
                            }
                            val splitSigners = currentSignerDigests(splitArchive)
                            require(splitSigners.isEmpty() || splitSigners == baseSigners) {
                                "Update split APK signer sets are inconsistent"
                            }
                        }
                    Log.i(
                        TAG,
                        "verified base plus ${staged.size - 1} split(s); " +
                            "$inspectedSplits split(s) exposed a manifest",
                    )

                    val admin = SecureGuardDeviceAdminReceiver.getComponentName(context)
                    val wasHidden = runCatching {
                        devicePolicyManager.isApplicationHidden(admin, plan.packageName)
                    }.getOrDefault(false)
                    val wasSuspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        runCatching {
                            context.packageManager.isPackageSuspended(plan.packageName)
                        }.getOrDefault(false)
                    } else {
                        false
                    }

                    onProgress(UpdateOperationStage.INSTALLING, totalBytes, totalBytes)
                    Log.i(TAG, "committing install session for ${plan.packageName}")
                    // A blocked-installation policy also blocks A Bloq's own
                    // session, so it is lifted for the session only.
                    InstallRestrictionGuard.withInstallAllowed(context) {
                        installVerifiedFiles(staged, plan.packageName, totalBytes)
                    }
                    verifyInstalledPackage(plan.packageName, plan.versionCode)
                    Log.i(TAG, "update installed: ${plan.packageName} ${plan.versionCode}")
                    if (wasHidden) {
                        runCatching {
                            devicePolicyManager.setApplicationHidden(admin, plan.packageName, true)
                        }
                    }
                    if (wasSuspended && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        runCatching {
                            devicePolicyManager.setPackagesSuspended(
                                admin,
                                arrayOf(plan.packageName),
                                true,
                            )
                        }
                    }
                } finally {
                    updateDirectory.deleteRecursively()
                }
            }
            } catch (error: Exception) {
                Log.w(
                    TAG,
                    "update failed for ${candidate.packageName}: " +
                        "${error.javaClass.simpleName}: ${error.message}",
                    error,
                )
                throw error
            }
        }
    }

    suspend fun uninstall(packageName: String) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            requireDeviceOwner()
            repository.assertUninstallAllowed(packageName)
            installedPackageInfo(packageName)
            awaitInstallerResult("uninstall") { statusReceiver ->
                context.packageManager.packageInstaller.uninstall(packageName, statusReceiver)
            }
            require(runCatching { installedPackageInfo(packageName) }.isFailure) {
                "Package uninstall reported success but the app is still installed"
            }
        }
    }

    private suspend fun resolvePlan(candidate: UpdateCandidate): UpdatePlan = when (val locator = candidate.locator) {
        is UpdateLocator.GooglePlay -> playUpdateSource.resolve(candidate)
        is UpdateLocator.SignedCatalog -> UpdatePlan(
            packageName = candidate.packageName,
            versionCode = candidate.versionCode,
            versionName = candidate.versionName,
            source = UpdateSource.SIGNED_CATALOG,
            artifacts = listOf(
                UpdateArtifact(
                    role = UpdateArtifactRole.BASE,
                    name = "base.apk",
                    url = locator.downloadUrl,
                    size = locator.apkSize,
                    sha256 = locator.apkSha256,
                ),
            ),
            expectedSignerSha256 = locator.apkSignerSha256,
        )
        // Unreachable by design: the app's own update is driven by UpdateManager,
        // so this pipeline keeps refusing to install the host package.
        is UpdateLocator.SelfUpdate -> error("The app's own update is not installed through the mini-store")
    }

    private fun validatePlan(plan: UpdatePlan) {
        require(plan.packageName != context.packageName) { "A Bloq cannot update itself here" }
        require(plan.versionCode > 0) { "Invalid update version" }
        require(plan.artifacts.isNotEmpty() && plan.artifacts.size <= MAX_INSTALL_ARTIFACTS) {
            "Invalid update artifact count"
        }
        require(plan.artifacts.count { it.role == UpdateArtifactRole.BASE } == 1) {
            "Update must contain exactly one base APK"
        }
        require(plan.artifacts.all { it.size in 1..MAX_ARTIFACT_BYTES }) {
            "Invalid update artifact size"
        }
        require(plan.artifacts.all { SHA256_REGEX.matches(it.sha256) }) {
            "Invalid update artifact SHA-256"
        }
        require(plan.artifacts.map { sessionName(it, plan.artifacts.indexOf(it)) }.distinct().size == plan.artifacts.size) {
            "Update artifact names are not unique"
        }
    }

    /**
     * Downloads one artifact, retrying only transient failures.
     *
     * A hash, size, package or signer mismatch is never retried: those indicate
     * the payload is wrong, not that the network hiccuped. Transient content-host
     * errors are retried with a growing delay, and the delivery plan is resolved
     * again first so an expired URL is replaced rather than retried as-is.
     */
    private suspend fun downloadArtifactWithRetry(
        index: Int,
        artifactCount: Int,
        candidate: UpdateCandidate,
        planProvider: () -> UpdatePlan,
        onPlanRefreshed: (UpdatePlan) -> Unit,
        destination: File,
        onBytes: (Long) -> Unit,
    ): UpdateArtifact {
        var lastError: Exception? = null
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            val plan = planProvider()
            val artifact = plan.artifacts[index]
            try {
                Log.i(
                    TAG,
                    "downloading artifact ${index + 1}/$artifactCount " +
                        "(${artifact.role}, ${artifact.size}B), attempt $attempt",
                )
                downloadVerified(plan.source, artifact, destination, onBytes)
                return artifact
            } catch (error: TransientDownloadException) {
                lastError = error
                destination.delete()
                if (attempt == MAX_DOWNLOAD_ATTEMPTS) break
                val backoffMillis = DOWNLOAD_RETRY_BASE_MILLIS * attempt
                Log.w(
                    TAG,
                    "artifact ${index + 1}/$artifactCount failed transiently " +
                        "(${error.message}); retrying in ${backoffMillis}ms",
                )
                delay(backoffMillis)
                // Refresh the delivery URLs, keeping the same target version.
                runCatching { resolvePlan(candidate) }
                    .onSuccess { refreshed ->
                        if (refreshed.packageName == plan.packageName &&
                            refreshed.versionCode == plan.versionCode &&
                            refreshed.artifacts.size == plan.artifacts.size
                        ) {
                            onPlanRefreshed(refreshed)
                            Log.i(TAG, "delivery URLs refreshed for ${plan.packageName}")
                        }
                    }
                    .onFailure { Log.w(TAG, "could not refresh delivery URLs: ${it.message}") }
            }
        }
        throw lastError ?: IllegalStateException("Artifact download failed")
    }

    private suspend fun downloadVerified(
        source: UpdateSource,
        artifact: UpdateArtifact,
        destination: File,
        onBytes: (Long) -> Unit,
    ) {
        val connection = openVerifiedConnection(source, artifact.url)
        try {
            val declaredLength = connection.contentLengthLong
            require(declaredLength == -1L || declaredLength == artifact.size) {
                "Update server size does not match the signed metadata"
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            try {
                connection.inputStream.use { input ->
                    FileOutputStream(destination, false).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            // Blocking reads do not observe cancellation, so it is
                            // checked between chunks to stop a cancelled download.
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= artifact.size) { "Downloaded APK exceeds its declared size" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            onBytes(total)
                        }
                        output.fd.sync()
                    }
                }
            } catch (error: IOException) {
                // A dropped connection is worth another attempt.
                throw TransientDownloadException("Update download was interrupted", error)
            }
            if (total != artifact.size) {
                throw TransientDownloadException("Downloaded APK is incomplete")
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == artifact.sha256) {
                "Downloaded APK SHA-256 does not match its metadata"
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Opens the artifact stream, following redirects manually.
     *
     * Google serves APKs through a redirect to its content hosts, so redirects
     * cannot be refused outright. They are followed only while every hop stays
     * on HTTPS and on a host trusted for this source, and the number of hops is
     * capped. Integrity still rests on the size and SHA-256 checks applied to
     * the received bytes, so a redirect can never substitute the payload.
     */
    private fun openVerifiedConnection(
        source: UpdateSource,
        artifactUrl: String,
    ): HttpsURLConnection {
        var target = URL(artifactUrl)
        var redirects = 0
        while (true) {
            require(target.protocol == "https" && isTrustedHost(source, target.host)) {
                "Untrusted update download host"
            }
            val connection = (target.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 90_000
            }
            val status = try {
                connection.responseCode
            } catch (error: IOException) {
                connection.disconnect()
                throw TransientDownloadException("Could not reach the update server", error)
            }
            if (status == HttpsURLConnection.HTTP_OK) return connection

            val isRedirect = status in REDIRECT_STATUS_CODES
            if (!isRedirect) {
                connection.disconnect()
                if (status in TRANSIENT_STATUS_CODES) {
                    throw TransientDownloadException("Update server returned HTTP $status")
                }
                throw IllegalArgumentException("Update server returned HTTP $status")
            }

            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(!location.isNullOrBlank()) { "Update server sent a redirect without a target" }
            require(redirects < MAX_DOWNLOAD_REDIRECTS) { "Too many update download redirects" }
            redirects++
            // Resolved against the current URL so relative targets are handled.
            target = runCatching { URL(target, location) }
                .getOrElse { throw IllegalArgumentException("Update server sent an invalid redirect") }
            Log.i(TAG, "following redirect $redirects to host ${target.host}")
        }
    }

    private fun isTrustedHost(source: UpdateSource, host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT)
        return when (source) {
            UpdateSource.SIGNED_CATALOG -> normalized == "downloads.imreykodesh.com"
            UpdateSource.GOOGLE_PLAY -> normalized == "play.googleapis.com" ||
                normalized.endsWith(".googleapis.com") ||
                normalized.endsWith(".googleusercontent.com") ||
                normalized.endsWith(".gvt1.com")
            // This pipeline never downloads the app's own update.
            UpdateSource.SELF_UPDATE -> false
        }
    }

    private suspend fun installVerifiedFiles(
        staged: List<StagedArtifact>,
        expectedPackage: String,
        totalBytes: Long,
    ) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(expectedPackage)
            setSize(totalBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                staged.forEachIndexed { index, stagedArtifact ->
                    val name = sessionName(stagedArtifact.artifact, index)
                    session.openWrite(name, 0, stagedArtifact.file.length()).use { output ->
                        stagedArtifact.file.inputStream().use { input -> input.copyTo(output) }
                        session.fsync(output)
                    }
                }
                awaitInstallerResult("update") { statusReceiver ->
                    session.commit(statusReceiver)
                    committed = true
                }
            }
        } catch (error: Exception) {
            if (!committed) runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private fun sessionName(artifact: UpdateArtifact, index: Int): String {
        if (artifact.role == UpdateArtifactRole.BASE) return "base.apk"
        val sanitized = artifact.name
            .removeSuffix(".apk")
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .take(80)
            .ifBlank { "split_$index" }
        return "split_${index}_$sanitized.apk"
    }

    private suspend fun awaitInstallerResult(
        operation: String,
        start: (android.content.IntentSender) -> Unit,
    ) {
        try {
            withTimeout(INSTALLER_RESULT_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val completed = AtomicBoolean(false)
                    val action = "${context.packageName}.MINI_STORE_${operation.uppercase()}_${UUID.randomUUID()}"
                    lateinit var receiver: BroadcastReceiver
                    fun unregister() = runCatching { context.unregisterReceiver(receiver) }

                    receiver = object : BroadcastReceiver() {
                        override fun onReceive(receiverContext: Context, intent: Intent) {
                            if (!completed.compareAndSet(false, true)) return
                            unregister()
                            val status = intent.getIntExtra(
                                PackageInstaller.EXTRA_STATUS,
                                PackageInstaller.STATUS_FAILURE,
                            )
                            when (status) {
                                PackageInstaller.STATUS_SUCCESS -> continuation.resume(Unit)
                                PackageInstaller.STATUS_PENDING_USER_ACTION -> continuation.resumeWithException(
                                    IllegalStateException(
                                        "Package $operation unexpectedly requires user action; silent operation was refused",
                                    ),
                                )
                                else -> {
                                    val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                                        ?: "status $status"
                                    continuation.resumeWithException(
                                        IllegalStateException("Package $operation failed: $detail"),
                                    )
                                }
                            }
                        }
                    }
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        IntentFilter(action),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    continuation.invokeOnCancellation {
                        if (completed.compareAndSet(false, true)) unregister()
                    }
                    try {
                        val pendingIntent = PendingIntent.getBroadcast(
                            context,
                            action.hashCode(),
                            Intent(action).setPackage(context.packageName),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                        start(pendingIntent.intentSender)
                    } catch (error: Exception) {
                        if (completed.compareAndSet(false, true)) {
                            unregister()
                            continuation.resumeWithException(error)
                        }
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException("Timed out waiting for package $operation result", error)
        }
    }

    private suspend fun verifyInstalledPackage(expectedPackage: String, expectedVersion: Long) {
        repeat(PACKAGE_VISIBILITY_RETRIES) { attempt ->
            val installedVersion = runCatching {
                versionCode(installedPackageInfo(expectedPackage))
            }.getOrNull()
            if (installedVersion == expectedVersion) return
            if (attempt < PACKAGE_VISIBILITY_RETRIES - 1) delay(PACKAGE_VISIBILITY_RETRY_MILLIS)
        }
        error("Package update reported success but the installed version does not match")
    }

    private suspend fun <T> withTemporaryUninstallRestriction(block: suspend () -> T): T {
        val admin = SecureGuardDeviceAdminReceiver.getComponentName(context)
        val wasRestricted = BlockUninstallAppsFeature.isPolicyActive(
            context,
            devicePolicyManager,
            admin,
        )
        var restrictionAdded = false
        if (!wasRestricted) {
            devicePolicyManager.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
            restrictionAdded = true
        }
        return try {
            block()
        } finally {
            if (restrictionAdded) {
                devicePolicyManager.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
            }
        }
    }

    private fun verifySigningContinuity(
        installed: PackageInfo,
        archive: PackageInfo,
        expectedSigner: String?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val installedInfo = installed.signingInfo ?: error("Installed app has no signing information")
            val archiveInfo = archive.signingInfo ?: error("Update APK has no signing information")
            val installedCurrent = installedInfo.apkContentsSigners.orEmpty().map(::digest).toSet()
            val archiveCurrent = archiveInfo.apkContentsSigners.orEmpty().map(::digest).toSet()
            val archiveHistory = archiveInfo.signingCertificateHistory.orEmpty().map(::digest).toSet()
            if (expectedSigner != null) {
                require(expectedSigner in archiveCurrent) { "APK signer does not match the signed catalog" }
            }
            if (installedInfo.hasMultipleSigners() || archiveInfo.hasMultipleSigners()) {
                require(installedCurrent == archiveCurrent) {
                    "APK signer set does not match the installed app"
                }
            } else {
                require(installedCurrent.isNotEmpty() && installedCurrent.all { it in archiveHistory }) {
                    "APK signing lineage does not continue the installed app"
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val installedCurrent = installed.signatures.orEmpty().map(::digest).toSet()
            @Suppress("DEPRECATION")
            val archiveCurrent = archive.signatures.orEmpty().map(::digest).toSet()
            if (expectedSigner != null) {
                require(expectedSigner in archiveCurrent) { "APK signer does not match the signed catalog" }
            }
            require(installedCurrent.isNotEmpty() && installedCurrent == archiveCurrent) {
                "APK signer set does not match the installed app"
            }
        }
    }

    private fun currentSignerDigests(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty().map(::digest).toSet()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().map(::digest).toSet()
        }

    private fun digest(signature: Signature): String = MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun archivePackageInfo(file: File): PackageInfo? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
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

    private fun requireDeviceOwner() {
        require(devicePolicyManager.isDeviceOwnerApp(context.packageName)) {
            "A Bloq must remain Device Owner to manage packages silently"
        }
    }

    private data class StagedArtifact(
        val artifact: UpdateArtifact,
        val file: File,
    )

    /** A download failure worth retrying: server-side or network, not payload. */
    private class TransientDownloadException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    companion object {
        private const val TAG = "MiniStoreInstall"
        private const val INSTALLER_RESULT_TIMEOUT_MILLIS = 2 * 60 * 1000L
        private const val PACKAGE_VISIBILITY_RETRIES = 5
        private const val PACKAGE_VISIBILITY_RETRY_MILLIS = 250L
        private const val MAX_INSTALL_ARTIFACTS = 100
        private const val MAX_DOWNLOAD_REDIRECTS = 5
        private const val MAX_DOWNLOAD_ATTEMPTS = 4
        private const val DOWNLOAD_RETRY_BASE_MILLIS = 2_000L
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val TRANSIENT_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
        private const val MAX_ARTIFACT_BYTES = 4L * 1024 * 1024 * 1024
        private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")
    }
}
