package com.secureguard.mdm.ministore.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.R
import com.secureguard.mdm.ministore.data.CatalogSourceState
import com.secureguard.mdm.ministore.data.ManagedInstalledApp
import com.secureguard.mdm.ministore.data.MiniStoreRepository
import com.secureguard.mdm.ministore.data.PlaySourceState
import com.secureguard.mdm.ministore.data.UpdateCandidate
import com.secureguard.mdm.ministore.data.UpdateLocator
import com.secureguard.mdm.ministore.data.UpdateOperationStage
import com.secureguard.mdm.ministore.data.UpdateSource
import com.secureguard.mdm.ministore.domain.MiniStoreAccessGate
import com.secureguard.mdm.ministore.install.MiniStorePackageOperator
import com.secureguard.mdm.ministore.play.DeviceGoogleAccounts
import com.secureguard.mdm.ministore.play.PlayAccountSession
import com.secureguard.mdm.ministore.play.PlaySessionEvent
import com.secureguard.mdm.security.PasswordManager
import com.secureguard.mdm.utils.update.DownloadProgress
import com.secureguard.mdm.utils.update.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class MiniStorePasswordPurpose { ENTRY, BLACKLIST }

/**
 * Split of the update list. The tab lists updates only, so these are subsets of
 * the updatable apps and not of everything installed.
 */
enum class MiniStoreCategory { ALL, USER, SYSTEM }

