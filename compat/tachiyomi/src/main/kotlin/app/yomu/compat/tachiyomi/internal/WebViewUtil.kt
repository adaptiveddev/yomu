package app.yomu.compat.tachiyomi.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import okhttp3.Headers

internal object WebViewUtil {
    /** Oldest Chromium major version that still solves current anti-bot challenges reliably. */
    const val MINIMUM_WEBVIEW_VERSION = 118

    fun supportsWebView(context: Context): Boolean = try {
        // Throws (MissingWebViewPackageException) when WebView is missing, disabled or in the middle of an update.
        CookieManager.getInstance()
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)
    } catch (_: Throwable) {
        false
    }

    val isMiui: Boolean by lazy { !systemProperty("ro.miui.ui.version.name").isNullOrEmpty() }

    @SuppressLint("PrivateApi")
    private fun systemProperty(key: String): String? = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as? String
    } catch (_: Exception) {
        null
    }

    val isSamsung: Boolean by lazy { Build.MANUFACTURER.equals("samsung", ignoreCase = true) }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.setDefaultSettings() {
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        cacheMode = WebSettings.LOAD_DEFAULT
        // Allow cookies from third-party iframes (challenge widgets are often hosted on other domains).
        CookieManager.getInstance().setAcceptThirdPartyCookies(this@setDefaultSettings, true)
    }
}

internal fun WebView.setUserAgent(userAgent: String?) {
    if (!userAgent.isNullOrBlank()) settings.userAgentString = userAgent
}

internal fun WebView.isOutdated(): Boolean = getWebViewMajorVersion() < WebViewUtil.MINIMUM_WEBVIEW_VERSION

private fun WebView.getWebViewMajorVersion(): Int {
    val pkg = WebViewCompat.getCurrentWebViewPackage(context)
    val fromPackage = pkg?.versionName?.substringBefore('.')?.toIntOrNull()
    if (fromPackage != null) return fromPackage
    val ua = settings.userAgentString ?: return 0
    return Regex("""Chrome/(\d+)""").find(ua)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/** OkHttp headers → WebView headers (WebView sets its own User-Agent / encoding / cookies). */
internal fun Headers.toWebViewHeaders(): Map<String, String> {
    val skip = setOf("user-agent", "accept-encoding", "cookie", "content-length", "host")
    return toMultimap()
        .filterKeys { it.lowercase() !in skip }
        .mapValues { it.value.getOrNull(0).orEmpty() }
}
