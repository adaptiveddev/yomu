package app.yomu.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchMetadataTest {
    private fun book(id: Long, title: String, series: String? = null, index: Float? = null, tags: List<String> = emptyList()) =
        Book(id = id, title = title, authors = listOf("A$id"), series = series, seriesIndex = index, language = "en", publisher = "P$id", format = BookFormat.EPUB, uri = "file:///$id", tags = tags)

    @Test
    fun keepLeavesBooksAsTheyAre() {
        val b = book(1, "One", "S", 3f, listOf("x"))
        val edit = BatchMetadata()
        assertTrue(edit.isEmpty)
        assertEquals(b, edit.applyTo(b, 0))
    }

    @Test
    fun setsClearsAndNumbersInTitleOrder() {
        val books = listOf(book(1, "Vol 10"), book(2, "Vol 2"), book(3, "Vol 1"))
        val edit = BatchMetadata(
            authors = FieldEdit.Set("Ann, Bob & Ann"),
            series = FieldEdit.Set(" Saga "),
            numberFrom = 1f,
            language = FieldEdit.Set("RU"),
            publisher = FieldEdit.Clear,
            status = ReadingStatus.FINISHED,
        )
        val ordered = edit.ordered(books)
        assertEquals(listOf("Vol 1", "Vol 2", "Vol 10"), ordered.map { it.title })
        val out = ordered.mapIndexed { i, b -> edit.applyTo(b, i) }
        assertEquals(listOf(1f, 2f, 3f), out.map { it.seriesIndex })
        out.forEach {
            assertEquals(listOf("Ann", "Bob"), it.authors)
            assertEquals("Saga", it.series)
            assertEquals("ru", it.language)
            assertNull(it.publisher)
            assertEquals(ReadingStatus.FINISHED, it.status)
        }
    }

    @Test
    fun selectionOrderAndClearedSeriesDropsTheNumber() {
        val books = listOf(book(1, "B", "S", 5f), book(2, "A", "S", 6f))
        val edit = BatchMetadata(numberFrom = 10f, order = BatchOrder.SELECTION)
        val ordered = edit.ordered(books, selection = listOf(1, 2))
        assertEquals(listOf(1L, 2L), ordered.map { it.id })
        assertEquals(listOf(10f, 11f), ordered.mapIndexed { i, b -> edit.applyTo(b, i).seriesIndex })
        val cleared = BatchMetadata(series = FieldEdit.Clear).applyTo(books[0], 0)
        assertNull(cleared.series)
        assertNull(cleared.seriesIndex)
    }

    @Test
    fun tagsAreAddedAndRemovedIgnoringCase() {
        val b = book(1, "One", tags = listOf("Fantasy", "todo", "Read"))
        val edit = BatchMetadata(addTags = listOf("fantasy", " Favourite ", ""), removeTags = setOf("TODO"))
        assertTrue(edit.changesTags)
        assertEquals(listOf("Fantasy", "Read", "Favourite"), edit.tagsOf(b))
    }

    @Test
    fun fieldEditFromTheDialog() {
        assertEquals(FieldEdit.Keep, BatchMetadata.fieldEdit("", common = null, clear = false))
        assertEquals(FieldEdit.Keep, BatchMetadata.fieldEdit(" en ", common = "en", clear = false))
        assertEquals(FieldEdit.Clear, BatchMetadata.fieldEdit("", common = "en", clear = false))
        assertEquals(FieldEdit.Clear, BatchMetadata.fieldEdit("whatever", common = null, clear = true))
        assertEquals(FieldEdit.Set("de"), BatchMetadata.fieldEdit("de", common = "en", clear = false))
        assertEquals("x", BatchMetadata.common(listOf("x", "x")))
        assertNull(BatchMetadata.common(listOf("x", null)))
    }
}
