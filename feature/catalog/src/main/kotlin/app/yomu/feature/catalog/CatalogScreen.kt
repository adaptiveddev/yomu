package app.yomu.feature.catalog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.ui.components.BookGridItem
import app.yomu.core.ui.components.BookListItem
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.SearchField
import app.yomu.core.ui.components.SheetAction
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.SkeletonList
import app.yomu.core.ui.components.TextInputDialog
import app.yomu.core.ui.components.YomuSheet
import app.yomu.core.ui.components.YomuTab
import app.yomu.core.ui.components.motionItem
import app.yomu.core.ui.components.PillTab
import app.yomu.core.ui.components.PillTabRow
import app.yomu.core.ui.components.TabActionsSheet
import app.yomu.core.ui.components.TabOption
import app.yomu.core.ui.components.TopLevelBody
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.components.topLevelContentInsets
import app.yomu.core.ui.components.TabsPickerSheet
import app.yomu.core.ui.components.ToolbarPill
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.catalog.HistoryEntry
import app.yomu.data.source.SourceHealth
import app.yomu.data.source.SourceInfo
import app.yomu.data.source.SourceManager
import app.yomu.domain.catalog.LibraryDisplay
import app.yomu.domain.catalog.LibrarySortMode
import app.yomu.domain.catalog.SourceListFilter
import app.yomu.domain.catalog.SourceSortMode
import app.yomu.domain.model.BookCollection
import app.yomu.domain.model.CatalogEntry
import app.yomu.core.ui.R as CoreR

private enum class HomeTab { SOURCES, LIBRARY, UPDATES, HISTORY }

