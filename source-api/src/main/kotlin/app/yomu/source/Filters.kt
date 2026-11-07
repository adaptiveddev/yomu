package app.yomu.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Declarative filters. The app renders them automatically in the filter sheet. */
sealed class Filter<T>(val name: String, var state: T) {
    class Header(name: String) : Filter<Unit>(name, Unit)
    class Separator : Filter<Unit>("", Unit)
    class Text(name: String, state: String = "") : Filter<String>(name, state)
    class CheckBox(name: String, state: Boolean = false, val key: String = name) : Filter<Boolean>(name, state)
    class TriState(name: String, state: Int = IGNORE, val key: String = name) : Filter<Int>(name, state) {
        companion object {
            const val IGNORE = 0
            const val INCLUDE = 1
            const val EXCLUDE = 2
        }
    }
    class Select(name: String, val values: List<String>, state: Int = 0, val keys: List<String> = values) : Filter<Int>(name, state) {
        val selectedKey: String get() = keys.getOrElse(state) { "" }
    }
    class Sort(name: String, val values: List<String>, state: Selection? = Selection(0, false), val keys: List<String> = values) :
        Filter<Sort.Selection?>(name, state) {
        data class Selection(val index: Int, val ascending: Boolean)
        val selectedKey: String? get() = state?.let { keys.getOrNull(it.index) }
    }
    class Group(name: String, val filters: List<Filter<*>>) : Filter<List<Filter<*>>>(name, filters)
}

class FilterList(val list: List<Filter<*>> = emptyList()) : List<Filter<*>> by list {
    constructor(vararg filters: Filter<*>) : this(filters.toList())

    /** All leaf filters, flattening groups. */
    fun leaves(): List<Filter<*>> = list.flatMap { if (it is Filter.Group) it.filters else listOf(it) }

    /**
     * A copy with the same structure and states that does not change when this list is edited (a running search keeps
     * the filters it was started with while the panel is edited).
     */
    fun deepCopy(): FilterList = FilterList(list.map(::copyOf))

    /**
     * Serializes the current state so that the last used filter can be restored. Every value carries the kind and the
     * name of its filter (1.8.0), so it finds its filter again after the source changed its list (tags that were
     * loaded meanwhile, a new filter at the top…); position alone put values on the wrong filters.
     */
    fun saveState(): JsonElement = JsonArray(list.map(::entryOf))

    /** Restores [saveState]; also reads the positional format of 1.7 and older. */
    fun restoreState(json: JsonElement) {
        val arr = json as? JsonArray ?: return
        restoreList(list, arr)
    }

    private fun copyOf(f: Filter<*>): Filter<*> = when (f) {
        is Filter.Header -> Filter.Header(f.name)
        is Filter.Separator -> Filter.Separator()
        is Filter.Text -> Filter.Text(f.name, f.state)
        is Filter.CheckBox -> Filter.CheckBox(f.name, f.state, f.key)
        is Filter.TriState -> Filter.TriState(f.name, f.state, f.key)
        is Filter.Select -> Filter.Select(f.name, f.values, f.state, f.keys)
        is Filter.Sort -> Filter.Sort(f.name, f.values, f.state, f.keys)
        is Filter.Group -> Filter.Group(f.name, f.filters.map(::copyOf))
    }

    private fun entryOf(f: Filter<*>): JsonElement = JsonObject(
        mapOf(
            KIND to JsonPrimitive(kindOf(f)),
            NAME to JsonPrimitive(f.name),
            VALUE to if (f is Filter.Group) JsonArray(f.filters.map(::entryOf)) else stateOf(f),
        ),
    )

    private fun restoreList(filters: List<Filter<*>>, arr: JsonArray) {
        if (arr.isEmpty()) return
        if (!arr.all { it is JsonObject && KIND in it }) {
            // Saved by 1.7 or older: values by position.
            filters.forEachIndexed { i, f -> arr.getOrNull(i)?.let { runCatching { apply(f, it) } } }
            return
        }
        val entries = arr.map { it as JsonObject }
        val aligned = entries.size == filters.size && filters.indices.all { i -> sameFilter(filters[i], entries[i]) }
        if (aligned) {
            filters.forEachIndexed { i, f -> runCatching { applyEntry(f, entries[i]) } }
            return
        }
        // The list changed since: each saved value goes to the first unused filter of the same kind and name.
        val used = BooleanArray(entries.size)
        filters.forEach { f ->
            val i = entries.indices.firstOrNull { !used[it] && sameFilter(f, entries[it]) } ?: return@forEach
            used[i] = true
            runCatching { applyEntry(f, entries[i]) }
        }
    }

    private fun sameFilter(f: Filter<*>, e: JsonObject): Boolean =
        (e[KIND] as? JsonPrimitive)?.content == kindOf(f) && (e[NAME] as? JsonPrimitive)?.content == f.name

    private fun applyEntry(f: Filter<*>, e: JsonObject) {
        val v = e[VALUE] ?: return
        if (f is Filter.Group) (v as? JsonArray)?.let { restoreList(f.filters, it) } else apply(f, v)
    }

    private fun stateOf(f: Filter<*>): JsonElement = when (f) {
        is Filter.Text -> JsonPrimitive(f.state)
        is Filter.CheckBox -> JsonPrimitive(f.state)
        is Filter.TriState -> JsonPrimitive(f.state)
        is Filter.Select -> JsonPrimitive(f.state)
        is Filter.Sort -> f.state?.let { JsonObject(mapOf("i" to JsonPrimitive(it.index), "a" to JsonPrimitive(it.ascending))) } ?: JsonNull
        is Filter.Group -> JsonArray(f.filters.map { stateOf(it) })
        else -> JsonNull
    }

    private fun apply(f: Filter<*>, e: JsonElement) {
        when (f) {
            is Filter.Text -> f.state = e.jsonPrimitive.content
            is Filter.CheckBox -> f.state = e.jsonPrimitive.boolean
            is Filter.TriState -> f.state = e.jsonPrimitive.int.coerceIn(Filter.TriState.IGNORE, Filter.TriState.EXCLUDE)
            is Filter.Select -> f.state = e.jsonPrimitive.int.coerceIn(0, (f.values.size - 1).coerceAtLeast(0))
            is Filter.Sort -> when (e) {
                is JsonObject -> f.state = Filter.Sort.Selection(
                    e["i"]!!.jsonPrimitive.int.coerceIn(0, (f.values.size - 1).coerceAtLeast(0)),
                    e["a"]!!.jsonPrimitive.boolean,
                )
                is JsonNull -> f.state = null
                else -> Unit
            }
            is Filter.Group -> (e as? JsonArray)?.let { arr -> f.filters.forEachIndexed { i, c -> arr.getOrNull(i)?.let { apply(c, it) } } }
            else -> Unit
        }
    }

    private companion object {
        const val KIND = "t"
        const val NAME = "n"
        const val VALUE = "v"

        fun kindOf(f: Filter<*>): String = when (f) {
            is Filter.Header -> "header"
            is Filter.Separator -> "separator"
            is Filter.Text -> "text"
            is Filter.CheckBox -> "check"
            is Filter.TriState -> "tri"
            is Filter.Select -> "select"
            is Filter.Sort -> "sort"
            is Filter.Group -> "group"
        }
    }
}
