package app.yomu.source

import kotlinx.serialization.Serializable

/** Version of the extension contract. Extensions declare it in their manifest (yomu.extension.api). */
const val EXTENSION_API_VERSION = 1

enum class SourceContent { MANGA, NOVEL, BOOK }

object Status {
    const val UNKNOWN = 0
    const val ONGOING = 1
    const val COMPLETED = 2
    const val HIATUS = 3
    const val CANCELLED = 4
}

/** Downloadable file of a book (OPDS acquisition link). */
@Serializable
data class SBookLink(val url: String, val mimeType: String, val title: String? = null, val size: Long? = null) {
    /**
     * Audio to listen to online instead of downloading: a feed of the chapters ([MIME_AUDIO_FEED]) or one address
     * ([MIME_AUDIO_STREAM]). The app adds such a book to the library as an audiobook and opens the player.
     */
    val isAudioStream: Boolean
        get() = mimeType.substringBefore(';').trim().lowercase().let { it == MIME_AUDIO_FEED || it == MIME_AUDIO_STREAM }

    val formatLabel: String
        get() = when {
            "epub" in mimeType -> "EPUB"
            "pdf" in mimeType -> "PDF"
            "fb2" in mimeType || "fictionbook" in mimeType -> "FB2"
            "mobi" in mimeType || "kindle" in mimeType -> "MOBI"
            "cbz" in mimeType || "comicbook" in mimeType -> "CBZ"
            "text/plain" in mimeType -> "TXT"
            "html" in mimeType -> "HTML"
            isAudioStream -> "STREAM"
            "mpeg" in mimeType && mimeType.startsWith("audio") -> "MP3"
            "m4b" in mimeType || "audiobook" in mimeType -> "M4B"
            mimeType.startsWith("audio/") -> "AUDIO"
            else -> mimeType.substringAfter('/').substringBefore(';').uppercase()
        }
}

/** A title in a catalog: manga, web novel or book. [SManga] and [SBook] are aliases of this model. */
@Serializable
data class SItem(
    val url: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: Int = Status.UNKNOWN,
    val thumbnailUrl: String? = null,
    val type: SourceContent = SourceContent.MANGA,
    val language: String? = null,
    /** Acquisition links for books (OPDS style). */
    val links: List<SBookLink> = emptyList(),
    /** True for OPDS navigation entries that open a sub-catalog instead of a title page. */
    val isNavigation: Boolean = false,
    val initialized: Boolean = false,
    /** Opaque per-title data a source asks the app to keep (JSON object; Tachiyomi "memo"). */
    val memo: String? = null,
)

typealias SManga = SItem
typealias SBook = SItem

@Serializable
data class SChapter(
    val url: String,
    val name: String,
    val number: Float = -1f,
    val dateUpload: Long = 0,
    val scanlator: String? = null,
    /** Opaque per-chapter data a source asks the app to keep (JSON object; Tachiyomi "memo"). */
    val memo: String? = null,
)

@Serializable
data class Page(val index: Int, val url: String = "", val imageUrl: String? = null)

data class ItemsPage(val items: List<SItem>, val hasNextPage: Boolean)

class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when a site requires a browser check (Cloudflare and similar). The app opens a WebView for [url]. */
class WebViewRequiredException(val url: String) : Exception("Browser verification required for $url")

class HttpStatusException(val code: Int, val url: String) : Exception("HTTP $code for $url")

/** [SBookLink.mimeType] of a feed of audio chapters (RSS with enclosures, as podcasts and LibriVox publish). */
const val MIME_AUDIO_FEED = "application/rss+xml"

/** [SBookLink.mimeType] of one audio address to stream (any format the player knows, or a YouTube link). */
const val MIME_AUDIO_STREAM = "application/x-yomu-audio-stream"
