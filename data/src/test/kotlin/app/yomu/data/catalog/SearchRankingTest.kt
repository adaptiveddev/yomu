package app.yomu.data.catalog

import app.yomu.source.SBookLink
import app.yomu.source.SItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchRankingTest {
    private fun item(title: String) = SItem(url = "/" + title.hashCode(), title = title)

    @Test
    fun `normalizes case accents and punctuation`() {
        assertEquals("kaguya sama love is war", SearchRanking.normalize("Kaguya-sama: Love Is War"))
        assertEquals("pokemon", SearchRanking.normalize("Pokémon"))
        assertEquals("еж", SearchRanking.normalize("Ёж!"))
    }

    @Test
    fun `exact and prefix matches come first, source order kept inside groups`() {
        val items = listOf(
            item("The Berserk Chronicles"),
            item("Berserk of Gluttony"),
            item("Something else"),
            item("Berserk"),
            item("Berserk: Prototype"),
        )
        val ranked = SearchRanking.rank(items, "berserk").map { it.title }
        assertEquals(listOf("Berserk", "Berserk of Gluttony", "Berserk: Prototype", "The Berserk Chronicles", "Something else"), ranked)
    }

    @Test
    fun `all words in any order rank above unrelated results`() {
        val items = listOf(item("Other"), item("War and Peace"), item("Peace of War"))
        assertEquals(listOf("War and Peace", "Peace of War", "Other"), SearchRanking.rank(items, "war peace").map { it.title })
        assertEquals(items, SearchRanking.rank(items, " "))
    }

    @Test
    fun `book links prefer epub and drop duplicates`() {
        val links = listOf(
            SBookLink("https://x/book.pdf", "application/pdf"),
            SBookLink("https://x/book.txt", "text/plain"),
            SBookLink("https://x/book.epub?dl=1", "application/octet-stream"),
            SBookLink("https://x/book.fb2.zip", "application/zip"),
            SBookLink("https://x/book.pdf", "application/pdf"),
        )
        val sorted = SearchRanking.sortLinks(links).map { it.url.substringAfterLast('.') }
        assertEquals(listOf("epub?dl=1", "zip", "pdf", "txt"), sorted)
    }
}
