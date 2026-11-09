package app.yomu.feature.audio

import app.yomu.domain.model.AudioNavEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PlayerMathTest {
    private val marks = listOf(
        AudioNavEntry(0, 0, "One", isMark = true),
        AudioNavEntry(0, 60_000, "Two", isMark = true),
        AudioNavEntry(1, 0, "Three", isMark = false),
    )

    @Test
    fun spanEndsAtNextMarkOfTheSameTrack() {
        assertEquals(ChapterSpan(60_000, 100_000), PlayerMath.span(marks, 1, 100_000))
        assertEquals(ChapterSpan(0, 60_000), PlayerMath.span(marks, 0, 100_000))
        assertEquals(ChapterSpan(0, 50_000), PlayerMath.span(marks, 2, 50_000))
    }

    @Test
    fun progressAndSeekAreRelativeToTheChapter() {
        val span = ChapterSpan(60_000, 100_000)
        assertEquals(0.25f, span.progress(70_000), 0.001f)
        assertEquals(80_000L, span.positionAt(0.5f))
        assertEquals(0f, ChapterSpan(0, 0).progress(5_000))
    }

    @Test
    fun lengthsAreZeroWhereTheTrackIsUnknown() {
        assertEquals(listOf(60_000L, 40_000L, 0L), PlayerMath.lengths(marks, listOf(100_000, 0)))
    }

    @Test
    fun bookProgressNeedsEveryLength() {
        assertEquals(0.25f, PlayerMath.bookProgress(listOf(100L, 100L), 0, 50)!!, 0.001f)
        assertEquals(null, PlayerMath.bookProgress(listOf(100L, 0L), 0, 50))
    }

    @Test
    fun sleepExtensionRoundsTheRemainderUp() {
        assertEquals(8, PlayerMath.extendedSleepMinutes(sleepAt = 150_000, now = 0, extraMin = 5))
        assertEquals(5, PlayerMath.extendedSleepMinutes(sleepAt = 0, now = 10, extraMin = 5))
    }

    @Test
    fun bookmarksAndSkipIntervalsSurviveARoundTrip() {
        val marks = listOf(Bookmark(1, 5_000, "quote", 99), Bookmark(0, 1_000))
        val json = AudioSettings(skipBackSec = 15, skipForwardSec = 60, bookmarks = marks).merge("""{"fontSize":18}""")
        val back = AudioSettings.of(json)
        assertEquals(15, back.skipBackSec)
        assertEquals(60, back.skipForwardSec)
        assertEquals(listOf(Bookmark(0, 1_000), Bookmark(1, 5_000, "quote", 99)), back.bookmarks)
        assertNotNull(Json.parseToJsonElement(json).jsonObject["fontSize"])
    }

    @Test
    fun invalidIntervalsAndBookmarksAreDropped() {
        val s = AudioSettings.of("""{"audio":{"skipBack":7,"skipForward":"x","bookmarks":[{"track":-1,"ms":5},{"track":0},"junk",{"track":2,"ms":9}]}}""")
        assertEquals(AudioSettings.DEFAULT_SKIP_BACK, s.skipBackSec)
        assertEquals(AudioSettings.DEFAULT_SKIP_FORWARD, s.skipForwardSec)
        assertEquals(listOf(Bookmark(2, 9)), s.bookmarks)
        assertEquals(emptyList<Bookmark>(), AudioSettings.decodeBookmarks("nope"))
    }

    @Test
    fun chapterSearchMatchesEveryWordIgnoringCase() {
        assertEquals(listOf(0, 1, 2), PlayerMath.search(marks, "  "))
        assertEquals(listOf(1), PlayerMath.search(marks, "two"))
        assertEquals(listOf(0, 1), PlayerMath.search(marks, "O"))
        assertEquals(emptyList<Int>(), PlayerMath.search(marks, "one two"))
    }
}
