package app.yomu.data.download

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import app.yomu.core.database.DownloadTaskEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.core.network.NetworkClients
import app.yomu.data.R
import app.yomu.data.catalog.CatalogRepository
import app.yomu.data.library.ImportService
import app.yomu.data.source.SourceManager
import app.yomu.domain.model.ChapterInfo
import app.yomu.domain.model.DownloadStatus
import app.yomu.source.CatalogSource
import app.yomu.source.FilterList
import app.yomu.source.ItemsPage
import app.yomu.source.Page
import app.yomu.source.SChapter
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

/**
 * Chapter downloads end to end against a fake site: one release per chapter, "move to top", retries of transient
 * errors, error pages that must not be saved as manga pages, CBZ packing without leftovers — and the chapter sync that
 * keeps read marks when a source glitches or changes its addresses, feeding "download new chapters".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChapterDownloadQueueTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer
    private lateinit var db: YomuDatabase
    private lateinit var settings: AppSettings
    private lateinit var catalog: CatalogRepository
    private lateinit var downloads: DownloadRepository
    private lateinit var source: FakeSource
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val flaky = HashSet<String>()

    @Before
    fun setUp(): Unit = runBlocking {
        // Workers must not race the queue processed by the test itself.
        runCatching { WorkManager.initialize(context, Configuration.Builder().setExecutor { }.build()) }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    requests += path
                    return when {
                        // Chapter 3 is behind a verification page: HTML instead of the image.
                        path.startsWith("/img/c3/") -> MockResponse.Builder().code(200).setHeader("Content-Type", "text/html")
                            .body("<!doctype html><html><body>Checking your browser…</body></html>").build()
                        // The second page of chapter 2 fails once with 503.
                        path == "/img/c2/1.png" && synchronized(flaky) { flaky.add(path) } -> MockResponse.Builder().code(503).build()
                        else -> MockResponse.Builder().code(200).setHeader("Content-Type", "image/png").body(Buffer().write(PNG)).build()
                    }
                }
            }
            start()
        }
        db = YomuDatabase.build(context, inMemory = true)
        settings = AppSettings(context).apply {
            resetAll()
            maxParallelDownloads.set(1) // one after another: the order of requests shows the queue order
            packCbz.set(true)
            downloadRetries.set(0)
        }
        source = FakeSource(server.url("/").toString().trimEnd('/'))
        val sources = mockk<SourceManager>(relaxed = true)
        every { sources.get(any()) } answers { if (firstArg<Long>() == FakeSource.ID) source else null }
        val net = NetworkClients(context)
        catalog = CatalogRepository(context, db, sources, settings, { net.client })
        downloads = DownloadRepository(context, db, settings, net, catalog, mockk<ImportService>(relaxed = true))
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    @Test
    fun downloadsOneReleasePerChapterWithRetriesAndValidation(): Unit = runBlocking {
        val entryId = addTitle()
        val c1 = chapter(entryId, "/c1")
        val c2 = chapter(entryId, "/c2")
        val c3 = chapter(entryId, "/c3")

        // Chapter 2 exists twice (two groups): only the release of the group the title is read in is queued.
        assertEquals(3, downloads.enqueueUnread(entryId))
        assertNull(task(chapter(entryId, "/c2b").id))
        // Queued again: nothing new.
        assertEquals(0, downloads.enqueueUnread(entryId))
        downloads.moveToTop(listOf(task(c3.id)!!.id))

        downloads.runQueue { }

        // Chapter 3 went first (moved to the top) and failed with an understandable reason instead of saving HTML.
        assertTrue(requests.first().startsWith("/img/c3/"))
        val failed = task(c3.id)!!
        assertEquals(DownloadStatus.FAILED.name, failed.status)
        assertEquals(context.getString(R.string.download_error_not_image), failed.error)
        assertFalse(chapter(entryId, "/c3").downloaded)

        // Chapter 2 survived the 503 (retried) and both chapters were packed into complete CBZ files.
        assertEquals(2, requests.count { it == "/img/c2/1.png" })
        listOf(c1, c2).forEach { c ->
            assertEquals(DownloadStatus.DONE.name, task(c.id)!!.status)
            val saved = chapter(entryId, c.url)
            assertTrue(saved.downloaded)
            val cbz = File(saved.localPath!!)
            assertTrue("not a CBZ: $cbz", cbz.name.endsWith(".cbz") && cbz.exists())
            ZipFile(cbz).use { zip ->
                val names = zip.entries().toList().map { it.name }.sorted()
                assertEquals(listOf("000.png", "001.png", "002.png", "ComicInfo.xml"), names)
            }
            // No half-written archive and no page folder left behind.
            assertFalse(File(cbz.path + ".part").exists())
            assertFalse(File(cbz.parentFile, cbz.nameWithoutExtension + ".tmp").exists())
        }

        // "Retry failed" puts the failed chapter back into the queue; "delete read" keeps unread downloads.
        downloads.retryFailed()
        assertEquals(DownloadStatus.QUEUED.name, task(c3.id)!!.status)
        catalog.setRead(listOf(c1.id), true)
        assertEquals(1, downloads.deleteReadDownloads(entryId))
        assertFalse(chapter(entryId, "/c1").downloaded)
        assertTrue(chapter(entryId, "/c2").downloaded)
    }

    @Test
    fun syncKeepsReadMarksAndFeedsAutoDownload(): Unit = runBlocking {
        val entryId = addTitle()
        catalog.setFavorite(entryId, true)
        catalog.setRead(listOf(chapter(entryId, "/c1").id), true)

        // A glitch (empty chapter list) must not wipe the chapters and their read marks.
        source.chapters = emptyList()
        assertEquals(0, catalog.refresh(entryId))
        assertEquals(4, catalog.chapters(entryId).size)

        // The site moves chapter 1 to a new address and publishes chapter 4.
        source.chapters = listOf(SChapter("/c4", "Chapter 4", 4f, scanlator = "Alpha")) + INITIAL.map { if (it.url == "/c1") it.copy(url = "/c1-new") else it }
        val result = catalog.checkUpdates()
        assertTrue(result.failed.isEmpty())
        val update = result.updated.single()
        assertEquals(listOf(chapter(entryId, "/c4").id), update.newChapterIds)
        assertTrue("read mark lost", chapter(entryId, "/c1-new").read)
        assertTrue(catalog.chapters(entryId).none { it.url == "/c1" })

        // "Download new chapters": off by default, then the new chapter is queued once.
        assertEquals(0, downloads.autoDownloadNew(result))
        settings.autoDownloadNew.set(true)
        assertEquals(1, downloads.autoDownloadNew(result))
        assertEquals(0, downloads.autoDownloadNew(result))

        // "Download ahead" while reading chapter 1: chapters 2 and 3 (one release each); chapter 4 is already queued.
        assertEquals(2, downloads.ensureAhead(entryId, chapter(entryId, "/c1-new").id, count = 3))
        val queued = db.downloads().withStatus(listOf(DownloadStatus.QUEUED.name)).mapNotNull { it.chapterId }.toSet()
        assertEquals(setOf("/c2", "/c3", "/c4").map { chapter(entryId, it).id }.toSet(), queued)
    }

    @Test
    fun fullStoragePausesTheQueue(): Unit = runBlocking {
        val entryId = addTitle()
        val c1 = chapter(entryId, "/c1")
        val c2 = chapter(entryId, "/c2")
        val c3 = chapter(entryId, "/c3")
        source.noSpaceFor = "/img/c2/"
        assertEquals(3, downloads.enqueueUnread(entryId))

        val outcome = downloads.runQueue { }

        // Chapter 1 was saved; chapter 2 hit the full storage and the rest of the queue was paused, not failed one by one.
        assertTrue(outcome.outOfSpace)
        assertFalse(outcome.waitingForNetwork)
        assertEquals(0, outcome.failed)
        assertEquals(DownloadStatus.DONE.name, task(c1.id)!!.status)
        assertEquals(DownloadStatus.PAUSED.name, task(c2.id)!!.status)
        assertEquals(context.getString(R.string.download_error_space), task(c2.id)!!.error)
        assertEquals(DownloadStatus.PAUSED.name, task(c3.id)!!.status)
        // The rest of the queue shows the reason too, not a message left from an earlier attempt.
        assertEquals(context.getString(R.string.download_error_space), task(c3.id)!!.error)
        assertFalse(requests.any { it.startsWith("/img/c3/") })

        // After freeing space, "Resume all" puts everything back into the queue.
        source.noSpaceFor = null
        downloads.resumeAll()
        assertEquals(DownloadStatus.QUEUED.name, task(c2.id)!!.status)
        assertEquals(DownloadStatus.QUEUED.name, task(c3.id)!!.status)
    }

    private suspend fun addTitle(): Long {
        val id = catalog.upsert(FakeSource.ID, SItem(url = "/title", title = "Test Manga"))
        catalog.refresh(id)
        assertEquals(4, catalog.chapters(id).size)
        return id
    }

    private suspend fun chapter(entryId: Long, url: String): ChapterInfo = catalog.chapters(entryId).single { it.url == url }

    private suspend fun task(chapterId: Long): DownloadTaskEntity? =
        db.downloads().withStatus(DownloadStatus.entries.map { it.name }).filter { it.chapterId == chapterId }.maxByOrNull { it.id }

    private class FakeSource(private val base: String) : CatalogSource {
        override val id = ID
        override val name = "Fake"
        override val lang = "en"
        override val content = SourceContent.MANGA
        override val supportsLatest = false

        @Volatile var chapters: List<SChapter> = INITIAL

        /** Pages whose address contains this text fail as if the storage were full. */
        @Volatile var noSpaceFor: String? = null

        override suspend fun fetchImage(page: Page, imageUrl: String): okhttp3.Response? {
            val prefix = noSpaceFor
            if (prefix != null && prefix in imageUrl) throw java.io.IOException("write failed: ENOSPC (No space left on device)")
            return null
        }

        override suspend fun getPopular(page: Int) = ItemsPage(emptyList(), false)
        override suspend fun getLatest(page: Int) = ItemsPage(emptyList(), false)
        override suspend fun search(query: String, filters: FilterList, page: Int) = ItemsPage(emptyList(), false)
        override suspend fun getChapters(item: SItem): List<SChapter> = chapters
        override suspend fun getPages(chapter: SChapter): List<Page> {
            val key = chapter.url.trim('/').substringBefore("-new")
            return List(3) { Page(it, imageUrl = "$base/img/$key/$it.png") }
        }

        companion object {
            const val ID = 4242L
        }
    }

    private companion object {
        /** Newest first, like Mihon extensions; chapter 2 also has a release by a second group. */
        val INITIAL = listOf(
            SChapter("/c3", "Chapter 3", 3f, scanlator = "Alpha"),
            SChapter("/c2b", "Chapter 2", 2f, scanlator = "Beta"),
            SChapter("/c2", "Chapter 2", 2f, scanlator = "Alpha"),
            SChapter("/c1", "Chapter 1", 1f, scanlator = "Alpha"),
        )

        /** A 1×1 PNG. */
        val PNG: ByteArray = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
    }
}
