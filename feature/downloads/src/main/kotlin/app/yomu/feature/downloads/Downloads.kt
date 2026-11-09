package app.yomu.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.Files
import app.yomu.core.common.ScreenErrors
import app.yomu.core.datastore.AppSettings
import app.yomu.core.datastore.stateIn
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.PreferenceHeader
import app.yomu.core.ui.components.SwipeAction
import app.yomu.core.ui.components.SwipeActionsBox
import app.yomu.core.ui.components.motionItem
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.audio.AudiobookDownloader
import app.yomu.data.download.AudioDownload
import app.yomu.data.download.DownloadOverview
import app.yomu.data.download.DownloadRepository
import app.yomu.data.download.DownloadSummary
import app.yomu.data.download.NetworkWait
import app.yomu.data.download.StorageUsage
import app.yomu.domain.model.DownloadItem
import app.yomu.domain.model.DownloadKind
import app.yomu.domain.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import app.yomu.core.ui.R as CoreR

/** Waiting downloads of one title (or a single book download), in queue order. */
data class QueueGroup(val entryId: Long?, val items: List<DownloadItem>)

/** Download queue split into the sections shown on screen. */
data class DownloadSections(
    val running: List<DownloadItem> = emptyList(),
    /** Waiting and paused downloads in the order they will run (see [DownloadRepository.moveToTop]). */
    val queued: List<DownloadItem> = emptyList(),
    val failed: List<DownloadItem> = emptyList(),
    val finished: List<DownloadItem> = emptyList(),
) {
    val isEmpty: Boolean get() = running.isEmpty() && queued.isEmpty() && failed.isEmpty() && finished.isEmpty()
    val hasActive: Boolean get() = running.isNotEmpty() || queued.any { it.status == DownloadStatus.QUEUED }
    val hasPaused: Boolean get() = queued.any { it.status == DownloadStatus.PAUSED } || failed.isNotEmpty()
    val hasPending: Boolean get() = running.isNotEmpty() || queued.isNotEmpty() || failed.isNotEmpty()

    /** [queued] grouped by title; a title's group stays where its first chapter is in the queue. */
    val queueGroups: List<QueueGroup> get() = groups(queued)

    companion object {
        fun of(list: List<DownloadItem>) = DownloadSections(
            running = list.filter { it.status == DownloadStatus.RUNNING },
            // The list comes in queue order (priority, then time added): kept as is.
            queued = list.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED },
            failed = list.filter { it.status == DownloadStatus.FAILED },
            finished = list.filter { it.status == DownloadStatus.DONE || it.status == DownloadStatus.CANCELLED }.sortedByDescending { it.updatedAt },
        )

        fun groups(items: List<DownloadItem>): List<QueueGroup> {
            val out = LinkedHashMap<String, MutableList<DownloadItem>>()
            items.forEach { d -> out.getOrPut(d.entryId?.let { "e$it" } ?: "d${d.id}") { mutableListOf() } += d }
            return out.values.map { QueueGroup(it.first().entryId, it) }
        }

        /** Row label inside a title group: "Title — Chapter 5" becomes "Chapter 5". */
        fun shortTitle(item: DownloadItem, entryTitle: String?): String =
            entryTitle?.takeIf { it.isNotEmpty() }?.let { item.title.removePrefix("$it — ").ifBlank { null } } ?: item.title
    }
}

