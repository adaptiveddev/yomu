package app.yomu.domain.export

import app.yomu.domain.model.Word
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExportersTest {
    @Test
    fun `csv quoting roundtrip`() {
        val cells = listOf("plain", "with, comma", "with \"quotes\"", "multi\nline", "")
        val line = cells.joinToString(",") { Csv.quote(it) }
        assertEquals(listOf(cells), Csv.parse(line + "\r\n"))
        assertEquals(listOf(listOf("a", "b")), Csv.parse("\uFEFFa,b\n\n"))
    }

    @Test
    fun `word csv export can be imported back`() {
        val words = listOf(
            Word(term = "cat", translation = "кошка", context = "The cat, \"Tom\", slept.", lang = "en", tags = listOf("animals", "a1")),
            Word(term = "dog", definition = "a domestic animal"),
        )
        val imported = WordExporter.importCsv(WordExporter.export(words, WordFormat.CSV))
        assertEquals(2, imported.size)
        assertEquals("кошка", imported[0].translation)
        assertEquals("The cat, \"Tom\", slept.", imported[0].context)
        assertEquals(listOf("animals", "a1"), imported[0].tags)
        assertEquals("en", imported[0].lang)
        assertEquals("a domestic animal", imported[1].definition)
    }

    @Test
    fun `headerless csv is read as word, translation, context`() {
        val imported = WordExporter.importCsv("hund,собака,Der Hund bellt\n")
        assertEquals(1, imported.size)
        assertEquals("hund", imported[0].term)
        assertEquals("собака", imported[0].translation)
        assertEquals("Der Hund bellt", imported[0].context)
    }

    @Test
    fun `anki export has header and tags`() {
        val tsv = WordExporter.export(listOf(Word(term = "cat", translation = "кошка", context = "a cat")), WordFormat.ANKI_TSV)
        assertTrue(tsv.startsWith("#separator:tab"))
        assertTrue(tsv.contains("cat\tкошка<br><i>a <b>cat</b></i>\tyomu"))
    }

    @Test
    fun `readwise csv parsing`() {
        val csv = "Highlight,Title,Author,URL,Note,Location,Date\n\"Quote, with comma\",Book,Me,,My note,500,2024-01-01T00:00:00Z\n"
        val h = AnnotationExporter.parseReadwiseCsv(csv).single()
        assertEquals("Quote, with comma", h.text)
        assertEquals("Book", h.title)
        assertEquals("My note", h.note)
        assertEquals(0.5f, h.location)
    }
}
