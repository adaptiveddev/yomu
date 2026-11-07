package app.yomu.sources.opds

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpdsParserTest {
    private val base = "https://books.example.org/opds/root.xml"

    private val atom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog">
          <title>Example Library</title>
          <link rel="next" href="root.xml?page=2" type="application/atom+xml;profile=opds-catalog"/>
          <link rel="search" href="/opds/search.xml" type="application/opensearchdescription+xml"/>
          <link rel="http://opds-spec.org/sort/new" href="new.xml" type="application/atom+xml;profile=opds-catalog"/>
          <entry>
            <title>New books</title>
            <id>urn:new</id>
            <link rel="subsection" href="new.xml" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
          </entry>
          <entry>
            <title>Pride and Prejudice</title>
            <id>urn:book:1</id>
            <author><name>Jane Austen</name></author>
            <dc:language>en</dc:language>
            <category term="fiction" label="Fiction"/>
            <summary type="html">&lt;p&gt;A &lt;b&gt;classic&lt;/b&gt; novel.&lt;/p&gt;</summary>
            <link rel="http://opds-spec.org/image/thumbnail" href="covers/1-small.jpg" type="image/jpeg"/>
            <link rel="http://opds-spec.org/image" href="covers/1.jpg" type="image/jpeg"/>
            <link rel="http://opds-spec.org/acquisition/open-access" href="books/1.epub" type="application/epub+zip" length="12345"/>
            <link rel="http://opds-spec.org/acquisition/buy" href="shop/1" type="text/html"/>
            <link rel="http://opds-spec.org/acquisition" href="books/1.fb2.zip" type="application/fb2+zip" title="FB2"/>
          </entry>
          <entry><id>urn:no-title</id></entry>
        </feed>
    """.trimIndent()

    @Test
    fun `atom feed with navigation and acquisition entries`() {
        val feed = OpdsParser.parse(atom, base)
        assertEquals("Example Library", feed.title)
        assertEquals("https://books.example.org/opds/root.xml?page=2", feed.nextUrl)
        assertEquals("https://books.example.org/opds/search.xml", feed.searchUrl)
        assertTrue(feed.searchIsOpenSearchDescription)
        assertEquals("https://books.example.org/opds/new.xml", feed.newUrl)
        assertEquals(2, feed.items.size)

        val nav = feed.items[0]
        assertTrue(nav.isNavigation)
        assertEquals("https://books.example.org/opds/new.xml", nav.url)

        val book = feed.items[1]
        assertFalse(book.isNavigation)
        assertEquals("Jane Austen", book.author)
        assertEquals("en", book.language)
        assertEquals(listOf("Fiction"), book.genres)
        assertEquals("A classic novel.", book.description)
        assertEquals("https://books.example.org/opds/covers/1-small.jpg", book.thumbnailUrl)
        assertEquals(listOf("https://books.example.org/opds/books/1.epub", "https://books.example.org/opds/books/1.fb2.zip"), book.links.map { it.url })
        assertEquals(12345L, book.links[0].size)
        assertEquals("FB2", book.links[1].title)
        assertTrue(feed.toPage().hasNextPage)
    }

    @Test
    fun `open search template is filled`() {
        val osd = """<?xml version="1.0"?><OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
            <Url type="text/html" template="/html?q={searchTerms}"/>
            <Url type="application/atom+xml" template="/opds/search?q={searchTerms}&amp;page={startPage?}"/>
            </OpenSearchDescription>"""
        val template = OpdsParser.parseOpenSearch(osd, base)
        assertEquals("https://books.example.org/opds/search?q={searchTerms}&page={startPage?}", template)
        assertEquals("https://books.example.org/opds/search?q=war+%26+peace&page=", OpdsParser.fillTemplate(template!!, "war & peace"))
        assertEquals("https://x.test/s?query=abc", OpdsParser.fillTemplate("https://x.test/s{?query}", "abc"))
    }

    @Test
    fun `opds 2 json feed`() {
        val json = """
            {
              "metadata": {"title": "JSON Library"},
              "links": [{"rel": "next", "href": "page2.json"}, {"rel": ["search"], "href": "search{?query}", "templated": true}],
              "navigation": [{"href": "popular.json", "title": "Popular"}],
              "publications": [{
                "metadata": {"title": "Moby Dick", "author": [{"name": "Herman Melville"}], "identifier": "urn:isbn:1", "subject": ["Sea"]},
                "links": [{"rel": "http://opds-spec.org/acquisition", "href": "/files/moby.epub", "type": "application/epub+zip"}],
                "images": [{"href": "covers/moby.jpg"}]
              }],
              "groups": [{"publications": [{"metadata": {"title": {"en": "Ulysses"}}, "links": []}]}]
            }
        """.trimIndent()
        val feed = OpdsParser.parse(json, "https://books.example.org/catalog/index.json")
        assertEquals("JSON Library", feed.title)
        assertEquals("https://books.example.org/catalog/page2.json", feed.nextUrl)
        assertEquals("https://books.example.org/catalog/search{?query}", feed.searchUrl)
        assertEquals(listOf("Popular", "Moby Dick", "Ulysses"), feed.items.map { it.title })
        assertTrue(feed.items[0].isNavigation)
        val moby = feed.items[1]
        assertEquals("Herman Melville", moby.author)
        assertEquals("https://books.example.org/files/moby.epub", moby.links.single().url)
        assertEquals("https://books.example.org/catalog/covers/moby.jpg", moby.thumbnailUrl)
        assertEquals(listOf("Sea"), moby.genres)
    }
}
