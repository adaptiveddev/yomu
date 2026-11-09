package app.yomu.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioTest {
    @Test
    fun chapterMarksInLeadingAndTrailingForms() {
        val text = """
            Full audiobook. Chapters:
            00:00 Intro
            0:12:05 - Chapter 1: The Start
            1:02:03 Chapter 2
            Epilogue (1:30:00)
            Thanks for listening!
        """.trimIndent()
        val marks = ChapterMarks.parse(text)
        assertEquals(
            listOf(
                AudioChapter("Intro", 0),
                AudioChapter("Chapter 1: The Start", 725_000),
                AudioChapter("Chapter 2", 3_723_000),
                AudioChapter("Epilogue", 5_400_000),
            ),
            marks,
        )
    }

    @Test
    fun chapterMarksNeedZeroStartThreeMarksAndOrder() {
        assertTrue(ChapterMarks.parse("0:00 a\n1:00 b").isEmpty())
        assertTrue(ChapterMarks.parse("0:10 a\n1:00 b\n2:00 c").isEmpty())
        assertTrue(ChapterMarks.parse("0:00 a\n2:00 b\n1:00 c").isEmpty())
        // A mark past the end of the video is not a chapter list of this video.
        assertTrue(ChapterMarks.parse("0:00 a\n1:00 b\n2:00 c", durationMs = 90_000).isEmpty())
        assertEquals(3, ChapterMarks.parse("[0:00] a\n[1:00] b\n• 2:00 | c").size)
    }

    @Test
    fun parseTimeRejectsInvalidValues() {
        assertEquals(83_000L, ChapterMarks.parseTime("1:23"))
        assertEquals(3_723_000L, ChapterMarks.parseTime("01:02:03"))
        assertEquals(null, ChapterMarks.parseTime("1:75"))
        assertEquals(null, ChapterMarks.parseTime("abc"))
    }

    @Test
    fun totalProgressUsesTimeWhenAllLengthsAreKnown() {
        assertEquals(0.5f, AudioPositions.total(listOf(1000, 3000), 1, 1000), 1e-4f)
        // Unknown lengths: each track weighs the same.
        assertEquals(0.75f, AudioPositions.total(listOf(0, 1000), 1, 500), 1e-4f)
        assertEquals(0.5f, AudioPositions.total(listOf(0, 0), 1, 500), 1e-4f)
    }

    @Test
    fun locatorRoundTripsPosition() {
        val l = AudioPositions.locator(2, 65_000, 130_000, 0.4f, "Two")
        assertEquals(2, l.chapter)
        assertEquals(0.5f, l.progression, 1e-4f)
        assertEquals(65_000L, AudioPositions.positionMs(l))
    }

    @Test
    fun navigationExpandsChapterMarks() {
        val tracks = listOf(
            AudioTrack("One", "file:///1.mp3"),
            AudioTrack("Two", "youtube:abc", chapters = listOf(AudioChapter("A", 0), AudioChapter("B", 60_000))),
        )
        val nav = AudioPositions.navigation(tracks)
        assertEquals(listOf("One", "A", "B"), nav.map { it.title })
        assertEquals(2, AudioPositions.current(nav, 1, 61_000))
        assertEquals(1, AudioPositions.current(nav, 1, 59_000))
        assertEquals(0, AudioPositions.current(nav, 0, 10))
    }

    @Test
    fun remainingNeedsKnownLengthsAfterTheCurrentTrack() {
        assertEquals(70_000L, AudioPositions.remaining(listOf(60_000, 50_000, 30_000), 1, 10_000))
        assertEquals(null, AudioPositions.remaining(listOf(60_000, 50_000, 0), 1, 10_000))
        assertEquals(0L, AudioPositions.remaining(listOf(60_000), 0, 90_000))
        assertEquals(null, AudioPositions.remaining(listOf(60_000), 3, 0))
    }

    @Test
    fun youtubeUris() {
        assertEquals("dQw4w9WgXcQ", AudiobookManifest.youtubeId(AudiobookManifest.youtubeUri("dQw4w9WgXcQ")))
        assertEquals(null, AudiobookManifest.youtubeId("https://example.com/a.mp3"))
        assertTrue(AudioFiles.isAudio("Book/01 - Intro.MP3"))
        assertTrue(!AudioFiles.isAudio("cover.jpg"))
        assertTrue(BookFormat.AUDIO.isAudio && "m4b" in BookFormat.AUDIO.extensions && "yaudio" in BookFormat.AUDIO.extensions)
    }
}
