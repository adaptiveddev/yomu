package app.yomu.sources.mangadex

import app.yomu.source.FilterList
import app.yomu.source.HttpStatusException
import app.yomu.source.SChapter
import app.yomu.source.SItem
import app.yomu.source.Status
import app.yomu.source.WebViewRequiredException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** MangaDex source against a fake API server (no real network). */
class MangaDexSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: MangaDexSource

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = MangaDexSource("en", apiUrl = server.url("/").toString().trimEnd('/'), coversUrl = "https://covers.test")
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun respond(body: String, code: Int = 200, server: String? = null) = this.server.enqueue(
        MockResponse.Builder().code(code).body(body).apply { server?.let { setHeader("Server", it) } }.build(),
    )

    private val mangaList = """
        {"result":"ok","limit":20,"offset":0,"total":45,"data":[
          {"id":"m1","type":"manga","attributes":{
             "title":{"en":"Test Manga"},"altTitles":[{"ja":"テスト"}],"description":{"en":"A description"},
             "status":"ongoing","tags":[{"id":"t1","type":"tag","attributes":{"name":{"en":"Action"}}}]},
           "relationships":[
             {"id":"a1","type":"author","attributes":{"name":"Author A"}},
             {"id":"a1","type":"artist","attributes":{"name":"Author A"}},
             {"id":"c1","type":"cover_art","attributes":{"fileName":"cover.jpg"}}]},
          {"id":"m2","type":"manga","attributes":{"title":{"ja-ro":"Romaji Only"},"status":"completed"},"relationships":[]}
        ]}
    """.trimIndent()

    @Test
    fun `popular parses titles and builds the request`() = runBlocking {
        respond(mangaList)
        val page = source.getPopular(1)
        assertTrue(page.hasNextPage)
        assertEquals(2, page.items.size)
        val first = page.items[0]
        assertEquals("/manga/m1", first.url)
        assertEquals("Test Manga", first.title)
        assertEquals("Author A", first.author)
        assertEquals("Author A", first.artist)
        assertEquals("A description", first.description)
        assertEquals(listOf("Action"), first.genres)
        assertEquals(Status.ONGOING, first.status)
        assertEquals("https://covers.test/covers/m1/cover.jpg.256.jpg", first.thumbnailUrl)
        assertEquals("Romaji Only", page.items[1].title)
        assertEquals(Status.COMPLETED, page.items[1].status)

        val request = server.takeRequest()
        assertEquals("/manga", request.url.encodedPath)
        assertEquals("20", request.url.queryParameter("limit"))
        assertEquals("0", request.url.queryParameter("offset"))
        assertEquals("desc", request.url.queryParameter("order[followedCount]"))
        assertEquals(listOf("en"), request.url.queryParameterValues("availableTranslatedLanguage[]"))
        assertEquals(listOf("safe", "suggestive"), request.url.queryParameterValues("contentRating[]"))
        assertEquals("https://mangadex.org/", request.headers["Referer"])
    }

    @Test
    fun `descriptions of other shapes do not break the page`() = runBlocking {
        // Real API answer shapes (Yomu 1.1.0 failed with "Unexpected JSON token ... relationships[5].attributes.description"):
        // covers and groups have a plain-string description, tags may have an empty array.
        respond(
            """
            {"result":"ok","limit":20,"offset":0,"total":1,"data":[
              {"id":"m1","type":"manga","attributes":{
                 "title":{"ko":"제목"},"altTitles":[{"ja":"題名"},{"en":"English Title"}],
                 "description":{"ru":"","en":"  Story  "},"status":"hiatus",
                 "tags":[{"id":"t1","type":"tag","attributes":{"name":{"en":"Drama"},"description":[]}}]},
               "relationships":[
                 {"id":"c1","type":"cover_art","attributes":{"description":"","volume":"4","fileName":"c.png","locale":"ko"}},
                 {"id":"g1","type":"scanlation_group","attributes":{"name":"Group","description":"About the group"}}]}
            ]}
            """.trimIndent(),
        )
        val item = source.getPopular(1).items.single()
        assertEquals("English Title", item.title)
        assertEquals("Story", item.description)
        assertEquals(listOf("Drama"), item.genres)
        assertEquals(Status.HIATUS, item.status)
        assertEquals("https://covers.test/covers/m1/c.png.256.jpg", item.thumbnailUrl)
    }

    @Test
    fun `second page uses offset and last page has no next`() = runBlocking {
        respond("""{"result":"ok","limit":20,"offset":40,"total":45,"data":[]}""")
        val page = source.getLatest(3)
        assertFalse(page.hasNextPage)
        val request = server.takeRequest()
        assertEquals("40", request.url.queryParameter("offset"))
        assertEquals("desc", request.url.queryParameter("order[latestUploadedChapter]"))
    }

    @Test
    fun `search sends title and relevance order`() = runBlocking {
        respond(mangaList)
        source.search("one piece", FilterList(), 1)
        val request = server.takeRequest()
        assertEquals("one piece", request.url.queryParameter("title"))
        assertEquals("desc", request.url.queryParameter("order[relevance]"))
    }

    @Test
    fun `chapters skip external links and parse names, dates and groups`() = runBlocking {
        respond(
            """
            {"result":"ok","limit":500,"offset":0,"total":3,"data":[
              {"id":"c2","type":"chapter","attributes":{"volume":"1","chapter":"2","title":"Second","publishAt":"2024-01-15T10:00:00+00:00"},
               "relationships":[{"id":"g1","type":"scanlation_group","attributes":{"name":"Group"}}]},
              {"id":"c1","type":"chapter","attributes":{"chapter":"1","title":"","publishAt":"2024-01-01T00:00:00+00:00"},"relationships":[]},
              {"id":"cx","type":"chapter","attributes":{"chapter":"0","externalUrl":"https://elsewhere.test/ch"},"relationships":[]}
            ]}
            """.trimIndent(),
        )
        val chapters = source.getChapters(SItem(url = "/manga/m1", title = "Test"))
        assertEquals(
            listOf(
                SChapter("/chapter/c2", "Vol.1 Ch.2 - Second", 2f, 1_705_312_800_000L, "Group"),
                SChapter("/chapter/c1", "Ch.1", 1f, 1_704_067_200_000L, null),
            ),
            chapters,
        )
        val request = server.takeRequest()
        assertEquals("/manga/m1/feed", request.url.encodedPath)
        assertEquals(listOf("en"), request.url.queryParameterValues("translatedLanguage[]"))
    }

    @Test
    fun `chapter feed without paging info does not loop forever`() = runBlocking {
        respond("""{"result":"ok","total":10,"data":[{"id":"c1","type":"chapter","attributes":{"chapter":"1"},"relationships":[]}]}""")
        val chapters = source.getChapters(SItem(url = "/manga/m1", title = "Test"))
        assertEquals(1, chapters.size)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `pages come from the at-home server`() = runBlocking {
        respond("""{"result":"ok","baseUrl":"https://node.test","chapter":{"hash":"h1","data":["1.png","2.png"],"dataSaver":[]}}""")
        val pages = source.getPages(SChapter("/chapter/c1", "Ch.1"))
        assertEquals(listOf("https://node.test/data/h1/1.png", "https://node.test/data/h1/2.png"), pages.map { it.imageUrl })
        assertEquals(listOf(0, 1), pages.map { it.index })
        assertEquals("/at-home/server/c1", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `cloudflare challenge asks for web view, other errors keep the status`() {
        respond("challenge", code = 503, server = "cloudflare")
        assertThrows<WebViewRequiredException> { runBlocking { source.getPopular(1) } }
        respond("missing", code = 404)
        val e = assertThrows<HttpStatusException> { runBlocking { source.getPopular(1) } }
        assertEquals(404, e.code)
    }

    @Test
    fun `api requests identify the app instead of a browser`() = runBlocking {
        respond(mangaList)
        source.getPopular(1)
        val ua = server.takeRequest().headers["User-Agent"].orEmpty()
        assertTrue(ua.startsWith("Yomu/"), ua)
        assertFalse(ua.contains("Mozilla"), ua)
        assertTrue(source.imageHeaders()["User-Agent"].orEmpty().startsWith("Yomu/"))
    }

    @Test
    fun `html anti-bot page becomes a webview request, not a json error`() {
        respond("<!doctype html><html><body>challenge</body></html>")
        assertThrows<WebViewRequiredException> { runBlocking { source.getPopular(1) } }
    }
}
