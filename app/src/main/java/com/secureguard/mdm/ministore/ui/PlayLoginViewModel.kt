package com.secureguard.mdm.ministore.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.ministore.play.DeviceGoogleAccounts
import com.secureguard.mdm.ministore.play.PlayAccountSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlayLoginStep { WEB_SIGN_IN, ENTER_EMAIL, EXCHANGING, FAILED }

data class PlayLoginUiState(
    val step: PlayLoginStep = PlayLoginStep.WEB_SIGN_IN,
    val email: String = "",
    val message: String? = null,
    val reloadKey: Int = 0,
    val completedEmail: String? = null,
    val deviceAccount: String? = null,
) {
    val isExchanging: Boolean get() = step == PlayLoginStep.EXCHANGING
}

/**
 * Drives the on-device sign-in.
 *
 * Google writes the consent cookie while the consent screen is still being
 * shown, so the first value observed is not necessarily usable. Rather than
 * consuming a single value and failing, every distinct cookie value is tried and
 * the web flow stays open until one is accepted or the attempt budget is spent.
 */
@HiltViewModel
class PlayLoginViewModel @Inject constructor(
    private val accountSession: PlayAccountSession,
    deviceAccounts: DeviceGoogleAccounts,
) : ViewModel() {
    // The device account is a hint for the sign-in page only: it pre-selects an
    // account in the picker. It is deliberately not used as the address of the
    // session, because the user may sign in with a different account.
    private val deviceAccount = deviceAccounts.primaryAccount()

    private val _uiState = MutableStateFlow(PlayLoginUiState(deviceAccount = deviceAccount))
    val uiState = _uiState.asStateFlow()

    private val rejectedTokens = mutableSetOf<String>()

    @Volatile
    private var exchangeInFlight = false

    @Volatile
    private var pendingToken: String? = null

    /**
     * Address read from the sign-in page, so the user is not asked to retype it.
     *
     * The page address always wins over any address held here. Previously the
     * state was pre-seeded with the device account and this callback refused to
     * overwrite it, so a user who signed in with a second account still got a
     * session labelled with the device account, and the store kept showing the
     * device account after a successful sign-in.
     */
    fun onEmailDiscovered(email: String) {
        val trimmed = email.trim()
        if (!isPlausibleEmail(trimmed)) return
        if (trimmed.equals(_uiState.value.email, ignoreCase = true)) return
        Log.i(TAG, "account address detected on the sign-in page")
        _uiState.update { it.copy(email = trimmed) }
        // A token may already be waiting for an address.
        if (exchangeInFlight) return
        pendingToken?.takeIf { it !in rejectedTokens }?.let { tryExchange(trimmed, it) }
    }

    fun setEmail(value: String) {
        _uiState.update { it.copy(email = value, message = null) }
    }

    fun confirmEmail() {
        val email = _uiState.value.email.trim()
        if (!isPlausibleEmail(email)) {
            _uiState.update { it.copy(message = "כתובת החשבון אינה תקינה") }
            return
        }
        val token = pendingToken
        if (token == null || token in rejectedTokens) {
            _uiState.update { it.copy(step = PlayLoginStep.WEB_SIGN_IN, message = null) }
            return
        }
        tryExchange(email, token)
    }

    /** Called repeatedly while the web flow is open, with the current cookie value. */
    fun onTokenCaptured(oauthToken: String) {
        if (_uiState.value.step != PlayLoginStep.WEB_SIGN_IN) return
        if (exchangeInFlight || oauthToken in rejectedTokens) return

        pendingToken = oauthToken
        val email = _uiState.value.email.trim()
        if (!isPlausibleEmail(email)) return
        tryExchange(email, oauthToken)
    }

    fun restart() {
        rejectedTokens.clear()
        pendingToken = null
        exchangeInFlight = false
        _uiState.update {
            it.copy(
                step = PlayLoginStep.WEB_SIGN_IN,
                email = "",
                message = null,
                reloadKey = it.reloadKey + 1,
            )
        }
    }

    /** Shown only when Google never exposed the address on the page. */
    fun requestManualEmail() {
        if (_uiState.value.step == PlayLoginStep.WEB_SIGN_IN && pendingToken != null) {
            _uiState.update { it.copy(step = PlayLoginStep.ENTER_EMAIL) }
        }
    }

    private fun tryExchange(email: String, oauthToken: String) {
        if (exchangeInFlight) return
        exchangeInFlight = true
        _uiState.update { it.copy(step = PlayLoginStep.EXCHANGING, message = null) }

        viewModelScope.launch {
            runCatching { accountSession.signIn(email, oauthToken) }
                .onSuccess { signedInEmail ->
                    Log.i(TAG, "Play session established")
                    exchangeInFlight = false
                    pendingToken = null
                    _uiState.update { it.copy(completedEmail = signedInEmail) }
                }
                .onFailure { error ->
                    // Type and message only; never the token value.
                    Log.w(TAG, "sign-in attempt rejected: ${error.javaClass.simpleName}: ${error.message}")
                    rejectedTokens += oauthToken
                    exchangeInFlight = false
                    pendingToken = null
                    val exhausted = rejectedTokens.size >= MAX_ATTEMPTS
                    _uiState.update {
                        it.copy(
                            // Stay in the web flow so pressing "I agree" issues a
                            // fresh cookie that is picked up automatically.
                            step = if (exhausted) PlayLoginStep.FAILED else PlayLoginStep.WEB_SIGN_IN,
                            message = if (exhausted) {
                                error.message ?: "ההתחברות ל-Google Play נכשלה"
                            } else {
                                "ממתין לאישור ההתחברות במסך של Google…"
                            },
                        )
                    }
                }
        }
    }

    private fun isPlausibleEmail(value: String): Boolean =
        value.length >= 5 && value.contains('@') && value.substringAfter('@').contains('.')

    private companion object {
        const val TAG = "MiniStorePlayLogin"
        const val MAX_ATTEMPTS = 6
    }
}
