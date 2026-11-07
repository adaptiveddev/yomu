package app.yomu.feature.editor

import org.jsoup.nodes.Element

/** One entry of the table of contents (flat list with nesting levels). */
data class TocEntry(val title: String, val path: String, val fragment: String? = null, val level: Int = 0)

/** A spine document as shown in the chapter list. */
data class EditorChapter(val index: Int, val path: String, val title: String, val size: Long)

data class ManifestImage(val path: String, val mediaType: String, val size: Long, val isCover: Boolean)

/** A top level block of a chapter, offered as split point. */
data class BlockInfo(val index: Int, val tag: String, val preview: String, val heading: Boolean)

data class SearchQuery(
    val text: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    /** Search the raw XHTML source instead of the visible text only. */
    val inMarkup: Boolean = false,
)

data class EditorSearchHit(val path: String, val count: Int, val previews: List<String>)

data class CleanupOptions(
    val emptyParagraphs: Boolean = true,
    val spaces: Boolean = true,
    val dashes: Boolean = true,
    val quotes: Boolean = false,
    val hyphenation: Boolean = false,
)

data class StyleCleanup(val fonts: Boolean = true, val colors: Boolean = true, val inlineStyles: Boolean = false)

enum class IssueCode(val error: Boolean, val fixable: Boolean) {
    NO_CONTAINER(true, true),
    NO_TITLE(true, true),
    NO_LANGUAGE(true, true),
    NO_IDENTIFIER(true, true),
    DUPLICATE_ID(true, true),
    MISSING_FILE(true, true),
    UNLISTED_FILE(false, true),
    JUNK_FILE(false, true),
    NO_NAV(true, true),
    TOC_BROKEN(true, true),
    NOT_WELL_FORMED(true, true),
    BROKEN_LINK(true, true),
    BROKEN_ANCHOR(false, true),
    MISSING_RESOURCE(true, true),
    EMPTY_CHAPTER(false, false),
    NO_COVER(false, false),
}

data class Issue(val code: IssueCode, val path: String? = null, val detail: String? = null)

internal fun Element.local(): String = tagName().substringAfterLast(':').lowercase()
internal fun Element.firstByLocal(name: String): Element? = getAllElements().firstOrNull { it.local() == name }
internal fun Element.allByLocal(name: String): List<Element> = getAllElements().filter { it.local() == name }
internal fun Element.childrenByLocal(name: String): List<Element> = children().filter { it.local() == name }

internal object MediaTypes {
    fun of(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "xhtml", "xht", "html", "htm" -> "application/xhtml+xml"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ncx" -> "application/x-dtbncx+xml"
        "js" -> "application/javascript"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "smil" -> "application/smil+xml"
        "xml" -> "application/xml"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    fun extensionOf(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        else -> "jpg"
    }

    fun isBinary(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in BINARY

    fun isFont(name: String, mediaType: String = ""): Boolean =
        mediaType.contains("font") || mediaType.contains("opentype") || name.substringAfterLast('.', "").lowercase() in setOf("ttf", "otf", "woff", "woff2")

    private val BINARY = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "ttf", "otf", "woff", "woff2", "mp3", "mp4", "m4a", "ogg", "wav", "zip", "pdf")
}
