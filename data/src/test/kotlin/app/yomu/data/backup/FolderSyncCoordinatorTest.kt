package app.yomu.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import app.yomu.core.database.BookEntity
import app.yomu.core.database.BookmarkEntity
import app.yomu.core.database.ReadingProgressEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.database.TrackEntity
import app.yomu.core.database.HighlightEntity
import app.yomu.core.datastore.AppSettings
import app.yomu.data.book.PageSources
import app.yomu.domain.model.Locator
import app.yomu.data.library.CoverStore
import app.yomu.data.library.ImportService
import app.yomu.data.library.ImportOutcome
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
import java.io.IOException
import java.io.InputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderSyncCoordinatorTest {
    private val app = RuntimeEnvironment.getApplication()
    private val root = File(app.cacheDir, "folder-coordinator-${UUID.randomUUID()}").apply { mkdirs() }
    private val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
    private val devices = mutableListOf<Device>()
    private inner class Device(name: String) {
        private val base = File(root, name)
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(base, "files").apply { mkdirs() }
            override fun getCacheDir() = File(base, "cache").apply { mkdirs() }
        }
        val db = YomuDatabase.build(context, inMemory = true)
        private val covers = CoverStore(context)
        private val settings = AppSettings(context)
        val books = BookRepositoryImpl(context, db, covers)
        private val importer = ImportService(context, books, settings, covers)
        val recorded = mutableListOf<SyncProblemInfo>()
        val sync = FolderSyncCoordinator(context, db, importer) { recorded += it }
        val manager = SyncManager(context, db, settings, { okhttp3.OkHttpClient() }, importer)
        suspend fun syncAll(server: Server, files: Boolean = true): Int {
            settings.syncBookFiles.set(files)
            return manager.runOnce(server)
        }
        suspend fun importUserFolder(dir: File, name: String = "Manga"): Long {
            settings.syncBookFiles.set(true)
            val result = importer.importFolder(Uri.fromFile(dir), name)
            assertTrue("Import failed: $result", result is ImportOutcome.Added)
            return (result as ImportOutcome.Added).bookId
        }
        suspend fun importUserFile(file: File): Long {
            val result = importer.importLocalFile(file, file.name)
            assertTrue("Import failed: $result", result is ImportOutcome.Added)
            return (result as ImportOutcome.Added).bookId
        }
        suspend fun resetSettings() { settings.syncBookFiles.set(false) }
        init { devices += this }
        suspend fun addFolder(dir: File): Long {
            val book = db.books().insert(BookEntity(title = "Manga", sortTitle = "Manga", authorsText = "", seriesName = null, seriesId = null,
                seriesIndex = null, language = null, description = null, publisher = null, format = "FOLDER", uri = Uri.fromFile(dir).toString(), isLinked = false,
                fileSize = 0, fileHash = id, coverPath = null, status = "READING", isManga = true, addedAt = 1, lastReadAt = 1, progress = 0f, readerSettingsJson = null))
            FolderRevisionCommit.initializeLocal(db, book, FolderSnapshot.snapshot(context, Uri.fromFile(dir), id, "Manga"))
            return book
        }
    }
    private class Server : FolderFilesTransport, TrackLinkTransport, SyncBackend {
        override val id = "target"
        var metadata: ByteArray? = null
        var metadataRevision = 0
        var failMetadataWrite = false
        var loseNextMetadataResponse = false
        override suspend fun check() = Unit
        override suspend fun read() = metadata?.let { SyncFile(it.copyOf(), "\"$metadataRevision\"") }
        override suspend fun write(bytes: ByteArray, previous: SyncFile?) {
            if (failMetadataWrite) throw SyncConflictException()
            if (previous?.tag != metadata?.let { "\"$metadataRevision\"" }) throw SyncConflictException()
            metadata = bytes.copyOf(); metadataRevision++
            if (loseNextMetadataResponse) {
                loseNextMetadataResponse = false
                throw IOException("Simulated lost response after metadata commit")
            }
        }
        var index: ByteArray? = null
        var revision = 0
        var trackIndex: ByteArray? = null
        var trackRevision = 0
        override suspend fun readTrackLinks() = trackIndex?.let { SyncFile(it.copyOf(), "\"$trackRevision\"") }
        override suspend fun writeTrackLinks(bytes: ByteArray, previous: SyncFile?) {
            if (previous?.tag != trackIndex?.let { "\"$trackRevision\"" }) throw SyncConflictException()
            trackIndex = bytes.copyOf(); trackRevision++
        }
        val blobs = mutableMapOf<String, ByteArray>()
        var interruptUpload = false
        var interruptDownload = false
        override suspend fun readFolderIndex() = index?.let { SyncFile(it.copyOf(), "\"$revision\"") }
        override suspend fun writeFolderIndex(bytes: ByteArray, previous: SyncFile?) {
            if (previous?.tag != index?.let { "\"$revision\"" }) throw SyncConflictException()
            index = bytes.copyOf(); revision++
        }
        var bookIndex: ByteArray? = null
        var bookRevision = 0
        override suspend fun readBookIndex(): SyncFile? = bookIndex?.let { SyncFile(it.copyOf(), "\"$bookRevision\"") }
        override suspend fun writeBookIndex(bytes: ByteArray, previous: SyncFile?) {
            if (previous?.tag != bookIndex?.let { "\"$bookRevision\"" }) throw SyncConflictException()
            bookIndex = bytes.copyOf(); bookRevision++
        }
        override suspend fun uploadBook(sha256: String, file: File) {
            val existing = blobs[sha256]
            if (existing == null) blobs[sha256] = file.readBytes() else BookFileSync.verifyStream(existing.inputStream(), sha256, file.length())
            if (interruptUpload) { interruptUpload = false; throw IOException("Lost upload response") }
        }
        override suspend fun <T> readBook(sha256: String, consume: (InputStream) -> T): T {
            val bytes = blobs[sha256] ?: throw IOException("missing")
            if (!interruptDownload) return consume(bytes.inputStream())
            interruptDownload = false
            return consume(object : InputStream() {
                var done = false
                override fun read(): Int {
                    if (done) throw IOException("Interrupted download")
                    done = true
                    return bytes[0].toInt() and 0xff
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    b[off] = read().toByte()
                    return 1
                }
            })
        }
    }
    @After fun close() { runBlocking { devices.firstOrNull()?.resetSettings() }; devices.forEach { it.db.close() }; root.deleteRecursively() }

    @Test fun removedBookmarkedPageIsSkippedWithAProblemUntilTheUserDecides() = runBlocking {
        val a = Device("preflight"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("one"); File(folder, "2.jpg").writeText("two")
        val book = a.addFolder(folder)
        a.db.annotations().insertBookmark(BookmarkEntity(0, book, "{\"chapter\":1}", "mark", 1f, 5))
        a.sync.sync(remote, "target")
        val before = remote.index!!.copyOf()
        File(folder, "2.jpg").delete()
        a.sync.sync(remote, "target")
        // The ambiguous folder is skipped with a recorded problem, not published blindly.
        assertTrue(a.db.books().get(book)!!.fileHash!!.let { a.recorded.any { p -> p.kind == "folder_missing_page" && p.target == book.toString() } })
        assertArrayEquals(before, remote.index)
        assertTrue(a.db.annotations().allBookmarks().single().locatorJson.contains("1"))
    }
    @Test fun replacingBookmarkedImageAtSameNameIsSkippedWithAProblemUntilTheUserDecides() = runBlocking {
        val a = Device("replacement"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        val image = File(folder, "1.jpg").apply { writeText("old page") }
        val book = a.addFolder(folder)
        a.db.annotations().insertBookmark(BookmarkEntity(0, book, "{\"chapter\":0}", "mark", 1f, 5))
        a.sync.sync(remote, "target")
        val before = remote.index!!.copyOf()
        val savedPosition = a.db.annotations().allBookmarks().single().locatorJson
        image.writeText("different image")
        a.sync.sync(remote, "target")
        assertTrue(a.recorded.any { it.kind == "folder_changed_page" && it.target == book.toString() })
        assertArrayEquals(before, remote.index)
        assertEquals(savedPosition, a.db.annotations().allBookmarks().single().locatorJson)
        // The explicit publication remaps what it can and keeps the annotation dangling.
        val report = a.sync.publishLocalVersion(book, remote, "target")
        assertTrue(report.isNotEmpty())
        assertEquals(savedPosition, a.db.annotations().allBookmarks().single().locatorJson)
    }

    @Test fun remotelyBookmarkedPageSurvivesAPublishedDeletionAsDangling() = runBlocking {
        val a = Device("preflight-a"); val b = Device("preflight-b"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("one"); File(folder, "2.jpg").writeText("two")
        a.importUserFolder(folder)
        a.syncAll(remote); b.syncAll(remote)
        val bId = b.db.books().all().single().id
        b.db.annotations().insertBookmark(BookmarkEntity(0, bId, "{\"chapter\":1}", "remote only", 1f, 15))
        b.syncAll(remote)
        assertTrue(a.db.annotations().allBookmarks().isEmpty())
        File(folder, "2.jpg").delete()
        a.syncAll(remote)
        // The deletion publishes; the other device's bookmark on the removed page survives in the
        // shared metadata as a dangling reference (its owner resolves it on their side).
        assertTrue(FolderMetadataSnapshotCodec.decode(remote.metadata!!).state.bookmarks.any { it.title == "remote only" })
        assertEquals(1, FolderManifestCodec.decode(remote.index!!).folders.single().pages.size)
    }

    @Test fun reimportedFolderDoesNotSilentlyDiscardItsOldSharedIdentity() = runBlocking {
        val a = Device("reimport"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("one")
        val oldBook = a.importUserFolder(folder)
        val oldIdentity = a.db.books().get(oldBook)!!.fileHash!!
        a.db.progress().upsert(ReadingProgressEntity(oldBook, "{\"chapter\":0}", 1f, 10))
        a.syncAll(remote)
        val before = remote.index!!.copyOf()
        a.db.books().delete(listOf(oldBook)) // the user kept the source images
        a.syncAll(remote) // records the local removal without reimporting it
        val newBook = a.importUserFolder(folder)
        assertNotEquals(oldIdentity, a.db.books().get(newBook)!!.fileHash)
        val error = runCatching { a.syncAll(remote) }.exceptionOrNull()
        assertTrue(error is IOException && error.message!!.contains("different identity"))
        assertArrayEquals(before, remote.index)
        assertTrue(FolderMetadataSnapshotCodec.decode(remote.metadata!!).state.books.any { it.hash == oldIdentity })
    }

    @Test fun missingLocalPositionRevisionNeverGetsGuessedFromCurrentFolder() = runBlocking {
        val a = Device("missing-base"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("one")
        val book = a.addFolder(folder)
        a.db.openHelper.writableDatabase.execSQL("DELETE FROM folder_sync_bases WHERE targetId = ? AND folderId = ?", arrayOf(FolderRevisionCommit.LOCAL, id))
        a.db.progress().upsert(ReadingProgressEntity(book, "{\"chapter\":0}", 1f, 10))
        a.sync.sync(remote, "target")
        // The folder is skipped with a recorded problem; nothing is guessed or published.
        assertTrue(a.recorded.any { it.kind == "folder_missing_revision" && it.target == book.toString() })
        assertNull(remote.index)
        assertEquals("{\"chapter\":0}", a.db.progress().get(book)!!.locatorJson)
    }

    @Test fun interruptedFolderTransfersDoNotPublishPartialFoldersAndCanRetry() = runBlocking {
        val a = Device("interrupt-a"); val b = Device("interrupt-b"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("first page"); File(folder, "2.jpg").writeText("second page")
        val book = a.importUserFolder(folder)
        a.db.progress().upsert(ReadingProgressEntity(book, "{\"chapter\":1}", 1f, 10))
        remote.interruptUpload = true
        assertTrue(runCatching { a.syncAll(remote) }.exceptionOrNull() is IOException)
        assertNull(remote.index)
        assertEquals(1, remote.blobs.size) // accepted page remains unreferenced, safely reused
        a.syncAll(remote)
        remote.interruptDownload = true
        assertTrue(runCatching { b.syncAll(remote) }.exceptionOrNull() is IOException)
        assertTrue(b.db.books().all().isEmpty())
        val stages = File(b.context.filesDir, "synced-books").listFiles().orEmpty().filter { it.name.startsWith("folder-") }
        assertTrue(stages.isEmpty())
        b.syncAll(remote)
        val copy = b.db.books().all().single()
        assertTrue(b.db.progress().get(copy.id)!!.locatorJson.contains("\"chapter\":1"))
        assertEquals("second page", File(Uri.parse(copy.uri).path!!, "2.jpg").readText())
    }

    @Test fun transfersFolderAndRemapsPositionsWhenPagesAreAdded() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "2.jpg").writeText("two"); File(folder, "3.jpg").writeText("three")
        val aId = a.addFolder(folder)
        a.db.progress().upsert(ReadingProgressEntity(aId, "{\"chapter\":1}", 1f, 10))
        assertEquals(0, a.sync.sync(remote, "target").changed)
        assertEquals(1, b.sync.sync(remote, "target").changed)
        val bBook = b.db.books().all().single()
        assertEquals(id, bBook.fileHash)
        b.db.progress().upsert(ReadingProgressEntity(bBook.id, "{\"chapter\":1}", 1f, 10))
        b.db.annotations().insertBookmark(BookmarkEntity(0, bBook.id, "{\"chapter\":0}", "Bookmark", 0f, 5))
        File(folder, "1.jpg").writeText("one")
        assertEquals(0, a.sync.sync(remote, "target").changed)
        assertEquals(1, b.sync.sync(remote, "target").changed)
        assertTrue(a.db.progress().all().single().locatorJson.contains("\"chapter\":2"))
        assertTrue(b.db.progress().all().single().locatorJson.contains("\"chapter\":2"))
        assertTrue(b.db.annotations().allBookmarks().single().locatorJson.contains("\"chapter\":1"))
        val newFolder = File(Uri.parse(b.db.books().get(bBook.id)!!.uri).path!!)
        assertEquals("one", File(newFolder, "1.jpg").readText())
        assertEquals("two", File(newFolder, "2.jpg").readText())
        assertTrue(File(Uri.parse(bBook.uri).path!!).isDirectory) // prior copy was not deleted
    }

    @Test fun metadataAndTrackerBindingFollowStableFolderIdentity() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("one")
        val aId = a.addFolder(folder)
        a.db.progress().upsert(ReadingProgressEntity(aId, "{\"chapter\":0}", 1f, 10))
        a.db.annotations().insertHighlight(HighlightEntity(0, aId, "{\"chapter\":0}", 0, "note", "yellow", "HIGHLIGHT", false, false, "", null, 5, 5))
        a.db.tracks().insert(TrackEntity(itemId = null, bookId = aId, service = 2, remoteId = 123, title = "Manga", url = "https://example.org/123", status = 1, score = 7f, lastChapterRead = 10f))
        a.sync.sync(remote, "target"); b.sync.sync(remote, "target")
        val metadata = SyncMerger(a.db).merge(null, null, "target", 100, SyncMerger.Options()).state
        SyncMerger(b.db).merge(metadata, null, "target", 101, SyncMerger.Options())
        TrackLinkSync(a.context, a.db).sync(remote, "target", true, true)
        TrackLinkSync(b.context, b.db).sync(remote, "target", true, true)
        val bId = b.db.books().all().single().id
        assertEquals("note", b.db.annotations().allHighlights().single().text)
        assertEquals(1f, b.db.progress().all().single().progress, .001f)
        val binding = b.db.tracks().getBook(bId, 2)!!
        assertEquals(123L, binding.remoteId)
        assertEquals(0f, binding.score, .001f)
        assertEquals(0f, binding.lastChapterRead, .001f)
        assertFalse(remote.trackIndex!!.decodeToString().contains("lastChapterRead"))
    }

    @Test fun revisionQualifiedMetadataFollowsPageInsertionsBetweenDevices() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "2.jpg").writeText("two"); File(folder, "3.jpg").writeText("three")
        val aId = a.addFolder(folder)
        a.db.progress().upsert(ReadingProgressEntity(aId, "{\"chapter\":1}", 1f, 10))
        a.db.annotations().insertHighlight(HighlightEntity(0, aId, "{\"chapter\":0}", 0, "note", "yellow", "HIGHLIGHT", false, false, "", null, 5, 5))
        suspend fun write(device: Device, base: SyncBase?): SyncBase {
            val previous = remote.read()
            val snapshot = previous?.let { FolderMetadataSnapshotCodec.decode(it.bytes) }
            val revisions = FolderManifestCodec.decode(remote.index!!).folders
            return mergeFolderMetadataAndWrite(device.context, device.db, remote, previous, snapshot, base, 100, SyncMerger.Options(), revisions).base
        }
        a.sync.sync(remote, "target"); var aBase = write(a, null)
        b.sync.sync(remote, "target"); var bBase = write(b, null)
        val bId = b.db.books().all().single().id
        assertTrue(b.db.progress().get(bId)!!.locatorJson.contains("\"chapter\":1"))
        b.db.annotations().insertBookmark(BookmarkEntity(0, bId, "{\"chapter\":0}", "mark", 0f, 8))
        File(folder, "1.jpg").writeText("one")
        a.sync.sync(remote, "target"); aBase = write(a, aBase)
        b.sync.sync(remote, "target"); bBase = write(b, bBase)
        assertTrue(b.db.progress().get(bId)!!.locatorJson.contains("\"chapter\":2"))
        assertTrue(b.db.annotations().allBookmarks().single().locatorJson.contains("\"chapter\":1"))
        val shared = FolderMetadataSnapshotCodec.decode(remote.metadata!!)
        assertTrue(shared.state.highlights.single().locator.contains("\"chapter\":1"))
        assertTrue(shared.state.bookmarks.single().locator.contains("\"chapter\":1"))
        assertTrue(shared.state.books.single().locator!!.contains("\"chapter\":2"))
        assertEquals(3, shared.revisions.single().pages.size)
        // The other device sees the saved bookmark at the same logical page.
        aBase = write(a, aBase)
        assertTrue(a.db.annotations().allBookmarks().single().locatorJson.contains("\"chapter\":1"))
        assertNotNull(aBase); assertNotNull(bBase)
    }

    @Test fun failedQualifiedMetadataWriteRollsBackIncomingMerge() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("one")
        val aId = a.addFolder(folder)
        a.db.annotations().insertHighlight(HighlightEntity(0, aId, "{\"chapter\":0}", 0, "note", "yellow", "HIGHLIGHT", false, false, "", null, 5, 5))
        a.sync.sync(remote, "target"); b.sync.sync(remote, "target")
        val revisions = FolderManifestCodec.decode(remote.index!!).folders
        mergeFolderMetadataAndWrite(a.context, a.db, remote, null, null, null, 100, SyncMerger.Options(), revisions)
        val previous = remote.read()!!; val snapshot = FolderMetadataSnapshotCodec.decode(previous.bytes)
        remote.failMetadataWrite = true
        assertTrue(runCatching { mergeFolderMetadataAndWrite(b.context, b.db, remote, previous, snapshot, null, 101, SyncMerger.Options(), revisions) }.exceptionOrNull() is SyncConflictException)
        assertTrue(b.db.annotations().allHighlights().isEmpty())
        assertArrayEquals(previous.bytes, remote.metadata)
    }

    @Test fun corruptRemotePageDoesNotReplaceFolderOrItsProgress() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("original")
        val aId = a.addFolder(folder)
        a.db.progress().upsert(ReadingProgressEntity(aId, "{\"chapter\":0}", 1f, 10))
        a.sync.sync(remote, "target"); b.sync.sync(remote, "target")
        val bBook = b.db.books().all().single()
        b.db.progress().upsert(ReadingProgressEntity(bBook.id, "{\"chapter\":0}", 1f, 10))
        File(folder, "2.jpg").writeText("new page")
        a.sync.sync(remote, "target")
        val manifest = FolderManifestCodec.decode(remote.index!!)
        val newPage = manifest.folders.single().pages.single { it.path == "2.jpg" }
        remote.blobs[newPage.sha256] = "bad".toByteArray()
        assertTrue(runCatching { b.sync.sync(remote, "target") }.exceptionOrNull() is IOException)
        assertEquals(bBook.uri, b.db.books().get(bBook.id)!!.uri)
        assertEquals("{\"chapter\":0}", b.db.progress().all().single().locatorJson)
        assertEquals("original", File(Uri.parse(bBook.uri).path!!, "1.jpg").readText())
    }

    @Test fun independentChangesConflictWithoutOverwritingEitherDevice() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("original")
        a.addFolder(folder); a.sync.sync(remote, "target"); b.sync.sync(remote, "target")
        File(folder, "1.jpg").writeText("a changed")
        val bDir = File(Uri.parse(b.db.books().all().single().uri).path!!)
        File(bDir, "1.jpg").writeText("b changed")
        a.sync.sync(remote, "target")
        val indexBefore = remote.index!!.copyOf()
        assertTrue(runCatching { b.sync.sync(remote, "target") }.exceptionOrNull() is IOException)
        assertEquals("b changed", File(bDir, "1.jpg").readText())
        assertArrayEquals(indexBefore, remote.index)
    }
    @Test fun independentlyImportedLegacyFolderDoesNotSilentlyDuplicate() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val aDir = File(a.context.filesDir, "original").apply { mkdirs() }
        File(aDir, "1.jpg").writeText("same image")
        a.addFolder(aDir); a.sync.sync(remote, "target")
        val bDir = File(b.context.filesDir, "local").apply { mkdirs() }
        File(bDir, "renamed.jpg").writeText("same image")
        val bId = b.addFolder(bDir)
        val local = b.db.books().get(bId)!!
        b.db.books().update(local.copy(fileHash = "dir:${Uri.fromFile(bDir)}"))
        b.db.progress().upsert(ReadingProgressEntity(bId, "{\"chapter\":0}", 1f, 10))
        val failure = runCatching { b.sync.sync(remote, "target") }.exceptionOrNull()
        assertTrue(failure is IOException && failure.message!!.contains("no portable identity"))
        assertEquals(1, b.db.books().all().size)
        assertEquals(bId, b.db.books().all().single().id)
        assertEquals("{\"chapter\":0}", b.db.progress().get(bId)!!.locatorJson)
    }

    @Test fun integratedSyncKeepsPageMetadataAcrossTwoLibraries() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "2.jpg").writeText("two"); File(folder, "3.jpg").writeText("three")
        val aId = a.addFolder(folder)
        a.db.progress().upsert(ReadingProgressEntity(aId, "{\"chapter\":1}", 1f, 10))
        a.db.annotations().insertHighlight(HighlightEntity(0, aId, "{\"chapter\":0}", 0, "note", "yellow", "HIGHLIGHT", false, false, "", null, 5, 5))
        a.syncAll(remote); b.syncAll(remote)
        val bId = b.db.books().all().single().id
        assertTrue(b.db.progress().get(bId)!!.locatorJson.contains("\"chapter\":1"))
        b.db.annotations().insertBookmark(BookmarkEntity(0, bId, "{\"chapter\":0}", "mark", 0f, 8))
        File(folder, "1.jpg").writeText("one")
        a.syncAll(remote); b.syncAll(remote); a.syncAll(remote)
        assertTrue(b.db.progress().get(bId)!!.locatorJson.contains("\"chapter\":2"))
        assertTrue(b.db.annotations().allBookmarks().single().locatorJson.contains("\"chapter\":1"))
        assertTrue(a.db.annotations().allBookmarks().single().locatorJson.contains("\"chapter\":1"))
        val snapshot = FolderMetadataSnapshotCodec.decode(remote.metadata!!)
        assertEquals(3, snapshot.version)
        assertEquals(3, snapshot.revisions.single().pages.size)
        assertTrue(snapshot.state.highlights.single().locator.contains("\"chapter\":1"))
        // Metadata-only sync remains possible for a folder whose local revision is known.
        b.syncAll(remote, files = false)
        assertEquals(1, b.db.books().all().size)
    }

    @Test fun unqualifiedStableFolderMetadataIsNotOverwritten() = runBlocking {
        val a = Device("a"); val remote = Server()
        val state = SyncState(version = 2, books = listOf(SyncBook(id, "Manga", "{\"chapter\":0}", 0f, 1, "READING")))
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        remote.metadata = json.encodeToString(SyncState.serializer(), state).toByteArray()
        val before = remote.metadata!!.copyOf()
        assertTrue(runCatching { a.syncAll(remote) }.exceptionOrNull() is IOException)
        assertArrayEquals(before, remote.metadata)
        assertNull(remote.index)
    }

    @Test fun upgradingLegacyMetadataDoesNotDropOtherBooksOrAnnotations() = runBlocking {
        val a = Device("a"); val remote = Server()
        val otherBook = SyncBook("other", "Other", "{\"chapter\":2}", .5f, 10, "READING")
        val otherNote = SyncNote("other", "{\"chapter\":2}", "keep this note", "", 20, 20)
        val legacy = SyncState(version = 2, books = listOf(otherBook), notes = listOf(otherNote))
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        remote.metadata = json.encodeToString(SyncState.serializer(), legacy).toByteArray()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("one")
        a.addFolder(folder)
        a.syncAll(remote)
        val saved = FolderMetadataSnapshotCodec.decode(remote.metadata!!)
        assertEquals(otherBook, saved.state.books.single { it.hash == "other" })
        assertEquals(otherNote, saved.state.notes.single { it.hash == "other" })
        assertEquals(1, saved.revisions.size)
    }

    @Test fun userImportTransfersNestedPagesAndBindingsThroughManager() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("first")
        File(folder, "chapter").mkdirs()
        File(folder, "chapter/2.png").writeText("second")
        val aId = a.importUserFolder(folder)
        val hash = a.db.books().get(aId)!!.fileHash!!
        assertTrue(FolderManifestCodec.validId(hash))
        a.db.progress().upsert(ReadingProgressEntity(aId, "{\"chapter\":1}", .5f, 10))
        a.db.annotations().insertBookmark(BookmarkEntity(0, aId, "{\"chapter\":0}", "mark", 0f, 8))
        a.db.tracks().insert(TrackEntity(itemId = null, bookId = aId, service = 2, remoteId = 123, title = "Manga", url = "https://example.org/123", status = 1, score = 7f, lastChapterRead = 10f))
        a.syncAll(remote); b.syncAll(remote)
        val copy = b.db.books().all().single()
        assertEquals(hash, copy.fileHash)
        val copyDir = File(Uri.parse(copy.uri).path!!)
        assertEquals("first", File(copyDir, "1.jpg").readText())
        assertEquals("second", File(copyDir, "chapter/2.png").readText())
        assertTrue(b.db.progress().get(copy.id)!!.locatorJson.contains("\"chapter\":1"))
        assertEquals("mark", b.db.annotations().allBookmarks().single().title)
        assertEquals(123L, b.db.tracks().getBook(copy.id, 2)!!.remoteId)
    }

    @Test fun lostResponseAfterMetadataCommitCanRetryWithoutLosingFolderOrNotes() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("one")
        val aId = a.importUserFolder(folder)
        a.db.annotations().insertBookmark(BookmarkEntity(0, aId, "{\"chapter\":0}", "mark", 0f, 8))
        remote.loseNextMetadataResponse = true
        assertTrue(runCatching { a.syncAll(remote) }.exceptionOrNull() is IOException)
        assertNotNull(remote.metadata)
        a.syncAll(remote); b.syncAll(remote); a.syncAll(remote)
        assertEquals(1, a.db.books().all().size)
        assertEquals(1, b.db.books().all().size)
        assertEquals("mark", b.db.annotations().allBookmarks().single().title)
        assertEquals(1, FolderMetadataSnapshotCodec.decode(remote.metadata!!).state.bookmarks.size)
    }

    @Test fun legacyFolderBlocksFileSyncBeforeAnythingIsPublished() = runBlocking {
        val a = Device("a"); val remote = Server()
        val folder = File(a.context.filesDir, "legacy").apply { mkdirs() }
        File(folder, "1.jpg").writeText("one")
        val aId = a.addFolder(folder)
        val old = a.db.books().get(aId)!!
        a.db.books().update(old.copy(fileHash = "dir:${Uri.fromFile(folder)}"))
        val failure = runCatching { a.syncAll(remote) }.exceptionOrNull()
        assertTrue(failure is IOException && failure.message!!.contains("no portable identity"))
        assertNull(remote.index); assertNull(remote.metadata); assertTrue(remote.blobs.isEmpty())
        assertEquals(aId, a.db.books().all().single().id)
        // Opting out of file transfer still allows the legacy metadata to sync.
        a.syncAll(remote, files = false)
        assertNotNull(remote.metadata)
    }

    @Test fun annotationDeleteConflictSurvivesFullSyncManagerAndPersistedBases() = runBlocking {
        val a = Device("annotation-a"); val b = Device("annotation-b"); val remote = Server()
        val book = a.importUserFile(File(a.context.filesDir, "book.txt").apply { writeText("a readable example book") })
        a.db.annotations().insertHighlight(HighlightEntity(0, book, "{}", 0, "text", "yellow", "HIGHLIGHT", false, false, "", null, 5, 5))
        a.syncAll(remote); b.syncAll(remote)
        b.db.annotations().deleteHighlight(b.db.annotations().allHighlights().single().id)
        b.syncAll(remote)
        val before = remote.metadata!!.copyOf()
        val highlight = a.db.annotations().allHighlights().single()
        a.db.annotations().updateHighlight(highlight.copy(note = "local edit", updatedAt = 6))
        assertTrue(runCatching { a.syncAll(remote) }.exceptionOrNull() is AnnotationSyncProblem)
        assertArrayEquals(before, remote.metadata)
        assertEquals("local edit", a.db.annotations().allHighlights().single().note)
    }

    @Test fun deletedBookStaysAbsentUntilManualImportAndRecoversSyncedData() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val file = File(a.context.filesDir, "original.txt").apply { writeText("a readable example book") }
        val oldId = a.importUserFile(file)
        a.db.progress().upsert(ReadingProgressEntity(oldId, "{\"chapter\":0}", .3f, 1000))
        a.db.books().updateProgress(oldId, .3f, 1000)
        a.db.annotations().insertBookmark(BookmarkEntity(0, oldId, "{\"chapter\":0}", "saved mark", .3f, 10))
        a.db.tracks().insert(TrackEntity(itemId = null, bookId = oldId, service = 2, remoteId = 123, title = "Book", url = "https://example.org/123", status = 1))
        a.syncAll(remote); b.syncAll(remote)
        a.db.books().delete(listOf(oldId))
        a.syncAll(remote); a.syncAll(remote)
        assertTrue(a.db.books().all().isEmpty())
        assertEquals(1, b.db.books().all().size)
        val newId = a.importUserFile(file)
        assertNotEquals(oldId, newId)
        a.syncAll(remote)
        assertEquals(.3f, a.db.progress().get(newId)!!.progress, .001f)
        assertEquals("saved mark", a.db.annotations().allBookmarks().single { it.bookId == newId }.title)
        assertEquals(123L, a.db.tracks().getBook(newId, 2)!!.remoteId)
    }

    @Test fun changedFolderCannotOpenAgainstStalePagePositions() = runBlocking {
        val a = Device("a"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "2.jpg").writeText("two"); File(folder, "3.jpg").writeText("three")
        val id = a.importUserFolder(folder)
        a.db.progress().upsert(ReadingProgressEntity(id, "{\"chapter\":1}", 1f, 10))
        a.books.verifyFolderRevisionBeforeReading(id)
        a.syncAll(remote)
        val beforeOpen = a.db.progress().get(id)!!.locatorJson
        File(folder, "1.jpg").writeText("one")
        val failure = runCatching { a.books.verifyFolderRevisionBeforeReading(id) }.exceptionOrNull()
        assertTrue(failure is IOException && failure.message!!.contains("synchronize"))
        assertEquals(beforeOpen, a.db.progress().get(id)!!.locatorJson)
        a.syncAll(remote)
        a.books.verifyFolderRevisionBeforeReading(id)
        assertTrue(a.db.progress().get(id)!!.locatorJson.contains("\"chapter\":2"))
    }

    @Test fun activeFolderReaderExcludesSyncAndReleasesOnClose() = runBlocking {
        val a = Device("a"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("one")
        val id = a.importUserFolder(folder)
        val reader = a.books.beginFolderReading(id)!!
        val failure = runCatching { a.syncAll(remote) }.exceptionOrNull()
        assertTrue(failure is IOException && failure.message!!.contains("Close"))
        assertNull(remote.metadata); assertNull(remote.index)
        reader.close(); reader.close() // leases are idempotent
        a.syncAll(remote)
        assertNotNull(remote.metadata)
        val sync = FolderReadSyncGate.sync(a.db)
        try { assertTrue(runCatching { a.books.beginFolderReading(id) }.exceptionOrNull() is IOException) }
        finally { sync.close() }
        a.books.beginFolderReading(id)!!.close()
    }

    @Test fun failedFolderOpenReleasesReadingGate() = runBlocking {
        val a = Device("a"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "1.jpg").writeText("one")
        val id = a.importUserFolder(folder)
        File(folder, "2.jpg").writeText("two")
        assertTrue(runCatching { a.books.beginFolderReading(id) }.exceptionOrNull() is IOException)
        a.syncAll(remote) // failure to open must not strand the process-local gate
        a.books.beginFolderReading(id)!!.close()
    }

    @Test fun editedFolderWhileReadingKeepsOriginalPageUntilSyncCanRemapIt() = runBlocking {
        val a = Device("a"); val remote = Server()
        val folder = File(a.context.filesDir, "original").apply { mkdirs() }
        File(folder, "2.jpg").writeText("two"); File(folder, "3.jpg").writeText("three")
        val id = a.importUserFolder(folder)
        a.syncAll(remote)
        val lease = a.books.beginFolderReading(id)!!
        try {
            val book = a.books.getBook(id)!!
            PageSources.open(a.context, book, Uri.parse(book.uri), folderProofs = lease.pages).use { pages ->
                File(folder, "1.jpg").writeText("one")
                assertEquals("three", pages.file(1).readText())
                a.books.saveProgress(id, Locator(chapter = 1, totalProgression = 1f))
                assertTrue(runCatching { a.syncAll(remote) }.exceptionOrNull() is IOException)
            }
        } finally { lease.close() }
        a.syncAll(remote)
        assertTrue(a.db.progress().get(id)!!.locatorJson.contains("\"chapter\":2"))
        val updated = a.books.beginFolderReading(id)!!
        try { assertEquals(listOf("1.jpg", "2.jpg", "3.jpg"), updated.pages.map { it.path }) }
        finally { updated.close() }
    }

    @Test fun independentlyImportedFoldersWithAddedPagesConflictInsteadOfDuplicating() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = Server()
        val aDir = File(a.context.filesDir, "folder-a").apply { mkdirs() }
        File(aDir, "1.jpg").writeText("one"); File(aDir, "2.jpg").writeText("two")
        a.importUserFolder(aDir); a.syncAll(remote)
        val bDir = File(b.context.filesDir, "folder-b").apply { mkdirs() }
        File(bDir, "1.jpg").writeText("one"); File(bDir, "2.jpg").writeText("two"); File(bDir, "3.jpg").writeText("three")
        val bId = b.importUserFolder(bDir, "Different title")
        b.db.progress().upsert(ReadingProgressEntity(bId, "{\"chapter\":2}", 1f, 10))
        val before = remote.index!!.copyOf()
        val failure = runCatching { b.syncAll(remote) }.exceptionOrNull()
        assertTrue(failure is IOException && failure.message!!.contains("different identity"))
        assertEquals(1, b.db.books().all().size)
        assertEquals("{\"chapter\":2}", b.db.progress().get(bId)!!.locatorJson)
        assertArrayEquals(before, remote.index)
    }

}
