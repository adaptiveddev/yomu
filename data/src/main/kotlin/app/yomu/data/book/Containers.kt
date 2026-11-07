package app.yomu.data.book

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import app.yomu.core.common.AppError
import app.yomu.core.common.Files
import app.yomu.data.backup.FolderSnapshot
import app.yomu.data.format.FormatDetector
import app.yomu.data.format.NaturalOrder
import app.yomu.domain.model.BookFormat
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Read only access to the files of a book: a ZIP based EPUB/CBZ, a RAR or 7z archive, or a folder. */
interface ResourceContainer : Closeable {
    val entries: List<String>
    fun open(path: String): InputStream?
    fun read(path: String): ByteArray? = open(path)?.use { it.readBytes() }
    fun exists(path: String): Boolean = path in entrySet
    val entrySet: Set<String>
    fun size(path: String): Long = -1
}

class ZipContainer private constructor(private val zip: ZipFile, private val extra: Closeable?) : ResourceContainer {
    private val map: Map<String, ZipArchiveEntry> = zip.entries.toList().filter { !it.isDirectory }.associateBy { it.name }
    private val lowerMap: Map<String, ZipArchiveEntry> = map.mapKeys { it.key.lowercase() }
    override val entries: List<String> = map.keys.toList()
    override val entrySet: Set<String> = map.keys

    private fun entry(path: String) = map[path] ?: map[path.removePrefix("/")] ?: lowerMap[path.lowercase().removePrefix("/")]

    override fun open(path: String): InputStream? = entry(path)?.let { zip.getInputStream(it) }
    override fun exists(path: String): Boolean = entry(path) != null
    override fun size(path: String): Long = entry(path)?.size ?: -1

    override fun close() {
        runCatching { zip.close() }
        runCatching { extra?.close() }
    }

    companion object {
        fun open(file: File): ZipContainer = ZipContainer(ZipFile.builder().setFile(file).get(), null)

        fun open(pfd: ParcelFileDescriptor): ZipContainer {
            val stream = FileInputStream(pfd.fileDescriptor)
            val zip = ZipFile.builder().setSeekableByteChannel(stream.channel).get()
            return ZipContainer(zip) { stream.close(); pfd.close() }
        }
    }
}

class RarContainer(file: File, private val deleteOnClose: Boolean) : ResourceContainer {
    private val file = file
    private val archive = Archive(file)
    private val headers: Map<String, FileHeader> = archive.fileHeaders.filter { !it.isDirectory }.associateBy { it.fileName.replace('\\', '/') }
    override val entries: List<String> = headers.keys.toList()
    override val entrySet: Set<String> = headers.keys

    override fun open(path: String): InputStream? {
        val h = headers[path] ?: return null
        val bytes = synchronized(archive) { java.io.ByteArrayOutputStream().also { archive.extractFile(h, it) }.toByteArray() }
        return ByteArrayInputStream(bytes)
    }

    override fun size(path: String): Long = headers[path]?.fullUnpackSize ?: -1

    override fun close() {
        runCatching { archive.close() }
        if (deleteOnClose) file.delete()
    }
}

class SevenZContainer private constructor(private val sevenZ: SevenZFile, private val extra: Closeable?) : ResourceContainer {
    private val map: Map<String, SevenZArchiveEntry> = sevenZ.entries.filter { !it.isDirectory }.associateBy { it.name }
    override val entries: List<String> = map.keys.toList()
    override val entrySet: Set<String> = map.keys

    override fun open(path: String): InputStream? {
        val e = map[path] ?: return null
        val bytes = synchronized(sevenZ) { sevenZ.getInputStream(e).use { it.readBytes() } }
        return ByteArrayInputStream(bytes)
    }

    override fun size(path: String): Long = map[path]?.size ?: -1

    override fun close() {
        runCatching { sevenZ.close() }
        runCatching { extra?.close() }
    }

