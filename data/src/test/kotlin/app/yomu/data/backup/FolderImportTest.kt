package app.yomu.data.backup

import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.data.library.CoverStore
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.data.repository.BookRepositoryImpl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderImportTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = YomuDatabase.build(context, inMemory = true)
    private val dir = File(context.cacheDir, "folder-import-${UUID.randomUUID()}").apply { mkdirs() }
    private val covers = CoverStore(context)
    private val settings = AppSettings(context)
    private val importer = ImportService(context, BookRepositoryImpl(context, db, covers), settings, covers)
    @After fun close() { db.close(); dir.deleteRecursively() }
    @Test fun verifiedFolderKeepsItsStableIdentityAndDoesNotDuplicate() = runBlocking {
        File(dir, "1.jpg").writeText("page bytes")
        val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
        val result = importer.importSyncedFolder(dir, id, "Manga")
        assertTrue(result is ImportOutcome.Added)
        val book = db.books().all().single()
        assertEquals(id, book.fileHash)
        assertEquals("FOLDER", book.format)
        assertEquals(id, FolderRevisionCommit.localRevision(db, id)!!.id)
        assertEquals("1.jpg", FolderRevisionCommit.localRevision(db, id)!!.pages.single().path)
        assertFalse(book.isLinked)
        assertTrue(importer.importSyncedFolder(dir, id, "Manga") is ImportOutcome.Duplicate)
        assertEquals(1, db.books().all().size)
    }
    @Test fun invalidFolderIdentityDoesNotEnterLibrary() = runBlocking {
        assertTrue(importer.importSyncedFolder(dir, "dir:/private/path", "Manga") is ImportOutcome.Failed)
        assertTrue(db.books().all().isEmpty())
    }
    @Test fun concurrentFolderImportsReturnOneBookAndOneDuplicate() = runBlocking {
        File(dir, "1.jpg").writeText("page bytes")
        val uri = android.net.Uri.fromFile(dir)
        val results = kotlinx.coroutines.coroutineScope {
            listOf(
                async { importer.importFolder(uri, "Manga") },
                async { importer.importFolder(uri, "Manga") },
            ).map { it.await() }
        }
        assertEquals(1, results.count { it is ImportOutcome.Added })
        assertEquals(1, results.count { it is ImportOutcome.Duplicate })
        assertEquals(1, db.books().all().size)
    }

    @Test fun newFolderGetsStableIdentityWhenFileSyncIsEnabled() = runBlocking {
        settings.syncBookFiles.set(true)
        try {
            File(dir, "1.jpg").writeText("page bytes")
            val result = importer.importFolder(android.net.Uri.fromFile(dir), "Manga")
            assertTrue(result is ImportOutcome.Added)
            val book = db.books().all().single()
            assertTrue(FolderManifestCodec.validId(book.fileHash!!))
            assertEquals(book.fileHash, FolderRevisionCommit.localRevision(db, book.fileHash!!)!!.id)
            assertTrue(importer.importFolder(android.net.Uri.fromFile(dir), "Manga") is ImportOutcome.Duplicate)
        } finally { settings.syncBookFiles.set(false) }
    }

}
