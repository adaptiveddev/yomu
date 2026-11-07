package app.yomu.core.ui.components

import android.content.Context
import android.webkit.CookieManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.yomu.core.common.Share
import app.yomu.core.ui.R
import app.yomu.core.ui.theme.Dimens
import timber.log.Timber

/** Whether WebView can be used right now. */
object WebViewSupport {
    private const val PACKAGE = "com.google.android.webview"

    /**
     * Creating a WebView throws when Android System WebView is missing, disabled or in the middle of an update;
     * loading the cookie store needs the same provider and fails the same way, without creating a view.
     */
    fun isAvailable(context: Context): Boolean = try {
        CookieManager.getInstance()
        true
    } catch (e: Throwable) {
        Timber.w(e, "WebView is not available")
        false
    }

    fun openStore(context: Context) {
        if (!Share.openUrl(context, "market://details?id=$PACKAGE")) Share.openUrl(context, "https://play.google.com/store/apps/details?id=$PACKAGE")
    }
}

/**
 * Shows [content] (a screen built on WebView) or, when WebView cannot be used on this device right now, an explanation
 * with a way to install it and to try again, instead of crashing the app.
 */
@Composable
fun RequireWebView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var available by remember { mutableStateOf(WebViewSupport.isAvailable(context)) }
    if (available) {
        content()
        return
    }
    Column(
        modifier.fillMaxSize().padding(Dimens.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Web, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Text(
            stringResource(R.string.webview_missing_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Dimens.l),
        )
        Text(
            stringResource(R.string.webview_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Dimens.s),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s), modifier = Modifier.padding(top = Dimens.l)) {
            OutlinedButton(onClick = { available = WebViewSupport.isAvailable(context) }) { Text(stringResource(R.string.retry)) }
            Button(onClick = { WebViewSupport.openStore(context) }) { Text(stringResource(R.string.webview_missing_store)) }
        }
    }
}
