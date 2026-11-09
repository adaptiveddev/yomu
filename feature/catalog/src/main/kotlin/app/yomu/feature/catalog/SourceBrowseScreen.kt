package app.yomu.feature.catalog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import app.yomu.core.ui.components.rememberHaptic
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.yomu.core.common.AppError
import app.yomu.core.ui.components.BookGridItem
import app.yomu.core.ui.components.BookListItem
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.ErrorState
import app.yomu.core.ui.components.ExpandVisibility
import app.yomu.core.ui.components.LocalReduceMotion
import app.yomu.core.ui.components.SearchField
import app.yomu.core.ui.components.SkeletonGrid
import app.yomu.core.ui.components.SkeletonList
import app.yomu.core.ui.components.YomuTab
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.components.motionItem
import app.yomu.core.ui.theme.Dimens
import app.yomu.source.FilterList
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import kotlinx.coroutines.launch
import kotlin.math.abs
import app.yomu.core.ui.R as CoreR

/**
 * Browsing one source: "Popular", "Latest" and "Search" tabs that can also be swiped. The search tab holds the search
 * field and the source's filters (they used to be icons in the top bar); every tab keeps its own list, scroll position
 * and loading or error state. An OPDS sub-catalog is a single list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(
    viewModel: BrowseModel,
    onBack: () -> Unit,
    onOpenTitle: (Long) -> Unit,
    onOpenNavigation: (sourceId: Long, url: String, title: String) -> Unit,
    onOpenWebView: (String) -> Unit,
    /** Opens another language of the source instead of this one, with the search (if any) and the tab on display. */
    onSwitchSource: ((sourceId: Long, query: String?, tab: String?) -> Unit)? = null,
) {
    val inLibrary by viewModel.inLibrary.collectAsStateWithLifecycle()
    val variants by viewModel.variants.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var listMode by rememberSaveable { mutableStateOf(viewModel.content == SourceContent.BOOK || viewModel.isNavigation) }
    val tabs = viewModel.tabs
    val pager = rememberPagerState(initialPage = tabs.indexOf(viewModel.initialTab).coerceAtLeast(0)) { tabs.size }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val reduce = LocalReduceMotion.current
    // Scroll positions live here: a page that is swiped away leaves the composition, its position stays.
    val popularGrid = rememberLazyGridState()
    val popularList = rememberLazyListState()
    val latestGrid = rememberLazyGridState()
    val latestList = rememberLazyListState()
    val searchGrid = rememberSaveable(results?.id, saver = LazyGridState.Saver) { LazyGridState() }
    val searchList = rememberSaveable(results?.id, saver = LazyListState.Saver) { LazyListState() }

    CatalogEventsEffect(viewModel.events, snackbar, onOpenTitle, onOpenWebView)

    val onItem: (SItem) -> Unit = { item ->
        if (item.isNavigation) onOpenNavigation(viewModel.sourceId, item.url, item.title) else viewModel.open(item)
    }
    val haptic = rememberHaptic()
    val onLongItem: (SItem) -> Unit = { item -> if (!item.isNavigation) { haptic.confirm(); viewModel.toggleLibrary(item) } }

    // Leaving the search tab hides the keyboard.
    LaunchedEffect(pager, tabs) {
        snapshotFlow { pager.currentPage }.collect { page ->
            if (tabs.getOrNull(page) != BrowseTab.SEARCH) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(viewModel.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) } },
                    actions = {
                        if (onSwitchSource != null && variants.size > 1) {
                            LanguageSwitch(variants, viewModel.sourceId) { target ->
                                scope.launch {
                                    viewModel.carryFiltersTo(target.sourceId)
                                    val tab = tabs.getOrNull(pager.currentPage)
                                    onSwitchSource(target.sourceId, results?.query?.takeIf { tab == BrowseTab.SEARCH }, tab?.name)
                                }
                            }
                        }
                        IconButton(onClick = { listMode = !listMode }) {
                            Icon(if (listMode) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.toggle_layout))
                        }
                        viewModel.webUrl?.let { url ->
                            IconButton(onClick = { onOpenWebView(url) }) { Icon(Icons.Filled.Public, stringResource(CoreR.string.open_webview)) }
                        }
                    },
                )
                if (viewModel.exists && tabs.size > 1) {
                    BrowseTabRow(tabs, pager) { index ->
                        scope.launch {
                            if (reduce) pager.scrollToPage(index) else pager.animateScrollToPage(index, animationSpec = tween(320))
                            // Tapping "Search" before anything was searched: straight to typing.
                            if (tabs[index] == BrowseTab.SEARCH && viewModel.results.value == null && viewModel.supportsSearch) {
                                runCatching { focus.requestFocus() }
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (!viewModel.exists) {
                EmptyState(title = stringResource(R.string.source_missing), modifier = Modifier.fillMaxSize())
                return@Box
            }
            HorizontalPager(state = pager, key = { tabs[it] }, modifier = Modifier.fillMaxSize()) { page ->
                when (val tab = tabs[page]) {
                    BrowseTab.POPULAR, BrowseTab.LATEST -> {
                        val items = (if (tab == BrowseTab.LATEST) viewModel.latest else viewModel.popular).collectAsLazyPagingItems()
                        BrowseList(
                            items = items,
                            listMode = listMode,
                            inLibrary = inLibrary,
                            gridState = if (tab == BrowseTab.LATEST) latestGrid else popularGrid,
                            listState = if (tab == BrowseTab.LATEST) latestList else popularList,
                            snackbar = snackbar,
                            onItem = onItem,
                            onLongItem = onLongItem,
                            onOpenWebView = onOpenWebView,
                        )
                    }
                    BrowseTab.SEARCH -> SearchPage(
                        model = viewModel,
                        results = results,
                        listMode = listMode,
                        inLibrary = inLibrary,
                        gridState = searchGrid,
                        listState = searchList,
                        snackbar = snackbar,
                        focus = focus,
                        onItem = onItem,
                        onLongItem = onLongItem,
                        onOpenWebView = onOpenWebView,
                    )
                }
            }
        }
    }
}

private fun BrowseTab.label(): Int = when (this) {
    BrowseTab.POPULAR -> R.string.browse_popular
    BrowseTab.LATEST -> R.string.browse_latest
    BrowseTab.SEARCH -> R.string.browse_search
}

/** The tabs; the indicator follows the pages while they are swiped instead of jumping when a swipe ends. */
@Composable
private fun BrowseTabRow(tabs: List<BrowseTab>, pager: PagerState, onSelect: (Int) -> Unit) {
    PrimaryTabRow(
        selectedTabIndex = pager.currentPage,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorLayout { measurable, constraints, positions ->
                    if (positions.isEmpty()) return@tabIndicatorLayout layout(0, 0) {}
                    val page = pager.currentPage.coerceIn(positions.indices)
                    val fraction = pager.currentPageOffsetFraction
                    val from = positions[page]
                    val to = positions.getOrNull(if (fraction >= 0f) page + 1 else page - 1) ?: from
                    val f = abs(fraction).coerceIn(0f, 1f)
                    val width = lerp(from.contentWidth, to.contentWidth, f).roundToPx()
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    // Placed like the stock indicator: the row centres it in a tab-wide slot that starts at "left".
                    layout(placeable.width, placeable.height) { placeable.place(lerp(from.left, to.left, f).roundToPx(), 0) }
                },
                width = Dp.Unspecified,
            )
        },
    ) {
        tabs.forEachIndexed { index, tab ->
            YomuTab(
                selected = pager.currentPage == index,
                onClick = { onSelect(index) },
                text = { Text(stringResource(tab.label()), maxLines = 1) },
            )
        }
    }
}

