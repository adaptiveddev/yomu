package app.yomu.data.book

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.yomu.core.common.AppError
import app.yomu.domain.model.TocItem
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class PdfLink(val bounds: RectF, val page: Int?, val uri: String?)

/** One visual text line of a PDF page with its bounding box (page points, origin top-left). */
data class PdfLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
}
data class PdfTextHit(val page: Int, val start: Int, val length: Int, val snippet: String)

/** Thread safe wrapper around a pdfium document. All calls are serialised because pdfium is not re-entrant. */
class PdfSource private constructor(private val core: PdfiumCore, private val doc: PdfDocument, private val pfd: ParcelFileDescriptor) : Closeable {
    private val mutex = Mutex()
    val pageCount: Int = doc.getPageCount()

    // Filled from whichever thread a caller resumes on.
    private val sizes = ConcurrentHashMap<Int, Pair<Int, Int>>()
    private val texts = ConcurrentHashMap<Int, String>()
    private val lines = ConcurrentHashMap<Int, List<PdfLine>>()

    @Volatile private var closed = false

    /** Runs [block] on the document; fails with [IllegalStateException] once the document is closed. */
    suspend fun <T> withDoc(block: (PdfDocument) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "The PDF document is closed" }
            block(doc)
        }
    }

    /** Page size in points. */
    suspend fun pageSize(index: Int): Pair<Int, Int> = sizes[index] ?: withDoc { d ->
        d.openPage(index).use { p -> Pair(p.getPageWidthPoint().coerceAtLeast(1), p.getPageHeightPoint().coerceAtLeast(1)) }
    }.also { sizes[index] = it }

    /**
     * Renders [index] into a bitmap of [width] px. [crop] (fractions of the page) is removed from the output.
     */
    suspend fun render(index: Int, width: Int, crop: RectF? = null, night: Boolean = false, annotations: Boolean = true): Bitmap {
        val (pw, ph) = pageSize(index)
        val c = crop ?: RectF(0f, 0f, 0f, 0f)
        val visibleW = 1f - c.left - c.right
        val visibleH = 1f - c.top - c.bottom
        val fullW = (width / visibleW.coerceAtLeast(0.2f)).toInt()
        val fullH = (fullW.toFloat() * ph / pw).toInt()
        val outH = (fullH * visibleH).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), outH, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        withDoc { d ->
            d.openPage(index).use { p ->
                p.renderPageBitmap(bmp, -(fullW * c.left).toInt(), -(fullH * c.top).toInt(), fullW, fullH, annotations, false, Color.WHITE, Color.WHITE)
            }
        }
        if (night) invert(bmp)
        return bmp
    }

    suspend fun text(index: Int): String = texts[index] ?: withDoc { d ->
        d.openPage(index).use { p ->
            p.openTextPage().use { t ->
                val n = t.textPageCountChars()
                if (n <= 0) "" else t.textPageGetText(0, n).orEmpty()
            }
        }
    }.also { texts[index] = it }

    /**
     * The page's text as visual lines with their bounding boxes (page points, origin top-left).
     * Built from per-character boxes, so the reading geometry survives for reflow. Empty for
     * scanned pages without a text layer.
     */
    suspend fun lines(index: Int): List<PdfLine> = lines[index] ?: withDoc { d ->
        d.openPage(index).use { p ->
            val h = p.getPageHeightPoint().toFloat()
            p.openTextPage().use { t ->
                val n = t.textPageCountChars()
                if (n <= 0) return@withDoc emptyList<PdfLine>()
                val text = t.textPageGetText(0, n).orEmpty()
                val out = mutableListOf<PdfLine>()
                val sb = StringBuilder()
                var left = Float.MAX_VALUE; var right = -Float.MAX_VALUE
                var top = Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
                var baseline = Float.MAX_VALUE
                fun close() {
                    if (sb.isNotEmpty()) {
                        out += PdfLine(sb.toString(), left, top, right, bottom)
                        sb.clear()
                        left = Float.MAX_VALUE; right = -Float.MAX_VALUE
                        top = Float.MAX_VALUE; bottom = -Float.MAX_VALUE
                        baseline = Float.MAX_VALUE
                    }
                }
                for (i in 0 until n) {
                    val c = text.getOrNull(i) ?: break
                    if (c == '\r' || c == '\n' || c == '\t') { close(); continue }
                    val box = t.textPageGetCharBox(i) ?: continue
                    // The native box is in PDF user space (origin bottom-left, bottom < top).
                    val charBottom = h - box.bottom
                    val charTop = h - box.top
                    val charHeight = charBottom - charTop
                    // A baseline jump beyond most of the character height starts a new line.
                    if (sb.isNotEmpty() && kotlin.math.abs(charBottom - baseline) > charHeight.coerceAtLeast(1f) * 0.6f) close()
                    baseline = charBottom
                    sb.append(c)
                    left = minOf(left, box.left.coerceAtMost(box.right))
                    right = maxOf(right, box.right.coerceAtLeast(box.left))
                    top = minOf(top, charTop)
                    bottom = maxOf(bottom, charBottom)
                }
                close()
                out
            }
        }
    }.also { lines[index] = it }

    /** Rectangles (in page points, origin top-left) covering the characters [start, start+length). */
    suspend fun rects(index: Int, start: Int, length: Int): List<RectF> = withDoc { d ->
        d.openPage(index).use { p ->
            val h = p.getPageHeightPoint().toFloat()
            p.openTextPage().use { t ->
                val count = t.textPageCountRects(start, length)
                (0 until count).mapNotNull { i -> t.textPageGetRect(i)?.let { r -> RectF(r.left, h - r.top, r.right, h - r.bottom) } }
            }
        }
    }

    /** Rectangles of the characters [start, start+length) in PDF user space (left, bottom, right, top), for annotations. */
    suspend fun userSpaceRects(index: Int, start: Int, length: Int): List<FloatArray> = withDoc { d ->
        d.openPage(index).use { p ->
            p.openTextPage().use { t ->
                val count = t.textPageCountRects(start, length)
                (0 until count).mapNotNull { i ->
                    t.textPageGetRect(i)?.let { r -> floatArrayOf(minOf(r.left, r.right), minOf(r.top, r.bottom), maxOf(r.left, r.right), maxOf(r.top, r.bottom)) }
                }
            }
        }
    }

    /** Character index at a position given in page points (origin top-left), or -1. */
    suspend fun charIndexAt(index: Int, x: Float, y: Float, tolerance: Float = 8f): Int = withDoc { d ->
        d.openPage(index).use { p ->
            val h = p.getPageHeightPoint().toDouble()
            p.openTextPage().use { t -> t.textPageGetCharIndexAtPos(x.toDouble(), h - y, tolerance.toDouble(), tolerance.toDouble()) }
        }
    }

    suspend fun links(index: Int): List<PdfLink> = withDoc { d ->
        d.openPage(index).use { p ->
            val h = p.getPageHeightPoint().toFloat()
            p.getPageLinks().map { l -> PdfLink(RectF(l.bounds.left, h - l.bounds.top, l.bounds.right, h - l.bounds.bottom), l.destPageIdx, l.uri) }
        }
    }

    suspend fun toc(): List<TocItem> = withDoc { d ->
        val out = mutableListOf<TocItem>()
        fun walk(list: List<PdfDocument.Bookmark>, level: Int) {
            list.forEach {
                out += TocItem(it.title.orEmpty().ifBlank { "${it.pageIdx + 1}" }, it.pageIdx.toInt(), null, level)
                walk(it.children, level + 1)
            }
        }
        walk(d.getTableOfContents(), 0)
        out
    }

    suspend fun meta(): PdfDocument.Meta = withDoc { it.getDocumentMeta() }

    suspend fun search(query: String, limit: Int = 300): List<PdfTextHit> {
        if (query.isBlank()) return emptyList()
        val out = mutableListOf<PdfTextHit>()
        for (i in 0 until pageCount) {
            val text = text(i)
            var from = 0
            while (out.size < limit) {
                val pos = text.indexOf(query, from, ignoreCase = true)
                if (pos < 0) break
                val s = (pos - 40).coerceAtLeast(0)
                val e = (pos + query.length + 40).coerceAtMost(text.length)
                out += PdfTextHit(i, pos, query.length, text.substring(s, e).replace('\n', ' '))
                from = pos + query.length
            }
            if (out.size >= limit) break
        }
        return out
    }

    /** Detects white margins of a page (fractions) by sampling a small rendering. */
    suspend fun detectMargins(index: Int): RectF {
        val bmp = render(index, 200)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        fun dark(x: Int, y: Int): Boolean {
            val c = pixels[y * w + x]
            return (Color.red(c) + Color.green(c) + Color.blue(c)) < 600
        }
        fun rowHasInk(y: Int) = (0 until w).any { dark(it, y) }
        fun colHasInk(x: Int) = (0 until h).any { dark(x, it) }
        val top = (0 until h).firstOrNull { rowHasInk(it) } ?: 0
        val bottom = (h - 1 downTo 0).firstOrNull { rowHasInk(it) } ?: (h - 1)
        val left = (0 until w).firstOrNull { colHasInk(it) } ?: 0
        val right = (w - 1 downTo 0).firstOrNull { colHasInk(it) } ?: (w - 1)
        val pad = 0.01f
        return RectF(
            (left.toFloat() / w - pad).coerceIn(0f, 0.4f),
            (top.toFloat() / h - pad).coerceIn(0f, 0.4f),
            (1f - (right + 1).toFloat() / w - pad).coerceIn(0f, 0.4f),
            (1f - (bottom + 1).toFloat() / h - pad).coerceIn(0f, 0.4f),
        )
    }

    /**
     * Closes the document; a page being rendered at this moment finishes first (closing the native document under a
     * running call would crash the whole app). Later calls fail with [IllegalStateException] instead of reaching pdfium.
     */
    override fun close() {
        if (closed) return
        closed = true
        if (mutex.tryLock()) {
            try {
                release()
            } finally {
                mutex.unlock()
            }
        } else {
            closer.launch { mutex.withLock { release() } }
        }
    }

    private fun release() {
        runCatching { doc.close() }
        runCatching { pfd.close() }
    }

    companion object {
        private val closer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile private var core: PdfiumCore? = null

        private fun core(context: Context): PdfiumCore = core ?: synchronized(this) {
            core ?: PdfiumCore(context.applicationContext).also { core = it }
        }

        /**
         * Opens [uri]; with a [limit] only the first [limit] bytes are read: the PDF as it was before the
         * annotations Yomu appended to it (the reader draws those itself, see PdfAnnotationStore).
         */
        fun open(context: Context, uri: Uri, password: String? = null, limit: Long? = null): PdfSource {
            val pfd = try {
                if (uri.scheme == "file" || uri.scheme == null) {
                    ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
                } else {
                    context.contentResolver.openFileDescriptor(uri, "r")
                } ?: throw AppError.NoPermission(uri.toString())
            } catch (e: SecurityException) {
                throw AppError.NoPermission(e.message)
            } catch (e: java.io.FileNotFoundException) {
                throw missingOrDenied(context, uri, e)
            }
            val c = core(context)
            val doc = try {
                when {
                    limit != null && password != null -> c.newDocument(LimitedSource(pfd, limit), password)
                    limit != null -> c.newDocument(LimitedSource(pfd, limit))
                    password != null -> c.newDocument(pfd, password)
                    else -> c.newDocument(pfd)
                }
            } catch (e: PdfPasswordException) {
                pfd.close()
                throw PdfPasswordRequired(e)
            } catch (e: Exception) {
                pfd.close()
                throw AppError.CorruptedFile(e.message, e)
            }
            return PdfSource(c, doc, pfd)
        }

        fun invert(bmp: Bitmap) {
            val w = bmp.width
            val h = bmp.height
            val row = IntArray(w)
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val c = row[x]
                    row[x] = (c and 0xFF000000.toInt()) or (0xFFFFFF - (c and 0xFFFFFF))
                }
                bmp.setPixels(row, 0, w, 0, y, w, 1)
            }
        }
    }
}

class PdfPasswordRequired(cause: Throwable) : Exception("Password required", cause)

/** The first [limit] bytes of the file behind [pfd], for pdfium. */
private class LimitedSource(pfd: ParcelFileDescriptor, private val limit: Long) : io.legere.pdfiumandroid.PdfiumSource {
    private val channel = java.io.FileInputStream(pfd.fileDescriptor).channel

    override val length: Long get() = minOf(limit, runCatching { channel.size() }.getOrDefault(limit))

    override fun read(position: Long, buffer: ByteArray, size: Int): Int {
        val n = minOf(size.toLong(), length - position).toInt()
        if (n <= 0) return 0
        var done = 0
        while (done < n) {
            val r = channel.read(java.nio.ByteBuffer.wrap(buffer, done, n - done), position + done)
            if (r <= 0) break
            done += r
        }
        return done
    }

    // The file descriptor belongs to the PdfSource, which closes it.
    override fun close() = Unit
}
