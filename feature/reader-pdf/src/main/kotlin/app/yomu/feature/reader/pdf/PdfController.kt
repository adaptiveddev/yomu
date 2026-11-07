package app.yomu.feature.reader.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.yomu.data.book.PdfLink
import app.yomu.data.book.PdfSource
import app.yomu.data.book.PdfTextHit
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.PdfSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Text selected on a PDF page; [rects] are in page points (origin top-left). */
data class PdfSelection(val page: Int, val start: Int, val end: Int, val text: String, val rects: List<RectF>)

/** State shared by the PDF views: rendering cache, selection, search hits, highlights and navigation requests. */
class PdfController(val pdf: PdfSource) {
    val pageCount: Int get() = pdf.pageCount
    var page by mutableIntStateOf(0)
    var settings by mutableStateOf(PdfSettings())
    var selection by mutableStateOf<PdfSelection?>(null)
    var highlights by mutableStateOf<List<Highlight>>(emptyList())
    var hits by mutableStateOf<List<PdfTextHit>>(emptyList())
    var currentHit by mutableStateOf<PdfTextHit?>(null)
    var ttsMark by mutableStateOf<Triple<Int, Int, Int>?>(null)

    private val requests = Channel<Int>(Channel.CONFLATED)
    val pageRequests: Flow<Int> = requests.receiveAsFlow()

    private val maxBytes = (Runtime.getRuntime().maxMemory() / 6).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val margins = HashMap<Int, RectF>()
    private val linkCache = HashMap<Int, List<PdfLink>>()
    private val renderLock = Mutex()

    fun goTo(index: Int) {
        requests.trySend(index.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    }

    /** Crop applied to [index]: manual margins or detected ones in auto mode. */
    suspend fun crop(index: Int): RectF {
        val s = settings
        if (s.autoCrop) return margins[index] ?: runCatching { pdf.detectMargins(index) }.getOrDefault(RectF()).also { margins[index] = it }
        val m = s.manualCrop
        return RectF(m.left, m.top, m.right, m.bottom)
    }

    /**
     * Size of [index] in points. A page the PDF library cannot read (a damaged file, or the document already closed
     * while the reader is leaving) counts as A4 instead of failing the screen that asked.
     */
    private suspend fun pageSize(index: Int): Pair<Int, Int> =
        runCatching { pdf.pageSize(index) }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 } ?: (A4_WIDTH to A4_HEIGHT)

    /** Aspect ratio (height / width) of the visible part of [index]. */
    suspend fun ratio(index: Int): Float {
        val (w, h) = pageSize(index)
        val c = crop(index)
        val vw = (1f - c.left - c.right).coerceAtLeast(0.2f)
        val vh = (1f - c.top - c.bottom).coerceAtLeast(0.2f)
        return (h * vh) / (w * vw)
    }

    suspend fun bitmap(index: Int, width: Int): Bitmap {
        val night = settings.night
        val c = crop(index)
        val key = "$index:$width:$night:${c.left},${c.top},${c.right},${c.bottom}"
        cache.get(key)?.let { return it }
        return renderLock.withLock {
            cache.get(key) ?: pdf.render(index, width.coerceIn(64, 4096), c, night).also { cache.put(key, it) }
        }
    }

    suspend fun links(index: Int): List<PdfLink> = linkCache[index] ?: runCatching { pdf.links(index) }.getOrDefault(emptyList()).also { linkCache[index] = it }

    fun clearCache() {
        cache.evictAll()
        margins.clear()
    }

    /** Converts a point inside the rendered (cropped) page of size [w]x[h] px to page points. */
    suspend fun toPage(index: Int, x: Float, y: Float, w: Float, h: Float): Pair<Float, Float> {
        val (pw, ph) = pageSize(index)
        val c = crop(index)
        val vw = 1f - c.left - c.right
        val vh = 1f - c.top - c.bottom
        return Pair((c.left + x / w * vw) * pw, (c.top + y / h * vh) * ph)
    }

    /** Converts a rectangle in page points to px of the rendered page. */
    suspend fun toView(index: Int, r: RectF, w: Float, h: Float): RectF {
        val (pw, ph) = pageSize(index)
        val c = crop(index)
        val vw = 1f - c.left - c.right
        val vh = 1f - c.top - c.bottom
        fun fx(v: Float) = ((v / pw) - c.left) / vw * w
        fun fy(v: Float) = ((v / ph) - c.top) / vh * h
        return RectF(fx(r.left), fy(r.top), fx(r.right), fy(r.bottom))
    }

    /** Selects the word around [charIndex]. */
    suspend fun selectWord(index: Int, charIndex: Int): PdfSelection? {
        val text = pdf.text(index)
        if (charIndex !in text.indices) return null
        if (!text[charIndex].isLetterOrDigit()) return null
        var s = charIndex
        var e = charIndex + 1
        while (s > 0 && text[s - 1].isLetterOrDigit()) s--
        while (e < text.length && text[e].isLetterOrDigit()) e++
        return select(index, s, e)
    }

    suspend fun select(index: Int, start: Int, end: Int): PdfSelection? {
        val text = pdf.text(index)
        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        if (b <= a) return null
        val rects = runCatching { pdf.rects(index, a, b - a) }.getOrDefault(emptyList())
        return PdfSelection(index, a, b, text.substring(a, b), rects).also { selection = it }
    }

    /** Expands the current selection to the sentence or paragraph around it. */
    suspend fun expand(paragraph: Boolean) {
        val sel = selection ?: return
        val text = pdf.text(sel.page)
        var s = sel.start
        var e = sel.end
        if (paragraph) {
            while (s > 0 && !(text[s - 1] == '\n' && s >= 2 && text[s - 2] == '\n') && !(text[s - 1] == '\n' && s >= 2 && text[s - 2] == '\r')) s--
            while (e < text.length && !(text[e] == '\n' && e + 1 < text.length && (text[e + 1] == '\n' || text[e + 1] == '\r'))) e++
        } else {
            while (s > 0 && text[s - 1] !in ".!?。！？") s--
            while (e < text.length && text[e - 1] !in ".!?。！？") e++
        }
        while (s < e && text[s].isWhitespace()) s++
        select(sel.page, s, e)
    }

    fun close() {
        cache.evictAll()
        pdf.close()
    }

    private companion object {
        const val A4_WIDTH = 595
        const val A4_HEIGHT = 842
    }
}
