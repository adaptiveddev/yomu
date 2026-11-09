package app.yomu.sources.opds

import app.yomu.source.MIME_AUDIO_FEED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibriVoxSourceTest {
    @Test
    fun parsesBooksWithListenAndZipLinks() {
        val json = """{"books":[{"id":"314","title":"Adventures of Sherlock Holmes","description":"<p>Twelve <i>stories</i>.</p>",
            "language":"English","url_rss":"https:\/\/librivox.org\/rss\/314",
            "url_zip_file":"https:\/\/archive.org\/compress\/adventures_holmes\/formats=64KBPS MP3&file=\/adventures_holmes.zip",
            "url_librivox":"https:\/\/librivox.org\/the-adventures-of-sherlock-holmes\/","totaltime":"10:17:59",
            "authors":[{"first_name":"Sir Arthur Conan","last_name":"Doyle"}],
            "coverart_thumbnail":"https:\/\/archive.org\/thumb.jpg","genres":[{"id":"1","name":"Detective Fiction"}]}]}"""
        val item = LibriVoxSource.parse(json).single()
        assertEquals("Adventures of Sherlock Holmes", item.title)
        assertEquals("Sir Arthur Conan Doyle", item.author)
        assertEquals("https://librivox.org/the-adventures-of-sherlock-holmes/", item.url)
        assertTrue(item.description!!.startsWith("Twelve stories."))
        assertEquals(listOf(MIME_AUDIO_FEED, "application/zip"), item.links.map { it.mimeType })
        assertTrue(item.links.first().isAudioStream)
        assertTrue(" " !in item.links[1].url)
        assertEquals(listOf("Detective Fiction"), item.genres)
        assertEquals("""{"id":"314"}""", item.memo)
    }

    @Test
    fun nothingFoundIsEmpty() {
        assertTrue(LibriVoxSource.parse("""{"error":"Audiobooks could not be found"}""").isEmpty())
    }
}
