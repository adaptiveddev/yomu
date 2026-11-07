package app.yomu

import android.content.Context
import android.net.Uri
import androidx.work.Configuration
import androidx.work.WorkManager
import app.yomu.core.common.AppError
import app.yomu.core.database.YomuDatabase
import app.yomu.data.book.Containers
import app.yomu.data.book.EpubBuilder
import app.yomu.data.book.EpubMeta
import app.yomu.data.download.DownloadRepository
import app.yomu.domain.model.BookFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * A book downloaded from a catalog must open, also after it was downloaded again (double tap, second format, re-download)
 * and after its file was lost. Regression test for "No access to the file" after a download in 1.0.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadedBookTest {
    private lateinit var server: MockWebServer
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // Workers must not race the queue processed by the test itself.
        runCatching { WorkManager.initialize(context, Configuration.Builder().setExecutor { }.build()) }
        val epub = File(context.cacheDir, "served.epub")
        EpubBuilder(EpubMeta(title = "Downloaded Book", authors = listOf("Robolectric"), language = "en")).apply {
            addChapter("Chapter 1", "<p>Hello from the catalog.</p>")
        }.writeTo(epub)
        val bytes = epub.readBytes()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse.Builder().code(200).setHeader("Content-Type", "application/epub+zip").body(Buffer().write(bytes)).build()
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.close()
        stopKoin()
    }

    @Test
    fun downloadedBookStaysReadable() = runBlocking {
        val koin = GlobalContext.get()
        val downloads = koin.get<DownloadRepository>()
        val db = koin.get<YomuDatabase>()
        val url = server.url("/book.epub").toString()

        // A double tap queues the book once.
        val first = downloads.enqueueBook("Downloaded Book", url, "application/epub+zip")
        assertEquals(first, downloads.enqueueBook("Downloaded Book", url, "application/epub+zip"))
        process(downloads, db, first)
        val book = db.books().all().single()
        val file = File(Uri.parse(book.uri).path!!)
        assertTrue("downloaded file is missing: $file", file.exists())
        assertOpens(book.uri)

        // Downloading the same book again must not take the file away from the library copy.
        val second = downloads.enqueueBook("Downloaded Book", url, "application/epub+zip")
        assertNotEquals(first, second)
        process(downloads, db, second)
        assertEquals(1, db.books().all().size)
        assertTrue("re-download deleted the library file", file.exists())
        assertOpens(db.books().get(book.id)!!.uri)

        // A lost file is reported as missing (not as a permission problem), and a new download is adopted by the book.
        file.delete()
        val error = runCatching { Containers.open(context, Uri.parse(book.uri), BookFormat.EPUB) }.exceptionOrNull()
        assertTrue("unexpected error: $error", error is AppError.FileMissing)
        val third = downloads.enqueueBook("Downloaded Book", url, "application/epub+zip")
        process(downloads, db, third)
        val adopted = db.books().get(book.id)!!
        assertEquals(1, db.books().all().size)
        assertTrue(File(Uri.parse(adopted.uri).path!!).exists())
        assertOpens(adopted.uri)
    }

    private suspend fun process(downloads: DownloadRepository, db: YomuDatabase, id: Long) {
        downloads.runQueue { }
        withTimeout(20_000) {
            while (db.downloads().get(id)?.status !in setOf("DONE", "FAILED", "CANCELLED")) delay(50)
        }
        val task = db.downloads().get(id)!!
        assertEquals("download failed: ${task.error}", "DONE", task.status)
    }

    private fun assertOpens(uri: String) {
        Containers.open(context, Uri.parse(uri), BookFormat.EPUB).use { c ->
            assertNotNull("not an EPUB", c.read("META-INF/container.xml"))
        }
    }
}
