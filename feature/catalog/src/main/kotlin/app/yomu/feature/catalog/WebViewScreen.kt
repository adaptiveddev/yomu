package app.yomu.feature.catalog

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import app.yomu.core.common.Share
import app.yomu.core.ui.components.RequireWebView
import app.yomu.core.ui.theme.Dimens
import app.yomu.source.SourceNetwork
import app.yomu.core.ui.R as CoreR

/**
 * Built-in browser used to pass Cloudflare-like checks and to log in to sites. Cookies are shared with
 * the app's HTTP client through [CookieManager], so requests work after the check is passed here.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(url) }
    var current by remember { mutableStateOf(url) }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }
    DisposableEffect(Unit) {
        onDispose {
            // Throws when WebView is not available (the screen then showed an explanation instead).
            runCatching { CookieManager.getInstance().flush() }
            webView?.destroy()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.Close, stringResource(CoreR.string.close)) } },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.webview_reload)) }
                        IconButton(onClick = onBack) { Icon(Icons.Filled.Done, stringResource(CoreR.string.done)) }
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, stringResource(CoreR.string.more)) }
                        DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.webview_open_browser)) },
                                leadingIcon = { Icon(Icons.Filled.OpenInBrowser, null) },
                                onClick = { showMenu = false; Share.openUrl(context, current) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(CoreR.string.share)) },
                                leadingIcon = { Icon(Icons.Filled.Share, null) },
                                onClick = { showMenu = false; Share.shareText(context, current) },
                            )
                        }
                    },
                )
                if (progress in 0.01f..0.99f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.webview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
                )
            }
        },
    ) { padding ->
        RequireWebView(Modifier.padding(padding)) {
            AndroidView(
                modifier = Modifier.padding(padding).fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.userAgentString = SourceNetwork.userAgent
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme.orEmpty()
                                return scheme != "http" && scheme != "https"
                            }
    
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                current = url
                                canGoBack = view.canGoBack()
                            }
    
                            override fun onPageFinished(view: WebView, url: String) {
                                current = url
                                canGoBack = view.canGoBack()
                                CookieManager.getInstance().flush()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress / 100f
                            }
    
                            override fun onReceivedTitle(view: WebView, t: String?) {
                                if (!t.isNullOrBlank()) title = t
                            }
                        }
                        loadUrl(url, mapOf("User-Agent" to SourceNetwork.userAgent))
                        webView = this
                    }
                },
            )
        }
    }
}
