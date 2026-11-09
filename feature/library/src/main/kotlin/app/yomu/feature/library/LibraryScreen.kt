package app.yomu.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import app.yomu.core.ui.components.FastScroller
import app.yomu.core.ui.components.SwipeAction
import app.yomu.core.ui.components.SwipeActionsBox
import app.yomu.core.ui.components.asFastScrollTarget
import app.yomu.core.ui.components.rememberHaptic
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.scale
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.yomu.core.ui.components.BookGridItem
import app.yomu.core.ui.components.BookListItem
import app.yomu.core.ui.components.CircleIconButton
import app.yomu.data.download.DownloadSummary
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.CoverImage
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.OneTimeHint
import app.yomu.core.ui.components.PillTab
import app.yomu.core.ui.components.PillTabRow
import app.yomu.core.ui.components.RemovableChip
import app.yomu.core.ui.components.SearchField
import app.yomu.core.ui.components.SheetAction
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.SkeletonGrid
import app.yomu.core.ui.components.SkeletonList
import app.yomu.core.ui.components.TabActionsSheet
import app.yomu.core.ui.components.TabOption
import app.yomu.core.ui.components.TabsPickerSheet
import app.yomu.core.ui.components.TextInputDialog
import app.yomu.core.ui.components.ToolbarPill
import app.yomu.core.ui.components.YomuFab
import app.yomu.core.ui.components.HideNavBarWhile
import app.yomu.core.ui.components.TopLevelBody
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.components.topLevelContentInsets
import app.yomu.core.ui.components.YomuSheet
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.components.motionItem
import app.yomu.core.ui.components.sharedCover
import app.yomu.core.ui.components.tabState
import app.yomu.core.ui.theme.Dimens
import app.yomu.domain.model.Book
import app.yomu.domain.model.BookCollection
import app.yomu.domain.model.CatalogEntry
import app.yomu.domain.model.LibraryFilter
import app.yomu.domain.model.LibrarySort
import app.yomu.domain.model.ReadingStatus
import kotlinx.coroutines.launch
import java.io.File
import app.yomu.core.ui.R as CoreR

private fun Book.cover(): Any? = coverPath?.let { File(it) }

/**
 * The online library pane, provided by the app: the library's "Online" tab (given the snackbar host and the search
 * text) shows every title added with the heart in the catalog, with the catalog's own sort and filters. The same
 * titles also sit on a shelf in "All" and in the user's tabs.
 */
class OnlineLibrary(val pane: @Composable (SnackbarHostState, String) -> Unit)

