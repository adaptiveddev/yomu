package app.yomu.feature.catalog

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DisabledByDefault
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import java.util.IdentityHashMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.yomu.core.ui.components.ExpandVisibility
import app.yomu.core.ui.components.animateMotionFloat
import app.yomu.core.ui.components.motionItem
import app.yomu.core.ui.components.motionSpec
import app.yomu.core.ui.theme.Dimens
import app.yomu.source.Filter
import app.yomu.core.ui.R as CoreR

/**
 * One counter per filter (1.8.0). Filters are plain mutable objects, so after an edit their controls must be told to
 * read them again. Before, one version number made every control of the panel redraw after each tap, which took a
 * long moment with the hundreds of tags of some sources; now a tap redraws its own control and the groups around it.
 */
@Stable
internal class FilterTicks {
    private val ticks = IdentityHashMap<Filter<*>, MutableIntState>()

    private fun of(f: Filter<*>): MutableIntState = ticks.getOrPut(f) { mutableIntStateOf(0) }

    /** Called by a control while it is drawn: it is drawn again after [bump] of the same filter. */
    fun read(f: Filter<*>): Int = of(f).intValue

    fun bump(f: Filter<*>) {
        of(f).intValue++
    }
}

/** The source asks to press "reset" to load its tags: shown as a "loading tags" row with a button instead. */
private class ReloadHint(val loading: Boolean, val onReload: (() -> Unit)?)

private val LocalReloadHint = compositionLocalOf { ReloadHint(false, null) }

/**
 * The filters of a source: groups of checkboxes and tri-states as chips in collapsible cards (with a search field in
 * long groups), the sort as chips with the direction, short selects as chips and long ones as a menu.
 * Filter objects are mutable: a new [version] (the list was reset or reloaded) makes every control read its filter
 * again; a single edit only redraws its own control. [loading] and [onReload] replace the "press reset to load the
 * tags" hints of some extensions with a progress row and a button.
 */
@Composable
fun SourceFiltersContent(
    filters: List<Filter<*>>,
    version: Int,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onReload: (() -> Unit)? = null,
) {
    val ticks = remember(filters, version) { FilterTicks() }
    val hint = remember(loading, onReload) { ReloadHint(loading, onReload) }
    CompositionLocalProvider(LocalReloadHint provides hint) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            filters.forEach { f -> FilterBlock(f, ticks, onChanged) }
        }
    }
}

/** "Filters" with the active ones as removable chips, shown above the search results. */
@Composable
fun ActiveFiltersRow(
    active: List<ActiveFilter>,
    onEdit: () -> Unit,
    onRemove: (ActiveFilter) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Dimens.l, vertical = Dimens.xs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "edit") {
            AssistChip(
                onClick = onEdit,
                label = { Text(stringResource(R.string.filters)) },
                leadingIcon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        items(active, key = { "f" + it.path.joinToString(".") }) { a ->
            val exclude = a.kind == ActiveFilter.Kind.EXCLUDE
            InputChip(
                selected = true,
                onClick = { onRemove(a) },
                label = { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
                leadingIcon = { Icon(kindIcon(a.kind), null, Modifier.size(InputChipDefaults.IconSize)) },
                trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.filters_remove), Modifier.size(InputChipDefaults.IconSize)) },
                colors = if (exclude) excludeInputColors() else InputChipDefaults.inputChipColors(),
                modifier = motionItem(),
            )
        }
        if (active.size > 1) item(key = "clear") {
            TextButton(onClick = onClear, modifier = motionItem()) {
                Icon(Icons.Outlined.FilterAltOff, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Dimens.xs))
                Text(stringResource(R.string.filters_reset_all))
            }
        }
    }
}

/** Recent searches as chips under the search field; tapping one searches again. */
@Composable
fun SearchHistoryRow(history: List<String>, onPick: (String) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Dimens.l),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(history, key = { "h$it" }) { q ->
            AssistChip(
                onClick = { onPick(q) },
                label = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                leadingIcon = { Icon(Icons.Outlined.History, null, Modifier.size(FilterChipDefaults.IconSize)) },
                modifier = motionItem(),
            )
        }
        item(key = "clear") {
            TextButton(onClick = onClear) { Text(stringResource(R.string.search_history_clear)) }
        }
    }
}

