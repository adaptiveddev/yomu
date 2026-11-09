package app.yomu.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @RawQuery(observedEntities = [BookEntity::class, BookTagCrossRef::class, BookCollectionCrossRef::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, BookEntity>

    @RawQuery(observedEntities = [BookEntity::class, BookTagCrossRef::class, BookCollectionCrossRef::class])
    fun observeRaw(query: SupportSQLiteQuery): Flow<List<BookEntity>>

    @RawQuery
    suspend fun queryRaw(query: SupportSQLiteQuery): List<BookEntity>

    @Query("SELECT * FROM books WHERE lastReadAt IS NOT NULL AND status = 'READING' ORDER BY lastReadAt DESC LIMIT :limit")
    fun observeContinue(limit: Int): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id IN (:ids)")
    suspend fun getAll(ids: Collection<Long>): List<BookEntity>

    @Query("SELECT * FROM books WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): BookEntity?

    @Query("UPDATE books SET uri = :uri, isLinked = :isLinked WHERE id = :id")
    suspend fun setUri(id: Long, uri: String, isLinked: Boolean)

    @Query("SELECT * FROM books WHERE fileHash = :hash LIMIT 1")
    suspend fun byHash(hash: String): BookEntity?

    @Query("SELECT * FROM books ORDER BY id")
    suspend fun all(): List<BookEntity>

    @Query("SELECT COUNT(*) FROM books")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<BookEntity>)

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun delete(ids: Collection<Long>)

    @Query("UPDATE books SET status = :status WHERE id IN (:ids)")
    suspend fun setStatus(ids: Collection<Long>, status: String)

    @Query("UPDATE books SET progress = :progress, lastReadAt = :at, status = CASE WHEN status = 'NEW' THEN 'READING' ELSE status END WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Float, at: Long)

    @Query("UPDATE books SET readerSettingsJson = :json WHERE id = :id")
    suspend fun setReaderSettings(id: Long, json: String?)

    @Query("UPDATE books SET coverPath = :path WHERE id = :id")
    suspend fun setCover(id: Long, path: String?)

    @Query("UPDATE books SET isManga = :manga WHERE id = :id")
    suspend fun setManga(id: Long, manga: Boolean)

    @Query("SELECT DISTINCT language FROM books WHERE language IS NOT NULL AND language != '' ORDER BY language")
    fun observeLanguages(): Flow<List<String>>

    @Query("SELECT uri FROM books WHERE scanFolder = :folder")
    suspend fun urisInFolder(folder: String): List<String>

    @Query("SELECT COUNT(*) FROM books WHERE status = 'FINISHED'")
    suspend fun finishedCount(): Int

    @Query("SELECT COUNT(*) FROM books WHERE status = 'FINISHED' AND COALESCE(lastReadAt, addedAt) >= :from")
    suspend fun finishedSince(from: Long): Int

    @Query("SELECT * FROM books WHERE status = 'FINISHED' AND isLinked = 0")
    suspend fun finishedCopied(): List<BookEntity>

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM books WHERE isLinked = 0")
    fun observeCopiedSize(): Flow<Long>

    @Query("INSERT INTO books_fts(books_fts) VALUES('rebuild')")
    suspend fun rebuildFts()
}

data class CollectionWithCount(val id: Long, val name: String, val count: Int)

@Dao
interface CollectionDao {
    /** Library tabs with the number of books and online library titles in each. */
    @Query(
        "SELECT c.id, c.name, (SELECT COUNT(*) FROM book_collections bc WHERE bc.collectionId = c.id) + " +
            "(SELECT COUNT(*) FROM catalog_collections cc JOIN catalog_items i ON i.id = cc.itemId WHERE cc.collectionId = c.id AND i.favorite = 1) AS count " +
            "FROM collections c WHERE c.kind = 'BOOK' ORDER BY c.sortOrder, c.name",
    )
    fun observeBookCollections(): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections WHERE kind = 'BOOK' ORDER BY sortOrder, name")
    suspend fun bookCollections(): List<CollectionEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM collections WHERE kind = 'BOOK'")
    suspend fun maxSortOrder(): Int

    @Query("UPDATE collections SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Int)

    /** Tabs of the online library titles (titles removed from the library are left out). */
    @Query("SELECT cc.* FROM catalog_collections cc JOIN catalog_items i ON i.id = cc.itemId WHERE i.favorite = 1")
    fun observeCatalogMemberships(): Flow<List<CatalogCollectionCrossRef>>

    @Query("SELECT collectionId FROM catalog_collections WHERE itemId = :itemId")
    suspend fun collectionsOfItem(itemId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItems(refs: List<CatalogCollectionCrossRef>)

    @Query("DELETE FROM catalog_collections WHERE collectionId = :collectionId AND itemId IN (:itemIds)")
    suspend fun removeItems(itemIds: Collection<Long>, collectionId: Long)

    @Query("DELETE FROM catalog_collections WHERE itemId IN (:itemIds)")
    suspend fun clearItems(itemIds: Collection<Long>)

    @Query("SELECT * FROM collections WHERE kind = :kind ORDER BY sortOrder, name")
    fun observeKind(kind: String): Flow<List<CollectionEntity>>

    @Query("SELECT collectionId FROM book_collections WHERE bookId = :bookId")
    suspend fun collectionsOf(bookId: Long): List<Long>

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(collections: List<CollectionEntity>)

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(refs: List<BookCollectionCrossRef>)

    @Query("DELETE FROM book_collections WHERE collectionId = :collectionId AND bookId IN (:bookIds)")
    suspend fun remove(bookIds: Collection<Long>, collectionId: Long)

    @Query("SELECT * FROM collections")
    suspend fun all(): List<CollectionEntity>

    @Query("SELECT * FROM book_collections")
    suspend fun allRefs(): List<BookCollectionCrossRef>

    @Query("SELECT * FROM catalog_collections")
    suspend fun allItemRefs(): List<CatalogCollectionCrossRef>
}

@Dao
interface TagDao {
    @Query("SELECT name FROM tags ORDER BY name")
    fun observeNames(): Flow<List<String>>

    @Query("SELECT t.name FROM tags t JOIN book_tags bt ON bt.tagId = t.id WHERE bt.bookId = :bookId ORDER BY t.name")
    suspend fun tagsFor(bookId: Long): List<String>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(refs: List<BookTagCrossRef>)

    @Query("DELETE FROM book_tags WHERE bookId = :bookId")
    suspend fun clear(bookId: Long)

    @Query("DELETE FROM tags WHERE id NOT IN (SELECT tagId FROM book_tags)")
    suspend fun prune()

    @Query("SELECT * FROM tags")
    suspend fun all(): List<TagEntity>

    @Query("SELECT * FROM book_tags")
    suspend fun allRefs(): List<BookTagCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<TagEntity>)
}

@Dao
interface AuthorDao {
    @Query("SELECT * FROM authors WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): AuthorEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(author: AuthorEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(refs: List<BookAuthorCrossRef>)

    @Query("DELETE FROM book_authors WHERE bookId = :bookId")
    suspend fun clear(bookId: Long)

    @Query("SELECT * FROM series WHERE name = :name LIMIT 1")
    suspend fun seriesByName(name: String): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeries(series: SeriesEntity): Long

    @Query("SELECT name FROM series ORDER BY name")
    fun observeSeries(): Flow<List<String>>
}

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun get(bookId: Long): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress")
    suspend fun all(): List<ReadingProgressEntity>

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapter, createdAt")
    fun observeHighlights(bookId: Long): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights ORDER BY createdAt DESC")
    fun observeAllHighlights(): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapter, createdAt")
    suspend fun highlights(bookId: Long): List<HighlightEntity>

    @Query("SELECT * FROM highlights")
    suspend fun allHighlights(): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE id = :id")
    suspend fun highlight(id: Long): HighlightEntity?

    @Insert
    suspend fun insertHighlight(highlight: HighlightEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlights(highlights: List<HighlightEntity>)

    @Update
    suspend fun updateHighlight(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    @Query("SELECT * FROM notes WHERE bookId = :bookId ORDER BY createdAt")
    fun observeNotes(bookId: Long): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE bookId = :bookId ORDER BY createdAt")
    suspend fun notes(bookId: Long): List<NoteEntity>

    @Query("SELECT * FROM notes")
    suspend fun allNotes(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: Long): NoteEntity?

    @Insert
    suspend fun insertNote(note: NoteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotes(notes: List<NoteEntity>)

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: Long)

    @Query("DELETE FROM notes WHERE highlightId = :highlightId")
    suspend fun deleteNotesOfHighlight(highlightId: Long)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY progress")
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY progress")
    suspend fun bookmarks(bookId: Long): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks")
    suspend fun allBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun bookmark(id: Long): BookmarkEntity?

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarks(bookmarks: List<BookmarkEntity>)

    @Update
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("SELECT bookId FROM highlights UNION SELECT bookId FROM notes UNION SELECT bookId FROM bookmarks")
    suspend fun annotatedBookIds(): List<Long>
}

@Dao
interface WordDao {
    @Query(
        "SELECT * FROM words WHERE (:search = '' OR term LIKE '%' || :search || '%' OR translation LIKE '%' || :search || '%') " +
            "AND (:bookId IS NULL OR bookId = :bookId) AND (:tag IS NULL OR (',' || tags || ',') LIKE '%,' || :tag || ',%') " +
            "AND (:maxLevel IS NULL OR level <= :maxLevel) ORDER BY createdAt DESC",
    )
    fun observe(search: String, bookId: Long?, tag: String?, maxLevel: Int?): Flow<List<WordEntity>>

    @Query("SELECT COUNT(*) FROM words WHERE dueAt <= :now")
    fun observeDue(now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM words")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM words WHERE dueAt <= :now ORDER BY dueAt LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<WordEntity>

    @Query("SELECT * FROM words WHERE normalized = :normalized AND lang = :lang LIMIT 1")
    suspend fun find(normalized: String, lang: String): WordEntity?

    @Query("SELECT * FROM words WHERE normalized = :normalized LIMIT 1")
    suspend fun findAnyLang(normalized: String): WordEntity?

    @Query("SELECT * FROM words WHERE id = :id")
    suspend fun get(id: Long): WordEntity?

    @Insert
    suspend fun insert(word: WordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(words: List<WordEntity>)

    @Update
    suspend fun update(word: WordEntity)

    @Query("DELETE FROM words WHERE id IN (:ids)")
    suspend fun delete(ids: Collection<Long>)

    @Query("SELECT COUNT(*) FROM words WHERE lastReviewedAt >= :since")
    suspend fun reviewedSince(since: Long): Int

    @Query("SELECT term FROM words WHERE (:lang IS NULL OR lang = :lang)")
    suspend fun terms(lang: String?): List<String>

    @Query("SELECT normalized FROM words")
    fun observeNormalized(): Flow<List<String>>

    @Query("SELECT * FROM words ORDER BY createdAt DESC")
    suspend fun all(): List<WordEntity>

    @Query("SELECT DISTINCT tags FROM words WHERE tags != ''")
    fun observeTagStrings(): Flow<List<String>>
}

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionaries ORDER BY sortOrder, id")
    fun observe(): Flow<List<DictionaryEntity>>

    @Query("SELECT * FROM dictionaries WHERE enabled = 1 ORDER BY sortOrder, id")
    suspend fun enabled(): List<DictionaryEntity>

    @Insert
    suspend fun insert(dictionary: DictionaryEntity): Long

    @Update
    suspend fun update(dictionary: DictionaryEntity)

    @Query("UPDATE dictionaries SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE dictionaries SET sortOrder = :order WHERE id = :id")
    suspend fun setOrder(id: Long, order: Int)

    @Query("DELETE FROM dictionaries WHERE id = :id")
    suspend fun delete(id: Long)

    /** Dictionaries whose import never finished (the count is set only at its end); their entries go with them. */
    @Query("DELETE FROM dictionaries WHERE entryCount = 0")
    suspend fun deleteUnfinished(): Int

    @Insert
    suspend fun insertEntries(entries: List<DictionaryEntryEntity>)

    @Query("UPDATE dictionaries SET entryCount = (SELECT COUNT(*) FROM dictionary_entries WHERE dictId = :id) WHERE id = :id")
    suspend fun refreshCount(id: Long)

    @Query(
        "SELECT e.* FROM dictionary_entries e JOIN dictionaries d ON d.id = e.dictId " +
            "WHERE d.enabled = 1 AND (e.normalized IN (:keys) OR e.reading IN (:keys)) ORDER BY d.sortOrder, e.id LIMIT 80",
    )
    suspend fun lookup(keys: List<String>): List<DictionaryEntryEntity>

    @Query("SELECT headword FROM dictionary_entries WHERE normalized LIKE :prefix || '%' GROUP BY normalized ORDER BY length(normalized) LIMIT 20")
    suspend fun suggest(prefix: String): List<String>

    @Query("SELECT * FROM dictionaries WHERE id = :id")
    suspend fun get(id: Long): DictionaryEntity?

    @Query("INSERT OR REPLACE INTO lookup_history(`query`, at) VALUES(:query, :at)")
    suspend fun addHistory(query: String, at: Long)

    @Query("SELECT * FROM lookup_history ORDER BY at DESC LIMIT 50")
    fun observeHistory(): Flow<List<LookupHistoryEntity>>

    @Query("DELETE FROM lookup_history")
    suspend fun clearHistory()
}

@Dao
interface TranslationCacheDao {
    @Query("SELECT * FROM translation_cache WHERE `key` = :key")
    suspend fun get(key: String): TranslationCacheEntity?

    @Upsert
    suspend fun put(entry: TranslationCacheEntity)

    @Query("DELETE FROM translation_cache WHERE at < :before")
    suspend fun trim(before: Long)

    @Query("DELETE FROM translation_cache")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM translation_cache")
    fun observeCount(): Flow<Int>
}

data class CatalogItemWithUnread(
    @Embedded val item: CatalogItemEntity,
    val unread: Int,
    val downloadedCount: Int,
    val lastReadAt: Long?,
    val chapterCount: Int,
)

data class HistoryRow(
    @Embedded val chapter: ChapterEntity,
    val itemTitle: String,
    val coverUrl: String?,
    val sourceId: Long,
)

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources")
    fun observe(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("UPDATE sources SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE sources SET lastUsedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM extension_repos ORDER BY addedAt")
    fun observeRepos(): Flow<List<ExtensionRepoEntity>>

    @Query("SELECT * FROM extension_repos")
    suspend fun repos(): List<ExtensionRepoEntity>

    @Upsert
    suspend fun upsertRepo(repo: ExtensionRepoEntity)

    @Query("DELETE FROM extension_repos WHERE url = :url")
    suspend fun deleteRepo(url: String)
}

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_items WHERE sourceId = :sourceId AND url = :url LIMIT 1")
    suspend fun find(sourceId: Long, url: String): CatalogItemEntity?

    @Insert
    suspend fun insert(item: CatalogItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CatalogItemEntity>)

    @Update
    suspend fun update(item: CatalogItemEntity)

    @Query("SELECT * FROM catalog_items WHERE id = :id")
    fun observe(id: Long): Flow<CatalogItemEntity?>

    @Query("SELECT * FROM catalog_items WHERE id = :id")
    suspend fun get(id: Long): CatalogItemEntity?

    @Query(
        "SELECT i.*, (SELECT COUNT(*) FROM chapters c WHERE c.itemId = i.id AND c.read = 0) AS unread, " +
            "(SELECT COUNT(*) FROM chapters c WHERE c.itemId = i.id AND c.downloaded = 1) AS downloadedCount, " +
            "(SELECT MAX(c.lastReadAt) FROM chapters c WHERE c.itemId = i.id) AS lastReadAt, " +
            "(SELECT COUNT(*) FROM chapters c WHERE c.itemId = i.id) AS chapterCount " +
            "FROM catalog_items i WHERE i.favorite = 1 ORDER BY i.lastUpdateAt DESC, i.title",
    )
    fun observeFavorites(): Flow<List<CatalogItemWithUnread>>

    @Query("UPDATE catalog_items SET chapterFlags = :json WHERE id = :id")
    suspend fun setChapterFlags(id: Long, json: String?)

    @Query("SELECT chapterFlags FROM catalog_items WHERE id = :id")
    fun observeChapterFlags(id: Long): Flow<String?>

    @Query("SELECT * FROM catalog_items WHERE favorite = 1")
    suspend fun favorites(): List<CatalogItemEntity>

    @Query("SELECT * FROM catalog_items")
    suspend fun all(): List<CatalogItemEntity>

    @Query("UPDATE catalog_items SET favorite = :favorite, categoryId = :categoryId, addedAt = :at WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, categoryId: Long?, at: Long)

    @Query("UPDATE catalog_items SET readerSettingsJson = :json WHERE id = :id")
    suspend fun setReaderSettings(id: Long, json: String?)

    @Query("DELETE FROM catalog_items WHERE favorite = 0 AND id NOT IN (SELECT itemId FROM chapters WHERE lastReadAt IS NOT NULL OR downloaded = 1)")
    suspend fun pruneUnused()

    @Query("SELECT * FROM chapters WHERE itemId = :itemId ORDER BY sourceOrder")
    fun observeChapters(itemId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE itemId = :itemId ORDER BY sourceOrder")
    suspend fun chapters(itemId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun chapter(id: Long): ChapterEntity?

    @Query("SELECT * FROM chapters")
    suspend fun allChapters(): List<ChapterEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChapters(chapters: List<ChapterEntity>): List<Long>

    @Update
    suspend fun updateChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE id IN (:ids)")
    suspend fun deleteChapters(ids: Collection<Long>)

    @Query("UPDATE chapters SET read = :read WHERE id IN (:ids)")
    suspend fun setRead(ids: Collection<Long>, read: Boolean)

    @Query("UPDATE chapters SET bookmarked = :bookmarked WHERE id IN (:ids)")
    suspend fun setBookmarked(ids: Collection<Long>, bookmarked: Boolean)

    @Query("UPDATE chapters SET lastPageRead = :page, lastReadAt = :at, read = (read OR :finished), isNew = 0 WHERE id = :id")
    suspend fun saveProgress(id: Long, page: Int, at: Long, finished: Boolean)

    @Query("UPDATE chapters SET downloaded = :downloaded, localPath = :path WHERE id = :id")
    suspend fun setDownloaded(id: Long, downloaded: Boolean, path: String?)

    @Query("UPDATE chapters SET isNew = 0 WHERE itemId = :itemId")
    suspend fun clearNew(itemId: Long)

    @Query("SELECT COUNT(*) FROM chapters WHERE isNew = 1")
    fun observeNewCount(): Flow<Int>

    @Query(
        "SELECT c.*, i.title AS itemTitle, i.coverUrl AS coverUrl, i.sourceId AS sourceId FROM chapters c " +
            "JOIN catalog_items i ON i.id = c.itemId WHERE c.lastReadAt IS NOT NULL ORDER BY c.lastReadAt DESC LIMIT 300",
    )
    fun observeHistory(): Flow<List<HistoryRow>>

    @Query(
        "SELECT c.*, i.title AS itemTitle, i.coverUrl AS coverUrl, i.sourceId AS sourceId FROM chapters c " +
            "JOIN catalog_items i ON i.id = c.itemId WHERE c.isNew = 1 ORDER BY c.fetchedAt DESC LIMIT 300",
    )
    fun observeUpdates(): Flow<List<HistoryRow>>

    @Query("UPDATE chapters SET lastReadAt = NULL WHERE id = :id")
    suspend fun removeHistory(id: Long)

    @Query("UPDATE chapters SET lastReadAt = NULL")
    suspend fun clearHistory()

    @Query("UPDATE chapters SET lastReadAt = NULL WHERE itemId = :itemId")
    suspend fun removeHistoryOfItem(itemId: Long)

    @Query("SELECT * FROM catalog_items WHERE id IN (:ids)")
    suspend fun items(ids: Collection<Long>): List<CatalogItemEntity>

    @Query("SELECT * FROM chapters WHERE downloaded = 1")
    suspend fun downloadedChapters(): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE downloaded = 1 AND read = 1")
    suspend fun downloadedRead(): List<ChapterEntity>
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY CASE status WHEN 'RUNNING' THEN 0 WHEN 'QUEUED' THEN 1 WHEN 'PAUSED' THEN 2 WHEN 'FAILED' THEN 3 ELSE 4 END, priority DESC, createdAt, id")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    /** Queued tasks in the order they should run. */
    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY priority DESC, createdAt, id LIMIT :limit")
    suspend fun nextQueued(limit: Int): List<DownloadTaskEntity>

    /** Next queued task whose source is not busy ([busy] may be empty). */
    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' AND (sourceId IS NULL OR sourceId NOT IN (:busy)) ORDER BY priority DESC, createdAt, id LIMIT 1")
    suspend fun nextQueuedExcluding(busy: Collection<Long>): DownloadTaskEntity?

    @Query("SELECT * FROM downloads WHERE status IN (:statuses)")
    suspend fun withStatus(statuses: Collection<String>): List<DownloadTaskEntity>

    @Query("SELECT * FROM downloads WHERE kind = 'BOOK' AND url = :url AND status = 'FAILED' LIMIT 1")
    suspend fun failedForUrl(url: String): DownloadTaskEntity?

    @Query("SELECT * FROM downloads WHERE status = 'RUNNING'")
    suspend fun running(): List<DownloadTaskEntity>

    @Query("SELECT COALESCE(MAX(priority), 0) FROM downloads WHERE status IN ('QUEUED','PAUSED')")
    suspend fun maxPriority(): Int

    @Query("UPDATE downloads SET priority = :priority, updatedAt = :at WHERE id IN (:ids)")
    suspend fun setPriority(ids: Collection<Long>, priority: Int, at: Long)

    /** Back to the queue after a transient error, counting the attempt. */
    @Query("UPDATE downloads SET status = 'QUEUED', attempts = :attempts, error = :error, updatedAt = :at WHERE id = :id")
    suspend fun requeue(id: Long, attempts: Int, error: String?, at: Long)

    /** Manual retry: failed or paused tasks go back to the queue with a fresh retry budget. */
    @Query("UPDATE downloads SET status = 'QUEUED', attempts = 0, error = NULL, updatedAt = :at WHERE id IN (:ids)")
    suspend fun restart(ids: Collection<Long>, at: Long)

    @Query("SELECT id FROM downloads WHERE status = :status")
    suspend fun idsWithStatus(status: String): List<Long>

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'DONE' AND updatedAt >= :since")
    suspend fun doneSince(since: Long): Int

    @Query("SELECT * FROM downloads WHERE chapterId IN (:chapterIds) AND status IN ('QUEUED','RUNNING','PAUSED','FAILED')")
    suspend fun pendingForChapters(chapterIds: Collection<Long>): List<DownloadTaskEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadTaskEntity?

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("UPDATE downloads SET status = :status, error = :error, updatedAt = :at WHERE id IN (:ids)")
    suspend fun setStatus(ids: Collection<Long>, status: String, error: String?, at: Long)

    @Query("UPDATE downloads SET status = :to, updatedAt = :at WHERE status IN (:from)")
    suspend fun moveAll(from: List<String>, to: String, at: Long)

    /** Like [moveAll], replacing the message shown with the tasks ([error] null clears it). */
    @Query("UPDATE downloads SET status = :to, error = :error, updatedAt = :at WHERE status IN (:from)")
    suspend fun moveAllWithError(from: List<String>, to: String, error: String?, at: Long)

    @Query("UPDATE downloads SET bytesDownloaded = :done, bytesTotal = :total, pagesDone = :pagesDone, pagesTotal = :pagesTotal, updatedAt = :at WHERE id = :id")
    suspend fun progress(id: Long, done: Long, total: Long, pagesDone: Int, pagesTotal: Int, at: Long)

    @Query("DELETE FROM downloads WHERE status IN ('DONE','CANCELLED')")
    suspend fun clearFinished()

    @Query("DELETE FROM downloads WHERE id IN (:ids)")
    suspend fun delete(ids: Collection<Long>)

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED','RUNNING')")
    suspend fun activeCount(): Int

    @Query("SELECT * FROM downloads WHERE chapterId = :chapterId AND status IN ('QUEUED','RUNNING','PAUSED') LIMIT 1")
    suspend fun activeForChapter(chapterId: Long): DownloadTaskEntity?

    @Query("SELECT * FROM downloads WHERE kind = 'BOOK' AND url = :url AND status IN ('QUEUED','RUNNING','PAUSED') LIMIT 1")
    suspend fun activeForUrl(url: String): DownloadTaskEntity?
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: ReadingSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<ReadingSessionEntity>)

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt")
    suspend fun all(): List<ReadingSessionEntity>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM reading_sessions WHERE bookId = :bookId")
    suspend fun timeForBook(bookId: Long): Long

    @Query("SELECT * FROM reading_sessions WHERE hidden = 0 ORDER BY startedAt DESC LIMIT :limit")
    fun observeVisible(limit: Int): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt")
    fun observeAll(): Flow<List<ReadingSessionEntity>>

    @Query("UPDATE reading_sessions SET hidden = 1 WHERE bookId = :bookId")
    suspend fun hideBook(bookId: Long)

    @Query("UPDATE reading_sessions SET hidden = 1 WHERE catalogItemId = :itemId")
    suspend fun hideCatalogItem(itemId: Long)

    @Query("UPDATE reading_sessions SET hidden = 1")
    suspend fun hideAll()

    @Query("DELETE FROM reading_sessions")
    suspend fun deleteAll()
}

@Dao
interface OcrCacheDao {
    @Query("SELECT * FROM ocr_cache WHERE `key` = :key")
    suspend fun get(key: String): OcrCacheEntity?

    @Upsert
    suspend fun put(entry: OcrCacheEntity)

    @Query("DELETE FROM ocr_cache")
    suspend fun clear()
}

/** A tracking entry with the title of the online library it belongs to (2.0.1). */
data class TrackWithItem(
    @Embedded val track: TrackEntity,
    val itemTitle: String,
    val itemCover: String?,
    val itemFavorite: Boolean,
    val isBook: Boolean,
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE itemId = :itemId ORDER BY service")
    fun observe(itemId: Long): Flow<List<TrackEntity>>

    /** Every tracked title with its online library entry, the latest change first (the profile's Tracking tab). */
    @Query(
        "SELECT tracks.*, COALESCE(catalog_items.title, books.title) AS itemTitle, " +
            "COALESCE(catalog_items.coverUrl, books.coverPath) AS itemCover, " +
            "COALESCE(catalog_items.favorite, 1) AS itemFavorite, (tracks.bookId IS NOT NULL) AS isBook " +
            "FROM tracks LEFT JOIN catalog_items ON catalog_items.id = tracks.itemId " +
            "LEFT JOIN books ON books.id = tracks.bookId " +
            "WHERE catalog_items.id IS NOT NULL OR books.id IS NOT NULL ORDER BY tracks.updatedAt DESC",
    )
    fun observeWithItems(): Flow<List<TrackWithItem>>

    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY service")
    fun observeBook(bookId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY service")
    suspend fun forBook(bookId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE bookId = :bookId AND service = :service")
    suspend fun getBook(bookId: Long, service: Int): TrackEntity?

    @Query("SELECT * FROM tracks WHERE itemId = :itemId ORDER BY service")
    suspend fun forItem(itemId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE itemId = :itemId AND service = :service")
    suspend fun get(itemId: Long, service: Int): TrackEntity?

    @Query("SELECT * FROM tracks")
    suspend fun all(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE pending = 1")
    suspend fun pending(): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface FolderSyncBaseDao {
    @Query("SELECT * FROM folder_sync_bases WHERE targetId = :targetId")
    suspend fun forTarget(targetId: String): List<FolderSyncBaseEntity>
    @Query("SELECT * FROM folder_sync_bases WHERE folderId = :folderId")
    suspend fun forFolder(folderId: String): List<FolderSyncBaseEntity>
    @Upsert
    suspend fun upsert(base: FolderSyncBaseEntity)
    @Query("DELETE FROM folder_sync_bases WHERE targetId = :targetId AND folderId = :folderId")
    suspend fun delete(targetId: String, folderId: String)
    @Query("DELETE FROM folder_sync_bases")
    suspend fun clear()
}
