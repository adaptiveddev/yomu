package app.yomu.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class, BookFts::class, AuthorEntity::class, BookAuthorCrossRef::class, SeriesEntity::class,
        CollectionEntity::class, BookCollectionCrossRef::class, CatalogCollectionCrossRef::class, TagEntity::class, BookTagCrossRef::class,
        ReadingProgressEntity::class, BookmarkEntity::class, HighlightEntity::class, NoteEntity::class,
        WordEntity::class, DictionaryEntity::class, DictionaryEntryEntity::class, LookupHistoryEntity::class,
        TranslationCacheEntity::class, SourceEntity::class, CatalogItemEntity::class, ChapterEntity::class,
        DownloadTaskEntity::class, ReadingSessionEntity::class, ExtensionRepoEntity::class, OcrCacheEntity::class, TrackEntity::class,
        FolderSyncBaseEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class YomuDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun collections(): CollectionDao
    abstract fun tags(): TagDao
    abstract fun authors(): AuthorDao
    abstract fun progress(): ProgressDao
    abstract fun annotations(): AnnotationDao
    abstract fun words(): WordDao
    abstract fun dictionaries(): DictionaryDao
    abstract fun translationCache(): TranslationCacheDao
    abstract fun sources(): SourceDao
    abstract fun catalog(): CatalogDao
    abstract fun downloads(): DownloadDao
    abstract fun sessions(): SessionDao
    abstract fun ocrCache(): OcrCacheDao
    abstract fun tracks(): TrackDao
    abstract fun folderSyncBases(): FolderSyncBaseDao

    companion object {
        const val NAME = "yomu.db"

        fun build(context: Context, inMemory: Boolean = false): YomuDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, YomuDatabase::class.java)
            } else {
                Room.databaseBuilder(context, YomuDatabase::class.java, NAME)
            }
            // Schema changes ship explicit Migration objects (never destructive); see docs/DECISIONS.md.
            return builder.addMigrations(*MIGRATIONS).build()
        }

        /** 1 → 2: extension data (memo), online reading sessions and pinned store signing keys. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN memo TEXT")
                db.execSQL("ALTER TABLE chapters ADD COLUMN memo TEXT")
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN catalogItemId INTEGER")
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE extension_repos ADD COLUMN signingKey TEXT")
                db.execSQL("ALTER TABLE extension_repos ADD COLUMN website TEXT")
                db.execSQL("ALTER TABLE extension_repos ADD COLUMN badge TEXT")
            }
        }

        /** 2 → 3: per-title chapter sort/filters, automatic download retries and queue priority. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN chapterFlags TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 3 → 4 (1.5.0): online titles can be put in the library tabs. The categories of the online library become tabs
         * (merged into a book tab of the same name), and their titles stay in them.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `catalog_collections` (`itemId` INTEGER NOT NULL, `collectionId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`itemId`, `collectionId`), " +
                        "FOREIGN KEY(`itemId`) REFERENCES `catalog_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_collections_collectionId` ON `catalog_collections` (`collectionId`)")
                categoriesToTabs(db)
            }
        }

        /** Turns online library categories (kind = CATALOG, up to 1.4) into tabs; also run after restoring an older backup. */
        fun categoriesToTabs(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO catalog_collections (itemId, collectionId) " +
                    "SELECT i.id, COALESCE((SELECT b.id FROM collections b WHERE b.kind = 'BOOK' AND b.name = c.name ORDER BY b.id LIMIT 1), c.id) " +
                    "FROM catalog_items i JOIN collections c ON c.id = i.categoryId WHERE c.kind = 'CATALOG'",
            )
            db.execSQL("DELETE FROM collections WHERE kind = 'CATALOG' AND name IN (SELECT name FROM collections WHERE kind = 'BOOK')")
            db.execSQL("UPDATE collections SET kind = 'BOOK' WHERE kind = 'CATALOG'")
            db.execSQL("UPDATE catalog_items SET categoryId = NULL WHERE categoryId IS NOT NULL")
        }

        /** 4 → 5 (2.0.0): entries on tracking services (MyAnimeList, AniList, Shikimori). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER NOT NULL, " +
                        "`service` INTEGER NOT NULL, `remoteId` INTEGER NOT NULL, `libraryId` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, `status` INTEGER NOT NULL, `score` REAL NOT NULL, `lastChapterRead` REAL NOT NULL, " +
                        "`totalChapters` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`pending` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`itemId`) REFERENCES `catalog_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_itemId_service` ON `tracks` (`itemId`, `service`)")
            }
        }

        /** 5 → 6: tracking can also belong to a local book. Existing online entries retain their ids. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks RENAME TO tracks_old")
                db.execSQL("DROP INDEX IF EXISTS index_tracks_itemId_service")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER, " +
                        "`service` INTEGER NOT NULL, `remoteId` INTEGER NOT NULL, `libraryId` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, `status` INTEGER NOT NULL, `score` REAL NOT NULL, `lastChapterRead` REAL NOT NULL, " +
                        "`totalChapters` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`pending` INTEGER NOT NULL, `bookId` INTEGER, " +
                        "FOREIGN KEY(`itemId`) REFERENCES `catalog_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("INSERT INTO tracks (id,itemId,service,remoteId,libraryId,title,url,status,score,lastChapterRead,totalChapters,startedAt,finishedAt,updatedAt,pending) SELECT id,itemId,service,remoteId,libraryId,title,url,status,score,lastChapterRead,totalChapters,startedAt,finishedAt,updatedAt,pending FROM tracks_old")
                db.execSQL("DROP TABLE tracks_old")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_itemId_service` ON `tracks` (`itemId`, `service`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_bookId_service` ON `tracks` (`bookId`, `service`)")
            }
        }

        /** 6 → 7: folder revision bases must commit with page-position updates. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `folder_sync_bases` (`targetId` TEXT NOT NULL, `folderId` TEXT NOT NULL, `revisionJson` TEXT NOT NULL, PRIMARY KEY(`targetId`, `folderId`))")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
    }
}
