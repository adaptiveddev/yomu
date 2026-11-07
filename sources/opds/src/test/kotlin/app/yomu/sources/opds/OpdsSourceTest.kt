package app.yomu.sources.opds

import app.yomu.source.FilterList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections

/** Full OPDS flow against a fake catalog server: root feed, paging by rel="next", OpenSearch and auth. */
class OpdsSourceTest {
    private lateinit var server: MockWebServer
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    private fun entry(n: Int) = """
        <entry>
          <title>Book $n</title>
          <id>urn:book:$n</id>
          <author><name>Author $n</name></author>
          <summary>About book $n</summary>
          <link rel="http://opds-spec.org/image/thumbnail" type="image/jpeg" href="/covers/$n.jpg"/>
          <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/books/$n.epub"/>
        </entry>
    """.trimIndent()

    private fun feed(title: String, links: String, vararg entries: Int) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>$title</title>
          $links
          ${entries.joinToString("\n") { entry(it) }}
        </feed>
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val body = when (request.url.encodedPath) {
                    "/opds" -> feed(
                        "Root",
                        """
                        <link rel="search" type="application/opensearchdescription+xml" href="/opensearch.xml"/>
                        <link rel="http://opds-spec.org/sort/popular" type="application/atom+xml;profile=opds-catalog" href="popular"/>
                        """.trimIndent(),
                    )
                    "/popular" -> if (request.url.queryParameter("page") == "2") {
                        feed("Popular 2", "", 3)
                    } else {
                        feed("Popular", """<link rel="next" type="application/atom+xml" href="/popular?page=2"/>""", 1, 2)
                    }
                    "/opensearch.xml" -> """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
                          <Url type="application/atom+xml;profile=opds-catalog" template="/search?q={searchTerms}&amp;page={startPage?}"/>
                        </OpenSearchDescription>
                    """.trimIndent()
                    "/search" -> feed("Results", "", 7)
                    else -> return MockResponse.Builder().code(404).build()
                }
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/atom+xml").body(body).build()
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun source(user: String? = null, pass: String? = null) =
        OpdsSource("Test", server.url("/opds").toString(), username = user, password = pass)

    @Test
    fun `popular follows next links page by page`() = runBlocking {
        val source = source()
        val first = source.getPopular(1)
        assertEquals(listOf("Book 1", "Book 2"), first.items.map { it.title })
        assertTrue(first.hasNextPage)
        val book = first.items.first()
        assertEquals("Author 1", book.author)
        assertEquals("About book 1", book.description)
        assertEquals(server.url("/covers/1.jpg").toString(), book.thumbnailUrl)
        assertEquals(server.url("/books/1.epub").toString(), book.links.single().url)
        assertEquals("application/epub+zip", book.links.single().mimeType)
        assertFalse(book.isNavigation)

        val second = source.getPopular(2)
        assertEquals(listOf("Book 3"), second.items.map { it.title })
        assertFalse(second.hasNextPage)

        // A page that was never linked returns nothing without hitting the network.
        val before = requests.size
        assertTrue(source.getPopular(3).items.isEmpty())
        assertEquals(before, requests.size)
        // The root feed is loaded once and cached.
        assertEquals(1, requests.count { it.url.encodedPath == "/opds" })
    }

    @Test
    fun `search uses the OpenSearch template`() = runBlocking {
        val result = source().search("war and peace", FilterList(), 1)
        assertEquals(listOf("Book 7"), result.items.map { it.title })
        val search = requests.last()
        assertEquals("/search", search.url.encodedPath)
        assertEquals("war and peace", search.url.queryParameter("q"))
    }

    @Test
    fun `basic auth and accept headers are sent`() = runBlocking {
        source("user", "pass").getPopular(1)
        val root = requests.first()
        assertEquals("Basic dXNlcjpwYXNz", root.headers["Authorization"])
        assertTrue(root.headers["Accept"].orEmpty().contains("application/atom+xml"))
    }
}
