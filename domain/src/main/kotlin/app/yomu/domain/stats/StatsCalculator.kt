package app.yomu.domain.stats

import app.yomu.domain.model.ReadingSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class StatsPeriod { WEEK, MONTH, YEAR, ALL }

/** One bar of the activity chart: a day (week/month view) or a month (year/all-time view). */
data class StatsBucket(val start: LocalDate, val ms: Long, val isMonth: Boolean)

data class TitleTime(val key: String, val ms: Long, val sessions: Int, val lastAt: Long)

data class StatsSummary(
    val period: StatsPeriod,
    val from: LocalDate,
    val to: LocalDate,
    val totalMs: Long,
    val sessions: Int,
    val pages: Int,
    val chars: Long,
    val activeDays: Int,
    /** Average over the days of the period that are already past (or since the first session for ALL). */
    val avgPerDayMs: Long,
    val longestSessionMs: Long,
    val bestDay: LocalDate?,
    val bestDayMs: Long,
    val buckets: List<StatsBucket>,
    /** Milliseconds read per hour of the day (0..23). */
    val byHour: List<Long>,
    /** Milliseconds per weekday, Monday first. */
    val byWeekday: List<Long>,
    val topTitles: List<TitleTime>,
    val titlesCount: Int,
    val todayMs: Long,
    val streakDays: Int,
    val bestStreak: Int,
    /** Day -> ms for the last [HEATMAP_WEEKS] weeks (all periods). */
    val heatmap: Map<LocalDate, Long>,
    val heatmapStart: LocalDate,
    val previousTotalMs: Long,
) {
    val charsPerMinute: Int get() = if (totalMs <= 0) 0 else (chars * 60_000 / totalMs).toInt()
    val pagesPerHour: Int get() = if (totalMs <= 0) 0 else (pages * 3_600_000L / totalMs).toInt()
    val avgSessionMs: Long get() = if (sessions == 0) 0 else totalMs / sessions

    companion object {
        const val HEATMAP_WEEKS = 18
    }
}

/**
 * Pure statistics over reading sessions. Sessions crossing midnight are split between both days, so the charts add up.
 */
object StatsCalculator {

    fun compute(sessions: List<ReadingSession>, period: StatsPeriod, now: Long, zone: ZoneId): StatsSummary {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val perDay = HashMap<LocalDate, Long>()
        sessions.forEach { s -> split(s, zone) { day, _, ms -> perDay.merge(day, ms, Long::plus) } }

        val first = sessions.minOfOrNull { it.startedAt }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today
        val (from, to) = when (period) {
            StatsPeriod.WEEK -> today.minusDays(6) to today
            StatsPeriod.MONTH -> today.minusDays(29) to today
            StatsPeriod.YEAR -> today.withDayOfMonth(1).minusMonths(11) to today
            StatsPeriod.ALL -> minOf(first, today) to today
        }
        val inPeriod = sessions.filter { s ->
            val d = Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate()
            !d.isBefore(from) && !d.isAfter(to)
        }
        val byHour = LongArray(24)
        val byWeekday = LongArray(7)
        val periodDays = HashMap<LocalDate, Long>()
        inPeriod.forEach { s ->
            split(s, zone) { day, hour, ms ->
                if (!day.isBefore(from) && !day.isAfter(to)) {
                    periodDays.merge(day, ms, Long::plus)
                    byHour[hour] += ms
                    byWeekday[day.dayOfWeek.value - 1] += ms
                }
            }
        }
        val total = periodDays.values.sum()
        val daysElapsed = (to.toEpochDay() - from.toEpochDay() + 1).coerceAtLeast(1)
        val best = periodDays.maxByOrNull { it.value }

        val buckets = when (period) {
            StatsPeriod.WEEK, StatsPeriod.MONTH -> (0 until daysElapsed).map { i ->
                val d = from.plusDays(i)
                StatsBucket(d, periodDays[d] ?: 0L, isMonth = false)
            }
            StatsPeriod.YEAR, StatsPeriod.ALL -> {
                val months = generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(to)) }.toList()
                val tail = if (period == StatsPeriod.ALL) months.takeLast(24) else months
                tail.map { ym ->
                    val ms = periodDays.entries.filter { YearMonth.from(it.key) == ym }.sumOf { it.value }
                    StatsBucket(ym.atDay(1), ms, isMonth = true)
                }
            }
        }

        val titles = inPeriod.filter { it.titleKey != null }.groupBy { it.titleKey!! }.map { (k, v) ->
            TitleTime(k, v.sumOf { it.durationMs }, v.size, v.maxOf { it.endedAt })
        }.sortedByDescending { it.ms }

        // Streaks look at all reading, whatever the period.
        val days = perDay.keys.sorted()
        var bestStreak = 0
        var run = 0
        var prev: LocalDate? = null
        days.forEach { d ->
            run = if (prev != null && d == prev.plusDays(1)) run + 1 else 1
            bestStreak = maxOf(bestStreak, run)
            prev = d
        }
        var streak = 0
        var day = if (perDay.containsKey(today)) today else today.minusDays(1)
        while (perDay.containsKey(day)) {
            streak++
            day = day.minusDays(1)
        }

        val heatStart = today.minusWeeks(StatsSummary.HEATMAP_WEEKS.toLong() - 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val heatmap = perDay.filterKeys { !it.isBefore(heatStart) && !it.isAfter(today) }

        val previous = when (period) {
            StatsPeriod.ALL -> 0L
            else -> {
                val length = to.toEpochDay() - from.toEpochDay() + 1
                val pFrom = from.minusDays(length)
                val pTo = from.minusDays(1)
                perDay.filterKeys { !it.isBefore(pFrom) && !it.isAfter(pTo) }.values.sum()
            }
        }

        return StatsSummary(
            period = period,
            from = from,
            to = to,
            totalMs = total,
            sessions = inPeriod.size,
            pages = inPeriod.sumOf { it.pages },
            chars = inPeriod.sumOf { it.chars },
            activeDays = periodDays.count { it.value > 0 },
            avgPerDayMs = total / daysElapsed,
            longestSessionMs = inPeriod.maxOfOrNull { it.durationMs } ?: 0L,
            bestDay = best?.key,
            bestDayMs = best?.value ?: 0L,
            buckets = buckets,
            byHour = byHour.toList(),
            byWeekday = byWeekday.toList(),
            topTitles = titles.take(5),
            titlesCount = titles.size,
            todayMs = perDay[today] ?: 0L,
            streakDays = streak,
            bestStreak = bestStreak,
            heatmap = heatmap,
            heatmapStart = heatStart,
            previousTotalMs = previous,
        )
    }

    /** Calls [sink] for every (day, hour) slice of the session. */
    internal inline fun split(s: ReadingSession, zone: ZoneId, sink: (LocalDate, Int, Long) -> Unit) {
        var t = s.startedAt
        val end = s.startedAt + s.durationMs.coerceAtLeast(0)
        if (end == t) return
        var guard = 0
        while (t < end && guard++ < 24 * 31) {
            val z = Instant.ofEpochMilli(t).atZone(zone)
            val nextHour = z.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
            val sliceEnd = minOf(end, nextHour)
            sink(z.toLocalDate(), z.hour, sliceEnd - t)
            t = sliceEnd
        }
    }
}
