package app.yomu.feature.reader

import app.yomu.domain.model.TocItem
import app.yomu.feature.reader.epub.TextDocument

/** A one-chapter document for online novel chapters. */
class SingleHtmlDocument(override val title: String, private val html: String, override val language: String? = null) : TextDocument {
    override val rtl: Boolean = false
    override val chapterCount: Int = 1
    override val toc: List<TocItem> = listOf(TocItem(title, 0))
    override fun chapterPath(index: Int): String = "chapter.xhtml"
    override fun chapterIndexOf(path: String): Int = if (path.substringBefore('#') == "chapter.xhtml") 0 else -1
    override fun chapterTitle(index: Int): String = title
    override fun weight(index: Int): Long = html.length.toLong().coerceAtLeast(1)
    override suspend fun chapterHtml(index: Int): String =
        if (html.contains("<body", ignoreCase = true)) html else "<html><head><title>$title</title></head><body>$html</body></html>"
    override fun resource(path: String): ByteArray? = null
    override fun close() = Unit
}
