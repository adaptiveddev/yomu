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

/**
 * Book removals propagate as verified deletion markers: an unchanged copy is removed with its
 * records, a copy changed since the last sync is kept and reported, and with deletion sync off
 * nothing leaves the device. Server blobs and folders are never touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookDeletionSyncTest : BookAdoptionTestBase() {
    @Test fun removalPropagatesToUnchangedDevicesAndKeepsChangedOnes() = runBlocking {
        val a = Device("a"); val b = Device("b"); val server = FakeBackend()
        val v1 = "a deletable example book".toByteArray()
        a.settings.syncDeletions.set(true); b.settings.syncDeletions.set(true)
        val aId = a.add("example.txt", v1)
        a.db.progress().upsert(ReadingProgressEntity(aId, "{\"chapter\":0}", 0.2f, 1000))
        a.db.annotations().insertHighlight(HighlightEntity(0, aId, "{}", 0, "note", "yellow", "HIGHLIGHT", false, false, "", null, 1000, 1000))
        a.sync(server); b.sync(server)
        val id = b.db.books().all().single().fileHash!!
        assertTrue(b.db.annotations().allHighlights().isNotEmpty())

        // A deletes the book locally and syncs: the marker is published, the entry removed.
        a.db.books().delete(listOf(aId))
        println("DIAG base=${BookFileSync.readBase(a.context, server.id)}")
        a.sync(server)
        println("DIAG index=${server.readBookIndex()?.bytes?.decodeToString()?.take(400)}")
        val index = BookFileSync.decodeIndex(server.readBookIndex()?.bytes)
        assertTrue(index.deleted.containsKey(id))
        assertTrue(index.books.none { it.bookHash == id })

        // B (unchanged since its last sync) applies the removal: row, file and records are gone.
        b.sync(server)
        assertTrue(b.db.books().all().isEmpty())
        assertTrue(b.db.annotations().allHighlights().isEmpty())
        assertTrue(b.problemKinds().isEmpty())
        // The blob stays on the server; a manual re-import on B works and syncs again.
        assertEquals(1, server.blobs.size)
        val again = b.add("reimported.txt", v1)
        b.sync(server)
        assertTrue(b.db.books().get(again) != null)
    }

    @Test fun changedCopyIsKeptAndReportedInsteadOfDeleted() = runBlocking {
        val a = Device("a"); val b = Device("b"); val server = FakeBackend()
        a.settings.syncDeletions.set(true); b.settings.syncDeletions.set(true)
        val aId = a.add("example.txt", "original content".toByteArray())
        a.sync(server); b.sync(server)
        val bId = b.db.books().all().single().id
        val id = b.db.books().get(bId)!!.fileHash!!

        a.db.books().delete(listOf(aId))
        a.sync(server)
        // B changed locally after the last sync: a highlight the other device never saw.
        b.db.annotations().insertHighlight(HighlightEntity(0, bId, "{}", 0, "mine", "yellow", "HIGHLIGHT", false, false, "", null, 2000, 2000))
        b.sync(server)
        assertTrue(b.problemKinds().contains("book_deletion_conflict"))
        // Nothing was removed: row, file and the local highlight survive.
        val row = b.db.books().get(bId)
        assertNotNull(row)
        assertTrue(File(android.net.Uri.parse(row!!.uri).path!!).exists())
        assertEquals("mine", b.db.annotations().allHighlights().single().text)
    }

    @Test fun withDeletionSyncOffNothingLeavesTheDevice() = runBlocking {
        val a = Device("a"); val b = Device("b"); val server = FakeBackend()
        val aId = a.add("example.txt", "kept locally".toByteArray())
        a.sync(server); b.sync(server)
        a.settings.syncDeletions.set(false)
        a.db.books().delete(listOf(aId))
        a.sync(server)
        val index = BookFileSync.decodeIndex(server.readBookIndex()?.bytes)
        assertTrue(index.deleted.isEmpty())
        assertEquals(1, b.db.books().all().size)
        // B re-enables deletions; its copy stays because it was never marked.
        b.settings.syncDeletions.set(false)
        b.sync(server)
        assertEquals(1, b.db.books().all().size)
    }
}
