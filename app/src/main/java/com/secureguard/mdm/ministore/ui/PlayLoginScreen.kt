package com.secureguard.mdm.ministore.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.secureguard.mdm.R
import kotlinx.coroutines.delay

/**
 * On-device Google sign-in for the Mini Store.
 *
 * The web flow stores a single-use `oauth_token` cookie once the account
 * consent screen is accepted. That cookie is `HttpOnly`, so page scripts cannot
 * read it, but the app can through [CookieManager]. The cookie jar is polled
 * rather than hooked to page callbacks, because the final step of the flow does
 * not reliably emit a page-finished event.
 *
 * The Play Store app plays no part in this: the page is served by Google and
 * the session is minted directly against Google's servers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayLoginScreen(
    onNavigateBack: () -> Unit,
    viewModel: PlayLoginViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    BackHandler(enabled = !state.isExchanging) { onNavigateBack() }

    LaunchedEffect(state.completedEmail) {
        if (state.completedEmail != null) onNavigateBack()
    }

    // Poll the cookie jar while the web flow is on screen. Google refreshes the
    // consent cookie once the user accepts, so polling continues after a
    // rejected attempt instead of giving up on the first value.
    LaunchedEffect(state.step, state.reloadKey) {
        if (state.step != PlayLoginStep.WEB_SIGN_IN) return@LaunchedEffect
        var elapsedWithToken = 0L
        while (true) {
            val token = capturedOauthToken()
            if (token != null) {
                viewModel.onTokenCaptured(token)
                elapsedWithToken += COOKIE_POLL_INTERVAL_MILLIS
                // The address is normally read from the page; ask for it only if
                // Google never showed it.
                if (elapsedWithToken >= MANUAL_EMAIL_FALLBACK_MILLIS) {
                    viewModel.requestManualEmail()
                }
            }
            delay(COOKIE_POLL_INTERVAL_MILLIS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mini_store_play_login_title)) },
                actions = {
                    TextButton(onClick = onNavigateBack, enabled = !state.isExchanging) {
                        Text(stringResource(R.string.mini_store_play_login_cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.isExchanging) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            when (state.step) {
                PlayLoginStep.EXCHANGING -> CenteredMessage {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.mini_store_play_login_exchanging))
                }

                PlayLoginStep.ENTER_EMAIL -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.mini_store_play_login_email_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = viewModel::setEmail,
                        singleLine = true,
                        label = { Text(stringResource(R.string.mini_store_play_login_email_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = viewModel::confirmEmail,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.mini_store_play_login_continue))
                    }
                }

                PlayLoginStep.FAILED -> CenteredMessage {
                    Text(
                        text = stringResource(R.string.mini_store_play_login_retry_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = viewModel::restart) {
                        Text(stringResource(R.string.mini_store_play_login_retry))
                    }
                }

                PlayLoginStep.WEB_SIGN_IN -> Column(modifier = Modifier.fillMaxSize()) {
                    state.deviceAccount?.let { account ->
                        Text(
                            text = stringResource(
                                R.string.mini_store_play_login_continue_with_account,
                                account,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    GoogleSignInWebView(
                        reloadKey = state.reloadKey,
                        loginHint = state.deviceAccount,
                        onEmailDiscovered = viewModel::onEmailDiscovered,
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) { content() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GoogleSignInWebView(
    reloadKey: Int,
    loginHint: String?,
    onEmailDiscovered: (String) -> Unit,
) {
    val signInUrl = signInUrl(loginHint)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            resetConsentCookie()
            WebView(context).apply {
                // The default WebView user agent is kept deliberately: overriding
                // it makes Google's setup flow behave inconsistently.
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportZoom(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        Log.i(TAG, "sign-in page loaded: ${url.substringBefore('?').take(80)}")
                        discoverEmail(view, onEmailDiscovered)
                    }

                    override fun doUpdateVisitedHistory(
                        view: WebView,
                        url: String,
                        isReload: Boolean,
                    ) {
                        // The consent step navigates without a fresh page load.
                        discoverEmail(view, onEmailDiscovered)
                    }
                }
                loadUrl(signInUrl)
            }
        },
        update = { webView ->
            if (webView.getTag(RELOAD_TAG) != reloadKey) {
                webView.setTag(RELOAD_TAG, reloadKey)
                resetConsentCookie()
                webView.loadUrl(signInUrl)
            }
        },
    )
}

/**
 * Pre-selects the account already present on the device, so the operator
 * confirms an account instead of choosing one. Google still performs the
 * authentication; the hint only skips account selection.
 */
private fun signInUrl(loginHint: String?): String =
    if (loginHint.isNullOrBlank()) {
        EMBEDDED_SETUP_URL
    } else {
        "$EMBEDDED_SETUP_URL?Email=" + Uri.encode(loginHint)
    }

/**
 * Reads the signed-in address from the page so the user is not asked to type an
 * address that Google already knows. Best effort: the manual field is shown if
 * nothing is found.
 */
private fun discoverEmail(webView: WebView, onEmailDiscovered: (String) -> Unit) {
    webView.evaluateJavascript(EMAIL_PROBE_SCRIPT) { result ->
        val email = result?.trim('"', ' ', '\n')?.takeIf { it.contains('@') && it != "null" }
        if (email != null) onEmailDiscovered(email)
    }
}

/**
 * Clears only the consent cookie. A cookie left from an earlier attempt is
 * already spent and would be rejected, but the Google sign-in cookies are kept
 * so a retry does not force the user through two-factor verification again.
 */
private fun resetConsentCookie() {
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setCookie(COOKIE_DOMAIN, "$OAUTH_COOKIE_NAME=; Max-Age=0; Path=/")
        flush()
    }
}

private fun capturedOauthToken(): String? {
    val cookies = CookieManager.getInstance().getCookie(COOKIE_DOMAIN)
    if (cookies == null) {
        Log.i(TAG, "no cookies for the sign-in domain yet")
        return null
    }
    val names = cookies.split(';').mapNotNull { it.trim().substringBefore('=').takeIf(String::isNotBlank) }
    val token = cookies.split(';')
        .map(String::trim)
        .firstOrNull { it.startsWith("$OAUTH_COOKIE_NAME=") }
        ?.substringAfter('=')
        ?.trim()
    // Names only, never values.
    Log.i(TAG, "cookies present: ${names.joinToString(",")}; consent cookie length=${token?.length ?: 0}")
    return token?.takeIf { it.startsWith(OAUTH_TOKEN_PREFIX) }
}

private const val EMBEDDED_SETUP_URL = "https://accounts.google.com/EmbeddedSetup"
private const val COOKIE_DOMAIN = "https://accounts.google.com"
private const val OAUTH_COOKIE_NAME = "oauth_token"
private const val OAUTH_TOKEN_PREFIX = "oauth2_4/"
private const val COOKIE_POLL_INTERVAL_MILLIS = 1_000L
private const val MANUAL_EMAIL_FALLBACK_MILLIS = 12_000L
private const val TAG = "MiniStorePlayLogin"
private val RELOAD_TAG = R.string.mini_store_play_login_title
private const val EMAIL_PROBE_SCRIPT =
    "(function(){var m=document.body.innerText.match(/[\\w.+-]+@[\\w-]+\\.[\\w.-]+/);" +
        "return m?m[0]:null;})();"
