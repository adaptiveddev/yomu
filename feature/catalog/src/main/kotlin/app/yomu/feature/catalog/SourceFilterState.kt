package app.yomu.feature.catalog

import app.yomu.source.Filter

/** A filter that differs from the source's default: a removable chip above the search results. */
data class ActiveFilter(val path: List<Int>, val label: String, val kind: Kind) {
    enum class Kind { INCLUDE, EXCLUDE, SORT, VALUE }
}

/**
 * The filters of a source compared with its defaults (a fresh `getFilterList()`): which ones are set, and putting
 * one back. Filters are matched by position (and type and name), the way sources read them.
 */
object SourceFilterState {

    fun active(current: List<Filter<*>>, defaults: List<Filter<*>>): List<ActiveFilter> {
        val out = ArrayList<ActiveFilter>()
        collect(current, defaults, emptyList(), out)
        return out
    }

    /** Puts the filter at [path] back to its default; false when there is no such filter. */
    fun reset(current: List<Filter<*>>, defaults: List<Filter<*>>, path: List<Int>): Boolean {
        val f = at(current, path) ?: return false
        copy(matching(at(defaults, path), f), f)
        return true
    }

    /**
     * Whether the list has a hint like "Press 'Reset' to attempt to load tags": the extension loads part of its
     * filters in the background and has not got them yet.
     */
    fun hasReloadHint(list: List<Filter<*>>): Boolean = list.any { f ->
        (f is Filter.Header && isReloadHint(f.name)) || (f is Filter.Group && hasReloadHint(f.filters))
    }

    fun isReloadHint(text: String): Boolean = RELOAD_HINT.containsMatchIn(text)

    private val RELOAD_HINT = Regex("\\b(reset|reload|refresh)\\b", RegexOption.IGNORE_CASE)

    /** The kinds and names of the filters, nested groups included: changes when the source sends another list. */
    fun signature(list: List<Filter<*>>): Int = list.fold(list.size) { h, f ->
        var x = 31 * h + f::class.java.name.hashCode()
        x = 31 * x + f.name.hashCode()
        if (f is Filter.Group) x = 31 * x + signature(f.filters)
        if (f is Filter.Select) x = 31 * x + f.values.hashCode()
        x
    }

    /** Checked boxes and included or excluded tri-states: what a group header counts. */
    fun isSet(f: Filter<*>): Boolean = (f is Filter.CheckBox && f.state) || (f is Filter.TriState && f.state != Filter.TriState.IGNORE)

    /** Unchecks the boxes and clears the tri-states of a group ("clear" in its header). */
    fun clear(group: Filter.Group) {
        group.filters.forEach { f ->
            when (f) {
                is Filter.CheckBox -> f.state = false
                is Filter.TriState -> f.state = Filter.TriState.IGNORE
                is Filter.Group -> clear(f)
                else -> Unit
            }
        }
    }

    private fun collect(current: List<Filter<*>>, defaults: List<Filter<*>>?, path: List<Int>, out: MutableList<ActiveFilter>) {
        current.forEachIndexed { i, f ->
            val d = matching(defaults?.getOrNull(i), f)
            val p = path + i
            when (f) {
                is Filter.Group -> collect(f.filters, (d as? Filter.Group)?.filters, p, out)
                is Filter.CheckBox -> {
                    val default = (d as? Filter.CheckBox)?.state ?: false
                    if (f.state != default) out += ActiveFilter(p, f.name, if (f.state) ActiveFilter.Kind.INCLUDE else ActiveFilter.Kind.EXCLUDE)
                }
                is Filter.TriState -> {
                    val default = (d as? Filter.TriState)?.state ?: Filter.TriState.IGNORE
                    if (f.state != default) out += when (f.state) {
                        Filter.TriState.INCLUDE -> ActiveFilter(p, f.name, ActiveFilter.Kind.INCLUDE)
                        Filter.TriState.EXCLUDE -> ActiveFilter(p, f.name, ActiveFilter.Kind.EXCLUDE)
                        else -> ActiveFilter(p, "${f.name}: —", ActiveFilter.Kind.VALUE)
                    }
                }
                is Filter.Select -> {
                    val default = (d as? Filter.Select)?.state ?: 0
                    if (f.state != default) f.values.getOrNull(f.state)?.let { out += ActiveFilter(p, "${f.name}: $it", ActiveFilter.Kind.VALUE) }
                }
                is Filter.Sort -> {
                    val s = f.state
                    if (s != null && s != (d as? Filter.Sort)?.state) {
                        f.values.getOrNull(s.index)?.let { out += ActiveFilter(p, it + if (s.ascending) " ↑" else " ↓", ActiveFilter.Kind.SORT) }
                    }
                }
                is Filter.Text -> {
                    val default = (d as? Filter.Text)?.state.orEmpty()
                    if (f.state.isNotBlank() && f.state != default) out += ActiveFilter(p, "${f.name}: ${f.state.trim()}", ActiveFilter.Kind.VALUE)
                }
                is Filter.Header, is Filter.Separator -> Unit
            }
        }
    }

    /** The default of [f]: the filter at the same place when it is of the same kind and name. */
    private fun matching(d: Filter<*>?, f: Filter<*>): Filter<*>? = d?.takeIf { it::class == f::class && it.name == f.name }

    private fun at(list: List<Filter<*>>, path: List<Int>): Filter<*>? {
        var level: List<Filter<*>> = list
        var found: Filter<*>? = null
        for (i in path) {
            val f = level.getOrNull(i) ?: return null
            found = f
            level = (f as? Filter.Group)?.filters ?: emptyList()
        }
        return found
    }

    private fun copy(from: Filter<*>?, to: Filter<*>) {
        when (to) {
            is Filter.CheckBox -> to.state = (from as? Filter.CheckBox)?.state ?: false
            is Filter.TriState -> to.state = (from as? Filter.TriState)?.state ?: Filter.TriState.IGNORE
            is Filter.Select -> to.state = (from as? Filter.Select)?.state ?: 0
            is Filter.Sort -> to.state = (from as? Filter.Sort)?.state
            is Filter.Text -> to.state = (from as? Filter.Text)?.state.orEmpty()
            is Filter.Group -> to.filters.forEachIndexed { i, c -> copy(matching((from as? Filter.Group)?.filters?.getOrNull(i), c), c) }
            is Filter.Header, is Filter.Separator -> Unit
        }
    }
}