class DownloadsViewModel(
    private val repo: DownloadRepository,
    private val settings: AppSettings,
    overview: DownloadOverview,
    private val audioDownloader: AudiobookDownloader,
) : ViewModel() {
    private val titles = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** Titles of the catalog entries in the queue, by entry id ("" when the entry is gone). */
    val entryTitles: StateFlow<Map<Long, String>> = titles
    val sections: StateFlow<DownloadSections?> = repo.observe()
        .map { list ->
            loadTitles(list)
            DownloadSections.of(list)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val usage = MutableStateFlow<StorageUsage?>(null)
    val maxRetries: StateFlow<Int> = settings.downloadRetries.stateIn(viewModelScope)
    val summary: StateFlow<DownloadSummary> = overview.summary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadSummary())
    val networkWait: StateFlow<NetworkWait> = overview.networkWait.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkWait.NONE)
    val audio: StateFlow<List<AudioDownload>> = overview.audioQueue.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { refreshUsage() }

    private suspend fun loadTitles(list: List<DownloadItem>) {
        val known = titles.value
        val missing = list.mapNotNullTo(HashSet()) { it.entryId }.filterNot { it in known }
        if (missing.isEmpty()) return
        val found = runCatching { repo.entryTitles(missing) }.getOrDefault(emptyMap())
        titles.update { it + missing.associateWith { id -> found[id].orEmpty() } }
    }

    fun refreshUsage() = viewModelScope.launch(ScreenErrors) { usage.value = withContext(Dispatchers.IO) { repo.storageUsage() } }
    fun pause(items: List<DownloadItem>) = viewModelScope.launch(ScreenErrors) { repo.pause(items.map { it.id }) }
    fun resume(items: List<DownloadItem>) = viewModelScope.launch(ScreenErrors) { repo.resume(items.map { it.id }) }
    fun cancel(items: List<DownloadItem>) = viewModelScope.launch(ScreenErrors) { repo.cancel(items.map { it.id }) }
    fun moveToTop(items: List<DownloadItem>) = viewModelScope.launch(ScreenErrors) { repo.moveToTop(items.map { it.id }) }
    fun remove(item: DownloadItem) = viewModelScope.launch(ScreenErrors) { repo.remove(listOf(item.id)) }
    fun pauseAll() = viewModelScope.launch(ScreenErrors) { repo.pauseAll() }
    fun resumeAll() = viewModelScope.launch(ScreenErrors) { repo.resumeAll() }
    fun retryFailed() = viewModelScope.launch(ScreenErrors) { repo.retryFailed() }
    fun clearQueue() = viewModelScope.launch(ScreenErrors) { repo.clearQueue() }
    fun clearFinished() = viewModelScope.launch(ScreenErrors) {
        repo.clearFinished()
        audioDownloader.dismissFinished()
    }
    fun retryAudio(bookId: Long) = viewModelScope.launch(ScreenErrors) { audioDownloader.start(bookId, settings.downloadWifiOnly.get()) }
    fun cancelAudio(bookId: Long) = audioDownloader.cancel(bookId)
    fun clearCache() = viewModelScope.launch(ScreenErrors) {
        withContext(Dispatchers.IO) { repo.clearCache() }
        refreshUsage()
    }
}

val downloadsModule = module {
    viewModel { DownloadsViewModel(get(), get(), get(), get()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) } },
                actions = { DownloadsActions(viewModel) },
            )
        },
    ) { padding ->
        DownloadsContent(viewModel, Modifier.padding(padding).fillMaxSize())
    }
}

/** Top-bar actions of the downloads (pause or resume all, retry, clear): on their screen and in the profile's tab. */
@Composable
fun DownloadsActions(viewModel: DownloadsViewModel) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    var showMenu by remember { mutableStateOf(false) }
    var confirmClearQueue by remember { mutableStateOf(false) }
    val s = sections ?: return
    if (s.hasActive) {
        IconButton(onClick = viewModel::pauseAll) { Icon(Icons.Filled.Pause, stringResource(R.string.downloads_pause_all)) }
    } else if (s.hasPaused) {
        IconButton(onClick = viewModel::resumeAll) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.downloads_resume_all)) }
    }
    if (s.hasPending || s.finished.isNotEmpty()) {
        Box {
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, stringResource(CoreR.string.more)) }
            DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                if (s.failed.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.downloads_retry_failed)) },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                        onClick = { showMenu = false; viewModel.retryFailed() },
                    )
                }
                if (s.finished.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.downloads_clear_finished)) },
                        leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) },
                        onClick = { showMenu = false; viewModel.clearFinished() },
                    )
                }
                if (s.hasPending) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.downloads_clear_queue)) },
                        leadingIcon = { Icon(Icons.Filled.ClearAll, null) },
                        onClick = { showMenu = false; confirmClearQueue = true },
                    )
                }
            }
        }
    }
    if (confirmClearQueue) {
        ConfirmDialog(
            title = stringResource(R.string.downloads_clear_queue),
            text = stringResource(R.string.downloads_clear_queue_text),
            confirmLabel = stringResource(CoreR.string.clear),
            onConfirm = viewModel::clearQueue,
            onDismiss = { confirmClearQueue = false },
        )
    }
}

