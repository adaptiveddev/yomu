package app.yomu.data.backup

import android.net.Uri
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
class FolderFileTransferTest {
    private val context = RuntimeEnvironment.getApplication()
    private val root = File(context.cacheDir, "folder-transfer-${UUID.randomUUID()}").apply { mkdirs() }
    private val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
    private val created = mutableListOf<File>()
    private class Fake : FolderFilesTransport {
        val data = mutableMapOf<String, ByteArray>()
        override suspend fun readFolderIndex(): SyncFile? = null
        override suspend fun writeFolderIndex(bytes: ByteArray, previous: SyncFile?) = Unit
        override suspend fun readBookIndex(): SyncFile? = null
        override suspend fun writeBookIndex(bytes: ByteArray, previous: SyncFile?) = Unit
        override suspend fun uploadBook(sha256: String, file: File) { data[sha256] = file.readBytes() }
        override suspend fun <T> readBook(sha256: String, consume: (InputStream) -> T): T = consume((data[sha256] ?: throw IOException("missing")).inputStream())
    }
    @After fun cleanup() { root.deleteRecursively(); created.forEach { it.deleteRecursively() } }
    @Test fun transfersNestedPagesWithVerifiedContentAndOrder() = runBlocking {
        File(root, "ch1").mkdirs()
        File(root, "ch1/2.jpg").writeText("second")
        File(root, "ch1/1.png").writeText("first")
        val entry = FolderSnapshot.snapshot(context, Uri.fromFile(root), id, "Manga")
        val remote = Fake()
        FolderFileTransfer.upload(context, Uri.fromFile(root), entry, remote)
        val copy = FolderFileTransfer.download(context, entry, remote).also { created += it }
        assertEquals("first", File(copy, "ch1/1.png").readText())
        assertEquals("second", File(copy, "ch1/2.jpg").readText())
        assertEquals(entry.pages, FolderSnapshot.snapshot(context, Uri.fromFile(copy), id, "Manga").pages)
    }
    @Test fun corruptDownloadIsDiscarded() = runBlocking {
        File(root, "1.jpg").writeText("original")
        val entry = FolderSnapshot.snapshot(context, Uri.fromFile(root), id, "Manga")
        val remote = Fake()
        FolderFileTransfer.upload(context, Uri.fromFile(root), entry, remote)
        remote.data[entry.pages.single().sha256] = "bad".toByteArray()
        val parent = File(context.filesDir, "synced-books")
        val before = parent.listFiles().orEmpty().toSet()
        assertTrue(runCatching { FolderFileTransfer.download(context, entry, remote) }.exceptionOrNull() is IOException)
        assertEquals(before, parent.listFiles().orEmpty().toSet())
    }
    @Test fun changedSourceIsNotUploadedAsTheOldSnapshot() = runBlocking {
        val file = File(root, "1.jpg").apply { writeText("original") }
        val entry = FolderSnapshot.snapshot(context, Uri.fromFile(root), id, "Manga")
        file.writeText("changed")
        val remote = Fake()
        assertTrue(runCatching { FolderFileTransfer.upload(context, Uri.fromFile(root), entry, remote) }.exceptionOrNull() is IOException)
        assertTrue(remote.data.isEmpty())
    }
}
