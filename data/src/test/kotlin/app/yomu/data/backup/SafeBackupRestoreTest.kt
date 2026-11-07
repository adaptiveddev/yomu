package app.yomu.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import app.yomu.core.database.BookEntity
import app.yomu.core.database.ReadingProgressEntity
import app.yomu.core.database.YomuDatabase
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
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafeBackupRestoreTest {
    private val app = RuntimeEnvironment.getApplication() as Context
    private fun context(root: File): Context = object : ContextWrapper(app) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
    private suspend fun book(db: YomuDatabase, file: File, title: String): Long = db.books().insert(BookEntity(
        title = title, sortTitle = title, authorsText = "", seriesName = null, seriesId = null, seriesIndex = null,
        language = null, description = null, publisher = null, format = "TXT", uri = Uri.fromFile(file).toString(),
        isLinked = false, fileSize = file.length(), fileHash = title, coverPath = null, status = "READING",
        isManga = false, addedAt = 1, lastReadAt = 1, progress = .5f, readerSettingsJson = null))

    @Test fun conflictingExistingFileIsNeverOverwritten() = runBlocking {
        val root = File(app.cacheDir, "backup-safe-${UUID.randomUUID()}"); val source = context(File(root, "a")); val dest = context(File(root, "b"))
        val a = YomuDatabase.build(source, true); val b = YomuDatabase.build(dest, true)
        try {
            val original = File(source.filesDir, "books/book.txt").apply { parentFile.mkdirs(); writeText("backup content") }
            book(a, original, "backup")
            val archive = ByteArrayOutputStream().also { BackupManager(source, a, AppSettings(source)).create(it, true) }.toByteArray()
            val existing = File(dest.filesDir, "books/book.txt").apply { parentFile.mkdirs(); writeText("newer local content") }
            val localId = book(b, existing, "existing")
            b.progress().upsert(ReadingProgressEntity(localId, "{}", .5f, 2))
            assertTrue(runCatching { BackupManager(dest, b, AppSettings(dest)).restore(archive.inputStream()) }.exceptionOrNull() is IOException)
            assertEquals("newer local content", existing.readText())
            assertEquals("existing", b.books().all().single().title)
            assertEquals(.5f, b.progress().get(localId)!!.progress, .001f)
        } finally { a.close(); b.close(); root.deleteRecursively() }
    }

    @Test fun databaseIdConflictDoesNotReplaceExistingBookOrProgress() = runBlocking {
        val root = File(app.cacheDir, "backup-safe-${UUID.randomUUID()}"); val source = context(File(root, "a")); val dest = context(File(root, "b"))
        val a = YomuDatabase.build(source, true); val b = YomuDatabase.build(dest, true)
        try {
            val saved = File(source.filesDir, "books/from-backup.txt").apply { parentFile.mkdirs(); writeText("backup content") }
            book(a, saved, "backup")
            val archive = ByteArrayOutputStream().also { BackupManager(source, a, AppSettings(source)).create(it, true) }.toByteArray()
            val existing = File(dest.filesDir, "books/existing.txt").apply { parentFile.mkdirs(); writeText("local content") }
            val localId = book(b, existing, "existing")
            b.progress().upsert(ReadingProgressEntity(localId, "{}", .5f, 2))
            val error = runCatching { BackupManager(dest, b, AppSettings(dest)).restore(archive.inputStream()) }.exceptionOrNull()
            if (error !is IOException) throw AssertionError("Expected safe database conflict", error)
            assertEquals("existing", b.books().all().single().title)
            assertEquals("local content", existing.readText())
            assertEquals(.5f, b.progress().get(localId)!!.progress, .001f)
        } finally { a.close(); b.close(); root.deleteRecursively() }
    }

    @Test fun unsafeArchivePathCannotOverwriteSyncState() = runBlocking {
        val root = File(app.cacheDir, "backup-safe-${UUID.randomUUID()}"); val dest = context(root)
        val db = YomuDatabase.build(dest, true)
        try {
            val sync = File(dest.filesDir, "sync/base.json").apply { parentFile.mkdirs(); writeText("original") }
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("backup.json")); z.write("{\"format\":\"yomu-backup\",\"version\":1,\"tables\":{}}".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("files/books/../sync/base.json")); z.write("changed".toByteArray()); z.closeEntry()
            }
            assertTrue(runCatching { BackupManager(dest, db, AppSettings(dest)).restore(out.toByteArray().inputStream()) }.exceptionOrNull() is IOException)
            assertEquals("original", sync.readText())
        } finally { db.close(); root.deleteRecursively() }
    }
}
