package app.yomu.data.book

import android.net.Uri
import app.yomu.domain.model.Book
import app.yomu.domain.model.BookFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import app.yomu.data.backup.BookFileSync
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderPageCacheTest {
    private val context = RuntimeEnvironment.getApplication()
    private val root = File(context.cacheDir, "folder-pages-${UUID.randomUUID()}").apply { mkdirs() }
    @After fun cleanup() { root.deleteRecursively(); File(context.cacheDir, "pages/book_12345678").deleteRecursively() }
    @Test fun reopeningFolderAfterEditDoesNotShowCachedOldImages() = runBlocking {
        val first = File(root, "2.jpg").apply { writeText("old page") }
        val uri = Uri.fromFile(root)
        val book = Book(id = 12345678, title = "Manga", format = BookFormat.FOLDER, uri = uri.toString())
        PageSources.open(context, book, uri).use { assertEquals("old page", it.file(0).readText()) }
        first.writeText("new page")
        File(root, "1.jpg").writeText("new first")
        PageSources.open(context, book, uri).use {
            assertEquals(2, it.count)
            assertEquals("new first", it.file(0).readText())
            assertEquals("new page", it.file(1).readText())
        }
    }
    @Test fun verifiedFolderReaderFreezesPageOrderAndRejectsChangedBytes() = runBlocking {
        val first = File(root, "2.jpg").apply { writeText("old page") }
        val second = File(root, "3.jpg").apply { writeText("other page") }
        fun proof(file: File): FolderPageProof {
            val digest = file.inputStream().use { BookFileSync.digest(it) }
            return FolderPageProof(file.name, digest.sha256, digest.size)
        }
        val proofs = listOf(proof(first), proof(second))
        val uri = Uri.fromFile(root)
        val book = Book(id = 12345678, title = "Manga", format = BookFormat.FOLDER, uri = uri.toString())
        PageSources.open(context, book, uri, folderProofs = proofs).use { pages ->
            File(root, "1.jpg").writeText("new first")
            assertEquals(2, pages.count)
            assertEquals("2.jpg", pages.name(0))
            assertEquals("old page", pages.file(0).readText())
            second.writeText("changed page")
            assertTrue(runCatching { pages.file(1) }.exceptionOrNull() is java.io.IOException)
        }
        assertTrue(runCatching { PageSources.open(context, book, uri, folderProofs = proofs) }.exceptionOrNull() is java.io.IOException)
    }

}
