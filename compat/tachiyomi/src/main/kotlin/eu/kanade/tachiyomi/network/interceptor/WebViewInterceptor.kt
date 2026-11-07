package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import app.yomu.compat.tachiyomi.R
import app.yomu.compat.tachiyomi.internal.WebViewUtil
import app.yomu.compat.tachiyomi.internal.setDefaultSettings
import app.yomu.compat.tachiyomi.internal.setUserAgent
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class WebViewInterceptor(
    private val context: Context,
    private val defaultUserAgentProvider: () -> String,
) : Interceptor {

    /**
     * Initializes the WebView once (off the main thread) to avoid blocking the UI later.
     */
    private val initWebView by lazy {
        // Crashes on some devices; the only impact of skipping is a slower first WebView.
        // See https://bugs.chromium.org/p/chromium/issues/detail?id=1279562
        if (WebViewUtil.isMiui || (Build.VERSION.SDK_INT == Build.VERSION_CODES.S && WebViewUtil.isSamsung)) {
            return@lazy
        }
        try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            // Avoid crashes while Chrome/WebView is being updated.
        }
    }

    abstract fun shouldIntercept(response: Response): Boolean

    abstract fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!shouldIntercept(response)) {
            return response
        }
        if (!prepareWebView()) return response
        return intercept(chain, request, response)
    }

    /**
     * Returns whether a WebView can be used, initializing it if so.
     */
    protected fun prepareWebView(): Boolean {
        if (!WebViewUtil.supportsWebView(context)) {
            toast(R.string.compat_webview_required)
            return false
        }
        initWebView
        return true
    }

    protected fun toast(res: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, res, Toast.LENGTH_LONG).show()
        }
    }

    fun CountDownLatch.awaitFor30Seconds() {
        await(30, TimeUnit.SECONDS)
    }

    fun createWebView(request: Request): WebView {
        return WebView(context).apply {
            setDefaultSettings()
            // Avoid sending empty User-Agent, Chromium WebView will reset to default if empty
            setUserAgent(request.header("User-Agent") ?: defaultUserAgentProvider())
        }
    }
}