/**
 * The storage card and the download queue: running, queued (grouped by title), failed and finished downloads. The bottom
 * padding leaves room for the floating navigation bar when it is shown in the profile.
 */
@Composable
fun DownloadsContent(viewModel: DownloadsViewModel, modifier: Modifier = Modifier) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val titles by viewModel.entryTitles.collectAsStateWithLifecycle()
    val maxRetries by viewModel.maxRetries.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val wait by viewModel.networkWait.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    var confirmClearCache by remember { mutableStateOf(false) }
    val s = sections
    LaunchedEffect(s?.finished?.size) { viewModel.refreshUsage() }

    CompositionLocalProvider(LocalNetworkWait provides wait) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
        // Always the first item, also while the sizes are being counted: a card inserted above the queue later would
        // keep the list scrolled to the queue and leave the card half hidden above it.
        item(key = "usage") {
            Column {
                QueueSummary(summary, wait, viewModel)
                UsageCard(usage, onClearCache = { confirmClearCache = true })
            }
        }
        if (s == null) return@LazyColumn
        if (s.isEmpty && audio.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.downloads_empty),
                    message = stringResource(R.string.downloads_empty_hint),
                    icon = Icons.Outlined.CloudDownload,
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.xl),
                )
            }
        }
        section(R.string.downloads_running, s.running, viewModel, titles, maxRetries)
        audioSection(audio, viewModel)
        if (s.queued.isNotEmpty()) {
            item(key = "h-queued") { SectionHeader("${stringResource(R.string.downloads_queued)} (${s.queued.size})") }
            s.queueGroups.forEach { group ->
                val title = group.entryId?.let { titles[it] }?.takeIf { it.isNotEmpty() }
                if (title != null && group.items.size > 1) {
                    item(key = "g-${group.entryId}") { GroupHeader(title, group.items, viewModel) }
                    items(group.items, key = { it.id }) { DownloadRow(it, viewModel, DownloadSections.shortTitle(it, title), maxRetries, motionItem().padding(start = Dimens.l)) }
                } else {
                    items(group.items, key = { it.id }) { DownloadRow(it, viewModel, it.title, maxRetries, motionItem()) }
                }
            }
        }
        section(R.string.downloads_failed, s.failed, viewModel, titles, maxRetries, actionLabel = R.string.downloads_retry_all, onAction = viewModel::retryFailed)
        section(R.string.downloads_finished, s.finished, viewModel, titles, maxRetries)
    }
    }

    if (confirmClearCache) {
        ConfirmDialog(
            title = stringResource(R.string.downloads_clear_cache),
            text = stringResource(R.string.downloads_clear_cache_text),
            confirmLabel = stringResource(CoreR.string.clear),
            onConfirm = viewModel::clearCache,
            onDismiss = { confirmClearCache = false },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: Int,
    list: List<DownloadItem>,
    vm: DownloadsViewModel,
    titles: Map<Long, String>,
    maxRetries: Int,
    actionLabel: Int? = null,
    onAction: (() -> Unit)? = null,
) {
    if (list.isEmpty()) return
    item(key = "h-$title") {
        SectionHeader("${stringResource(title)} (${list.size})", action = if (actionLabel != null && onAction != null) stringResource(actionLabel) to onAction else null)
    }
    items(list, key = { it.id }) { d -> DownloadRow(d, vm, d.title, maxRetries, motionItem()) }
}

@Composable
private fun SectionHeader(text: String, action: Pair<String, () -> Unit>? = null) {
    Row(Modifier.fillMaxWidth().padding(end = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { PreferenceHeader(text) }
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
    }
}

/** Header of the waiting chapters of one title: move them to the top of the queue, pause, resume or cancel them. */
@Composable
private fun GroupHeader(title: String, items: List<DownloadItem>, vm: DownloadsViewModel) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.xs, top = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        // The count stays visible when a long title is cut.
        Row(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(" · ${items.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = { vm.moveToTop(items) }) { Icon(Icons.Filled.VerticalAlignTop, stringResource(R.string.downloads_move_to_top)) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(CoreR.string.more)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                if (items.any { it.status == DownloadStatus.QUEUED }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.downloads_pause)) }, leadingIcon = { Icon(Icons.Filled.Pause, null) }, onClick = {
                        menu = false; vm.pause(items.filter { it.status == DownloadStatus.QUEUED })
                    })
                }
                if (items.any { it.status == DownloadStatus.PAUSED }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.downloads_resume)) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, onClick = {
                        menu = false; vm.resume(items.filter { it.status == DownloadStatus.PAUSED })
                    })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.downloads_cancel)) }, leadingIcon = { Icon(Icons.Filled.Close, null) }, onClick = {
                    menu = false; vm.cancel(items)
                })
            }
        }
    }
}

