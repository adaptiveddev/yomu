package app.yomu.data.backup

import app.yomu.core.database.HighlightEntity
import app.yomu.core.database.ReadingProgressEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Adopting a changed file re-keys everything; the other device follows the replacement marker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookAdoptionFollowTest : BookAdoptionTestBase() {
    @Test fun adoptRekeysEverythingAndTheOtherDeviceFollows() = runBlocking {
        val a = Device("a"); val b = Device("b"); val server = FakeBackend()
        val v1 = "A readable example book with text".toByteArray()
        val bookId = a.add("example.txt", v1)
        a.db.progress().upsert(ReadingProgressEntity(bookId, "{\"chapter\":1}", 0.4f, 1000))
        a.db.books().updateProgress(bookId, 0.4f, 1000)
        a.db.annotations().insertHighlight(HighlightEntity(0, bookId, "{}", 0, "example", "yellow", "HIGHLIGHT", false, false, "", null, 1000, 1000))
        a.sync(server)
        b.sync(server)
        val oldId = a.db.books().get(bookId)!!.fileHash!!
        assertEquals(oldId, b.db.books().get(b.db.books().all().first().id)!!.fileHash)
        assertTrue("B should have the highlight", b.db.annotations().allHighlights().isNotEmpty())

        // The file changes on A; the sync records a problem and the user adopts the new version.
        bookFile(a.db.books().get(bookId)!!.uri).appendBytes(" v2 text".toByteArray())
        a.sync(server)
        assertTrue("changed book must be recorded", a.problemKinds().contains("book_changed"))
        BookAdoption(a.context, a.db, a.settings, server, server.id).adopt(bookId)

        val newId = a.db.books().get(bookId)!!.fileHash!!
        assertTrue(newId != oldId && newId.matches(Regex("[0-9a-f]{40}:[0-9]+")))
        // Every A-side record followed the new identity; the book row keeps its id, progress and highlight.
        assertEquals(1, a.db.annotations().allHighlights().size)
        assertEquals(0.4f, a.db.books().get(bookId)!!.progress, 0.001f)
        val index = BookFileSync.decodeIndex(server.readBookIndex()?.bytes)
        assertEquals(newId, index.replaced[oldId])
        assertTrue(index.books.none { it.bookHash == oldId })
        assertTrue(index.books.single { it.bookHash == newId }.sha256.isNotBlank())
        val remoteState = SyncManager.decodeRemote(server.read()!!.bytes)
        assertTrue(remoteState.books.none { it.hash == oldId })
        assertTrue(remoteState.books.single { it.hash == newId }.progress >= 0f)
        assertTrue(remoteState.highlights.none { it.hash == oldId })
        assertEquals(newId, remoteState.highlights.single().hash)

        // B follows the marker: re-keyed row, the new file version, annotations preserved.
        b.sync(server)
        val bRow = b.db.books().all().single()
        assertEquals(newId, bRow.fileHash)
        assertEquals((v1 + " v2 text".toByteArray()).size.toLong(), bookFile(bRow.uri).length())
        assertTrue(b.db.annotations().allHighlights().isNotEmpty())
        assertEquals(newId, SyncManager.decodeRemote(server.read()!!.bytes).highlights.single().hash)
    }
}
