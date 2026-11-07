package app.yomu.data.book

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import app.yomu.data.backup.BookFileSync
import app.yomu.domain.model.Book
import app.yomu.domain.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Page data captured by a stable-folder reading session. */
data class FolderPageProof(val path: String, val sha256: String, val size: Long)

/** Pages of an image based publication. Pages are materialised as files so that large images can be subsampled. */
interface PageSource : Closeable {
    val count: Int
    fun name(index: Int): String
    suspend fun file(index: Int): File
}

class ArchivePageSource(
    private val container: ResourceContainer,
    private val pages: List<String>,
    private val cacheDir: File,
    private val folderProofs: List<FolderPageProof>? = null,
) : PageSource {
    private val lock = Mutex()
    override val count: Int get() = pages.size
    override fun name(index: Int): String = pages[index]

    override suspend fun file(index: Int): File = withContext(Dispatchers.IO) {
        val name = pages[index]
        val ext = name.substringAfterLast('.', "img").lowercase().take(5)
        val out = File(cacheDir, "%05d.%s".format(index, ext))
        if (out.exists() && out.length() > 0) return@withContext out
        lock.withLock {
            if (!(out.exists() && out.length() > 0)) {
                cacheDir.mkdirs()
                val tmp = File(cacheDir, "$index.tmp")
                try {
                    (container.open(name) ?: error("Missing page $name")).use { input ->
                        tmp.outputStream().use { output ->
                            val proof = folderProofs?.get(index)
                            if (proof == null) input.copyTo(output) else BookFileSync.copyVerified(input, output, proof.sha256, proof.size)
                        }
                    }
                    if (!tmp.renameTo(out)) throw java.io.IOException("Cannot save cached page: $name")
                } finally { tmp.delete() }
            }
        }
        out
    }

    override fun close() = container.close()
}

class PdfPageSource(private val pdf: PdfSource, private val width: Int, private val cacheDir: File, private val crop: RectF? = null) : PageSource {
    override val count: Int get() = pdf.pageCount
    override fun name(index: Int): String = "${index + 1}"

    override suspend fun file(index: Int): File = withContext(Dispatchers.IO) {
        val out = File(cacheDir, "p%05d_%d.jpg".format(index, width))
        if (out.exists() && out.length() > 0) return@withContext out
        cacheDir.mkdirs()
        val bmp = pdf.render(index, width, crop)
        File(cacheDir, "$index.tmp").also { tmp ->
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            tmp.renameTo(out)
        }
        out
    }

    override fun close() = pdf.close()
}

object PageSources {
    fun cacheDirFor(context: Context, key: String): File = File(context.cacheDir, "pages/$key").also { dir ->
        // keep the cache small: only the last few publications
        dir.parentFile?.listFiles()?.filter { it != dir }?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.deleteRecursively() }
        dir.mkdirs()
        dir.setLastModified(System.currentTimeMillis())
    }

    /** Opens any image based publication (comic archives, folders, image EPUBs and PDF rendered as images). */
    fun open(context: Context, book: Book, readingUri: Uri, pdfWidth: Int = 1600, folderProofs: List<FolderPageProof>? = null): PageSource {
        val cacheKey = "book_${book.id}"
        if (book.format == BookFormat.FOLDER) {
            // Linked folders can change without their URI or book id changing. Cached pages are
            // indexed by position, so retaining them would show the wrong image after edits.
            val stale = File(context.cacheDir, "pages/$cacheKey")
            if (stale.exists() && !stale.deleteRecursively()) throw java.io.IOException("Cannot clear the image folder cache")
        }
        val dir = cacheDirFor(context, cacheKey)
        return when (book.format) {
            BookFormat.PDF -> PdfPageSource(PdfSource.open(context, readingUri), pdfWidth, dir)
            BookFormat.CBZ, BookFormat.CBR, BookFormat.CB7, BookFormat.FOLDER -> {
                val c = Containers.open(context, readingUri, book.format)
                val entries = Containers.imageEntries(c)
                if (folderProofs != null && (book.format != BookFormat.FOLDER || entries != folderProofs.map { it.path })) {
                    c.close()
                    throw java.io.IOException("Image folder changed while opening; synchronize before reading")
                }
                ArchivePageSource(c, entries, dir, folderProofs)
            }
            else -> {
                val c = Containers.open(context, readingUri, BookFormat.EPUB)
                val pkg = EpubParser.parse(c)
                ArchivePageSource(c, EpubParser.imagesInSpineOrder(c, pkg), dir)
            }
        }
    }
}