data class MiniStoreUiState(
    val accessGranted: Boolean = false,
    val isLoading: Boolean = false,
    val isAuthenticating: Boolean = false,
    val allApps: List<ManagedInstalledApp> = emptyList(),
    /**
     * The app's own pending update. Held apart from [allApps] so it is pinned
     * above the list, is not part of "update all", and cannot be filtered or
     * blacklisted away.
     */
    val selfUpdate: ManagedInstalledApp? = null,
    val blacklist: Set<String> = emptySet(),
    val selectedCategory: MiniStoreCategory = MiniStoreCategory.ALL,
    val searchQuery: String = "",
    val passwordPurpose: MiniStorePasswordPurpose? = null,
    val passwordError: String? = null,
    val blacklistEditorVisible: Boolean = false,
    val operationPackage: String? = null,
    val operationStage: UpdateOperationStage? = null,
    val operationCompletedBytes: Long = 0,
    val operationTotalBytes: Long = 0,
    val message: String? = null,
    val sourceWarning: String? = null,
    val catalogSourceState: CatalogSourceState = CatalogSourceState.FAILED,
    val playSourceState: PlaySourceState = PlaySourceState.SIGNED_OUT,
    val playSignedInEmail: String? = null,
    val deviceGoogleAccount: String? = null,
    /** Why the stored Google session stopped working, when that is known. */
    val playSessionIssue: String? = null,
    val queuedPackages: Set<String> = emptySet(),
) {
    val visibleApps: List<ManagedInstalledApp>
        get() = allApps.filterNot { it.packageName in blacklist }

    /**
     * What the updates tab shows. Listing every installed app turned the tab into
     * an inventory the user had to search through to find the one thing it is for,
     * so only apps with a pending update are listed; the user/system split is a
     * filter inside that set.
     */
    val updatableApps: List<ManagedInstalledApp>
        get() = visibleApps.filter { it.update != null }

    val updateCount: Int get() = updatableApps.size
    val userAppCount: Int get() = updatableApps.count { !it.isSystemApp }
    val systemAppCount: Int get() = updatableApps.count { it.isSystemApp }

    /** The split is offered only when updates exist on both sides of it. */
    val categoryFilterVisible: Boolean get() = userAppCount > 0 && systemAppCount > 0

    val displayedApps: List<ManagedInstalledApp>
        get() {
            val categorized = when (selectedCategory) {
                MiniStoreCategory.ALL -> updatableApps
                MiniStoreCategory.USER -> updatableApps.filterNot { it.isSystemApp }
                MiniStoreCategory.SYSTEM -> updatableApps.filter { it.isSystemApp }
            }
            val query = searchQuery.trim()
            return if (query.isEmpty()) {
                categorized
            } else {
                categorized.filter {
                    it.displayName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
            }
        }

    val isBusy: Boolean get() = isLoading || isAuthenticating || operationPackage != null

    /**
     * Updates may be requested while another one runs, since they are queued.
     * Other management actions stay blocked during an operation.
     */
    val canQueueUpdates: Boolean get() = accessGranted && !isLoading && !isAuthenticating
}

@HiltViewModel
class MiniStoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MiniStoreRepository,
    private val passwordManager: PasswordManager,
    private val packageOperator: MiniStorePackageOperator,
    private val accountSession: PlayAccountSession,
    private val accessGate: MiniStoreAccessGate,
    private val deviceAccounts: DeviceGoogleAccounts,
    private val updateManager: UpdateManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        MiniStoreUiState(
            playSourceState = if (repository.isPlaySourceConfigured()) {
                PlaySourceState.FAILED
            } else {
                PlaySourceState.SIGNED_OUT
            },
        ),
    )
    val uiState = _uiState.asStateFlow()
    private var expiryJob: Job? = null

    // Packages awaiting an update, processed one at a time.
    private val pendingUpdates = ArrayDeque<String>()
    private var queueJob: Job? = null
    private var activeUpdateJob: Job? = null

    fun setCategory(category: MiniStoreCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    init {
        Log.i(
            TAG,
            "created: passwordRequired=${repository.isPasswordRequired()} " +
                "privileged=${accessGate.isPrivileged()}",
        )
        when {
            !repository.isPasswordRequired() -> grantEntryAndLoad()
            // Returning from the Google sign-in screen recreates this ViewModel.
            // A still-valid window means the password was already accepted.
            accessGate.isPrivileged() -> {
                scheduleExpiry()
                grantEntryAndLoad()
            }
            else -> _uiState.update { it.copy(passwordPurpose = MiniStorePasswordPurpose.ENTRY) }
        }
    }

    fun submitPassword(password: String) {
        val state = _uiState.value
        val purpose = state.passwordPurpose ?: return
        if (state.isAuthenticating || state.operationPackage != null || state.isLoading) return
        _uiState.update { it.copy(isAuthenticating = true, passwordError = null) }
        viewModelScope.launch {
            val verified = withContext(Dispatchers.Default) {
                passwordManager.verifyPassword(password)
            }
            val current = _uiState.value
            if (!current.isAuthenticating || current.passwordPurpose != purpose) return@launch
            if (!verified) {
                _uiState.update { it.copy(isAuthenticating = false, passwordError = "סיסמה שגויה") }
                return@launch
            }

            accessGate.grant()
            scheduleExpiry()
            _uiState.update {
                it.copy(
                    accessGranted = true,
                    isAuthenticating = false,
                    passwordPurpose = null,
                    passwordError = null,
                )
            }
            when (purpose) {
                MiniStorePasswordPurpose.ENTRY -> refresh()
                MiniStorePasswordPurpose.BLACKLIST -> {
                    _uiState.update { it.copy(blacklistEditorVisible = true) }
                }
            }
        }
    }

    fun dismissPasswordPrompt() {
        if (_uiState.value.isAuthenticating) return
        _uiState.update { it.copy(passwordPurpose = null, passwordError = null) }
    }

    fun signOutOfPlay() {
        if (!ensureEntryAccess() || _uiState.value.isBusy) return
        accountSession.signOut()
        _uiState.update {
            it.copy(
                playSignedInEmail = null,
                playSourceState = PlaySourceState.SIGNED_OUT,
                message = "החיבור ל-Google Play הוסר",
            )
        }
        refresh()
    }

    fun refresh() {
        if (!ensureEntryAccess()) return
        val state = _uiState.value
        if (state.isBusy) return
        _uiState.update {
            it.copy(
                isLoading = true,
                message = null,
                sourceWarning = null,
                playSignedInEmail = accountSession.signedInEmail(),
                deviceGoogleAccount = deviceAccounts.primaryAccount(),
                playSessionIssue = playSessionIssue(),
                playSourceState = if (repository.isPlaySourceConfigured()) {
                    PlaySourceState.FAILED
                } else {
                    PlaySourceState.SIGNED_OUT
                },
            )
        }
        viewModelScope.launch {
            runCatching { repository.loadInstalledApps() }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            allApps = result.apps,
                            selfUpdate = result.selfUpdate,
                            blacklist = repository.blacklist(),
                            sourceWarning = result.sourceWarning,
                            catalogSourceState = result.catalogSourceState,
                            playSourceState = result.playSourceState,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = error.message ?: "טעינת האפליקציות נכשלה",
                        )
                    }
                }
        }
    }

    fun requestBlacklistEditor() {
        if (!ensureEntryAccess() || _uiState.value.isBusy) return
        _uiState.update { it.copy(blacklistEditorVisible = true) }
    }

    fun closeBlacklistEditor() = _uiState.update { it.copy(blacklistEditorVisible = false) }

    /**
     * Excludes an app straight from its card. Hiding an app from the update list
     * is reversible and does not change what the user may run, so it carries the
     * same access level as the rest of the store: no extra password.
     */
    fun blacklistApp(app: ManagedInstalledApp) {
        if (!ensureEntryAccess()) return
        pendingUpdates.remove(app.packageName)
        viewModelScope.launch {
            repository.setBlacklisted(app.packageName, true)
            _uiState.update {
                it.copy(
                    blacklist = repository.blacklist(),
                    queuedPackages = it.queuedPackages - app.packageName,
                    message = "${app.displayName} הוחרגה ולא תוצג יותר",
                )
            }
        }
    }

    fun setBlacklisted(packageName: String, blacklisted: Boolean) {
        val state = _uiState.value
        if (state.isBusy || !state.blacklistEditorVisible) return
        viewModelScope.launch {
            repository.setBlacklisted(packageName, blacklisted)
            _uiState.update { it.copy(blacklist = repository.blacklist()) }
        }
    }

    /**
     * Queues an update. Several apps can be requested one after another without
     * waiting: they are processed sequentially, because Android installs one
     * package per session and parallel downloads would only split the same
     * bandwidth.
     */
    fun update(app: ManagedInstalledApp) {
        val candidate = app.update ?: return
        // The app's own update is not queued with the rest: installing it can
        // replace this process the moment the session is committed, which would
        // silently drop whatever else was waiting in the queue.
        if (candidate.source == UpdateSource.SELF_UPDATE) {
            startSelfUpdate(app, candidate)
            return
        }
        if (!ensureEntryAccess()) return
        if (repository.isBlacklisted(app.packageName)) return
        if (app.packageName in _uiState.value.queuedPackages) return

        _uiState.update {
            it.copy(queuedPackages = it.queuedPackages + app.packageName, message = null)
        }
        pendingUpdates.add(app.packageName)
        drainUpdateQueue()
    }

    /**
     * Runs the app's own update through [UpdateManager], which already owns
     * manifest validation, redirect pinning, signing continuity and the silent
     * install for this one package. The mini-store install pipeline is left
     * untouched: it refuses to install the host package on purpose.
     *
     * Requires an idle store, because the install can replace this process and
     * anything else in flight would be lost without explanation.
     */
    private fun startSelfUpdate(app: ManagedInstalledApp, candidate: UpdateCandidate) {
        if (!ensureEntryAccess()) return
        val info = (candidate.locator as? UpdateLocator.SelfUpdate)?.info ?: return
        val state = _uiState.value
        if (state.isBusy || state.queuedPackages.isNotEmpty()) {
            _uiState.update { it.copy(message = "יש להמתין לסיום העדכון הפעיל") }
            return
        }

        val packageName = app.packageName
        _uiState.update {
            it.copy(
                queuedPackages = it.queuedPackages + packageName,
                operationPackage = packageName,
                operationStage = UpdateOperationStage.RESOLVING,
                operationCompletedBytes = 0,
                operationTotalBytes = info.apkSize,
                message = null,
            )
        }

        // Assigned to the same handle as a regular update, so the existing cancel
        // rules apply: cancellable while downloading, refused once installing.
        val job = viewModelScope.launch {
            try {
                updateManager.downloadAndInstallUpdate(info).collect { progress ->
                    when (progress) {
                        is DownloadProgress.Downloading -> updateOperationProgress(
                            UpdateOperationStage.DOWNLOADING,
                            info.apkSize * progress.progress.coerceIn(0, 100) / 100,
                            info.apkSize,
                        )
                        DownloadProgress.Installing -> updateOperationProgress(
                            UpdateOperationStage.INSTALLING,
                            info.apkSize,
                            info.apkSize,
                        )
                        // The verified session was handed to Android. The final
                        // result arrives from InstallReceiver, and this process may
                        // be replaced before anything else here runs.
                        DownloadProgress.Completed ->
                            _uiState.update { it.copy(message = "העדכון מותקן…") }
                        is DownloadProgress.Error ->
                            _uiState.update { it.copy(message = progress.message) }
                    }
                }
            } catch (cancellation: CancellationException) {
                _uiState.update { it.copy(message = "העדכון בוטל") }
                throw cancellation
            } finally {
                _uiState.update {
                    it.copy(
                        queuedPackages = it.queuedPackages - packageName,
                        operationPackage = null,
                        operationStage = null,
                        operationCompletedBytes = 0,
                        operationTotalBytes = 0,
                    )
                }
            }
        }
        activeUpdateJob = job
        job.invokeOnCompletion {
            if (activeUpdateJob === job) activeUpdateJob = null
            // Same as the regular queue: reload once the operation settles. After a
            // successful commit this may never run, because the process is replaced.
            refresh()
        }
    }

    private fun drainUpdateQueue() {
        if (queueJob?.isActive == true) return
        queueJob = viewModelScope.launch {
            var failures = 0
            var succeeded = 0
            var cancelled = 0
            while (true) {
                val packageName = pendingUpdates.removeFirstOrNull() ?: break
                if (!ensureEntryAccess()) {
                    pendingUpdates.clear()
                    _uiState.update {
                        it.copy(
                            queuedPackages = emptySet(),
                            message = "העדכון נעצר כי הרשאת הניהול פגה",
                        )
                    }
                    return@launch
                }

                val app = _uiState.value.allApps.firstOrNull { it.packageName == packageName }
                val candidate = app?.update
                if (candidate == null || repository.isBlacklisted(packageName)) {
                    _uiState.update { it.copy(queuedPackages = it.queuedPackages - packageName) }
                    continue
                }

                _uiState.update {
                    it.copy(
                        operationPackage = packageName,
                        operationStage = UpdateOperationStage.RESOLVING,
                        operationCompletedBytes = 0,
                        operationTotalBytes = 0,
                    )
                }
                // Wrapped in its own scope so cancelling one update does not tear
                // down the queue loop, and the outcome is reported per app.
                val outcome = runCatching {
                    coroutineScope {
                        val job = launch {
                            packageOperator.update(candidate, ::updateOperationProgress)
                        }
                        activeUpdateJob = job
                        job.join()
                    }
                }
                activeUpdateJob = null

                when {
                    outcome.isSuccess -> {
                        succeeded++
                        _uiState.update { it.copy(message = "${app.displayName} עודכנה בהצלחה") }
                    }
                    outcome.exceptionOrNull() is CancellationException -> {
                        cancelled++
                        _uiState.update { it.copy(message = "העדכון של ${app.displayName} בוטל") }
                    }
                    else -> {
                        failures++
                        _uiState.update {
                            it.copy(
                                message = outcome.exceptionOrNull()?.message
                                    ?: "העדכון של ${app.displayName} נכשל",
                            )
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        queuedPackages = it.queuedPackages - packageName,
                        operationPackage = null,
                        operationStage = null,
                        operationCompletedBytes = 0,
                        operationTotalBytes = 0,
                    )
                }
            }
            if (succeeded + failures + cancelled > 1) {
                _uiState.update {
                    it.copy(
                        message = "הושלמו $succeeded עדכונים, $failures נכשלו, $cancelled בוטלו",
                    )
                }
            }
            refresh()
        }
    }

    /**
     * Cancels an update. A queued app is simply dropped from the queue; the one
     * currently running is interrupted. Interrupting is safe: the install session
     * is only committed after every artifact is downloaded and verified, so
     * cancelling before that leaves the installed app untouched and the staging
     * directory is cleaned up.
     */
    fun cancelUpdate(packageName: String) {
        if (!ensureEntryAccess()) return
        val state = _uiState.value
        if (packageName !in state.queuedPackages) return

        // Once the install session is committed the outcome is Android's, so the
        // install phase itself is not interruptible.
        if (state.operationPackage == packageName &&
            state.operationStage == UpdateOperationStage.INSTALLING
        ) {
            _uiState.update { it.copy(message = "ההתקנה כבר החלה ולא ניתן לבטל אותה") }
            return
        }

        pendingUpdates.remove(packageName)
        if (state.operationPackage == packageName) {
            Log.i(TAG, "cancelling the running update for $packageName")
            activeUpdateJob?.cancel()
        } else {
            _uiState.update {
                it.copy(
                    queuedPackages = it.queuedPackages - packageName,
                    message = "העדכון בוטל",
                )
            }
        }
    }

    /** Cancels everything: the running update and the whole queue. */
    fun cancelAllUpdates() {
        if (!ensureEntryAccess()) return
        if (_uiState.value.queuedPackages.isEmpty()) return
        pendingUpdates.clear()
        activeUpdateJob?.cancel()
        _uiState.update { it.copy(queuedPackages = emptySet(), message = "העדכונים בוטלו") }
    }

    /** Enqueues every visible update; the same queue processes them in order. */
    fun updateAll() {
        if (!ensureEntryAccess()) return
        val state = _uiState.value
        val updates = state.updatableApps
            .filter { it.packageName !in state.queuedPackages }
        if (updates.isEmpty()) return
        _uiState.update {
            it.copy(
                queuedPackages = it.queuedPackages + updates.map(ManagedInstalledApp::packageName),
                message = null,
            )
        }
        updates.forEach { pendingUpdates.add(it.packageName) }
        drainUpdateQueue()
    }

    /**
     * Called when the screen becomes visible again, including on return from the
     * Google sign-in screen. That return reuses the existing screen, so nothing
     * else would notice that the account state changed.
     */
    fun onScreenResumed() {
        val signedInEmail = accountSession.signedInEmail()
        val accountChanged = signedInEmail != _uiState.value.playSignedInEmail
        _uiState.update {
            it.copy(
                playSignedInEmail = signedInEmail,
                deviceGoogleAccount = deviceAccounts.primaryAccount(),
            )
        }
        if (!accountChanged) return
        Log.i(TAG, "account state changed while away; reloading updates")
        val state = _uiState.value
        if (state.accessGranted && !state.isBusy) refresh()
    }

    fun onBackgrounded() {
        Log.i(TAG, "backgrounded: management authorisation revoked")
        accessGate.revoke()
        expiryJob?.cancel()
        expiryJob = null
        val passwordRequired = repository.isPasswordRequired()
        _uiState.update {
            it.copy(
                accessGranted = !passwordRequired,
                isAuthenticating = false,
                passwordPurpose = if (passwordRequired) MiniStorePasswordPurpose.ENTRY else null,
                passwordError = null,
                blacklistEditorVisible = false,
            )
        }
    }

    private fun updateOperationProgress(
        stage: UpdateOperationStage,
        completedBytes: Long,
        totalBytes: Long,
    ) {
        _uiState.update {
            it.copy(
                operationStage = stage,
                operationCompletedBytes = completedBytes,
                operationTotalBytes = totalBytes,
            )
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    private companion object {
        const val TAG = "MiniStoreAccess"
    }

    private fun grantEntryAndLoad() {
        _uiState.update { it.copy(accessGranted = true, passwordPurpose = null) }
        refresh()
    }

    private fun ensureEntryAccess(): Boolean {
        val state = _uiState.value
        if (!state.accessGranted) return false
        if (!repository.isPasswordRequired() || isPrivileged()) return true
        expireAuthorization()
        return false
    }

    /**
     * Human-readable reason for a lost session, taken from the durable audit
     * trail. Reported only while the device is signed out, so the user is told
     * why a sign-in is being requested instead of facing a silent prompt.
     */
    private fun playSessionIssue(): String? {
        if (accountSession.signedInEmail() != null) return null
        val status = accountSession.status()
        val event = status.lastFailureEvent ?: return null
        if (status.lastFailureAtMillis <= 0L) return null
        val reason = when (event) {
            PlaySessionEvent.SESSION_INVALIDATED.name,
            PlaySessionEvent.CREDENTIAL_REJECTED.name,
            PlaySessionEvent.REFRESH_FAILED.name,
            -> R.string.mini_store_play_issue_rejected
            PlaySessionEvent.REFRESH_UNAVAILABLE.name -> R.string.mini_store_play_issue_no_refresh
            PlaySessionEvent.KEYSTORE_KEY_MISSING_CLEARED.name,
            PlaySessionEvent.SESSION_UNREADABLE_CLEARED.name,
            -> R.string.mini_store_play_issue_unreadable
            PlaySessionEvent.SESSION_LOAD_TRANSIENT_KEPT.name ->
                R.string.mini_store_play_issue_temporary
            else -> return null
        }
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(status.lastFailureAtMillis))
        return context.getString(reason, date)
    }

    private fun isPrivileged(): Boolean = accessGate.isPrivileged()

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        expiryJob = viewModelScope.launch {
            delay(accessGate.remainingMillis())
            expireAuthorization()
        }
    }

    private fun expireAuthorization() {
        accessGate.revoke()
        expiryJob?.cancel()
        expiryJob = null
        val passwordRequired = repository.isPasswordRequired()
        _uiState.update {
            it.copy(
                accessGranted = if (passwordRequired) false else it.accessGranted,
                isAuthenticating = false,
                passwordPurpose = if (passwordRequired) MiniStorePasswordPurpose.ENTRY else null,
                passwordError = null,
                blacklistEditorVisible = false,
            )
        }
    }

}
