package app.yomu.feature.catalog

import app.yomu.source.Filter
import app.yomu.source.FilterList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceFilterStateTest {
    /** Filters the way a source declares them (a fresh copy every time, like `getFilterList()`). */
    private fun filters() = FilterList(
        Filter.Header("Choose the filters"),
        Filter.Sort("Sort", listOf("Popular", "Latest"), Filter.Sort.Selection(0, false)),
        Filter.Group("Content rating", listOf(Filter.CheckBox("Safe", true), Filter.CheckBox("Erotica"))),
        Filter.Group("Genres", listOf(Filter.TriState("Action"), Filter.TriState("Romance"))),
        Filter.Select("Tags mode", listOf("And", "Or")),
        Filter.Text("Author"),
    )

    private fun FilterList.group(i: Int) = (this[i] as Filter.Group).filters

    @Test
    fun `defaults are not active`() {
        assertTrue(SourceFilterState.active(filters(), filters()).isEmpty())
    }

    @Test
    fun `changed filters are listed with their kind`() {
        val f = filters()
        (f[1] as Filter.Sort).state = Filter.Sort.Selection(1, true)
        (f.group(2)[0] as Filter.CheckBox).state = false
        (f.group(3)[0] as Filter.TriState).state = Filter.TriState.INCLUDE
        (f.group(3)[1] as Filter.TriState).state = Filter.TriState.EXCLUDE
        (f[4] as Filter.Select).state = 1
        (f[5] as Filter.Text).state = " Oda "

        val active = SourceFilterState.active(f, filters())
        assertEquals(listOf("Latest ↑", "Safe", "Action", "Romance", "Tags mode: Or", "Author: Oda"), active.map { it.label })
        assertEquals(
            listOf(ActiveFilter.Kind.SORT, ActiveFilter.Kind.EXCLUDE, ActiveFilter.Kind.INCLUDE, ActiveFilter.Kind.EXCLUDE, ActiveFilter.Kind.VALUE, ActiveFilter.Kind.VALUE),
            active.map { it.kind },
        )
        assertEquals(listOf(3, 1), active[3].path)
    }

    @Test
    fun `reset puts one filter back to its default`() {
        val f = filters()
        val defaults = filters()
        (f.group(2)[0] as Filter.CheckBox).state = false
        (f.group(3)[1] as Filter.TriState).state = Filter.TriState.EXCLUDE
        (f[4] as Filter.Select).state = 1

        assertTrue(SourceFilterState.reset(f, defaults, listOf(3, 1)))
        assertTrue(SourceFilterState.reset(f, defaults, listOf(2, 0)))
        assertTrue((f.group(2)[0] as Filter.CheckBox).state)
        assertEquals(listOf("Tags mode: Or"), SourceFilterState.active(f, defaults).map { it.label })
        assertFalse(SourceFilterState.reset(f, defaults, listOf(9)))
    }

    @Test
    fun `a filter list that changed shape is compared safely`() {
        val f = filters()
        (f.group(3)[0] as Filter.TriState).state = Filter.TriState.INCLUDE
        // The source now returns fewer filters (a list loaded from the site, not loaded yet).
        val shorter = FilterList(Filter.Header("Press reset to load the genres"))
        assertEquals(listOf("Popular ↓", "Safe", "Action"), SourceFilterState.active(f, shorter).map { it.label })
        assertTrue(SourceFilterState.reset(f, shorter, listOf(3, 0)))
        assertEquals(Filter.TriState.IGNORE, (f.group(3)[0] as Filter.TriState).state)
    }

    @Test
    fun `clear empties a group and counts what is set`() {
        val f = filters()
        val genres = f[3] as Filter.Group
        (genres.filters[0] as Filter.TriState).state = Filter.TriState.INCLUDE
        (genres.filters[1] as Filter.TriState).state = Filter.TriState.EXCLUDE
        assertEquals(2, genres.filters.count(SourceFilterState::isSet))
        SourceFilterState.clear(genres)
        assertEquals(0, genres.filters.count(SourceFilterState::isSet))
    }

    @Test
    fun `finds the hint of tags that are still loading`() {
        assertTrue(SourceFilterState.hasReloadHint(FilterList(Filter.Header("Press 'reset' to attempt to load tags"), Filter.Text("Pages"))))
        assertTrue(SourceFilterState.hasReloadHint(listOf(Filter.Group("Tags", listOf(Filter.Header("Tap Reload to fetch the genres"))))))
        assertFalse(SourceFilterState.hasReloadHint(filters()))
        // Words that only contain "reset" are not the hint.
        assertFalse(SourceFilterState.hasReloadHint(listOf(Filter.Header("Resetting is not needed"))))
    }

    @Test
    fun `signature changes with the filters the source sends, not with their states`() {
        val before = SourceFilterState.signature(filters())
        val edited = filters().apply { (this[5] as Filter.Text).state = "Author name" }
        assertEquals(before, SourceFilterState.signature(edited))
        val withTags = FilterList(filters().list + Filter.Group("Tags", listOf(Filter.TriState("Action"))))
        assertFalse(before == SourceFilterState.signature(withTags))
    }
}
