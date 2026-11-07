package app.yomu.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.UiState
import app.yomu.core.datastore.AppSettings
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.CoverImage
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.StateContent
import app.yomu.data.repository.ReadingHistoryRepository
import app.yomu.data.repository.TitleInfo
import app.yomu.domain.repository.StatsRepository
import app.yomu.domain.stats.StatsCalculator
import app.yomu.domain.stats.StatsPeriod
import app.yomu.domain.stats.StatsSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import app.yomu.core.ui.R as CoreR

data class StatsUi(val summary: StatsSummary, val titles: Map<String, TitleInfo>, val goalMinutes: Int, val finished: Int)

class ReadingStatsViewModel(
    private val stats: StatsRepository,
    private val history: ReadingHistoryRepository,
    private val settings: AppSettings,
) : ViewModel() {
    val period = MutableStateFlow(StatsPeriod.WEEK)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<StatsUi>> = combine(stats.observeSessions(), period, settings.dailyGoalMinutes.flow) { s, p, g -> Triple(s, p, g) }
        .mapLatest { (sessions, p, goal) ->
            if (sessions.isEmpty()) return@mapLatest UiState.Empty
            val zone = ZoneId.systemDefault()
            val summary = StatsCalculator.compute(sessions, p, System.currentTimeMillis(), zone)
            val titles = history.describe(summary.topTitles.map { it.key })
            val from = if (p == StatsPeriod.ALL) 0L else summary.from.atStartOfDay(zone).toInstant().toEpochMilli()
            UiState.Data(StatsUi(summary, titles, goal, history.finishedBooksSince(from)))
        }
        // The summary goes over every session ever recorded: not on the main thread (it made the tab stutter).
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun setGoal(minutes: Int) = viewModelScope.launch(ScreenErrors) { settings.dailyGoalMinutes.set(minutes) }
    fun reset() = viewModelScope.launch(ScreenErrors) { stats.resetStats() }
}

@Composable
fun ReadingStatsScreen(viewModel: ReadingStatsViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) } },
                actions = { ReadingStatsActions(viewModel) },
            )
        },
    ) { padding ->
        ReadingStatsPane(viewModel, Modifier.padding(padding))
    }
}

/** The goal and "reset" of the statistics, for the top bar of the screen or of the profile tab that shows them. */
@Composable
fun ReadingStatsActions(viewModel: ReadingStatsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var goalDialog by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }
    IconButton(onClick = { goalDialog = true }) { Icon(Icons.Outlined.Flag, stringResource(R.string.stats_goal)) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(CoreR.string.more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.stats_reset)) },
                leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                onClick = { menu = false; resetDialog = true },
            )
        }
    }
    if (goalDialog) {
        val goal = (state as? UiState.Data)?.value?.goalMinutes ?: 20
        GoalDialog(goal, onDismiss = { goalDialog = false }, onSave = { viewModel.setGoal(it); goalDialog = false })
    }
    if (resetDialog) {
        ConfirmDialog(
            title = stringResource(R.string.stats_reset),
            text = stringResource(R.string.stats_reset_text),
            confirmLabel = stringResource(R.string.stats_reset_confirm),
            onConfirm = viewModel::reset,
            onDismiss = { resetDialog = false },
        )
    }
}

/**
 * The statistics without a top bar: the period, today, the charts and the top titles. Used by the statistics screen
 * and by the Statistics tab of the profile (1.8.0), where it scrolls on under the floating navigation bar.
 */
@Composable
fun ReadingStatsPane(viewModel: ReadingStatsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    StateContent(
        state = state,
        modifier = modifier,
        onRetry = {},
        empty = {
            EmptyState(
                title = stringResource(R.string.stats_empty),
                message = stringResource(R.string.stats_empty_text),
                icon = Icons.Outlined.QueryStats,
                modifier = modifier,
            )
        },
    ) { s -> StatsContent(s, period, { viewModel.period.value = it }, modifier) }
}

