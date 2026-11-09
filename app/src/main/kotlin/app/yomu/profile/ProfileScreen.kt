package app.yomu.profile

import android.content.Context
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.SendToMobile
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.R
import app.yomu.core.common.ErrorLog
import app.yomu.core.common.Share
import app.yomu.core.ui.components.BookListItem
import app.yomu.core.ui.components.TopLevelBody
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.components.topLevelContentInsets
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.repository.ReadingHistoryItem
import app.yomu.feature.library.HistoryActions
import app.yomu.feature.library.HistoryPane
import app.yomu.feature.library.HistoryViewModel
import app.yomu.feature.reader.ReadingStatsActions
import app.yomu.feature.reader.ReadingStatsPane
import app.yomu.feature.reader.ReadingStatsViewModel
import app.yomu.feature.settings.SettingsSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import app.yomu.core.ui.R as CoreR

private enum class ProfileTab(@StringRes val label: Int) {
    OVERVIEW(R.string.profile_tab_overview),
    HISTORY(R.string.profile_tab_history),
}

/** Full-screen pages opened from the overview; they replace the tabs until Back. */
private enum class ProfilePage(@StringRes val title: Int) {
    NONE(R.string.profile_title),
    STATS(R.string.profile_tab_stats),
    TRACKING(R.string.profile_tab_tracking),
}

/** Emoji avatars of the profile; "" (the first one in the picker) is the first letter of the name. */
private val AVATARS = listOf("📚", "📖", "🦊", "🐱", "🐼", "🦉", "🐉", "🌸", "🌙", "⭐", "🔥", "🍵", "🎧", "🎮", "👾", "🍙")

/**
 * The "Profile" tab: an overview (summary, milestones, recent reading, entries to the statistics, tracking and downloads)
 * and the reading history, switched by a segmented control. Statistics and tracking open as pages inside the tab.
 */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    historyViewModel: HistoryViewModel,
    statsViewModel: ReadingStatsViewModel,
    onOpenBook: (Long) -> Unit,
    onBookDetails: (Long) -> Unit,
    onReadChapter: (Long) -> Unit,
    onOpenTitle: (Long) -> Unit,
    onOpenSettings: (SettingsSection) -> Unit,
    onOpenDownloads: (() -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableStateOf(ProfileTab.OVERVIEW) }
    var page by rememberSaveable { mutableStateOf(ProfilePage.NONE) }
    // Created when the page is first opened.
    val trackingViewModel: ProfileTrackingViewModel? = if (page == ProfilePage.TRACKING) koinViewModel() else null
    var searching by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(enabled = page != ProfilePage.NONE) { page = ProfilePage.NONE }

    Scaffold(
        contentWindowInsets = topLevelContentInsets(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page.title)) },
                navigationIcon = {
                    if (page != ProfilePage.NONE) {
                        IconButton(onClick = { page = ProfilePage.NONE }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back))
                        }
                    }
                },
                actions = {
                    when (page) {
                        ProfilePage.STATS -> ReadingStatsActions(statsViewModel)
                        ProfilePage.TRACKING -> trackingViewModel?.let { vm -> TrackingActions(vm, onOpenSettings = { onOpenSettings(SettingsSection.TRACKING) }) }
                        ProfilePage.NONE -> when (tab) {
                            ProfileTab.OVERVIEW -> IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, stringResource(R.string.profile_edit)) }
                            ProfileTab.HISTORY -> HistoryActions(historyViewModel, searching, onSearching = { searching = it })
                        }
                    }
                },
            )
        },
    ) { padding ->
        TopLevelBody(padding, Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                when (page) {
                    ProfilePage.STATS -> ReadingStatsPane(statsViewModel, Modifier.fillMaxSize())
                    ProfilePage.TRACKING -> trackingViewModel?.let { vm ->
                        TrackingPane(
                            viewModel = vm,
                            snackbar = snackbar,
                            onOpenTitle = onOpenTitle,
                            onBookDetails = onBookDetails,
                            onOpenSettings = { onOpenSettings(SettingsSection.TRACKING) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    ProfilePage.NONE -> {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
                            ProfileTab.entries.forEachIndexed { i, t ->
                                SegmentedButton(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    shape = SegmentedButtonDefaults.itemShape(i, ProfileTab.entries.size),
                                ) { Text(stringResource(t.label), maxLines = 1) }
                            }
                        }
                        when (tab) {
                            ProfileTab.OVERVIEW -> ProfileOverview(
                                state = state,
                                historyViewModel = historyViewModel,
                                snackbar = snackbar,
                                viewModel = viewModel,
                                onEdit = { editing = true },
                                onOpenStats = { page = ProfilePage.STATS },
                                onOpenHistory = { tab = ProfileTab.HISTORY },
                                onOpenTracking = { page = ProfilePage.TRACKING },
                                onOpenDownloads = onOpenDownloads,
                                onOpenBook = onOpenBook,
                                onReadChapter = onReadChapter,
                                onOpenTitle = onOpenTitle,
                                onOpenBackup = { onOpenSettings(SettingsSection.BACKUP) },
                            )
                            ProfileTab.HISTORY -> HistoryPane(
                                viewModel = historyViewModel,
                                searching = searching,
                                onSearching = { searching = it },
                                snackbar = snackbar,
                                onOpenBook = onOpenBook,
                                onBookDetails = onBookDetails,
                                onReadChapter = onReadChapter,
                                onOpenTitle = onOpenTitle,
                                onOpenStats = { page = ProfilePage.STATS },
                                modifier = Modifier.fillMaxSize(),
                                showToday = false,
                            )
                        }
                    }
                }
            }
        }
    }

    if (editing) {
        EditProfileDialog(
            name = state?.name.orEmpty(),
            avatar = state?.avatar.orEmpty(),
            onDismiss = { editing = false },
            onSave = { name, avatar ->
                viewModel.saveProfile(name, avatar)
                editing = false
            },
        )
    }
}

