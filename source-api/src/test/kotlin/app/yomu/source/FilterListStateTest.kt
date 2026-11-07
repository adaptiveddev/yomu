package app.yomu.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilterListStateTest {
    /** The filters before the extension loaded its tags: a "press reset" header stands in for them. */
    private fun beforeTags() = FilterList(
        Filter.Header("Press 'reset' to attempt to load tags"),
        Filter.Sort("Sort By", listOf("Popular", "Latest"), Filter.Sort.Selection(0, false)),
        Filter.Text("Pages"),
        Filter.Group("Categories", listOf(Filter.CheckBox("Doujinshi"), Filter.CheckBox("Manga"))),
    )

    /** The same filters once the tags arrived: a new group at the top, the header gone. */
    private fun afterTags() = FilterList(
        Filter.Group("Tags", listOf(Filter.TriState("Action"), Filter.TriState("Comedy"))),
        Filter.Sort("Sort By", listOf("Popular", "Latest"), Filter.Sort.Selection(0, false)),
        Filter.Text("Pages"),
        Filter.Group("Categories", listOf(Filter.CheckBox("Manga"), Filter.CheckBox("Doujinshi"), Filter.CheckBox("Western"))),
    )

    @Test
    fun `round trip keeps every state`() {
        val saved = beforeTags().apply {
            (this[1] as Filter.Sort).state = Filter.Sort.Selection(1, true)
            (this[2] as Filter.Text).state = "20"
            ((this[3] as Filter.Group).filters[1] as Filter.CheckBox).state = true
        }.saveState()
        val restored = beforeTags().apply { restoreState(saved) }
        assertEquals(Filter.Sort.Selection(1, true), (restored[1] as Filter.Sort).state)
        assertEquals("20", (restored[2] as Filter.Text).state)
        assertEquals(listOf(false, true), (restored[3] as Filter.Group).filters.map { (it as Filter.CheckBox).state })
    }

    @Test
    fun `values find their filters by name after the list changed`() {
        val saved = beforeTags().apply {
            (this[1] as Filter.Sort).state = Filter.Sort.Selection(1, false)
            (this[2] as Filter.Text).state = "20"
            ((this[3] as Filter.Group).filters[0] as Filter.CheckBox).state = true
        }.saveState()
        val restored = afterTags().apply { restoreState(saved) }
        assertEquals(Filter.Sort.Selection(1, false), (restored[1] as Filter.Sort).state)
        assertEquals("20", (restored[2] as Filter.Text).state)
        // "Doujinshi" moved to the second place in its group and keeps its value; the others stay unchecked.
        assertEquals(listOf(false, true, false), (restored[3] as Filter.Group).filters.map { (it as Filter.CheckBox).state })
        assertTrue((restored[0] as Filter.Group).filters.all { (it as Filter.TriState).state == Filter.TriState.IGNORE })
    }

    @Test
    fun `reads the positional format of older versions`() {
        val legacy = JsonArray(listOf(JsonNull, JsonNull, JsonPrimitive("15"), JsonArray(listOf(JsonPrimitive(true), JsonPrimitive(false)))))
        val restored = beforeTags().apply { restoreState(legacy) }
        assertEquals("15", (restored[2] as Filter.Text).state)
        assertEquals(listOf(true, false), (restored[3] as Filter.Group).filters.map { (it as Filter.CheckBox).state })
    }

    @Test
    fun `broken or foreign json is ignored`() {
        val restored = beforeTags().apply { restoreState(JsonPrimitive("nonsense")) }
        assertEquals("", (restored[2] as Filter.Text).state)
        beforeTags().restoreState(JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive(3))))
    }

    @Test
    fun `a deep copy does not follow later edits`() {
        val list = beforeTags()
        val copy = list.deepCopy()
        (list[2] as Filter.Text).state = "edited"
        ((list[3] as Filter.Group).filters[0] as Filter.CheckBox).state = true
        assertEquals("", (copy[2] as Filter.Text).state)
        assertFalse(((copy[3] as Filter.Group).filters[0] as Filter.CheckBox).state)
        assertNotSame(list[3], copy[3])
        assertEquals(list.map { it.name }, copy.map { it.name })
    }
}
