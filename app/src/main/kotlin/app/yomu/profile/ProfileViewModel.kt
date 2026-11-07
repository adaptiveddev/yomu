package app.yomu.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.ScreenErrors
import app.yomu.core.datastore.AppSettings
import app.yomu.data.backup.BackupManager
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.data.repository.ReadingHistoryRepository
import app.yomu.data.repository.WordRepositoryImpl
import app.yomu.domain.repository.StatsRepository
import app.yomu.domain.stats.StatsCalculator
import app.yomu.domain.stats.StatsPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Kinds of milestones of the profile; [tiers] are the values to reach, in order. */
enum class MilestoneKind(val tiers: LongArray) {
    /** Hours of reading. */
    HOURS(longArrayOf(1, 10, 50, 100, 250, 500, 1000)),
    /** Days in a row (the best streak counts, so a milestone is never lost). */
    STREAK(longArrayOf(3, 7, 14, 30, 100, 365)),
    BOOKS(longArrayOf(1, 5, 10, 25, 50, 100)),
    PAGES(longArrayOf(100, 1_000, 5_000, 10_000, 50_000)),
    WORDS(longArrayOf(10, 50, 100, 500, 1_000, 5_000)),
    ;

    /** How many tiers [value] reached. */
    fun level(value: Long): Int = tiers.count { value >= it }

    /** The next value to reach, or null when every tier is done. */
    fun next(value: Long): Long? = tiers.firstOrNull { value < it }

