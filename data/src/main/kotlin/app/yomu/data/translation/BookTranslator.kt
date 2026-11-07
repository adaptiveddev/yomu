package app.yomu.data.translation

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.yomu.core.common.AppError
import app.yomu.core.common.Files
import app.yomu.data.R
import app.yomu.data.book.Containers
import app.yomu.data.book.EpubBuilder
import app.yomu.data.book.EpubParser
import app.yomu.data.library.BookFiles
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.data.work.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.zip.ZipOutputStream

/**
 * Creates a translated copy of an EPUB (or converted) book. In bilingual mode the translation is inserted
 * under every paragraph (class `yomu-tr`), otherwise the paragraph text is replaced.
 */
class BookTranslator(
    private val context: Context,
    private val books: BookRepositoryImpl,
    private val translator: TranslationService,
    private val importer: ImportService,
) {
    /** A document of the book's reading order, for choosing what to translate. */
    data class Chapter(val index: Int, val title: String)

    /** The book's documents in reading order, titled from its table of contents (file name otherwise). */
    suspend fun chapters(bookId: Long): List<Chapter> = withContext(Dispatchers.IO) {
        val book = books.getBook(bookId) ?: return@withContext emptyList()
        Containers.open(context, BookFiles.readingUri(context, book), BookFiles.readingFormat(book)).use { container ->
            val pkg = EpubParser.parse(container)
            chapterTitles(pkg.spine.map { it.path }, pkg.toc)
        }
    }

    /**
     * Creates the translated copy. [only] limits the translation to these indexes of the reading order (the other
     * documents are copied unchanged); null translates everything.
     */
    suspend fun translate(
        bookId: Long,
        target: String,
        bilingual: Boolean,
        engineId: String?,
        only: Set<Int>? = null,
        progress: suspend (Float) -> Unit,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val book = books.getBook(bookId) ?: throw AppError.CorruptedFile("Book $bookId not found")
        val uri = BookFiles.readingUri(context, book)
        val format = BookFiles.readingFormat(book)
        val outDir = File(context.filesDir, "books").also { it.mkdirs() }
        var out = File(outDir, "${Files.sanitize(book.title)} [$target].epub")
        if (out.exists()) out = File(outDir, "${Files.sanitize(book.title)} [$target] ${System.currentTimeMillis()}.epub")
        Containers.open(context, uri, format).use { container ->
            val pkg = EpubParser.parse(container)
            val documents = pkg.spine.filterIndexed { i, _ -> only == null || i in only }.map { it.path }.toSet()
            val total = documents.size.coerceAtLeast(1)
            var done = 0
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                EpubBuilder.writeMimetype(zip)
                for (entry in container.entries) {
                    if (entry == "mimetype") continue
                    val bytes = container.read(entry) ?: continue
                    val data = if (entry in documents) {
                        val doc = EpubParser.parseXml(bytes)
                        translateDocument(doc, target, bilingual, engineId)
                        done++
                        progress(done.toFloat() / total)
                        doc.outerHtml().toByteArray()
                    } else if (entry == pkg.opfPath) {
                        retitle(bytes, target)
                    } else {
                        bytes
                    }
                    zip.putNextEntry(java.util.zip.ZipEntry(entry))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }
        val result = importer.importLocalFile(out, out.name)
        // The imported book keeps pointing at [out]; only remove the file when it was not added.
        if (result !is ImportOutcome.Added) out.delete()
        result
    }

    /** Translates the text blocks of one (X)HTML document in place (also used by the editor for chosen chapters). */
    suspend fun translateDocument(doc: Document, target: String, bilingual: Boolean, engineId: String?) {
        val blocks = doc.getAllElements().filter { it.isTranslatableBlock() }
        for (block in blocks) {
            val text = block.text().trim()
            if (text.length < 2 || text.none { it.isLetter() }) continue
            val translated = runCatching { translator.translate(text, null, target, engineId).translated }.getOrNull() ?: continue
            if (bilingual) {
                val tag = if (block.tagName().substringAfterLast(':').lowercase() in setOf("li", "td")) "div" else "p"
                block.after(Element(tag).addClass("yomu-tr").text(translated))
            } else {
                block.text(translated)
            }
        }
        if (bilingual && doc.getElementsByTag("style").none { it.data().contains(".yomu-tr") }) {
            doc.getElementsByTag("head").firstOrNull()?.appendElement("style")?.appendText(BILINGUAL_CSS)
        }
    }

    private fun Element.isTranslatableBlock(): Boolean {
        val name = tagName().substringAfterLast(':').lowercase()
        if (name !in BLOCKS) return false
        // Skip containers whose text belongs to nested blocks.
        return children().none { it.tagName().substringAfterLast(':').lowercase() in BLOCKS }
    }

    private fun retitle(opf: ByteArray, target: String): ByteArray {
        val doc = EpubParser.parseXml(opf)
        doc.getAllElements().firstOrNull { it.tagName().substringAfterLast(':') == "title" }?.let { it.text(it.text() + " [$target]") }
        doc.getAllElements().firstOrNull { it.tagName().substringAfterLast(':') == "identifier" }?.let { it.text(it.text() + "-$target") }
        return doc.outerHtml().toByteArray()
    }

    companion object {
        /** Titles for the documents [paths] of the reading order: the first (top-most) table of contents entry pointing at each. */
        fun chapterTitles(paths: List<String>, toc: List<app.yomu.domain.model.TocItem>): List<Chapter> {
            val titles = HashMap<Int, String>()
            toc.sortedBy { it.level }.forEach { t -> if (t.title.isNotBlank()) titles.putIfAbsent(t.chapter, t.title.trim()) }
            return paths.mapIndexed { i, path -> Chapter(i, titles[i] ?: path.substringAfterLast('/').substringBeforeLast('.')) }
        }

        private val BLOCKS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "dd", "dt", "td", "figcaption", "div")
        const val BILINGUAL_CSS = ".yomu-tr{opacity:.75;font-style:italic;margin-top:.2em;margin-bottom:.8em}"
    }
}

class BookTranslationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val translator: BookTranslator by inject()

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK, -1)
        val target = inputData.getString(KEY_TARGET) ?: return Result.failure()
        val bilingual = inputData.getBoolean(KEY_BILINGUAL, true)
        val engine = inputData.getString(KEY_ENGINE)
        val only = inputData.getIntArray(KEY_CHAPTERS)?.toSet()
        val title = applicationContext.getString(R.string.notif_translating)
        runCatching { setForeground(Notifications.foreground(applicationContext, Notifications.ID_TRANSLATION, Notifications.TASKS, title, 0f)) }
        return runCatching {
            translator.translate(bookId, target, bilingual, engine, only) { p ->
                setProgress(workDataOf(KEY_PROGRESS to p))
                runCatching { setForeground(Notifications.foreground(applicationContext, Notifications.ID_TRANSLATION, Notifications.TASKS, title, p)) }
            }
        }.fold(
            onSuccess = { outcome ->
                Notifications.notify(
                    applicationContext, Notifications.ID_TRANSLATION + 1,
                    Notifications.builder(applicationContext, Notifications.TASKS)
                        .setContentTitle(applicationContext.getString(R.string.notif_translation_done, outcome.name))
                        .setAutoCancel(true),
                )
                Result.success()
            },
            onFailure = { Result.failure(workDataOf(KEY_ERROR to (it.message ?: it.javaClass.simpleName))) },
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notifications.foreground(applicationContext, Notifications.ID_TRANSLATION, Notifications.TASKS, applicationContext.getString(R.string.notif_translating), 0f)

    companion object {
        const val KEY_BOOK = "book"
        const val KEY_TARGET = "target"
        const val KEY_BILINGUAL = "bilingual"
        const val KEY_ENGINE = "engine"
        const val KEY_CHAPTERS = "chapters"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        fun name(bookId: Long) = "translate_$bookId"

        /** Starts translating the book; [chapters] (indexes of the reading order) limits it to those documents. */
        fun start(context: Context, bookId: Long, target: String, bilingual: Boolean, engine: String?, chapters: Collection<Int>? = null): java.util.UUID {
            val data = androidx.work.Data.Builder()
                .putLong(KEY_BOOK, bookId)
                .putString(KEY_TARGET, target)
                .putBoolean(KEY_BILINGUAL, bilingual)
                .putString(KEY_ENGINE, engine)
            chapters?.let { data.putIntArray(KEY_CHAPTERS, it.sorted().toIntArray()) }
            val request = OneTimeWorkRequestBuilder<BookTranslationWorker>().setInputData(data.build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(bookId), ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        @Suppress("unused")
        fun uriOf(file: File): Uri = Uri.fromFile(file)
    }
}
