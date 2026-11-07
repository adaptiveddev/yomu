package app.yomu.feature.reader.epub

import app.yomu.data.book.EpubPackage
import app.yomu.data.book.EpubParser
import app.yomu.data.book.ResourceContainer
import app.yomu.data.book.Xhtml
import app.yomu.domain.model.TocItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.Closeable

/** A reflowable document split into chapters, rendered by the WebView engine. */
interface TextDocument : Closeable {
    val title: String
    val language: String?
    val rtl: Boolean
    val chapterCount: Int
    val toc: List<TocItem>
    fun isFixedLayout(index: Int): Boolean = false
    fun isImageChapter(index: Int): Boolean = false

    /** Path of chapter [index] relative to the virtual host (used in URLs). */
    fun chapterPath(index: Int): String
    fun chapterIndexOf(path: String): Int
    fun chapterTitle(index: Int): String?

    /** Relative weight of a chapter (used to compute the progression in the whole book). */
    fun weight(index: Int): Long

    /** Raw (X)HTML of chapter [index]. */
    suspend fun chapterHtml(index: Int): String

    /** Bytes of a resource (image, CSS, font) by path, or null. */
    fun resource(path: String): ByteArray?

    fun totalProgression(chapter: Int, progression: Float): Float {
        if (chapterCount == 0) return 0f
        val weights = (0 until chapterCount).map { weight(it).coerceAtLeast(1) }
        val total = weights.sum().toFloat()
        val before = weights.take(chapter.coerceIn(0, chapterCount)).sum()
        val current = weights.getOrElse(chapter) { 0 }
        return ((before + current * progression.coerceIn(0f, 1f)) / total).coerceIn(0f, 1f)
    }

    /** Inverse of [totalProgression]: chapter and progression inside it. */
    fun locate(total: Float): Pair<Int, Float> {
        if (chapterCount == 0) return 0 to 0f
        val weights = (0 until chapterCount).map { weight(it).coerceAtLeast(1) }
        val target = weights.sum() * total.coerceIn(0f, 1f)
        var acc = 0L
        weights.forEachIndexed { i, w ->
            if (acc + w >= target) return i to ((target - acc) / w).toFloat().coerceIn(0f, 1f)
            acc += w
        }
        return chapterCount - 1 to 1f
    }
}

/** Text of a chapter as the reader runtime sees it (concatenated text nodes of the body). */
object ChapterText {
    fun of(html: String): String {
        val doc = Jsoup.parse(html)
        val sb = StringBuilder()
        fun walk(e: Element) {
            for (n in e.childNodes()) {
                when (n) {
                    is TextNode -> sb.append(n.wholeText)
                    is Element -> if (n.tagName().lowercase() !in setOf("script", "style", "noscript")) walk(n)
                }
            }
        }
        doc.body().let(::walk)
        return sb.toString()
    }
}

class EpubTextDocument(private val container: ResourceContainer, val pkg: EpubPackage) : TextDocument {
    override val title: String get() = pkg.meta.title
    override val language: String? get() = pkg.meta.language
    override val rtl: Boolean get() = pkg.rtl
    override val chapterCount: Int get() = pkg.spine.size
    override val toc: List<TocItem> = pkg.toc.ifEmpty {
        pkg.spine.mapIndexed { i, item -> TocItem(item.path.substringAfterLast('/').substringBeforeLast('.'), i) }
    }
    private val weights = pkg.spine.map { container.size(it.path).coerceAtLeast(1) }
    private val titles: Map<Int, String> = pkg.toc.groupBy { it.chapter }.mapValues { it.value.first().title }

    override fun isFixedLayout(index: Int): Boolean = pkg.spine.getOrNull(index)?.layoutOverride ?: pkg.fixedLayout
    override fun isImageChapter(index: Int): Boolean = pkg.spine.getOrNull(index)?.isImage == true

    override fun chapterPath(index: Int): String = pkg.spine[index.coerceIn(0, pkg.spine.lastIndex)].path
    override fun chapterIndexOf(path: String): Int = pkg.spineIndexOf(path)
    override fun chapterTitle(index: Int): String? = titles[index] ?: titles.filterKeys { it <= index }.maxByOrNull { it.key }?.value
    override fun weight(index: Int): Long = weights.getOrElse(index) { 1 }

    override suspend fun chapterHtml(index: Int): String = withContext(Dispatchers.IO) {
        val bytes = container.read(chapterPath(index)) ?: return@withContext Xhtml.page("", "", language)
        val item = pkg.spine[index.coerceIn(0, pkg.spine.lastIndex)]
        if (item.isImage) {
            val src = item.path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            """<html><head><meta name="viewport" content="width=1024,height=768"/></head><body style="margin:0;width:1024px;height:768px"><img src="/$src" style="width:100%;height:100%;object-fit:contain"/></body></html>"""
        } else String(bytes, Charsets.UTF_8)
    }

    override fun resource(path: String): ByteArray? = container.read(path)

    override fun close() = container.close()

    companion object {
        fun open(container: ResourceContainer): EpubTextDocument = EpubTextDocument(container, EpubParser.parse(container))
    }
}
