package app.yomu.feature.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class MangaWorkspaceTest {
    @TempDir
    lateinit var tmp: File
    private lateinit var ws: MangaWorkspace

    @BeforeEach
    fun setUp() {
        val names = (1..5).map { "img/p$it.jpg" }
        names.forEach { File(tmp, it).apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(it.length.toByte())) }
        ws = MangaWorkspace.create(tmp, names, ComicInfo(title = "Vol 1", series = "Series", manga = true))
    }

    private fun order() = ws.pages.map { it.file.substringAfter("img/p").substringBefore('.') }.joinToString("")

    @Test
    fun `create and reload keep pages and info`() {
        assertEquals("12345", order())
        val reloaded = MangaWorkspace(tmp)
        assertEquals(ws.pages, reloaded.pages)
        assertEquals("Vol 1", reloaded.info.title)
        assertTrue(reloaded.info.manga)
    }

    @Test
    fun `move selected pages keeps their order`() {
        ws.moveTo(setOf(0, 2), 2)
        assertEquals("24135", order())
        ws.setCover(4)
        assertEquals("52413", order())
    }

    @Test
    fun `rotate normalises angles`() {
        ws.rotate(setOf(1), -90)
        assertEquals(270, ws.pages[1].rotation)
        ws.rotate(setOf(1), 180)
        assertEquals(90, ws.pages[1].rotation)
        assertEquals(90, MangaWorkspace(tmp).pages[1].rotation)
    }

    @Test
    fun `delete keeps at least one page`() {
        ws.delete(setOf(0, 1))
        assertEquals("345", order())
        assertThrows<IllegalStateException> { ws.delete(setOf(0, 1, 2)) }
    }

    @Test
    fun `insert and replace pages`() {
        File(tmp, "img/x.png").writeBytes(byteArrayOf(1))
        ws.insert(1, listOf("img/x.png"))
        assertEquals(6, ws.pages.size)
        assertEquals("img/x.png", ws.pages[1].file)
        ws.replace(0, listOf(MangaPage("img/p1.jpg"), MangaPage("img/x.png")))
        assertEquals(listOf("img/p1.jpg", "img/x.png", "img/x.png"), ws.pages.take(3).map { it.file })
    }

    @Test
    fun `export writes numbered pages and ComicInfo`() {
        ws.rotate(setOf(0), 90)
        val out = ByteArrayOutputStream()
        ws.exportCbz(out, render = { byteArrayOf(9, 9) to "jpg" })
        val names = mutableListOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
                if (e.name == "ComicInfo.xml") {
                    val info = ComicInfoXml.parse(zip.readBytes().decodeToString())
                    assertEquals("Vol 1", info.title)
                    assertEquals("Series", info.series)
                }
            }
        }
        assertEquals(listOf("0001.jpg", "0002.jpg", "0003.jpg", "0004.jpg", "0005.jpg", "ComicInfo.xml"), names)
    }

    @Test
    fun `ComicInfo xml roundtrip`() {
        val info = ComicInfo(
            title = "Title & <more>", series = "S", number = "3", volume = "1", writer = "W", penciller = "P",
            genre = "Action", languageIso = "ja", summary = "Line one\nLine two", year = "2020", manga = true,
        )
        val xml = ComicInfoXml.write(info, pageCount = 12)
        assertTrue(xml.contains("<PageCount>12</PageCount>"))
        val parsed = ComicInfoXml.parse(xml)
        assertEquals(info.copy(extra = parsed.extra), parsed)
    }
}
