package app.yomu.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.Card
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import app.yomu.data.download.DownloadRepository
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

class DownloadsViewModel(private val repo: DownloadRepository, settings: AppSettings) : ViewModel() {
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
    fun clearFinished() = viewModelScope.launch(ScreenErrors) { repo.clearFinished() }
    fun clearCache() = viewModelScope.launch(ScreenErrors) {
        withContext(Dispatchers.IO) { repo.clearCache() }
        refreshUsage()
    }
}

val downloadsModule = module {
    viewModel { DownloadsViewModel(get(), get()) }
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
    var confirmClearCache by remember { mutableStateOf(false) }
    val s = sections
    LaunchedEffect(s?.finished?.size) { viewModel.refreshUsage() }

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
        // Always the first item, also while the sizes are being counted: a card inserted above the queue later would
        // keep the list scrolled to the queue and leave the card half hidden above it.
        item(key = "usage") { UsageCard(usage, onClearCache = { confirmClearCache = true }) }
        if (s == null) return@LazyColumn
        if (s.isEmpty) {
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
    val retrying = item.status == DownloadStatus.QUEUED && item.attempts > 0 && !item.error.isNullOrBlank()
    val detail = when {
        item.status == DownloadStatus.FAILED -> item.error ?: stringResource(R.string.downloads_failed)
        // Paused by the app (the storage is full) rather than by the user: the reason stays visible.
        item.status == DownloadStatus.PAUSED && !item.error.isNullOrBlank() -> stringResource(R.string.downloads_paused) + " · " + item.error
        retrying -> stringResource(R.string.downloads_retrying, item.attempts, maxRetries.coerceAtLeast(item.attempts), item.error.orEmpty())
        item.status == DownloadStatus.QUEUED && !item.error.isNullOrBlank() -> item.error.orEmpty()
        item.pagesTotal > 0 -> stringResource(R.string.downloads_pages, item.pagesDone, item.pagesTotal)
        item.bytesTotal > 0 -> "${Files.humanSize(item.bytesDownloaded)} / ${Files.humanSize(item.bytesTotal)}"
        item.bytesDownloaded > 0 -> Files.humanSize(item.bytesDownloaded)
        item.status == DownloadStatus.PAUSED -> stringResource(R.string.downloads_paused)
        item.status == DownloadStatus.CANCELLED -> stringResource(R.string.downloads_cancelled)
        item.status == DownloadStatus.DONE -> stringResource(R.string.downloads_done)
        else -> stringResource(R.string.downloads_waiting)
    }
    val warn = item.status == DownloadStatus.FAILED || retrying
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
                Text(
                    detail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.status == DownloadStatus.RUNNING) {
                    if (item.progress > 0f) {
                        LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth().padding(top = Dimens.xs))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Dimens.xs))
                    }
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
