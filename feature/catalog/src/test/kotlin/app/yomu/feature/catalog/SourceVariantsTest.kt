package app.yomu.feature.catalog

import app.yomu.data.source.SourceInfo
import app.yomu.source.SourceContent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/** The language switch of a source (1.9.0): which sources count as languages of the open one, and in what order. */
class SourceVariantsTest {
    private lateinit var locale: Locale

    @BeforeEach
    fun english() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterEach
    fun restore() = Locale.setDefault(locale)

    private fun info(
        id: Long,
        lang: String,
        name: String = "NHentai.xxx",
        pkg: String? = "eu.kanade.tachiyomi.extension.all.nhentaixxx",
        enabled: Boolean = true,
        kind: SourceInfo.Kind = SourceInfo.Kind.EXTENSION,
        content: SourceContent = SourceContent.MANGA,
    ) = SourceInfo(
        id = id, name = name, lang = lang, content = content, kind = kind, pinned = false, enabled = enabled,
        supportsLatest = true, configurable = false, extensionPkg = pkg,
    )

    @Test
    fun `languages of one extension, multilingual first, then by name`() {
        val list = listOf(info(1, "en"), info(2, "ja"), info(3, "all"), info(4, "zh"), info(5, "en", name = "Other", pkg = "other.pkg"))
        val variants = SourceVariants.of(list, 2)
        // All languages, Chinese, English, Japanese; the source of another extension is not one of them.
        assertEquals(listOf(3L, 4L, 1L, 2L), variants.map { it.sourceId })
        assertEquals(listOf("all", "zh", "en", "ja"), variants.map { it.lang })
    }

    @Test
    fun `a source in one language has no switch`() {
        val list = listOf(info(1, "en"), info(2, "ja", name = "Other"), info(3, "ja", pkg = "other.pkg"))
        assertTrue(SourceVariants.of(list, 1).isEmpty())
        assertTrue(SourceVariants.of(list, 42).isEmpty())
    }

    @Test
    fun `disabled languages are left out, the open one stays`() {
        val list = listOf(info(1, "en", enabled = false), info(2, "ja", enabled = false), info(3, "zh"))
        // Chinese, then English (the disabled one that is open); disabled Japanese is not offered.
        assertEquals(listOf(3L, 1L), SourceVariants.of(list, 1).map { it.sourceId })
        assertTrue(SourceVariants.of(list, 3).isEmpty())
    }

    @Test
    fun `one source per language, the open one first`() {
        val list = listOf(info(1, "en"), info(2, "EN"), info(3, "ja"))
        assertEquals(listOf(2L, 3L), SourceVariants.of(list, 2).map { it.sourceId })
    }

    @Test
    fun `opds catalogs and other content are not languages of a source`() {
        val opds = listOf(info(1, "en", kind = SourceInfo.Kind.OPDS, pkg = null), info(2, "ru", kind = SourceInfo.Kind.OPDS, pkg = null))
        assertTrue(SourceVariants.of(opds, 1).isEmpty())
        val mixed = listOf(info(1, "en"), info(2, "ja", content = SourceContent.BOOK))
        assertTrue(SourceVariants.of(mixed, 1).isEmpty())
    }
}
