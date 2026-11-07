package app.yomu.data.source.tachiyomi

import app.yomu.source.Filter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.source.model.Filter as TFilter
import eu.kanade.tachiyomi.source.model.FilterList as TFilterList

class TachiyomiFiltersTest {
    private class Tag(name: String) : TFilter.TriState(name)
    private class Tags(tags: List<Tag>) : TFilter.Group<Tag>("Tags", tags)
    private class SortBy : TFilter.Sort("Sort By", arrayOf("Popular", "Latest"), TFilter.Sort.Selection(0, false))
    private class Pages : TFilter.Text("Pages")

    /** What a NHentai-like extension returns before its tags loaded, and after. */
    private fun extensionBefore() = TFilterList(listOf(TFilter.Header("Press 'reset' to attempt to load tags"), SortBy(), Pages()))
    private fun extensionAfter() = TFilterList(listOf(Tags(listOf(Tag("Action"), Tag("Comedy"))), TFilter.Separator(), SortBy(), Pages()))

    @Test
    fun `states are copied by position while the lists match`() {
        val panel = TachiyomiFilters.toYomu(extensionBefore())
        (panel[1] as Filter.Sort).state = Filter.Sort.Selection(1, false)
        (panel[2] as Filter.Text).state = "30"
        val fresh = extensionBefore()
        TachiyomiFilters.applyStates(panel, fresh)
        assertEquals(TFilter.Sort.Selection(1, false), (fresh[1] as TFilter.Sort).state)
        assertEquals("30", (fresh[2] as TFilter.Text).state)
    }

    @Test
    fun `states go by kind and name when the extension changed its list`() {
        // The panel was built before the tags loaded; the search gets the list with the tags.
        val panel = TachiyomiFilters.toYomu(extensionBefore())
        (panel[1] as Filter.Sort).state = Filter.Sort.Selection(1, true)
        (panel[2] as Filter.Text).state = "30"
        val fresh = extensionAfter()
        TachiyomiFilters.applyStates(panel, fresh)
        assertEquals(TFilter.Sort.Selection(1, true), (fresh[2] as TFilter.Sort).state)
        assertEquals("30", (fresh[3] as TFilter.Text).state)
        assertEquals(listOf(0, 0), (fresh[0] as Tags).state.map { it.state })
    }

    @Test
    fun `group children are matched by name too`() {
        val panel = TachiyomiFilters.toYomu(extensionAfter())
        ((panel[0] as Filter.Group).filters[1] as Filter.TriState).state = Filter.TriState.INCLUDE
        // The extension now lists one more tag in front.
        val fresh = TFilterList(listOf(Tags(listOf(Tag("Adventure"), Tag("Action"), Tag("Comedy"))), TFilter.Separator(), SortBy(), Pages()))
        TachiyomiFilters.applyStates(panel, fresh)
        assertEquals(listOf(0, 0, TFilter.TriState.STATE_INCLUDE), (fresh[0] as Tags).state.map { it.state })
    }
}