@Composable
private fun StatsContent(ui: StatsUi, period: StatsPeriod, onPeriod: (StatsPeriod) -> Unit, modifier: Modifier) {
    val s = ui.summary
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navBarBottomPadding(8.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(StatsPeriod.WEEK to R.string.stats_week, StatsPeriod.MONTH to R.string.stats_month, StatsPeriod.YEAR to R.string.stats_year, StatsPeriod.ALL to R.string.stats_all)
            options.forEachIndexed { i, (p, label) ->
                SegmentedButton(selected = period == p, onClick = { onPeriod(p) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                    Text(stringResource(label), maxLines = 1)
                }
            }
        }

        TodayCard(s, ui.goalMinutes)

        // Hero: time in the period and the change against the previous one.
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.stats_total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(duration(s.totalMs), style = MaterialTheme.typography.displaySmall)
                if (period != StatsPeriod.ALL && s.previousTotalMs > 0) {
                    val change = ((s.totalMs - s.previousTotalMs) * 100 / s.previousTotalMs).toInt()
                    val color = if (change >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    Text(
                        stringResource(if (change >= 0) R.string.stats_more_than_before else R.string.stats_less_than_before, kotlin.math.abs(change)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                    )
                }
                Spacer(Modifier.height(12.dp))
                ActivityChart(s, ui.goalMinutes)
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2) {
            val cell = Modifier.weight(1f)
            StatCard(stringResource(R.string.stats_avg_day), duration(s.avgPerDayMs), cell)
            StatCard(stringResource(R.string.stats_active_days), s.activeDays.toString(), cell)
            StatCard(stringResource(R.string.stats_sessions), s.sessions.toString(), cell)
            StatCard(stringResource(R.string.stats_avg_session), duration(s.avgSessionMs), cell)
            StatCard(stringResource(R.string.stats_pages), s.pages.toString(), cell)
            StatCard(
                stringResource(R.string.stats_speed),
                if (s.chars > 0) stringResource(R.string.stats_cpm, s.charsPerMinute) else stringResource(R.string.stats_pph, s.pagesPerHour),
                cell,
            )
            StatCard(stringResource(R.string.stats_longest), duration(s.longestSessionMs), cell)
            StatCard(stringResource(R.string.stats_finished), ui.finished.toString(), cell)
            s.bestDay?.let { d ->
                StatCard(stringResource(R.string.stats_best_day), duration(s.bestDayMs) + "\n" + shortDate(d), cell)
            }
            StatCard(stringResource(R.string.stats_best), pluralStringResource(R.plurals.stats_days, s.bestStreak, s.bestStreak), cell)
        }

        if (s.topTitles.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.stats_top_titles), style = MaterialTheme.typography.titleMedium)
                    val max = s.topTitles.maxOf { it.ms }.coerceAtLeast(1)
                    s.topTitles.forEach { t ->
                        val info = ui.titles[t.key]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val cover: Any? = info?.cover?.let { c -> if (t.key.startsWith("b:")) File(c) else c }
                            CoverImage(cover, info?.title ?: "?", Modifier.width(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(info?.title ?: stringResource(R.string.stats_deleted_title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(duration(t.ms) + " · " + pluralStringResource(R.plurals.stats_sessions_count, t.sessions, t.sessions),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                LinearProgressIndicator(progress = { t.ms.toFloat() / max }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp))
                            }
                        }
                    }
                    if (s.titlesCount > s.topTitles.size) {
                        Text(pluralStringResource(R.plurals.stats_titles_total, s.titlesCount, s.titlesCount), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.stats_heatmap), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Heatmap(s)
            }
        }

        if (s.totalMs > 0) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.stats_by_hour), style = MaterialTheme.typography.titleMedium)
                    val peak = s.byHour.indices.maxBy { s.byHour[it] }
                    Text(stringResource(R.string.stats_peak_hour, partOfDay(peak)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    SimpleBars(s.byHour, labels = (0..23).map { if (it % 6 == 0) "$it" else "" }, height = 96)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.stats_by_weekday), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    SimpleBars(s.byWeekday, labels = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }, height = 96)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TodayCard(s: StatsSummary, goalMinutes: Int) {
    val minutes = (s.todayMs / 60_000).toInt()
    val goal = goalMinutes.coerceAtLeast(1)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { (minutes.toFloat() / goal).coerceIn(0f, 1f) },
                    modifier = Modifier.size(64.dp),
                    strokeWidth = 7.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text("$minutes", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.stats_today_of_goal, minutes, goal), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (minutes >= goal) stringResource(R.string.stats_goal_reached) else stringResource(R.string.stats_goal_left, goal - minutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Default.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.stats_streak) + ": " + pluralStringResource(R.plurals.stats_days, s.streakDays, s.streakDays),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

/** Bars per day (week/month) or per month (year/all) with the daily goal as a dashed line; tap a bar for its value. */
@Composable
private fun ActivityChart(s: StatsSummary, goalMinutes: Int) {
    val buckets = s.buckets
    if (buckets.isEmpty()) return
    var selected by remember(s) { mutableStateOf<Int?>(null) }
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val goalColor = MaterialTheme.colorScheme.tertiary
    val highlight = MaterialTheme.colorScheme.tertiary
    val daily = buckets.none { it.isMonth }
    val goalMs = goalMinutes * 60_000L
    val max = maxOf(buckets.maxOf { it.ms }, if (daily) goalMs else 0L).coerceAtLeast(60_000L)
    val locale = Locale.getDefault()
    val shown = selected?.let { buckets.getOrNull(it) } ?: buckets.lastOrNull()
    shown?.let { b ->
        val label = if (b.isMonth) b.start.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } + " " + b.start.year
        else shortDate(b.start)
        Text("$label: ${duration(b.ms)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
    val description = buckets.joinToString { (it.ms / 60_000).toString() }
    Canvas(
        Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = description }
            .pointerInput(buckets) {
                detectTapGestures { o ->
                    val w = size.width.toFloat() / buckets.size
                    selected = (o.x / w).toInt().coerceIn(0, buckets.lastIndex)
                }
            },
    ) {
        val gap = if (buckets.size > 20) 2.dp.toPx() else 6.dp.toPx()
        val w = (size.width - gap * (buckets.size - 1)) / buckets.size
        buckets.forEachIndexed { i, b ->
            val x = i * (w + gap)
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 4))
            val h = size.height * b.ms / max
            if (h > 0f) drawRoundRect(if (selected == i) highlight else bar, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 4))
        }
        if (daily && goalMs > 0) {
            val y = size.height - size.height * goalMs / max
            drawLine(goalColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        buckets.forEachIndexed { i, b ->
            val text = when {
                b.isMonth -> if (buckets.size <= 12 || i % 3 == 0) b.start.month.getDisplayName(TextStyle.NARROW_STANDALONE, locale) else ""
                buckets.size <= 7 -> b.start.dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
                else -> if (i % 5 == 0 || i == buckets.lastIndex) b.start.dayOfMonth.toString() else ""
            }
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** GitHub-style calendar of the last weeks: one column per week, Monday on top. */
@Composable
private fun Heatmap(s: StatsSummary) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val full = MaterialTheme.colorScheme.primary
    val today = LocalDate.now()
    val weeks = StatsSummary.HEATMAP_WEEKS
    val max = (s.heatmap.values.maxOrNull() ?: 0L).coerceAtLeast(15 * 60_000L)
    val activeDays = s.heatmap.count { it.value > 0 }
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = activeDays.toString() }) {
        val gap = 3.dp.toPx()
        val cell = minOf((size.width - gap * (weeks - 1)) / weeks, (size.height - gap * 6) / 7)
        val left = (size.width - (cell * weeks + gap * (weeks - 1))) / 2
        for (w in 0 until weeks) {
            for (d in 0 until 7) {
                val day = s.heatmapStart.plusDays((w * 7 + d).toLong())
                if (day.isAfter(today)) continue
                val ms = s.heatmap[day] ?: 0L
                val color = if (ms <= 0) empty else lerp(empty, full, (0.25f + 0.75f * (ms.toFloat() / max)).coerceIn(0f, 1f))
                drawRoundRect(color, Offset(left + w * (cell + gap), d * (cell + gap)), Size(cell, cell), CornerRadius(cell / 4))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.stats_less), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(0f, 0.35f, 0.65f, 1f).forEach { f ->
            Canvas(Modifier.padding(horizontal = 2.dp).size(10.dp)) { drawRoundRect(if (f == 0f) empty else lerp(empty, full, f), cornerRadius = CornerRadius(3f)) }
        }
        Text(stringResource(R.string.stats_more), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SimpleBars(values: List<Long>, labels: List<String>, height: Int) {
    val bar = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val x = i * (w + gap)
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 4))
            val h = size.height * v / max
            if (h > 0f) drawRoundRect(bar, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 4))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun GoalDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by remember { mutableFloatStateOf(current.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_goal)) },
        text = {
            Column {
                Text(stringResource(R.string.stats_goal_minutes, value.toInt()), style = MaterialTheme.typography.headlineSmall)
                Slider(value = value, onValueChange = { value = (it / 5).toInt() * 5f }, valueRange = 5f..180f, steps = 34)
                Text(stringResource(R.string.stats_goal_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.toInt().coerceAtLeast(5)) }) { Text(stringResource(CoreR.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier.widthIn(min = 140.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun partOfDay(hour: Int): String = stringResource(
    when (hour) {
        in 5..11 -> R.string.stats_morning
        in 12..16 -> R.string.stats_afternoon
        in 17..22 -> R.string.stats_evening
        else -> R.string.stats_night
    },
)

private fun shortDate(d: LocalDate): String = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(d)

@Composable
private fun duration(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return if (min >= 60) stringResource(R.string.reader_hours_minutes, min / 60, min % 60) else stringResource(R.string.reader_minutes, min)
}