@Composable
private fun FilterBlock(f: Filter<*>, ticks: FilterTicks, onChanged: () -> Unit) {
    when (f) {
        is Filter.Header -> if (f.name.isNotBlank()) {
            val hint = LocalReloadHint.current
            val onReload = hint.onReload
            if (onReload != null && SourceFilterState.isReloadHint(f.name)) {
                ReloadHintRow(hint.loading, onReload)
            } else {
                Text(f.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Dimens.s))
            }
        }
        is Filter.Separator -> HorizontalDivider(Modifier.padding(vertical = Dimens.xs))
        is Filter.Text -> TextFilter(f, ticks, onChanged)
        is Filter.CheckBox, is Filter.TriState -> CheckFilter(f, ticks, onChanged)
        is Filter.Select -> SelectFilter(f, ticks, onChanged)
        is Filter.Sort -> SortCard(f, ticks, onChanged)
        is Filter.Group -> GroupCard(f, ticks, onChanged)
    }
}

/** In place of "Press 'Reset' to load the tags": the tags are loading, or a button to ask for them again. */
@Composable
private fun ReloadHintRow(loading: Boolean, onReload: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = Dimens.l, end = Dimens.s, top = Dimens.xs, bottom = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Sell, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(
                stringResource(if (loading) R.string.filters_tags_loading else R.string.filters_tags_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (!loading) TextButton(onClick = onReload) { Text(stringResource(R.string.filters_tags_reload)) }
        }
    }
}

/** A checkbox or tri-state outside a group: a row with a box. */
@Composable
private fun CheckFilter(f: Filter<*>, ticks: FilterTicks, onChanged: () -> Unit) {
    ticks.read(f)
    when (f) {
        is Filter.CheckBox -> CheckRow(
            icon = if (f.state) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            tint = if (f.state) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            label = f.name,
        ) { f.state = !f.state; ticks.bump(f); onChanged() }
        is Filter.TriState -> CheckRow(
            icon = when (f.state) {
                Filter.TriState.INCLUDE -> Icons.Filled.CheckBox
                Filter.TriState.EXCLUDE -> Icons.Filled.DisabledByDefault
                else -> Icons.Filled.CheckBoxOutlineBlank
            },
            tint = when (f.state) {
                Filter.TriState.INCLUDE -> MaterialTheme.colorScheme.primary
                Filter.TriState.EXCLUDE -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            label = f.name,
        ) { f.state = (f.state + 1) % 3; ticks.bump(f); onChanged() }
        else -> Unit
    }
}

/** A card with a header (icon, title, the number of chosen items, "clear", a chevron) that folds its content. */
@Composable
private fun FilterCard(
    icon: ImageVector,
    title: String,
    count: Int,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClear: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onToggle).padding(start = Dimens.l, end = Dimens.s, top = Dimens.xs, bottom = Dimens.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.m),
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).animateContentSize(sizeSpec())) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!expanded && summary != null) {
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (count > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(count.toString()) }
                if (onClear != null && count > 0) {
                    IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Clear, stringResource(R.string.filters_clear_group), Modifier.size(18.dp))
                    }
                }
                val rotation by animateMotionFloat(if (expanded) 180f else 0f, "chevron")
                Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ExpandVisibility(expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.l, bottom = Dimens.m),
                    verticalArrangement = Arrangement.spacedBy(Dimens.xs),
                    content = content,
                )
            }
        }
    }
}

/** Checkboxes and tri-states of [g] and of the groups inside it (tags split by letter), in order. */
private fun leafChips(g: Filter.Group): List<Filter<*>> = g.filters.flatMap { f ->
    when {
        isChip(f) -> listOf(f)
        f is Filter.Group -> leafChips(f)
        else -> emptyList()
    }
}