/** Catalog home: sources, the online library (favourites), updates feed and reading history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogHomeViewModel,
    onOpenSource: (Long) -> Unit,
    onOpenTitle: (Long) -> Unit,
    onReadChapter: (Long) -> Unit,
    onGlobalSearch: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val sources by viewModel.sourceList.collectAsStateWithLifecycle()
    val langs by viewModel.languages.collectAsStateWithLifecycle()
    val allLangs by viewModel.allLanguages.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val libraryTotal by viewModel.libraryTotal.collectAsStateWithLifecycle()
    val libraryDisplay by viewModel.libraryDisplay.collectAsStateWithLifecycle()
    val sourceQuery by viewModel.sourceQuery.collectAsStateWithLifecycle()
    val sourceSort by viewModel.sourceSort.collectAsStateWithLifecycle()
    val sourceFilter by viewModel.sourceFilter.collectAsStateWithLifecycle()
    val sourceTotal by viewModel.sourceTotal.collectAsStateWithLifecycle()
    val languageCounts by viewModel.languageCounts.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val tabMembers by viewModel.tabMembers.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val newCount by viewModel.newCount.collectAsStateWithLifecycle()
    val incognito by viewModel.incognito.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showAddOpds by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::configureLocal) }

    CatalogEventsEffect(viewModel.events, snackbar, onOpenTitle)

    Scaffold(
        contentWindowInsets = topLevelContentInsets(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.catalog_title)) },
                actions = {
                    IconButton(onClick = onGlobalSearch) { Icon(Icons.Filled.Search, stringResource(R.string.global_search)) }
                    IconButton(onClick = { viewModel.setIncognito(!incognito) }) {
                        Icon(
                            if (incognito) Icons.Filled.VisibilityOff else Icons.Outlined.Visibility,
                            stringResource(if (incognito) R.string.catalog_incognito_on else R.string.catalog_incognito_off),
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, stringResource(CoreR.string.more)) }
                        DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ext_title)) },
                                leadingIcon = { Icon(Icons.Filled.Extension, null) },
                                onClick = { showMenu = false; onOpenExtensions() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.catalog_downloads)) },
                                leadingIcon = { Icon(Icons.Filled.Download, null) },
                                onClick = { showMenu = false; onOpenDownloads() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.catalog_add_opds)) },
                                leadingIcon = { Icon(Icons.Filled.RssFeed, null) },
                                onClick = { showMenu = false; showAddOpds = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.catalog_local_folder)) },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                                onClick = { showMenu = false; pickFolder.launch(null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.catalog_check_updates)) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { showMenu = false; viewModel.checkUpdates() },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        TopLevelBody(padding, Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                HomeTab.entries.forEachIndexed { i, t ->
                    YomuTab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = {
                            when (t) {
                                HomeTab.SOURCES -> Text(stringResource(R.string.catalog_tab_sources))
                                HomeTab.LIBRARY -> Text(stringResource(R.string.catalog_tab_library))
                                HomeTab.UPDATES -> BadgedBox(
                                    badge = {
                                        if (newCount > 0) {
                                            Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                                Text(newCount.coerceAtMost(99).toString())
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.catalog_tab_updates))
                                }
                                HomeTab.HISTORY -> Text(stringResource(R.string.catalog_tab_history))
                            }
                        },
                    )
                }
            }
            when (HomeTab.entries[tab]) {
                HomeTab.SOURCES -> SourcesTab(
                    ready = ready,
                    sources = sources,
                    query = sourceQuery,
                    sort = sourceSort,
                    health = health,
                    langs = langs,
                    allLangs = allLangs,
                    filter = sourceFilter,
                    total = sourceTotal,
                    languageCounts = languageCounts,
                    viewModel = viewModel,
                    onOpenSource = { s ->
                        if (s.kind == SourceInfo.Kind.LOCAL && !viewModel.localConfigured) pickFolder.launch(null) else onOpenSource(s.id)
                    },
                    onAddOpds = { showAddOpds = true },
                    onPickFolder = { pickFolder.launch(null) },
                    onOpenExtensions = onOpenExtensions,
                )
                HomeTab.LIBRARY -> LibraryTab(favorites, libraryTotal, libraryDisplay, tabs, tabMembers, viewModel, onOpenTitle, onBrowse = { tab = 0 })
                HomeTab.UPDATES -> FeedTab(
                    entries = updates,
                    emptyTitle = stringResource(R.string.updates_empty),
                    emptyIcon = Icons.Outlined.NewReleases,
                    header = {
                        OutlinedButton(onClick = viewModel::checkUpdates, modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s)) {
                            Icon(Icons.Filled.Refresh, null)
                            Text(stringResource(R.string.catalog_check_updates), Modifier.padding(start = Dimens.s))
                        }
                    },
                    onClick = { onReadChapter(it.chapter.id) },
                    onOpenTitle = { onOpenTitle(it.chapter.entryId) },
                    onRemove = null,
                )
                HomeTab.HISTORY -> {
                    var confirmClear by remember { mutableStateOf(false) }
                    FeedTab(
                        entries = history,
                        emptyTitle = stringResource(R.string.history_empty),
                        emptyIcon = Icons.Outlined.History,
                        header = {
                            if (history.isNotEmpty()) {
                                TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = Dimens.s)) {
                                    Icon(Icons.Filled.DeleteSweep, null)
                                    Text(stringResource(R.string.history_clear), Modifier.padding(start = Dimens.s))
                                }
                            }
                        },
                        onClick = { onReadChapter(it.chapter.id) },
                        onOpenTitle = { onOpenTitle(it.chapter.entryId) },
                        onRemove = { viewModel.removeHistory(it.chapter.id) },
                    )
                    if (confirmClear) {
                        ConfirmDialog(
                            title = stringResource(R.string.history_clear),
                            text = stringResource(R.string.history_clear_confirm),
                            confirmLabel = stringResource(CoreR.string.clear),
                            onConfirm = viewModel::clearHistory,
                            onDismiss = { confirmClear = false },
                        )
                    }
                }
            }
        } }
    }

    if (showAddOpds) {
        AddOpdsDialog(onDismiss = { showAddOpds = false }, onAdd = { n, u, l, p -> viewModel.addOpds(n, u, l, p); showAddOpds = false })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourcesTab(
    ready: Boolean,
    sources: List<SourceInfo>,
    query: String,
    sort: SourceSortMode,
    health: Map<Long, SourceHealth>,
    langs: Set<String>,
    allLangs: List<String>,
    filter: SourceListFilter,
    total: Int,
    languageCounts: Map<String, Int>,
    viewModel: CatalogHomeViewModel,
    onOpenSource: (SourceInfo) -> Unit,
    onAddOpds: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenExtensions: () -> Unit,
) {
    var menuFor by remember { mutableStateOf<SourceInfo?>(null) }
    var removeFor by remember { mutableStateOf<SourceInfo?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var showLanguages by remember { mutableStateOf(false) }
    if (!ready && sources.isEmpty() && query.isEmpty()) {
        SkeletonList(Modifier.fillMaxSize())
        return
    }
    // Split once per list, not on every recomposition of the tab.
    val (pinned, others) = remember(sources) { sources.partition { it.pinned } }
    val filtered = filter.isActive || SourceManager.ALL_LANGUAGES !in langs
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
        if (total > 0 || query.isNotEmpty()) {
            item(key = "tools") {
                Column(Modifier.fillMaxWidth().padding(bottom = Dimens.xs)) {
                    if (query.isNotEmpty() || total > SOURCE_SEARCH_MIN) {
                        SearchField(
                            query = query,
                            onQueryChange = { viewModel.sourceQuery.value = it },
                            placeholder = stringResource(R.string.sources_search_hint),
                            modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
                        )
                    }
                    SourceFilterBar(
                        filter = filter,
                        langs = langs,
                        sort = sort,
                        shown = sources.size,
                        total = total,
                        onOpenFilters = { showFilters = true },
                        onOpenLanguages = { showLanguages = true },
                        onUpdate = { viewModel.updateSourceFilter(it) },
                        modifier = Modifier.padding(vertical = Dimens.xs),
                    )
                }
            }
        }
        if (sources.isEmpty() && query.isNotEmpty()) {
            item(key = "not-found") {
                EmptyState(title = stringResource(R.string.sources_not_found, query), icon = Icons.Filled.Search, modifier = Modifier.fillMaxWidth().padding(top = Dimens.xl))
            }
        } else if (sources.isEmpty() && total > 0 && filtered) {
            item(key = "filtered-empty") {
                EmptyState(
                    title = stringResource(R.string.source_filter_empty),
                    icon = Icons.Filled.FilterList,
                    actionLabel = stringResource(R.string.chapter_filters_reset),
                    onAction = { viewModel.resetSourceFilters() },
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.xl),
                )
            }
        } else if (sources.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.sources_empty),
                    message = stringResource(R.string.sources_empty_hint),
                    icon = Icons.Outlined.Public,
                    actionLabel = stringResource(R.string.ext_title),
                    onAction = onOpenExtensions,
                )
            }
        }
        val row: @Composable LazyItemScope.(SourceInfo) -> Unit = { s ->
            SourceRow(
                s = s,
                health = health[s.id],
                onOpen = { onOpenSource(s) },
                onMenu = { menuFor = s },
                onPin = { viewModel.setPinned(s.id, !s.pinned) },
                modifier = Modifier.animateItem(),
            )
        }
        if (pinned.isNotEmpty()) {
            item(key = "h-pinned") { ListHeader(stringResource(R.string.catalog_section_pinned), Modifier.animateItem()) }
            items(pinned, key = { it.id }) { row(it) }
            if (others.isNotEmpty()) item(key = "h-other") { ListHeader(stringResource(R.string.catalog_section_other), Modifier.animateItem()) }
        }
        items(others, key = { it.id }) { row(it) }
        item(key = "add-opds") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.catalog_add_opds)) },
                supportingContent = { Text(stringResource(R.string.catalog_add_opds_hint)) },
                leadingContent = { Icon(Icons.Filled.Add, null) },
                modifier = Modifier.clickable(onClick = onAddOpds),
            )
        }
        item(key = "extensions") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.ext_title)) },
                supportingContent = { Text(stringResource(R.string.ext_hint)) },
                leadingContent = { Icon(Icons.Filled.Extension, null) },
                modifier = Modifier.clickable(onClick = onOpenExtensions),
            )
        }
    }

    if (showFilters) {
        SourceFilterSheet(
            filter = filter,
            sort = sort,
            onSort = { viewModel.setSourceSort(it) },
            onUpdate = { viewModel.updateSourceFilter(it) },
            onDismiss = { showFilters = false },
        )
    }
    if (showLanguages) {
        SourceLanguagesSheet(
            all = allLangs,
            counts = languageCounts,
            selected = langs,
            onToggle = { viewModel.toggleLanguage(it) },
            onAll = { viewModel.clearLanguages() },
            onDefault = { viewModel.defaultLanguages() },
            onDismiss = { showLanguages = false },
        )
    }
    menuFor?.let { s ->
        YomuSheet(onDismiss = { menuFor = null }) {
            SheetTitle(s.name)
            SheetAction(if (s.pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin, stringResource(if (s.pinned) R.string.source_unpin else R.string.source_pin)) {
                viewModel.setPinned(s.id, !s.pinned); menuFor = null
            }
            SheetAction(if (s.enabled) Icons.Outlined.Block else Icons.Outlined.CheckCircle, stringResource(if (s.enabled) R.string.source_disable else R.string.source_enable)) {
                viewModel.setEnabled(s.id, !s.enabled); menuFor = null
            }
            SheetAction(if (s.hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, stringResource(if (s.hidden) R.string.source_unhide else R.string.source_hide)) {
                viewModel.setHidden(s.id, !s.hidden); menuFor = null
            }
            val pkg = s.extensionPkg
            if (pkg != null && !s.hidden) {
                SheetAction(Icons.Outlined.VisibilityOff, stringResource(R.string.source_hide_extension, s.extensionName ?: pkg)) {
                    viewModel.setExtensionHidden(pkg, true); menuFor = null
                }
            }
            if (s.kind == SourceInfo.Kind.LOCAL) {
                SheetAction(Icons.Filled.Folder, stringResource(R.string.catalog_local_folder)) { menuFor = null; onPickFolder() }
            }
            if (s.kind == SourceInfo.Kind.OPDS) {
                SheetAction(Icons.Filled.Delete, stringResource(R.string.source_remove), destructive = true) { removeFor = s; menuFor = null }
            }
        }
    }
    removeFor?.let { s ->
        ConfirmDialog(
            title = stringResource(R.string.source_remove),
            text = stringResource(R.string.source_remove_confirm, s.name),
            confirmLabel = stringResource(CoreR.string.delete),
            onConfirm = { viewModel.removeOpds(s.id) },
            onDismiss = { removeFor = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceRow(s: SourceInfo, health: SourceHealth?, onOpen: () -> Unit, onMenu: () -> Unit, onPin: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val kind = when (s.kind) {
        SourceInfo.Kind.OPDS -> "OPDS"
        SourceInfo.Kind.LOCAL -> stringResource(R.string.source_kind_local)
        SourceInfo.Kind.BUILT_IN, SourceInfo.Kind.EXTENSION -> null
    }
    val details = listOfNotNull(
        s.lang.takeIf { it.isNotBlank() && s.kind != SourceInfo.Kind.LOCAL }?.let { langLabel(it, single = true) },
        contentLabel(context, s.content),
        kind,
        if (s.isNsfw) stringResource(R.string.ext_nsfw) else null,
        if (!s.enabled) stringResource(R.string.source_disabled) else null,
        if (s.hidden) stringResource(R.string.source_hidden) else null,
    )
    val warning = when {
        health?.unstable == true -> stringResource(R.string.source_unstable)
        health?.slow == true -> stringResource(R.string.source_slow)
        else -> null
    }
    val errorColor = MaterialTheme.colorScheme.error
    ListItem(
        headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                buildAnnotatedString {
                    append(details.joinToString(" · "))
                    if (warning != null) {
                        if (details.isNotEmpty()) append(" · ")
                        withStyle(SpanStyle(color = errorColor)) { append(warning) }
                    }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { SourceIcon(s) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPin) {
                    Icon(
                        if (s.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        stringResource(if (s.pinned) R.string.source_unpin else R.string.source_pin),
                        tint = if (s.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, stringResource(CoreR.string.more)) }
            }
        },
        modifier = modifier
            .alpha(if (s.enabled && !s.hidden) 1f else 0.55f)
            .combinedClickable(onClick = onOpen, onLongClick = onMenu),
    )
}

@Composable
private fun ListHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.l, top = Dimens.m, bottom = Dimens.xs),
    )
}

@Composable
internal fun LibraryTab(
    favorites: List<CatalogEntry>,
    total: Int,
    display: LibraryDisplay,
    tabs: List<BookCollection>,
    tabMembers: Map<Long, Set<Long>>,
    viewModel: CatalogHomeViewModel,
    onOpenTitle: (Long) -> Unit,
    onBrowse: () -> Unit,
    query: String = "",
    showTabs: Boolean = true,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf<Long?>(null) }
    var menuFor by remember { mutableStateOf<CatalogEntry?>(null) }
    var tabsFor by remember { mutableStateOf<CatalogEntry?>(null) }
    var createTab by remember { mutableStateOf(false) }
    var editTab by remember { mutableStateOf<BookCollection?>(null) }
    var showDisplay by remember { mutableStateOf(false) }
    // A deleted tab falls back to "All".
    if (tab != null && tabs.none { it.id == tab }) tab = null

    if (total == 0) {
        EmptyState(
            title = stringResource(R.string.online_library_empty),
            message = stringResource(R.string.online_library_empty_hint),
            icon = Icons.Outlined.CollectionsBookmark,
            actionLabel = stringResource(R.string.catalog_tab_sources),
            onAction = onBrowse,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val q = query.trim()
    val activeTab = tab.takeIf { showTabs }
    val shown = remember(favorites, activeTab, tabMembers, q) {
        favorites.filter { e ->
            (activeTab == null || tabMembers[e.id]?.contains(activeTab) == true) &&
                (q.isEmpty() || e.title.contains(q, ignoreCase = true) || e.author?.contains(q, ignoreCase = true) == true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (showTabs) {
            PillTabRow(
                tabs = listOf(PillTab("all", stringResource(R.string.category_all), total)) +
                    tabs.map { t -> PillTab(t.id.toString(), t.name, tabMembers.values.count { t.id in it }) },
                selected = activeTab?.toString() ?: "all",
                onSelect = { key -> tab = key.toLongOrNull() },
                onLongPress = { key -> key.toLongOrNull()?.let { id -> editTab = tabs.firstOrNull { it.id == id } } },
                addLabel = stringResource(CoreR.string.tabs_new),
                onAdd = { createTab = true },
                modifier = Modifier.padding(top = Dimens.s),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val active = listOf(display.unreadOnly, display.downloadedOnly, display.completedOnly).count { it }
            ToolbarPill(Icons.Filled.FilterList, stringResource(R.string.library_filter_short), onClick = { showDisplay = true }, badge = active)
            ToolbarPill(
                if (display.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                librarySortLabel(display.sort),
                onClick = { showDisplay = true },
            )
        }
        if (shown.isEmpty() && display.isFiltered) {
            EmptyState(
                title = stringResource(R.string.library_filtered_empty),
                actionLabel = stringResource(R.string.chapter_filters_reset),
                onAction = { viewModel.updateLibraryDisplay { it.copy(unreadOnly = false, downloadedOnly = false, completedOnly = false) } },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (shown.isEmpty() && q.isNotEmpty()) {
            EmptyState(title = stringResource(R.string.online_library_nothing_found), icon = Icons.Filled.Search, modifier = Modifier.fillMaxSize())
        } else if (shown.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.tab_empty_online),
                message = stringResource(R.string.tab_empty_online_hint),
                icon = Icons.Outlined.CollectionsBookmark,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(Dimens.gridMin),
                contentPadding = PaddingValues(start = Dimens.m, end = Dimens.m, top = Dimens.xs, bottom = navBarBottomPadding(96.dp)),
                horizontalArrangement = Arrangement.spacedBy(Dimens.m),
                verticalArrangement = Arrangement.spacedBy(Dimens.m),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.id }) { e ->
                    BookGridItem(
                        title = e.title,
                        subtitle = e.author ?: contentLabel(context, e.type),
                        cover = e.coverUrl,
                        progress = 0f,
                        badge = if (e.unread > 0) e.unread.toString() else null,
                        modifier = motionItem(),
                        onClick = { onOpenTitle(e.id) },
                        onLongClick = { menuFor = e },
                    )
                }
            }
        }
    }

    if (showDisplay) {
        LibraryDisplaySheet(display, viewModel, onDismiss = { showDisplay = false })
    }
    menuFor?.let { e ->
        YomuSheet(onDismiss = { menuFor = null }) {
            SheetTitle(e.title)
            SheetAction(Icons.Filled.Bookmarks, stringResource(R.string.tabs_action)) { tabsFor = e; menuFor = null }
            if (e.unread > 0) {
                SheetAction(Icons.Filled.Download, stringResource(R.string.library_download_unread)) { viewModel.downloadUnread(listOf(e.id)); menuFor = null }
            }
            SheetAction(Icons.Filled.Delete, stringResource(R.string.title_remove_from_library), destructive = true) {
                viewModel.removeFromLibrary(listOf(e.id)); menuFor = null
            }
        }
    }
    tabsFor?.let { e ->
        val member = tabMembers[e.id].orEmpty()
        TabsPickerSheet(
            title = stringResource(R.string.tabs_pick_title),
            subtitle = e.title,
            tabs = tabs.map { TabOption(it.id, it.name, if (it.id in member) ToggleableState.On else ToggleableState.Off) },
            onToggle = { id -> viewModel.setTab(listOf(e.id), id, id !in member) },
            onCreate = { name -> viewModel.createTab(name, listOf(e.id)) },
            onDismiss = { tabsFor = null },
        )
    }
    if (createTab) {
        TextInputDialog(
            title = stringResource(CoreR.string.tabs_new),
            label = stringResource(CoreR.string.tabs_name),
            onConfirm = { viewModel.createTab(it) },
            onDismiss = { createTab = false },
        )
    }
    editTab?.let { t ->
        val index = tabs.indexOfFirst { it.id == t.id }
        TabActionsSheet(
            name = t.name,
            canMoveLeft = index > 0,
            canMoveRight = index in 0 until tabs.lastIndex,
            onRename = { viewModel.renameTab(t.id, it) },
            onMove = { viewModel.moveTab(t.id, it) },
            onDelete = { viewModel.deleteTab(t.id); if (tab == t.id) tab = null },
            onDismiss = { editTab = null },
        )
    }
}

private const val SOURCE_SEARCH_MIN = 6

@Composable
internal fun sourceSortLabel(mode: SourceSortMode): String = stringResource(
    when (mode) {
        SourceSortMode.RECENT -> R.string.source_sort_recent
        SourceSortMode.NAME -> R.string.source_sort_name
        SourceSortMode.LANGUAGE -> R.string.source_sort_language
    },
)

@Composable
private fun librarySortLabel(mode: LibrarySortMode): String = stringResource(
    when (mode) {
        LibrarySortMode.LAST_UPDATE -> R.string.library_sort_last_update
        LibrarySortMode.TITLE -> R.string.library_sort_title
        LibrarySortMode.UNREAD -> R.string.library_sort_unread
        LibrarySortMode.LAST_READ -> R.string.library_sort_last_read
        LibrarySortMode.DATE_ADDED -> R.string.library_sort_date_added
        LibrarySortMode.CHAPTERS -> R.string.library_sort_chapters
    },
)

/** Sort and filters of the online library. */
@Composable
private fun LibraryDisplaySheet(display: LibraryDisplay, viewModel: CatalogHomeViewModel, onDismiss: () -> Unit) {
    YomuSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetTitle(stringResource(R.string.sort_and_filter))
            ListHeader(stringResource(R.string.chapter_sort), Modifier.padding(start = Dimens.s))
            LibrarySortMode.entries.forEach { mode ->
                val selected = display.sort == mode
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { viewModel.setLibrarySort(mode) })
                        .padding(horizontal = Dimens.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(librarySortLabel(mode), Modifier.weight(1f).padding(start = Dimens.m))
                    if (selected) {
                        Icon(
                            if (display.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                            stringResource(if (display.descending) R.string.sort_descending else R.string.sort_ascending),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            ListHeader(stringResource(R.string.chapter_filter), Modifier.padding(start = Dimens.s))
            listOf(
                Triple(R.string.library_filter_unread, display.unreadOnly) { v: Boolean -> viewModel.updateLibraryDisplay { it.copy(unreadOnly = v) } },
                Triple(R.string.library_filter_downloaded, display.downloadedOnly) { v: Boolean -> viewModel.updateLibraryDisplay { it.copy(downloadedOnly = v) } },
                Triple(R.string.library_filter_completed, display.completedOnly) { v: Boolean -> viewModel.updateLibraryDisplay { it.copy(completedOnly = v) } },
            ).forEach { (label, checked, onChange) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
                        .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onChange(it) })
                        .padding(horizontal = Dimens.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(stringResource(label), Modifier.padding(start = Dimens.m))
                }
            }
        }
    }
}

