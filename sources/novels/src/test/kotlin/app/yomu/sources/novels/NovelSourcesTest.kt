package app.yomu.sources.novels

import app.yomu.source.FilterList
import app.yomu.source.SChapter
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import app.yomu.source.Status
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The two built-in novel sources against fake servers with trimmed copies of the real answers. */
class NovelSourcesTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun respond(body: String, type: String = "application/json") =
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", type).body(body).build())

    private val base get() = server.url("/").toString().trimEnd('/')

    @Test
    fun proseMirrorBecomesHtml() {
        val doc = Json.parseToJsonElement(
            """{"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"Hello "},{"type":"text","marks":[{"type":"bold"}],"text":"<world>"}]},
              {"type":"horizontalRule"},
              {"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","marks":[{"type":"italic"}],"text":"quote"}]}]},
              {"type":"image","attrs":{"images":[{"image":"pic"}]}},
              {"type":"paragraph"}]}""",
        )
        val html = ProseMirror.toHtml(doc) { if (it == "pic") "https://img.test/pic.jpg" else null }
        assertEquals(
            "<p>Hello <b>&lt;world&gt;</b></p><hr/><blockquote><p><i>quote</i></p></blockquote><p><img src=\"https://img.test/pic.jpg\" alt=\"\"/></p><p>&nbsp;</p>",
            html,
        )
        assertEquals("Hello <world>", ProseMirror.toText(Json.parseToJsonElement("""{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Hello <world>"}]}]}""")))
    }

    @Test
    fun ranobeLibListsDetailsChaptersAndText() = runBlocking {
        val source = RanobeLibSource(apiUrl = base, baseUrl = "https://ranobelib.test")
        respond(
            """{"data":[{"id":1,"name":"Jeonjijeog","rus_name":"Точка зрения","eng_name":"ORV","slug_url":"1--orv",
               "cover":{"default":"https://cover.test/1.jpg"},"status":{"id":2}}],"meta":{"has_next_page":true}}""",
        )
        val page = source.getPopular(1)
        val list = server.takeRequest()
        assertEquals("3", list.headers["Site-Id"])
        assertTrue(list.url.toString().contains("sort_by=views"), list.url.toString())
        assertEquals(listOf("Точка зрения"), page.items.map { it.title })
        assertEquals(Status.COMPLETED, page.items[0].status)
        assertEquals(SourceContent.NOVEL, page.items[0].type)
        assertTrue(page.hasNextPage)

        respond(
            """{"data":{"slug_url":"1--orv","rus_name":"Точка зрения","eng_name":"ORV","name":"Jeonjijeog",
               "summary":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Описание"}]}]},
               "genres":[{"name":"Фэнтези"}],"tags":[{"name":"Апокалипсис"}],"authors":[{"name":"singNsong"}],"artists":[],"status":{"id":1}}}""",
        )
        val details = source.getDetails(page.items[0])
        server.takeRequest()
        assertEquals("singNsong", details.author)
        assertEquals(listOf("Фэнтези", "Апокалипсис"), details.genres)
        assertTrue(details.description!!.startsWith("Описание"))
        assertEquals(Status.ONGOING, details.status)

        respond(
            """{"data":[
              {"volume":"1","number":"1","name":"Начало","branches":[{"branch_id":10,"created_at":"2024-03-28T01:17:52.000000Z","teams":[{"name":"Team A"}]},
                                                                  {"branch_id":11,"created_at":"2024-03-29T01:17:52.000000Z","teams":[{"name":"Team B"}]}]},
              {"volume":"1","number":"2","name":"","branches":[{"branch_id":null,"created_at":"2024-04-01T00:00:00.000000Z","teams":[]}]}]}""",
        )
        val chapters = source.getChapters(details)
        server.takeRequest()
        assertEquals(listOf("Том 1 Глава 2", "Том 1 Глава 1 — Начало", "Том 1 Глава 1 — Начало"), chapters.map { it.name })
        assertEquals(listOf(2f, 1f, 1f), chapters.map { it.number })
        assertEquals("Team B", chapters[0 + 1].scanlator.let { chapters.first { c -> c.url.endsWith("|11") }.scanlator })
        assertTrue(chapters.all { it.dateUpload > 0 })

        respond(
            """{"data":{"content":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Текст главы"}]},
               {"type":"image","attrs":{"images":[{"image":"a1"}]}}]},
               "attachments":[{"name":"a1","filename":"a1.jpg","url":"/uploads/a1.jpg"}]}}""",
        )
        val text = source.getContent(chapters.first { it.url.endsWith("|10") })
        val request = server.takeRequest().url.toString()
        assertTrue(request.contains("number=1") && request.contains("volume=1") && request.contains("branch_id=10"), request)
        assertEquals("<p>Текст главы</p><p><img src=\"https://ranobelib.test/uploads/a1.jpg\" alt=\"\"/></p>", text)

        respond("""{"data":{"content":"<p>Old <b>HTML</b> chapter</p>","attachments":[]}}""")
        assertEquals("<p>Old <b>HTML</b> chapter</p>", source.getContent(SChapter(url = "1--orv|1|2|", name = "x")))
    }

    @Test
    fun novelBinListsDetailsChaptersAndText() = runBlocking {
        val source = NovelBinSource(baseUrl = base)
        respond(
            """<html><body><div class="list list-novel"><div class="row">
                 <img src="$base/novel_80_113/shadow-slave.jpg" class="cover">
                 <h3 class="novel-title"><a href="$base/b/shadow-slave" title="Shadow Slave">Shadow Slave</a></h3>
                 <span class="author"><span class="glyphicon"></span>Guiltythree </span></div></div>
               <ul class="pagination"><li class="active"><a>1</a></li><li class="next"><a href="?page=2">›</a></li></ul></body></html>""",
            "text/html",
        )
        val page = source.search("shadow", FilterList(), 1)
        assertTrue(server.takeRequest().url.toString().contains("/search?keyword=shadow"))
        val item = page.items.single()
        assertEquals("/b/shadow-slave", item.url)
        assertEquals("Guiltythree", item.author)
        assertEquals("$base/novel/shadow-slave.jpg", item.thumbnailUrl)
        assertTrue(page.hasNextPage)

        respond(
            """<html><body><h3 class="title">Shadow Slave</h3><div class="book"><img src="$base/novel/shadow-slave.jpg"></div>
               <ul class="info info-meta"><li><h3>Author:</h3><a>Guiltythree</a></li>
                 <li><h3>Genre:</h3><a>Action</a><a>Fantasy</a></li><li><h3>Status:</h3><a>Ongoing</a></li></ul>
               <div class="desc-text" id="novel-description-content"><p>Growing up in poverty.</p><p>Second.</p></div></body></html>""",
            "text/html",
        )
        val details = source.getDetails(item)
        server.takeRequest()
        assertEquals(listOf("Action", "Fantasy"), details.genres)
        assertEquals(Status.ONGOING, details.status)
        assertEquals("Growing up in poverty.\n\nSecond.", details.description)

        respond(
            """<html><body><template><li data-chapter-item><a href="$base/b/shadow-slave/chapter-1-nightmare-begins" title="Chapter 1 Nightmare Begins">x</a></li></template>
               <ul class="list-chapter"><li data-chapter-item><a href="$base/b/shadow-slave/chapter-1-nightmare-begins" title="Chapter 1 Nightmare Begins">x</a></li>
               <li data-chapter-item><a href="$base/b/shadow-slave/chapter-2-slave-caravan" title="Chapter 2 Slave Caravan">x</a></li></ul></body></html>""",
            "text/html",
        )
        val chapters = source.getChapters(details)
        assertTrue(server.takeRequest().url.toString().contains("/ajax/chapter-archive?novelId=shadow-slave"))
        assertEquals(listOf("Chapter 2 Slave Caravan", "Chapter 1 Nightmare Begins"), chapters.map { it.name })
        assertEquals(listOf(2f, 1f), chapters.map { it.number })

        respond(
            """<html><body><div id="chr-content"><div class="js-ad-slot">AD</div><script>x()</script>
               <p>A frail-looking young man.</p><p style="display:none">stolen notice</p><p> </p><p>Second line.</p></div></body></html>""",
            "text/html",
        )
        val text = source.getContent(chapters.last())
        assertEquals("<p>A frail-looking young man.</p>\n<p>Second line.</p>", text)
        assertFalse(text.contains("AD") || text.contains("stolen"))
    }

    @Test
    fun novelBinChapterNumbers() {
        assertEquals(3235f, NovelBinSource.chapterNumber("Chapter 3235 Bitter Fate"))
        assertEquals(12.5f, NovelBinSource.chapterNumber("Chapter 12.5 - Side"))
        assertEquals(-1f, NovelBinSource.chapterNumber("Prologue"))
        assertEquals(null, NovelBinSource.chapterHtml(Jsoup.parse("<div id='chr-content'><script>a</script></div>")))
    }
}