private const val TAB_ALL = "all"
private const val TAB_ONLINE = "online"

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (Book) -> Unit,
    onOpenDetails: (Book) -> Unit,
    onFindBooksOnline: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTitle: (Long) -> Unit = {},
    onReadChapter: (Long) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    online: OnlineLibrary? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val items = viewModel.paged.collectAsLazyPagingItems()
    val continueItems by viewModel.continueItems.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val filter by viewModel.filterState.collectAsStateWithLifecycle()
    val types by viewModel.typeFilter.collectAsStateWithLifecycle()
    val showBooks by viewModel.showBooks.collectAsStateWithLifecycle()
    val activeFilters by viewModel.activeFilters.collectAsStateWithLifecycle()
    val search by viewModel.searchText.collectAsStateWithLifecycle()
    val grid by viewModel.grid.collectAsStateWithLifecycle()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val showContinue by viewModel.showContinue.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val progress by viewModel.importProgress.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val ascending by viewModel.ascending.collectAsStateWithLifecycle()
    val onlineCount by viewModel.onlineCount.collectAsStateWithLifecycle()
    val onlineShown by viewModel.onlineShown.collectAsStateWithLifecycle()
    val tabPick by viewModel.tabPick.collectAsStateWithLifecycle()
    val incognito by viewModel.incognito.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val audioSearch by viewModel.audioSearch.collectAsStateWithLifecycle()
    val downloads by viewModel.downloadSummary.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val haptic = rememberHaptic()
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showAudioLink by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var newTab by remember { mutableStateOf(false) }
    var tabActions by remember { mutableStateOf<BookCollection?>(null) }
    var onlineMenu by remember { mutableStateOf<CatalogEntry?>(null) }
    var tagDialog by remember { mutableStateOf<Set<Long>?>(null) }
    var deleteFilesDialog by remember { mutableStateOf<Set<Long>?>(null) }
    // The "Online" tab: every title added with the heart in the catalog.
    var onlineTab by rememberSaveable { mutableStateOf(false) }
    val inOnline = online != null && onlineTab
    val onlineShownTab = online != null && (onlineCount > 0 || onlineTab)
    val onlineTotal = onlineCount.coerceAtLeast(0)

    val openDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.import(it) }
    val openFolderBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(viewModel::importFolderAsBook) }
    val openScanFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(viewModel::addScanFolder) }

    val strImported = stringResource(R.string.library_import_result)
    val strFailed = stringResource(R.string.library_import_failed)
    val strUndo = stringResource(CoreR.string.undo)
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is LibraryEvent.Imported -> {
                    val msg = if (e.added == 0 && e.duplicates == 0 && e.failed > 0) {
                        strFailed.format(e.firstError?.let { errorMessage(context, it) } ?: "")
                    } else {
                        strImported.format(e.added, e.duplicates, e.failed)
                    }
                    scope.launch { snackbar.showSnackbar(msg, withDismissAction = true) }
                }
                is LibraryEvent.Deleted -> scope.launch {
                    val r = snackbar.showSnackbar(
                        resources.getQuantityString(R.plurals.library_deleted, e.ids.size, e.ids.size),
                        actionLabel = strUndo, withDismissAction = true, duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) viewModel.undoDelete(e.ids) else viewModel.commitDelete(e.ids)
                }
                is LibraryEvent.Message -> scope.launch { snackbar.showSnackbar(resources.getString(e.text)) }
                is LibraryEvent.OpenBook -> onOpenBook(e.book)
                is LibraryEvent.ReadChapter -> onReadChapter(e.chapterId)
                is LibraryEvent.OpenTitle -> onOpenTitle(e.itemId)
            }
        }
    }

    val selecting = selection.isNotEmpty()
    val filtered = search.isNotBlank() || activeFilters > 0
    val booksLoading = items.loadState.refresh is LoadState.Loading && items.itemCount == 0
    val noBooksAtAll = total == 0
    val onlineLoaded = onlineCount >= 0
    // What the grid shows: online titles as tiles (in a user tab, or in "All" while searching or filtering) and books.
    val inTab = filter.collectionId != null
    val onlineTiles = if (inOnline || selecting) emptyList() else if (inTab || filtered) onlineShown else emptyList()
    val shelf = !inOnline && !inTab && !filtered && !selecting && onlineShown.isNotEmpty()
    val bookCount = if (showBooks) items.itemCount else 0
    // "All" without books: a compact card offers the import instead of the full-screen empty state.
    val noBooksCard = !inOnline && !inTab && !filtered && noBooksAtAll && onlineCount > 0
    val fullEmpty = !inOnline && !inTab && !filtered && noBooksAtAll && onlineCount == 0
    val showFab = !selecting && !inOnline && !noBooksCard && !fullEmpty
    // The actions for the selected books take the place of the floating navigation bar.
    HideNavBarWhile(selecting)

    Scaffold(
        contentWindowInsets = topLevelContentInsets(),
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                when {
                    selecting -> TopAppBar(
                        title = { Text(pluralStringResource(R.plurals.library_selected, selection.size, selection.size)) },
                        navigationIcon = {
                            IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Default.Close, stringResource(CoreR.string.close)) }
                        },
                        actions = {
                            // One selected book: shortcut to its details (the only way to reach them from the grid besides a second long press).
                            val single = selection.singleOrNull()?.let { id -> items.itemSnapshotList.items.firstOrNull { it.id == id } }
                            if (single != null) {
                                IconButton(onClick = { viewModel.clearSelection(); onOpenDetails(single) }) {
                                    Icon(Icons.Outlined.Info, stringResource(R.string.library_details))
                                }
                            }
                            IconButton(onClick = { viewModel.openBatchEdit(selection) }) { Icon(Icons.Default.Edit, stringResource(R.string.library_edit_metadata)) }
                            IconButton(onClick = { haptic.tick(); viewModel.selectAll() }) { Icon(Icons.Default.SelectAll, stringResource(CoreR.string.select_all)) }
                        },
                    )
                    searching -> Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Dimens.s, vertical = Dimens.xs)) {
                        SearchField(
                            query = search,
                            onQueryChange = viewModel::setSearch,
                            placeholder = stringResource(R.string.library_search_hint),
                            modifier = Modifier.weight(1f),
                            trailing = {
                                IconButton(onClick = { searching = false; viewModel.setSearch("") }) {
                                    Icon(Icons.Default.Close, stringResource(CoreR.string.close))
                                }
                            },
                        )
                    }
                    else -> LibraryHeader(
                        subtitle = listOfNotNull(
                            total.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.library_count, it, it) },
                            onlineTotal.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.library_online_short, it, it) },
                        ).joinToString(" · ").ifEmpty { null },
                        onSearch = { searching = true },
                        downloads = downloads,
                        onDownloads = onOpenDownloads,
                    ) {
                        Box {
                            CircleIconButton(Icons.Default.MoreVert, stringResource(CoreR.string.more), onClick = { showMenu = true })
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_new_collection)) },
                                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                    onClick = { showMenu = false; newTab = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_rescan)) },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                    onClick = { showMenu = false; viewModel.rescan() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreR.string.incognito_mode)) },
                                    leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) },
                                    trailingIcon = { Switch(checked = incognito, onCheckedChange = null, modifier = Modifier.scale(0.8f)) },
                                    onClick = { showMenu = false; viewModel.setIncognito(!incognito) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreR.string.settings)) },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                                    onClick = { showMenu = false; onOpenSettings() },
                                )
                            }
                        }
                    }
                }
                val selectedKey = when {
                    inOnline -> TAB_ONLINE
                    else -> filter.collectionId?.toString() ?: TAB_ALL
                }
                PillTabRow(
                    tabs = buildList {
                        add(PillTab(TAB_ALL, stringResource(R.string.library_all)))
                        if (onlineShownTab) add(PillTab(TAB_ONLINE, stringResource(R.string.library_online), onlineTotal, Icons.Outlined.Public))
                        collections.forEach { add(PillTab(it.id.toString(), it.name, it.count)) }
                    },
                    selected = selectedKey,
                    onSelect = { key ->
                        when (key) {
                            TAB_ALL -> { onlineTab = false; viewModel.setCollection(null) }
                            TAB_ONLINE -> { viewModel.clearSelection(); onlineTab = true }
                            else -> { onlineTab = false; viewModel.setCollection(key.toLong()) }
                        }
                    },
                    onLongPress = { key -> key.toLongOrNull()?.let { id -> tabActions = collections.firstOrNull { it.id == id } } },
                    addLabel = stringResource(R.string.library_new_collection),
                    onAdd = { newTab = true },
                    modifier = Modifier.padding(top = Dimens.xs, bottom = Dimens.xs),
                )
                if (!inOnline) {
                    LibraryToolbar(
                        activeFilters = activeFilters,
                        sort = sort,
                        ascending = ascending,
                        grid = grid,
                        onFilters = { showFilters = true },
                        onSort = viewModel::setSort,
                        onToggleGrid = { viewModel.setGrid(!grid) },
                    )
                    if (activeFilters > 0) ActiveFilters(filter, types, viewModel)
                }
                if (progress.running) {
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.library_importing, progress.done, progress.total, progress.current.orEmpty()),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
                        maxLines = 1,
                    )
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(selecting) {
                BottomAppBar {
                    BulkAction(Icons.Default.DoneAll, stringResource(R.string.library_mark_read)) { viewModel.setStatus(selection, ReadingStatus.FINISHED) }
                    BulkAction(Icons.Outlined.AutoStories, stringResource(R.string.library_mark_unread)) { viewModel.setStatus(selection, ReadingStatus.NEW) }
                    BulkAction(Icons.Default.Bookmarks, stringResource(R.string.library_to_collection)) { viewModel.pickTabsForBooks(selection) }
                    BulkAction(Icons.AutoMirrored.Filled.Label, stringResource(R.string.library_add_tag)) { tagDialog = selection }
                    BulkAction(Icons.Default.Delete, stringResource(CoreR.string.delete)) { viewModel.requestDelete(selection) }
                    BulkAction(Icons.Default.DeleteForever, stringResource(R.string.library_delete_files)) { deleteFilesDialog = selection }
                }
            }
        },
        floatingActionButton = {
            if (showFab) {
                YomuFab(text = stringResource(R.string.library_import), icon = Icons.Default.Add, onClick = { showImport = true }, expanded = !grid)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        TopLevelBody(padding, Modifier.fillMaxSize()) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { if (!selecting) viewModel.refresh() }, modifier = Modifier.fillMaxSize()) {
            when {
                inOnline -> online.pane(snackbar, search)
                (booksLoading && showBooks) || total < 0 || !onlineLoaded -> if (grid) SkeletonGrid() else SkeletonList()
                fullEmpty -> EmptyState(
                    title = stringResource(R.string.library_empty_title),
                    message = stringResource(R.string.library_empty_text),
                    icon = Icons.Outlined.AutoStories,
                    actionLabel = stringResource(R.string.library_import),
                    onAction = { showImport = true },
                )
                bookCount == 0 && onlineTiles.isEmpty() && !shelf && !noBooksCard -> when {
                    filtered -> EmptyState(
                        title = stringResource(R.string.library_nothing_found),
                        icon = Icons.Default.Search,
                        actionLabel = if (activeFilters > 0) stringResource(R.string.library_clear_filters) else null,
                        onAction = if (activeFilters > 0) viewModel::clearFilters else null,
                    )
                    else -> EmptyState(
                        title = stringResource(R.string.library_tab_empty_title),
                        message = stringResource(R.string.library_tab_empty_text),
                        icon = Icons.Outlined.Bookmarks,
                    )
                }
                else -> {
                    val continueRow = showContinue && !inTab && !filtered && !selecting && continueItems.isNotEmpty()
                    LibraryGrid(
                        books = items,
                        showBooks = showBooks,
                        grid = grid,
                        columns = columns,
                        selection = selection,
                        onlineTiles = onlineTiles,
                        header = {
                            if (continueRow) ContinueReading(continueItems, onOpenBook, viewModel::continueOnline)
                            if (shelf) OnlineShelf(onlineShown, onlineTotal, onOpenTitle, onLongPress = { onlineMenu = it }, onShowAll = { onlineTab = true })
                            if (noBooksCard) NoBooksCard(onImport = { showImport = true })
                        },
                        sectionLabels = onlineTiles.isNotEmpty() && bookCount > 0,
                        onOpenTitle = onOpenTitle,
                        onOnlineLongPress = { onlineMenu = it },
                        onClick = { book -> if (selecting) { haptic.tick(); viewModel.toggleSelect(book.id) } else onOpenBook(book) },
                        onLongClick = { book -> if (selecting) onOpenDetails(book) else { haptic.perform(); viewModel.toggleSelect(book.id) } },
                        onDetails = onOpenDetails,
                        sort = sort,
                        swipeEnabled = !selecting,
                        onToggleRead = { book ->
                            haptic.confirm()
                            viewModel.setStatus(setOf(book.id), if (book.status == ReadingStatus.FINISHED) ReadingStatus.NEW else ReadingStatus.FINISHED)
                        },
                        onRemove = { book -> haptic.confirm(); viewModel.requestDelete(setOf(book.id)) },
                    )
                }
            }
            if (bookCount > 0 && !selecting && !inOnline) {
                OneTimeHint(
                    "library_select", stringResource(R.string.hint_library_select),
                    Modifier.align(Alignment.BottomCenter).padding(bottom = navBarBottomPadding(64.dp)).widthIn(max = 480.dp),
                )
            }
            }
        }
    }

    if (showImport) {
        YomuSheet(onDismiss = { showImport = false }) {
            SheetTitle(stringResource(R.string.library_import))
            SheetAction(Icons.Default.UploadFile, stringResource(R.string.library_import_files)) {
                showImport = false
                openDocs.launch(arrayOf("*/*"))
            }
            SheetAction(Icons.Default.FolderOpen, stringResource(R.string.library_import_folder_book)) {
                showImport = false
                openFolderBook.launch(null)
            }
            SheetAction(Icons.Default.LibraryAdd, stringResource(R.string.library_add_scan_folder)) {
                showImport = false
                openScanFolder.launch(null)
            }
            SheetAction(Icons.Default.Headphones, stringResource(R.string.library_audio_link)) {
                showImport = false
                showAudioLink = true
            }
            HorizontalDivider()
            SheetAction(Icons.Default.TravelExplore, stringResource(R.string.library_find_online)) {
                showImport = false
                onFindBooksOnline()
            }
        }
    }
    if (showAudioLink) {
        TextInputDialog(
            title = stringResource(R.string.library_audio_link),
            label = stringResource(R.string.library_audio_link_hint),
            onConfirm = { viewModel.addAudioLink(it) },
            onDismiss = { showAudioLink = false },
        )
    }
    audioSearch?.let { state ->
        AudioSearchSheet(
            state = state,
            onPick = viewModel::addYouTubeResult,
            onRetry = { viewModel.searchYouTube(state.query) },
            onDismiss = viewModel::closeAudioSearch,
        )
    }
    if (showFilters) {
        FiltersSheet(viewModel, onDismiss = { showFilters = false })
    }
    tabPick?.let { pick ->
        val subtitle = pick.title ?: pluralStringResource(R.plurals.library_selected, pick.size, pick.size)
        TabsPickerSheet(
            title = stringResource(R.string.library_tabs_title),
            subtitle = subtitle,
            tabs = collections.map { TabOption(it.id, it.name, tabState(pick.counts[it.id] ?: 0, pick.size)) },
            onToggle = viewModel::togglePickTab,
            onCreate = viewModel::createTabInPick,
            onDismiss = viewModel::closeTabPick,
        )
    }
    if (newTab) {
        TextInputDialog(
            title = stringResource(R.string.library_new_collection),
            label = stringResource(R.string.library_collection_name),
            onConfirm = { viewModel.createCollection(it) },
            onDismiss = { newTab = false },
        )
    }
    tabActions?.let { t ->
        val index = collections.indexOfFirst { it.id == t.id }
        TabActionsSheet(
            name = t.name,
            canMoveLeft = index > 0,
            canMoveRight = index in 0 until collections.lastIndex,
            onRename = { viewModel.renameCollection(t.id, it) },
            onMove = { viewModel.moveCollection(t.id, it) },
            onDelete = { viewModel.deleteCollection(t.id) },
            onDismiss = { tabActions = null },
        )
    }
    onlineMenu?.let { e ->
        YomuSheet(onDismiss = { onlineMenu = null }) {
            SheetTitle(e.title)
            SheetAction(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.library_open_title)) { onlineMenu = null; onOpenTitle(e.id) }
            SheetAction(Icons.Default.Bookmarks, stringResource(R.string.library_tabs_action)) { viewModel.pickTabsForOnline(e.id, e.title); onlineMenu = null }
            SheetAction(Icons.Default.Delete, stringResource(R.string.library_remove_online), destructive = true) {
                viewModel.removeOnline(e.id); onlineMenu = null
            }
        }
    }
    val batch by viewModel.batch.collectAsStateWithLifecycle()
    batch?.let { list ->
        BatchMetadataDialog(books = list, onDismiss = viewModel::closeBatchEdit, onApply = viewModel::applyBatchEdit)
    }
    tagDialog?.let { ids ->
        TextInputDialog(
            title = stringResource(R.string.library_add_tag),
            label = stringResource(R.string.library_tag),
            onConfirm = { viewModel.addTag(ids, it) },
            onDismiss = { tagDialog = null },
        )
    }
    deleteFilesDialog?.let { ids ->
        ConfirmDialog(
            title = stringResource(R.string.library_delete_files),
            text = stringResource(R.string.library_delete_files_text),
            confirmLabel = stringResource(CoreR.string.delete),
            onConfirm = { haptic.confirm(); viewModel.commitDelete(ids, deleteFiles = true) },
            onDismiss = { deleteFilesDialog = null },
        )
    }
}

