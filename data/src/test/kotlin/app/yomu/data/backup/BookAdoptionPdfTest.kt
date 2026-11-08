package app.yomu.data.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.CRC32

/** A version written by Yomu's own PDF annotation worker adopts without user action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookAdoptionPdfTest : BookAdoptionTestBase() {
    @Test fun selfModifiedPdfAdoptsAutomatically() = runBlocking {
        val a = Device("a"); val server = FakeBackend()
        // Import as a plain file: the scenario exercises identity bookkeeping, not the PDF engine
        // (pdfium's init lock deadlocks across Robolectric tests in one worker).
        val v1 = "original content".toByteArray()
        val bookId = a.add("book.txt", v1)
        a.db.books().update(a.db.books().get(bookId)!!.copy(format = "PDF"))
        a.sync(server)
        val old = a.db.books().get(bookId)!!.fileHash!!
        val oldSize = old.substringAfter(':').toLong()
        val file = bookFile(a.db.books().get(bookId)!!.uri)
        // Simulate the annotation writer: the base bytes stay, an appendage is added and recorded.
        val append = "%% yomu annotations".toByteArray()
        file.appendBytes(append)
        val manifestDir = File(a.context.filesDir, "pdf-annotations").apply { mkdirs() }
        val crc = CRC32().apply { update(append) }.value
        val manifestJson = """{"base":$oldSize,"length":${oldSize + append.size},"crc":$crc,"signature":"s","ids":[]}"""
        File(manifestDir, "$bookId.json").writeText(manifestJson)

        a.sync(server)
        val newId = a.db.books().get(bookId)!!.fileHash!!
        assertTrue("self-written PDF version should adopt automatically", newId != old)
        val index = BookFileSync.decodeIndex(server.readBookIndex()?.bytes)
        assertEquals(newId, index.replaced[old])
        assertTrue(index.version >= 2)
    }
}
