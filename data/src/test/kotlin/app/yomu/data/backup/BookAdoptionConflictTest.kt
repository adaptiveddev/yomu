package app.yomu.data.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A file changed on both devices is a conflict; the explicit resolution replaces the file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookAdoptionConflictTest : BookAdoptionTestBase() {
    @Test fun changedOnBothDevicesIsAConflictUntilForced() = runBlocking {
        val a = Device("a"); val b = Device("b"); val server = FakeBackend()
        val bookId = a.add("example.txt", "original content".toByteArray())
        a.sync(server); b.sync(server)
        val oldId = a.db.books().get(bookId)!!.fileHash!!
        val bId = b.db.books().all().first().id

        bookFile(a.db.books().get(bookId)!!.uri).writeBytes("version adopted by A".toByteArray())
        BookAdoption(a.context, a.db, a.settings, server, server.id).adopt(bookId)
        val aNew = a.db.books().get(bookId)!!.fileHash!!

        bookFile(b.db.books().get(bId)!!.uri).writeBytes("version edited on B".toByteArray())
        b.sync(server)
        assertTrue(b.problemKinds().contains("book_update_conflict"))
        // B's conflicting file is not silently overwritten by a normal sync.
        assertEquals("version edited on B", bookFile(b.db.books().get(bId)!!.uri).readText())

        val problems = SyncProblemStore(b.context).load()
        val target = problems.single { it.kind == "book_update_conflict" }.target!!.toLong()
        assertEquals(bId, target)
        BookAdoption(b.context, b.db, b.settings, server, server.id).follow(bId, forced = true)
        assertEquals(aNew, b.db.books().get(bId)!!.fileHash)
        assertEquals("version adopted by A", bookFile(b.db.books().get(bId)!!.uri).readText())
        val index = BookFileSync.decodeIndex(server.readBookIndex()?.bytes)
        assertEquals(aNew, index.replaced[oldId])
    }
}
