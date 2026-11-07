package app.yomu.data.source.tachiyomi

import app.yomu.source.Filter
import app.yomu.source.FilterList
import eu.kanade.tachiyomi.source.model.Filter as TFilter
import eu.kanade.tachiyomi.source.model.FilterList as TFilterList

/**
 * Converts Tachiyomi filters to Yomu filters for the filter sheet and copies the chosen states back.
 *
 * Extensions cast the filters they receive to their own subclasses, so a search always gets a fresh
 * `getFilterList()` from the extension with the states applied position by position.
 */
internal object TachiyomiFilters {

    fun toYomu(list: TFilterList): FilterList = FilterList(list.map(::convert))

    @Suppress("UNCHECKED_CAST")
    private fun convert(f: TFilter<*>): Filter<*> = when (f) {
        is TFilter.Header -> Filter.Header(f.name)
        is TFilter.Separator -> Filter.Separator()
        is TFilter.Text -> Filter.Text(f.name, f.state)
        is TFilter.CheckBox -> Filter.CheckBox(f.name, f.state)
        is TFilter.TriState -> Filter.TriState(f.name, f.state)
        is TFilter.Select<*> -> Filter.Select(f.name, f.values.map { it.toString() }, f.state)
        is TFilter.Sort -> Filter.Sort(
            f.name,
            f.values.toList(),
            f.state?.let { Filter.Sort.Selection(it.index, it.ascending) },
        )
        is TFilter.Group<*> -> Filter.Group(
            f.name,
            (f.state as List<Any?>).map { child -> (child as? TFilter<*>)?.let(::convert) ?: Filter.Header(child.toString()) },
        )
        // Unknown filter types keep their position so indices stay aligned.
        else -> Filter.Header(f.name)
    }

    /**
     * Copies the states of [from] (a converted copy) into [to] (fresh filters of the extension): position by position
     * while both lists have the same filters, which is the usual case. When the extension's list changed since the
     * panel was built (tags that load in the background replace a "press reset" header, 1.8.0), each filter takes the
     * state of the first unused one of the same kind and name: by position the states landed on the wrong filters.
     */
    fun applyStates(from: FilterList, to: TFilterList) = applyList(from.list, to.list)

    private fun applyList(from: List<Filter<*>>, to: List<Any?>) {
        val aligned = from.size == to.size && from.indices.all { i -> sameFilter(from[i], to[i]) }
        if (aligned) {
            from.zip(to).forEach { (y, t) -> if (t is TFilter<*>) runCatching { apply(y, t) } }
            return
        }
        val used = BooleanArray(from.size)
        to.forEach { t ->
            if (t !is TFilter<*>) return@forEach
            val i = from.indices.firstOrNull { !used[it] && sameFilter(from[it], t) } ?: return@forEach
            used[i] = true
            runCatching { apply(from[i], t) }
        }
    }

    /** Whether [y] is the converted form of [t]: same kind and name. */
    private fun sameFilter(y: Filter<*>, t: Any?): Boolean {
        // Children of a group that are not filters are shown as headers.
        if (t !is TFilter<*>) return y is Filter.Header
        // Separators become nameless ones.
        if (t is TFilter.Separator) return y is Filter.Separator
        if (y.name != t.name) return false
        return when (t) {
            is TFilter.Header -> y is Filter.Header
            is TFilter.Text -> y is Filter.Text
            is TFilter.CheckBox -> y is Filter.CheckBox
            is TFilter.TriState -> y is Filter.TriState
            is TFilter.Select<*> -> y is Filter.Select
            is TFilter.Sort -> y is Filter.Sort
            is TFilter.Group<*> -> y is Filter.Group
            else -> y is Filter.Header
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun apply(y: Filter<*>, t: TFilter<*>) {
        when {
            t is TFilter.Text && y is Filter.Text -> t.state = y.state
            t is TFilter.CheckBox && y is Filter.CheckBox -> t.state = y.state
            t is TFilter.TriState && y is Filter.TriState -> t.state = y.state
            t is TFilter.Select<*> && y is Filter.Select -> t.state = y.state.coerceIn(0, (t.values.size - 1).coerceAtLeast(0))
            t is TFilter.Sort && y is Filter.Sort -> t.state = y.state?.let { TFilter.Sort.Selection(it.index, it.ascending) }
            t is TFilter.Group<*> && y is Filter.Group -> applyList(y.filters, t.state as List<Any?>)
        }
    }
}