@Composable
private fun FeedTab(
    entries: List<HistoryEntry>,
    emptyTitle: String,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    header: @Composable () -> Unit,
    onClick: (HistoryEntry) -> Unit,
    onOpenTitle: (HistoryEntry) -> Unit,
    onRemove: ((HistoryEntry) -> Unit)?,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
        item(key = "header") { header() }
        if (entries.isEmpty()) {
            item(key = "empty") { EmptyState(title = emptyTitle, icon = emptyIcon, modifier = Modifier.fillMaxWidth().padding(top = Dimens.xl)) }
        }
        items(entries, key = { "${it.chapter.id}-${it.readAt}" }) { h ->
            BookListItem(
                title = h.entryTitle,
                subtitle = h.chapter.name,
                cover = h.coverUrl,
                progress = 0f,
                modifier = motionItem(),
                meta = listOfNotNull(
                    relativeTime(h.readAt).ifBlank { null },
                    if (h.chapter.downloaded) stringResource(R.string.chapter_downloaded) else null,
                    if (h.chapter.read) stringResource(R.string.chapter_read) else null,
                ).joinToString(" · "),
                onClick = { onClick(h) },
                onLongClick = { onOpenTitle(h) },
                trailing = {
                    Row {
                        IconButton(onClick = { onOpenTitle(h) }) { Icon(Icons.Outlined.CollectionsBookmark, stringResource(R.string.title_open)) }
                        if (onRemove != null) IconButton(onClick = { onRemove(h) }) { Icon(Icons.Filled.Delete, stringResource(CoreR.string.delete)) }
                    }
                },
            )
        }
    }
}

@Composable
private fun AddOpdsDialog(onDismiss: () -> Unit, onAdd: (String, String, String?, String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val address = webAddress(url)
    val invalid = url.isNotBlank() && address == null
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.RssFeed, null) },
        title = { Text(stringResource(R.string.catalog_add_opds)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                OutlinedTextField(
                    url, { url = it },
                    label = { Text(stringResource(R.string.opds_url)) },
                    placeholder = { Text("example.org/opds") },
                    supportingText = when {
                        invalid -> { { Text(stringResource(R.string.opds_url_invalid)) } }
                        address != null && address != url.trim() -> { { Text(stringResource(R.string.opds_url_will_use, address)) } }
                        else -> null
                    },
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.opds_name)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.opds_user)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    label = { Text(stringResource(R.string.opds_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Outlined.Visibility,
                                stringResource(if (showPassword) R.string.opds_hide_password else R.string.opds_show_password),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.opds_examples), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = address != null, onClick = { address?.let { onAdd(name, it, user, password) } }) { Text(stringResource(CoreR.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}
