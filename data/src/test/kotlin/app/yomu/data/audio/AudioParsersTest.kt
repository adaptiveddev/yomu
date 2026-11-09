package app.yomu.data.audio

import app.yomu.data.format.FormatDetector
import app.yomu.domain.model.AudioOrigin
import app.yomu.domain.model.BookFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioParsersTest {
    @Test
    fun youtubeLinks() {
        assertEquals(YouTubeRef("dQw4w9WgXcQ", null), YouTubeClient.parseUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"))
        assertEquals(YouTubeRef("dQw4w9WgXcQ", null), YouTubeClient.parseUrl("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals(YouTubeRef("dQw4w9WgXcQ", null), YouTubeClient.parseUrl("youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(YouTubeRef(null, "PLabcdefgh123"), YouTubeClient.parseUrl("https://m.youtube.com/playlist?list=PLabcdefgh123"))
        assertEquals(YouTubeRef("dQw4w9WgXcQ", "PLabcdefgh123"), YouTubeClient.parseUrl("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabcdefgh123"))
        assertNull(YouTubeClient.parseUrl("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeClient.parseUrl("https://www.youtube.com/@channel"))
    }

    @Test
    fun youtubeStreamsPreferAudioOnlyThenMuxedThenHls() {
        val response = Json.parseToJsonElement(
            """
            {"streamingData":{
              "formats":[{"itag":18,"url":"https://v.googlevideo.com/18","mimeType":"video/mp4"}],
              "adaptiveFormats":[
                {"itag":251,"url":"https://v.googlevideo.com/251","mimeType":"audio/webm; codecs=\"opus\"","bitrate":160000},
                {"itag":140,"url":"https://v.googlevideo.com/140","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000},
                {"itag":139,"url":"https://v.googlevideo.com/139drc","mimeType":"audio/mp4","bitrate":50000,"xtags":"drc=1"},
                {"itag":137,"url":"https://v.googlevideo.com/137","mimeType":"video/mp4","bitrate":4000000},
                {"itag":141,"signatureCipher":"s=…","mimeType":"audio/mp4","bitrate":256000}
              ],
              "hlsManifestUrl":"https://manifest.googlevideo.com/hls"}}
            """,
        ).jsonObject
        val list = YouTubeClient.candidates(response, mapOf("User-Agent" to "ua"))
        assertEquals(listOf("140", "251", "18", "hls"), list.map { it.url.substringAfterLast('/') })
        assertTrue(list.last().isHls)
        assertEquals("ua", list.first().headers["User-Agent"])
    }

    @Test
    fun youtubePlaylistPages() {
        val page = Json.parseToJsonElement(
            """
            {"contents":{"x":[
              {"playlistVideoRenderer":{"videoId":"aaaaaaaaaaa","title":{"runs":[{"text":"Chapter "},{"text":"1"}]},"lengthSeconds":"125"}},
              {"playlistVideoRenderer":{"videoId":"bbbbbbbbbbb","title":{"simpleText":"Chapter 2"},"isPlayable":false}},
              {"lockupViewModel":{"contentId":"ccccccccccc","contentType":"LOCKUP_CONTENT_TYPE_VIDEO","metadata":{"lockupMetadataViewModel":{"title":{"content":"Chapter 3"}}}}}
            ]}}
            """,
        )
        val tracks = YouTubeClient.parsePlaylistVideos(page)
        assertEquals(listOf("Chapter 1", "Chapter 3"), tracks.map { it.title })
        assertEquals("youtube:aaaaaaaaaaa", tracks.first().uri)
        assertEquals(125_000L, tracks.first().durationMs)
    }

    @Test
    fun podcastFeedNewestFirstIsReversed() {
        val xml = """<?xml version="1.0"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
            <channel>
              <title>My Show</title><itunes:author>Host</itunes:author><language>ru-RU</language>
              <itunes:image href="https://example.com/cover.jpg"/>
              <item><title>Ep 2</title><pubDate>Tue, 02 Jan 2024 10:00:00 GMT</pubDate><itunes:duration>1:02:03</itunes:duration>
                <enclosure url="https://example.com/2.mp3" type="audio/mpeg"/></item>
              <item><title>Ep 1</title><pubDate>Mon, 01 Jan 2024 10:00:00 GMT</pubDate><itunes:duration>3906</itunes:duration>
                <enclosure url="https://example.com/1.mp3" type="audio/mpeg"/></item>
              <item><title>No audio</title><link>https://example.com/post</link></item>
            </channel></rss>"""
        val m = PodcastFeed.parse(xml, "https://example.com/feed")!!
        assertEquals("My Show", m.title)
        assertEquals(listOf("Host"), m.authors)
        assertEquals("ru", m.language)
        assertEquals("https://example.com/cover.jpg", m.coverUrl)
        assertEquals(AudioOrigin.PODCAST, m.origin)
        assertEquals(listOf("Ep 1", "Ep 2"), m.tracks.map { it.title })
        assertEquals(listOf(3_906_000L, 3_723_000L), m.tracks.map { it.durationMs })
        assertTrue(PodcastFeed.looksLikeFeed(xml))
    }

    @Test
    fun linksAndZips() {
        assertEquals("https://youtu.be/abcdefghijk", AudiobookStore.extractUrl("Look at this: https://youtu.be/abcdefghijk."))
        assertEquals("https://youtu.be/abcdefghijk", AudiobookStore.extractUrl("youtu.be/abcdefghijk"))
        assertNull(AudiobookStore.extractUrl("just some words"))
        assertTrue(AudiobookStore.isAudioZip(listOf("book/01.mp3", "book/02.mp3", "book/cover.jpg")))
        assertFalse(AudiobookStore.isAudioZip(listOf("001.jpg", "002.jpg", "003.jpg", "theme.mp3")))
        assertFalse(AudiobookStore.isAudioZip(listOf("chapter.txt")))
        assertEquals("03 - Three", AudiobookStore.trackTitle("CD1/03_-_Three.mp3".replace("_-_", " - ")))
        assertTrue(AudiobookStore.isManifest("file:///data/audiobooks/x.yaudio"))
    }

    @Test
    fun audioSignatures() {
        fun bytes(vararg s: Any): ByteArray = s.flatMap { v -> if (v is String) v.toByteArray(Charsets.ISO_8859_1).toList() else listOf((v as Int).toByte()) }.toByteArray()
        assertEquals(BookFormat.AUDIO, FormatDetector.detect(bytes("ID3", 4, 0, 0), "a.bin"))
        assertEquals(BookFormat.AUDIO, FormatDetector.detect(bytes(0, 0, 0, 0x20, "ftypM4B ", 0, 0), "book"))
        assertEquals(BookFormat.AUDIO, FormatDetector.detect(bytes("OggS", 0, 2), null))
        assertEquals(BookFormat.AUDIO, FormatDetector.detect(bytes(0xFF, 0xFB, 0x90, 0x64), "track.mp3"))
        assertEquals(BookFormat.AUDIO, FormatDetector.detect("{\"title\":\"x\"}".toByteArray(), "book.yaudio"))
        // An mp4 video is not an audiobook, and bare frame-sync bytes need an audio name.
        assertEquals(BookFormat.UNKNOWN, FormatDetector.detect(bytes(0, 0, 0, 0x20, "ftypisom", 0, 1, 2, 3), "clip.mp4"))
        assertFalse(FormatDetector.isAudio(bytes(0xFF, 0xFB, 0x90, 0x64), "file.bin"))
    }

    // Shapes below follow live InnerTube answers of 2026-11 (trimmed).

    @Test
    fun youtubeWatchPageGivesExactChaptersAndDetails() {
        val page = Json.parseToJsonElement(
            """
            {"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
              {"videoPrimaryInfoRenderer":{"title":{"runs":[{"text":"The 12 Best Sherlock Holmes Adventures"}]}}},
              {"videoSecondaryInfoRenderer":{"owner":{"videoOwnerRenderer":{"title":{"runs":[{"text":"Magpie Audio"}]}}},
                "attributedDescription":{"content":"Timestamps\n0:00 The Speckled Band\n57:32 The Red-Headed League"}}}
            ]}}}},
            "playerOverlays":{"playerOverlayRenderer":{"decoratedPlayerBarRenderer":{"decoratedPlayerBarRenderer":{"playerBar":{"multiMarkersPlayerBarRenderer":{"markersMap":[{"value":{"chapters":[
              {"chapterRenderer":{"title":{"simpleText":"The Speckled Band"},"timeRangeStartMillis":0}},
              {"chapterRenderer":{"title":{"simpleText":"The Red-Headed League"},"timeRangeStartMillis":3452000}},
              {"chapterRenderer":{"title":{"simpleText":"The Dancing Men"},"timeRangeStartMillis":6590000}}
            ]}}]}}}}}},
            "engagementPanels":[{"macroMarkersListItemRenderer":{"title":{"simpleText":"The Speckled Band"},"timeDescription":{"simpleText":"0:00"}}}]}
            """,
        )
        val info = YouTubeClient.parseNext(page)
        assertEquals("The 12 Best Sherlock Holmes Adventures", info.title)
        assertEquals("Magpie Audio", info.author)
        assertTrue(info.description!!.startsWith("Timestamps"))
        assertEquals(listOf(0L, 3_452_000L, 6_590_000L), info.chapters.map { it.startMs })
        assertEquals("The Dancing Men", info.chapters.last().title)

        val listedOnly = Json.parseToJsonElement(
            """{"x":[
              {"macroMarkersListItemRenderer":{"title":{"runs":[{"text":"Intro"}]},"timeDescription":{"runs":[{"text":"0:00"}]}}},
              {"macroMarkersListItemRenderer":{"title":{"simpleText":"Part 2"},"timeDescription":{"simpleText":"1:49:50"}}}
            ]}""",
        )
        assertEquals(listOf(0L, 6_590_000L), YouTubeClient.parseNext(listedOnly).chapters.map { it.startMs })
    }

    @Test
    fun youtubeSearchKeepsVideosAndPlaylists() {
        val page = Json.parseToJsonElement(
            """
            {"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
              {"videoRenderer":{"videoId":"A4TU2h_rDlM","title":{"runs":[{"text":"Complete Sherlock Holmes"}]},
                "longBylineText":{"runs":[{"text":"Gates of Imagination"}]},"lengthText":{"simpleText":"65:14:28"}}},
              {"videoRenderer":{"videoId":"liveliveliv","title":{"runs":[{"text":"Live radio"}]}}},
              {"lockupViewModel":{"contentId":"PLZrCyG_xOH1jC5siVt75wbGGvPRJleTRy","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
                "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/9D0bGia4QrI/hq720.jpg"}]},
                  "overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"77 videos"}}]}}]}}}},
                "metadata":{"lockupMetadataViewModel":{"title":{"content":"Sherlock Holmes - The Complete Canon"},
                  "metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Magpie Audio"}},{"text":{"content":"Playlist"}}]}]}}}}}},
              {"shortsLockupViewModel":{"entityId":"shorts"}},
              {"videoRenderer":{"videoId":"A4TU2h_rDlM","title":{"runs":[{"text":"Duplicate"}]},"lengthText":{"simpleText":"1:00"}}}
            ]}}]}}}
            """,
        )
        val results = YouTubeClient.parseSearch(page)
        assertEquals(listOf("A4TU2h_rDlM", "PLZrCyG_xOH1jC5siVt75wbGGvPRJleTRy"), results.map { it.id })
        val video = results[0]
        assertEquals(YouTubeResult("A4TU2h_rDlM", false, "Complete Sherlock Holmes", "Gates of Imagination", "65:14:28", YouTubeClient.thumbnail("A4TU2h_rDlM")), video)
        assertEquals(YouTubeRef("A4TU2h_rDlM", null), video.ref)
        val list = results[1]
        assertTrue(list.isPlaylist)
        assertEquals("Magpie Audio", list.author)
        assertEquals("77 videos", list.detail)
        assertEquals("https://i.ytimg.com/vi/9D0bGia4QrI/hq720.jpg", list.thumbnailUrl)
        assertEquals(YouTubeRef(null, "PLZrCyG_xOH1jC5siVt75wbGGvPRJleTRy"), list.ref)
    }

    @Test
    fun youtubePlaylistLockupLengthsComeFromBadges() {
        val page = Json.parseToJsonElement(
            """{"x":[{"lockupViewModel":{"contentId":"ddddddddddd","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
              "contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"50:43"}}]}}]}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"A Scandal in Bohemia"}}}}}]}""",
        )
        val track = YouTubeClient.parsePlaylistVideos(page).single()
        assertEquals("A Scandal in Bohemia", track.title)
        assertEquals(3_043_000L, track.durationMs)
    }

    @Test
    fun youtubeStreamExpiry() {
        assertEquals(1_794_300_000_000L, YouTubeClient.expiresAt("https://rr1---sn-x.googlevideo.com/videoplayback?expire=1794300000&id=1"))
        assertEquals(1_794_300_000_000L, YouTubeClient.expiresAt("https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1794300000/ei/abc/file/index.m3u8"))
        assertEquals(0L, YouTubeClient.expiresAt("https://example.com/a.mp3"))
    }
}