    /** Progress from the last reached tier to the next one (1 when all are done). */
    fun progress(value: Long): Float {
        val next = next(value) ?: return 1f
        val prev = tiers.lastOrNull { value >= it } ?: 0L
        return ((value - prev).toFloat() / (next - prev).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

data class Milestone(val kind: MilestoneKind, val value: Long) {
    val level: Int get() = kind.level(value)
    val next: Long? get() = kind.next(value)
    val progress: Float get() = kind.progress(value)
    val maxLevel: Int get() = kind.tiers.size
}

data class TopTitle(val title: String, val cover: String?, val ms: Long, val isBook: Boolean)

/** Everything the profile shows: who reads (name, avatar) and what they have read so far, over all time. */
data class ProfileUi(
    val name: String,
    val avatar: String,
    val totalMs: Long,
    val sessions: Int,
    val pages: Int,
    val activeDays: Int,
    val streakDays: Int,
    val bestStreak: Int,
    val longestSessionMs: Long,
    val todayMs: Long,
    val goalMinutes: Int,
    val titlesCount: Int,
    val finishedBooks: Int,
    val wordsSaved: Int,
    val libraryBooks: Int,
    /** Day of the first reading session; null before the first one. */
    val since: LocalDate?,
    val topTitles: List<TopTitle>,
) {
    val milestones: List<Milestone>
        get() = listOf(
            Milestone(MilestoneKind.HOURS, totalMs / 3_600_000),
            Milestone(MilestoneKind.STREAK, bestStreak.toLong()),
            Milestone(MilestoneKind.BOOKS, finishedBooks.toLong()),
            Milestone(MilestoneKind.PAGES, pages.toLong()),
            Milestone(MilestoneKind.WORDS, wordsSaved.toLong()),
        )
}

/**
 * The profile tab (1.8.0): a summary of all reading, milestones and what can be shared (a statistics card, the
 * history as a table, a backup to move everything to another phone).
 */
class ProfileViewModel(
    private val stats: StatsRepository,
    private val history: ReadingHistoryRepository,
    private val settings: AppSettings,
    private val backup: BackupManager,
    words: WordRepositoryImpl,
    books: BookRepositoryImpl,
) : ViewModel() {

    private val who = combine(settings.profileName.flow, settings.profileAvatar.flow) { n, a -> n to a }

    /** null while loading. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ProfileUi?> =
        combine(stats.observeSessions(), who, settings.dailyGoalMinutes.flow, words.observeCount(), books.observeCount()) { s, w, g, wc, bc ->
            Inputs(s, w.first, w.second, g, wc, bc)
        }.mapLatest { i ->
            val zone = ZoneId.systemDefault()
            // All-time summary over every session: on a background thread, it is the heaviest part of the tab.
            val summary = StatsCalculator.compute(i.sessions, StatsPeriod.ALL, System.currentTimeMillis(), zone)
            val top = summary.topTitles.take(3)
            val described = if (top.isEmpty()) emptyMap() else history.describe(top.map { it.key })
            ProfileUi(
                name = i.name.trim(),
                avatar = i.avatar.trim(),
                totalMs = summary.totalMs,
                sessions = summary.sessions,
                pages = summary.pages,
                activeDays = summary.activeDays,
                streakDays = summary.streakDays,
                bestStreak = summary.bestStreak,
                longestSessionMs = summary.longestSessionMs,
                todayMs = summary.todayMs,
                goalMinutes = i.goal,
                titlesCount = summary.titlesCount,
                finishedBooks = runCatching { history.finishedBooksSince(0) }.getOrDefault(0),
                wordsSaved = i.words,
                libraryBooks = i.books,
                since = i.sessions.minOfOrNull { it.startedAt }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
                topTitles = top.mapNotNull { t ->
                    val d = described[t.key] ?: return@mapNotNull null
                    TopTitle(d.title, d.cover, t.ms, isBook = t.key.startsWith("b:"))
                },
            )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private data class Inputs(
        val sessions: List<app.yomu.domain.model.ReadingSession>,
        val name: String,
        val avatar: String,
        val goal: Int,
        val words: Int,
        val books: Int,
    )

    fun saveProfile(name: String, avatar: String) = viewModelScope.launch(ScreenErrors) {
        settings.profileName.set(name.trim().take(MAX_NAME))
        settings.profileAvatar.set(avatar.trim())
    }

    /**
     * The reading history as a table (one row per book or title and day) for a spreadsheet: written to [dir] and
     * returned for sharing.
     */
    suspend fun exportHistoryCsv(dir: File): File = withContext(Dispatchers.IO) {
        val items = history.observe().first()
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val zone = ZoneId.systemDefault()
        val file = File(dir, "yomu_history_" + LocalDate.now() + ".csv")
        file.bufferedWriter().use { w ->
            // A BOM so Excel opens the UTF-8 titles (Cyrillic, Japanese) correctly.
            w.write("\uFEFF")
            w.write("date,time,type,title,details,source,minutes,pages,progress_percent,chapters\n")
            items.forEach { item ->
                val cells = listOf(
                    item.day.toString(),
                    time.format(Instant.ofEpochMilli(item.readAt).atZone(zone)),
                    if (item.bookId != null) "book" else "online",
                    item.title,
                    item.subtitle.orEmpty(),
                    item.sourceName.orEmpty(),
                    (item.durationMs / 60_000).toString(),
                    item.pages.toString(),
                    item.progress?.let { p -> (p * 100).toInt().toString() }.orEmpty(),
                    if (item.catalogItemId != null) item.chaptersRead.toString() else "",
                )
                w.write(cells.joinToString(",") { c -> csv(c) })
                w.write("\n")
            }
        }
        file
    }

    /** A backup without the book files (settings, library, history, statistics, words) to send to another device. */
    suspend fun createBackup(dir: File): File = withContext(Dispatchers.IO) {
        val file = File(dir, backup.suggestedName())
        try {
            file.outputStream().use { backup.create(it, includeFiles = false) }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        file
    }

    companion object {
        const val MAX_NAME = 40

        /** One CSV cell: quoted when it holds a comma, a quote or a line break; formulas are defused for spreadsheets. */
        internal fun csv(value: String): String {
            val safe = if (value.isNotEmpty() && value[0] in "=+-@" && value.toDoubleOrNull() == null) "'$value" else value
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }
    }
}
