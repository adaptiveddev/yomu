package app.yomu.feature.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebAddressTest {
    @Test
    fun `typed addresses become urls`() {
        assertEquals("https://standardebooks.org/feeds/opds", webAddress(" standardebooks.org/feeds/opds "))
        assertEquals("https://example.org/opds", webAddress("https://https://example.org/opds"))
        assertEquals("https://example.org/opds", webAddress("HTTPS://example.org/opds"))
        assertEquals("http://192.168.1.10:8080/opds", webAddress("192.168.1.10:8080/opds"))
        assertEquals("http://calibre.local/opds", webAddress("calibre.local/opds"))
        assertEquals("http://localhost:8080", webAddress("localhost:8080"))
        assertEquals("http://example.org/opds", webAddress("http://example.org/opds"))
    }

    @Test
    fun `non addresses are rejected`() {
        assertNull(webAddress(""))
        assertNull(webAddress("https://"))
        assertNull(webAddress("hello"))
        assertNull(webAddress("ftp://example.org/books"))
    }

    @Test
    fun `initials of source names`() {
        assertEquals("WC", initials("Weeb Central"))
        assertEquals("A", initials("AComics"))
        assertEquals("M", initials("MangaDex (EN)"))
        assertEquals("D", initials("[RU] Desu"))
        assertEquals("?", initials(" "))
    }
}
