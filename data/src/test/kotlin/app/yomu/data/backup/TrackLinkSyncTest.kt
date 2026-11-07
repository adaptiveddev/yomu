package app.yomu.data.backup

import app.yomu.core.database.BookEntity
import app.yomu.core.database.TrackEntity
import app.yomu.core.database.YomuDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackLinkSyncTest {
    private val context = RuntimeEnvironment.getApplication()
    private val root = UUID.randomUUID().toString()
    private val a = YomuDatabase.build(context, inMemory = true)
    private val b = YomuDatabase.build(context, inMemory = true)
    private val json = Json { encodeDefaults = true }
    private inner class Transport : TrackLinkTransport {
        var data: ByteArray? = null
        var revision = 0
        override suspend fun readTrackLinks() = data?.let { SyncFile(it.copyOf(), revision.toString()) }
        override suspend fun writeTrackLinks(bytes: ByteArray, previous: SyncFile?) {
            if ((data == null) != (previous == null) || (previous != null && previous.tag != revision.toString())) throw SyncConflictException()
            data = bytes.copyOf(); revision++
        }
    }
    private val server = Transport()
    @After fun close() { a.close(); b.close() }
    private suspend fun addBook(db: YomuDatabase, hash: String): Long = db.books().insert(BookEntity(
        title = "Book", sortTitle = "book", authorsText = "", seriesName = null, seriesId = null, seriesIndex = null,
        language = null, description = null, publisher = null, format = "EPUB", uri = "file:///$hash", isLinked = false,
        fileSize = 1, fileHash = hash, coverPath = null, status = "READING", isManga = false, addedAt = 1,
        lastReadAt = null, progress = 0f, readerSettingsJson = null,
    ))
    private suspend fun bind(db: YomuDatabase, book: Long, remote: Long, pending: Boolean = false): Long = db.tracks().insert(
        TrackEntity(itemId = null, bookId = book, service = 2, remoteId = remote, libraryId = 777,
            title = "Example", url = "https://anilist.co/manga/$remote", status = 1, score = 7f,
            lastChapterRead = 12f, updatedAt = 1, pending = pending),
    )
    private suspend fun sync(db: YomuDatabase, device: String, deletes: Boolean = true): Int =
        TrackLinkSync(context, db).sync(server, "$root-$device", catalog = true, deletions = deletes)
    private fun serverLinks() = json.decodeFromString(TrackLinksFile.serializer(), server.data!!.toString(Charsets.UTF_8)).links

    @Test fun transfersBindingWithoutAccountStateAndPropagatesUnbind() = runBlocking {
        val ab = addBook(a, "hash-1"); val bb = addBook(b, "hash-1")
        bind(a, ab, 100); sync(a, "a"); sync(b, "b")
        val t = b.tracks().getBook(bb, 2)!!
        assertEquals(100, t.remoteId); assertEquals(-1, t.libraryId); assertEquals(0f, t.lastChapterRead); assertFalse(t.pending)
        val payload = server.data!!.toString(Charsets.UTF_8)
        assertFalse(payload.contains("token")); assertFalse(payload.contains("777")); assertFalse(payload.contains("lastChapterRead"))
        a.tracks().deleteById(a.tracks().getBook(ab, 2)!!.id); sync(a, "a"); sync(b, "b")
        assertNull(b.tracks().getBook(bb, 2)); assertTrue(serverLinks().isEmpty())
        sync(a, "a"); sync(b, "b"); assertNull(b.tracks().getBook(bb, 2))
    }

    @Test fun remoteLinkIsPreservedWhenBookIsAbsent() = runBlocking {
        val id = addBook(a, "hash-2"); bind(a, id, 200); sync(a, "a")
        sync(b, "b"); assertEquals(200, serverLinks().single().remoteId)
        val book = addBook(b, "hash-2"); sync(b, "b"); assertEquals(200, b.tracks().getBook(book, 2)!!.remoteId)
    }

    @Test fun simultaneousDifferentRebindingIsAConflict() = runBlocking {
        val ab = addBook(a, "hash-3"); val bb = addBook(b, "hash-3")
        bind(a, ab, 300); sync(a, "a"); sync(b, "b")
        val at = a.tracks().getBook(ab, 2)!!; val bt = b.tracks().getBook(bb, 2)!!
        a.tracks().update(at.copy(remoteId = 301)); b.tracks().update(bt.copy(remoteId = 302))
        sync(a, "a")
        val failure = runCatching { sync(b, "b") }.exceptionOrNull()
        assertTrue(failure is IOException); assertEquals(301, serverLinks().single().remoteId); assertEquals(302, b.tracks().getBook(bb, 2)!!.remoteId)
    }

    @Test fun unsentLocalChangesBlockRemoteUnbind() = runBlocking {
        val ab = addBook(a, "hash-4"); val bb = addBook(b, "hash-4")
        bind(a, ab, 400); sync(a, "a"); sync(b, "b")
        val bt = b.tracks().getBook(bb, 2)!!; b.tracks().update(bt.copy(pending = true))
        a.tracks().deleteById(a.tracks().getBook(ab, 2)!!.id); sync(a, "a")
        assertTrue(runCatching { sync(b, "b") }.exceptionOrNull() is IOException)
        assertTrue(b.tracks().getBook(bb, 2)!!.pending)
    }

    @Test fun disabledDeletionsLeaveBothSidesAlone() = runBlocking {
        val ab = addBook(a, "hash-5"); val bb = addBook(b, "hash-5")
        bind(a, ab, 500); sync(a, "a"); sync(b, "b")
        a.tracks().deleteById(a.tracks().getBook(ab, 2)!!.id); sync(a, "a", false)
        assertNull(a.tracks().getBook(ab, 2)); assertEquals(500, serverLinks().single().remoteId)
        sync(a, "a", true); sync(b, "b", true); assertNull(b.tracks().getBook(bb, 2))
    }

    @Test fun missingRemoteFileNeverDeletesLocalLinks() = runBlocking {
        val ab = addBook(a, "hash-missing"); bind(a, ab, 123); sync(a, "a")
        server.data = null
        assertTrue(runCatching { sync(a, "a") }.exceptionOrNull() is IOException)
        assertEquals(123, a.tracks().getBook(ab, 2)!!.remoteId)
        assertNull(server.data)
    }

    @Test fun malformedOrFutureFileIsNotOverwritten() = runBlocking {
        val ab = addBook(a, "hash-6"); bind(a, ab, 600)
        val original = """{"version":2,"links":[]}""".toByteArray(); server.data = original
        assertTrue(runCatching { sync(a, "a") }.exceptionOrNull() is IOException)
        assertArrayEquals(original, server.data)
    }
    @Test fun knownEmptyRemoteFileMustNotDisappear() = runBlocking {
        server.data = json.encodeToString(TrackLinksFile.serializer(), TrackLinksFile()).toByteArray()
        sync(a, "empty-known")
        server.data = null
        assertTrue(runCatching { sync(a, "empty-known") }.exceptionOrNull() is IOException)
        assertNull(server.data)
    }

    @Test fun absentNeverCreatedRemoteFileCanStillBeCreated() = runBlocking {
        sync(a, "empty-new")
        val book = addBook(a, "new-link")
        bind(a, book, 123)
        sync(a, "empty-new")
        assertEquals(123, serverLinks().single().remoteId)
    }

    @Test fun invalidUtf8TrackingFileIsNeverOverwritten() = runBlocking {
        val ab = addBook(a, "hash-utf"); bind(a, ab, 600)
        val original = "{\"version\":1,\"links\":[{\"owner\":{\"bookHash\":\"hash-utf\"},\"service\":2,\"remoteId\":600,\"title\":\"".toByteArray() + byteArrayOf(0x80.toByte()) + "\",\"url\":\"https://example.org\"}]}".toByteArray()
        server.data = original
        assertTrue(runCatching { sync(a, "utf") }.exceptionOrNull() is IOException)
        assertArrayEquals(original, server.data)
    }

    @Test fun oversizedTrackingBaseDoesNotChangeRemoteOrLocalBinding() = runBlocking {
        val book = addBook(a, "bounded"); bind(a, book, 123); sync(a, "bounded")
        val before = server.data!!.copyOf()
        val target = "$root-bounded"
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(target.toByteArray()).joinToString("") { "%02x".format(it) }
        val base = java.io.File(context.filesDir, "sync-tracking/$hash.json")
        java.io.RandomAccessFile(base, "rw").use { it.setLength(4L * 1024 * 1024 + 1) }
        assertTrue(runCatching { sync(a, "bounded") }.exceptionOrNull() is IOException)
        assertArrayEquals(before, server.data)
        assertEquals(123, a.tracks().getBook(book, 2)!!.remoteId)
        base.delete()
        Unit
    }

}
