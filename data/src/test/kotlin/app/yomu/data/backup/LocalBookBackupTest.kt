package app.yomu.data.backup

import android.content.Context
import android.content.ContextWrapper
import app.yomu.core.database.BookEntity
import app.yomu.core.database.ReadingProgressEntity
import app.yomu.core.database.TrackEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.database.FolderSyncBaseEntity
import app.yomu.core.datastore.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalBookBackupTest {
    @Test fun backupRestoresSyncedFilesAndLocalTrack() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Context
        val root = File(app.cacheDir, "backup-local-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val first = YomuDatabase.build(context, inMemory = true)
        val second = YomuDatabase.build(context, inMemory = true)
        try {
            val file = File(context.filesDir, "synced-books/example.txt").apply { parentFile!!.mkdirs(); writeText("book contents") }
            val bookId = first.books().insert(BookEntity(title = "Example", sortTitle = "Example", authorsText = "", seriesName = null,
                seriesId = null, seriesIndex = null, language = null, description = null, publisher = null, format = "TXT",
                uri = android.net.Uri.fromFile(file).toString(), isLinked = false, fileSize = file.length(), fileHash = "hash",
                coverPath = null, status = "READING", isManga = false, addedAt = 1, lastReadAt = null, progress = .2f, readerSettingsJson = null))
            first.tracks().insert(TrackEntity(itemId = null, bookId = bookId, service = 2, remoteId = 123, title = "Example", url = "", status = 1, pending = true))
            val out = ByteArrayOutputStream()
            BackupManager(context, first, AppSettings(context)).create(out, includeFiles = true)
            val archive = out.toByteArray()
            val names = mutableListOf<String>()
            ZipInputStream(archive.inputStream()).use { zip -> while (true) { val e = zip.nextEntry ?: break; names += e.name } }
            assertTrue(names.contains("files/synced-books/example.txt"))
            file.delete()
            second.folderSyncBases().upsert(FolderSyncBaseEntity("target", "old", "old revision"))
            BackupManager(context, second, AppSettings(context)).restore(archive.inputStream())
            assertTrue(second.folderSyncBases().forTarget("target").isEmpty())
            assertEquals("book contents", file.readText())
            val restored = second.tracks().getBook(bookId, 2)!!
            assertEquals(123L, restored.remoteId); assertTrue(restored.pending); assertNull(restored.itemId)
        } finally { first.close(); second.close(); root.deleteRecursively() }
    }
    @Test fun backupRestoresFolderPositionRevisionButNotRemoteMergeBase() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Context
        val root = File(app.cacheDir, "backup-folder-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val first = YomuDatabase.build(context, inMemory = true)
        val second = YomuDatabase.build(context, inMemory = true)
        try {
            val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
            val folder = File(context.filesDir, "synced-books/folder-a").apply { mkdirs() }
            File(folder, "2.jpg").writeText("two"); File(folder, "3.jpg").writeText("three")
            val bookId = first.books().insert(BookEntity(title = "Manga", sortTitle = "Manga", authorsText = "", seriesName = null,
                seriesId = null, seriesIndex = null, language = null, description = null, publisher = null, format = "FOLDER",
                uri = android.net.Uri.fromFile(folder).toString(), isLinked = false, fileSize = 0, fileHash = id,
                coverPath = null, status = "READING", isManga = true, addedAt = 1, lastReadAt = 1, progress = 1f, readerSettingsJson = null))
            first.progress().upsert(ReadingProgressEntity(bookId, "{\"chapter\":1}", 1f, 10))
            val before = FolderSnapshot.snapshot(context, android.net.Uri.fromFile(folder), id, "Manga")
            FolderRevisionCommit.initialize(first, "target", bookId, before)
            val out = ByteArrayOutputStream()
            BackupManager(context, first, AppSettings(context)).create(out, includeFiles = true)
            folder.deleteRecursively()
            BackupManager(context, second, AppSettings(context)).restore(out.toByteArray().inputStream())
            assertEquals(before, FolderRevisionCommit.localRevision(second, id))
            assertTrue(second.folderSyncBases().forTarget("target").isEmpty())
            assertEquals("three", File(folder, "3.jpg").readText())
            File(folder, "1.jpg").writeText("one")
            val after = FolderSnapshot.snapshot(context, android.net.Uri.fromFile(folder), id, "Manga")
            FolderRevisionCommit.initialize(second, "target", bookId, before)
            FolderRevisionCommit.apply(second, "target", bookId, before, after, android.net.Uri.fromFile(folder).toString())
            assertTrue(second.progress().get(bookId)!!.locatorJson.contains("\"chapter\":2"))
        } finally { first.close(); second.close(); root.deleteRecursively() }
    }

    @Test fun oldBackupWithUnqualifiedFolderPositionsDoesNotGuessARevision() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Context
        val root = File(app.cacheDir, "backup-old-folder-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val first = YomuDatabase.build(context, inMemory = true)
        val second = YomuDatabase.build(context, inMemory = true)
        try {
            val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
            val folder = File(context.filesDir, "synced-books/folder-a").apply { mkdirs() }
            File(folder, "1.jpg").writeText("one")
            first.books().insert(BookEntity(title = "Manga", sortTitle = "Manga", authorsText = "", seriesName = null,
                seriesId = null, seriesIndex = null, language = null, description = null, publisher = null, format = "FOLDER",
                uri = android.net.Uri.fromFile(folder).toString(), isLinked = false, fileSize = 0, fileHash = id,
                coverPath = null, status = "READING", isManga = true, addedAt = 1, lastReadAt = 1, progress = 0f, readerSettingsJson = null))
            val out = ByteArrayOutputStream(); BackupManager(context, first, AppSettings(context)).create(out, includeFiles = true)
            BackupManager(context, second, AppSettings(context)).restore(out.toByteArray().inputStream())
            assertTrue(runCatching { FolderRevisionCommit.localRevision(second, id) }.exceptionOrNull() is java.io.IOException)
            assertEquals("one", File(folder, "1.jpg").readText())
        } finally { first.close(); second.close(); root.deleteRecursively() }
    }

    @Test fun restoreDoesNotReplaceExistingFolderPageRevision() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Context
        val root = File(app.cacheDir, "backup-conflicting-revision-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val first = YomuDatabase.build(context, inMemory = true)
        val second = YomuDatabase.build(context, inMemory = true)
        try {
            val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
            val folder = File(context.filesDir, "synced-books/folder-a").apply { mkdirs() }
            File(folder, "1.jpg").writeText("one")
            val book = BookEntity(title = "Manga", sortTitle = "Manga", authorsText = "", seriesName = null,
                seriesId = null, seriesIndex = null, language = null, description = null, publisher = null, format = "FOLDER",
                uri = android.net.Uri.fromFile(folder).toString(), isLinked = false, fileSize = 0, fileHash = id,
                coverPath = null, status = "READING", isManga = true, addedAt = 1, lastReadAt = 1, progress = 0f, readerSettingsJson = null)
            val bookId = first.books().insert(book)
            val before = FolderSnapshot.snapshot(context, android.net.Uri.fromFile(folder), id, "Manga")
            FolderRevisionCommit.initializeLocal(first, bookId, before)
            val out = ByteArrayOutputStream(); BackupManager(context, first, AppSettings(context)).create(out, includeFiles = true)
            BackupManager(context, second, AppSettings(context)).restore(out.toByteArray().inputStream())
            val changed = before.copy(pages = before.pages + FolderPage("2.jpg", "a".repeat(64), 1))
            val stored = FolderManifestCodec.encode(FolderManifest(folders = listOf(changed))).decodeToString()
            second.folderSyncBases().upsert(FolderSyncBaseEntity(FolderRevisionCommit.LOCAL, id, stored))
            val error = runCatching { BackupManager(context, second, AppSettings(context)).restore(out.toByteArray().inputStream()) }.exceptionOrNull()
            assertTrue(error is java.io.IOException && error.message!!.contains("revision conflicts"))
            assertEquals(changed, FolderRevisionCommit.localRevision(second, id))
            assertEquals(bookId, second.books().all().single().id)
        } finally { first.close(); second.close(); root.deleteRecursively() }
    }

}