/** Large title with the counts and round actions (downloads while there are any, search, menu). */
@Composable
private fun LibraryHeader(subtitle: String?, onSearch: () -> Unit, downloads: DownloadSummary, onDownloads: () -> Unit, menu: @Composable () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = Dimens.xl, end = Dimens.l, top = Dimens.m, bottom = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (downloads.visible) {
                DownloadsButton(downloads, onDownloads)
                Spacer(Modifier.width(Dimens.s))
            }
            CircleIconButton(Icons.Default.Search, stringResource(CoreR.string.search), onClick = onSearch)
            Spacer(Modifier.width(Dimens.s))
            menu()
        }
        // A thin strip stays visible under the title while data is flowing, also when the button is out of sight.
        if (downloads.active) {
            val fraction = downloads.fraction?.let { animatedFraction(it) }
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(2.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
        }
    }
}

@Composable
private fun animatedFraction(target: Float): Float = androidx.compose.animation.core.animateFloatAsState(target, label = "downloads").value

/** Progress ring around a download icon with the number of items left; red with a warning icon when only failures remain. */
@Composable
private fun DownloadsButton(s: DownloadSummary, onClick: () -> Unit) {
    val failedOnly = !s.active && s.remaining == 0
    val count = if (s.remaining > 0) s.remaining else s.failed
    val label = if (s.remaining > 0) pluralStringResource(R.plurals.library_downloads_left, s.remaining, s.remaining) else stringResource(R.string.library_downloads_failed)
    val container = if (failedOnly) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (failedOnly) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Box {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier.minimumInteractiveComponentSize().size(Dimens.touch - 4.dp).semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (s.active) {
                    val ring = Modifier.size(36.dp)
                    val fraction = s.fraction?.let { animatedFraction(it) }
                    if (fraction != null) {
                        CircularProgressIndicator(progress = { fraction }, modifier = ring, strokeWidth = 3.dp, trackColor = content.copy(alpha = 0.18f))
                    } else {
                        CircularProgressIndicator(modifier = ring, strokeWidth = 3.dp, trackColor = content.copy(alpha = 0.18f))
                    }
                }
                Icon(if (failedOnly) Icons.Outlined.ErrorOutline else Icons.Default.Download, null, Modifier.size(20.dp))
            }
        }
        Badge(Modifier.align(Alignment.TopEnd)) { Text(if (count > 99) "99+" else count.toString()) }
    }
}

