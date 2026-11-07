package app.yomu.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [Index("title"), Index("sortTitle"), Index("addedAt"), Index("lastReadAt"), Index("format"), Index("status"), Index("language"), Index(value = ["uri"], unique = true), Index("fileHash")],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sortTitle: String,
    val authorsText: String,
    val seriesName: String?,
    val seriesId: Long?,
    val seriesIndex: Float?,
    val language: String?,
    val description: String?,
    val publisher: String?,
    val format: String,
    val uri: String,
    val isLinked: Boolean,
    val fileSize: Long,
    val fileHash: String?,
    val coverPath: String?,
    val status: String,
    val isManga: Boolean,
    val addedAt: Long,
    val lastReadAt: Long?,
    val progress: Float,
    val readerSettingsJson: String?,
    val scanFolder: String? = null,
)

@Fts4(contentEntity = BookEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "books_fts")
data class BookFts(val title: String, val authorsText: String, val seriesName: String?)

@Entity(tableName = "authors", indices = [Index(value = ["name"], unique = true)])
data class AuthorEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(
    tableName = "book_authors",
    primaryKeys = ["bookId", "authorId"],
    indices = [Index("authorId")],
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AuthorEntity::class, parentColumns = ["id"], childColumns = ["authorId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class BookAuthorCrossRef(val bookId: Long, val authorId: Long, val position: Int)

@Entity(tableName = "series", indices = [Index(value = ["name"], unique = true)])
data class SeriesEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

/**
 * The user's library tabs (kind = BOOK): books ([BookCollectionCrossRef]) and online titles ([CatalogCollectionCrossRef]).
 * Until 1.4 the online library had its own categories (kind = CATALOG); the 3 → 4 migration made them tabs.
 */
@Entity(tableName = "collections", indices = [Index("kind")])
data class CollectionEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val kind: String = "BOOK", val sortOrder: Int = 0)

@Entity(
    tableName = "book_collections",
    primaryKeys = ["bookId", "collectionId"],
    indices = [Index("collectionId")],
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class BookCollectionCrossRef(val bookId: Long, val collectionId: Long)

/** Online library titles in the user's tabs (1.5.0). */
@Entity(
    tableName = "catalog_collections",
    primaryKeys = ["itemId", "collectionId"],
    indices = [Index("collectionId")],
    foreignKeys = [
        ForeignKey(entity = CatalogItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CatalogCollectionCrossRef(val itemId: Long, val collectionId: Long)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(
    tableName = "book_tags",
    primaryKeys = ["bookId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class BookTagCrossRef(val bookId: Long, val tagId: Long)

@Entity(
    tableName = "reading_progress",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class ReadingProgressEntity(@PrimaryKey val bookId: Long, val locatorJson: String, val progress: Float, val updatedAt: Long)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val locatorJson: String,
    val title: String,
    val progress: Float,
    val createdAt: Long,
)

@Entity(tableName = "highlights", indices = [Index("bookId"), Index("isQuote"), Index("createdAt")])
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val locatorJson: String,
    val chapter: Int,
    val text: String,
    val color: String,
    val style: String,
    val isQuote: Boolean,
    val isFavorite: Boolean,
    val tags: String,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "notes", indices = [Index("bookId"), Index("highlightId")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val highlightId: Long?,
    val locatorJson: String?,
    val text: String,
    val tags: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "words", indices = [Index(value = ["normalized", "lang"], unique = true), Index("dueAt"), Index("bookId"), Index("level")])
data class WordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val term: String,
    val normalized: String,
    val lang: String,
    val translation: String?,
    val definition: String?,
    val context: String?,
    val bookId: Long?,
    val bookTitle: String?,
    val locatorJson: String?,
    val tags: String,
    val createdAt: Long,
    val level: Int,
    val ease: Double,
    val intervalDays: Int,
    val repetitions: Int,
    val dueAt: Long,
    val lapses: Int,
    val lastReviewedAt: Long?,
)

@Entity(tableName = "dictionaries")
data class DictionaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val format: String,
    val sourceLang: String?,
    val targetLang: String?,
    val entryCount: Int,
    val enabled: Boolean,
    val sortOrder: Int,
    val importedAt: Long,
)

@Entity(
    tableName = "dictionary_entries",
    indices = [Index("normalized"), Index("dictId"), Index("reading")],
    foreignKeys = [ForeignKey(entity = DictionaryEntity::class, parentColumns = ["id"], childColumns = ["dictId"], onDelete = ForeignKey.CASCADE)],
)
data class DictionaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dictId: Long,
    val headword: String,
    val normalized: String,
    val reading: String?,
    val pos: String?,
    val ipa: String?,
    /** Definitions separated by \u001e, examples after \u001d. */
    val body: String,
)

@Entity(tableName = "lookup_history", indices = [Index(value = ["query"], unique = true), Index("at")])
data class LookupHistoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val query: String, val at: Long)

@Entity(tableName = "translation_cache", indices = [Index("at")])
data class TranslationCacheEntity(@PrimaryKey val key: String, val engine: String, val source: String, val result: String, val at: Long)

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val lang: String,
    val kind: String,
    val pinned: Boolean,
    val enabled: Boolean,
    val configJson: String?,
    val lastUsedAt: Long,
)

@Entity(tableName = "catalog_items", indices = [Index(value = ["sourceId", "url"], unique = true), Index("favorite"), Index("categoryId")])
data class CatalogItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val url: String,
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genres: String,
    val status: Int,
    val coverUrl: String?,
    val type: String,
    val favorite: Boolean,
    val categoryId: Long?,
    val addedAt: Long,
    val lastUpdateAt: Long,
    val initialized: Boolean,
    val readerSettingsJson: String? = null,
    /** Source-specific data of Tachiyomi/Mihon extensions (JSON object), added in schema 2. */
    val memo: String? = null,
    /** Sort and filters of the chapter list (JSON of domain ChapterDisplay); null = app default. Added in schema 3. */
    val chapterFlags: String? = null,
)

@Entity(
    tableName = "chapters",
    indices = [Index(value = ["itemId", "url"], unique = true), Index("lastReadAt"), Index("isNew")],
    foreignKeys = [ForeignKey(entity = CatalogItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val url: String,
    val name: String,
    val number: Float,
    val scanlator: String?,
    val uploadDate: Long,
    val read: Boolean,
    val bookmarked: Boolean,
    val lastPageRead: Int,
    val downloaded: Boolean,
    val localPath: String?,
    val isNew: Boolean,
    val sourceOrder: Int,
    val fetchedAt: Long,
    val lastReadAt: Long?,
    /** Source-specific data of Tachiyomi/Mihon extensions (JSON object), added in schema 2. */
    val memo: String? = null,
)

@Entity(tableName = "downloads", indices = [Index("status"), Index("chapterId")])
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val title: String,
    val url: String,
    val headersJson: String?,
    val status: String,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val pagesDone: Int,
    val pagesTotal: Int,
    val error: String?,
    val chapterId: Long?,
    val itemId: Long?,
    val sourceId: Long?,
    val destPath: String?,
    val mimeType: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Automatic retries already made after transient errors (schema 3). */
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    /** Higher runs first ("move to top"); ties by [createdAt] (schema 3). */
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
)

@Entity(tableName = "reading_sessions", indices = [Index("startedAt"), Index("bookId")])
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long?,
    val startedAt: Long,
    val durationMs: Long,
    val pages: Int,
    val chars: Long,
    /** Set for online manga/novel reading (schema 2); [bookId] is null then. */
    val catalogItemId: Long? = null,
    /** Removed from the reading history by the user; still counted in the statistics (schema 2). */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)

@Entity(tableName = "extension_repos")
data class ExtensionRepoEntity(
    @PrimaryKey val url: String,
    val name: String,
    val addedAt: Long,
    /** SHA-256 fingerprint of the key the store signs its extensions with (pinned when the store is added). */
    val signingKey: String? = null,
    val website: String? = null,
    val badge: String? = null,
)

@Entity(tableName = "ocr_cache")
data class OcrCacheEntity(@PrimaryKey val key: String, val blocksJson: String, val at: Long)

/**
 * The online title's entry in the user's list on a tracking service (2.0.0): one row per title and service.
 * Services use Mihon's numbering (1 MyAnimeList, 2 AniList, 4 Shikimori) so imported backups map one to one.
 */
@Entity(
    tableName = "tracks",
    indices = [Index(value = ["itemId", "service"], unique = true), Index(value = ["bookId", "service"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = CatalogItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long?,
    val service: Int,
    /** Title id on the service. */
    val remoteId: Long,
    /** Id of the list entry on the service (AniList list entry, Shikimori user rate); 0 = not known yet. */
    val libraryId: Long = 0,
    val title: String,
    val url: String,
    /** Code of domain TrackStatus. */
    val status: Int,
    /** 0–10, 0 = not rated. */
    val score: Float = 0f,
    val lastChapterRead: Float = 0f,
    val totalChapters: Int = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val updatedAt: Long = 0,
    /** Changed here but not sent to the service yet (offline, error, not signed in). */
    val pending: Boolean = false,
    /** Exactly one of itemId and bookId is set by the app. */
    val bookId: Long? = null,
)

/** Last successfully applied folder revision. Stored transactionally with page-position migration. */
@Entity(tableName = "folder_sync_bases", primaryKeys = ["targetId", "folderId"])
data class FolderSyncBaseEntity(val targetId: String, val folderId: String, val revisionJson: String)
