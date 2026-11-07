package app.yomu.domain.catalog

import app.yomu.domain.model.ChapterInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterOrderTest {
    private var nextId = 1L

    /** Chapters as listed by a source: index in the list = sourceOrder. */
    private fun listed(vararg specs: Pair<String, Float>, scanlators: List<String?> = emptyList()): List<ChapterInfo> =
        specs.mapIndexed { i, (name, number) ->
            ChapterInfo(id = nextId++, entryId = 1, url = "/c/$name/$i", name = name, number = number, sourceOrder = i, scanlator = scanlators.getOrNull(i))
        }

    private fun names(list: List<ChapterInfo>) = list.map { it.name }

    @Test
    fun `newest first source is turned around`() {
        val list = listed("3" to 3f, "2" to 2f, "1" to 1f)
        assertTrue(ChapterOrder.isNewestFirst(list))
        assertEquals(listOf("1", "2", "3"), names(ChapterOrder.reading(list)))
    }

    @Test
    fun `oldest first source keeps its order`() {
        val list = listed("1" to 1f, "2" to 2f, "3" to 3f)
        assertFalse(ChapterOrder.isNewestFirst(list))
        assertEquals(listOf("1", "2", "3"), names(ChapterOrder.reading(list)))
    }

    @Test
    fun `unnumbered chapters stay between their neighbours`() {
        val list = listed("3" to 3f, "Extra" to -1f, "2" to 2f, "1" to 1f, "Prologue" to -1f)
        assertEquals(listOf("Prologue", "1", "2", "Extra", "3"), names(ChapterOrder.reading(list)))
    }

    @Test
    fun `direction falls back to upload dates`() {
        val list = listOf(
            ChapterInfo(id = 1, entryId = 1, url = "a", name = "Old", uploadDate = 1_000, sourceOrder = 0),
            ChapterInfo(id = 2, entryId = 1, url = "b", name = "New", uploadDate = 2_000, sourceOrder = 1),
        )
        assertEquals(listOf("Old", "New"), names(ChapterOrder.reading(list)))
    }

    @Test
    fun `misplaced numbered chapter is sorted by number`() {
        val list = listed("5" to 5f, "4" to 4f, "2" to 2f, "3" to 3f, "1" to 1f)
        assertEquals(listOf("1", "2", "3", "4", "5"), names(ChapterOrder.reading(list)))
    }

    @Test
    fun `numbering restarted per volume keeps the source order`() {
        val list = listed("V2 C2" to 2f, "V2 C1" to 1f, "V1 C3" to 3f, "V1 C2" to 2f, "V1 C1" to 1f)
        assertEquals(listOf("V1 C1", "V1 C2", "V1 C3", "V2 C1", "V2 C2"), names(ChapterOrder.reading(list)))
    }

    @Test
    fun `groups listed one after another are merged by number`() {
        val list = listed(
            "2A" to 2f, "1A" to 1f, "2B" to 2f, "1B" to 1f,
            scanlators = listOf("A", "A", "B", "B"),
        )
        val reading = ChapterOrder.reading(list)
        assertEquals(listOf(1f, 1f, 2f, 2f), reading.map { it.number })
        assertEquals(2, ChapterOrder.slots(reading).size)
    }

    @Test
    fun `next chapter skips other releases and keeps the group`() {
        val list = listed(
            "3A" to 3f, "3B" to 3f, "2B" to 2f, "2A" to 2f, "1A" to 1f, "1B" to 1f,
            scanlators = listOf("A", "B", "B", "A", "A", "B"),
        )
        val reading = ChapterOrder.reading(list)
        val oneB = reading.first { it.name == "1B" }
        assertEquals("2B", ChapterOrder.adjacent(reading, oneB.id, forward = true)?.name)
        val threeA = reading.first { it.name == "3A" }
        assertEquals("2A", ChapterOrder.adjacent(reading, threeA.id, forward = false)?.name)
        assertNull(ChapterOrder.adjacent(reading, threeA.id, forward = true))
    }

    @Test
    fun `continue skips chapters read in another release`() {
        val list = listed(
            "2A" to 2f, "2B" to 2f, "1A" to 1f, "1B" to 1f,
            scanlators = listOf("A", "B", "A", "B"),
        ).map { if (it.name == "1A") it.copy(read = true) else it }
        val reading = ChapterOrder.reading(list)
        assertEquals("2A", ChapterOrder.continueWith(reading)?.name)
        assertEquals("2A", ChapterOrder.nextUnread(reading, reading.first { it.name == "1A" }.id)?.name)
    }

    @Test
    fun `continue prefers a started chapter and restarts the last one when everything is read`() {
        val started = listed("3" to 3f, "2" to 2f, "1" to 1f).map {
            when (it.name) { "1" -> it.copy(read = true); "3" -> it.copy(lastPageRead = 4); else -> it }
        }
        assertEquals("3", ChapterOrder.continueWith(ChapterOrder.reading(started))?.name)
        val allRead = listed("2" to 2f, "1" to 1f).map { it.copy(read = true) }
        assertEquals("2", ChapterOrder.continueWith(ChapterOrder.reading(allRead))?.name)
    }

    @Test
    fun `upcoming downloads skip downloaded chapters and duplicates`() {
        val list = listed(
            "4" to 4f, "3" to 3f, "3 dup" to 3f, "2" to 2f, "1" to 1f,
            scanlators = listOf("A", "A", "B", "A", "A"),
        ).map {
            when (it.name) { "1" -> it.copy(read = true); "2" -> it.copy(downloaded = true); else -> it }
        }
        val reading = ChapterOrder.reading(list)
        assertEquals(listOf("3", "4"), names(ChapterOrder.upcoming(reading, afterId = null, count = 5)))
        // "Keep the next 2 downloaded" after chapter 1: chapter 2 counts, so only chapter 3 is needed.
        val one = reading.first { it.name == "1" }.id
        assertEquals(listOf("3"), names(ChapterOrder.upcoming(reading, afterId = one, count = 2, skipDownloaded = false)))
    }

    @Test
    fun `display sorts filters and hides duplicates`() {
        val list = listed(
            "3" to 3f, "2" to 2f, "2 B" to 2f, "1" to 1f,
            scanlators = listOf("A", "A", "B", "A"),
        ).map { if (it.name == "1") it.copy(read = true) else it }
        assertEquals(listOf("3", "2", "2 B", "1"), names(ChapterDisplay().apply(list)))
        assertEquals(listOf("1", "2 B", "2", "3"), names(ChapterDisplay(descending = false).apply(list)))
        assertEquals(listOf("3", "2"), names(ChapterDisplay(unreadOnly = true, hideDuplicates = true).apply(list)))
        assertEquals(listOf("3", "2", "1"), names(ChapterDisplay(excludedScanlators = setOf("B")).apply(list)))
        assertEquals(listOf("A", "B"), ChapterDisplay().scanlatorsOf(list))
    }

    @Test
    fun `upload date sort keeps undated chapters next to their neighbours`() {
        val list = listOf(
            ChapterInfo(id = 1, entryId = 1, url = "a", name = "1", number = 1f, uploadDate = 100, sourceOrder = 3),
            ChapterInfo(id = 2, entryId = 1, url = "b", name = "2", number = 2f, uploadDate = 0, sourceOrder = 2),
            ChapterInfo(id = 3, entryId = 1, url = "c", name = "3", number = 3f, uploadDate = 300, sourceOrder = 1),
            ChapterInfo(id = 4, entryId = 1, url = "d", name = "0.5", number = 0.5f, uploadDate = 400, sourceOrder = 0),
        )
        assertEquals(listOf("1", "2", "3", "0.5"), names(ChapterDisplay(sort = ChapterSortMode.UPLOAD_DATE, descending = false).apply(list)))
    }

    @Test
    fun `positions count chapters in reading order and releases of one chapter share it`() {
        val list = listed("3" to 3f, "2b" to 2f, "2a" to 2f, "1" to 1f, scanlators = listOf("A", "B", "A", "A"))
        val positions = ChapterOrder.positions(list)
        assertEquals(listOf(3, 2, 2, 1), list.map { positions[it.id] })
        assertEquals(3, ChapterOrder.slots(ChapterOrder.reading(list)).size)
    }

    @Test
    fun `positions of unnumbered chapters follow the reading order`() {
        val list = listed("2" to 2f, "Extra" to -1f, "1" to 1f)
        val positions = ChapterOrder.positions(list)
        assertEquals(listOf(3, 2, 1), list.map { positions[it.id] })
    }
}