/** Filter (with the number of active filters), sort and the grid/list switch. */
@Composable
private fun LibraryToolbar(
    activeFilters: Int,
    sort: LibrarySort,
    ascending: Boolean,
    grid: Boolean,
    onFilters: () -> Unit,
    onSort: (LibrarySort) -> Unit,
    onToggleGrid: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.l, top = Dimens.xs, bottom = Dimens.s),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarPill(Icons.Default.FilterList, stringResource(R.string.library_filter), onClick = onFilters, badge = activeFilters)
        Box {
            ToolbarPill(if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, stringResource(sort.label()), onClick = { sortMenu = true })
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                LibrarySort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(stringResource(s.label())) },
                        onClick = { onSort(s); sortMenu = false },
                        trailingIcon = {
                            if (s == sort) Icon(if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        CircleIconButton(
            if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
            stringResource(if (grid) R.string.library_view_list else R.string.library_view_grid),
            onClick = onToggleGrid,
        )
    }
}

/** The active filters as removable chips, and "Reset all". */
@Composable
private fun ActiveFilters(filter: LibraryFilter, types: Set<FormatGroup>, viewModel: LibraryViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = Dimens.l, end = Dimens.l, bottom = Dimens.s),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatGroup.entries.filter { it in types }.forEach { g -> RemovableChip(stringResource(g.label()), onRemove = { viewModel.toggleGroup(g) }) }
        ReadingStatus.entries.filter { it in filter.statuses }.forEach { s -> RemovableChip(stringResource(s.label()), onRemove = { viewModel.toggleStatus(s) }) }
        filter.tag?.let { RemovableChip("#$it", onRemove = { viewModel.setTag(null) }) }
        filter.language?.let { RemovableChip(it.uppercase(), onRemove = { viewModel.setLanguage(null) }) }
        TextButton(onClick = viewModel::clearFilters) { Text(stringResource(R.string.library_reset_all)) }
    }
}

