package app.yomu.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.yomu.data.audio.AudiobookStore
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.yomu.core.database.AuthorEntity
import app.yomu.core.database.BookAuthorCrossRef
import app.yomu.core.database.BookCollectionCrossRef
import app.yomu.core.database.BookEntity
import app.yomu.core.database.BookTagCrossRef
import app.yomu.core.database.CollectionEntity
import app.yomu.core.database.ReadingProgressEntity
import app.yomu.core.database.SeriesEntity
import app.yomu.core.database.TagEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.common.Files
import app.yomu.data.backup.FolderManifestCodec
import app.yomu.data.backup.FolderRevisionCommit
import app.yomu.data.backup.FolderReadSyncGate
import app.yomu.data.backup.FolderSnapshot
import app.yomu.data.book.FolderPageProof
import app.yomu.data.library.CoverStore
import app.yomu.data.library.ImportService
import app.yomu.domain.model.BookFormat
import app.yomu.domain.model.BatchMetadata
import app.yomu.domain.model.Book
import app.yomu.domain.model.BookCollection
import app.yomu.domain.model.LibraryQuery
import app.yomu.domain.model.LibrarySort
import app.yomu.domain.model.Locator
import app.yomu.domain.model.ReadingStatus
import app.yomu.domain.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.File

class BookRepositoryImpl(
    private val context: Context,
    private val db: YomuDatabase,
    private val covers: CoverStore,
) : BookRepository {
    private val books = db.books()

    fun rawDb(): YomuDatabase = db

    class FolderReadSession internal constructor(val pages: List<FolderPageProof>, private val lease: java.io.Closeable) : java.io.Closeable {
        override fun close() = lease.close()
    }

    /** Hold until the reader closes so sync cannot remap positions under an active reader. */
    suspend fun beginFolderReading(bookId: Long): FolderReadSession? {
        val book = books.get(bookId) ?: return null
        if (book.format != "FOLDER" || book.fileHash?.let(FolderManifestCodec::validId) != true) return null
        val lease = FolderReadSyncGate.readerAfterClose(db)
        try {
            verifyFolderRevisionBeforeReading(bookId)
            val current = books.get(bookId) ?: throw java.io.IOException("Image folder no longer exists")
            if (current.fileHash != book.fileHash || current.uri != book.uri) throw java.io.IOException("Image folder changed while opening")
            val id = book.fileHash ?: throw java.io.IOException("Folder identity missing")
            val revision = FolderRevisionCommit.localRevision(db, id) ?: throw java.io.IOException("Folder page revision missing")
            return FolderReadSession(revision.pages.map { FolderPageProof(it.path, it.sha256, it.size) }, lease)
        } catch (e: Throwable) {
            lease.close()
            throw e
        }
    }

    /** Refuse to open a stable image folder if its saved locators reference an older page set. */
    suspend fun verifyFolderRevisionBeforeReading(bookId: Long) = withContext(Dispatchers.IO) {
        val book = books.get(bookId) ?: return@withContext
        val id = book.fileHash ?: return@withContext
        if (book.format != "FOLDER" || !FolderManifestCodec.validId(id)) return@withContext
        val revision = FolderRevisionCommit.localRevision(db, id)
            ?: throw java.io.IOException("Folder page revision is missing; restore a backup before reading: ${book.title}")
        val observed = FolderSnapshot.snapshot(context, Uri.parse(book.uri), id, book.title)
        if (observed.pages != revision.pages)
            throw java.io.IOException("Image folder changed; synchronize it before reading to preserve saved positions: ${book.title}")
        val current = books.get(bookId)
        if (current?.fileHash != id || current.uri != book.uri || FolderRevisionCommit.localRevision(db, id)?.pages != revision.pages)
            throw java.io.IOException("Image folder changed while opening; try again: ${book.title}")
    }

    /** Resolve UI positions only after acquiring the folder read lease: sync may have remapped them. */
    suspend fun resolveSavedPosition(bookId: Long, reference: String): Locator? {
        val parts = reference.split(":")
        if (parts.size != 3) throw java.io.IOException("Invalid saved position reference")
        val id = parts[1].toLongOrNull() ?: throw java.io.IOException("Invalid saved position reference")
        val createdAt = parts[2].toLongOrNull() ?: throw java.io.IOException("Invalid saved position reference")
        val json = when (parts[0]) {
            "h" -> db.annotations().highlight(id)?.takeIf { it.bookId == bookId && it.createdAt == createdAt }?.let { Pair(true, it.locatorJson) }
            "n" -> db.annotations().note(id)?.takeIf { it.bookId == bookId && it.createdAt == createdAt }?.let { Pair(true, it.locatorJson) }
            "b" -> db.annotations().bookmark(id)?.takeIf { it.bookId == bookId && it.createdAt == createdAt }?.let { Pair(true, it.locatorJson) }
            "w" -> db.words().get(id)?.takeIf { it.bookId == bookId && it.createdAt == createdAt }?.let { Pair(true, it.locatorJson) }
            else -> null
        } ?: throw java.io.IOException("Saved position no longer exists; reopen the list and try again")
        return json.second?.let {
            try { DataJson.decodeFromString(Locator.serializer(), it) }
            catch (e: Exception) { throw java.io.IOException("Saved position is invalid", e) }
        }
    }

    fun buildQuery(q: LibraryQuery): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val sql = StringBuilder("SELECT books.* FROM books WHERE 1 = 1")
        val f = q.filter
        if (f.formats.isNotEmpty()) {
            sql.append(" AND books.format IN (").append(f.formats.joinToString(",") { "?" }).append(")")
            args.addAll(f.formats.map { it.name })
        }
        if (f.statuses.isNotEmpty()) {
            sql.append(" AND books.status IN (").append(f.statuses.joinToString(",") { "?" }).append(")")
            args.addAll(f.statuses.map { it.name })
        }
        f.language?.let {
            sql.append(" AND books.language = ?")
            args += it
        }
        f.tag?.let {
            sql.append(" AND books.id IN (SELECT bt.bookId FROM book_tags bt JOIN tags t ON t.id = bt.tagId WHERE t.name = ?)")
            args += it
        }
        f.collectionId?.let {
            sql.append(" AND books.id IN (SELECT bookId FROM book_collections WHERE collectionId = ?)")
            args += it
        }
        val search = q.search.trim()
        if (search.isNotEmpty()) {
            val match = search.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { "\"" + it.replace("\"", "") + "\"*" }
            sql.append(" AND (books.id IN (SELECT rowid FROM books_fts WHERE books_fts MATCH ?) OR books.title LIKE ? OR books.authorsText LIKE ? OR books.seriesName LIKE ?)")
            args += match
            val like = "%$search%"
            args += like
            args += like
            args += like
        }
        val dir = if (q.ascending) "ASC" else "DESC"
        val order = when (q.sort) {
            LibrarySort.TITLE -> "books.sortTitle COLLATE NOCASE $dir"
            LibrarySort.AUTHOR -> "books.authorsText COLLATE NOCASE $dir, books.seriesName, books.seriesIndex"
            LibrarySort.ADDED -> "books.addedAt $dir"
            LibrarySort.LAST_READ -> "COALESCE(books.lastReadAt, 0) $dir, books.addedAt $dir"
            LibrarySort.PROGRESS -> "books.progress $dir"
        }
        sql.append(" ORDER BY ").append(order)
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    fun pagedBooks(query: LibraryQuery): Flow<PagingData<Book>> =
        Pager(PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false)) { books.pagingSource(buildQuery(query)) }
            .flow.map { data -> data.map { it.toDomain() } }

    override fun observeBooks(query: LibraryQuery): Flow<List<Book>> = books.observeRaw(buildQuery(query)).map { l -> l.map { it.toDomain() } }

    override fun observeContinueReading(limit: Int): Flow<List<Book>> = books.observeContinue(limit).map { l -> l.map { it.toDomain() } }

    override fun observeBook(id: Long): Flow<Book?> = books.observe(id).map { it?.toDomain(db.tags().tagsFor(id)) }

    override suspend fun getBook(id: Long): Book? = books.get(id)?.toDomain(db.tags().tagsFor(id))

    suspend fun getEntity(id: Long): BookEntity? = books.get(id)

    fun observeCount(): Flow<Int> = books.observeCount()

    fun observeSeries(): Flow<List<String>> = db.authors().observeSeries()

    override suspend fun update(book: Book) {
        val e = books.get(book.id) ?: return
        db.withTransaction {
            books.update(
                e.copy(
                    title = book.title,
                    sortTitle = sortTitle(book.title),
                    authorsText = authorsToText(book.authors),
                    seriesName = book.series,
                    seriesIndex = book.seriesIndex,
                    language = book.language,
                    description = book.description,
                    publisher = book.publisher,
                    status = book.status.name,
                    isManga = book.isManga,
                    coverPath = book.coverPath,
                ),
            )
            linkAuthors(book.id, book.authors)
            linkSeries(book.id, book.series)
        }
    }

    /** Inserts a newly imported book together with authors, series and tags. */
    suspend fun insert(entity: BookEntity, authors: List<String>, tags: List<String>): Long = db.withTransaction {
        val id = books.insert(entity)
        linkAuthors(id, authors)
        linkSeries(id, entity.seriesName)
        if (tags.isNotEmpty()) setTags(id, tags)
        id
    }

    private suspend fun linkAuthors(bookId: Long, authors: List<String>) {
        val dao = db.authors()
        dao.clear(bookId)
        val refs = authors.mapIndexedNotNull { i, name ->
            if (name.isBlank()) return@mapIndexedNotNull null
            val id = dao.byName(name)?.id ?: dao.insert(AuthorEntity(name = name)).takeIf { it > 0 } ?: dao.byName(name)?.id ?: return@mapIndexedNotNull null
            BookAuthorCrossRef(bookId, id, i)
        }
        if (refs.isNotEmpty()) dao.link(refs)
    }

    private suspend fun linkSeries(bookId: Long, series: String?) {
        if (series.isNullOrBlank()) {
            books.get(bookId)?.let { if (it.seriesId != null) books.update(it.copy(seriesId = null)) }
            return
        }
        val dao = db.authors()
        val id = dao.seriesByName(series)?.id ?: dao.insertSeries(SeriesEntity(name = series))
        books.get(bookId)?.let { if (it.seriesId != id) books.update(it.copy(seriesId = id)) }
    }

    override suspend fun setStatus(ids: Collection<Long>, status: ReadingStatus) = books.setStatus(ids, status.name)

    /**
     * Applies the batch [edit] to the [list] of books (already in the order they are numbered in, see
     * [BatchMetadata.ordered]) in one transaction. Returns how many books changed.
     */
    suspend fun batchEdit(list: List<Book>, edit: BatchMetadata): Int = db.withTransaction {
        var changed = 0
        list.forEachIndexed { i, before ->
            val after = edit.applyTo(before, i)
            if (after == before) return@forEachIndexed
            if (after.copy(tags = before.tags) != before) update(after)
            if (after.tags != before.tags) setTags(before.id, after.tags)
            changed++
        }
        changed
    }

    suspend fun setManga(id: Long, manga: Boolean) = books.setManga(id, manga)

    override suspend fun delete(ids: Collection<Long>, deleteFiles: Boolean) {
        val list = books.getAll(ids)
        books.delete(ids)
        list.forEach { b ->
            covers.delete(b.coverPath)
            File(context.filesDir, "converted/${b.id}.epub").delete()
            File(context.cacheDir, "pages/book_${b.id}").deleteRecursively()
            val uri = Uri.parse(b.uri)
            if (!b.isLinked && uri.scheme == "file") {
                File(uri.path!!).let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
                // Offline copies of an online audiobook live next to its manifest.
                if (AudiobookStore.isManifest(b.uri)) AudiobookStore.downloadDirOf(File(uri.path!!)).deleteRecursively()
            } else if (deleteFiles && b.isLinked) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.onFailure { Timber.w(it, "Cannot delete ${b.uri}") }
            }
        }
        db.tags().prune()
    }

    override suspend fun saveProgress(bookId: Long, locator: Locator) = db.withTransaction {
        val now = System.currentTimeMillis()
        db.progress().upsert(ReadingProgressEntity(bookId, locator.toJson(), locator.totalProgression, now))
        books.updateProgress(bookId, locator.totalProgression, now)
    }

    override suspend fun getProgress(bookId: Long): Locator? = db.progress().get(bookId)?.locatorJson?.toLocator()

    override suspend fun saveReaderSettings(bookId: Long, json: String?) = books.setReaderSettings(bookId, json)

    /**
     * Points the book at a new file (used by the editor for "Replace original" when the format changes,
     * e.g. FB2 -> EPUB or CBR -> CBZ). Cached conversions and rendered pages are dropped.
     */
    suspend fun replaceFile(bookId: Long, file: File, format: BookFormat) {
        val e = books.get(bookId) ?: return
        val hash = Files.sha1(file.inputStream(), ImportService.HASH_LIMIT) + ":" + file.length()
        books.update(e.copy(uri = Uri.fromFile(file).toString(), format = format.name, isLinked = false, fileSize = file.length(), fileHash = hash))
        invalidateCaches(bookId)
    }

    /**
     * Points a book whose file went missing or lost its permission at the document the user picked again.
     * Reading progress, notes and statistics stay attached to the book.
     */
    suspend fun relink(bookId: Long, uri: Uri) {
        val e = books.get(bookId) ?: return
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val size = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l >= 0 } }
        }.getOrNull() ?: e.fileSize
        books.update(e.copy(uri = uri.toString(), isLinked = true, fileSize = size))
        invalidateCaches(bookId)
    }

    /** Called after the content of a book file was rewritten in place. */
    suspend fun contentChanged(bookId: Long, file: File?) {
        if (file != null) {
            books.get(bookId)?.let { e ->
                val hash = Files.sha1(file.inputStream(), ImportService.HASH_LIMIT) + ":" + file.length()
                books.update(e.copy(fileSize = file.length(), fileHash = hash))
            }
        }
        invalidateCaches(bookId)
    }

    fun invalidateCaches(bookId: Long) {
        File(context.filesDir, "converted/$bookId.epub").delete()
        File(context.cacheDir, "pages/book_$bookId").deleteRecursively()
    }

    suspend fun setCover(bookId: Long, bytes: ByteArray): String? {
        val path = covers.save("b$bookId", bytes) ?: return null
        books.setCover(bookId, path)
        return path
    }

    override fun observeCollections(): Flow<List<BookCollection>> =
        db.collections().observeBookCollections().map { l -> l.map { BookCollection(it.id, it.name, it.count) } }

    override suspend fun createCollection(name: String): Long = db.collections().insert(CollectionEntity(name = name.trim()))
    override suspend fun renameCollection(id: Long, name: String) = db.collections().rename(id, name.trim())
    override suspend fun deleteCollection(id: Long) = db.collections().delete(id)
    override suspend fun addToCollection(bookIds: Collection<Long>, collectionId: Long) =
        db.collections().add(bookIds.map { BookCollectionCrossRef(it, collectionId) })
    override suspend fun removeFromCollection(bookIds: Collection<Long>, collectionId: Long) = db.collections().remove(bookIds, collectionId)
    suspend fun collectionsOf(bookId: Long): List<Long> = db.collections().collectionsOf(bookId)

    override fun observeTags(): Flow<List<String>> = db.tags().observeNames()
    override fun observeLanguages(): Flow<List<String>> = books.observeLanguages()

    override suspend fun setTags(bookId: Long, tags: List<String>) {
        val dao = db.tags()
        dao.clear(bookId)
        val refs = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { name ->
            val id = dao.byName(name)?.id ?: dao.insert(TagEntity(name = name))
            BookTagCrossRef(bookId, id)
        }
        dao.link(refs)
        dao.prune()
    }

    override suspend fun addTag(bookIds: Collection<Long>, tag: String) {
        val dao = db.tags()
        val name = tag.trim()
        if (name.isEmpty()) return
        val id = dao.byName(name)?.id ?: dao.insert(TagEntity(name = name))
        dao.link(bookIds.map { BookTagCrossRef(it, id) })
    }
}

suspend fun BookRepositoryImpl.findByHash(hash: String): Long? = rawDb().books().byHash(hash)?.id
suspend fun BookRepositoryImpl.findByUri(uri: String): Long? = rawDb().books().byUri(uri)?.id
suspend fun BookRepositoryImpl.setFileUri(id: Long, uri: String, isLinked: Boolean) = rawDb().books().setUri(id, uri, isLinked)
suspend fun BookRepositoryImpl.urisInFolder(folder: String): List<String> = rawDb().books().urisInFolder(folder)
