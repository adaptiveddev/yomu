package app.yomu.data.format

import app.yomu.domain.model.BookFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FormatDetectorTest {
    private fun zipHeader(firstName: String, content: String = ""): ByteArray {
        val h = ByteArray(30) { 0 }
        h[0] = 'P'.code.toByte(); h[1] = 'K'.code.toByte(); h[2] = 3; h[3] = 4
        return h + firstName.toByteArray() + content.toByteArray()
    }

    @Test
    fun `detects by magic bytes`() {
        assertEquals(BookFormat.EPUB, FormatDetector.detect(zipHeader("mimetype", "application/epub+zip"), "book.zip"))
        assertEquals(BookFormat.CBZ, FormatDetector.detect(zipHeader("001.jpg"), "comic.zip"))
        assertEquals(BookFormat.DOCX, FormatDetector.detect(zipHeader("[Content_Types].xml"), null))
        assertEquals(BookFormat.FB2, FormatDetector.detect(zipHeader("book.fb2"), "book.fb2.zip"))
        assertEquals(BookFormat.CBR, FormatDetector.detect("Rar!\u001a\u0007".toByteArray(), "x.cbr"))
        assertEquals(BookFormat.PDF, FormatDetector.detect("%PDF-1.7\n".toByteArray(), "x"))
        assertEquals(BookFormat.FB2, FormatDetector.detect("<?xml version=\"1.0\"?><FictionBook>".toByteArray(), "x.xml"))
        assertEquals(BookFormat.HTML, FormatDetector.detect("<!DOCTYPE html><html>".toByteArray(), "x"))
        assertEquals(BookFormat.TXT, FormatDetector.detect("Plain text".toByteArray(), "notes"))
        assertEquals(BookFormat.UNKNOWN, FormatDetector.detect(ByteArray(64) { 1 }, "blob"))
    }

    @Test
    fun `natural order sorts numbers numerically`() {
        val sorted = listOf("page10.jpg", "page2.jpg", "Page1.jpg", "page2a.jpg").sortedWith(NaturalOrder)
        assertEquals(listOf("Page1.jpg", "page2.jpg", "page2a.jpg", "page10.jpg"), sorted)
        assertTrue(FormatDetector.isImage("a/b/C.JPG"))
        assertTrue(!FormatDetector.isImage("a/._hidden.jpg"))
    }
}