/** The latest book or online title in progress as a large card, the others in a row under it; both resume reading. */
@Composable
private fun ContinueReading(items: List<ContinueItem>, onOpenBook: (Book) -> Unit, onContinueTitle: (Long) -> Unit) {
    val first = items.firstOrNull() ?: return
    val open: (ContinueItem) -> Unit = { item ->
        when (item) {
            is ContinueItem.BookItem -> onOpenBook(item.book)
            is ContinueItem.OnlineItem -> onContinueTitle(item.entry.id)
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = Dimens.s)) {
        Surface(
            onClick = { open(first) },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.s, vertical = Dimens.xs),
        ) {
            Row(Modifier.padding(Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.l), verticalAlignment = Alignment.CenterVertically) {
                val (title, cover) = when (first) {
                    is ContinueItem.BookItem -> first.book.title to first.book.cover()
                    is ContinueItem.OnlineItem -> first.entry.title to first.entry.coverUrl
                }
                CoverImage(cover, title, Modifier.width(76.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    Text(stringResource(R.string.library_continue), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val (detail, fraction) = when (first) {
                        is ContinueItem.BookItem -> first.book.authorsText.ifBlank { null } to first.book.progress
                        is ContinueItem.OnlineItem -> pluralStringResource(R.plurals.library_unread_chapters, first.entry.unread, first.entry.unread) to first.entry.readFraction()
                    }
                    detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            strokeCap = StrokeCap.Round,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                            modifier = Modifier.weight(1f).height(6.dp),
                        )
                        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { open(first) }, modifier = Modifier.padding(top = Dimens.xs).heightIn(min = 40.dp)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.details_continue), Modifier.padding(start = Dimens.xs))
                    }
                }
            }
        }
        val rest = items.drop(1)
        if (rest.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.s),
                horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                modifier = Modifier.padding(top = Dimens.s),
            ) {
                items(rest, key = { if (it is ContinueItem.BookItem) "cb${it.book.id}" else "co${(it as ContinueItem.OnlineItem).entry.id}" }) { item ->
                    when (item) {
                        is ContinueItem.BookItem -> BookGridItem(
                            title = item.book.title,
                            subtitle = "${(item.book.progress * 100).toInt()}%",
                            cover = item.book.cover(),
                            progress = item.book.progress,
                            modifier = Modifier.width(Dimens.coverWidth - 14.dp),
                            onClick = { onOpenBook(item.book) },
                        )
                        is ContinueItem.OnlineItem -> BookGridItem(
                            title = item.entry.title,
                            subtitle = pluralStringResource(R.plurals.library_unread_chapters, item.entry.unread, item.entry.unread),
                            cover = item.entry.coverUrl,
                            progress = item.entry.readFraction(),
                            modifier = Modifier.width(Dimens.coverWidth - 14.dp),
                            onClick = { onContinueTitle(item.entry.id) },
                        )
                    }
                }
            }
        }
    }
}

