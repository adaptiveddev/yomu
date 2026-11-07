package app.yomu.domain.stats

import app.yomu.domain.model.ReadingSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StatsCalculatorTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val today: LocalDate = LocalDate.of(2026, 3, 18) // a Wednesday
    private val now = at(today, 21, 0)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun session(day: LocalDate, hour: Int, minutes: Int, bookId: Long? = 1, pages: Int = 10, chars: Long = 0) =
        ReadingSession(bookId = bookId, startedAt = at(day, hour), durationMs = minutes * 60_000L, pages = pages, chars = chars)

    @Test
    fun `sessions crossing midnight are split between the days`() {
        val s = session(today.minusDays(1), 23, 90) // 23:00 → 00:30
        val r = StatsCalculator.compute(listOf(s), StatsPeriod.WEEK, now, zone)
        assertEquals(90 * 60_000L, r.totalMs)
        assertEquals(60 * 60_000L, r.buckets.first { it.start == today.minusDays(1) }.ms)
        assertEquals(30 * 60_000L, r.buckets.first { it.start == today }.ms)
        assertEquals(30 * 60_000L, r.todayMs)
        assertEquals(60 * 60_000L, r.byHour[23])
        assertEquals(30 * 60_000L, r.byHour[0])
        assertEquals(2, r.activeDays)
    }

    @Test
    fun `week has seven daily buckets and totals add up`() {
        val list = listOf(
            session(today, 8, 20),
            session(today.minusDays(2), 12, 40, bookId = 2),
            session(today.minusDays(10), 12, 50), // outside of the week
        )
        val r = StatsCalculator.compute(list, StatsPeriod.WEEK, now, zone)
        assertEquals(7, r.buckets.size)
        assertEquals(today.minusDays(6), r.from)
        assertEquals(60 * 60_000L, r.totalMs)
        assertEquals(r.totalMs, r.buckets.sumOf { it.ms })
        assertEquals(2, r.sessions)
        assertEquals(2, r.titlesCount)
        assertEquals("b:2", r.topTitles.first().key)
        assertEquals(today.minusDays(2), r.bestDay)
        // The previous week had the 50 minute session.
        assertEquals(50 * 60_000L, r.previousTotalMs)
    }

    @Test
    fun `streaks count consecutive days up to today or yesterday`() {
        val list = listOf(
            session(today.minusDays(1), 10, 15),
            session(today.minusDays(2), 10, 15),
            session(today.minusDays(3), 10, 15),
            session(today.minusDays(6), 10, 15),
            session(today.minusDays(7), 10, 15),
        )
        val r = StatsCalculator.compute(list, StatsPeriod.MONTH, now, zone)
        assertEquals(3, r.streakDays) // today not read yet: the streak is still alive
        assertEquals(3, r.bestStreak)
        assertEquals(30, r.buckets.size)
        val withToday = StatsCalculator.compute(list + session(today, 9, 5), StatsPeriod.MONTH, now, zone)
        assertEquals(4, withToday.streakDays)
        assertEquals(4, withToday.bestStreak)
    }

    @Test
    fun `year and all time use monthly buckets`() {
        val list = listOf(session(today.minusMonths(3), 10, 30), session(today, 10, 30))
        val year = StatsCalculator.compute(list, StatsPeriod.YEAR, now, zone)
        assertEquals(12, year.buckets.size)
        assertTrue(year.buckets.all { it.isMonth })
        assertEquals(60 * 60_000L, year.totalMs)
        val all = StatsCalculator.compute(list, StatsPeriod.ALL, now, zone)
        assertEquals(4, all.buckets.size)
        assertEquals(0L, all.previousTotalMs)
    }

    @Test
    fun `reading speed and averages`() {
        val r = StatsCalculator.compute(listOf(session(today, 10, 60, pages = 30, chars = 60_000)), StatsPeriod.WEEK, now, zone)
        assertEquals(1000, r.charsPerMinute)
        assertEquals(30, r.pagesPerHour)
        assertEquals(60 * 60_000L, r.avgSessionMs)
        assertEquals(60 * 60_000L / 7, r.avgPerDayMs)
        val empty = StatsCalculator.compute(emptyList(), StatsPeriod.WEEK, now, zone)
        assertEquals(0L, empty.totalMs)
        assertEquals(0, empty.streakDays)
        assertEquals(0, empty.charsPerMinute)
    }
}
