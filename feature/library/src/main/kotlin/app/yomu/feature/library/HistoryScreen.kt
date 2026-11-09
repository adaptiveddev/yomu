package app.yomu.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.ScreenErrors
import app.yomu.core.datastore.AppSettings
import app.yomu.core.ui.components.BookListItem
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.SwipeAction
import app.yomu.core.ui.components.SwipeActionsBox
import app.yomu.core.ui.components.motionItem
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.repository.ReadingHistoryItem
import app.yomu.data.repository.ReadingHistoryRepository
import app.yomu.domain.repository.StatsRepository
import app.yomu.domain.stats.StatsCalculator
import app.yomu.domain.stats.StatsPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import app.yomu.core.ui.R as CoreR

data class HistoryDay(val day: LocalDate, val totalMs: Long, val items: List<ReadingHistoryItem>)

data class TodaySummary(val todayMs: Long, val goalMinutes: Int, val streakDays: Int)

class HistoryViewModel(
    private val history: ReadingHistoryRepository,
    stats: StatsRepository,
    settings: AppSettings,
) : ViewModel() {
    val query = MutableStateFlow("")

    /** null while loading. */
    val days: StateFlow<List<HistoryDay>?> = combine(history.observe(), query) { items, q ->
        val needle = q.trim()
        items.filter { needle.isEmpty() || it.title.contains(needle, true) || it.subtitle?.contains(needle, true) == true }
            .groupBy { it.day }
            .map { (day, list) -> HistoryDay(day, list.sumOf { it.durationMs }, list.sortedByDescending { it.readAt }) }
            .sortedByDescending { it.day }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val today: StateFlow<TodaySummary?> = combine(stats.observeSessions(), settings.dailyGoalMinutes.flow) { sessions, goal ->
        if (sessions.isEmpty()) null else {
            val s = StatsCalculator.compute(sessions, StatsPeriod.WEEK, System.currentTimeMillis(), ZoneId.systemDefault())
            TodaySummary(s.todayMs, goal, s.streakDays)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun remove(item: ReadingHistoryItem) = viewModelScope.launch(ScreenErrors) { history.remove(item) }
    fun clear() = viewModelScope.launch(ScreenErrors) { history.clear() }

    /** Chapter to open for an online title (next unread when the last one was finished). */
    suspend fun resumeChapter(item: ReadingHistoryItem): Long? = item.catalogItemId?.let { history.resumeChapter(it, item.chapterId) }
}

/** Reading history of books and online titles, grouped by day (a screen of its own; the profile shows [HistoryPane]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onOpenBook: (Long) -> Unit,
    onBookDetails: (Long) -> Unit,
    onReadChapter: (Long) -> Unit,
    onOpenTitle: (Long) -> Unit,
    onOpenStats: () -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                scrollBehavior = scroll,
                title = { Text(stringResource(R.string.history_title)) },
                actions = {
                    IconButton(onClick = onOpenStats) { Icon(Icons.Outlined.BarChart, stringResource(R.string.history_stats)) }
                    HistoryActions(viewModel, searching, onSearching = { searching = it })
                },
            )
        },
    ) { padding ->
        HistoryPane(
            viewModel = viewModel,
            searching = searching,
            onSearching = { searching = it },
            snackbar = snackbar,
            onOpenBook = onOpenBook,
            onBookDetails = onBookDetails,
            onReadChapter = onReadChapter,
            onOpenTitle = onOpenTitle,
            onOpenStats = onOpenStats,
            modifier = Modifier.padding(padding),
        )
    }
}

/** Search and "Clear history" for the top bar of the history (its own screen or the History tab of the profile). */
@Composable
fun HistoryActions(viewModel: HistoryViewModel, searching: Boolean, onSearching: (Boolean) -> Unit) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    if (searching) {
        IconButton(onClick = { onSearching(false); viewModel.query.value = "" }) { Icon(Icons.Default.Close, stringResource(CoreR.string.close)) }
    } else {
        IconButton(onClick = { onSearching(true) }) { Icon(Icons.Default.Search, stringResource(CoreR.string.search)) }
    }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(CoreR.string.more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_clear_all)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                enabled = !days.isNullOrEmpty(),
                onClick = { menu = false; confirmClear = true },
            )
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.history_clear_all),
            text = stringResource(R.string.history_clear_all_text),
            confirmLabel = stringResource(CoreR.string.clear),
            onConfirm = viewModel::clear,
            onDismiss = { confirmClear = false },
        )
    }
}

