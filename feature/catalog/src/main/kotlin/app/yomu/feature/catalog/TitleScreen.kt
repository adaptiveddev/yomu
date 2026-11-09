package app.yomu.feature.catalog

import androidx.compose.material.icons.filled.Bookmarks
import app.yomu.core.ui.components.TabsPickerSheet
import app.yomu.core.ui.components.TabOption
import androidx.compose.ui.state.ToggleableState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ExtensionOff
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.AppError
import app.yomu.core.common.Files
import app.yomu.core.common.Share
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.CoverImage
import app.yomu.core.ui.components.coverTint
import app.yomu.core.ui.components.ErrorState
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.SkeletonList
import app.yomu.core.ui.components.TextInputDialog
import app.yomu.core.ui.components.CoverTheme
import app.yomu.core.ui.components.SwipeAction
import app.yomu.core.ui.components.SwipeActionsBox
import app.yomu.core.ui.components.rememberCoverColor
import app.yomu.core.ui.components.rememberHaptic
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.yomu.core.ui.components.YomuFab
import app.yomu.core.ui.components.YomuSheet
import app.yomu.core.ui.theme.Dimens
import app.yomu.domain.catalog.ChapterDisplay
import app.yomu.domain.catalog.ChapterSortMode
import app.yomu.domain.model.CatalogEntry
import app.yomu.domain.model.ChapterInfo
import app.yomu.domain.model.ContentType
import app.yomu.domain.model.DownloadItem
import app.yomu.domain.model.DownloadStatus
import app.yomu.source.SBookLink
import app.yomu.source.Status
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.util.Date
import app.yomu.core.ui.R as CoreR
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import app.yomu.core.network.TrafficMeter
import app.yomu.core.ui.components.MetaItem
import app.yomu.core.ui.components.MetaLine
import app.yomu.core.ui.components.MotionContent
import app.yomu.core.ui.components.PopIcon
import app.yomu.core.ui.components.animateMotionFloat
import app.yomu.core.ui.components.motionItem
import app.yomu.core.ui.components.motionSpec

/**
 * Title page of a manga, web novel or catalog book: details, chapters, downloads.
 * [inPane]: shown beside a list on a wide window; the back arrow becomes "close" and the side next to the list takes no insets.
 */