/**
 * One list of titles with its own states: a skeleton while the first page loads, the error with "Retry" (and
 * "Open in WebView" for verification pages), "Nothing found", or the titles with pull to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseList(
    items: LazyPagingItems<SItem>,
    listMode: Boolean,
    inLibrary: Set<String>,
    gridState: LazyGridState,
    listState: LazyListState,
    snackbar: SnackbarHostState,
    onItem: (SItem) -> Unit,
    onLongItem: (SItem) -> Unit,
    onOpenWebView: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val refresh = items.loadState.refresh
    val empty = items.itemCount == 0
    // A refresh that fails while titles are on screen (pull to refresh) keeps them and tells why in a snackbar.
    LaunchedEffect(refresh) {
        if (refresh is LoadState.Error && items.itemCount > 0) snackbar.showSnackbar(errorMessage(context, refresh.error))
    }
    // The refresh indicator only spins for refreshes that start while titles are shown (pull to refresh). The first
    // page can arrive a frame before its "loaded" state: without this the indicator would flash in and out.
    var settled by remember(items) { mutableStateOf(false) }
    LaunchedEffect(refresh) { if (refresh !is LoadState.Loading) settled = true }
    when {
        empty && refresh is LoadState.Error -> {
            val err = AppError.from(refresh.error)
            ErrorState(
                error = err,
                modifier = modifier.fillMaxSize(),
                onRetry = { items.retry() },
                secondaryLabel = if (err is AppError.NeedsWebView) stringResource(CoreR.string.open_webview) else null,
                onSecondary = if (err is AppError.NeedsWebView) ({ onOpenWebView(err.url) }) else null,
            )
        }
        // Finished with nothing (the first load of a list has not reached the end yet, so it shows the skeleton).
        empty && refresh is LoadState.NotLoading && items.loadState.append.endOfPaginationReached ->
            EmptyState(title = stringResource(R.string.browse_nothing), icon = Icons.Outlined.SearchOff, modifier = modifier.fillMaxSize())
        empty -> if (listMode) SkeletonList(modifier.fillMaxSize()) else SkeletonGrid(modifier.fillMaxSize())
        else -> PullToRefreshBox(isRefreshing = settled && refresh is LoadState.Loading, onRefresh = { items.refresh() }, modifier = modifier.fillMaxSize()) {
            if (listMode) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = Dimens.xl)) {
                    items(count = items.itemCount, key = items.itemKey { it.url }) { i ->
                        val item = items[i] ?: return@items
                        if (item.isNavigation) {
                            ListItem(
                                headlineContent = { Text(item.title) },
                                supportingContent = item.description?.takeIf { it.isNotBlank() }?.let { d -> { Text(d, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                                leadingContent = { Icon(Icons.Filled.Folder, null) },
                                modifier = motionItem().clickable { onItem(item) },
                            )
                        } else {
                            BookListItem(
                                title = item.title,
                                subtitle = item.author,
                                cover = item.thumbnailUrl,
                                progress = 0f,
                                meta = listOfNotNull(
                                    if (item.url in inLibrary) stringResource(R.string.in_library) else null,
                                    item.links.map { it.formatLabel }.distinct().joinToString(", ").ifBlank { null },
                                ).joinToString(" · "),
                                onClick = { onItem(item) },
                                onLongClick = { onLongItem(item) },
                                modifier = motionItem(),
                            )
                        }
                    }
                    item(key = "footer") { AppendFooter(items) }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(Dimens.gridMin),
                    state = gridState,
                    contentPadding = PaddingValues(Dimens.m),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.m),
                    verticalArrangement = Arrangement.spacedBy(Dimens.m),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(count = items.itemCount, key = items.itemKey { it.url }) { i ->
                        val item = items[i] ?: return@items
                        BookGridItem(
                            title = item.title,
                            subtitle = item.author,
                            cover = item.thumbnailUrl,
                            progress = 0f,
                            badge = if (item.url in inLibrary) "✓" else null,
                            onClick = { onItem(item) },
                            onLongClick = { onLongItem(item) },
                            modifier = motionItem(),
                        )
                    }
                    item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { AppendFooter(items) }
                }
            }
        }
    }
}

/**
 * The Search tab: the field and a filter button on top. Before the first search, and whenever the filter button is
 * on, the page shows recent searches and the filters with a "Show results" button; otherwise the results, with the
 * active filters as chips above them.
 */