/**
 * The history list without a top bar: the search field while [searching], today's goal ([showToday]) and the days.
 * Lists leave room for the floating navigation bar when it is drawn over them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryPane(
    viewModel: HistoryViewModel,
    searching: Boolean,
    onSearching: (Boolean) -> Unit,
    snackbar: SnackbarHostState,
    onOpenBook: (Long) -> Unit,
    onBookDetails: (Long) -> Unit,
    onReadChapter: (Long) -> Unit,
    onOpenTitle: (Long) -> Unit,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
    showToday: Boolean = true,
) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    fun open(item: ReadingHistoryItem) {
        val bookId = item.bookId
        val itemId = item.catalogItemId
        when {
            bookId != null -> onOpenBook(bookId)
            itemId != null -> scope.launch(ScreenErrors) {
                val chapter = viewModel.resumeChapter(item)
                if (chapter != null) onReadChapter(chapter) else onOpenTitle(itemId)
            }
        }
    }

    fun details(item: ReadingHistoryItem) {
        item.bookId?.let(onBookDetails) ?: item.catalogItemId?.let(onOpenTitle)
    }

    Column(modifier.fillMaxSize()) {
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                placeholder = { Text(stringResource(R.string.history_search)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    IconButton(onClick = { onSearching(false); viewModel.query.value = "" }) { Icon(Icons.Default.Close, stringResource(CoreR.string.close)) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
            )
        }
        val list = days
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() && query.isBlank() -> EmptyState(
                title = stringResource(R.string.history_empty_title),
                message = stringResource(R.string.history_empty_text),
                icon = Icons.Outlined.History,
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl)),
            ) {
                if (showToday) {
                    today?.takeIf { query.isBlank() }?.let { t ->
                        item(key = "today") { TodayCard(t, onOpenStats) }
                    }
                }
                if (list.isEmpty()) {
                    item(key = "nothing") {
                        Text(
                            stringResource(R.string.history_nothing_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Dimens.l),
                        )
                    }
                }
                list.forEach { day ->
                    stickyHeader(key = "d:${day.day}") { DayHeader(day) }
                    items(day.items, key = { it.key }) { item ->
                        val remove: () -> Unit = {
                            viewModel.remove(item)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar(resources.getString(R.string.history_removed, item.title))
                            }
                        }
                        SwipeActionsBox(
                            modifier = motionItem(),
                            end = SwipeAction.destructive(stringResource(R.string.history_remove), remove),
                        ) {
                            HistoryRow(item = item, onOpen = { open(item) }, onDetails = { details(item) }, onRemove = remove)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayCard(t: TodaySummary, onOpenStats: () -> Unit) {
    val minutes = (t.todayMs / 60_000).toInt()
    val goal = t.goalMinutes.coerceAtLeast(1)
    ElevatedCard(
        onClick = onOpenStats,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
    ) {
        Row(Modifier.padding(Dimens.l), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { (minutes.toFloat() / goal).coerceIn(0f, 1f) },
                    modifier = Modifier.size(56.dp),
                    strokeWidth = 6.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text("$minutes", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(Dimens.l))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.history_today_goal, minutes, goal), style = MaterialTheme.typography.titleSmall)
                val text = if (minutes >= goal) stringResource(R.string.history_goal_done) else stringResource(R.string.history_goal_left, goal - minutes)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (t.streakDays > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Default.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        Text(
                            pluralStringResource(R.plurals.history_streak, t.streakDays, t.streakDays),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.history_stats), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayHeader(day: HistoryDay) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = Dimens.l, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dayLabel(day.day), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (day.totalMs >= 60_000) {
            Text(formatDuration(day.totalMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryRow(item: ReadingHistoryItem, onOpen: () -> Unit, onDetails: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val time = remember(item.readAt) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(java.time.Instant.ofEpochMilli(item.readAt).atZone(ZoneId.systemDefault())) }
    val meta = buildList {
        add(time)
        if (item.durationMs >= 60_000) add(formatDuration(item.durationMs))
        if (item.bookId != null) {
            if (item.finished) add(stringResource(R.string.history_finished))
            else item.progress?.takeIf { it > 0f }?.let { add("${(it * 100).toInt()}%") }
        } else {
            if (item.chaptersRead > 1) add(pluralStringResource(R.plurals.history_chapters, item.chaptersRead, item.chaptersRead))
            item.sourceName?.let { add(it) }
        }
    }.joinToString(" · ")
    BookListItem(
        title = item.title,
        subtitle = item.subtitle,
        cover = item.cover?.let { c -> if (item.bookId != null) File(c) else c },
        progress = if (item.bookId != null && !item.finished) item.progress ?: 0f else 0f,
        meta = meta,
        onClick = onOpen,
        onLongClick = { menu = true },
        modifier = modifier,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onOpen) { Icon(Icons.Default.PlayArrow, stringResource(R.string.history_continue)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(CoreR.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (item.bookId != null) R.string.history_book_details else R.string.history_open_title)) },
                            leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            onClick = { menu = false; onDetails() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_remove)) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = { menu = false; onRemove() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.history_today)
        today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> {
            val pattern = if (day.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy"
            DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(day).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }
}
