package app.yomu.data.catalog

import android.content.Context
import androidx.core.net.toUri
import app.yomu.data.source.SourceCalls
import app.yomu.source.CatalogSource
import app.yomu.source.HttpStatusException
import app.yomu.source.Page
import app.yomu.source.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** The server answered with something that is not an image (an error or verification page, an empty body). */
class NotAnImageException(message: String) : IOException(message)

/** Recognises images by their first bytes, so that error pages are not saved as manga pages. */
object ImageSniffer {
    const val HEAD_SIZE = 32

    /** File extension of the image in [head], or null when the bytes are not a known image format. */
    fun extension(head: ByteArray): String? {
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        fun ascii(from: Int, text: String) = text.indices.all { at(from + it) == text[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg"
            at(0) == 0x89 && ascii(1, "PNG") -> "png"
            ascii(0, "GIF8") -> "gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "webp"
            ascii(4, "ftyp") && (ascii(8, "avif") || ascii(8, "avis")) -> "avif"
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "msf1")) -> "heic"
            (at(0) == 0xFF && at(1) == 0x0A) || (ascii(4, "JXL ")) -> "jxl"
            ascii(0, "BM") -> "bmp"
            else -> null
        }
    }

    /** Whether [head] starts like a text document (HTML, XML or JSON), ignoring a BOM and white space. */
    fun looksLikeText(head: ByteArray): Boolean {
        var i = 0
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
        while (i < head.size && head[i].toInt().toChar().isWhitespace()) i++
        if (i >= head.size) return false
        return head[i].toInt().toChar() in "<{["
    }

    fun head(file: File): ByteArray = file.inputStream().use { input ->
        val buf = ByteArray(HEAD_SIZE)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        buf.copyOf(n)
    }
}

/** Downloads page images with retries and validation; shared by the online reader cache and the chapter downloader. */
object PageFetcher {
    /**
     * Saves the image of [page] to [out] and returns its file extension. Transient failures (timeouts, dropped
     * connections, 429/5xx, error pages instead of images) are retried [attempts] times with a growing pause.
     */
    suspend fun fetch(
        context: Context,
        client: OkHttpClient,
        source: CatalogSource,
        page: Page,
        out: File,
        attempts: Int = 3,
    ): String = withContext(Dispatchers.IO) {
        var resolved: String? = null
        SourceCalls.retrying(attempts, baseDelayMs = 800) { attempt ->
            // Addresses resolved from the page (often signed and short-lived) are asked again after a failure.
            val url = page.imageUrl ?: resolved?.takeIf { attempt == 0 } ?: source.getImageUrl(page).also { resolved = it }
            fetchOnce(context, client, source, page, url, out)
        }
    }

    private suspend fun fetchOnce(context: Context, client: OkHttpClient, source: CatalogSource, page: Page, url: String, out: File): String {
        val tmp = File(out.parentFile, out.name + ".part")
        try {
            if (url.startsWith("content://") || url.startsWith("file://")) {
                context.contentResolver.openInputStream(url.toUri())?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    ?: throw IOException("Cannot open $url")
                return finish(tmp, out, null)
            }
            // Extensions fetch images with their own client (cookies, signed URLs, descrambling interceptors).
            val response = source.fetchImage(page, url) ?: run {
                val req = Request.Builder().url(url).apply { source.imageHeaders().forEach { (k, v) -> header(k, v) } }.build()
                client.newCall(req).await()
            }
            return response.use { r ->
                if (!r.isSuccessful) throw HttpStatusException(r.code, url)
                tmp.outputStream().use { r.body.byteStream().copyTo(it) }
                finish(tmp, out, r.body.contentType()?.let { "${it.type}/${it.subtype}" })
            }
        } finally {
            tmp.delete()
        }
    }

    private fun finish(tmp: File, out: File, contentType: String?): String {
        if (tmp.length() == 0L) throw NotAnImageException("Empty image")
        val head = ImageSniffer.head(tmp)
        val ext = ImageSniffer.extension(head)
            ?: if (ImageSniffer.looksLikeText(head) || contentType?.startsWith("text/") == true || contentType?.contains("json") == true) {
                throw NotAnImageException("Not an image (${contentType ?: "text"})")
            } else {
                when (contentType?.substringAfter('/')) { "png" -> "png"; "webp" -> "webp"; "gif" -> "gif"; "avif" -> "avif"; else -> "jpg" }
            }
        out.delete()
        if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
        return ext
    }
}
