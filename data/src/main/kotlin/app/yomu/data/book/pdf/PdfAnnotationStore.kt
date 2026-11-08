package app.yomu.data.book.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.data.book.PdfSource
import app.yomu.data.repository.toDomain
import app.yomu.data.repository.toEntity
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.HighlightColor
import app.yomu.domain.model.HighlightStyle
import app.yomu.domain.model.Locator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * What Yomu appended to a PDF: the file was [base] bytes long before, [length] after; [crc] is the checksum of the
 * appended bytes. While the file still ends with them, the next update replaces them instead of adding another one,
 * and the reader opens the first [base] bytes (it draws the highlights itself).
 */
@Serializable
data class PdfAnnotationManifest(val base: Long, val length: Long, val crc: Long, val signature: String, val ids: List<String> = emptyList())

/**
 * Keeps the highlights of PDF books in the PDF files as well (the PDF setting "Save highlights in PDF files"):
 * after a change the file gets standard annotations that other PDF apps show. The database stays the source of
 * truth; annotations Yomu wrote on another device are imported when the book is opened.
 */
/** Where the characters of a page are: rectangles (left, bottom, right, top) in PDF user space. */
fun interface PdfTextGeometry {
    /** For each range (page, start, end) of the first [limit] bytes of [uri]: its rectangles, empty when unknown. */
    suspend fun rects(uri: Uri, limit: Long, ranges: List<IntArray>): List<List<FloatArray>>
}

/** The text layer as pdfium sees it. */
class PdfiumTextGeometry(private val context: Context) : PdfTextGeometry {
    override suspend fun rects(uri: Uri, limit: Long, ranges: List<IntArray>): List<List<FloatArray>> {
        val pdf = PdfSource.open(context, uri, limit = limit)
        return try {
            ranges.map { (page, start, end) ->
                if (page !in 0 until pdf.pageCount || end <= start) emptyList() else runCatching { pdf.userSpaceRects(page, start, end - start) }.getOrDefault(emptyList())
            }
        } finally {
            pdf.close()
        }
    }
}