/** A row of the online titles in "All" ("From the catalog · 12 · All →"). */
@Composable
private fun OnlineShelf(entries: List<CatalogEntry>, count: Int, onOpen: (Long) -> Unit, onLongPress: (CatalogEntry) -> Unit, onShowAll: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = Dimens.s)) {
        SectionLabel(stringResource(R.string.library_section_online), count, Icons.Outlined.Public) {
            TextButton(onClick = onShowAll) {
                Text(stringResource(R.string.library_all))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = Dimens.xs).size(18.dp))
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Dimens.s), horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            items(entries.take(SHELF_MAX), key = { "o${it.id}" }) { e ->
                OnlineTile(e, Modifier.width(Dimens.coverWidth), onOpen, onLongPress)
            }
        }
    }
}

@Composable
private fun OnlineTile(e: CatalogEntry, modifier: Modifier, onOpen: (Long) -> Unit, onLongPress: (CatalogEntry) -> Unit) {
    BookGridItem(
        title = e.title,
        subtitle = e.author,
        cover = e.coverUrl,
        progress = 0f,
        badge = if (e.unread > 0) e.unread.toString() else null,
        modifier = modifier,
        onClick = { onOpen(e.id) },
        onLongClick = { onLongPress(e) },
    )
}

@Composable
private fun SectionLabel(text: String, count: Int?, icon: ImageVector?, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = Dimens.l, end = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
    ) {
        icon?.let { Icon(it, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
        Text(text, style = MaterialTheme.typography.titleMedium)
        Text(count?.toString().orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** "No books from the device yet": a compact card with the import, under the online titles. */
@Composable
private fun NoBooksCard(onImport: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.s, vertical = Dimens.s),
    ) {
        Row(Modifier.padding(Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.l), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AutoStories, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.library_no_books_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.library_no_books_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onImport, modifier = Modifier.padding(top = Dimens.s).heightIn(min = 40.dp)) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.library_import), Modifier.padding(start = Dimens.xs))
                }
            }
        }
    }
}

@Composable
private fun BulkAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    IconButton(onClick = { haptic.confirm(); onClick() }, modifier = Modifier.semantics { contentDescription = label }) { Icon(icon, label) }
}

private fun LibrarySort.label(): Int = when (this) {
    LibrarySort.TITLE -> R.string.sort_title
    LibrarySort.AUTHOR -> R.string.sort_author
    LibrarySort.ADDED -> R.string.sort_added
    LibrarySort.LAST_READ -> R.string.sort_last_read
    LibrarySort.PROGRESS -> R.string.sort_progress
}

private fun FormatGroup.label(): Int = when (this) {
    FormatGroup.BOOKS -> R.string.format_books
    FormatGroup.PDF -> R.string.format_pdf
    FormatGroup.COMICS -> R.string.format_comics
    FormatGroup.AUDIO -> R.string.format_audio
    FormatGroup.ONLINE -> R.string.format_online
}