/** The group right around each chip of [g] (for the counters of the groups inside it). */
private fun parentsOf(g: Filter.Group, out: IdentityHashMap<Filter<*>, Filter.Group> = IdentityHashMap()): IdentityHashMap<Filter<*>, Filter.Group> {
    g.filters.forEach { f ->
        if (isChip(f)) out[f] = g
        if (f is Filter.Group) parentsOf(f, out)
    }
    return out
}

@Composable
private fun GroupCard(g: Filter.Group, ticks: FilterTicks, onChanged: () -> Unit) {
    // Redrawn when one of its filters changes (its children bump it), for the count and the summary.
    ticks.read(g)
    val all = remember(g) { leafChips(g) }
    val chosen = all.filter(SourceFilterState::isSet)
    // Short groups (statuses, ratings) start open; long ones (genres, themes) show what is chosen in the header.
    var expanded by rememberSaveable(g.name) { mutableStateOf(g.filters.size <= SMALL_GROUP) }
    var query by rememberSaveable(g.name) { mutableStateOf("") }
    val childChanged: () -> Unit = remember(g, ticks, onChanged) { { ticks.bump(g); onChanged() } }
    FilterCard(
        icon = filterIcon(g.name),
        title = g.name,
        count = chosen.size,
        summary = chosen.joinToString(", ") { c -> if (c is Filter.TriState && c.state == Filter.TriState.EXCLUDE) "✕ ${c.name}" else c.name }.ifBlank { null },
        expanded = expanded,
        onToggle = { expanded = !expanded },
        onClear = {
            SourceFilterState.clear(g)
            // Every chip of the group changed.
            all.forEach(ticks::bump)
            parentsOf(g).values.toSet().forEach(ticks::bump)
            ticks.bump(g)
            onChanged()
        },
    ) {
        GroupContent(g, all, query, { query = it }, ticks, childChanged)
    }
}

