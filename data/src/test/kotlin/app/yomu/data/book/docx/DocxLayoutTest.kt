package app.yomu.data.book.docx

import app.yomu.data.book.ResourceContainer
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Structure of the print layout HTML. The rendered documents are also written to build/docx-layout/<name>/ together with the
 * layout assets, so that the pagination can be checked in a browser (see docs/DEVELOPMENT.md).
 */
class DocxLayoutTest {
    private class MapContainer(private val files: Map<String, ByteArray>) : ResourceContainer {
        override val entries: List<String> = files.keys.toList()
        override val entrySet: Set<String> = files.keys
        override fun open(path: String): InputStream? = files[path]?.inputStream()
        override fun close() = Unit
    }

    private fun container(resource: String): MapContainer {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(javaClass.classLoader!!.getResourceAsStream(resource)!!).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) files[e.name] = zip.readBytes()
            }
        }
        return MapContainer(files)
    }

    private fun render(name: String): Pair<DocxLayout.Result, Map<String, ByteArray>> {
        val media = LinkedHashMap<String, ByteArray>()
        val result = DocxLayout.render(container("docx/$name.docx")) { n, b -> media[n] = b }
        val dir = File("build/docx-layout/$name").apply { deleteRecursively(); mkdirs() }
        File(dir, "index.html").writeText(result.html)
        media.forEach { (n, b) -> File(dir, "${DocxLayout.MEDIA_DIR}/$n").apply { parentFile?.mkdirs() }.writeBytes(b) }
        File("src/main/assets/docx").copyRecursively(dir, overwrite = true)
        return result to media
    }

    @Test
    fun rendersSections() {
        val (result, media) = render("sample")
        val doc = Jsoup.parse(result.html)
        val sections = doc.select("#src > section.sec")
        assertTrue("sections: ${sections.size}", sections.size >= 2)
        // Portrait A4 first, the landscape section is wider than it is high.
        assertEquals("793.73", sections[0].attr("data-w"))
        assertTrue(sections.any { it.attr("data-w").toDouble() > it.attr("data-h").toDouble() })
        assertTrue(doc.select("template[data-k=hdr-default]").isNotEmpty())
        val footer = doc.select("template[data-k=ftr-default]").first()!!
        assertTrue(footer.select(".f-page").isNotEmpty())
        assertTrue(footer.select(".f-pages").isNotEmpty())
        assertEquals(1, media.size)
        assertEquals(1, doc.select("img.im").size)
        assertTrue(doc.select(".pb").isNotEmpty())
    }

    @Test
    fun rendersTablesAndLists() {
        val doc = Jsoup.parse(render("sample").first.html)
        val tables = doc.select("table.tbl")
        assertTrue("tables: ${tables.size}", tables.size >= 3)
        assertTrue(doc.select("tr[data-hdr]").isNotEmpty())
        assertTrue(doc.select("td[rowspan]").isNotEmpty())
        assertTrue(doc.select("td[colspan=4]").isNotEmpty())
        val html = doc.html()
        assertTrue(html.contains("background:#D9E2F3"))
        assertTrue(html.contains("background:#FFF2CC"))
        val labels = doc.select("span.ll").map { it.text() }
        assertTrue(labels.toString(), labels.containsAll(listOf("1.", "2.", "3.")))
        assertTrue(labels.toString(), labels.any { it == "•" })
        // Justified body text with a first line indent, headings keep with the next paragraph.
        assertTrue(doc.select("div.p.j").isNotEmpty())
        assertTrue(doc.select("div.p[data-kn]").isNotEmpty())
    }

    @Test
    fun numberFormats() {
        assertEquals("xiv", DocxLayout.numberText(14, "lowerRoman"))
        assertEquals("C", DocxLayout.numberText(3, "upperLetter"))
        assertEquals("aa", DocxLayout.numberText(27, "lowerLetter"))
        assertEquals("в", DocxLayout.numberText(3, "russianLower"))
        assertEquals("07", DocxLayout.numberText(7, "decimalZero"))
        assertEquals("•", DocxLayout.symbolChar('\uF0B7', "Symbol"))
        assertEquals("▪", DocxLayout.symbolChar('\uF0A7', "Wingdings"))
    }
}
