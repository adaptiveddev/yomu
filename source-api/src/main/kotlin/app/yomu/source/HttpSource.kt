package app.yomu.source

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Network shared by the host app with all sources, including external extensions. */
object SourceNetwork {
    @Volatile
    var client: OkHttpClient = OkHttpClient()

    /** Host app version, used by sources that must identify themselves (API clients). */
    @Volatile
    var appVersion: String = "1.1"

    @Volatile
    var userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Base class for HTTP based sources. */
abstract class HttpSource : CatalogSource {
    abstract val baseUrl: String
    override val homeUrl: String? get() = baseUrl
    open val versionId: Int = 1
    override val id: Long by lazy { SourceIds.generate(name, lang, versionId) }

    open val client: OkHttpClient get() = SourceNetwork.client
    protected val json: Json get() = SourceNetwork.json

    open fun headersBuilder(): Headers.Builder = Headers.Builder().add("User-Agent", SourceNetwork.userAgent)
    val headers: Headers by lazy { headersBuilder().build() }

    protected suspend fun get(url: String, headers: Headers = this.headers): Response {
        val response = client.newCall(Request.Builder().url(url).headers(headers).build()).await()
        if (!response.isSuccessful) {
            val code = response.code
            val server = response.header("Server").orEmpty()
            response.close()
            if ((code == 403 || code == 503) && server.contains("cloudflare", ignoreCase = true)) throw WebViewRequiredException(url)
            throw HttpStatusException(code, url)
        }
        return response
    }

    protected suspend fun getText(url: String, headers: Headers = this.headers): String = get(url, headers).use { it.body.string() }

    /**
     * Like [getText] for JSON APIs: an HTML answer (anti-bot or maintenance page) becomes a clear
     * [WebViewRequiredException] instead of a cryptic JSON parsing error.
     */
    protected suspend fun getJsonText(url: String, headers: Headers = this.headers): String = get(url, headers).use { r ->
        val type = r.header("Content-Type").orEmpty()
        val body = r.body.string()
        if (type.contains("html", ignoreCase = true) || body.trimStart().startsWith("<")) {
            throw WebViewRequiredException(r.request.url.toString())
        }
        body
    }

    override fun toString(): String = "$name ($lang)"
}