@Composable
fun TitleScreen(
    viewModel: TitleViewModel,
    onBack: () -> Unit,
    onReadChapter: (Long) -> Unit,
    onMigrate: (entryId: Long, title: String) -> Unit,
    onOpenWebView: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    inPane: Boolean = false,
    onOpenBook: (Long) -> Unit = {},
) {
    val cover by viewModel.entry.collectAsStateWithLifecycle()
    CoverTheme(rememberCoverColor(cover?.coverUrl)) {
        TitleContent(viewModel, onBack, onReadChapter, onMigrate, onOpenWebView, onOpenDownloads, inPane, onOpenBook)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleContent(
    viewModel: TitleViewModel,
    onBack: () -> Unit,
    onReadChapter: (Long) -> Unit,
    onMigrate: (entryId: Long, title: String) -> Unit,
    onOpenWebView: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    inPane: Boolean,
    onOpenBook: (Long) -> Unit,
) {
    val context = LocalContext.current
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val entryTabs by viewModel.entryTabs.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val links by viewModel.links.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val display by viewModel.display.collectAsStateWithLifecycle()
    val total by viewModel.totalChapters.collectAsStateWithLifecycle()
    val scanlators by viewModel.scanlators.collectAsStateWithLifecycle()
    val sourceName by viewModel.sourceName.collectAsStateWithLifecycle()
    val sourceMissing by viewModel.sourceMissing.collectAsStateWithLifecycle()
    val pending by viewModel.downloadStates.collectAsStateWithLifecycle()
    val stats by viewModel.chapterStats.collectAsStateWithLifecycle()
    val warnMobile by viewModel.warnMobileReading.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptic = rememberHaptic()
    val scope = rememberCoroutineScope()
    val barInsets = if (inPane) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End) else TopAppBarDefaults.windowInsets
    val scaffoldInsets = if (inPane) WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End) else ScaffoldDefaults.contentWindowInsets
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var mobileAsk by remember { mutableStateOf<Long?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showDownloadMenu by remember { mutableStateOf(false) }
    var showSelectMenu by remember { mutableStateOf(false) }
    var showDisplay by remember { mutableStateOf(false) }
    var askCount by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var pickTabs by remember { mutableStateOf(false) }
    var editTabs by remember { mutableStateOf(false) }
    var showTracking by remember { mutableStateOf(false) }
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()

    CatalogEventsEffect(viewModel.events, snackbar, onOpenWebView = onOpenWebView, onOpenBook = onOpenBook)
    val numberWidth = rememberNumberWidth(stats.total)

    val e = entry
    val isBook = e?.type == ContentType.BOOK
    val selecting = selection.isNotEmpty()
    var wasSelecting by rememberSaveable { mutableStateOf(selecting) }
    LaunchedEffect(selecting) {
        if (selecting && !wasSelecting) haptic.perform()
        wasSelecting = selecting
    }
    // "Ask before reading online on mobile data": chapters that are not downloaded would be loaded over it.
    val read: (ChapterInfo) -> Unit = { ch ->
        if (warnMobile && !ch.downloaded && TrafficMeter.isMetered()) mobileAsk = ch.id else onReadChapter(ch.id)
    }

    Scaffold(
        contentWindowInsets = scaffoldInsets,
        topBar = {
            if (selecting) {
                TopAppBar(
                    windowInsets = barInsets,
                    title = { Text(stringResource(R.string.selected_count, selection.size)) },
                    navigationIcon = { IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Filled.Close, stringResource(CoreR.string.close)) } },
                    actions = {
                        IconButton(onClick = viewModel::selectAll) { Icon(Icons.Filled.SelectAll, stringResource(CoreR.string.select_all)) }
                        IconButton(onClick = viewModel::invertSelection) { Icon(Icons.Filled.FlipToBack, stringResource(R.string.select_invert)) }
                        Box {
                            IconButton(onClick = { showSelectMenu = true }) { Icon(Icons.Filled.MoreVert, stringResource(CoreR.string.more)) }
                            DropdownMenu(showSelectMenu, onDismissRequest = { showSelectMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.select_range)) },
                                    leadingIcon = { Icon(Icons.Outlined.UnfoldMore, null) },
                                    enabled = selection.size >= 2,
                                    onClick = { showSelectMenu = false; viewModel.selectRange() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.select_unread)) },
                                    leadingIcon = { Icon(Icons.Filled.RemoveDone, null) },
                                    onClick = { showSelectMenu = false; viewModel.selectUnread() },
                                )
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    windowInsets = barInsets,
                    title = { Text(e?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            if (inPane) Icon(Icons.Filled.Close, stringResource(CoreR.string.close))
                            else Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back))
                        }
                    },
                    actions = {
                        if (e != null) {
                            IconButton(onClick = {
                                haptic.toggle(!e.favorite)
                                if (e.favorite) viewModel.setFavorite(false) else if (tabs.isNotEmpty()) pickTabs = true else viewModel.setFavorite(true)
                            }) {
                                PopIcon(
                                    on = e.favorite,
                                    onIcon = Icons.Filled.Favorite,
                                    offIcon = Icons.Filled.FavoriteBorder,
                                    contentDescription = stringResource(if (e.favorite) R.string.title_remove_from_library else R.string.title_add_to_library),
                                    onTint = MaterialTheme.colorScheme.primary,
                                    offTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (!isBook) {
                            Box {
                                IconButton(onClick = { showDownloadMenu = true }) { Icon(Icons.Filled.Download, stringResource(R.string.download)) }
                                DropdownMenu(showDownloadMenu, onDismissRequest = { showDownloadMenu = false }) {
                                    listOf(1, 5, 10, 25).forEach { n ->
                                        DropdownMenuItem(
                                            text = { Text(pluralStringResource(R.plurals.download_next, n, n)) },
                                            leadingIcon = { Icon(if (n == 1) Icons.Filled.Download else Icons.Outlined.FastForward, null) },
                                            onClick = { showDownloadMenu = false; haptic.confirm(); viewModel.downloadNext(n) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.download_custom)) },
                                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                        onClick = { showDownloadMenu = false; askCount = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.download_unread)) },
                                        leadingIcon = { Icon(Icons.Filled.RemoveDone, null) },
                                        onClick = { showDownloadMenu = false; haptic.confirm(); viewModel.downloadUnread() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.download_all)) },
                                        leadingIcon = { Icon(Icons.Outlined.CloudDownload, null) },
                                        onClick = { showDownloadMenu = false; haptic.confirm(); viewModel.downloadAll() },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.download_delete_read)) },
                                        leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) },
                                        onClick = { showDownloadMenu = false; haptic.confirm(); viewModel.deleteReadDownloads() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.download_delete_all)) },
                                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        onClick = { showDownloadMenu = false; confirmDeleteAll = true },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.catalog_downloads)) },
                                        leadingIcon = { Icon(Icons.Filled.Downloading, null) },
                                        onClick = { showDownloadMenu = false; onOpenDownloads() },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, stringResource(CoreR.string.more)) }
                            DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.title_refresh)) },
                                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                    onClick = { showMenu = false; viewModel.refresh() },
                                )
                                viewModel.webUrl()?.let { url ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(CoreR.string.open_webview)) },
                                        leadingIcon = { Icon(Icons.Filled.Public, null) },
                                        onClick = { showMenu = false; onOpenWebView(url) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(CoreR.string.share)) },
                                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                                        onClick = { showMenu = false; Share.shareText(context, "${e?.title.orEmpty()}\n$url") },
                                    )
                                }
                                if (e?.favorite == true) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tabs_action)) },
                                        leadingIcon = { Icon(Icons.Filled.Bookmarks, null) },
                                        onClick = { showMenu = false; editTabs = true },
                                    )
                                }
                                if (e != null && !isBook) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.migrate)) },
                                        leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                                        onClick = { showMenu = false; onMigrate(e.id, e.title) },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (selecting) {
                val selected = chapters.orEmpty().filter { it.id in selection }
                BottomAppBar {
                    val allRead = selected.all { it.read }
                    IconButton(onClick = { haptic.confirm(); viewModel.setRead(selection, !allRead) }) {
                        Icon(if (allRead) Icons.Filled.RemoveDone else Icons.Filled.DoneAll, stringResource(if (allRead) R.string.mark_unread else R.string.mark_read))
                    }
                    val allBookmarked = selected.all { it.bookmarked }
                    IconButton(onClick = { haptic.confirm(); viewModel.setBookmarked(selection, !allBookmarked) }) {
                        Icon(if (allBookmarked) Icons.Filled.BookmarkBorder else Icons.Filled.Bookmark, stringResource(if (allBookmarked) R.string.unbookmark else R.string.bookmark))
                    }
                    IconButton(onClick = { haptic.confirm(); viewModel.download(selection) }) { Icon(Icons.Filled.Download, stringResource(R.string.download)) }
                    if (selected.any { it.downloaded }) {
                        IconButton(onClick = { haptic.confirm(); viewModel.deleteDownloads(selection.filter { id -> selected.any { it.id == id && it.downloaded } }) }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.delete_download))
                        }
                    }
                    if (selected.size == 1) {
                        TextButton(onClick = { haptic.confirm(); viewModel.markPreviousRead(selected.first()); viewModel.clearSelection() }) {
                            Text(stringResource(R.string.mark_previous_read))
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            val list = chapters
            if (!selecting && !isBook && !list.isNullOrEmpty()) {
                val started = list.any { it.read || it.lastPageRead > 0 }
                YomuFab(
                    text = stringResource(if (started) R.string.title_continue else R.string.title_start),
                    icon = Icons.Filled.PlayArrow,
                    onClick = { viewModel.continueChapter()?.let(read) },
                    expanded = fabExpanded,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (e == null) {
            SkeletonList(Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "header") { TitleHeader(e, sourceName ?: if (sourceMissing) stringResource(R.string.source_not_installed) else null) }
                if (sourceMissing) item(key = "missing") { MissingSourceCard(onMigrate = { onMigrate(e.id, e.title) }) }
                item(key = "actions") {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.l, vertical = Dimens.s),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
                    ) {
                        FilledTonalButton(onClick = {
                            haptic.toggle(!e.favorite)
                            if (e.favorite) viewModel.setFavorite(false) else if (tabs.isNotEmpty()) pickTabs = true else viewModel.setFavorite(true)
                        }) {
                            PopIcon(e.favorite, Icons.Filled.Favorite, Icons.Filled.FavoriteBorder, null, Modifier.size(18.dp))
                            Text(stringResource(if (e.favorite) R.string.in_library else R.string.title_add_to_library), Modifier.padding(start = Dimens.s))
                        }
                        if (!isBook) TrackingButton(tracks.size) { showTracking = true }
                        viewModel.webUrl()?.let { url ->
                            OutlinedButton(onClick = { onOpenWebView(url) }) {
                                Icon(Icons.Filled.Public, null, Modifier.size(18.dp))
                                Text("WebView", Modifier.padding(start = Dimens.s))
                            }
                        }
                    }
                }
                if (!e.description.isNullOrBlank()) item(key = "description") { Description(e.description!!) }
                if (e.genres.isNotEmpty()) item(key = "genres") { Genres(e.genres) }
                item(key = "divider") { HorizontalDivider(Modifier.padding(top = Dimens.s)) }

                loadError?.let { err ->
                    item(key = "error") {
                        val app = AppError.from(err)
                        ErrorState(
                            error = app,
                            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.l),
                            onRetry = viewModel::refresh,
                            secondaryLabel = if (app is AppError.NeedsWebView) stringResource(CoreR.string.open_webview) else null,
                            onSecondary = if (app is AppError.NeedsWebView) ({ onOpenWebView(app.url) }) else null,
                        )
                    }
                }

                if (isBook) {
                    item(key = "links-title") {
                        Text(stringResource(R.string.title_files), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(Dimens.l))
                    }
                    val l = links
                    when {
                        l == null -> item(key = "links-loading") {
                            Box(Modifier.fillMaxWidth().padding(Dimens.l), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                        l.isEmpty() -> item(key = "links-empty") {
                            Text(stringResource(R.string.title_no_files), modifier = Modifier.padding(horizontal = Dimens.l), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> items(l, key = { it.url }) { link -> LinkRow(link) { viewModel.downloadBook(link) } }
                    }
                } else {
                    val list = chapters
                    item(key = "chapters-title") {
                        Row(Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.s, top = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        list == null -> stringResource(R.string.chapters)
                                        list.size != total -> stringResource(R.string.chapters_shown_of, list.size, total)
                                        else -> pluralStringResource(R.plurals.chapters_count, list.size, list.size)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    sortLabel(display),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.updateDisplay { it.copy(descending = !it.descending) } }) {
                                Icon(
                                    if (display.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                                    stringResource(if (display.descending) R.string.sort_newest_first else R.string.sort_oldest_first),
                                )
                            }
                            IconButton(onClick = { showDisplay = true }) {
                                BadgedBox(badge = { if (display.isFiltered) Badge() }) {
                                    Icon(
                                        Icons.Filled.FilterList,
                                        stringResource(R.string.sort_and_filter),
                                        tint = if (display.isFiltered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (stats.total > 0) item(key = "chapters-progress") { ChapterProgress(stats) }
                    when {
                        list == null -> item(key = "chapters-loading") { SkeletonList(Modifier.fillMaxWidth().height(300.dp)) }
                        list.isEmpty() && total > 0 -> item(key = "chapters-filtered") {
                            Column(Modifier.fillMaxWidth().padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                                Text(stringResource(R.string.chapters_filtered_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = viewModel::resetFilters) { Text(stringResource(R.string.chapter_filters_reset)) }
                            }
                        }
                        list.isEmpty() && loadError == null && !refreshing -> item(key = "chapters-empty") {
                            Text(stringResource(R.string.chapters_empty), modifier = Modifier.padding(Dimens.l), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> items(list, key = { it.id }) { ch ->
                            val markLabel = stringResource(if (ch.read) R.string.mark_unread else R.string.mark_read)
                            val deleteLabel = stringResource(R.string.delete_download)
                            val deletedText = stringResource(R.string.title_downloads_deleted)
                            val undoLabel = stringResource(CoreR.string.undo)
                            val scheme = MaterialTheme.colorScheme
                            SwipeActionsBox(
                                modifier = motionItem(),
                                enabled = !selecting,
                                start = SwipeAction.neutral(if (ch.read) Icons.Filled.RemoveDone else Icons.Filled.DoneAll, markLabel) {
                                    haptic.confirm()
                                    viewModel.setRead(listOf(ch.id), !ch.read)
                                },
                                end = when {
                                    ch.downloaded -> SwipeAction(Icons.Filled.Delete, deleteLabel, scheme.errorContainer, scheme.onErrorContainer) {
                                        // Files go only once the snackbar closes without "Undo".
                                        haptic.confirm()
                                        scope.launch {
                                            val r = snackbar.showSnackbar(deletedText, undoLabel, duration = SnackbarDuration.Long)
                                            if (r != SnackbarResult.ActionPerformed) viewModel.deleteDownloads(listOf(ch.id), notify = false)
                                        }
                                    }
                                    pending == null -> SwipeAction.neutral(Icons.Filled.Download, stringResource(R.string.download)) {
                                        haptic.confirm()
                                        viewModel.download(listOf(ch.id))
                                    }
                                    else -> null
                                },
                            ) {
                            ChapterRow(
                                chapter = ch,
                                number = stats.positions[ch.id],
                                numberWidth = numberWidth,
                                pending = pending[ch.id],
                                selected = ch.id in selection,
                                onClick = { if (selecting) viewModel.toggleSelect(ch.id) else read(ch) },
                                onLongClick = { viewModel.toggleSelect(ch.id) },
                                onDownload = { viewModel.download(listOf(ch.id)) },
                                onDelete = { viewModel.deleteDownloads(listOf(ch.id)) },
                            )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickTabs) {
        // Hearting a title when tabs exist: pick its tabs first (none is fine).
        var chosen by remember { mutableStateOf(emptySet<Long>()) }
        TabsPickerSheet(
            title = stringResource(R.string.title_add_to_library),
            subtitle = stringResource(R.string.tabs_pick_hint),
            tabs = tabs.map { TabOption(it.id, it.name, if (it.id in chosen) ToggleableState.On else ToggleableState.Off) },
            onToggle = { id -> chosen = if (id in chosen) chosen - id else chosen + id },
            onCreate = { name -> viewModel.createTab(name) { id -> chosen = chosen + id } },
            onDismiss = { pickTabs = false },
            confirmLabel = stringResource(R.string.tabs_pick_add),
            onConfirm = { viewModel.setFavorite(true, chosen); pickTabs = false },
        )
    }
    if (editTabs) {
        TabsPickerSheet(
            title = stringResource(R.string.tabs_pick_title),
            subtitle = e?.title,
            tabs = tabs.map { TabOption(it.id, it.name, if (it.id in entryTabs) ToggleableState.On else ToggleableState.Off) },
            onToggle = { id -> viewModel.setTab(id, id !in entryTabs) },
            onCreate = { name -> viewModel.createTab(name) { id -> viewModel.setTab(id, true) } },
            onDismiss = { editTabs = false },
        )
    }
    if (showTracking) TrackingSheet(viewModel, onDismiss = { showTracking = false })
    if (showDisplay) {
        ChapterDisplaySheet(
            display = display,
            scanlators = scanlators,
            onSort = viewModel::setSort,
            onChange = { transform -> viewModel.updateDisplay(transform) },
            onToggleScanlator = viewModel::toggleScanlator,
            onReset = viewModel::resetFilters,
            onUseForAll = { viewModel.useDisplayForAll(); showDisplay = false },
            onDismiss = { showDisplay = false },
        )
    }
    if (askCount) {
        TextInputDialog(
            title = stringResource(R.string.download_custom_title),
            label = stringResource(R.string.download_custom_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onConfirm = { text -> text.toIntOrNull()?.takeIf { it > 0 }?.let { viewModel.downloadNext(it.coerceAtMost(MAX_CUSTOM_DOWNLOAD)) } },
            onDismiss = { askCount = false },
        )
    }
    if (confirmDeleteAll) {
        ConfirmDialog(
            title = stringResource(R.string.download_delete_all),
            text = stringResource(R.string.download_delete_all_confirm),
            confirmLabel = stringResource(CoreR.string.delete),
            onConfirm = { haptic.confirm(); viewModel.deleteAllDownloads() },
            onDismiss = { confirmDeleteAll = false },
        )
    }
    mobileAsk?.let { id ->
        AlertDialog(
            onDismissRequest = { mobileAsk = null },
            icon = { Icon(Icons.Outlined.SignalCellularAlt, null) },
            title = { Text(stringResource(R.string.mobile_read_title)) },
            text = { Text(stringResource(R.string.mobile_read_text)) },
            confirmButton = { TextButton(onClick = { mobileAsk = null; onReadChapter(id) }) { Text(stringResource(R.string.mobile_read_anyway)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { mobileAsk = null; viewModel.download(listOf(id)) }) { Text(stringResource(R.string.download)) }
                    TextButton(onClick = { mobileAsk = null }) { Text(stringResource(CoreR.string.cancel)) }
                }
            },
        )
    }
}

/** Width of the chapter number badges: the widest number of the list, so that the titles line up. */
@Composable
private fun rememberNumberWidth(total: Int): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    return remember(total, style, density) {
        val digits = total.coerceAtLeast(1).toString().length.coerceAtLeast(2)
        with(density) { measurer.measure("8".repeat(digits), style).size.width.toDp() } + 14.dp
    }
}

/** "Read 12 of 368 · Downloaded 5" with a progress bar, above the chapter list. */
@Composable
private fun ChapterProgress(stats: ChapterStats) {
    val fraction by animateMotionFloat(if (stats.total > 0) stats.read.toFloat() / stats.total else 0f, label = "read-progress")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs),
        verticalArrangement = Arrangement.spacedBy(Dimens.xs),
    ) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        MetaLine(
            listOfNotNull(
                MetaItem(Icons.Outlined.TaskAlt, stringResource(R.string.chapters_read_of, stats.read, stats.total)),
                stats.downloaded.takeIf { it > 0 }?.let { MetaItem(Icons.Filled.DownloadDone, stringResource(R.string.chapters_downloaded_n, it)) },
            ),
        )
    }
}

private const val MAX_CUSTOM_DOWNLOAD = 1000

@Composable
private fun sortLabel(display: ChapterDisplay): String = stringResource(
    when (display.sort) {
        ChapterSortMode.NUMBER -> R.string.chapter_sort_number
        ChapterSortMode.SOURCE -> R.string.chapter_sort_source
        ChapterSortMode.UPLOAD_DATE -> R.string.chapter_sort_date
    },
)

/** Sort, filters and groups of the chapter list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterDisplaySheet(
    display: ChapterDisplay,
    scanlators: List<String>,
    onSort: (ChapterSortMode) -> Unit,
    onChange: ((ChapterDisplay) -> ChapterDisplay) -> Unit,
    onToggleScanlator: (String) -> Unit,
    onReset: () -> Unit,
    onUseForAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    YomuSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetTitle(stringResource(R.string.sort_and_filter))
            SheetSection(stringResource(R.string.chapter_sort))
            ChapterSortMode.entries.forEach { mode ->
                val selected = display.sort == mode
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.touch).selectable(selected = selected, role = Role.RadioButton, onClick = { onSort(mode) })
                        .padding(horizontal = Dimens.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(sortLabel(display.copy(sort = mode)), Modifier.weight(1f).padding(start = Dimens.m))
                    if (selected) {
                        Icon(
                            if (display.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                            stringResource(if (display.descending) R.string.sort_newest_first else R.string.sort_oldest_first),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            SheetSection(stringResource(R.string.chapter_filter))
            FilterRow(stringResource(R.string.chapter_filter_unread), display.unreadOnly) { v -> onChange { it.copy(unreadOnly = v) } }
            FilterRow(stringResource(R.string.chapter_filter_downloaded), display.downloadedOnly) { v -> onChange { it.copy(downloadedOnly = v) } }
            FilterRow(stringResource(R.string.chapter_filter_bookmarked), display.bookmarkedOnly) { v -> onChange { it.copy(bookmarkedOnly = v) } }
            FilterRow(stringResource(R.string.chapter_filter_duplicates), display.hideDuplicates, stringResource(R.string.chapter_filter_duplicates_sum)) { v ->
                onChange { it.copy(hideDuplicates = v) }
            }
            if (scanlators.size > 1) {
                SheetSection(stringResource(R.string.chapter_groups))
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.xl),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.s),
                ) {
                    scanlators.forEach { name ->
                        FilterChip(
                            selected = name !in display.excludedScanlators,
                            onClick = { onToggleScanlator(name) },
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.chapter_groups_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.xs),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.m),
                horizontalArrangement = Arrangement.spacedBy(Dimens.s, Alignment.End),
            ) {
                TextButton(onClick = onReset, enabled = display.isFiltered) { Text(stringResource(R.string.chapter_filters_reset)) }
                FilledTonalButton(onClick = onUseForAll) { Text(stringResource(R.string.chapter_display_for_all)) }
            }
        }
    }
}

@Composable
private fun SheetSection(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = Dimens.xl, end = Dimens.xl, top = Dimens.m, bottom = Dimens.xs),
    )
}

@Composable
private fun FilterRow(label: String, checked: Boolean, summary: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.touch).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = Dimens.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = Dimens.m)) {
            Text(label)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TitleHeader(e: CatalogEntry, sourceName: String?) {
    val context = LocalContext.current
    val tint = app.yomu.core.ui.components.rememberCoverColor(e.coverUrl, app.yomu.core.ui.components.coverPlaceholderColor(e.title))
    Box(Modifier.fillMaxWidth()) {
        if (e.coverUrl != null && !app.yomu.core.ui.components.LocalHideCovers.current) {
            AsyncImage(
                model = e.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(24.dp).alpha(0.35f),
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)),
            ),
        )
        Box(Modifier.matchParentSize().coverTint(tint))
        Row(Modifier.fillMaxWidth().padding(Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.l)) {
            CoverImage(e.coverUrl, e.title, Modifier.width(112.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                Text(e.title, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                val people = listOfNotNull(e.author, e.artist?.takeIf { it != e.author }).joinToString(", ")
                if (people.isNotBlank()) {
                    MetaLine(listOf(MetaItem(Icons.Outlined.Person, people)), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
                MetaLine(
                    listOfNotNull(
                        statusLabel(context, e.status)?.let { MetaItem(statusIcon(e.status), it) },
                        MetaItem(typeIcon(e.type), contentLabel(context, e.type)),
                    ),
                )
                if (sourceName != null) MetaLine(listOf(MetaItem(Icons.Outlined.Extension, sourceName)), maxLines = 2)
            }
        }
    }
}

/** The title's extension is gone: downloaded chapters still open, new ones need another source. */
@Composable
private fun MissingSourceCard(onMigrate: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs),
    ) {
        Row(
            Modifier.padding(start = Dimens.l, top = Dimens.s, bottom = Dimens.s, end = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            Icon(Icons.Outlined.ExtensionOff, null)
            Text(stringResource(R.string.title_source_missing), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onMigrate) { Text(stringResource(R.string.migrate)) }
        }
    }
}

@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded }.padding(horizontal = Dimens.l, vertical = Dimens.xs),
    ) {
        Text(
            text.trim(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(if (expanded) R.string.show_less else R.string.show_more),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = Dimens.xs),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Genres(genres: List<String>) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        genres.forEach { g -> SuggestionChip(onClick = {}, label = { Text(g) }) }
    }
}

@Composable
private fun LinkRow(link: SBookLink, onDownload: () -> Unit) {
    ListItem(
        headlineContent = { Text(if (link.isAudioStream) stringResource(R.string.title_listen_online) else link.title?.takeIf { it.isNotBlank() } ?: link.formatLabel) },
        supportingContent = { Text(listOfNotNull(if (link.isAudioStream) stringResource(R.string.title_listen_online_hint) else link.formatLabel, link.size?.takeIf { it > 0 }?.let { Files.humanSize(it) }).joinToString(" · ")) },
        leadingContent = { Icon(if (link.isAudioStream) Icons.Outlined.Headphones else Icons.Outlined.FileDownload, null) },
        trailingContent = { FilledTonalButton(onClick = onDownload) { Text(stringResource(if (link.isAudioStream) R.string.title_listen else R.string.download)) } },
        modifier = Modifier.clickable(onClick = onDownload),
    )
}

/** Download state of a chapter row, for animating between its icons. */
private enum class RowDownload { FAILED, RUNNING, QUEUED, DONE, NONE }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: ChapterInfo,
    number: Int?,
    numberWidth: Dp,
    pending: DownloadItem?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = remember(chapter.uploadDate) {
        if (chapter.uploadDate > 0) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(chapter.uploadDate)) else null
    }
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        motionSpec(tween(200)),
        label = "row-container",
    )
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                if (chapter.bookmarked) Icon(Icons.Outlined.Bookmarks, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                if (chapter.isNew) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
                Text(chapter.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            val parts = listOfNotNull(
                date?.let { MetaItem(Icons.Outlined.CalendarToday, it) },
                chapter.scanlator?.takeIf { it.isNotBlank() }?.let { MetaItem(Icons.Outlined.Groups, it) },
                if (!chapter.read && chapter.lastPageRead > 0) MetaItem(Icons.Outlined.AutoStories, stringResource(R.string.chapter_page, chapter.lastPageRead + 1)) else null,
            )
            val failure = pending?.takeIf { it.status == DownloadStatus.FAILED }?.let { it.error?.takeIf { e -> e.isNotBlank() } ?: stringResource(R.string.download_failed) }
            if (parts.isNotEmpty() || failure != null) {
                Column {
                    if (parts.isNotEmpty()) MetaLine(parts, style = MaterialTheme.typography.bodyMedium)
                    if (failure != null) {
                        Text(failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        trailingContent = {
            val state = when {
                pending != null -> when (pending.status) {
                    DownloadStatus.FAILED -> RowDownload.FAILED
                    DownloadStatus.RUNNING -> RowDownload.RUNNING
                    else -> RowDownload.QUEUED
                }
                chapter.downloaded -> RowDownload.DONE
                else -> RowDownload.NONE
            }
            MotionContent(state, label = "chapter-download") { st ->
                when (st) {
                    RowDownload.FAILED -> IconButton(onClick = onDownload) { Icon(Icons.Filled.ErrorOutline, stringResource(CoreR.string.retry), tint = MaterialTheme.colorScheme.error) }
                    RowDownload.RUNNING -> Box(Modifier.size(Dimens.touch), contentAlignment = Alignment.Center) {
                        val progress by animateMotionFloat(pending?.progress ?: 0f, label = "chapter-progress")
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    RowDownload.QUEUED -> Box(Modifier.size(Dimens.touch), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Downloading, stringResource(R.string.download_queued)) }
                    RowDownload.DONE -> IconButton(onClick = onDelete) { Icon(Icons.Filled.DownloadDone, stringResource(R.string.delete_download), tint = MaterialTheme.colorScheme.primary) }
                    RowDownload.NONE -> IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, stringResource(R.string.download)) }
                }
            }
        },
        leadingContent = { ChapterNumber(number, selected, chapter.isNew && !chapter.read, numberWidth) },
        colors = ListItemDefaults.colors(containerColor = container),
        modifier = modifier
            .alpha(if (chapter.read && !selected) 0.55f else 1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

/** Position of the chapter in reading order (1 = the first chapter); a check mark while the row is selected. */
@Composable
private fun ChapterNumber(number: Int?, selected: Boolean, fresh: Boolean, width: Dp) {
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(
        when {
            selected -> scheme.primary
            fresh -> scheme.primaryContainer
            else -> scheme.surfaceVariant
        },
        motionSpec(tween(200)),
        label = "number-bg",
    )
    val content = when {
        selected -> scheme.onPrimary
        fresh -> scheme.onPrimaryContainer
        else -> scheme.onSurfaceVariant
    }
    Box(
        Modifier.widthIn(min = width).heightIn(min = 32.dp).clip(MaterialTheme.shapes.small).background(background).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        MotionContent(selected, label = "number") { sel ->
            if (sel) {
                Icon(Icons.Filled.Done, null, Modifier.size(18.dp), tint = content)
            } else {
                Text(number?.toString() ?: "–", style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
            }
        }
    }
}

/** Icon of a publication status. */
private fun statusIcon(status: Int): ImageVector = when (status) {
    Status.COMPLETED -> Icons.Outlined.TaskAlt
    Status.HIATUS -> Icons.Outlined.PauseCircle
    Status.CANCELLED -> Icons.Outlined.Cancel
    else -> Icons.Outlined.Timelapse
}

/** Icon of a content type. */
private fun typeIcon(type: ContentType): ImageVector = when (type) {
    ContentType.MANGA -> Icons.Outlined.AutoStories
    ContentType.NOVEL -> Icons.AutoMirrored.Outlined.Article
    ContentType.BOOK -> Icons.AutoMirrored.Outlined.MenuBook
}
