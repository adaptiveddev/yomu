package app.yomu.feature.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.yomu.core.ui.components.RemovableChip
import app.yomu.core.ui.components.SearchField
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.ToolbarPill
import app.yomu.core.ui.components.YomuSheet
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.source.SourceInfo
import app.yomu.data.source.SourceManager
import app.yomu.domain.catalog.NsfwFilter
import app.yomu.domain.catalog.SourceListFilter
import app.yomu.domain.catalog.SourceSortMode
import app.yomu.source.SourceContent

/*
 * Filters of the source list (1.8.0). The languages used to be one long row of chips that was built in full, every
 * language at once, each time the list changed; now a lazy bar shows the buttons and the active filters only, and the
 * choices live in two sheets. Everything is kept in the settings, so the list looks the same after a restart.
 */

private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value

@Composable
private fun kindLabel(kind: SourceInfo.Kind): String = stringResource(
    when (kind) {
        SourceInfo.Kind.EXTENSION -> R.string.source_filter_kind_extension
        SourceInfo.Kind.BUILT_IN -> R.string.source_filter_kind_built_in
        SourceInfo.Kind.OPDS -> R.string.source_filter_kind_opds
        SourceInfo.Kind.LOCAL -> R.string.source_filter_kind_local
    },
)

@Composable
private fun nsfwLabel(mode: NsfwFilter): String = stringResource(
    when (mode) {
        NsfwFilter.ALL -> R.string.source_filter_nsfw_all
        NsfwFilter.HIDE -> R.string.source_filter_nsfw_hide
        NsfwFilter.ONLY -> R.string.source_filter_nsfw_only
    },
)

/** "All languages", "English", "English, Русский" or "5 languages". */
@Composable
private fun languagesSummary(langs: Set<String>): String = when {
    SourceManager.ALL_LANGUAGES in langs || langs.isEmpty() -> stringResource(R.string.catalog_all_languages)
    langs.size <= 2 -> langs.map { langLabel(it) }.sortedBy { it.lowercase() }.joinToString(", ")
    else -> pluralStringResource(R.plurals.source_filter_languages, langs.size, langs.size)
}

/**
 * The bar over the source list: Filters (with the number of active filters), Languages and Sort buttons, then every
 * active filter as a chip that removes it, and "12 of 340" while something is hidden.
 */
@Composable
internal fun SourceFilterBar(
    filter: SourceListFilter,
    langs: Set<String>,
    sort: SourceSortMode,
    shown: Int,
    total: Int,
    onOpenFilters: () -> Unit,
    onOpenLanguages: () -> Unit,
    onUpdate: ((SourceListFilter) -> SourceListFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val allLanguages = SourceManager.ALL_LANGUAGES in langs
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Dimens.l),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "filters") {
            ToolbarPill(Icons.Filled.FilterList, stringResource(R.string.source_filters), onClick = onOpenFilters, badge = filter.activeCount)
        }
        item(key = "languages") {
            ToolbarPill(Icons.Filled.Translate, languagesSummary(langs), onClick = onOpenLanguages, badge = if (allLanguages) 0 else langs.size)
        }
        item(key = "sort") {
            ToolbarPill(Icons.AutoMirrored.Filled.Sort, sourceSortLabel(sort), onClick = onOpenFilters)
        }
        filter.content.sorted().forEach { name ->
            val content = SourceContent.entries.firstOrNull { it.name == name } ?: return@forEach
            item(key = "content-$name") { RemovableChip(contentLabel(context, content), onRemove = { onUpdate { it.copy(content = it.content - name) } }) }
        }
        filter.kinds.sorted().forEach { name ->
            val kind = SourceInfo.Kind.entries.firstOrNull { it.name == name } ?: return@forEach
            item(key = "kind-$name") { RemovableChip(kindLabel(kind), onRemove = { onUpdate { it.copy(kinds = it.kinds - name) } }) }
        }
        if (filter.nsfw != NsfwFilter.ALL) {
            item(key = "nsfw") { RemovableChip(nsfwLabel(filter.nsfw), onRemove = { onUpdate { it.copy(nsfw = NsfwFilter.ALL) } }) }
        }
        if (filter.pinnedOnly) {
            item(key = "pinned") { RemovableChip(stringResource(R.string.source_filter_pinned), onRemove = { onUpdate { it.copy(pinnedOnly = false) } }) }
        }
        if (filter.hideDisabled) {
            item(key = "disabled") { RemovableChip(stringResource(R.string.source_filter_hide_disabled), onRemove = { onUpdate { it.copy(hideDisabled = false) } }) }
        }
        if (filter.hideUnstable) {
            item(key = "unstable") { RemovableChip(stringResource(R.string.source_filter_hide_unstable), onRemove = { onUpdate { it.copy(hideUnstable = false) } }) }
        }
        if (filter.showHidden) {
            item(key = "hidden") { RemovableChip(stringResource(R.string.source_filter_show_hidden), onRemove = { onUpdate { it.copy(showHidden = false) } }) }
        }
        if (shown < total) {
            item(key = "count") {
                Text(
                    stringResource(R.string.source_filter_shown, shown, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.xs),
                )
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = Dimens.xl, end = Dimens.xl, top = Dimens.m, bottom = Dimens.xs),
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = Dimens.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = Dimens.m))
    }
}

