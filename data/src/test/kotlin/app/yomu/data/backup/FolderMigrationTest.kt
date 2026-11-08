package app.yomu.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import app.yomu.core.database.BookEntity
import app.yomu.core.database.HighlightEntity
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
 * A legacy (`dir:`) image folder is migrated onto a stable identity: the shared metadata, the
 * folder index (with a replacement marker), the merge bases and the local rows are rewritten;
 * pages, progress, positions and annotations survive. A second device that imported the same
 * folder as a legacy copy follows the marker instead of creating a second identity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderMigrationTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val root = File(app.cacheDir, "folder-migration-tests-${UUID.randomUUID()}").apply { mkdirs() }
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

        /** Imports with file sync off, so the folder receives the legacy `dir:` identity. */
        suspend fun importLegacyFolder(source: File): Long {
            settings.syncBookFiles.set(false)
            val result = importer.importFolder(Uri.fromFile(source), source.name)
            assertTrue(result.toString(), result is ImportOutcome.Added)
            val id = (result as ImportOutcome.Added).bookId
            assertTrue(db.books().get(id)!!.fileHash!!.startsWith("dir:"))
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

    @After fun close() {
        runBlocking { devices.forEach { it.settings.syncBookFiles.set(false) } }
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

    private fun folderWithImages(name: String, seed: String): File {
        val dir = File(root, "source-$name").apply { mkdirs() }
        for (i in 1..3) File(dir, "page-$i.jpg").writeBytes("image $seed $i".toByteArray())
        return dir
    }

    @Test fun legacyFolderMigratesAndTheOtherDeviceFollowsTheMarker() = runBlocking {
        val a = Device("a"); val b = Device("b"); val server = Server()
        val source = folderWithImages("manga", "seed")
        val aId = a.importLegacyFolder(source)
        a.db.progress().upsert(ReadingProgressEntity(aId, "{\"chapter\":1}", 0.5f, 1000))
        a.db.books().updateProgress(aId, 0.5f, 1000)
        a.db.annotations().insertHighlight(HighlightEntity(0, aId, "{}", 1, "page note", "yellow", "HIGHLIGHT", false, false, "", null, 1000, 1000))
        val legacyId = a.db.books().get(aId)!!.fileHash!!

        // File sync refuses legacy folders until the migration action is used.
        try { a.sync(server); fail("Legacy folder must block file sync") }
        catch (e: FolderSyncProblem) { assertEquals(FolderSyncProblem.Kind.LEGACY, e.kind); assertEquals(aId.toString(), e.target) }

        FolderAdoption(a.context, a.db, server, server.id).migrate(aId)
        val stableId = a.db.books().get(aId)!!.fileHash!!
        assertTrue(FolderManifestCodec.validId(stableId) && stableId != legacyId)
        // Progress and annotations stayed on the book; the position baseline followed the identity.
        assertEquals(0.5f, a.db.books().get(aId)!!.progress, 0.0001f)
        assertEquals(1, a.db.annotations().allHighlights().size)
        assertTrue(a.db.folderSyncBases().forFolder(stableId).any { it.targetId == FolderRevisionCommit.LOCAL })
        assertTrue(a.db.folderSyncBases().forFolder(legacyId).isEmpty())

        // The migrated folder syncs: pages upload, metadata carries the new identity.
        a.sync(server)
        val manifest = FolderManifestCodec.decode(server.index!!)
        assertEquals(stableId, manifest.replaced[legacyId])
        assertTrue(manifest.folders.single { it.id == stableId }.pages.size == 3)
        val metadata = FolderMetadataSnapshotCodec.decode(server.metadata!!)
        assertTrue(FolderMetadataSnapshotCodec.folderHashes(metadata.state).contains(stableId))

        // B imported the same folder independently (legacy identity): it follows the marker.
        val bId = b.importLegacyFolder(source)
        b.sync(server)
        assertEquals(stableId, b.db.books().get(bId)!!.fileHash!!)
        assertTrue(b.db.folderSyncBases().forFolder(stableId).any { it.targetId == FolderRevisionCommit.LOCAL })
        // A second run converges without new identities or copies.
        b.sync(server)
        assertEquals(1, FolderManifestCodec.decode(server.index!!).folders.size)
        assertEquals(1, FolderMetadataSnapshotCodec.decode(server.metadata!!).state.books.size)
    }
}