/** Storage taken by the app; [u] is null while the sizes are being counted ("…" in place of the numbers). */
@Composable
private fun UsageCard(u: StorageUsage?, onClearCache: () -> Unit) {
    fun size(bytes: Long?) = bytes?.let(Files::humanSize) ?: "…"
    Card(Modifier.fillMaxWidth().padding(Dimens.l)) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            Text(stringResource(R.string.storage_total, size(u?.total)), style = MaterialTheme.typography.titleMedium)
            UsageLine(R.string.storage_books, size(u?.books))
            UsageLine(R.string.storage_chapters, size(u?.chapters))
            UsageLine(R.string.storage_converted, size(u?.converted))
            UsageLine(R.string.storage_covers, size(u?.covers))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.storage_cache) + ": " + size(u?.cache),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearCache, enabled = u != null && u.cache > 0) { Text(stringResource(R.string.downloads_clear_cache)) }
            }
        }
    }
}

@Composable
private fun UsageLine(label: Int, size: String) {
    Text(stringResource(label) + ": " + size, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DownloadRow(item: DownloadItem, vm: DownloadsViewModel, label: String, maxRetries: Int, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val wait = LocalNetworkWait.current
    val hasError = !item.error.isNullOrBlank()
    // A task sleeping before its next attempt is still RUNNING in the database, a requeued one is QUEUED.
    val retrying = hasError && item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.QUEUED && item.attempts > 0 && hasError
    val attempt = if (item.status == DownloadStatus.RUNNING) item.attempts + 1 else item.attempts
    val percent = if (item.progress > 0f) (item.progress * 100).toInt().coerceIn(0, 100) else null
    val state: RowState = when {
        item.status == DownloadStatus.FAILED -> RowState(Icons.Outlined.ErrorOutline, stringResource(R.string.downloads_failed), true)
        item.status == DownloadStatus.PAUSED -> RowState(Icons.Filled.Pause, stringResource(R.string.downloads_paused), hasError)
        retrying -> RowState(Icons.Filled.Sync, stringResource(R.string.downloads_retrying, attempt, maxRetries.coerceAtLeast(attempt), item.error.orEmpty()), true)
        item.status == DownloadStatus.QUEUED && wait == NetworkWait.OFFLINE -> RowState(Icons.Outlined.CloudOff, stringResource(R.string.downloads_state_waiting_network))
        item.status == DownloadStatus.QUEUED && wait == NetworkWait.WIFI_ONLY -> RowState(Icons.Outlined.Wifi, stringResource(R.string.downloads_state_waiting_wifi))
        item.status == DownloadStatus.QUEUED -> RowState(Icons.Outlined.Schedule, stringResource(R.string.downloads_state_queued))
        item.status == DownloadStatus.RUNNING ->
            RowState(Icons.Filled.Download, listOfNotNull(stringResource(R.string.downloads_state_downloading), percent?.let { stringResource(R.string.downloads_percent, it) }).joinToString(" · "), accent = true)
        item.status == DownloadStatus.DONE -> RowState(Icons.Outlined.CheckCircle, stringResource(R.string.downloads_done))
        else -> RowState(Icons.Outlined.Cancel, stringResource(R.string.downloads_cancelled))
    }
    val detail = when {
        item.status == DownloadStatus.FAILED || item.status == DownloadStatus.PAUSED && hasError -> item.error
        item.pagesTotal > 0 -> stringResource(R.string.downloads_pages_of, item.pagesDone, item.pagesTotal)
        item.bytesTotal > 0 -> "${Files.humanSize(item.bytesDownloaded)} / ${Files.humanSize(item.bytesTotal)}"
        item.bytesDownloaded > 0 -> Files.humanSize(item.bytesDownloaded)
        else -> null
    }
    val failed = item.status == DownloadStatus.FAILED
    val removable = failed || item.status == DownloadStatus.DONE || item.status == DownloadStatus.CANCELLED
    SwipeActionsBox(
        modifier = modifier,
        start = if (failed) SwipeAction.neutral(Icons.Filled.Refresh, stringResource(CoreR.string.retry)) { vm.resume(listOf(item)) } else null,
        end = if (removable) {
            SwipeAction.destructive(stringResource(R.string.downloads_remove)) { vm.remove(item) }
        } else {
            SwipeAction.destructive(stringResource(R.string.downloads_cancel)) { vm.cancel(listOf(item)) }
        },
    ) {
    ListItem(
        headlineContent = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                StateLine(state)
                if (item.status == DownloadStatus.RUNNING && !retrying) {
                    if (item.progress > 0f) {
                        LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth().padding(top = Dimens.xs))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Dimens.xs))
                    }
                }
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (item.status == DownloadStatus.FAILED || item.status == DownloadStatus.PAUSED && hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Dimens.xs),
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                when (item.kind) {
                    DownloadKind.BOOK -> Icons.AutoMirrored.Filled.MenuBook
                    DownloadKind.CHAPTER -> Icons.Outlined.Collections
                    DownloadKind.EXTENSION -> Icons.Filled.Extension
                },
                null,
            )
        },
        trailingContent = {
            Row {
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                        IconButton(onClick = { vm.pause(listOf(item)) }) { Icon(Icons.Filled.Pause, stringResource(R.string.downloads_pause)) }
                    DownloadStatus.PAUSED ->
                        IconButton(onClick = { vm.resume(listOf(item)) }) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.downloads_resume)) }
                    DownloadStatus.FAILED ->
                        IconButton(onClick = { vm.resume(listOf(item)) }) { Icon(Icons.Filled.Refresh, stringResource(CoreR.string.retry)) }
                    else -> Unit
                }
                when (item.status) {
                    DownloadStatus.DONE, DownloadStatus.CANCELLED ->
                        IconButton(onClick = { vm.remove(item) }) { Icon(Icons.Filled.Delete, stringResource(R.string.downloads_remove)) }
                    DownloadStatus.QUEUED, DownloadStatus.PAUSED -> Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(CoreR.string.more)) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.downloads_move_to_top)) },
                                leadingIcon = { Icon(Icons.Filled.VerticalAlignTop, null) },
                                onClick = { menu = false; vm.moveToTop(listOf(item)) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.downloads_cancel)) },
                                leadingIcon = { Icon(Icons.Filled.Close, null) },
                                onClick = { menu = false; vm.cancel(listOf(item)) },
                            )
                        }
                    }
                    else -> IconButton(onClick = { vm.cancel(listOf(item)) }) { Icon(Icons.Filled.Close, stringResource(R.string.downloads_cancel)) }
                }
            }
        },
    )
    }
}

