package app.yomu.sources.novels

import app.yomu.source.CatalogSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** The real sites (network): `./gradlew :sources:novels:test -Pyomu.liveTests=true`. */
class NovelSourcesLiveTest {
    private fun check(source: CatalogSource) = runBlocking {
        assumeTrue(System.getProperty("yomu.liveTests") == "true")
        val page = source.getPopular(1)
        assertTrue(page.items.isNotEmpty(), "$source: empty list")
        val details = source.getDetails(page.items.first())
        val chapters = source.getChapters(details)
        assertTrue(chapters.isNotEmpty(), "$source: no chapters for ${details.title}")
        val text = source.getContent(chapters.last())
        assertTrue(text.length > 200, "$source: short chapter ${chapters.last().name}: $text")
        println("$source OK: ${details.title} — ${chapters.size} chapters, first ${chapters.last().name} (${text.length} chars)")
    }

    @Test
    fun ranobeLib() = check(RanobeLibSource())

    @Test
    fun novelBin() = check(NovelBinSource())
}
