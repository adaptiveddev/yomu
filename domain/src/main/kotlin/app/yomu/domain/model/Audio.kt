package app.yomu.domain.model

import kotlinx.serialization.Serializable

/** Audio file types the player opens (all decoded by the platform/Media3 without extra codecs). */
object AudioFiles {
    val EXTENSIONS = listOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav")

    fun isAudio(name: String): Boolean {
        val file = name.substringBefore('?').substringAfterLast('/')
        return !file.startsWith(".") && file.substringAfterLast('.', "").lowercase() in EXTENSIONS
    }

    fun mimeType(name: String): String = when (name.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }
}

/** Where an audiobook came from; shown on the player and used to pick how its tracks are resolved. */
@Serializable
enum class AudioOrigin { FILES, CATALOG, YOUTUBE, PODCAST, NOTEBOOKLM, URL }

/** A chapter mark inside one track (YouTube description timestamps, m4b chapters). */
@Serializable
data class AudioChapter(val title: String, val startMs: Long)

/**
 * One playable track. [uri] is `file:`, `content:`, `http(s):` or `youtube:<videoId>` (resolved to a stream address
 * right before playback, since YouTube addresses expire after a few hours). [file] is a downloaded copy of an online
 * track for offline listening; it plays instead of [uri] while it exists.
 */
@Serializable
data class AudioTrack(
    val title: String,
    val uri: String,
    val durationMs: Long = 0,
    val chapters: List<AudioChapter> = emptyList(),
    val file: String? = null,
)

/** The `.yaudio` file of an audiobook that is not a plain file or folder: online streams, YouTube, podcasts. */
@Serializable
data class AudiobookManifest(
    val version: Int = 1,
    val title: String,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val language: String? = null,
    val coverUrl: String? = null,
    val origin: AudioOrigin = AudioOrigin.URL,
    /** The page the book was added from (a YouTube video or playlist, a feed, a catalog title). */
    val sourceUrl: String? = null,
    val tracks: List<AudioTrack>,
) {
    companion object {
        const val EXTENSION = "yaudio"
        const val MIME_TYPE = "application/x-yomu-audiobook+json"
        const val YOUTUBE_SCHEME = "youtube"

        fun youtubeUri(videoId: String) = "$YOUTUBE_SCHEME:$videoId"
        fun youtubeId(uri: String): String? = uri.takeIf { it.startsWith("$YOUTUBE_SCHEME:") }?.substringAfter(':')?.takeIf { it.isNotBlank() }
    }
}

/** An entry of the player's chapter list: a whole track, or a chapter mark inside one. */
data class AudioNavEntry(val track: Int, val startMs: Long, val title: String, val isMark: Boolean)

/** Positions in an audiobook, stored in the common [Locator]: chapter = track index, offset = milliseconds. */
object AudioPositions {
    fun locator(track: Int, positionMs: Long, trackDurationMs: Long, total: Float, title: String?): Locator = Locator(
        chapter = track,
        progression = if (trackDurationMs > 0) (positionMs.toFloat() / trackDurationMs).coerceIn(0f, 1f) else 0f,
        totalProgression = total.coerceIn(0f, 1f),
        offset = positionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        chapterTitle = title,
    )

    fun positionMs(locator: Locator): Long = locator.offset?.toLong()?.coerceAtLeast(0) ?: 0

    /**
     * Progress through the whole book. With every track length known it is time based; otherwise each track counts
     * the same (a playlist whose lengths the player has not seen yet).
     */
    fun total(durations: List<Long>, track: Int, positionMs: Long): Float {
        if (durations.isEmpty()) return 0f
        val index = track.coerceIn(0, durations.lastIndex)
        if (durations.all { it > 0 }) {
            val sum = durations.sum().toFloat()
            val before = durations.take(index).sum()
            return ((before + positionMs.coerceIn(0, durations[index])) / sum).coerceIn(0f, 1f)
        }
        val within = durations[index].takeIf { it > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
        return ((index + within) / durations.size).coerceIn(0f, 1f)
    }

    /** The chapter list: tracks, with their chapter marks instead when a track has them. */
    fun navigation(tracks: List<AudioTrack>): List<AudioNavEntry> = tracks.flatMapIndexed { i, t ->
        if (t.chapters.isEmpty()) listOf(AudioNavEntry(i, 0, t.title, isMark = false))
        else t.chapters.map { AudioNavEntry(i, it.startMs, it.title, isMark = true) }
    }

    /** Index in [entries] of the entry playing at [positionMs] of [track], or -1. */
    fun current(entries: List<AudioNavEntry>, track: Int, positionMs: Long): Int =
        entries.indexOfLast { it.track < track || (it.track == track && it.startMs <= positionMs) }

    /** Listening time left in the book at normal speed, or null while a track length is unknown. */
    fun remaining(durations: List<Long>, track: Int, positionMs: Long): Long? {
        if (track !in durations.indices) return null
        val later = durations.drop(track + 1)
        if (durations[track] <= 0 || later.any { it <= 0 }) return null
        return (durations[track] - positionMs).coerceAtLeast(0) + later.sum()
    }
}

/**
 * Chapter marks written as timestamps in a description ("00:00 Intro", "1:02:03 - Chapter 2", "Chapter 3 (1:30:00)").
 * Like YouTube itself, a list counts only when it starts at 0:00, has at least three marks and goes forward in time.
 */
object ChapterMarks {
    private const val TIME = """((?:\d{1,2}:)?\d{1,2}:\d{2})"""
    private val leading = Regex("""^\s*(?:[-•*▶►]\s*)?[\[(]?$TIME[\])]?\s*(?:[-–—:|.)]\s*)?(.+?)\s*$""")
    private val trailing = Regex("""^\s*(?:[-•*▶►]\s*)?(.+?)\s*(?:[-–—|:]\s*)?[\[(]?$TIME[\])]?\s*$""")

    fun parse(text: String?, durationMs: Long = 0): List<AudioChapter> {
        if (text.isNullOrBlank()) return emptyList()
        val marks = text.lineSequence().mapNotNull { line ->
            leading.matchEntire(line)?.let { m -> mark(m.groupValues[1], m.groupValues[2]) }
                ?: trailing.matchEntire(line)?.let { m -> mark(m.groupValues[2], m.groupValues[1]) }
        }.toList()
        if (marks.size < 3 || marks.first().startMs != 0L) return emptyList()
        if (marks.zipWithNext().any { (a, b) -> b.startMs <= a.startMs }) return emptyList()
        if (durationMs > 0 && marks.last().startMs >= durationMs) return emptyList()
        return marks
    }

    private fun mark(time: String, title: String): AudioChapter? {
        val clean = title.trim().trim('-', '–', '—', '|', ':').trim()
        if (clean.isEmpty()) return null
        return parseTime(time)?.let { AudioChapter(clean, it) }
    }

    /** "1:02:03" or "02:03" to milliseconds. */
    fun parseTime(time: String): Long? {
        val parts = time.split(':').map { it.toLongOrNull() ?: return null }
        if (parts.size !in 2..3 || parts.drop(1).any { it >= 60 }) return null
        return parts.fold(0L) { acc, v -> acc * 60 + v } * 1000
    }
}
