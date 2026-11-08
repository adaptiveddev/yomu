package app.yomu.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import app.yomu.core.database.YomuDatabase
import app.yomu.core.database.HighlightEntity
import app.yomu.core.database.ReadingProgressEntity
import app.yomu.core.datastore.AppSettings
import app.yomu.data.library.CoverStore
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.data.repository.BookRepositoryImpl
import kotlinx.coroutines.runBlocking
import io.mockk.coEvery
import io.mockk.mockk
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
class BookFileSyncTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val root = File(app.cacheDir, "book-sync-tests-${UUID.randomUUID()}").apply { mkdirs() }
    private val devices = mutableListOf<Device>()

    private inner class Device(name: String) {
        private val dir = File(root, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
        }
        val db = YomuDatabase.build(context, inMemory = true)
        private val covers = CoverStore(context)
        val importer = ImportService(context, BookRepositoryImpl(context, db, covers), AppSettings(context), covers)
        val sync = BookFileSync(context, db, importer)
        suspend fun add(name: String, bytes: ByteArray): Long {
            val f = File(context.filesDir, name).apply { parentFile?.mkdirs(); writeBytes(bytes) }
            val result = importer.importLocalFile(f, name)
            assertTrue(result.toString(), result is ImportOutcome.Added)
            return (result as ImportOutcome.Added).bookId
        }
        init { devices += this }
    }
    private class FakeServer : BookFilesTransport {
        var index: ByteArray? = null
        var revision = 0
        var conflictNext = false
        var uploads = 0
        var loseUploadResponse = false
        var interruptDownload = false
        var afterUpload: (() -> Unit)? = null
        val blobs = HashMap<String, ByteArray>()
        override suspend fun readBookIndex() = index?.let { SyncFile(it.copyOf(), "\"$revision\"") }
        override suspend fun writeBookIndex(bytes: ByteArray, previous: SyncFile?) {
            if (conflictNext) { conflictNext = false; throw SyncConflictException() }
            if (previous?.tag != index?.let { "\"$revision\"" }) throw SyncConflictException()
            index = bytes.copyOf(); revision++
        }
        override suspend fun uploadBook(sha256: String, file: File) {
            val previous = blobs[sha256]
            if (previous != null) BookFileSync.verifyStream(previous.inputStream(), sha256, file.length())
            else { blobs[sha256] = file.readBytes(); uploads++ }
            afterUpload?.invoke()
            if (loseUploadResponse) { loseUploadResponse = false; throw IOException("lost upload response") }
        }
        override suspend fun <T> readBook(sha256: String, consume: (InputStream) -> T): T {
            val bytes = blobs[sha256] ?: throw IOException("missing blob")
            if (!interruptDownload) return consume(bytes.inputStream())
            return consume(object : InputStream() {
                private val inner = bytes.inputStream()
                private var delivered = false
                override fun read(): Int = throw IOException("interrupted download")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (delivered) throw IOException("interrupted download")
                    delivered = true
                    return inner.read(b, off, minOf(len, 3))
                }
            })
        }
    }
    @After fun close() { devices.forEach { it.db.close() }; root.deleteRecursively() }

    @Test fun copiesBooksAndRestoresProgressAndHighlights() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = FakeServer()
        val content = "A readable example book with text".toByteArray()
        val id = a.add("example.txt", content)
        a.db.progress().upsert(ReadingProgressEntity(id, "{\"chapter\":1}", 0.4f, 1000))
        a.db.books().updateProgress(id, 0.4f, 1000)
        a.db.annotations().insertHighlight(HighlightEntity(0, id, "{}", 0, "example", "yellow", "HIGHLIGHT", false, false, "", null, 1000, 1000))
        assertEquals(0, a.sync.sync(remote))
        val state = SyncMerger(a.db).merge(null, null, "test", 2000, SyncMerger.Options()).state
        assertEquals(1, b.sync.sync(remote))
        SyncMerger(b.db).merge(state, null, "test", 3000, SyncMerger.Options())
        val copy = b.db.books().all().single()
        assertArrayEquals(content, File(android.net.Uri.parse(copy.uri).path!!).readBytes())
        assertEquals("example", b.db.annotations().allHighlights().single().text)
        assertEquals(0.4f, b.db.progress().all().single().progress, 0.0001f)
        assertEquals(0, b.sync.sync(remote))
        assertEquals(1, remote.uploads)
    }

    @Test fun corruptedDownloadNeverEntersLibrary() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = FakeServer()
        a.add("example.txt", "original book content".toByteArray()); a.sync.sync(remote)
        val sha = remote.blobs.keys.single(); remote.blobs[sha] = "wrong bytes".toByteArray()
        try { b.sync.sync(remote); fail("Expected integrity error") } catch (e: IOException) { assertTrue(e.message!!.contains("book")) }
        assertTrue(b.db.books().all().isEmpty())
        assertTrue(File(b.context.filesDir, "synced-books").listFiles().orEmpty().isEmpty())
    }

    @Test fun importFailureAfterDatabaseCommitDoesNotDeleteVerifiedBook() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = FakeServer()
        val content = "verified content that must not be lost".toByteArray()
        a.add("example.txt", content); a.sync.sync(remote)
        val original = a.db.books().all().single()
        val failingImporter = mockk<ImportService>()
        coEvery { failingImporter.importLocalFile(any(), any()) } coAnswers {
            val file = firstArg<File>()
            b.db.books().insert(original.copy(id = 0, uri = android.net.Uri.fromFile(file).toString()))
            ImportOutcome.Failed(file.name, IOException("cover save failed after commit"))
        }
        val sync = BookFileSync(b.context, b.db, failingImporter)
        try { sync.sync(remote); fail("Expected import error") } catch (_: IOException) { }
        val saved = b.db.books().all().single()
        val savedFile = File(android.net.Uri.parse(saved.uri).path!!)
        assertTrue(savedFile.isFile)
        assertArrayEquals(content, savedFile.readBytes())
        assertEquals(0, b.sync.sync(remote))
        assertArrayEquals(content, savedFile.readBytes())
    }

    @Test fun interruptedDownloadIsDiscardedAndCanBeRetried() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = FakeServer()
        val content = "a complete book to transfer".toByteArray()
        a.add("example.txt", content); a.sync.sync(remote)
        remote.interruptDownload = true
        try { b.sync.sync(remote); fail("Expected interrupted download") } catch (_: IOException) { }
        assertTrue(b.db.books().all().isEmpty())
        assertTrue(File(b.context.filesDir, "synced-books").listFiles().orEmpty().isEmpty())
        remote.interruptDownload = false
        assertEquals(1, b.sync.sync(remote))
        val copy = b.db.books().all().single()
        assertArrayEquals(content, File(android.net.Uri.parse(copy.uri).path!!).readBytes())
    }

    @Test fun lostUploadResponseDoesNotPublishIndexAndCanBeRetried() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = FakeServer()
        val content = "a book with a lost response".toByteArray()
        a.add("example.txt", content); remote.loseUploadResponse = true
        try { a.sync.sync(remote); fail("Expected lost response") } catch (_: IOException) { }
        assertNull(remote.index)
        assertEquals(1, remote.uploads)
        assertEquals(0, a.sync.sync(remote))
        assertEquals(1, remote.uploads)
        assertEquals(1, b.sync.sync(remote))
        val copy = b.db.books().all().single()
        assertArrayEquals(content, File(android.net.Uri.parse(copy.uri).path!!).readBytes())
    }

    @Test fun localFileChangingDuringUploadDoesNotPublishManifest() = runBlocking {
        val a = Device("a"); val remote = FakeServer()
        val id = a.add("example.txt", "original book content".toByteArray())
        val local = File(Uri.parse(a.db.books().get(id)!!.uri).path!!)
        remote.afterUpload = { local.writeText("changed while uploading") }
        assertTrue(runCatching { a.sync.sync(remote) }.exceptionOrNull() is IOException)
        assertNull(remote.index)
        assertEquals("changed while uploading", local.readText())
        assertEquals(id, a.db.books().all().single().id)
    }

    @Test fun duplicateLocalIdentitiesDoNotPublishFiles() = runBlocking {
        val a = Device("a"); val remote = FakeServer()
        val id = a.add("one.txt", "a book".toByteArray())
        val original = a.db.books().get(id)!!
        a.db.books().insert(original.copy(id = 0, uri = "file:///different.txt"))
        assertTrue(runCatching { a.sync.sync(remote) }.exceptionOrNull() is IOException)
        assertNull(remote.index)
        assertTrue(remote.blobs.isEmpty())
        assertEquals(2, a.db.books().all().size)
    }

    @Test fun samePrefixDifferentFileIsReportedNotOverwritten() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = FakeServer()
        val problems = mutableListOf<SyncProblemInfo>()
        val prefix = ByteArray(1024 * 1024) { 'a'.code.toByte() }
        val first = prefix + "first".toByteArray(); val second = prefix + "other".toByteArray()
        a.add("first.txt", first); b.add("other.txt", second)
        a.sync.sync(remote)
        val before = remote.index!!.copyOf()
        val reported = BookFileSync(b.context, b.db, b.importer) { problems += it }.sync(remote)
        assertEquals(0, reported)
        assertEquals(listOf("book_collision"), problems.map { it.kind })
        assertArrayEquals(before, remote.index)
        assertEquals(1, remote.blobs.size)
        assertEquals(1, b.db.books().all().size)
    }

    @Test fun retriesManifestConflictWithoutDuplicatingTheBlob() = runBlocking {
        val a = Device("a"); val remote = FakeServer(); remote.conflictNext = true
        a.add("example.txt", "some text in the book".toByteArray())
        a.sync.sync(remote)
        assertEquals(1, remote.uploads)
        assertEquals(1, BookFileSync.decodeIndex(remote.index).books.size)
    }

    @Test fun changedLocalBookReportsConflictAndKeepsItsProgressAndNotes() = runBlocking {
        val a = Device("a"); val remote = FakeServer()
        val original = "original book content".toByteArray()
        val id = a.add("example.txt", original)
        a.db.progress().upsert(ReadingProgressEntity(id, "{\"chapter\":1}", 0.4f, 1000))
        a.db.books().updateProgress(id, 0.4f, 1000)
        a.db.annotations().insertHighlight(HighlightEntity(0, id, "{}", 0, "my note", "yellow", "HIGHLIGHT", false, false, "", null, 1000, 1000))
        a.sync.sync(remote)
        val indexBefore = remote.index!!.copyOf()
        val blobBefore = remote.blobs.values.single().copyOf()
        val localFile = File(android.net.Uri.parse(a.db.books().get(id)!!.uri).path!!)
        localFile.writeText("edited book content with a different length")
        val problems = mutableListOf<SyncProblemInfo>()
        val imported = BookFileSync(a.context, a.db, a.importer) { problems += it }.sync(remote)
        assertEquals(0, imported)
        assertEquals(listOf("book_changed"), problems.map { it.kind })
        assertEquals(listOf(id.toString()), problems.map { it.target })
        assertEquals(id, a.db.books().all().single().id)
        assertEquals("my note", a.db.annotations().allHighlights().single().text)
        assertEquals(0.4f, a.db.progress().all().single().progress, 0.0001f)
        assertArrayEquals(indexBefore, remote.index)
        assertArrayEquals(blobBefore, remote.blobs.values.single())
        assertEquals("edited book content with a different length", localFile.readText())
    }

    @Test fun repairsMissingLocalFileWithoutReplacingLibraryRecord() = runBlocking {
        val a = Device("a"); val remote = FakeServer()
        val id = a.add("example.txt", "some text in the book".toByteArray())
        a.sync.sync(remote)
        val before = a.db.books().get(id)!!
        File(android.net.Uri.parse(before.uri).path!!).delete()
        assertEquals(1, a.sync.sync(remote))
        assertEquals(id, a.db.books().all().single().id)
        assertTrue(File(android.net.Uri.parse(a.db.books().get(id)!!.uri).path!!).isFile)
    }

    @Test fun localDeletionDoesNotReimportAndManualReaddClearsSuppression() = runBlocking {
        val a = Device("a"); val b = Device("b"); val remote = FakeServer()
        val content = "a book to remove".toByteArray()
        val id = a.add("example.txt", content)
        a.sync.sync(remote); b.sync.sync(remote)
        a.db.books().delete(listOf(id))
        assertEquals(0, a.sync.sync(remote)); assertTrue(a.db.books().all().isEmpty())
        assertEquals(0, a.sync.sync(remote)); assertTrue(a.db.books().all().isEmpty())
        assertEquals(1, b.db.books().all().size) // Never delete another device's book.
        a.add("example.txt", content)
        assertEquals(0, a.sync.sync(remote)); assertEquals(1, a.db.books().all().size)
        val restored = a.db.books().all().single().id
        a.db.books().delete(listOf(restored)); a.sync.sync(remote)
        assertTrue(a.db.books().all().isEmpty())
    }

    @Test fun missingKnownManifestFailsClosed() = runBlocking {
        val a = Device("a"); val remote = FakeServer()
        a.add("example.txt", "book contents".toByteArray()); a.sync.sync(remote)
        remote.index = null
        try { a.sync.sync(remote); fail("Expected missing index error") } catch (e: IOException) { assertTrue(e.message!!.contains("index is missing")) }
        assertNull(remote.index); assertEquals(1, a.db.books().all().size)
    }

    @Test fun resetAfterRestoreForgetsDeletionSuppression() = runBlocking {
        val a = Device("a"); val remote = FakeServer(); val id = a.add("example.txt", "book contents".toByteArray())
        a.sync.sync(remote); a.db.books().delete(listOf(id)); a.sync.sync(remote)
        assertTrue(a.db.books().all().isEmpty())
        SyncManager.clearBase(a.context)
        assertEquals(1, a.sync.sync(remote)); assertEquals(1, a.db.books().all().size)
    }

    @Test fun emptyFileDoesNotPublishAnInvalidIndex() = runBlocking {
        val a = Device("empty"); val remote = FakeServer()
        a.add("empty.txt", byteArrayOf())
        try { a.sync.sync(remote); fail("Expected empty-file error") } catch (e: IOException) { assertTrue(e.message!!.contains("empty")) }
        assertNull(remote.index)
        assertTrue(remote.blobs.isEmpty())
    }

    @Test fun longNamesPreserveTheRecognisedExtension() {
        val longName = "a".repeat(200) + ".fb2.zip"
        val name = BookFileSync.safeName(longName, listOf("fb2"), "fb2")
        assertTrue(name.endsWith(".fb2.zip"))
        assertTrue(name.length <= 120)
        assertEquals("bad_name.pdf", BookFileSync.safeName("bad/name.pdf", listOf("pdf"), "pdf"))
        assertTrue(BookFileSync.safeName("a".repeat(200), listOf("pdf"), "pdf").endsWith(".pdf"))
    }

    @Test fun fileSyncIsOptInByDefault() = runBlocking {
        assertFalse(AppSettings(app).syncBookFiles.default)
    }

    @Test fun rejectsMalformedIndexWithoutReplacingIt() {
        listOf("{}", "not json", "{\"version\":3,\"books\":[]}", "{\"version\":1,\"books\":[{\"bookHash\":\"bad\",\"sha256\":\"bad\",\"size\":2,\"name\":\"a\"}]}").forEach {
            try { BookFileSync.decodeIndex(it.toByteArray()); fail("Expected error for $it") } catch (_: IOException) { }
        }
    }
    @Test fun oversizedLocalBaseFailsWithoutTouchingRemoteManifest() = runBlocking {
        val a = Device("bounded"); val remote = FakeServer()
        a.add("example.txt", "book contents".toByteArray()); a.sync.sync(remote)
        val before = remote.index!!.copyOf()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest("test".toByteArray()).joinToString("") { "%02x".format(it) }
        val base = File(a.context.filesDir, "sync-books/$hash.json")
        java.io.RandomAccessFile(base, "rw").use { it.setLength(4L * 1024 * 1024 + 1) }
        assertTrue(runCatching { a.sync.sync(remote) }.exceptionOrNull() is IOException)
        assertArrayEquals(before, remote.index)
        assertEquals(1, a.db.books().all().size)
    }

}
