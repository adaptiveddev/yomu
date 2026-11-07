package app.yomu.domain.srs

import app.yomu.domain.model.SrsState
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * SuperMemo-2 scheduler. "Know" maps to quality 4, "don't know" to quality 1.
 * Intervals: 1 day, 6 days, then previous * ease.
 */
object Sm2 {
    const val DAY_MS = 86_400_000L

    fun review(state: SrsState, quality: Int, now: Long): SrsState {
        require(quality in 0..5)
        val ease = max(1.3, state.ease + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02)))
        return if (quality < 3) {
            state.copy(
                repetitions = 0,
                intervalDays = 0,
                ease = ease,
                lapses = state.lapses + 1,
                // Show again in 10 minutes inside the same session.
                dueAt = now + 10 * 60_000L,
                lastReviewedAt = now,
            )
        } else {
            val reps = state.repetitions + 1
            val interval = when (reps) {
                1 -> 1
                2 -> 6
                else -> (state.intervalDays * ease).roundToInt().coerceAtLeast(1)
            }
            state.copy(repetitions = reps, intervalDays = interval, ease = ease, dueAt = now + interval * DAY_MS, lastReviewedAt = now)
        }
    }

    fun qualityFor(knew: Boolean) = if (knew) 4 else 1

    /** Knowledge level 0..5 derived from the schedule, shown in lists and used by filters. */
    fun level(state: SrsState): Int = when {
        state.repetitions == 0 -> 0
        state.intervalDays < 2 -> 1
        state.intervalDays < 7 -> 2
        state.intervalDays < 21 -> 3
        state.intervalDays < 60 -> 4
        else -> 5
    }
}
