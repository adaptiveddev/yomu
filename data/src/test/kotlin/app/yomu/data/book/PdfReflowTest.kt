package app.yomu.data.book

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Synthetic layouts of one page: lines are top-left rectangles as produced by [PdfSource.lines]. */
class PdfReflowTest {
    private val pageWidth = 595f // A4 in points
    private fun line(text: String, left: Float, top: Float, right: Float = left + 8f * text.length, height: Float = 12f) =
        PdfLine(text, left, top, right, top + height)

    private fun column(left: Float, prefix: String, count: Int = 6, top: Float = 100f) =
        (0 until count).map { i -> line("$prefix ${i + 1} with words.", left, top + i * 15f, right = left + 208f) }

    @Test fun emptyAndSingleLines() {
        assertTrue(PdfReflow.paragraphs(emptyList(), pageWidth).isEmpty())
        assertEquals(listOf("Only one line"), PdfReflow.paragraphs(listOf(line("Only one line", 72f, 100f)), pageWidth))
    }

    @Test fun continuousLinesMergeIntoOneParagraph() {
        val lines = (0..5).map { i -> line("This is reading line number ${i + 1} of the paragraph.", 72f, 100f + i * 15f) }
        val paragraphs = PdfReflow.paragraphs(lines, pageWidth)
        assertEquals(1, paragraphs.size)
        assertTrue(paragraphs.single().startsWith("This is reading"))
        assertTrue(paragraphs.single().endsWith("of the paragraph."))
    }

    @Test fun verticalGapStartsANewParagraph() {
        val lines = listOf(
            line("First paragraph ends here.", 72f, 100f),
            line("Second paragraph starts", 72f, 140f), // 40pt gap, line height 12
            line("and continues right here.", 72f, 155f),
        )
        val paragraphs = PdfReflow.paragraphs(lines, pageWidth)
        assertEquals(listOf("First paragraph ends here.", "Second paragraph starts and continues right here."), paragraphs)
    }

    @Test fun indentationStartsANewParagraph() {
        val lines = listOf(
            line("The previous paragraph finished with a full stop.", 72f, 100f),
            line("An unindented continuation of it follows here.", 72f, 115f), // same left: no break
            line("An indented first line marks a new paragraph.", 92f, 135f), // +20pt indent
            line("and its second line keeps it together.", 72f, 150f),
        )
        val paragraphs = PdfReflow.paragraphs(lines, pageWidth)
        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs[0].endsWith("follows here."))
        assertTrue(paragraphs[1].startsWith("An indented first line"))
        assertTrue(paragraphs[1].endsWith("keeps it together."))
    }

    @Test fun headingsSplitBySize() {
        val lines = listOf(
            line("Chapter title in a larger font", 72f, 100f, height = 20f),
            line("Body text of the chapter begins on this line", 72f, 130f),
            line("and wraps onto the next visual line.", 72f, 145f),
        )
        val paragraphs = PdfReflow.paragraphs(lines, pageWidth)
        assertEquals(listOf("Chapter title in a larger font", "Body text of the chapter begins on this line and wraps onto the next visual line."), paragraphs)
    }

    @Test fun hyphenatedWordsJoinAcrossLines() {
        val lines = listOf(
            line("The reader joins hyphen-", 72f, 100f),
            line("ated words across lines and soft hyphens too.", 72f, 115f),
        )
        val paragraphs = PdfReflow.paragraphs(lines, pageWidth)
        assertEquals(listOf("The reader joins hyphenated words across lines and soft hyphens too."), paragraphs)
    }

    @Test fun twoColumnsAreReadInTheRightOrder() {
        val paragraphs = PdfReflow.paragraphs(column(72f, "Left") + column(320f, "Right"), pageWidth)
        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs[0].startsWith("Left 1"))
        assertTrue(paragraphs[0].endsWith("Left 6 with words."))
        assertTrue(paragraphs[1].startsWith("Right 1"))
        assertTrue(paragraphs[1].endsWith("Right 6 with words."))
    }

    @Test fun figureAboveColumnsSplitsByBands() {
        // A wide caption above two columns: the horizontal band cut separates it, then the
        // vertical gutter splits the columns.
        val lines = listOf(
            line("A full width figure caption above the columns", 72f, 100f, right = 520f),
            line("Left column line one", 72f, 140f),
            line("Left column line two", 72f, 155f),
            line("Left column line three", 72f, 170f),
            line("Right column line one", 320f, 140f),
            line("Right column line two", 320f, 155f),
            line("Right column line three", 320f, 170f),
        )
        val paragraphs = PdfReflow.paragraphs(lines, pageWidth)
        assertEquals(3, paragraphs.size)
        assertEquals("A full width figure caption above the columns", paragraphs[0])
        assertTrue(paragraphs[1].startsWith("Left column"))
        assertTrue(paragraphs[2].startsWith("Right column"))
    }

    @Test fun interleavedColumnsAreSplitByTheGutter() {
        val left = (0..3).map { i -> line("Left text ${i + 1}", 72f, 100f + i * 15f) }
        val right = (0..3).map { i -> line("Right text ${i + 1}", 320f, 100f + i * 15f) }
        val paragraphs = PdfReflow.paragraphs(left + right, pageWidth)
        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs[0].startsWith("Left text 1"))
        assertTrue(paragraphs[0].endsWith("Left text 4"))
        assertTrue(paragraphs[1].startsWith("Right text 1"))
        assertTrue(paragraphs[1].endsWith("Right text 4"))
    }

    @Test fun pageNumbersInMarginsAreDropped() {
        val lines = listOf(
            line("12", 300f, 30f),                       // header page number
            line("The actual body of the page starts", 72f, 100f),
            line("and continues on this line.", 72f, 115f),
            line("- 13 -", 300f, 760f),                  // footer page number
        )
        val paragraphs = PdfReflow.paragraphs(lines, pageWidth)
        assertEquals(1, paragraphs.size)
        val text = paragraphs.single()
        assertTrue(text.startsWith("The actual body"))
        assertTrue(!text.contains(" 13 "))
    }

    @Test fun bulletListItemsBreakParagraphs() {
        val lines = listOf(
            line("Intro sentence before the items begins here.", 72f, 100f),
            line("\u2022 First item of the list goes on.", 72f, 130f),
            line("\u2022 Second item of the list goes on.", 72f, 145f),
        )
        val paragraphs = PdfReflow.paragraphs(lines, pageWidth)
        assertEquals(3, paragraphs.size)
        assertTrue(paragraphs[1].startsWith("• First item"))
        assertTrue(paragraphs[2].startsWith("• Second item"))
    }
}
