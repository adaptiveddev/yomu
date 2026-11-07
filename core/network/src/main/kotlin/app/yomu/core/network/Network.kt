package app.yomu.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/** Shares cookies between OkHttp and WebView so that a solved Cloudflare challenge is reused by HTTP requests. */
class WebViewCookieJar : CookieJar {
    private val manager: CookieManager? by lazy { runCatching { CookieManager.getInstance() }.getOrNull() }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = manager ?: return
        cookies.forEach { m.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = manager?.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }

    fun clear() {
        manager?.removeAllCookies(null)
    }
}

/**
 * First interceptor of the client: an unexpected exception further down the chain (a bug in an interceptor, a broken
 * cookie store, a malformed answer) fails only that request with an [IOException]. For enqueued calls OkHttp would
 * otherwise rethrow it on its own thread and crash the app.
 */
class UncaughtErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = try {
        chain.proceed(chain.request())
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e.message ?: e.javaClass.simpleName, e)
    } catch (e: StackOverflowError) {
        throw IOException("Stack overflow while handling the request", e)
    }
}

/** Retries idempotent requests on IO errors and on 429/5xx with exponential backoff. */
class RetryInterceptor(private val maxRetries: Int = 2, private val baseDelayMs: Long = 600) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" && request.method != "HEAD") return chain.proceed(request)
        var attempt = 0
        var lastError: IOException? = null
        while (attempt <= maxRetries) {
            try {
                val response = chain.proceed(request)
                val retryable = response.code == 429 || response.code in 502..504
                if (!retryable || attempt == maxRetries) return response
                val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1000)
                response.close()
                Thread.sleep(retryAfter?.coerceAtMost(10_000) ?: (baseDelayMs shl attempt))
            } catch (e: IOException) {
                lastError = e
                if (attempt == maxRetries || chain.call().isCanceled()) throw e
                Thread.sleep(baseDelayMs shl attempt)
            }
            attempt++
        }
        throw lastError ?: IOException("Retry failed")
    }
}

/**
 * Simple per-host rate limiter: at most [permits] requests per [periodMs] for hosts matching [hosts] (all when empty;
 * subdomains match too) except [except].
 */
class RateLimitInterceptor(
    private val permits: Int,
    private val periodMs: Long,
    private val hosts: Set<String> = emptySet(),
    private val except: Set<String> = emptySet(),
) : Interceptor {
    private val history = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private fun Set<String>.matches(host: String) = any { host == it || host.endsWith(".$it") }

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        if ((hosts.isNotEmpty() && !hosts.matches(host)) || except.matches(host)) return chain.proceed(chain.request())
        val queue = history.getOrPut(host) { ArrayDeque() }
        while (true) {
            val wait: Long = synchronized(queue) {
                val now = System.currentTimeMillis()
                while (queue.isNotEmpty() && now - queue.first() > periodMs) queue.removeFirst()
                if (queue.size < permits) {
                    queue.addLast(now)
                    0L
                } else {
                    periodMs - (now - queue.first())
                }
            }
            if (wait <= 0L) break
            Thread.sleep(wait.coerceAtLeast(10))
        }
        return chain.proceed(chain.request())
    }
}

/**
 * Extra request headers per host, registered by the catalog layer: covers and pages of extension sources often need
 * the source's Referer or User-Agent even when they are loaded by the shared client (for example by the image loader).
 */
object HostHeaders {
    private val byHost = ConcurrentHashMap<String, Map<String, String>>()

    fun register(host: String, headers: Map<String, String>) {
        if (host.isBlank() || headers.isEmpty()) return
        val clean = headers.filterKeys { it.isNotBlank() && !it.equals("Host", true) && !it.equals("Content-Length", true) && !it.equals("Accept-Encoding", true) }
        if (clean.isNotEmpty() && byHost[host] != clean) byHost[host] = clean
    }

    /** Registers the host of [url] (a cover or page address). */
    fun registerUrl(url: String?, headers: Map<String, String>) {
        val host = url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore(':')?.lowercase().orEmpty()
        register(host, headers)
    }

    operator fun get(host: String): Map<String, String>? = byHost[host]
}

private class HostHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val extra = HostHeaders[request.url.host] ?: return chain.proceed(request)
        val builder = request.newBuilder()
        extra.forEach { (k, v) -> if (request.header(k) == null) builder.header(k, v) }
        return chain.proceed(builder.build())
    }
}

private class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
    }
}

/** Owner of the shared OkHttp client. */
class NetworkClients(context: Context) {
    val cookieJar = WebViewCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http"), 64L * 1024 * 1024))
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        // Covers of a grid load in parallel: OkHttp allows only 5 requests per host by default.
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
        .addInterceptor(UncaughtErrorInterceptor())
        .addInterceptor(HostHeadersInterceptor())
        .addInterceptor(UserAgentInterceptor(DEFAULT_USER_AGENT))
        // The MangaDex API allows about 5 requests per second; its image server (covers) is not held to that limit.
        .addInterceptor(RateLimitInterceptor(permits = 5, periodMs = 1000, hosts = setOf("mangadex.org"), except = setOf(MANGADEX_UPLOADS)))
        .addInterceptor(RateLimitInterceptor(permits = 25, periodMs = 1000, hosts = setOf(MANGADEX_UPLOADS)))
        .addInterceptor(RetryInterceptor())
        .addNetworkInterceptor(TrafficMeter.interceptor)
        .build()

    /** Client without cache for large downloads. */
    val downloadClient: OkHttpClient = client.newBuilder().cache(null).readTimeout(90, TimeUnit.SECONDS).build()

    private companion object {
        const val MAX_REQUESTS_PER_HOST = 10
        const val MANGADEX_UPLOADS = "uploads.mangadex.org"
    }
}

/** Observes connectivity; [isOnline] and [isUnmetered] are kept up to date. */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(current()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { _online.value = true }
                    override fun onLost(network: Network) { _online.value = current() != null }
                },
            )
        }.onFailure { Timber.w(it, "Network callback not registered") }
    }

    private fun current(): NetworkCapabilities? = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }

    val isOnline: Boolean get() = current()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val isUnmetered: Boolean get() = current()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    @Volatile private var meteredAt = Long.MIN_VALUE / 2
    @Volatile private var meteredValue = false

    /**
     * True on mobile data or another metered network (false offline). Re-read from the system at most every
     * [METERED_TTL_MS], so it is cheap enough to check for every request.
     */
    val isMetered: Boolean
        get() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - meteredAt >= METERED_TTL_MS) {
                meteredValue = runCatching { current()?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } }
                    .getOrNull() ?: false
                meteredAt = now
            }
            return meteredValue
        }

    private companion object {
        const val METERED_TTL_MS = 2_000L
    }
}
