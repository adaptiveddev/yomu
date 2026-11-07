package app.yomu.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.yomu.core.database.YomuDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Upgrading from 1.0 must keep the user's data: builds a version 1 database from the exported schema (1.json, as shipped in 1.0.0),
 * fills it, opens it with the current Room database (which runs the migrations and validates every table against the entities).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseMigrationTest {
    private val schemas = File("../core/database/schemas/app.yomu.core.database.YomuDatabase")

    private fun createFromSchema(file: File, version: Int) {
        val db = Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            db["entities"]!!.jsonArray.forEach { e ->
                val entity = e.jsonObject
                val table = entity.string("tableName")
                sql.execSQL(entity.string("createSql").replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { i -> sql.execSQL(i.jsonObject.string("createSql").replace("\${TABLE_NAME}", table)) }
                entity["contentSyncTriggers"]?.jsonArray?.forEach { t -> sql.execSQL(t.jsonPrimitive.content) }
            }
            db["setupQueries"]!!.jsonArray.forEach { q -> sql.execSQL(q.jsonPrimitive.content) }
            sql.version = version
        }
    }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun migrates1To2KeepingData() {
        val context = RuntimeEnvironment.getApplication() as Context
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        createFromSchema(file, 1)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL("INSERT INTO reading_sessions (bookId, startedAt, durationMs, pages, chars) VALUES (7, 1000, 600000, 12, 3400)")
            sql.execSQL("INSERT INTO extension_repos (url, name, addedAt) VALUES ('https://example.org/index.min.json', 'Repo', 1)")
            sql.execSQL(
                "INSERT INTO catalog_items (sourceId, url, title, genres, status, type, favorite, addedAt, lastUpdateAt, initialized) " +
                    "VALUES (1, '/title/1', 'Title', '', 0, 'MANGA', 1, 1, 1, 1)",
            )
            sql.execSQL(
                "INSERT INTO chapters (itemId, url, name, number, uploadDate, read, bookmarked, lastPageRead, downloaded, isNew, sourceOrder, fetchedAt) " +
                    "VALUES (1, '/chapter/1', 'Chapter 1', 1, 0, 0, 0, 0, 0, 0, 0, 0)",
            )
        }

        val room = Room.databaseBuilder(context, YomuDatabase::class.java, file.path)
            .addMigrations(*YomuDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase // runs the migrations and Room's schema validation
            assertEquals(CURRENT_VERSION, db.version)
            db.query("SELECT bookId, durationMs, hidden, catalogItemId FROM reading_sessions").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(7L, c.getLong(0))
                assertEquals(600000L, c.getLong(1))
                assertEquals(0, c.getInt(2))
                assertTrue(c.isNull(3))
            }
            db.query("SELECT name, signingKey FROM extension_repos").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Repo", c.getString(0))
                assertTrue(c.isNull(1))
            }
            db.query("SELECT c.name, c.memo, i.memo FROM chapters c JOIN catalog_items i ON i.id = c.itemId").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Chapter 1", c.getString(0))
            }
        } finally {
            room.close()
            file.delete()
        }
    }

    /** 1.3.0: per-title chapter sort/filter, download retries and queue priority. */
    @Test
    fun migrates2To3KeepingData() {
        val context = RuntimeEnvironment.getApplication() as Context
        val file = context.getDatabasePath("migration-test-2.db").apply { parentFile?.mkdirs(); delete() }
        createFromSchema(file, 2)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL(
                "INSERT INTO catalog_items (sourceId, url, title, genres, status, type, favorite, addedAt, lastUpdateAt, initialized) " +
                    "VALUES (1, '/title/1', 'Title', '', 0, 'MANGA', 1, 1, 1, 1)",
            )
            sql.execSQL(
                "INSERT INTO downloads (kind, title, url, status, bytesDownloaded, bytesTotal, pagesDone, pagesTotal, sourceId, createdAt, updatedAt) " +
                    "VALUES ('CHAPTER', 'Chapter 1', '/chapter/1', 'QUEUED', 0, 0, 0, 0, 1, 5, 5)",
            )
        }

        val room = Room.databaseBuilder(context, YomuDatabase::class.java, file.path)
            .addMigrations(*YomuDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(CURRENT_VERSION, db.version)
            db.query("SELECT title, chapterFlags FROM catalog_items").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Title", c.getString(0))
                assertTrue(c.isNull(1))
            }
            db.query("SELECT title, status, attempts, priority FROM downloads").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Chapter 1", c.getString(0))
                assertEquals("QUEUED", c.getString(1))
                assertEquals(0, c.getInt(2))
                assertEquals(0, c.getInt(3))
            }
        } finally {
            room.close()
            file.delete()
        }
    }

    /** 1.5.0: online library categories (collections of kind CATALOG) become tabs shared with books. */
    @Test
    fun migrates3To4TurningCategoriesIntoTabs() {
        val context = RuntimeEnvironment.getApplication() as Context
        val file = context.getDatabasePath("migration-test-3.db").apply { parentFile?.mkdirs(); delete() }
        createFromSchema(file, 3)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL("INSERT INTO collections (id, name, kind, sortOrder) VALUES (1, 'Favourites', 'BOOK', 0)")
            sql.execSQL("INSERT INTO collections (id, name, kind, sortOrder) VALUES (2, 'Favourites', 'CATALOG', 0)")
            sql.execSQL("INSERT INTO collections (id, name, kind, sortOrder) VALUES (3, 'Reading', 'CATALOG', 1)")
            listOf(1 to 2, 2 to 3, 3 to null).forEach { (id, category) ->
                sql.execSQL(
                    "INSERT INTO catalog_items (id, sourceId, url, title, genres, status, type, favorite, categoryId, addedAt, lastUpdateAt, initialized) " +
                        "VALUES ($id, 1, '/title/$id', 'Title $id', '', 0, 'MANGA', 1, ${category ?: "NULL"}, 1, 1, 1)",
                )
            }
        }

        val room = Room.databaseBuilder(context, YomuDatabase::class.java, file.path)
            .addMigrations(*YomuDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(CURRENT_VERSION, db.version)
            db.query("SELECT COUNT(*) FROM collections WHERE kind = 'CATALOG'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            val tabs = mutableMapOf<Long, String>()
            db.query("SELECT id, name FROM collections ORDER BY id").use { c -> while (c.moveToNext()) tabs[c.getLong(0)] = c.getString(1) }
            assertEquals(mapOf(1L to "Favourites", 3L to "Reading"), tabs) // the CATALOG "Favourites" merged into the book tab
            val members = mutableSetOf<Pair<Long, Long>>()
            db.query("SELECT itemId, collectionId FROM catalog_collections").use { c -> while (c.moveToNext()) members += c.getLong(0) to c.getLong(1) }
            assertEquals(setOf(1L to 1L, 2L to 3L), members)
            db.query("SELECT COUNT(*) FROM catalog_items WHERE categoryId IS NOT NULL").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM catalog_items").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(3, c.getInt(0))
            }
        } finally {
            room.close()
            file.delete()
        }
    }

    /** 2.0.0: tracking service entries; existing titles and chapters stay. */
    @Test
    fun migrates4To5AddingTracks() {
        val context = RuntimeEnvironment.getApplication() as Context
        val file = context.getDatabasePath("migration-test-4.db").apply { parentFile?.mkdirs(); delete() }
        createFromSchema(file, 4)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL(
                "INSERT INTO catalog_items (id, sourceId, url, title, genres, status, type, favorite, addedAt, lastUpdateAt, initialized) " +
                    "VALUES (1, 1, '/title/1', 'Title', '', 0, 'MANGA', 1, 1, 1, 1)",
            )
        }
        val room = Room.databaseBuilder(context, YomuDatabase::class.java, file.path)
            .addMigrations(*YomuDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(CURRENT_VERSION, db.version)
            db.execSQL(
                "INSERT INTO tracks (itemId, service, remoteId, libraryId, title, url, status, score, lastChapterRead, totalChapters, startedAt, finishedAt, updatedAt, pending) " +
                    "VALUES (1, 2, 30013, 0, 'Title', 'https://anilist.co/manga/30013', 1, 8.5, 12, 0, 0, 0, 5, 0)",
            )
            db.query("SELECT t.remoteId, i.title FROM tracks t JOIN catalog_items i ON i.id = t.itemId").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(30013L, c.getLong(0))
                assertEquals("Title", c.getString(1))
            }
            db.execSQL("DELETE FROM catalog_items WHERE id = 1")
            db.query("SELECT COUNT(*) FROM tracks").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0)) // removed with the title
            }
        } finally {
            room.close()
            file.delete()
        }
    }

    @Test
    fun migrates5To6PreservingOnlineTracks() {
        val context = RuntimeEnvironment.getApplication() as Context
        val file = context.getDatabasePath("migration-test-5.db").apply { parentFile?.mkdirs(); delete() }
        createFromSchema(file, 5)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL("INSERT INTO catalog_items (id, sourceId, url, title, genres, status, type, favorite, addedAt, lastUpdateAt, initialized) VALUES (1, 1, '/one', 'One', '', 0, 'MANGA', 1, 1, 1, 1)")
            sql.execSQL("INSERT INTO tracks (id,itemId,service,remoteId,libraryId,title,url,status,score,lastChapterRead,totalChapters,startedAt,finishedAt,updatedAt,pending) VALUES (17,1,2,55,66,'One','',1,8,12,20,10,0,50,1)")
        }
        val room = Room.databaseBuilder(context, YomuDatabase::class.java, file.path).addMigrations(*YomuDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(CURRENT_VERSION, db.version)
            db.query("SELECT id,itemId,bookId,remoteId,lastChapterRead,pending FROM tracks").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(17L,c.getLong(0)); assertEquals(1L,c.getLong(1)); assertTrue(c.isNull(2))
                assertEquals(55L,c.getLong(3)); assertEquals(12.0,c.getDouble(4),0.001); assertEquals(1,c.getInt(5))
            }
        } finally { room.close(); file.delete() }
    }

    @Test
    fun migrates6To7WithoutChangingBooks() {
        val context = RuntimeEnvironment.getApplication() as Context
        val file = context.getDatabasePath("migration-test-6.db").apply { parentFile?.mkdirs(); delete() }
        createFromSchema(file, 6)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL("INSERT INTO books (id,title,sortTitle,authorsText,format,uri,isLinked,fileSize,status,isManga,addedAt,progress) VALUES (7,'Book','Book','','TXT','file:///book',0,10,'READING',0,1,0.5)")
        }
        val room = Room.databaseBuilder(context, YomuDatabase::class.java, file.path).addMigrations(*YomuDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val sql = room.openHelper.writableDatabase
            assertEquals(CURRENT_VERSION, sql.version)
            sql.query("SELECT title,progress FROM books WHERE id=7").use { c -> assertTrue(c.moveToFirst()); assertEquals("Book", c.getString(0)); assertEquals(.5f, c.getFloat(1), .001f) }
            sql.query("SELECT count(*) FROM folder_sync_bases").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        } finally { room.close(); file.delete() }
    }

    private companion object {
        const val CURRENT_VERSION = 7
    }
}