/** Books (paged) after the [header] and the online titles of the tab, as a grid or a list. */
@Composable
private fun LibraryGrid(
    books: LazyPagingItems<Book>,
    showBooks: Boolean,
    grid: Boolean,
    columns: Int,
    selection: Set<Long>,
    onlineTiles: List<CatalogEntry>,
    header: @Composable () -> Unit,
    sectionLabels: Boolean,
    onOpenTitle: (Long) -> Unit,
    onOnlineLongPress: (CatalogEntry) -> Unit,
    onClick: (Book) -> Unit,
    onLongClick: (Book) -> Unit,
    onDetails: (Book) -> Unit,
    sort: LibrarySort,
    swipeEnabled: Boolean,
    onToggleRead: (Book) -> Unit,
    onRemove: (Book) -> Unit,
) {
    val bookCount = if (showBooks) books.itemCount else 0
    val leading = 1 + (if (onlineTiles.isNotEmpty()) onlineTiles.size + (if (sectionLabels) 1 else 0) else 0) + (if (sectionLabels && bookCount > 0) 1 else 0)
    val dateFormat = rememberMonthFormat()
    val label: (Int) -> String? = { i -> books.peek((i - leading).coerceIn(0, (bookCount - 1).coerceAtLeast(0)))?.fastScrollLabel(sort, dateFormat) }
    val scrollDescription = stringResource(R.string.library_fast_scroll)
    if (grid) {
        val gridState = rememberLazyGridState()
        Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = if (columns <= 0) GridCells.Adaptive(Dimens.gridMin) else GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = Dimens.s, end = Dimens.s, bottom = navBarBottomPadding(96.dp)),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { Column { header() } }
            if (onlineTiles.isNotEmpty()) {
                if (sectionLabels) {
                    item(key = "online-label", span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel(stringResource(R.string.library_section_online), onlineTiles.size, Icons.Outlined.Public)
                    }
                }
                items(onlineTiles.size, key = { "o${onlineTiles[it].id}" }, contentType = { "online" }) { i ->
                    OnlineTile(onlineTiles[i], motionItem(), onOpenTitle, onOnlineLongPress)
                }
            }
            if (sectionLabels && bookCount > 0) {
                item(key = "books-label", span = { GridItemSpan(maxLineSpan) }) {
                    SectionLabel(stringResource(R.string.library_section_books), null, Icons.Outlined.AutoStories)
                }
            }
            items(bookCount, key = books.itemKey { it.id }, contentType = { "book" }) { index ->
                val book = books[index] ?: return@items
                BookGridItem(
                    title = book.title,
                    subtitle = book.authorsText,
                    cover = book.cover(),
                    progress = book.progress,
                    badge = book.format.name.takeIf { it != "EPUB" },
                    selected = book.id in selection,
                    finished = book.status == ReadingStatus.FINISHED,
                    coverModifier = Modifier.sharedCover("book-${book.id}"),
                    onClick = { onClick(book) },
                    onLongClick = { onLongClick(book) },
                    modifier = motionItem(),
                )
            }
        }
        FastScroller(gridState.asFastScrollTarget(), scrollDescription, Modifier.align(Alignment.CenterEnd).padding(bottom = navBarBottomPadding(96.dp)), label)
        }
    } else {
        val listState = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = navBarBottomPadding(96.dp)), modifier = Modifier.fillMaxSize()) {
            item(key = "header", contentType = "header") { Column { header() } }
            if (onlineTiles.isNotEmpty()) {
                if (sectionLabels) {
                    item(key = "online-label") { SectionLabel(stringResource(R.string.library_section_online), onlineTiles.size, Icons.Outlined.Public) }
                }
                items(onlineTiles, key = { "o${it.id}" }, contentType = { "online" }) { e ->
                    BookListItem(
                        title = e.title,
                        subtitle = e.author,
                        cover = e.coverUrl,
                        progress = e.readFraction().takeIf { e.lastReadAt > 0 } ?: 0f,
                        meta = if (e.unread > 0) pluralStringResource(R.plurals.library_unread_chapters, e.unread, e.unread) else null,
                        onClick = { onOpenTitle(e.id) },
                        onLongClick = { onOnlineLongPress(e) },
                        modifier = motionItem(),
                        trailing = { Icon(Icons.Outlined.Public, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Dimens.s)) },
                    )
                }
            }
            if (sectionLabels && bookCount > 0) {
                item(key = "books-label") { SectionLabel(stringResource(R.string.library_section_books), null, Icons.Outlined.AutoStories) }
            }
            items(bookCount, key = books.itemKey { it.id }, contentType = { "book" }) { index ->
                val book = books[index] ?: return@items
                val finished = book.status == ReadingStatus.FINISHED
                SwipeActionsBox(
                    modifier = motionItem(),
                    enabled = swipeEnabled,
                    start = SwipeAction.neutral(
                        if (finished) Icons.Outlined.AutoStories else Icons.Default.DoneAll,
                        stringResource(if (finished) R.string.library_mark_unread else R.string.library_mark_read),
                    ) { onToggleRead(book) },
                    end = SwipeAction.destructive(stringResource(R.string.library_remove_book)) { onRemove(book) },
                ) {
                BookListItem(
                    title = book.title,
                    subtitle = book.authorsText,
                    cover = book.cover(),
                    progress = book.progress,
                    meta = listOfNotNull(book.format.name, book.series, book.language?.uppercase()).joinToString(" · "),
                    selected = book.id in selection,
                    coverModifier = Modifier.sharedCover("book-${book.id}"),
                    onClick = { onClick(book) },
                    onLongClick = { onLongClick(book) },
                    trailing = {
                        IconButton(onClick = { onDetails(book) }) {
                            Icon(if (book.id in selection) Icons.Default.Check else Icons.Outlined.Info, stringResource(R.string.library_details))
                        }
                    },
                )
                }
            }
        }
        FastScroller(listState.asFastScrollTarget(), scrollDescription, Modifier.align(Alignment.CenterEnd).padding(bottom = navBarBottomPadding(96.dp)), label)
        }
    }
}

