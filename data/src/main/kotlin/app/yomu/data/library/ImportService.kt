package app.yomu.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import app.yomu.core.common.AppError
import app.yomu.core.common.Files
import app.yomu.core.database.BookEntity
import app.yomu.core.datastore.AppSettings
import app.yomu.data.format.FormatDetector
import app.yomu.data.audio.AudiobookStore
import app.yomu.data.audio.YouTubeClient
import app.yomu.data.book.Containers
import app.yomu.domain.model.AudioFiles
import app.yomu.domain.model.AudiobookManifest
import app.yomu.data.backup.FolderManifestCodec
import app.yomu.data.backup.FolderEntry
import app.yomu.data.backup.FolderSnapshot
import app.yomu.data.backup.FolderRevisionCommit
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.data.repository.findByHash
import app.yomu.data.repository.findByUri
import app.yomu.data.repository.setFileUri
import app.yomu.data.repository.urisInFolder
import app.yomu.data.repository.authorsToText
import app.yomu.data.repository.sortTitle
import app.yomu.domain.model.Book
import app.yomu.domain.model.BookFormat
import app.yomu.domain.model.ReadingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

sealed interface ImportOutcome {
    val name: String
    data class Added(override val name: String, val bookId: Long) : ImportOutcome
    data class Duplicate(override val name: String, val bookId: Long) : ImportOutcome
    data class Failed(override val name: String, val error: Throwable) : ImportOutcome
}

data class ImportProgress(val done: Int, val total: Int, val current: String?) {
    val running: Boolean get() = done < total
}