/** Sort and filters of the source list (everything but the languages, which have a sheet of their own). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceFilterSheet(
    filter: SourceListFilter,
    sort: SourceSortMode,
    onSort: (SourceSortMode) -> Unit,
    onUpdate: ((SourceListFilter) -> SourceListFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    YomuSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(end = Dimens.l), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { SheetTitle(stringResource(R.string.source_filters_title)) }
                TextButton(enabled = filter.isActive, onClick = { onUpdate { SourceListFilter() } }) { Text(stringResource(R.string.chapter_filters_reset)) }
            }
            SheetLabel(stringResource(R.string.source_sort))
            SourceSortMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
                        .selectable(selected = mode == sort, role = Role.RadioButton, onClick = { onSort(mode) })
                        .padding(horizontal = Dimens.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == sort, onClick = null)
                    Text(sourceSortLabel(mode), Modifier.padding(start = Dimens.m))
                }
            }
            SheetLabel(stringResource(R.string.source_filter_content))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.xl), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                SourceContent.entries.forEach { c ->
                    FilterChip(
                        selected = c.name in filter.content,
                        onClick = { onUpdate { it.copy(content = it.content.toggled(c.name)) } },
                        label = { Text(contentLabel(context, c)) },
                        shape = CircleShape,
                    )
                }
            }
            SheetLabel(stringResource(R.string.source_filter_kind))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.xl), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                listOf(SourceInfo.Kind.EXTENSION, SourceInfo.Kind.BUILT_IN, SourceInfo.Kind.OPDS, SourceInfo.Kind.LOCAL).forEach { k ->
                    FilterChip(
                        selected = k.name in filter.kinds,
                        onClick = { onUpdate { it.copy(kinds = it.kinds.toggled(k.name)) } },
                        label = { Text(kindLabel(k)) },
                        shape = CircleShape,
                    )
                }
            }
            SheetLabel(stringResource(R.string.source_filter_nsfw))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.xl), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                NsfwFilter.entries.forEach { mode ->
                    FilterChip(
                        selected = filter.nsfw == mode,
                        onClick = { onUpdate { it.copy(nsfw = mode) } },
                        label = { Text(nsfwLabel(mode)) },
                        shape = CircleShape,
                    )
                }
            }
            SheetLabel(stringResource(R.string.source_filter_show))
            CheckRow(stringResource(R.string.source_filter_pinned), filter.pinnedOnly) { v -> onUpdate { it.copy(pinnedOnly = v) } }
            CheckRow(stringResource(R.string.source_filter_hide_disabled), filter.hideDisabled) { v -> onUpdate { it.copy(hideDisabled = v) } }
            CheckRow(stringResource(R.string.source_filter_hide_unstable), filter.hideUnstable) { v -> onUpdate { it.copy(hideUnstable = v) } }
            CheckRow(stringResource(R.string.source_filter_show_hidden), filter.showHidden) { v -> onUpdate { it.copy(showHidden = v) } }
        }
    }
}

/**
 * Languages of the source list: a searchable list with the number of sources of each language. Built lazily, so a
 * repository with a hundred language variants opens at once.
 */
@Composable
internal fun SourceLanguagesSheet(
    all: List<String>,
    counts: Map<String, Int>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onAll: () -> Unit,
    onDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val multi = stringResource(R.string.ext_lang_multi)
    val labels = remember(all, multi) { all.associateWith { if (it == "multi") multi else languageName(it) } }
    val q = query.trim()
    val shown = remember(all, labels, q) {
        if (q.isEmpty()) all else all.filter { l -> labels[l].orEmpty().contains(q, ignoreCase = true) || l.contains(q, ignoreCase = true) }
    }
    val everything = SourceManager.ALL_LANGUAGES in selected
    YomuSheet(onDismiss = onDismiss) {
        SheetTitle(stringResource(R.string.source_filter_languages_title))
        if (all.size > 6) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.source_filter_languages_search),
                modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            FilterChip(selected = everything, onClick = onAll, label = { Text(stringResource(R.string.catalog_all_languages)) }, shape = CircleShape)
            FilterChip(selected = false, onClick = onDefault, label = { Text(stringResource(R.string.source_filter_languages_default)) }, shape = CircleShape)
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(shown, key = { it }) { l ->
                val checked = !everything && l in selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
                        .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(l) })
                        .padding(horizontal = Dimens.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(labels[l].orEmpty(), Modifier.weight(1f).padding(start = Dimens.m), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        counts[l]?.toString().orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