@Composable
private fun GroupContent(
    g: Filter.Group,
    all: List<Filter<*>>,
    query: String,
    onQuery: (String) -> Unit,
    ticks: FilterTicks,
    childChanged: () -> Unit,
) {
    if (all.size > SEARCHABLE_GROUP) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.filters_find_in, g.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, stringResource(CoreR.string.clear)) } }
            } else {
                null
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.xs),
        )
    }
    if (all.any { it is Filter.TriState }) {
        Text(stringResource(R.string.filters_tristate_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val q = query.trim()
    if (q.isNotEmpty()) {
        // A search goes through the groups inside too (tags split by letter) and shows the matches together.
        val matches = remember(all, q) { all.filter { it.name.contains(q, ignoreCase = true) } }
        if (matches.isEmpty()) {
            Text(stringResource(R.string.filters_nothing_found), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val parents = remember(g) { parentsOf(g) }
            ChipFlow(matches, ticks) { c -> parents[c]?.takeIf { it !== g }?.let(ticks::bump); childChanged() }
        }
        return
    }
    val runs = remember(g) { runsOf(g.filters) }
    runs.forEach { run ->
        if (isChip(run.first())) {
            ChipFlow(run, ticks) { childChanged() }
        } else {
            run.forEach { FilterBlock(it, ticks, childChanged) }
        }
    }
}

/**
 * Chips in rows. Long runs show the first [CHIP_PAGE] chips and the chosen ones, with "Show all": the thousands of
 * tags of some sources took seconds to draw at once.
 */
@Composable
private fun ChipFlow(chips: List<Filter<*>>, ticks: FilterTicks, onChanged: (Filter<*>) -> Unit) {
    var all by rememberSaveable(chips.size, chips.firstOrNull()?.name) { mutableStateOf(chips.size <= CHIP_PAGE + CHIP_PAGE / 2) }
    val shown = if (all) chips else chips.filterIndexed { i, c -> i < CHIP_PAGE || SourceFilterState.isSet(c) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        shown.forEach { c -> FilterChipOf(c, ticks, onChanged) }
    }
    if (!all) {
        TextButton(onClick = { all = true }) { Text(stringResource(R.string.filters_show_all, chips.size)) }
    }
}

@Composable
private fun FilterChipOf(f: Filter<*>, ticks: FilterTicks, onChanged: (Filter<*>) -> Unit) {
    ticks.read(f)
    val iconSize = Modifier.size(FilterChipDefaults.IconSize)
    when (f) {
        is Filter.CheckBox -> FilterChip(
            selected = f.state,
            onClick = { f.state = !f.state; ticks.bump(f); onChanged(f) },
            label = { Text(f.name) },
            leadingIcon = if (f.state) {
                { Icon(Icons.Filled.Check, null, iconSize) }
            } else {
                null
            },
        )
        is Filter.TriState -> {
            val exclude = f.state == Filter.TriState.EXCLUDE
            FilterChip(
                selected = f.state != Filter.TriState.IGNORE,
                onClick = { f.state = (f.state + 1) % 3; ticks.bump(f); onChanged(f) },
                label = { Text(f.name) },
                leadingIcon = when (f.state) {
                    Filter.TriState.INCLUDE -> ({ Icon(Icons.Filled.Check, null, iconSize) })
                    Filter.TriState.EXCLUDE -> ({ Icon(Icons.Outlined.Block, null, iconSize) })
                    else -> null
                },
                colors = if (exclude) {
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    FilterChipDefaults.filterChipColors()
                },
            )
        }
        else -> Unit
    }
}

@Composable
private fun SortCard(f: Filter.Sort, ticks: FilterTicks, onChanged: () -> Unit) {
    ticks.read(f)
    var expanded by rememberSaveable(f.name) { mutableStateOf(true) }
    val state = f.state
    FilterCard(
        icon = Icons.AutoMirrored.Outlined.Sort,
        title = f.name,
        count = 0,
        summary = state?.let { s -> f.values.getOrNull(s.index)?.let { it + if (s.ascending) " ↑" else " ↓" } },
        expanded = expanded,
        onToggle = { expanded = !expanded },
        onClear = null,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            f.values.forEachIndexed { i, v ->
                val selected = state?.index == i
                val ascending = state?.ascending == true
                FilterChip(
                    selected = selected,
                    onClick = { f.state = Filter.Sort.Selection(i, if (selected) !ascending else false); ticks.bump(f); onChanged() },
                    label = { Text(v) },
                    leadingIcon = if (selected) {
                        { Icon(if (ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        }
        Text(stringResource(R.string.filters_sort_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SelectFilter(f: Filter.Select, ticks: FilterTicks, onChanged: () -> Unit) {
    ticks.read(f)
    val asChips = f.values.size in 2..SELECT_CHIPS_MAX && f.values.all { it.length <= SELECT_CHIP_CHARS }
    if (asChips) {
        Column(Modifier.fillMaxWidth().padding(vertical = Dimens.xxs)) {
            Text(f.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                f.values.forEachIndexed { i, v ->
                    FilterChip(
                        selected = f.state == i,
                        onClick = { if (f.state != i) { f.state = i; ticks.bump(f); onChanged() } },
                        label = { Text(v) },
                        leadingIcon = if (f.state == i) {
                            { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    } else {
        var open by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(vertical = Dimens.xxs), verticalAlignment = Alignment.CenterVertically) {
            Icon(filterIcon(f.name), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Dimens.m))
            Text(f.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(start = Dimens.m, end = Dimens.xs)) {
                    Text(f.values.getOrElse(f.state) { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    f.values.forEachIndexed { i, v ->
                        DropdownMenuItem(
                            text = { Text(v) },
                            onClick = { open = false; if (f.state != i) { f.state = i; ticks.bump(f); onChanged() } },
                            leadingIcon = if (i == f.state) {
                                { Icon(Icons.Filled.Check, null) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextFilter(f: Filter.Text, ticks: FilterTicks, onChanged: () -> Unit) {
    // A reset puts new [ticks] in place: the field shows the filter's value again.
    var text by remember(f, ticks) { mutableStateOf(f.state) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; f.state = it; onChanged() },
        label = { Text(f.name) },
        leadingIcon = { Icon(filterIcon(f.name).takeIf { it != Icons.Outlined.Tune } ?: Icons.Outlined.TextFields, null) },
        trailingIcon = if (text.isNotEmpty()) {
            { IconButton(onClick = { text = ""; f.state = ""; onChanged() }) { Icon(Icons.Filled.Clear, stringResource(CoreR.string.clear)) } }
        } else {
            null
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CheckRow(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(horizontal = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.m),
    ) {
        Icon(icon, null, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun excludeInputColors() = InputChipDefaults.inputChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onErrorContainer,
)

/** Size changes of chips and headers (an icon appears, a summary folds away). */
@Composable
private fun sizeSpec(): FiniteAnimationSpec<IntSize> =
    motionSpec(spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold))

private fun kindIcon(kind: ActiveFilter.Kind): ImageVector = when (kind) {
    ActiveFilter.Kind.INCLUDE -> Icons.Filled.Check
    ActiveFilter.Kind.EXCLUDE -> Icons.Outlined.Block
    ActiveFilter.Kind.SORT -> Icons.Outlined.SwapVert
    ActiveFilter.Kind.VALUE -> Icons.Outlined.Tune
}

private fun isChip(f: Filter<*>): Boolean = f is Filter.CheckBox || f is Filter.TriState

/** Consecutive checkboxes and tri-states together (one row of chips), every other filter on its own. */
private fun runsOf(list: List<Filter<*>>): List<List<Filter<*>>> {
    val out = ArrayList<List<Filter<*>>>()
    var chips = ArrayList<Filter<*>>()
    list.forEach { f ->
        if (isChip(f)) {
            chips += f
        } else {
            if (chips.isNotEmpty()) { out += chips; chips = ArrayList() }
            out += listOf(f)
        }
    }
    if (chips.isNotEmpty()) out += chips
    return out
}

/** An icon for a filter from words of its name (sources name filters freely, in several languages). */
internal fun filterIcon(name: String): ImageVector {
    val n = name.lowercase()
    fun has(vararg words: String) = words.any { n.contains(it) }
    return when {
        has("sort", "order", "сортир", "порядок") -> Icons.AutoMirrored.Outlined.Sort
        has("rating", "рейтинг", "nsfw", "18+", "adult", "взросл") -> Icons.Outlined.Shield
        has("status", "статус") -> Icons.Outlined.Timelapse
        has("genre", "жанр") -> Icons.Outlined.TheaterComedy
        has("theme", "тема", "тематик") -> Icons.Outlined.Palette
        has("format", "формат") -> Icons.AutoMirrored.Outlined.Article
        has("tag", "тег", "метк") -> Icons.Outlined.Sell
        has("language", "lang", "язык") -> Icons.Outlined.Translate
        has("demographic", "демограф", "audience", "аудитор") -> Icons.Outlined.Groups
        has("type", "тип", "kind") -> Icons.Outlined.Category
        has("year", "год", "date", "дата", "created", "within", "period", "период", "time", "время") -> Icons.Outlined.CalendarMonth
        has("author", "artist", "автор", "художник", "creator", "scanlat", "перевод", "group", "групп") -> Icons.Outlined.Person
        has("chapter", "глав") -> Icons.AutoMirrored.Outlined.MenuBook
        has("content", "контент", "warning", "предупрежд") -> Icons.Outlined.ReportProblem
        else -> Icons.Outlined.Tune
    }
}

/** Groups up to this size start open. */
private const val SMALL_GROUP = 6

/** Groups with more chips than this get a search field. */
private const val SEARCHABLE_GROUP = 12

/** Chips shown before "Show all" in long groups. */
private const val CHIP_PAGE = 60

/** Selects with up to this many short values are chips; longer ones are a menu. */
private const val SELECT_CHIPS_MAX = 5
private const val SELECT_CHIP_CHARS = 18
