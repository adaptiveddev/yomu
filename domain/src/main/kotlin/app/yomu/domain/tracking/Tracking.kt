package app.yomu.domain.tracking

import kotlin.math.floor

/** Tracking services (2.0.0). Ids follow Mihon/Tachiyomi so the trackers of their backups map one to one. */
enum class TrackService(val id: Int, val displayName: String, val site: String) {
    MYANIMELIST(1, "MyAnimeList", "https://myanimelist.net"),
    ANILIST(2, "AniList", "https://anilist.co"),
    KITSU(3, "Kitsu", "https://kitsu.io"),
    SHIKIMORI(4, "Shikimori", "https://shikimori.io"),
    MANGAUPDATES(7, "MangaUpdates", "https://www.mangaupdates.com"),
    ;

    companion object {
        fun of(id: Int): TrackService? = entries.firstOrNull { it.id == id }
    }
}

/** Reading status in the user's list; every service has the same six. */
enum class TrackStatus(val code: Int) {
    READING(1),
    PLAN_TO_READ(2),
    COMPLETED(3),
    ON_HOLD(4),
    DROPPED(5),
    REREADING(6),
    ;

    companion object {
        fun of(code: Int): TrackStatus = entries.firstOrNull { it.code == code } ?: READING
    }
}

/** A title found on a service. */
data class TrackSearchResult(
    val remoteId: Long,
    val title: String,
    val altTitle: String? = null,
    val coverUrl: String? = null,
    val url: String,
    val format: String? = null,
    val publishing: String? = null,
    val totalChapters: Int = 0,
    val year: Int? = null,
    val description: String? = null,
)

/** The user's list entry for a title on a service. Dates are epoch milliseconds, 0 = not set; score is 0–10. */
data class TrackEntry(
    val service: TrackService,
    val remoteId: Long,
    val libraryId: Long = 0,
    val title: String,
    val url: String,
    val status: TrackStatus = TrackStatus.READING,
    val score: Float = 0f,
    val lastChapterRead: Float = 0f,
    val totalChapters: Int = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    /** Kitsu only: the entry is hidden from other users. Preserved on every update. */
    val privacy: Boolean = false,
)

object TrackProgress {
    /**
     * The entry after reading the chapter numbered [chapterNumber]: progress only moves forward, the first chapter starts the
     * title (planned or paused → reading, start date), the last one completes it. Null when nothing changes.
     */
    fun afterReading(entry: TrackEntry, chapterNumber: Float, now: Long): TrackEntry? {
        if (chapterNumber.isNaN() || chapterNumber < 1f) return null
        val read = floor(chapterNumber)
        if (read <= entry.lastChapterRead) return null
        var status = entry.status
        if (status == TrackStatus.PLAN_TO_READ || status == TrackStatus.ON_HOLD || status == TrackStatus.DROPPED) status = TrackStatus.READING
        var finished = entry.finishedAt
        if (entry.totalChapters > 0 && read >= entry.totalChapters && status != TrackStatus.COMPLETED) {
            status = TrackStatus.COMPLETED
            if (finished == 0L) finished = now
        }
        return entry.copy(
            lastChapterRead = read,
            status = status,
            startedAt = entry.startedAt.takeIf { it > 0 } ?: now,
            finishedAt = finished,
        )
    }
}

/** Mihon/Tachiyomi backup values → ours. */
object MihonTracking {
    /** Their status codes differ per service. */
    fun status(service: Int, code: Int): TrackStatus = when (service) {
        // MyAnimeList: 6 and 7 follow MAL's own numbering.
        1 -> when (code) {
            2 -> TrackStatus.COMPLETED
            3 -> TrackStatus.ON_HOLD
            4 -> TrackStatus.DROPPED
            6 -> TrackStatus.PLAN_TO_READ
            7 -> TrackStatus.REREADING
            else -> TrackStatus.READING
        }
        // MangaUpdates: the code is the list id (0 Reading, 1 Wish, 2 Complete, 3 Unfinished, 4 On Hold).
        TrackService.MANGAUPDATES.id -> when (code) {
            1 -> TrackStatus.PLAN_TO_READ
            2 -> TrackStatus.COMPLETED
            3 -> TrackStatus.DROPPED
            4 -> TrackStatus.ON_HOLD
            else -> TrackStatus.READING
        }
        else -> when (code) {
            2 -> TrackStatus.COMPLETED
            3 -> TrackStatus.ON_HOLD
            4 -> TrackStatus.DROPPED
            5 -> TrackStatus.PLAN_TO_READ
            6 -> TrackStatus.REREADING
            else -> TrackStatus.READING
        }
    }

    /** AniList uses 0–100 and Kitsu 0–20 in Mihon backups. */
    fun score(service: Int, score: Float): Float = (when (service) { TrackService.ANILIST.id -> score / 10f; TrackService.KITSU.id -> score / 2f; else -> score }).coerceIn(0f, 10f)
}