/** A backup made by 1.0 (rows without the columns added later) restores into a fresh install of the current version. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupCompatibilityTest {
    @Test
    fun restoresBackupFromVersion1() {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, YomuDatabase::class.java).allowMainThreadQueries().build()
        try {
            val backup = """
                {"format":"yomu-backup","version":1,"createdAt":1,"filesDir":"/data/user/0/app.yomu.reader/files","tables":{
                  "reading_sessions":[{"id":1,"bookId":null,"startedAt":1000,"durationMs":600000,"pages":12,"chars":3400}],
                  "extension_repos":[{"url":"https://example.org/index.min.json","name":"Repo","addedAt":1}]
                }}
            """.trimIndent()
            val zip = java.io.ByteArrayOutputStream().also { bytes ->
                java.util.zip.ZipOutputStream(bytes).use { z ->
                    z.putNextEntry(java.util.zip.ZipEntry("backup.json"))
                    z.write(backup.toByteArray())
                    z.closeEntry()
                }
            }.toByteArray()
            val manager = app.yomu.data.backup.BackupManager(context, db, app.yomu.core.datastore.AppSettings(context))
            val result = kotlinx.coroutines.runBlocking { manager.restore(zip.inputStream()) }
            assertEquals(2, result.rows)
            db.openHelper.readableDatabase.query("SELECT durationMs, hidden FROM reading_sessions").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(600000L, c.getLong(0))
                assertEquals(0, c.getInt(1))
            }
        } finally {
            db.close()
        }
    }

    /** A 1.4 backup keeps online categories in collections of kind CATALOG: they come back as tabs. */
    @Test
    fun restoresCategoriesFromVersion14AsTabs() {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, YomuDatabase::class.java).allowMainThreadQueries().build()
        try {
            val backup = """
                {"format":"yomu-backup","version":1,"createdAt":1,"filesDir":"/data/user/0/app.yomu.reader/files","tables":{
                  "collections":[{"id":5,"name":"Reading","kind":"CATALOG","sortOrder":0}],
                  "catalog_items":[{"id":1,"sourceId":1,"url":"/title/1","title":"Title","genres":"","status":0,"type":"MANGA",
                    "favorite":1,"categoryId":5,"addedAt":1,"lastUpdateAt":1,"initialized":1}]
                }}
            """.trimIndent()
            val zip = java.io.ByteArrayOutputStream().also { bytes ->
                java.util.zip.ZipOutputStream(bytes).use { z ->
                    z.putNextEntry(java.util.zip.ZipEntry("backup.json"))
                    z.write(backup.toByteArray())
                    z.closeEntry()
                }
            }.toByteArray()
            val manager = app.yomu.data.backup.BackupManager(context, db, app.yomu.core.datastore.AppSettings(context))
            kotlinx.coroutines.runBlocking { manager.restore(zip.inputStream()) }
            val sql = db.openHelper.readableDatabase
            sql.query("SELECT name, kind FROM collections").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Reading", c.getString(0))
                assertEquals("BOOK", c.getString(1))
            }
            sql.query("SELECT itemId, collectionId FROM catalog_collections").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals(5L, c.getLong(1))
            }
            sql.query("SELECT categoryId FROM catalog_items").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
            }
        } finally {
            db.close()
        }
    }
}
