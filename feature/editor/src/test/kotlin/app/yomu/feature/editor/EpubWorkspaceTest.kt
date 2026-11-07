package app.yomu.feature.editor

import app.yomu.data.book.EpubBuilder
import app.yomu.data.book.EpubMeta
import app.yomu.data.book.EpubParser
import app.yomu.data.book.ZipContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class EpubWorkspaceTest {
    @TempDir
    lateinit var tmp: File
    private lateinit var root: File
    private lateinit var ws: EpubWorkspace

    @BeforeEach
    fun setUp() {
        val builder = EpubBuilder(EpubMeta(title = "Test book", authors = listOf("Author"), language = "en", identifier = "urn:test:1"))
        builder.addChapter("One", "<h2>One</h2><p>The quick brown fox.</p><p>Second paragraph.</p><p>Third paragraph.</p>")
        builder.addChapter("Two", "<h2>Two</h2><p>Another fox jumps.</p>")
        builder.addChapter("Three", "<h2>Three</h2><p>The end.</p>")
        val epub = File(tmp, "book.epub")
        builder.writeTo(epub)
        root = File(tmp, "work")
        ZipFile(epub).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                val dest = File(root, e.name).apply { parentFile.mkdirs() }
                zip.getInputStream(e).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
        ws = EpubWorkspace(root)
    }

    private fun titles() = ws.chapters().map { it.title }

    @Test
    fun `reads chapters and metadata`() {
        assertEquals(listOf("One", "Two", "Three"), titles())
        assertEquals("Test book", ws.meta.title)
        assertEquals("en", ws.meta.language)
        assertTrue(ws.isEpub3)
    }

    @Test
    fun `fresh book has no validation errors`() {
        val errors = ws.validate().filter { it.code.error }
        assertTrue(errors.isEmpty(), "unexpected issues: $errors")
    }

    @Test
    fun `move rename add and delete chapters`() {
        ws.moveChapter(2, 0)
        assertEquals(listOf("Three", "One", "Two"), titles())
        ws.renameChapter(ws.spinePaths[1], "First")
        assertEquals(listOf("Three", "First", "Two"), titles())
        val added = ws.addChapter(0, "Inserted")
        assertEquals(listOf("Three", "Inserted", "First", "Two"), titles())
        assertTrue(ws.file(added).isFile)
        assertEquals(titles(), ws.toc().map { it.title })
        ws.deleteChapter(added)
        assertEquals(listOf("Three", "First", "Two"), titles())
        assertFalse(ws.file(added).exists())
        val errors = ws.validate().filter { it.code.error }
        assertTrue(errors.isEmpty(), "unexpected issues: $errors")
    }

    @Test
    fun `split and merge chapters`() {
        val first = ws.spinePaths[0]
        val blocks = ws.blocks(first)
        val third = blocks.indexOfFirst { it.preview.startsWith("Third") }
        assertTrue(third > 0, "blocks: $blocks")
        val created = ws.splitChapter(first, listOf(third))
        assertEquals(1, created.size)
        assertEquals(4, ws.spinePaths.size)
        assertEquals(created[0], ws.spinePaths[1])
        assertTrue(ws.bodyHtml(created[0]).contains("Third paragraph"))
        assertFalse(ws.bodyHtml(first).contains("Third paragraph"))

        ws.mergeWithNext(first)
        assertEquals(3, ws.spinePaths.size)
        val merged = ws.bodyHtml(first)
        assertTrue(merged.contains("Second paragraph") && merged.contains("Third paragraph"))
        val errors = ws.validate().filter { it.code.error }
        assertTrue(errors.isEmpty(), "unexpected issues: $errors")
    }

    @Test
    fun `visual editor html is saved as well-formed xhtml`() {
        val path = ws.spinePaths[0]
        val head = ws.readText(path).substringBefore("<body")
        // What a WebView gives for a chapter loaded as text/html: void tags left open, named entities, the editing flag.
        ws.replaceBody(
            path,
            "<h2 contenteditable=\"true\">One</h2><p>Fox&nbsp;and <b>dog</b><br>next line</p>" +
                "<p><img src=\"../images/a.png\" alt=\"\"></p><ul><li>item &amp; more</li></ul><hr>",
        )
        val text = ws.readText(path)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val xml = factory.newDocumentBuilder().parse(ws.file(path))
        assertEquals("html", xml.documentElement.localName)
        // The head is kept (only the way empty elements are written may change).
        fun norm(x: String) = x.replace(" />", "/>")
        assertEquals(norm(head), norm(text.substringBefore("<body")))
        assertFalse(text.contains("contenteditable"), text)
        assertFalse(text.contains("&nbsp;"), text)
        assertTrue(text.contains("<b>dog</b>"), text)
        assertTrue(text.contains("item &amp; more"), text)
        assertEquals(1, xml.getElementsByTagNameNS("*", "img").length)
        assertEquals(1, xml.getElementsByTagNameNS("*", "br").length)
        assertTrue(ws.bodyHtml(path).contains("next line"))
        assertEquals(listOf("One", "Two", "Three"), titles())
    }

    @Test
    fun `search and replace in text only`() {
        val scope = ws.documents()
        val hits = ws.search(SearchQuery("fox"), scope)
        assertEquals(2, hits.sumOf { it.count })
        assertTrue(hits.first().previews.first().contains("fox"))

        assertEquals(0, ws.search(SearchQuery("FOX", caseSensitive = true), scope).size)
        assertTrue(ws.search(SearchQuery("para", wholeWord = true), scope).isEmpty())
        assertEquals(1, ws.search(SearchQuery("jumps", wholeWord = true), scope).size)

        val replaced = ws.replaceAll(SearchQuery("fox"), "cat", scope)
        assertEquals(2, replaced)
        assertTrue(ws.search(SearchQuery("fox"), scope).isEmpty())
        assertEquals(2, ws.search(SearchQuery("cat"), scope).sumOf { it.count })
        // Markup is not touched by text search: "body" only exists as a tag name.
        assertTrue(ws.search(SearchQuery("body"), scope).isEmpty())
        assertTrue(ws.search(SearchQuery("body", inMarkup = true), scope).isNotEmpty())
    }

    @Test
    fun `regex replace supports groups`() {
        val scope = ws.documents()
        val n = ws.replaceAll(SearchQuery("(\\w+) paragraph", regex = true), "paragraph $1", scope)
        assertEquals(2, n)
        assertTrue(ws.bodyHtml(ws.spinePaths[0]).contains("paragraph Second"))
    }

    @Test
    fun `metadata edits are written to the opf`() {
        ws.writeMeta(ws.meta.copy(title = "New title", authors = listOf("A", "B"), description = "About"))
        val reread = EpubWorkspace(root).meta
        assertEquals("New title", reread.title)
        assertEquals(listOf("A", "B"), reread.authors)
        assertEquals("About", reread.description)
    }

    @Test
    fun `repacked book can be parsed again`() {
        ws.renameChapter(ws.spinePaths[0], "Renamed")
        val out = File(tmp, "out.epub")
        EpubBuilder.zipDirectory(root, out)
        ZipContainer.open(out).use { c ->
            val pkg = EpubParser.parse(c)
            assertEquals(3, pkg.spine.size)
            assertEquals("Renamed", pkg.toc.first().title)
        }
        ZipFile(out).use { zip -> assertEquals("mimetype", zip.entries().nextElement().name) }
    }

    @Test
    fun `broken markup is detected and normalised`() {
        assertNull(EpubWorkspace.wellFormedError("<html><body><p>ok&nbsp;</p></body></html>"))
        val error = EpubWorkspace.wellFormedError("<html><body><p>broken</body></html>")
        assertNotNull(error)
        val fixed = EpubWorkspace.normalizeXhtml("<html><body><p>broken<br></body></html>")
        assertNull(EpubWorkspace.wellFormedError(fixed), fixed)
    }

    @Test
    fun `fix repairs missing language`() {
        val opf = ws.readText(ws.opfPath)
        ws.writeText(ws.opfPath, opf.replace(Regex("<dc:language>[^<]*</dc:language>"), ""))
        ws.reload()
        val issues = ws.validate()
        assertTrue(issues.any { it.code == IssueCode.NO_LANGUAGE }, "issues: $issues")
        ws.fix(issues)
        assertFalse(ws.validate().any { it.code == IssueCode.NO_LANGUAGE })
    }
}
