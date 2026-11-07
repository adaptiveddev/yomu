package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import app.yomu.compat.tachiyomi.R
import app.yomu.compat.tachiyomi.internal.isOutdated
import app.yomu.compat.tachiyomi.internal.toWebViewHeaders
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Solves non-interactive Cloudflare challenges in an invisible WebView and retries the request with the
 * resulting `cf_clearance` cookie. Interactive challenges (captcha) must be solved by the user in WebView.
 */
class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    // Requests to a host share its lock while a challenge is solved, so concurrent requests wait for one WebView.
    private val hostLocks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    private val listenerScript = """
        addEventListener("message", ({data}) => {
            if (data?.source === "cloudflare-challenge") {
                window.yomuCf?.postMessage(data.event);
            }
        })
    """.trimIndent()

    override fun shouldIntercept(response: Response): Boolean {
        // The cf-mitigated header is the official way to detect a Cloudflare challenge:
        // https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/
        return response.header("cf-mitigated") == "challenge" && response.header("Server") in SERVER_CHECK
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val lock = hostLocks.computeIfAbsent(request.url.host) { ReentrantReadWriteLock() }

        val (response, clearance) = lock.read {
            val response = chain.proceed(request)
            if (!shouldIntercept(response)) return response
            response to clearance(request.url)
        }

        lock.write {
            // Another request may have solved the challenge while this one waited
            if (isCleared(request.url, clearance)) {
                response.close()
            } else {
                if (!prepareWebView()) return response
                solve(request, response, clearance)
            }
        }

        return chain.proceed(request)
    }

    override fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response {
        solve(request, response, clearance(request.url))
        return chain.proceed(request)
    }

    private fun clearance(url: HttpUrl): String? {
        return cookieManager.get(url).firstOrNull { it.name == "cf_clearance" }?.value
    }

    private fun isCleared(url: HttpUrl, previousClearance: String?): Boolean {
        val clearance = clearance(url)
        return !clearance.isNullOrBlank() && clearance != previousClearance
    }

    private fun solve(request: Request, response: Response, clearance: String?) {
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            resolveWithWebView(request, clearance)
        }
        // OkHttp's enqueue only handles IOExceptions: wrap so the app doesn't crash
        catch (e: CloudflareBypassException) {
            throw IOException(context.getString(R.string.compat_cloudflare_failed), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun resolveWithWebView(originalRequest: Request, originalClearance: String?) {
        // OkHttp doesn't support asynchronous interceptors: block this thread until the WebView is done.
        val latch = CountDownLatch(1)

        var webview: WebView? = null
        var challengeFound = false
        var cloudflareBypassed = false
        var challengeFailed = false
        var isWebViewOutdated = false
        var isRenderProcessGone = false

        val origRequestUrl = originalRequest.url.toString()
        // A WebView can only load a page with GET; the clearance covers the whole site, so earn it on the root page.
        val challengeUrl = if (originalRequest.method == "GET") {
            origRequestUrl
        } else {
            originalRequest.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        }
        val headers = originalRequest.headers.toWebViewHeaders()

        executor.execute {
            val view = createWebView(originalRequest)
            webview = view

            fun onChallengeEvent(event: String) {
                when (event) {
                    // The challenge cannot be solved non-interactively, abort.
                    "interactiveBegin" -> latch.countDown()
                    "fail" -> {
                        challengeFailed = true
                        latch.countDown()
                    }
                }
            }

            view.addJavascriptInterface(
                object {
                    @Suppress("unused")
                    @JavascriptInterface
                    fun postMessage(event: String) = onChallengeEvent(event)
                },
                "yomuCf",
            )

            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!challengeFailed && isCleared(originalRequest.url, originalClearance)) {
                        cloudflareBypassed = true
                        latch.countDown()
                    }
                    if (url == challengeUrl) {
                        if (!challengeFound) {
                            // The first request didn't return the challenge, abort.
                            latch.countDown()
                        } else {
                            view.evaluateJavascript(listenerScript, null)
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        val mitigated = errorResponse?.responseHeaders?.entries
                            ?.firstOrNull { it.key.equals("cf-mitigated", ignoreCase = true) }?.value
                        if (mitigated == "challenge") {
                            challengeFound = true
                        } else {
                            latch.countDown()
                        }
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // Not handling it kills the app, failing the bypass is enough
                    Timber.e("WebView renderer gone during Cloudflare bypass: crashed=%s, url=%s", detail.didCrash(), origRequestUrl)
                    isRenderProcessGone = true
                    latch.countDown()
                    return true
                }
            }

            view.loadUrl(challengeUrl, headers)
        }

        latch.awaitFor30Seconds()

        executor.execute {
            if (!cloudflareBypassed && !isRenderProcessGone) {
                isWebViewOutdated = webview?.isOutdated() == true
            }
            webview?.run {
                if (!isRenderProcessGone) {
                    removeJavascriptInterface("yomuCf")
                    stopLoading()
                }
                destroy()
            }
        }

        if (!cloudflareBypassed) {
            // A clearance from a failed challenge would only be rejected again
            cookieManager.remove(originalRequest.url, COOKIE_NAMES, 0)
            if (isWebViewOutdated) toast(R.string.compat_webview_outdated)
            throw CloudflareBypassException()
        }
    }
}

private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()