@Composable
private fun SearchPage(
    model: BrowseModel,
    results: SearchResults?,
    listMode: Boolean,
    inLibrary: Set<String>,
    gridState: LazyGridState,
    listState: LazyListState,
    snackbar: SnackbarHostState,
    focus: FocusRequester,
    onItem: (SItem) -> Unit,
    onLongItem: (SItem) -> Unit,
    onOpenWebView: (String) -> Unit,
) {
    val query by model.query.collectAsStateWithLifecycle()
    val filters by model.filters.collectAsStateWithLifecycle()
    val epoch by model.filterEpoch.collectAsStateWithLifecycle()
    val filtersLoading by model.filtersLoading.collectAsStateWithLifecycle()
    val active by model.activeFilters.collectAsStateWithLifecycle()
    // The same callbacks on every redraw, so the filter controls can skip redrawing.
    val onFilterChanged = remember(model) { { model.onFilterChanged() } }
    val onReload = remember(model) { { model.reloadFilters() } }
    val history by model.searchHistory.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var editing by rememberSaveable { mutableStateOf(false) }
    val showPanel = results == null || editing
    val run: () -> Unit = {
        focusManager.clearFocus()
        editing = false
        model.submit()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.m, top = Dimens.s, bottom = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            if (model.supportsSearch) {
                SearchField(
                    query = query,
                    onQueryChange = { model.query.value = it },
                    placeholder = stringResource(R.string.search_in_source, model.title),
                    modifier = Modifier.weight(1f),
                    onSearch = { run() },
                    focusRequester = focus,
                )
            } else {
                Text(stringResource(R.string.filters), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(vertical = Dimens.m))
            }
            if (filters.isNotEmpty()) {
                FilledTonalIconToggleButton(
                    checked = showPanel,
                    onCheckedChange = { if (results != null) editing = !editing },
                    modifier = Modifier.size(52.dp),
                ) {
                    BadgedBox(badge = { if (active.isNotEmpty()) Badge { Text(active.size.toString()) } }) {
                        Icon(Icons.Outlined.Tune, stringResource(R.string.filters))
                    }
                }
            }
        }
        AnimatedContent(
            targetState = showPanel,
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(110)) },
            modifier = Modifier.weight(1f),
            label = "searchPage",
        ) { panel ->
            if (panel || results == null) {
                SearchPanel(
                    history = history,
                    filters = filters,
                    version = epoch,
                    loading = filtersLoading,
                    activeCount = active.size,
                    onPick = { q ->
                        focusManager.clearFocus()
                        editing = false
                        model.searchFromHistory(q)
                    },
                    onClearHistory = model::clearSearchHistory,
                    onChanged = onFilterChanged,
                    onReset = model::resetFilters,
                    onReload = onReload,
                    onSearch = run,
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    ExpandVisibility(active.isNotEmpty()) {
                        ActiveFiltersRow(
                            active = active,
                            onEdit = { editing = true },
                            onRemove = { model.resetFilter(it.path) },
                            onClear = model::clearFilters,
                        )
                    }
                    key(results.id) {
                        BrowseList(
                            items = results.items.collectAsLazyPagingItems(),
                            listMode = listMode,
                            inLibrary = inLibrary,
                            gridState = gridState,
                            listState = listState,
                            snackbar = snackbar,
                            onItem = onItem,
                            onLongItem = onLongItem,
                            onOpenWebView = onOpenWebView,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Recent searches and the source's filters, with "Show results" pinned at the bottom. */
@Composable
private fun SearchPanel(
    history: List<String>,
    filters: FilterList,
    version: Int,
    loading: Boolean,
    activeCount: Int,
    onPick: (String) -> Unit,
    onClearHistory: () -> Unit,
    onChanged: () -> Unit,
    onReset: () -> Unit,
    onReload: () -> Unit,
    onSearch: () -> Unit,
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (history.isNotEmpty()) {
                PanelHeader(stringResource(R.string.browse_recent))
                SearchHistoryRow(history, onPick = onPick, onClear = onClearHistory, modifier = Modifier.padding(bottom = Dimens.s))
            }
            if (filters.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(end = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    PanelHeader(stringResource(R.string.filters))
                    if (activeCount > 0) Badge { Text(activeCount.toString()) }
                    Spacer(Modifier.weight(1f))
                    // Always on: extensions ask to press it to load their tags, before anything was chosen (1.8.0).
                    TextButton(onClick = onReset) {
                        Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Dimens.xs))
                        Text(stringResource(R.string.filters_reset))
                    }
                }
                SourceFiltersContent(
                    filters = filters,
                    version = version,
                    onChanged = onChanged,
                    modifier = Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.l, bottom = Dimens.l),
                    loading = loading,
                    onReload = onReload,
                )
            } else if (loading) {
                Box(Modifier.fillMaxWidth().padding(top = Dimens.xxl), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) }
            } else if (history.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.browse_search_hint),
                    icon = Icons.Filled.Search,
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.xxl),
                )
            }
        }
        if (filters.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Button(onClick = onSearch, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
                        Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Dimens.s))
                        Text(stringResource(R.string.browse_show_results))
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Dimens.l, end = Dimens.s, top = Dimens.m, bottom = Dimens.s),
    )
}