/** Imports documents into the library: format detection, de-duplication, copy or SAF link, metadata and covers. */
class ImportService(
    private val context: Context,
    private val books: BookRepositoryImpl,
    private val settings: AppSettings,
    private val covers: CoverStore,
    // Only folder and ZIP imports use it, never the network: tests may leave the default.
    private val audio: AudiobookStore = AudiobookStore(context, books, { okhttp3.OkHttpClient() }, YouTubeClient { okhttp3.OkHttpClient() }),
) {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow(ImportProgress(0, 0, null))
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    private val booksDir: File get() = File(context.filesDir, "books").also { it.mkdirs() }

    suspend fun import(uris: List<Uri>, link: Boolean? = null, scanFolder: String? = null): List<ImportOutcome> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val asLink = link ?: (settings.importMode.get() == "LINK")
            val out = mutableListOf<ImportOutcome>()
            _progress.value = ImportProgress(0, uris.size, null)
            uris.forEachIndexed { i, uri ->
                val name = Files.displayName(context, uri) ?: uri.lastPathSegment ?: "file"
                _progress.value = ImportProgress(i, uris.size, name)
                out += runCatching { importOne(uri, name, asLink, scanFolder, null) }
                    // Leaving the screen stops the import; it must not count as a failed file and go on.
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrElse { e ->
                        Timber.w(e, "Import failed for $name")
                        ImportOutcome.Failed(name, e)
                    }
            }
            _progress.value = ImportProgress(uris.size, uris.size, null)
            out
        }
    }

    /** Imports an image folder (comic) picked as a tree or found by the scanner. */
    suspend fun importFolder(uri: Uri, name: String, scanFolder: String? = null): ImportOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                if (isAudioFolder(uri)) return@runCatching audio.addFolder(uri, name, linked = uri.scheme != "file", scanFolder = scanFolder)
                // Keep existing imports unchanged when file sync is off. When enabled, a new
                // folder receives a stable identity and a baseline before it can be read.
                if (!settings.syncBookFiles.get()) importOne(uri, name, true, scanFolder, BookFormat.FOLDER)
                else {
                    val existing = books.findByUri(uri.toString())
                    if (existing != null) ImportOutcome.Duplicate(name, existing)
                    else {
                        val id = "folder:${java.util.UUID.randomUUID()}"
                        val revision = FolderSnapshot.snapshot(context, uri, id, name)
                        importOne(uri, name, true, scanFolder, BookFormat.FOLDER, folderIdentity = id, initialFolderRevision = revision)
                    }
                }
            }.getOrElse { ImportOutcome.Failed(name, it) }
        }
    }

    /** Imports a file that already lives in app storage (downloads). */
    suspend fun importLocalFile(file: File, displayName: String = file.name): ImportOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { importOne(Uri.fromFile(file), displayName, false, null, null, alreadyLocal = true) }
                .getOrElse { ImportOutcome.Failed(displayName, it) }
        }
    }

    /** Imports a fully verified private image folder with its cross-device identity. */
    internal suspend fun importSyncedFolder(folder: File, id: String, displayName: String): ImportOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!folder.isDirectory || !FolderManifestCodec.validId(id))
                return@withLock ImportOutcome.Failed(displayName, java.io.IOException("Invalid synced folder"))
            runCatching {
                val uri = Uri.fromFile(folder)
                val revision = FolderSnapshot.snapshot(context, uri, id, displayName)
                importOne(uri, displayName, false, null, BookFormat.FOLDER, alreadyLocal = true, folderIdentity = id, initialFolderRevision = revision)
            }
                .getOrElse { ImportOutcome.Failed(displayName, it) }
        }
    }

    /**
     * A downloaded [file] turned out to be a copy of library book [bookId]. If that book lost its file (deleted,
     * moved, permission revoked), the book takes over [file] and the path is returned; otherwise [file] is deleted
     * and the existing path returned. Reading progress, notes and settings of the book are kept either way.
     */
    suspend fun adoptFileForDuplicate(bookId: Long, file: File): String? = withContext(Dispatchers.IO) {
        val existing = books.getBook(bookId) ?: return@withContext null
        val uri = Uri.parse(existing.uri)
        val alive = runCatching { Files.open(context, uri).use { it.read() >= 0 } }.getOrDefault(false)
        if (alive) {
            if (uri.scheme == "file" && File(uri.path.orEmpty()).canonicalPath == file.canonicalPath) return@withContext file.path
            file.delete()
            return@withContext uri.path ?: existing.uri
        }
        books.setFileUri(bookId, Uri.fromFile(file).toString(), isLinked = false)
        file.path
    }

    private suspend fun importOne(
        uri: Uri,
        name: String,
        link: Boolean,
        scanFolder: String?,
        forced: BookFormat?,
        alreadyLocal: Boolean = false,
        folderIdentity: String? = null,
        initialFolderRevision: FolderEntry? = null,
    ): ImportOutcome {
        val format = forced ?: run {
            val header = Files.open(context, uri).use { input ->
                val buf = ByteArray(FormatDetector.HEADER_SIZE)
                val n = input.read(buf).coerceAtLeast(0)
                buf.copyOf(n)
            }
            FormatDetector.detect(header, name)
        }
        if (format == BookFormat.UNKNOWN) throw AppError.UnsupportedFormat(FormatDetector.extension(name).ifEmpty { "?" })
        if (format == BookFormat.CBZ && forced == null) importAudioZip(uri, name, alreadyLocal)?.let { return it }
        val size = if (format == BookFormat.FOLDER) 0L else Files.size(context, uri)
        val hash = if (format == BookFormat.FOLDER) (folderIdentity ?: "dir:$uri") else Files.sha1(Files.open(context, uri), HASH_LIMIT) + ":" + size
        books.findByHash(hash)?.let { return ImportOutcome.Duplicate(name, it) }
        books.findByUri(uri.toString())?.let { return ImportOutcome.Duplicate(name, it) }

        val storedUri: Uri
        val isLinked: Boolean
        if (alreadyLocal || format == BookFormat.FOLDER || link) {
            if (!alreadyLocal) takePermission(uri)
            storedUri = uri
            isLinked = !alreadyLocal
        } else {
            val dest = unique(File(booksDir, Files.sanitize(name)))
            try {
                Files.copy(context, uri, dest)
            } catch (e: java.io.IOException) {
                dest.delete()
                if (e.message?.contains("ENOSPC") == true) throw AppError.NoSpace()
                throw e
            }
            storedUri = Uri.fromFile(dest)
            isLinked = false
        }
        val copiedFile = if (!isLinked && !alreadyLocal) storedUri.path?.let(::File) else null
        var committed = false
        try {
        val meta = try {
            MetadataExtractor.extract(context, storedUri, format, name)
        } catch (e: AppError) {
            if (!isLinked && !alreadyLocal) File(storedUri.path!!).delete()
            throw e
        } catch (e: Exception) {
            if (!isLinked && !alreadyLocal) File(storedUri.path!!).delete()
            throw AppError.CorruptedFile(e.message, e)
        }
        val entity = BookEntity(
            title = meta.title,
            sortTitle = sortTitle(meta.title),
            authorsText = authorsToText(meta.authors),
            seriesName = meta.series,
            seriesId = null,
            seriesIndex = meta.seriesIndex,
            language = meta.language,
            description = meta.description,
            publisher = meta.publisher,
            format = format.name,
            uri = storedUri.toString(),
            isLinked = isLinked,
            fileSize = if (size > 0) size else 0,
            fileHash = hash,
            coverPath = null,
            status = ReadingStatus.NEW.name,
            isManga = format.isComic || meta.isManga,
            addedAt = System.currentTimeMillis(),
            lastReadAt = null,
            progress = 0f,
            readerSettingsJson = if (meta.rtl) RTL_SETTINGS else null,
            scanFolder = scanFolder,
        )
        val id = if (initialFolderRevision == null) books.insert(entity, meta.authors, meta.subjects.take(5))
        else books.rawDb().withTransaction {
            val inserted = books.insert(entity, meta.authors, meta.subjects.take(5))
            FolderRevisionCommit.initializeLocal(books.rawDb(), inserted, initialFolderRevision)
            inserted
        }
        committed = true
        meta.cover?.let { books.setCover(id, it) }
        meta.converted?.let { converted ->
            runCatching { converted.builder.writeTo(BookFiles.convertedFile(context, id)) }.onFailure { Timber.w(it, "Conversion cache failed") }
        }
        return ImportOutcome.Added(name, id)
        } finally {
            // Cancelled or failed before the book was saved: no orphan copy stays in the library folder.
            if (!committed) copiedFile?.delete()
        }
    }

    private fun takePermission(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** A folder is an audiobook when it holds audio files and no more images than those (a cover is fine). */
    private fun isAudioFolder(uri: Uri): Boolean {
        val tracks = runCatching { audio.listFolder(uri) }.getOrDefault(emptyList())
        if (tracks.isEmpty()) return false
        val images = runCatching { FolderSnapshot.enumerate(context, uri).size }.getOrDefault(0)
        return images <= tracks.size
    }

    /** A ZIP of audio files becomes a folder audiobook in app storage; null for any other ZIP (a comic). */
    private suspend fun importAudioZip(uri: Uri, name: String, alreadyLocal: Boolean): ImportOutcome? {
        val entries = runCatching { Containers.open(context, uri, BookFormat.CBZ).use { it.entries } }.getOrNull() ?: return null
        if (!AudiobookStore.isAudioZip(entries)) return null
        val local = uri.takeIf { it.scheme == "file" }?.path?.let(::File)
        val zip = local ?: File(context.cacheDir, "audiozip/${System.nanoTime()}.zip").also { Files.copy(context, uri, it) }
        try {
            return audio.addZip(zip, name)
        } finally {
            // A downloaded ZIP is not kept next to its unpacked tracks; a user's own file is never touched.
            if (local == null || alreadyLocal) zip.delete()
        }
    }

    private fun unique(file: File): File {
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val ext = file.extension
        var i = 1
        while (true) {
            val f = File(file.parentFile, "$base ($i)" + if (ext.isNotEmpty()) ".$ext" else "")
            if (!f.exists()) return f
            i++
        }
    }

    /** Scans a SAF folder tree: new documents are linked, folders with only images become comics. */
    suspend fun scanTree(treeUri: Uri): List<ImportOutcome> = withContext(Dispatchers.IO) {
        val files = SafTree.walk(context, treeUri)
        val known = books.urisInFolder(treeUri.toString()).toSet()
        // Folders with several audio files are audiobooks: their tracks are not separate books.
        val audioDirs = files.filter { !it.isDirectory && AudioFiles.isAudio(it.name) }
            .groupBy { it.path.substringBeforeLast('/', "") }
            .filter { (dir, tracks) -> dir.isNotEmpty() && tracks.size >= 2 }.keys
        val supported = files.filter {
            !it.isDirectory && FormatDetector.extension(it.name).let { ext -> ext in SUPPORTED_EXT } && it.uri.toString() !in known &&
                !(AudioFiles.isAudio(it.name) && it.path.substringBeforeLast('/', "") in audioDirs)
        }
        val out = mutableListOf<ImportOutcome>()
        if (supported.isNotEmpty()) out += import(supported.map { it.uri }, link = true, scanFolder = treeUri.toString())
        audioDirs.forEach { dirPath ->
            val dir = files.firstOrNull { it.isDirectory && it.path == dirPath } ?: return@forEach
            if (dir.uri.toString() in known) return@forEach
            out += mutex.withLock {
                runCatching { audio.addFolder(dir.uri, dir.name, linked = true, scanFolder = treeUri.toString()) }.getOrElse { ImportOutcome.Failed(dir.name, it) }
            }
        }
        val imageDirs = files.filter { !it.isDirectory && FormatDetector.isImage(it.name) }
            .groupBy { it.path.substringBeforeLast('/', "") }
            .filter { (dir, imgs) -> dir.isNotEmpty() && imgs.size >= 3 && dir !in audioDirs }
        imageDirs.keys.forEach { dirPath ->
            val dir = files.firstOrNull { it.isDirectory && it.path == dirPath } ?: return@forEach
            if (dir.uri.toString() in known) return@forEach
            if (files.any { it.isDirectory && it.path.startsWith("$dirPath/") && imageDirs.containsKey(it.path) }) return@forEach
            out += importFolder(dir.uri, dir.name, treeUri.toString())
        }
        out
    }

    companion object {
        const val HASH_LIMIT = 1024L * 1024
        val SUPPORTED_EXT = setOf("epub", "pdf", "cbz", "cbr", "cb7", "zip", "rar", "7z", "fb2", "fb2.zip", "txt", "html", "htm", "xhtml", "docx", "mobi", "azw3", "azw", "prc") +
            AudioFiles.EXTENSIONS + AudiobookManifest.EXTENSION
        const val RTL_SETTINGS = "{\"manga\":{\"direction\":\"RTL\"}}"
    }
}

