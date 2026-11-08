package app.yomu.data.backup

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The durable journal blocks ordinary syncs until the interrupted adoption is resumed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookAdoptionJournalTest : BookAdoptionTestBase() {
    @Test fun interruptedAdoptionResumesAndBlocksSyncUntilThen() = runBlocking {
        val a = Device("a"); val server = FakeBackend()
        val bookId = a.add("example.txt", "original content".toByteArray())
        a.sync(server)
        val oldId = a.db.books().get(bookId)!!.fileHash!!
        bookFile(a.db.books().get(bookId)!!.uri).writeBytes("changed content".toByteArray())
        val adoption = BookAdoption(a.context, a.db, a.settings, server, server.id)
        // Simulate a crash after the journal: the journal exists but nothing else happened.
        writeJournal(a.context, BookAdoption.Journal(oldId, "deadbeef".repeat(5).substring(0, 40) + ":14", "x".repeat(64), 14, "example.txt", 1))
        try { a.sync(server); fail("Pending adoption must block a normal sync") }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("interrupted migration")) }
        // The same old content cannot be re-adopted blindly; a matching journal is required.
        try { adoption.adopt(bookId); fail("Stale journal without the new identity must not resume") }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("changed again")) }
        clearJournal(a.context)
        adoption.adopt(bookId)
        assertTrue(a.db.books().get(bookId)!!.fileHash != oldId)
        clearJournal(a.context)
    }
}

/** Direct journal access for the interruption tests (the store itself is exercised elsewhere). */
private fun writeJournal(context: Context, journal: BookAdoption.Journal) {
    BookAdoption.journalFile(context).writeText(
        Json { encodeDefaults = true }.encodeToString(BookAdoption.Journal.serializer(), journal),
    )
}

private fun clearJournal(context: Context) { BookAdoption.journalFile(context).delete() }