@Composable
private fun AppendFooter(items: LazyPagingItems<SItem>) {
    when (val append = items.loadState.append) {
        is LoadState.Loading -> Box(Modifier.fillMaxWidth().padding(Dimens.l), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        is LoadState.Error -> Column(Modifier.fillMaxWidth().padding(Dimens.l), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(errorMessage(append.error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { items.retry() }) { Text(stringResource(CoreR.string.retry)) }
        }
        else -> Unit
    }
}

/**
 * The language of a source that comes in several (1.9.0): its code in the top bar, the languages in a menu. Before,
 * the only way to another language was back to the catalog and the source list again.
 */
@Composable
private fun LanguageSwitch(variants: List<SourceVariant>, current: Long, onPick: (SourceVariant) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val lang = variants.firstOrNull { it.sourceId == current }?.lang.orEmpty()
    val code = if (SourceVariants.isMultiLang(lang)) stringResource(R.string.source_lang_all_short) else lang.uppercase()
    val description = stringResource(R.string.source_language, langLabel(lang, single = true))
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = description }) {
            Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(code, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            variants.forEach { v ->
                val selected = v.sourceId == current
                DropdownMenuItem(
                    text = { Text(langLabel(v.lang, single = true)) },
                    onClick = {
                        open = false
                        if (!selected) onPick(v)
                    },
                    trailingIcon = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                )
            }
        }
    }
}
