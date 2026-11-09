package app.yomu.data.audio

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Real YouTube (network): `./gradlew :data:testDebugUnitTest --tests '*YouTubeLiveTest*' -Pyomu.liveTests=true`. */
class YouTubeLiveTest {
    private val youtube = YouTubeClient { OkHttpClient() }

    @BeforeEach
    fun live() {
        assumeTrue(System.getProperty("yomu.liveTests") == "true")
    }

    @Test
    fun searchPagesAndPlaylistsPlay() = runBlocking {
        val first = youtube.search("audiobook", YouTubeSearchFilter.LONG_VIDEOS)
        assertTrue(first.items.size >= 5, "videos: ${first.items.size}")
        assertTrue(first.items.all { !it.isPlaylist && it.durationMs > 20 * 60_000 })
        val second = youtube.search("audiobook", YouTubeSearchFilter.LONG_VIDEOS, first.continuation)
        assertTrue(second.items.map { it.id }.any { it !in first.items.map { f -> f.id } })

        val lists = youtube.search("audiobook", YouTubeSearchFilter.PLAYLISTS)
        val big = lists.items.first { (it.videoCount ?: 0) > 40 }
        val book = youtube.playlist(big.id)
        // More than the first page of 20: the continuations work.
        assertTrue(book.tracks.size > 25, "${big.id}: ${book.tracks.size} tracks")
        assertTrue(book.tracks.count { it.durationMs > 0 } > book.tracks.size / 2)
    }

    @Test
    fun videoWithChaptersStreams() = runBlocking {
        val video = youtube.search("audiobook full chapters", YouTubeSearchFilter.LONG_VIDEOS).items
            .firstNotNullOfOrNull { r -> youtube.video(r.id).takeIf { it.tracks.single().chapters.size >= 3 } }
        val track = video!!.tracks.single()
        assertEquals(0L, track.chapters.first().startMs)
        val stream = try {
            youtube.stream(track.uri.substringAfter(':'), progressive = true)
        } catch (e: YouTubeException) {
            // Data-centre networks get YouTube's bot check after a few requests: nothing the app can change.
            assumeTrue(e.reason != YouTubeException.Reason.BLOCKED, "YouTube blocks this network: ${e.message}")
            throw e
        }
        assertFalse(stream.isHls)
        assertTrue((stream.contentLength ?: 0) > 0)
    }
}
