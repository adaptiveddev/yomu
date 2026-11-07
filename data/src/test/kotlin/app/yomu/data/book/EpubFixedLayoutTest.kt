package app.yomu.data.book
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
class EpubFixedLayoutTest {
    private fun book(defaultFixed: Boolean, overrides: List<String>, pages: List<String>): ResourceContainer {
        val manifest = pages.indices.joinToString("") { """<item id="p$it" href="p$it.xhtml" media-type="application/xhtml+xml"/>""" }
        val refs = overrides.mapIndexed { i, prop -> """<itemref idref="p$i" properties="$prop"/>""" }.joinToString("")
        val content = mutableMapOf("book.opf" to """<package><metadata><title>Fixed test</title>${if (defaultFixed) "<meta property=\"rendition:layout\">pre-paginated</meta>" else ""}</metadata><manifest>$manifest</manifest><spine>$refs</spine></package>""")
        pages.forEachIndexed { i, page -> content["p$i.xhtml"] = "<html><body>$page</body></html>" }
        return object : ResourceContainer {
            override val entries get() = content.keys.toList()
            override val entrySet get() = content.keys
            override fun open(path: String): InputStream? = content[path]?.let { ByteArrayInputStream(it.toByteArray()) }
            override fun close() {}
        }
    }
    @Test fun `fixed text page is never mistaken for image-only comic`() {
        val c = book(true, listOf(""), listOf("<img src='a.png'/><p>Positioned text on the page</p>"))
        val pkg = EpubParser.parse(c); assertTrue(pkg.fixedLayout); assertFalse(EpubParser.looksLikeComic(c, pkg))
    }
    @Test fun `fixed image page is routed to fixed renderer`() {
        val c = book(true, listOf(""), listOf("<img src='a.png'/>")); assertFalse(EpubParser.looksLikeComic(c, EpubParser.parse(c)))
    }
    @Test fun `spine layout overrides are preserved`() {
        val c = book(true, listOf("rendition:layout-reflowable", "rendition:layout-pre-paginated page-spread-left", ""), listOf("a", "b", "c"))
        assertEquals(listOf(false, true, null), EpubParser.parse(c).spine.map { it.layoutOverride })
    }
    @Test fun `fixed spine override prevents image reader routing`() {
        val c = book(false, listOf("rendition:layout-pre-paginated"), listOf("<img src='a.png'/>")); assertFalse(EpubParser.looksLikeComic(c, EpubParser.parse(c)))
    }
    @Test fun `ordinary image-only epub remains a comic`() {
        val c = book(false, listOf(""), listOf("<img src='a.png'/>")); assertTrue(EpubParser.looksLikeComic(c, EpubParser.parse(c)))
    }
}