/** Resolves the file used by the readers: converted formats are cached as EPUB. */
object BookFiles {
    fun convertedFile(context: Context, bookId: Long): File = File(context.filesDir, "converted/$bookId.epub")

    suspend fun readingUri(context: Context, book: Book): Uri = withContext(Dispatchers.IO) {
        val uri = Uri.parse(book.uri)
        when (book.format) {
            BookFormat.FB2, BookFormat.TXT, BookFormat.HTML, BookFormat.DOCX, BookFormat.MOBI -> {
                val file = convertedFile(context, book.id)
                if (!file.exists()) {
                    val name = Files.displayName(context, uri) ?: "book.${book.format.extensions.firstOrNull().orEmpty()}"
                    val converted = MetadataExtractor.convert(context, uri, book.format, name) ?: throw AppError.UnsupportedFormat(book.format.name)
                    converted.builder.writeTo(file)
                }
                Uri.fromFile(file)
            }
            else -> uri
        }
    }

    /** Format of the document returned by [readingUri]. */
    fun readingFormat(book: Book): BookFormat = when (book.format) {
        BookFormat.FB2, BookFormat.TXT, BookFormat.HTML, BookFormat.DOCX, BookFormat.MOBI -> BookFormat.EPUB
        else -> book.format
    }
}