class PdfAnnotationStore(
    private val context: Context,
    private val db: YomuDatabase,
    private val settings: AppSettings,
    private val geometry: PdfTextGeometry = PdfiumTextGeometry(context),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val dir: File get() = File(context.filesDir, "pdf-annotations").also { it.mkdirs() }

    private fun manifestFile(bookId: Long) = File(dir, "$bookId.json")

    private fun manifest(bookId: Long): PdfAnnotationManifest? =
        runCatching { json.decodeFromString(PdfAnnotationManifest.serializer(), manifestFile(bookId).readText()) }.getOrNull()

    /** Schedules a write for every PDF book whose highlights differ from what its file holds. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            combine(settings.pdfWriteAnnotations.flow, db.annotations().observeAllHighlights()) { on, list -> on to list }
                .debounce(CHANGE_DELAY_MS)
                .collect { (on, list) ->
                    if (!on) return@collect
                    runCatching { schedule(list.map { it.toDomain() }) }.onFailure { Timber.w(it, "PDF annotations") }
                }
        }
    }

    private suspend fun schedule(all: List<Highlight>) {
        val byBook = all.groupBy { it.bookId }
        val written = dir.listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".json").toLongOrNull() }
        val candidates = (byBook.keys + written).toSet()
        if (candidates.isEmpty()) return
        val pdfs = db.books().getAll(candidates.toList()).filter { it.format == "PDF" }.associateBy { it.id }
        // Books that are gone: their manifests too.
        written.filter { it !in pdfs && db.books().get(it) == null }.forEach {
            manifestFile(it).delete()
            File(dir, "$it.scanned").delete()
        }
        pdfs.keys.forEach { id ->
            val highlights = byBook[id].orEmpty()
            val m = manifest(id)
            if (m == null && highlights.none { it.isPdfMarkup() }) return@forEach
            if (m?.signature != signature(highlights, notesOf(id))) PdfAnnotationWorker.enqueue(context, id)
        }
    }

    /** The bytes of [uri] the reader opens: without Yomu's own update when the file still ends with it. */
    fun renderLimit(bookId: Long, uri: Uri): Long? {
        val m = manifest(bookId) ?: return null
        return runCatching {
            open(uri, write = false).use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.let { ch -> if (ch.size() == m.length && crc(ch, m.base, m.length) == m.crc) m.base else null }
            }
        }.getOrNull()
    }

    /**
     * Whether the file's only change since [oldIdentity] was Yomu's own annotation append: the
     * recorded base length still equals the old identity's size, the base bytes still hash to the
     * old identity's prefix and the appended bytes still match the manifest. Sync uses this to
     * adopt self-written versions automatically instead of stopping with a conflict.
     */
    internal fun isOwnAppendage(bookId: Long, uri: Uri, oldIdentity: String): Boolean {
        val m = manifest(bookId) ?: return false
        val oldSize = oldIdentity.substringAfter(':').toLongOrNull() ?: return false
        val prefix = oldIdentity.substringBefore(':')
        if (m.base != oldSize) return false
        return runCatching {
            open(uri, write = false).use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    if (ch.size() != m.length || crc(ch, m.base, m.length) != m.crc) return@use false
                    val sha = java.security.MessageDigest.getInstance("SHA-1")
                    val buf = ByteArray(64 * 1024)
                    var left = minOf(m.base, 1024L * 1024L)
                    ch.position(0)
                    while (left > 0) {
                        val n = ch.read(java.nio.ByteBuffer.wrap(buf, 0, minOf(buf.size.toLong(), left).toInt()))
                        if (n <= 0) return@use false
                        sha.update(buf, 0, n)
                        left -= n
                    }
                    sha.digest().joinToString("") { "%02x".format(it) } == prefix
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Highlights Yomu wrote into the file on another device (or before the library was restored) that the library
     * does not have; done once per book, before the first write.
     */
    suspend fun importFromFile(bookId: Long): Int = withContext(Dispatchers.IO) {
        lock(bookId).withLock {
            if (manifestFile(bookId).exists()) return@withLock 0
            val book = db.books().get(bookId)?.takeIf { it.format == "PDF" } ?: return@withLock 0
            // A file is looked through once (again only when its size changes).
            val marker = File(dir, "$bookId.scanned")
            val found = runCatching {
                open(Uri.parse(book.uri), write = false).use { pfd ->
                    val channel = FileInputStream(pfd.fileDescriptor).channel
                    val stamp = channel.size().toString()
                    if (marker.exists() && marker.readText() == stamp) {
                        emptyList()
                    } else {
                        PdfAnnotationWriter.read(ChannelPdfBytes(channel)).also { marker.writeText(stamp) }
                    }
                }
            }.onFailure { Timber.w(it, "Reading PDF annotations") }.getOrNull().orEmpty()
            if (found.isEmpty()) return@withLock 0
            val have = db.annotations().highlights(bookId).map { it.toDomain() }.map { markupId(it) }.toSet()
            var added = 0
            found.filter { it.id !in have }.forEach { f ->
                val h = f.toHighlight(bookId) ?: return@forEach
                db.annotations().insertHighlight(h.toEntity())
                added++
            }
            added
        }
    }

    /** Writes the highlights of [bookId] into its file; false when it cannot (no write access, unreadable file…). */
    suspend fun write(bookId: Long): Boolean = withContext(Dispatchers.IO) {
        lock(bookId).withLock {
            if (!settings.pdfWriteAnnotations.get()) return@withLock false
            val book = db.books().get(bookId)
            if (book == null) {
                manifestFile(bookId).delete()
                return@withLock false
            }
            if (book.format != "PDF") return@withLock false
            val highlights = db.annotations().highlights(bookId).map { it.toDomain() }
            val notes = notesOf(bookId)
            val signature = signature(highlights, notes)
            val uri = Uri.parse(book.uri)
            val pfd = runCatching { open(uri, write = true) }.onFailure { Timber.w(it, "No write access to ${book.uri}") }.getOrNull()
                ?: return@withLock false
            pfd.use {
                val input = FileInputStream(pfd.fileDescriptor).channel
                val output = FileOutputStream(pfd.fileDescriptor).channel
                val length = input.size()
                val m = manifest(bookId)
                val replace = m != null && m.length == length && m.base < length && crc(input, m.base, m.length) == m.crc
                if (replace && m.signature == signature) return@withLock true
                val base = if (replace) m.base else length
                val markups = markups(uri, base, highlights, notes)
                val inc = try {
                    PdfAnnotationWriter.increment(PdfBytesView(ChannelPdfBytes(input), base), markups)
                } catch (e: PdfFormatException) {
                    Timber.w(e, "Cannot add annotations to ${book.uri}")
                    return@withLock false
                }
                withContext(NonCancellable) {
                    if (inc == null) {
                        if (replace) output.truncate(base)
                        manifestFile(bookId).delete()
                    } else {
                        if (replace) output.truncate(base)
                        var done = 0
                        while (done < inc.size) done += output.write(ByteBuffer.wrap(inc, done, inc.size - done), base + done)
                        output.force(true)
                        val crc = CRC32().apply { update(inc) }.value
                        val manifest = PdfAnnotationManifest(base, base + inc.size, crc, signature, markups.map { it.id })
                        manifestFile(bookId).writeText(json.encodeToString(PdfAnnotationManifest.serializer(), manifest))
                    }
                }
            }
            true
        }
    }

    private suspend fun markups(uri: Uri, base: Long, highlights: List<Highlight>, notes: Map<Long, List<String>>): List<PdfMarkup> {
        val wanted = highlights.filter { it.isPdfMarkup() }
        if (wanted.isEmpty()) return emptyList()
        val rects = geometry.rects(uri, base, wanted.map { intArrayOf(it.locator.chapter, it.locator.offset ?: 0, it.locator.endOffset ?: 0) })
        return wanted.zip(rects).mapNotNull { (h, r) ->
            if (r.isEmpty()) return@mapNotNull null
            val start = h.locator.offset ?: return@mapNotNull null
            val end = h.locator.endOffset ?: return@mapNotNull null
            val kind = when (h.style) {
                HighlightStyle.HIGHLIGHT -> MarkupKind.HIGHLIGHT
                HighlightStyle.UNDERLINE -> MarkupKind.UNDERLINE
                HighlightStyle.STRIKETHROUGH -> MarkupKind.STRIKEOUT
            }
            val contents = (listOfNotNull(h.note?.takeIf { it.isNotBlank() }) + notes[h.id].orEmpty()).joinToString("\n\n").ifBlank { null }
            PdfMarkup(
                id = markupId(h),
                page = h.locator.chapter,
                kind = kind,
                rects = r,
                rgb = (h.color.argb and 0xFFFFFF).toInt(),
                opacity = if (kind == MarkupKind.HIGHLIGHT) HIGHLIGHT_OPACITY else 1f,
                contents = contents,
                created = h.createdAt,
                modified = h.updatedAt,
                extra = mapOf(
                    "Start" to PdfNumber.of(start),
                    "End" to PdfNumber.of(end),
                    "Color" to PdfName(h.color.name),
                    "Style" to PdfName(h.style.name),
                    "Created" to PdfNumber.of(h.createdAt),
                    "Text" to PdfString.text(h.text.take(MAX_TEXT)),
                ),
            )
        }
    }

    private fun PdfFoundMarkup.toHighlight(bookId: Long): Highlight? {
        val x = extra ?: return null
        val start = (x["Start"] as? PdfNumber)?.int ?: return null
        val end = (x["End"] as? PdfNumber)?.int ?: return null
        val created = (x["Created"] as? PdfNumber)?.long ?: System.currentTimeMillis()
        val text = (x["Text"] as? PdfString)?.text().orEmpty()
        val color = HighlightColor.entries.firstOrNull { it.name == (x["Color"] as? PdfName)?.name } ?: HighlightColor.YELLOW
        val style = HighlightStyle.entries.firstOrNull { it.name == (x["Style"] as? PdfName)?.name } ?: HighlightStyle.HIGHLIGHT
        return Highlight(
            bookId = bookId,
            locator = Locator(chapter = page, offset = start, endOffset = end, text = text.take(MAX_TEXT)),
            text = text,
            color = color,
            style = style,
            note = contents,
            createdAt = created,
        )
    }

    private suspend fun notesOf(bookId: Long): Map<Long, List<String>> =
        db.annotations().notes(bookId).filter { it.highlightId != null && it.text.isNotBlank() }.groupBy({ it.highlightId!! }, { it.text })

    private fun lock(bookId: Long) = locks.getOrPut(bookId) { Mutex() }

    private fun open(uri: Uri, write: Boolean): ParcelFileDescriptor =
        if (uri.scheme == "file" || uri.scheme == null) {
            ParcelFileDescriptor.open(File(uri.path!!), if (write) ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            context.contentResolver.openFileDescriptor(uri, if (write) "rw" else "r") ?: throw java.io.FileNotFoundException(uri.toString())
        }

    companion object {
        private const val CHANGE_DELAY_MS = 3_000L
        private const val HIGHLIGHT_OPACITY = 0.45f
        private const val MAX_TEXT = 500

        /** The /NM of a highlight in the file: the same on every device the library is synced to. */
        fun markupId(h: Highlight): String = PdfAnnotationWriter.ID_PREFIX + "${h.createdAt}-${h.locator.chapter}-${h.locator.offset ?: 0}"

        private fun Highlight.isPdfMarkup(): Boolean = locator.offset != null && (locator.endOffset ?: 0) > (locator.offset ?: 0)

        /** What the file should hold for [highlights]: when it changes, the file is written again. */
        fun signature(highlights: List<Highlight>, notes: Map<Long, List<String>>): String {
            val md = MessageDigest.getInstance("SHA-1")
            highlights.filter { it.isPdfMarkup() }.sortedWith(compareBy({ it.createdAt }, { it.id })).forEach { h ->
                md.update("${markupId(h)}|${h.locator.endOffset}|${h.color}|${h.style}|${h.note.orEmpty()}|${notes[h.id].orEmpty().joinToString("\u0001")}\n".toByteArray())
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun crc(ch: FileChannel, from: Long, to: Long): Long {
            val crc = CRC32()
            val buf = ByteBuffer.allocate(1 shl 16)
            var pos = from
            while (pos < to) {
                buf.clear()
                buf.limit(minOf(buf.capacity().toLong(), to - pos).toInt())
                val n = ch.read(buf, pos)
                if (n <= 0) break
                crc.update(buf.array(), 0, n)
                pos += n
            }
            return crc.value
        }
    }
}

/** A file channel as [PdfBytes] (positional reads, the channel position is not used). */
class ChannelPdfBytes(private val channel: FileChannel) : PdfBytes {
    override val length: Long get() = channel.size()
    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = channel.read(ByteBuffer.wrap(buffer, offset, len), position)
}

/** Writes the highlights of one PDF book into its file, a moment after they changed. */
class PdfAnnotationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val store: PdfAnnotationStore by inject()

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_BOOK, -1)
        if (id > 0) runCatching { store.write(id) }.onFailure { Timber.w(it, "Writing PDF annotations of $id") }
        return Result.success()
    }

    companion object {
        private const val KEY_BOOK = "book"
        private const val DELAY_S = 2L

        fun enqueue(context: Context, bookId: Long) {
            val request = OneTimeWorkRequestBuilder<PdfAnnotationWorker>()
                .setInitialDelay(DELAY_S, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_BOOK to bookId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("pdf_annotations_$bookId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
