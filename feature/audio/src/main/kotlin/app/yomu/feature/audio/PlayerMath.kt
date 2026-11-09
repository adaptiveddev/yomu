package app.yomu.feature.audio

import app.yomu.domain.model.AudioNavEntry
import app.yomu.domain.model.AudioPositions

/** The stretch of a track that the current chapter covers; [endMs] is 0 while the track length is unknown. */
data class ChapterSpan(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0)

    fun progress(positionMs: Long): Float =
        if (lengthMs > 0) ((positionMs - startMs).toFloat() / lengthMs).coerceIn(0f, 1f) else 0f

    fun positionAt(fraction: Float): Long = startMs + (fraction.coerceIn(0f, 1f) * lengthMs).toLong()
}

internal object PlayerMath {
    /** Span of the chapter at [index] of [chapters] inside its track; marks end where the next mark of the same track starts. */
    fun span(chapters: List<AudioNavEntry>, index: Int, trackDurationMs: Long): ChapterSpan {
        val entry = chapters.getOrNull(index) ?: return ChapterSpan(0, trackDurationMs)
        val next = chapters.getOrNull(index + 1)?.takeIf { it.track == entry.track }
        return ChapterSpan(entry.startMs, next?.startMs ?: trackDurationMs)
    }

    /** Length of each chapter; 0 where the track length is not known. */
    fun lengths(chapters: List<AudioNavEntry>, trackDurations: List<Long>): List<Long> = chapters.indices.map { i ->
        val d = trackDurations.getOrElse(chapters[i].track) { 0 }
        val end = chapters.getOrNull(i + 1)?.takeIf { it.track == chapters[i].track }?.startMs ?: d
        if (end > 0) (end - chapters[i].startMs).coerceAtLeast(0) else 0
    }

    /** Share of the book heard, from 0 to 1, or null while a track length is unknown. */
    fun bookProgress(durations: List<Long>, track: Int, positionMs: Long): Float? {
        val total = durations.sum()
        val left = AudioPositions.remaining(durations, track, positionMs) ?: return null
        return if (total > 0) (1f - left.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /** Minutes to set the sleep timer to when [extraMin] are added to what is left until [sleepAt]. */
    fun extendedSleepMinutes(sleepAt: Long, now: Long, extraMin: Int): Int =
        (((sleepAt - now).coerceAtLeast(0) + 59_999) / 60_000).toInt() + extraMin

    /** Indices of [chapters] whose title contains every word of [query]; all of them for a blank query. */
    fun search(chapters: List<AudioNavEntry>, query: String): List<Int> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return chapters.indices.filter { i -> words.all { chapters[i].title.lowercase().contains(it) } }
    }
}
