package app.yomu.data.book

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class EpubRoundTripTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `builder output is parsed back`() {
        val b = EpubBuilder(
            EpubMeta(
                title = "Книга", authors = listOf("Автор", "Второй"), language = "ru", identifier = "urn:test:42",
                description = "Описание", series = "Цикл", seriesIndex = 2f,
            ),
        )
        b.coverHref = b.addResource("cover.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        b.addChapter("Part 1", "<p>a</p>")
        b.addChapter("Chapter 1 & more", "<p>b <b>bold</b><br></p>", level = 1)
        b.addChapter("Chapter 2", "<p>c</p>", level = 1)
        val file = File(tmp, "b.epub")
        b.writeTo(file)

        ZipFile(file).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
        }
        ZipContainer.open(file).use { c ->
            val pkg = EpubParser.parse(c)
            assertEquals("Книга", pkg.meta.title)
            assertEquals(listOf("Автор", "Второй"), pkg.meta.authors)
            assertEquals("ru", pkg.meta.language)
            assertEquals("urn:test:42", pkg.meta.identifier)
            assertEquals("Описание", pkg.meta.description)
            assertEquals("Цикл", pkg.meta.series)
            assertEquals(2f, pkg.meta.seriesIndex)
            assertEquals(3, pkg.spine.size)
            assertEquals(listOf("Part 1", "Chapter 1 & more", "Chapter 2"), pkg.toc.map { it.title })
            assertEquals(listOf(0, 1, 1), pkg.toc.map { it.level })
            val cover = assertNotNull(pkg.coverPath).let { pkg.coverPath!! }
            assertTrue(pkg.item(cover)!!.isImage)
            assertNotNull(pkg.navPath)
            assertNotNull(pkg.ncxPath)
            // Chapters are well formed XHTML.
            pkg.spine.forEach { item ->
                val xml = c.open(item.path)!!.use { it.readBytes() }
                EpubParser.parseXml(xml)
                assertTrue(String(xml).contains("xmlns=\"http://www.w3.org/1999/xhtml\""))
            }
        }
    }

    @Test
    fun `zipDirectory writes mimetype first and uncompressed`() {
        val dir = File(tmp, "dir").apply { mkdirs() }
        File(dir, "OEBPS").mkdirs()
        File(dir, "OEBPS/a.txt").writeText("a")
        File(dir, "mimetype").writeText("application/epub+zip")
        val out = File(tmp, "out.epub")
        EpubBuilder.zipDirectory(dir, out)
        ZipFile(out).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertNotNull(zip.getEntry("OEBPS/a.txt"))
        }
    }

    @Test
    fun `paths are resolved relative to the document`() {
        assertEquals("OEBPS/images/a b.png", EpubPaths.resolve("OEBPS/text/ch1.xhtml", "../images/a%20b.png#frag"))
        assertEquals("OEBPS/text/ch1.xhtml", EpubPaths.resolve("OEBPS/text/ch1.xhtml", "#only-anchor"))
        assertEquals("root.css", EpubPaths.resolve("OEBPS/text/ch1.xhtml", "/root.css"))
        assertEquals("a/c", EpubPaths.normalize("a/./b/../c"))
        assertEquals("../images/a.png", EpubPaths.relative("OEBPS/text/ch1.xhtml", "OEBPS/images/a.png"))
        assertEquals("ch2.xhtml", EpubPaths.relative("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"))
    }
}
