package app.yomu.domain.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TrackingTest {
    private val entry = TrackEntry(TrackService.ANILIST, remoteId = 1, title = "Title", url = "https://anilist.co/manga/1", status = TrackStatus.PLAN_TO_READ, totalChapters = 10)

    @Test
    fun `reading a chapter moves progress forward and starts the title`() {
        val next = TrackProgress.afterReading(entry, 3.5f, now = 100)!!
        assertEquals(3f, next.lastChapterRead)
        assertEquals(TrackStatus.READING, next.status)
        assertEquals(100L, next.startedAt)
        assertEquals(0L, next.finishedAt)
    }

    @Test
    fun `older chapters and extras do not change the entry`() {
        val reading = entry.copy(lastChapterRead = 5f, status = TrackStatus.READING, startedAt = 1)
        assertNull(TrackProgress.afterReading(reading, 4f, now = 100))
        assertNull(TrackProgress.afterReading(reading, 5.5f, now = 100))
        assertNull(TrackProgress.afterReading(reading, 0f, now = 100))
        assertNull(TrackProgress.afterReading(reading, -1f, now = 100))
    }

    @Test
    fun `the last chapter completes the title`() {
        val next = TrackProgress.afterReading(entry.copy(lastChapterRead = 9f, status = TrackStatus.READING, startedAt = 1), 10f, now = 200)!!
        assertEquals(TrackStatus.COMPLETED, next.status)
        assertEquals(200L, next.finishedAt)
        assertEquals(1L, next.startedAt)
    }

    @Test
    fun `mihon statuses and scores map per service`() {
        assertEquals(TrackStatus.PLAN_TO_READ, MihonTracking.status(1, 6))
        assertEquals(TrackStatus.REREADING, MihonTracking.status(1, 7))
        assertEquals(TrackStatus.PLAN_TO_READ, MihonTracking.status(2, 5))
        assertEquals(TrackStatus.COMPLETED, MihonTracking.status(4, 2))
        assertEquals(8.5f, MihonTracking.score(2, 85f))
        assertEquals(7f, MihonTracking.score(4, 7f))
        assertEquals(8.5f, MihonTracking.score(3, 17f))
        assertEquals(TrackService.KITSU, TrackService.of(3))
        assertEquals(TrackService.SHIKIMORI, TrackService.of(4))
        assertEquals(TrackService.MANGAUPDATES, TrackService.of(7))
        // MangaUpdates stores the list id as the status (0 Reading, 1 Wish, 2 Complete, 3 Unfinished, 4 On Hold).
        assertEquals(TrackStatus.PLAN_TO_READ, MihonTracking.status(7, 1))
        assertEquals(TrackStatus.COMPLETED, MihonTracking.status(7, 2))
        assertEquals(TrackStatus.DROPPED, MihonTracking.status(7, 3))
        assertEquals(TrackStatus.ON_HOLD, MihonTracking.status(7, 4))
        assertEquals(TrackStatus.READING, MihonTracking.status(7, 0))
        assertNull(TrackService.of(8))
    }
}
