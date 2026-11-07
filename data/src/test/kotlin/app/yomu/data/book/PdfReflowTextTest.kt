package app.yomu.data.book
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
class PdfReflowTextTest {
    @Test fun `paragraphs and line endings are handled`() {
        assertEquals(listOf("First line continues", "Second paragraph"), PdfReflowText.paragraphs("First line\r\ncontinues\r\n \r\nSecond\nparagraph"))
    }
    @Test fun `hard hyphen is retained and soft hyphen removed`() {
        assertEquals(listOf("well-known international"), PdfReflowText.paragraphs("well-\nknown inter\u00ad\nnational"))
    }
    @Test fun `empty text contains no paragraphs`() { assertEquals(emptyList<String>(), PdfReflowText.paragraphs("\r\n \n")) }
}
