package app.yomu.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import app.yomu.core.database.BookEntity
import app.yomu.core.database.ReadingProgressEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.data.library.CoverStore
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.data.repository.BookRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Page changes of a stable folder no longer stop the whole sync: the ambiguous folder is skipped
 * with a recorded problem and the conflicts screen publishes or accepts a version explicitly,
 * resetting only what cannot be remapped (visible report, no silent deletions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderPageChangesTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val root = File(app.cacheDir, "folder-page-change-tests-${UUID.randomUUID()}").apply { mkdirs() }
    private val devices = mutableListOf<Device>()

    private inner class Device(name: String) {
        private val dir = File(root, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
        }
        val settings = AppSettings(context)
        val db = YomuDatabase.build(context, inMemory = true)
        private val covers = CoverStore(context)
        val importer = ImportService(context, BookRepositoryImpl(context, db, covers), settings, covers)
        val manager = SyncManager(context, db, settings, { okhttp3.OkHttpClient() }, importer)

        suspend fun addStableFolder(source: File): Long {
            settings.syncBookFiles.set(true)
            val result = importer.importFolder(Uri.fromFile(source), source.name)
            assertTrue(result.toString(), result is ImportOutcome.Added)
            val id = (result as ImportOutcome.Added).bookId
            assertTrue(db.books().get(id)!!.fileHash!!.let(FolderManifestCodec::validId))
            return id
        }

        init { devices += this }
    }

    private class Server : FolderFilesTransport, TrackLinkTransport, SyncBackend {
        override val id = "target"
        var metadata: ByteArray? = null
        var metadataRevision = 0
        var index: ByteArray? = null
        var indexRevision = 0
        var links: ByteArray? = null
        var linksRevision = 0
        val blobs = HashMap<String, ByteArray>()

        private fun requireCas(current: ByteArray?, revision: Int, previous: SyncFile?) {
            if (previous?.tag != current?.let { "\"$revision\"" }) throw SyncConflictException()
        }
        override suspend fun check() = Unit
        override suspend fun read(): SyncFile? = metadata?.let { SyncFile(it.copyOf(), "\"$metadataRevision\"") }
        override suspend fun write(bytes: ByteArray, previous: SyncFile?) {
            requireCas(metadata, metadataRevision, previous)
            metadata = bytes.copyOf(); metadataRevision++
        }
        override suspend fun readBookIndex(): SyncFile? = null
        override suspend fun writeBookIndex(bytes: ByteArray, previous: SyncFile?) { }
        override suspend fun uploadBook(sha256: String, file: File) { blobs[sha256] = file.readBytes() }
        override suspend fun <T> readBook(sha256: String, consume: (InputStream) -> T): T =
            consume((blobs[sha256] ?: throw java.io.IOException("missing blob")).inputStream())
        override suspend fun readFolderIndex(): SyncFile? = index?.let { SyncFile(it.copyOf(), "\"$indexRevision\"") }
        override suspend fun writeFolderIndex(bytes: ByteArray, previous: SyncFile?) {
            requireCas(index, indexRevision, previous)
            index = bytes.copyOf(); indexRevision++
        }
        override suspend fun readTrackLinks(): SyncFile? = links?.let { SyncFile(it.copyOf(), "\"$linksRevision\"") }
        override suspend fun writeTrackLinks(bytes: ByteArray, previous: SyncFile?) {
            requireCas(links, linksRevision, previous)
            links = bytes.copyOf(); linksRevision++
        }
    }

    @After fun close(): Unit = runBlocking {
        devices.forEach { it.settings.syncBookFiles.set(false) }
        devices.forEach { it.db.close() }
        root.deleteRecursively()
    }

    private suspend fun Device.sync(server: Server) {
        settings.syncBookFiles.set(true)
        var attempt = 0
        while (true) {
            try { manager.runOnce(server); break }
            catch (e: SyncConflictException) { if (++attempt >= 3) throw e }
        }
    }

    private fun Device.problemKinds(): List<String> = SyncProblemStore(context).load().map { it.kind }

    private fun folder(name: String, pages: Int, pageText: (Int) -> String): File {
        val dir = File(root, name).apply { mkdirs() }
        for (i in 1..pages) File(dir, "page-$i.jpg").writeBytes(pageText(i).toByteArray())
        return dir
    }

    @Test fun pageChangeIsSkippedThenPublishedAndAcceptedExplicitly() = runBlocking {
        val a = Device("a"); val server = Server()
        val source = folder("manga", 3) { "original page $it" }
        val bookId = a.addStableFolder(source)
        a.db.progress().upsert(ReadingProgressEntity(bookId, "{\"chapter\":1}", 0.66f, 1000)) // reading page-2
        a.db.books().updateProgress(bookId, 0.66f, 1000)
        a.sync(server)
        val before = FolderManifestCodec.decode(server.index!!).folders.single()

        // The user replaces page 2's image in place; positions on it cannot be proven.
        File(source, "page-2.jpg").writeBytes("edited page 2".toByteArray())
        a.sync(server)
        assertTrue(a.problemKinds().contains("folder_changed_page"))
        // Nothing was published behind the user's back.
        assertEquals(before.pages, FolderManifestCodec.decode(server.index!!).folders.single().pages)

        // "Publish my version": the new pages go out and the reading position on the changed
        // page restarts the book; the commit is visible in the revision bases.
        val report = FolderSyncCoordinator(a.context, a.db, a.importer).publishLocalVersion(bookId, server, server.id)
        assertTrue(report.any { it.endsWith("page-2.jpg") })
        assertEquals(0f, a.db.progress().all().single().progress, 0.0001f)
        val published = FolderManifestCodec.decode(server.index!!).folders.single()
        assertEquals(3, published.pages.size)
        assertTrue(published.pages.any { it.path == "page-2.jpg" && it.sha256 != before.pages.single { p -> p.path == "page-2.jpg" }.sha256 })
        assertEquals(0, FolderSyncCoordinator(a.context, a.db, a.importer).sync(server, server.id).pendingEdits.size)

        // The other side of the flow: a fresh device downloads the published folder, page 3 is
        // then removed remotely; the local position on it reports instead of aborting.
        val b = Device("b")
        b.sync(server)
        val bId = b.db.books().all().single().id
        b.db.progress().upsert(ReadingProgressEntity(bId, "{\"chapter\":2}", 1.0f, 1000)) // reading page-3
        b.sync(server)
        File(source, "page-3.jpg").delete()
        a.sync(server) // A publishes the removal.
        val publishedAfterRemoval = FolderManifestCodec.decode(server.index!!).folders.single()
        assertEquals(2, publishedAfterRemoval.pages.size)
        b.sync(server)
        assertTrue(b.problemKinds().contains("folder_missing_page"))
        val accepted = FolderSyncCoordinator(b.context, b.db, b.importer).acceptRemoteVersion(bId, server, server.id)
        assertTrue(accepted.any { it.endsWith("page-3.jpg") })
        // The position on the removed page restarted the book, visibly reported.
        assertEquals(0f, b.db.progress().all().single().progress, 0.0001f)
        assertEquals(2, FolderRevisionCommit.localRevision(b.db, b.db.books().get(bId)!!.fileHash!!)!!.pages.size)
    }
}