private val LocalNetworkWait = compositionLocalOf { NetworkWait.NONE }

/** Icon and text of a download's state; [warn] paints it as a problem, [accent] as work in progress. */
private data class RowState(val icon: ImageVector, val text: String, val warn: Boolean = false, val accent: Boolean = false)

@Composable
private fun StateLine(state: RowState) {
    val color = when {
        state.warn -> MaterialTheme.colorScheme.error
        state.accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(state.icon, null, Modifier.size(16.dp), tint = color)
        Text(
            state.text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Dimens.xs),
        )
    }
}

/** Overall state of everything that downloads, with the controls that apply to the whole queue. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueSummary(s: DownloadSummary, wait: NetworkWait, vm: DownloadsViewModel) {
    if (!s.visible) return
    var confirmCancel by remember { mutableStateOf(false) }
    val problem = !s.active && s.failed > 0
    Card(
        Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.l, top = Dimens.l),
        colors = CardDefaults.cardColors(containerColor = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            val headline = listOfNotNull(
                s.running.takeIf { it > 0 }?.let { stringResource(R.string.downloads_summary_running, it) },
                s.queued.takeIf { it > 0 }?.let { stringResource(R.string.downloads_summary_queued, it) },
                s.paused.takeIf { it > 0 }?.let { stringResource(R.string.downloads_summary_paused, it) },
                s.failed.takeIf { it > 0 }?.let { stringResource(R.string.downloads_summary_failed, it) },
            ).joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                s.fraction?.let { Text(stringResource(R.string.downloads_percent, (it * 100).toInt()), style = MaterialTheme.typography.titleMedium) }
            }
            if (s.active) {
                val fraction = s.fraction
                when {
                    fraction != null -> LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    wait == NetworkWait.NONE -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (wait != NetworkWait.NONE && s.remaining > 0) {
                val (icon, text) = if (wait == NetworkWait.OFFLINE) Icons.Outlined.CloudOff to R.string.downloads_wait_offline else Icons.Outlined.Wifi to R.string.downloads_wait_wifi
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Dimens.s))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                if (s.active) {
                    TextButton(onClick = vm::pauseAll) { ButtonLabel(Icons.Filled.Pause, R.string.downloads_pause_all) }
                } else if (s.paused > 0) {
                    TextButton(onClick = vm::resumeAll) { ButtonLabel(Icons.Filled.PlayArrow, R.string.downloads_resume_all) }
                }
                if (s.failed > 0) TextButton(onClick = vm::retryFailed) { ButtonLabel(Icons.Filled.Refresh, R.string.downloads_retry_all) }
                TextButton(onClick = { confirmCancel = true }) { ButtonLabel(Icons.Filled.Close, R.string.downloads_cancel_all) }
            }
        }
    }
    if (confirmCancel) {
        ConfirmDialog(
            title = stringResource(R.string.downloads_clear_queue),
            text = stringResource(R.string.downloads_clear_queue_text),
            confirmLabel = stringResource(R.string.downloads_cancel_all),
            onConfirm = vm::clearQueue,
            onDismiss = { confirmCancel = false },
        )
    }
}

@Composable
private fun ButtonLabel(icon: ImageVector, text: Int) {
    Icon(icon, null, Modifier.size(18.dp))
    Text(stringResource(text), modifier = Modifier.padding(start = Dimens.xs))
}

private fun androidx.compose.foundation.lazy.LazyListScope.audioSection(list: List<AudioDownload>, vm: DownloadsViewModel) {
    if (list.isEmpty()) return
    item(key = "h-audio") { SectionHeader("${stringResource(R.string.downloads_audiobooks)} (${list.size})") }
    items(list, key = { "audio-${it.entry.bookId}" }) { AudioRow(it, vm, motionItem()) }
}

@Composable
private fun AudioRow(d: AudioDownload, vm: DownloadsViewModel, modifier: Modifier = Modifier) {
    val p = d.entry.progress
    val wait = LocalNetworkWait.current
    val failed = p.error != null
    val percent = (p.fraction * 100).toInt().coerceIn(0, 100)
    val state = when {
        failed -> RowState(Icons.Outlined.ErrorOutline, stringResource(R.string.downloads_failed), true)
        p.waiting && wait == NetworkWait.OFFLINE -> RowState(Icons.Outlined.CloudOff, stringResource(R.string.downloads_state_waiting_network))
        p.waiting && wait == NetworkWait.WIFI_ONLY -> RowState(Icons.Outlined.Wifi, stringResource(R.string.downloads_state_waiting_wifi))
        p.waiting -> RowState(Icons.Outlined.Schedule, stringResource(R.string.downloads_state_queued))
        else -> RowState(
            Icons.Filled.Download,
            listOfNotNull(stringResource(R.string.downloads_state_downloading), percent.takeIf { it > 0 }?.let { stringResource(R.string.downloads_percent, it) }).joinToString(" · "),
            accent = true,
        )
    }
    ListItem(
        modifier = modifier,
        headlineContent = { Text(d.title.ifBlank { stringResource(R.string.downloads_audiobook_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                StateLine(state)
                if (!failed && !p.waiting) {
                    if (p.fraction > 0f) {
                        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(top = Dimens.xs))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Dimens.xs))
                    }
                }
                val detail = if (failed) p.error?.takeIf { it.isNotBlank() } else if (p.total > 0) stringResource(R.string.downloads_tracks_of, p.track + 1, p.total) else null
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Dimens.xs),
                    )
                }
            }
        },
        leadingContent = { Icon(Icons.Outlined.Headphones, null) },
        trailingContent = {
            if (failed) {
                IconButton(onClick = { vm.retryAudio(d.entry.bookId) }) { Icon(Icons.Filled.Refresh, stringResource(CoreR.string.retry)) }
            } else {
                IconButton(onClick = { vm.cancelAudio(d.entry.bookId) }) { Icon(Icons.Filled.Close, stringResource(R.string.downloads_cancel)) }
            }
        },
    )
}