    companion object {
        fun open(pfd: ParcelFileDescriptor): SevenZContainer {
            val stream = FileInputStream(pfd.fileDescriptor)
            return SevenZContainer(SevenZFile.builder().setSeekableByteChannel(stream.channel).get()) { stream.close(); pfd.close() }
        }

        fun open(file: File): SevenZContainer = SevenZContainer(SevenZFile.builder().setFile(file).get(), null)
    }
}

/** A plain directory on the file system. */
class DirContainer(private val root: File) : ResourceContainer {
    override val entries: List<String> = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace('\\', '/') }.toList()
    override val entrySet: Set<String> = entries.toSet()
    override fun open(path: String): InputStream? = File(root, path).takeIf { it.isFile }?.inputStream()
    override fun exists(path: String): Boolean = File(root, path).isFile
    override fun size(path: String): Long = File(root, path).length()
    override fun close() = Unit
}

/** A folder picked through the Storage Access Framework (recursive, images only by default). */
class DocumentTreeContainer(private val context: Context, treeUri: Uri) : ResourceContainer {
    // Reuse the same strict traversal as file sync. In particular, a URI can select a
    // document *inside* a tree: fromTreeUri alone would open the tree root instead and
    // silently give reading positions a different page order from the sync manifest.
    private val files = FolderSnapshot.enumerate(context, treeUri).associate { it.path to it.uri }

    override val entries: List<String> = files.keys.toList()
    override val entrySet: Set<String> = files.keys
    override fun open(path: String): InputStream? = files[path]?.let { context.contentResolver.openInputStream(it) }
    override fun close() = Unit
}

/**
 * A [java.io.FileNotFoundException] means either that the document is gone or (for SAF links) that access was lost.
 * Tell them apart so the UI can offer the right fix (re-download/relink vs. grant access again).
 */
fun missingOrDenied(context: Context, uri: Uri, e: Exception): AppError {
    if (uri.scheme == "file" || uri.scheme == null) return AppError.FileMissing(uri.path)
    val exists = runCatching { DocumentFile.fromSingleUri(context, uri)?.exists() == true }.getOrDefault(false)
    val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    return if (granted && !exists) AppError.FileMissing(uri.toString()) else AppError.NoPermission(e.message)
}

object Containers {
    /** Opens [uri] as a container. RAR archives from content URIs are copied to the cache because junrar needs a file. */
    fun open(context: Context, uri: Uri, format: BookFormat): ResourceContainer {
        try {
            if (format == BookFormat.FOLDER) {
                return if (uri.scheme == "file") DirContainer(File(uri.path!!)) else DocumentTreeContainer(context, uri)
            }
            val file = if (uri.scheme == "file" || uri.scheme == null) File(uri.path!!) else null
            // Commons Compress reports a missing file as NoSuchFileException, which would look like a corrupted one.
            if (file != null && !file.exists()) throw AppError.FileMissing(file.path)
            return when (format) {
                BookFormat.CBR -> if (file != null) {
                    RarContainer(file, deleteOnClose = false)
                } else {
                    val tmp = File(context.cacheDir, "rar/${System.nanoTime()}.rar")
                    RarContainer(Files.copy(context, uri, tmp), deleteOnClose = true)
                }
                BookFormat.CB7 -> if (file != null) SevenZContainer.open(file) else SevenZContainer.open(pfd(context, uri))
                else -> if (file != null) ZipContainer.open(file) else ZipContainer.open(pfd(context, uri))
            }
        } catch (e: AppError) {
            throw e
        } catch (e: SecurityException) {
            throw AppError.NoPermission(e.message)
        } catch (e: java.io.FileNotFoundException) {
            throw missingOrDenied(context, uri, e)
        } catch (e: java.nio.file.NoSuchFileException) {
            throw missingOrDenied(context, uri, e)
        } catch (e: Exception) {
            throw AppError.CorruptedFile(e.message, e)
        }
    }

    fun pfd(context: Context, uri: Uri): ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r") ?: throw AppError.NoPermission(uri.toString())

    /** Image entries in natural order (used by comics). */
    fun imageEntries(container: ResourceContainer): List<String> =
        container.entries.filter { FormatDetector.isImage(it) && !it.startsWith("__MACOSX") }.sortedWith(NaturalOrder)
}
