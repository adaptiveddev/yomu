package app.yomu.data.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ImageSnifferTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `recognises common image formats`() {
        assertEquals("jpg", ImageSniffer.extension(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("png", ImageSniffer.extension(bytes(0x89) + ascii("PNG\r\n")))
        assertEquals("gif", ImageSniffer.extension(ascii("GIF89a")))
        assertEquals("webp", ImageSniffer.extension(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals("avif", ImageSniffer.extension(ascii("\u0000\u0000\u0000\u001cftypavif")))
        assertEquals("bmp", ImageSniffer.extension(ascii("BM\u0000\u0000")))
    }

    @Test
    fun `error pages are not images`() {
        val html = ascii("<!DOCTYPE html><html>")
        assertNull(ImageSniffer.extension(html))
        assertTrue(ImageSniffer.looksLikeText(ascii("  <html>")))
        assertTrue(ImageSniffer.looksLikeText(bytes(0xEF, 0xBB, 0xBF) + ascii("{\"error\":1}")))
        assertFalse(ImageSniffer.looksLikeText(bytes(0xFF, 0xD8, 0xFF)))
        assertFalse(ImageSniffer.looksLikeText(ByteArray(0)))
        assertNull(ImageSniffer.extension(ByteArray(0)))
    }

    @Test
    fun `reads only the head of a file`() {
        val file = File.createTempFile("page", ".bin")
        try {
            file.writeBytes(bytes(0xFF, 0xD8, 0xFF) + ByteArray(100))
            val head = ImageSniffer.head(file)
            assertEquals(ImageSniffer.HEAD_SIZE, head.size)
            assertEquals("jpg", ImageSniffer.extension(head))
            file.writeBytes(bytes(0x89, 0x50))
            assertEquals(2, ImageSniffer.head(file).size)
        } finally {
            file.delete()
        }
    }
}
