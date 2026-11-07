package eu.kanade.tachiyomi.network

import android.content.Context
import app.yomu.compat.tachiyomi.TachiyomiCompat
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Network stack shared by all extensions (`Injekt.get<NetworkHelper>()`).
 * Cookies live in the system [android.webkit.CookieManager], so a check solved in WebView is reused by requests.
 */
class NetworkHelper(private val context: Context) {

    val cookieJar = AndroidCookieJar()

    private fun clientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30.seconds)
        .readTimeout(30.seconds)
        .callTimeout(2.minutes)
        .cache(
            Cache(
                directory = File(context.cacheDir, "ext_network_cache"),
                maxSize = 5L * 1024 * 1024, // 5 MiB
            ),
        )
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
        .also { TachiyomiCompat.clientCustomizer?.invoke(it) }

    val client: OkHttpClient by lazy {
        clientBuilder()
            .addInterceptor(CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider))
            .build()
    }

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient get() = client

    fun defaultUserAgentProvider(): String = TachiyomiCompat.userAgent().trim()
}