/** A tappable row that opens a related page: icon, title, one line of explanation and a chevron. */
@Composable
private fun EntryRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Dimens.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Dimens.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProfileOverview(
    state: ProfileUi?,
    historyViewModel: HistoryViewModel,
    snackbar: SnackbarHostState,
    viewModel: ProfileViewModel,
    onEdit: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTracking: () -> Unit,
    onOpenDownloads: (() -> Unit)?,
    onOpenBook: (Long) -> Unit,
    onReadChapter: (Long) -> Unit,
    onOpenTitle: (Long) -> Unit,
    onOpenBackup: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val days by historyViewModel.days.collectAsStateWithLifecycle()
    val query by historyViewModel.query.collectAsStateWithLifecycle()
    val recent = remember(days, query) { if (query.isBlank()) days.orEmpty().asSequence().flatMap { it.items }.take(3).toList() else emptyList() }
    var busy by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val palette = ProfileCard.Palette(colors.primary.toArgb(), colors.tertiary.toArgb(), colors.onPrimary.toArgb())

    /** Prepares a file off the main thread and opens the share sheet for it; failures go to the snackbar. */
    fun shareFile(mime: String, make: suspend (File) -> File) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { make(Share.shareDir(context)) }
                Share.shareFile(context, file, mime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ErrorLog.handled(e)
                snackbar.showSnackbar(errorMessage(context, e))
            } finally {
                busy = false
            }
        }
    }

    fun openRecent(item: ReadingHistoryItem) {
        val bookId = item.bookId
        val itemId = item.catalogItemId
        when {
            bookId != null -> onOpenBook(bookId)
            itemId != null -> scope.launch {
                val chapter = runCatching { historyViewModel.resumeChapter(item) }.getOrNull()
                if (chapter != null) onReadChapter(chapter) else onOpenTitle(itemId)
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Dimens.l, end = Dimens.l, top = Dimens.m, bottom = navBarBottomPadding(Dimens.xl)),
        verticalArrangement = Arrangement.spacedBy(Dimens.m),
    ) {
        item(key = "header") { ProfileHeader(state, onEdit) }
        if (state == null) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(Dimens.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            return@LazyColumn
        }
        item(key = "time") { TimeCard(state, onOpenStats) }
        item(key = "numbers") { NumbersGrid(state) }
        item(key = "milestones") { MilestonesCard(state.milestones) }
        if (state.topTitles.isNotEmpty()) item(key = "top") { TopTitlesCard(state.topTitles, onOpenStats) }
        if (recent.isNotEmpty()) item(key = "recent") { RecentCard(recent, onOpen = ::openRecent, onOpenHistory = onOpenHistory) }
        item(key = "tracking") {
            EntryRow(Icons.Outlined.Sync, stringResource(R.string.profile_tab_tracking), stringResource(R.string.profile_tracking_entry), onOpenTracking)
        }
        if (onOpenDownloads != null) item(key = "downloads") {
            EntryRow(Icons.Outlined.Download, stringResource(R.string.profile_tab_downloads), stringResource(R.string.profile_downloads_entry), onOpenDownloads)
        }
        item(key = "share") {
            ShareSection(
                busy = busy,
                onShareCard = {
                    val content = cardContent(context, state)
                    shareFile("image/png") { dir -> withContext(Dispatchers.Default) { ProfileCard.render(File(dir, "yomu_profile.png"), content, palette) } }
                },
                onShareText = { Share.shareText(context, shareText(context, state)) },
                onShareHistory = { shareFile("text/csv") { dir -> viewModel.exportHistoryCsv(dir) } },
                onShareBackup = { shareFile("application/octet-stream") { dir -> viewModel.createBackup(dir) } },
                onOpenBackup = onOpenBackup,
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------ header

@Composable
private fun ProfileHeader(state: ProfileUi?, onEdit: () -> Unit) {
    val name = state?.name?.ifBlank { null } ?: stringResource(R.string.profile_default_name)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit, role = Role.Button).padding(vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(state?.avatar.orEmpty(), state?.name.orEmpty(), Modifier.size(72.dp))
        Spacer(Modifier.width(Dimens.l))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val since = state?.since
            val subtitle = when {
                state == null -> ""
                since != null -> stringResource(R.string.profile_since, formatDate(since))
                else -> stringResource(R.string.profile_no_reading)
            }
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state?.name.isNullOrBlank() && state != null) {
                Text(stringResource(R.string.profile_set_name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Avatar(avatar: String, name: String, modifier: Modifier = Modifier) {
    val text = avatar.ifBlank { initial(name) }
    Box(
        modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = if (avatar.isBlank()) 30.sp else 34.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun initial(name: String): String =
    name.trim().takeIf { it.isNotEmpty() }?.let { n -> String(Character.toChars(n.codePointAt(0))).uppercase() } ?: "📚"

// ------------------------------------------------------------------------------------------------ numbers

@Composable
private fun TimeCard(state: ProfileUi, onOpenStats: () -> Unit) {
    ElevatedCard(onClick = onOpenStats, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.l)) {
            Text(stringResource(R.string.profile_total_time), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    duration(LocalContext.current, state.totalMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.profile_open_stats), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val minutes = (state.todayMs / 60_000).toInt()
            val goal = state.goalMinutes.coerceAtLeast(1)
            Spacer(Modifier.height(Dimens.s))
            Text(
                stringResource(R.string.profile_today, minutes, goal),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { (minutes.toFloat() / goal).coerceIn(0f, 1f) },
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.s).height(6.dp),
            )
        }
    }
}

@Composable
private fun NumbersGrid(state: ProfileUi) {
    val context = LocalContext.current
    val numbers = listOf(
        Triple(Icons.AutoMirrored.Outlined.MenuBook, number(state.finishedBooks), R.string.profile_stat_books),
        Triple(Icons.Outlined.AutoStories, number(state.titlesCount), R.string.profile_stat_titles),
        Triple(Icons.Outlined.LocalFireDepartment, number(state.streakDays), R.string.profile_stat_streak),
        Triple(Icons.Outlined.EmojiEvents, number(state.bestStreak), R.string.profile_stat_best),
        Triple(Icons.AutoMirrored.Outlined.ShortText, number(state.pages), R.string.profile_stat_pages),
        Triple(Icons.Outlined.CalendarMonth, number(state.activeDays), R.string.profile_stat_days),
        Triple(Icons.Outlined.Translate, number(state.wordsSaved), R.string.profile_stat_words),
        Triple(Icons.Outlined.Schedule, duration(context, state.longestSessionMs), R.string.profile_stat_longest),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        numbers.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                row.forEach { (icon, value, label) -> NumberTile(icon, value, stringResource(label), Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NumberTile(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Dimens.card))
            .padding(horizontal = Dimens.m, vertical = Dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(Dimens.m))
        Column {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

// ------------------------------------------------------------------------------------------------ milestones

@Composable
private fun MilestonesCard(milestones: List<Milestone>) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WorkspacePremium, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Dimens.s))
                Text(stringResource(R.string.profile_milestones), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val reached = milestones.sumOf { it.level }
                val total = milestones.sumOf { it.maxLevel }
                Text("$reached / $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            milestones.forEach { MilestoneRow(it) }
        }
    }
}

@Composable
private fun MilestoneRow(m: Milestone) {
    val (title, unit) = when (m.kind) {
        MilestoneKind.HOURS -> R.string.profile_ms_hours to R.string.profile_unit_hours
        MilestoneKind.STREAK -> R.string.profile_ms_streak to R.string.profile_unit_days
        MilestoneKind.BOOKS -> R.string.profile_ms_books to R.string.profile_unit_books
        MilestoneKind.PAGES -> R.string.profile_ms_pages to R.string.profile_unit_pages
        MilestoneKind.WORDS -> R.string.profile_ms_words to R.string.profile_unit_words
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LevelBadge(m.level, done = m.next == null)
        Spacer(Modifier.width(Dimens.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                val next = m.next
                Text(
                    if (next == null) stringResource(R.string.profile_ms_done)
                    else stringResource(R.string.profile_ms_progress, number(m.value), number(next), stringResource(unit)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { m.progress },
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp),
            )
        }
    }
}

@Composable
private fun LevelBadge(level: Int, done: Boolean) {
    val bg = if (level > 0) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (level > 0) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(40.dp).background(bg, CircleShape).then(
            if (done) Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary, CircleShape) else Modifier,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(level.toString(), style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = FontWeight.Bold)
    }
}

// ------------------------------------------------------------------------------------------------ titles

@Composable
private fun TopTitlesCard(titles: List<TopTitle>, onOpenStats: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = Dimens.m)) {
            SectionTitle(stringResource(R.string.profile_top), action = stringResource(R.string.profile_open_stats), onAction = onOpenStats)
            titles.forEach { t ->
                BookListItem(
                    title = t.title,
                    subtitle = duration(context, t.ms),
                    cover = t.cover?.let { c -> if (t.isBook) File(c) else c },
                    progress = 0f,
                    onClick = onOpenStats,
                )
            }
        }
    }
}

@Composable
private fun RecentCard(items: List<ReadingHistoryItem>, onOpen: (ReadingHistoryItem) -> Unit, onOpenHistory: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = Dimens.m)) {
            SectionTitle(stringResource(R.string.profile_recent), action = stringResource(R.string.profile_all_history), onAction = onOpenHistory)
            items.forEach { item ->
                BookListItem(
                    title = item.title,
                    subtitle = item.subtitle ?: item.sourceName,
                    cover = item.cover?.let { c -> if (item.bookId != null) File(c) else c },
                    progress = if (item.bookId != null && !item.finished) item.progress ?: 0f else 0f,
                    onClick = { onOpen(item) },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

// ------------------------------------------------------------------------------------------------ sharing

@Composable
private fun ShareSection(
    busy: Boolean,
    onShareCard: () -> Unit,
    onShareText: () -> Unit,
    onShareHistory: () -> Unit,
    onShareBackup: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = Dimens.m)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.profile_share_section), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                stringResource(R.string.profile_share_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
            )
            ShareRow(Icons.Outlined.Image, R.string.profile_share_card, R.string.profile_share_card_sum, enabled = !busy, onClick = onShareCard)
            ShareRow(Icons.AutoMirrored.Outlined.ShortText, R.string.profile_share_text, R.string.profile_share_text_sum, enabled = true, onClick = onShareText)
            ShareRow(Icons.Outlined.Description, R.string.profile_share_history, R.string.profile_share_history_sum, enabled = !busy, onClick = onShareHistory)
            ShareRow(Icons.AutoMirrored.Outlined.SendToMobile, R.string.profile_share_backup, R.string.profile_share_backup_sum, enabled = !busy, onClick = onShareBackup)
            ShareRow(Icons.Outlined.Backup, R.string.profile_backup_settings, R.string.profile_backup_settings_sum, enabled = true, onClick = onOpenBackup)
        }
    }
}

@Composable
private fun ShareRow(icon: ImageVector, @StringRes title: Int, @StringRes summary: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Dimens.l, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.l))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------------------------------------ edit

@Composable
private fun EditProfileDialog(name: String, avatar: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var n by rememberSaveable { mutableStateOf(name) }
    var a by rememberSaveable { mutableStateOf(avatar) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                OutlinedTextField(
                    value = n,
                    onValueChange = { n = it.take(ProfileViewModel.MAX_NAME) },
                    label = { Text(stringResource(R.string.profile_name)) },
                    placeholder = { Text(stringResource(R.string.profile_default_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.profile_avatar), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    AvatarChoice(initial(n), selected = a.isEmpty(), onClick = { a = "" })
                    AVATARS.forEach { e -> AvatarChoice(e, selected = a == e, onClick = { a = e }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(n, a) }) { Text(stringResource(CoreR.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

@Composable
private fun AvatarChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .size(Dimens.touch)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .border(if (selected) 2.dp else 1.dp, border, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 22.sp)
    }
}

// ------------------------------------------------------------------------------------------------ formatting

private fun number(value: Int): String = NumberFormat.getIntegerInstance().format(value)
private fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private fun formatDate(day: LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault()).format(day)

private fun duration(context: Context, ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> context.getString(R.string.profile_duration_m, m)
        m == 0 -> context.getString(R.string.profile_duration_h, h)
        else -> context.getString(R.string.profile_duration_hm, h, m)
    }
}

private fun displayName(context: Context, state: ProfileUi): String = state.name.ifBlank { context.getString(R.string.profile_default_name) }

/** The shared statistics as a few lines of text. */
private fun shareText(context: Context, s: ProfileUi): String {
    val r = context.resources
    return buildString {
        appendLine(context.getString(R.string.profile_text_header, displayName(context, s)))
        appendLine(context.getString(R.string.profile_text_time, duration(context, s.totalMs)))
        appendLine(context.getString(R.string.profile_text_books, s.finishedBooks))
        appendLine(
            context.getString(
                R.string.profile_text_streak,
                r.getQuantityString(R.plurals.profile_days, s.streakDays, s.streakDays),
                r.getQuantityString(R.plurals.profile_days, s.bestStreak, s.bestStreak),
            ),
        )
        appendLine(context.getString(R.string.profile_text_pages, number(s.pages)))
        if (s.wordsSaved > 0) appendLine(context.getString(R.string.profile_text_words, number(s.wordsSaved)))
        s.topTitles.firstOrNull()?.let { appendLine(context.getString(R.string.profile_text_top, it.title)) }
        append(context.getString(R.string.profile_text_footer))
    }
}

private fun cardContent(context: Context, s: ProfileUi): ProfileCard.Content = ProfileCard.Content(
    brand = context.getString(R.string.app_name),
    avatar = s.avatar.ifBlank { initial(s.name) },
    name = displayName(context, s),
    subtitle = s.since?.let { context.getString(R.string.profile_since, formatDate(it)) } ?: context.getString(R.string.profile_card_title),
    total = duration(context, s.totalMs),
    totalLabel = context.getString(R.string.profile_card_total),
    tiles = listOf(
        number(s.finishedBooks) to context.getString(R.string.profile_stat_books),
        number(s.streakDays) to context.getString(R.string.profile_stat_streak),
        number(s.pages) to context.getString(R.string.profile_stat_pages),
        if (s.wordsSaved > 0) number(s.wordsSaved) to context.getString(R.string.profile_stat_words)
        else number(s.activeDays) to context.getString(R.string.profile_stat_days),
    ),
    topLabel = context.getString(R.string.profile_top),
    top = s.topTitles.map { it.title },
    footer = context.getString(R.string.profile_card_footer, formatDate(LocalDate.now())),
)
