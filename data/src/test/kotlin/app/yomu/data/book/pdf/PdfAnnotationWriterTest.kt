package app.yomu.data.book.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import java.io.File

class PdfAnnotationWriterTest {
    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/pdf/$name")!!.use { it.readBytes() }

    /** Written files go here; the build checks them with an independent PDF library when it can. */
    private val outDir = File("build/pdf-annotations-test").apply { mkdirs() }

    private fun markup(id: String, page: Int, kind: MarkupKind = MarkupKind.HIGHLIGHT, note: String? = null) = PdfMarkup(
        id = PdfAnnotationWriter.ID_PREFIX + id,
        page = page,
        kind = kind,
        rects = listOf(floatArrayOf(72f, 738f, 300f, 754f), floatArrayOf(72f, 708f, 250f, 724f)),
        rgb = 0xFFE066,
        opacity = 0.5f,
        contents = note,
        created = 1_700_000_000_000,
        modified = 1_700_000_100_000,
        extra = mapOf("Start" to PdfNumber.of(4), "End" to PdfNumber.of(19), "Style" to PdfName("HIGHLIGHT")),
    )

    private fun append(original: ByteArray, inc: ByteArray) = original + inc

    @ParameterizedTest
    @ValueSource(strings = ["classic.pdf", "objstm.pdf", "foreign.pdf", "predictor.pdf", "rotated.pdf"])
    fun writesReadsAndReplacesYomuAnnotations(name: String) {
        val original = fixture(name)
        val pages = PdfFile(ByteArrayPdfBytes(original)).load().pages().size
        val foreignBefore = PdfFile(ByteArrayPdfBytes(original)).load().let { f ->
            f.pages().sumOf { p -> ((f.resolve((f.getObject(p.number) as PdfDictionary)["Annots"]) as? PdfArray)?.size ?: 0) }
        }
        val first = listOfNotNull(
            markup("1-a", 0, note = "Заметка: «кавычки» (скобки) \\ слеш"),
            markup("2-b", 0, MarkupKind.UNDERLINE),
            if (pages > 1) markup("3-c", 1, MarkupKind.STRIKEOUT) else null,
        )
        val inc1 = PdfAnnotationWriter.increment(ByteArrayPdfBytes(original), first)
        assertNotNull(inc1)
        val v1 = append(original, inc1!!)
        File(outDir, name.removeSuffix(".pdf") + "-1.pdf").writeBytes(v1)
        val found1 = PdfAnnotationWriter.read(ByteArrayPdfBytes(v1))
        assertEquals(first.map { it.id }.toSet(), found1.map { it.id }.toSet())
        val note = found1.first { it.id.endsWith("1-a") }
        assertEquals("Заметка: «кавычки» (скобки) \\ слеш", note.contents)
        assertEquals(MarkupKind.UNDERLINE, found1.first { it.id.endsWith("2-b") }.kind)
        assertEquals(4, (note.extra?.get("Start") as PdfNumber).int)
        assertEquals(0xFFE066, note.rgb)

        // A second update on top of the first one (another app may have appended meanwhile): Yomu's old
        // annotations go, the new set and the annotations of other apps stay.
        val second = listOf(markup("4-d", 0, note = "new"))
        val inc2 = PdfAnnotationWriter.increment(ByteArrayPdfBytes(v1), second)!!
        val v2 = append(v1, inc2)
        File(outDir, name.removeSuffix(".pdf") + "-2.pdf").writeBytes(v2)
        assertEquals(listOf(second[0].id), PdfAnnotationWriter.read(ByteArrayPdfBytes(v2)).map { it.id })
        val f2 = PdfFile(ByteArrayPdfBytes(v2)).load()
        val total = f2.pages().sumOf { p -> ((f2.resolve((f2.getObject(p.number) as PdfDictionary)["Annots"]) as? PdfArray)?.size ?: 0) }
        assertEquals(foreignBefore + 1, total)

        // Replacing the update instead (the file still ends with Yomu's update): the original is the base.
        val inc3 = PdfAnnotationWriter.increment(PdfBytesView(ByteArrayPdfBytes(v1), original.size.toLong()), second)!!
        val v3 = append(original, inc3)
        assertEquals(listOf(second[0].id), PdfAnnotationWriter.read(ByteArrayPdfBytes(v3)).map { it.id })

        // Nothing to write and nothing written before: the file stays as it is.
        assertNull(PdfAnnotationWriter.increment(ByteArrayPdfBytes(original), emptyList()))
        // Everything removed: the pages lose Yomu's annotations.
        val v4 = append(v2, PdfAnnotationWriter.increment(ByteArrayPdfBytes(v2), emptyList())!!)
        File(outDir, name.removeSuffix(".pdf") + "-3.pdf").writeBytes(v4)
        assertTrue(PdfAnnotationWriter.read(ByteArrayPdfBytes(v4)).isEmpty())
    }

    @Test
    fun markupsOutsideThePagesAreIgnored() {
        val original = fixture("classic.pdf")
        assertNull(PdfAnnotationWriter.increment(ByteArrayPdfBytes(original), listOf(markup("x", 99))))
    }

    @Test
    fun objectsAndStringsRoundTrip() {
        val src = "<< /Type /Annot /N#20ame (a\\(b\\)c\\\\) /H <FEFF0410> /A [1 0 R -2.5 .5 true null] /D << /K /V >> >>"
        val obj = PdfLexer(src.toByteArray(Charsets.ISO_8859_1)).readObject() as PdfDictionary
        assertEquals("a(b)c\\", (obj["N ame"] as PdfString).text())
        assertEquals("А", (obj["H"] as PdfString).text())
        assertEquals(PdfReference(1, 0), (obj["A"] as PdfArray)[0])
        val again = PdfLexer(PdfWriter.bytes(obj)).readObject() as PdfDictionary
        assertEquals(obj.entries.keys, again.entries.keys)
        assertEquals(obj["N ame"], again["N ame"])
        assertEquals("0.333", PdfNumber.of(1f / 3).text)
        assertEquals("2", PdfNumber.of(2f).text)
    }

    @Test
    fun brokenFilesAreRefused() {
        assertThrows(PdfFormatException::class.java) { PdfAnnotationWriter.increment(ByteArrayPdfBytes("not a pdf".toByteArray()), listOf(markup("a", 0))) }
    }
}
