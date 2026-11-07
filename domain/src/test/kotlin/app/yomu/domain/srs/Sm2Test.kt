package app.yomu.domain.srs

import app.yomu.domain.model.SrsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Sm2Test {
    private val now = 1_700_000_000_000L

    @Test
    fun `successful reviews grow the interval`() {
        val first = Sm2.review(SrsState(), Sm2.qualityFor(true), now)
        assertEquals(1, first.repetitions)
        assertEquals(1, first.intervalDays)
        assertEquals(now + Sm2.DAY_MS, first.dueAt)
        assertEquals(now, first.lastReviewedAt)

        val second = Sm2.review(first, 4, now)
        assertEquals(6, second.intervalDays)
        val third = Sm2.review(second, 4, now)
        assertEquals(Math.round(6 * third.ease).toInt(), third.intervalDays)
        assertTrue(third.intervalDays > second.intervalDays)
    }

    @Test
    fun `failure resets repetitions and counts a lapse`() {
        val learned = SrsState(ease = 2.5, intervalDays = 20, repetitions = 4)
        val failed = Sm2.review(learned, Sm2.qualityFor(false), now)
        assertEquals(0, failed.repetitions)
        assertEquals(0, failed.intervalDays)
        assertEquals(1, failed.lapses)
        assertEquals(now + 10 * 60_000L, failed.dueAt)
        assertTrue(failed.ease < learned.ease)
    }

    @Test
    fun `ease never drops below the minimum`() {
        var s = SrsState()
        repeat(20) { s = Sm2.review(s, 0, now) }
        assertEquals(1.3, s.ease, 1e-9)
    }

    @Test
    fun `quality outside the range is rejected`() {
        assertThrows<IllegalArgumentException> { Sm2.review(SrsState(), 6, now) }
    }

    @Test
    fun `level follows the interval`() {
        assertEquals(0, Sm2.level(SrsState()))
        assertEquals(1, Sm2.level(SrsState(repetitions = 1, intervalDays = 1)))
        assertEquals(2, Sm2.level(SrsState(repetitions = 2, intervalDays = 6)))
        assertEquals(3, Sm2.level(SrsState(repetitions = 3, intervalDays = 15)))
        assertEquals(4, Sm2.level(SrsState(repetitions = 4, intervalDays = 40)))
        assertEquals(5, Sm2.level(SrsState(repetitions = 5, intervalDays = 100)))
    }
}