/** Type, status, tags, language, sort and view of the library in one sheet. */
@Composable
private fun FiltersSheet(viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val languages by viewModel.languages.collectAsStateWithLifecycle()
    val filter by viewModel.filterState.collectAsStateWithLifecycle()
    val types by viewModel.typeFilter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val ascending by viewModel.ascending.collectAsStateWithLifecycle()
    val grid by viewModel.grid.collectAsStateWithLifecycle()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val active by viewModel.activeFilters.collectAsStateWithLifecycle()
    YomuSheet(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(end = Dimens.l), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SheetTitle(stringResource(R.string.library_filter)) }
            if (active > 0) TextButton(onClick = viewModel::clearFilters) { Text(stringResource(R.string.library_reset)) }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            FilterSection(stringResource(R.string.library_type)) {
                FormatGroup.entries.forEach { g -> Choice(stringResource(g.label()), g in types) { viewModel.toggleGroup(g) } }
            }
            FilterSection(stringResource(R.string.library_status)) {
                ReadingStatus.entries.forEach { s -> Choice(stringResource(s.label()), s in filter.statuses) { viewModel.toggleStatus(s) } }
            }
            if (tags.isNotEmpty()) {
                FilterSection(stringResource(R.string.library_tags)) {
                    tags.forEach { t -> Choice("#$t", filter.tag == t) { viewModel.setTag(if (filter.tag == t) null else t) } }
                }
            }
            if (languages.isNotEmpty()) {
                FilterSection(stringResource(R.string.library_languages)) {
                    languages.forEach { l -> Choice(l.uppercase(), filter.language == l) { viewModel.setLanguage(if (filter.language == l) null else l) } }
                }
            }
            FilterSection(stringResource(R.string.library_sort)) {
                LibrarySort.entries.forEach { s ->
                    FilterChip(
                        selected = s == sort,
                        onClick = { viewModel.setSort(s) },
                        label = { Text(stringResource(s.label())) },
                        trailingIcon = if (s == sort) {
                            { Icon(if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        shape = CircleShape,
                    )
                }
            }
            Text(
                stringResource(R.string.library_view),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.xl, end = Dimens.xl, top = Dimens.m, bottom = Dimens.s),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.xl)) {
                SegmentedButton(selected = grid, onClick = { viewModel.setGrid(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.library_view_grid))
                }
                SegmentedButton(selected = !grid, onClick = { viewModel.setGrid(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(R.string.library_view_list))
                }
            }
            if (grid) {
                FilterSection(stringResource(R.string.library_columns)) {
                    (0..5).forEach { n ->
                        val value = if (n == 0) 0 else n + 1
                        Choice(if (n == 0) stringResource(R.string.library_columns_auto) else "$value", columns == value) { viewModel.setColumns(value) }
                    }
                }
            }
        }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.xl, vertical = Dimens.m).heightIn(min = Dimens.touch)) {
            Text(stringResource(CoreR.string.done))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Dimens.xl, end = Dimens.xl, top = Dimens.m, bottom = Dimens.xs),
    )
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.xl),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
    ) { content() }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
        } else {
            null
        },
        shape = CircleShape,
    )
}

/** Titles on the shelf; the rest are one tap away ("All"). */
private const val SHELF_MAX = 30

@Composable
private fun rememberMonthFormat(): java.text.DateFormat {
    val locale = java.util.Locale.getDefault()
    return remember(locale) { java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMM"), locale) }
}

/** The bubble text of the fast scroller: what the books are ordered by. */
private fun Book.fastScrollLabel(sort: LibrarySort, months: java.text.DateFormat): String = when (sort) {
    LibrarySort.TITLE -> initialOf(title)
    LibrarySort.AUTHOR -> initialOf(authors.firstOrNull().orEmpty())
    LibrarySort.ADDED -> months.format(addedAt)
    LibrarySort.LAST_READ -> lastReadAt?.let { months.format(it) } ?: "—"
    LibrarySort.PROGRESS -> "${(progress * 100).toInt()}%"
}

private fun initialOf(text: String): String {
    val c = text.trim().firstOrNull() ?: return "#"
    return if (c.isLetter()) c.uppercaseChar().toString() else "#"
}
