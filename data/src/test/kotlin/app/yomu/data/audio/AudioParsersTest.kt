package app.yomu.data.audio

import app.yomu.data.format.FormatDetector
import app.yomu.domain.model.AudioChapter
import app.yomu.domain.model.AudioOrigin
import app.yomu.domain.model.AudioTrack
import app.yomu.domain.model.BookFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory

class AudioParsersTest {
    @Test
    fun youtubeLinks() {
        assertEquals(YouTubeRef("dQw4w9WgXcQ", null), YouTubeClient.parseUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"))
        assertEquals(YouTubeRef("dQw4w9WgXcQ", null), YouTubeClient.parseUrl("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals(YouTubeRef("dQw4w9WgXcQ", null), YouTubeClient.parseUrl("youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(YouTubeRef(null, "PLabcdefgh123"), YouTubeClient.parseUrl("https://m.youtube.com/playlist?list=PLabcdefgh123"))
        assertEquals(YouTubeRef("dQw4w9WgXcQ", "PLabcdefgh123"), YouTubeClient.parseUrl("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabcdefgh123"))
        assertNull(YouTubeClient.parseUrl("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeClient.parseUrl("https://www.youtube.com/feed/subscriptions"))
    }

    @Test
    fun youtubeChannelLinks() {
        assertEquals(YouTubeRef(null, null, "https://www.youtube.com/@LibriVox"), YouTubeClient.parseUrl("https://m.youtube.com/@LibriVox/videos"))
        assertEquals(YouTubeRef(null, null, "https://www.youtube.com/channel/UCabc"), YouTubeClient.parseUrl("youtube.com/channel/UCabc"))
        assertEquals(YouTubeRef(null, null, "https://www.youtube.com/c/Name"), YouTubeClient.parseUrl("https://www.youtube.com/c/Name"))
        assertNull(YouTubeClient.parseUrl("https://www.youtube.com/channel/"))
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
              {"lockupViewModel":{"contentId":"ccccccccccc","contentType":"LOCKUP_CONTENT_TYPE_VIDEO","metadata":{"lockupMetadataViewModel":{"title":{"content":"Chapter 3"}}},"contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"1:02:03"}}]}}]}}}}
            ]}}
            """,
        )
        val tracks = YouTubeClient.parsePlaylistVideos(page)
        assertEquals(listOf("Chapter 1", "Chapter 3"), tracks.map { it.title })
        assertEquals("youtube:aaaaaaaaaaa", tracks.first().uri)
        assertEquals(listOf(125_000L, 3_723_000L), tracks.map { it.durationMs })
    }

    @Test
    fun youtubeKeepsTheOriginalVoiceOfDubbedVideos() {
        val response = Json.parseToJsonElement(
            """
            {"streamingData":{"adaptiveFormats":[
              {"itag":140,"url":"https://v.googlevideo.com/de","mimeType":"audio/mp4","bitrate":130000,"audioTrack":{"displayName":"German","audioIsDefault":false}},
              {"itag":140,"url":"https://v.googlevideo.com/en","mimeType":"audio/mp4","bitrate":129000,"audioTrack":{"displayName":"English original","audioIsDefault":true}},
              {"itag":251,"url":"https://v.googlevideo.com/en-opus","mimeType":"audio/webm","bitrate":140000,"audioTrack":{"displayName":"English original","audioIsDefault":true}}
            ]}}
            """,
        ).jsonObject
        assertEquals(listOf("en", "en-opus"), YouTubeClient.candidates(response, emptyMap()).map { it.url.substringAfterLast('/') })
    }

    @Test
    fun youtubeOfficialChapters() {
        val bar = Json.parseToJsonElement(
            """
            {"playerOverlays":{"x":{"multiMarkersPlayerBarRenderer":{"markersMap":[
              {"key":"HEATSEEKER","value":{"heatmap":{}}},
              {"key":"DESCRIPTION_CHAPTERS","value":{"chapters":[
                {"chapterRenderer":{"title":{"simpleText":"Intro"},"timeRangeStartMillis":0}},
                {"chapterRenderer":{"title":{"simpleText":"Chapter 1"},"timeRangeStartMillis":95000}}
              ]}}
            ]}}},
             "contents":{"videoPrimaryInfoRenderer":{"title":{"runs":[{"text":"The Book"}]}},
               "videoSecondaryInfoRenderer":{"attributedDescription":{"content":"About it"}}}}
            """,
        )
        assertEquals(listOf(0L to "Intro", 95_000L to "Chapter 1"), YouTubeClient.parseChapters(bar).map { it.startMs to it.title })
        assertEquals("The Book" to "About it", YouTubeClient.parseNextDetails(bar))

        val panel = Json.parseToJsonElement(
            """
            {"engagementPanels":[
              {"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-macro-markers-auto-chapters","content":{"items":[
                {"macroMarkersListItemRenderer":{"title":{"simpleText":"Start"},"timeDescription":{"simpleText":"0:00"},"onTap":{"watchEndpoint":{"startTimeSeconds":0}}}},
                {"macroMarkersListItemRenderer":{"title":{"simpleText":"Middle"},"timeDescription":{"simpleText":"12:30"}}}
              ]}}},
              {"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-key-moments","content":{"items":[
                {"macroMarkersListItemRenderer":{"title":{"simpleText":"Moment"},"timeDescription":{"simpleText":"3:00"}}}
              ]}}}
            ]}
            """,
        )
        assertEquals(listOf(0L to "Start", 750_000L to "Middle"), YouTubeClient.parseChapters(panel).map { it.startMs to it.title })
        assertTrue(YouTubeClient.parseChapters(Json.parseToJsonElement("{}")).isEmpty())
    }

    @Test
    fun youtubePlaylistSkipsDeletedVideosAndReadsBadges() {
        val page = Json.parseToJsonElement(
            """
            {"contents":{"playlistVideoListRenderer":{"contents":[
              {"playlistVideoRenderer":{"videoId":"ddddddddddd","title":{"runs":[{"text":"[Deleted video]"}]}}},
              {"playlistVideoRenderer":{"videoId":"eeeeeeeeeee","title":{"runs":[{"text":"[Bonus] Live"}]},"lengthSeconds":"60"}}
            ],"continuations":[{"nextContinuationData":{"continuation":"NEXT"}}]},
            "y":{"lockupViewModel":{"contentId":"fffffffffff","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
              "contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"1:02:03"}}]}}]}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Part 2"}}}}}},
             "header":{"playlistHeaderRenderer":{"title":{"runs":[{"text":"My book"}]}}}}
            """,
        )
        val tracks = YouTubeClient.parsePlaylistVideos(page)
        assertEquals(listOf("[Bonus] Live", "Part 2"), tracks.map { it.title })
        assertEquals(listOf(60_000L, 3_723_000L), tracks.map { it.durationMs })
        assertEquals("NEXT", YouTubeClient.continuation(page))
        assertEquals("My book", YouTubeClient.playlistTitle(page))
    }

    @Test
    fun youtubeSearchResults() {
        val page = Json.parseToJsonElement(
            """
            {"contents":[
              {"compactVideoRenderer":{"videoId":"v2hg3ZjL-8E","title":{"runs":[{"text":"A book"}]},"lengthText":{"runs":[{"text":"11:10:40"}]},
                "shortBylineText":{"runs":[{"text":"Reader"}]}}},
              {"compactPlaylistRenderer":{"playlistId":"PLxyz123456","title":{"runs":[{"text":"A series"}]},"videoCountShortText":{"runs":[{"text":"24"}]},
                "shortBylineText":{"runs":[{"text":"Channel"}]},"thumbnail":{"thumbnails":[{"url":"//i.ytimg.com/small.jpg"},{"url":"https://i.ytimg.com/big.jpg"}]}}},
              {"compactVideoRenderer":{"videoId":"notitle0000"}}
            ],"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"T2"}}}}
            """,
        )
        val items = YouTubeClient.parseSearch(page)
        assertEquals(2, items.size)
        val video = items.first { !it.isPlaylist }
        assertEquals(40_240_000L, video.durationMs)
        assertEquals("Reader", video.author)
        assertEquals("https://www.youtube.com/watch?v=v2hg3ZjL-8E", video.url)
        val list = items.first { it.isPlaylist }
        assertEquals(24, list.videoCount)
        assertEquals("https://i.ytimg.com/big.jpg", list.thumbnailUrl)
        assertEquals("https://www.youtube.com/playlist?list=PLxyz123456", list.url)
        assertEquals("T2", YouTubeClient.continuation(page))
    }

    @Test
    fun offlineCopies() {
        assertEquals("m4a", AudiobookDownloader.extension("audio/mp4", "youtube:abc"))
        assertEquals("webm", AudiobookDownloader.extension("audio/webm; codecs=\"opus\"", "youtube:abc"))
        assertEquals("mp3", AudiobookDownloader.extension(null, "https://example.com/ep1.mp3?token=1"))
        assertEquals("audio", AudiobookDownloader.extension(null, "https://example.com/stream"))
        assertTrue(AudiobookDownloader.isRemote("youtube:abc"))
        assertTrue(AudiobookDownloader.isRemote("https://example.com/a.mp3"))
        assertFalse(AudiobookDownloader.isRemote("file:///data/a.mp3"))

        val dir = createTempDirectory().toFile()
        val copy = java.io.File(dir, "0001.m4a").apply { writeText("x") }
        val track = AudioTrack("One", "youtube:abc", file = "file://" + copy.absolutePath)
        assertEquals(track.file, AudiobookStore.playableUri(track))
        copy.delete()
        // A copy that is gone streams again.
        assertEquals("youtube:abc", AudiobookStore.playableUri(track))
        assertEquals("book.files", AudiobookStore.downloadDirOf(java.io.File(dir, "book.yaudio")).name)
        dir.deleteRecursively()
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
        assertEquals(YouTubeResult("A4TU2h_rDlM", false, "Complete Sherlock Holmes", "Gates of Imagination", YouTubeClient.thumbnail("A4TU2h_rDlM"), durationMs = 234_868_000L), video)
        assertEquals(YouTubeRef("A4TU2h_rDlM", null), video.ref)
        val list = results[1]
        assertTrue(list.isPlaylist)
        assertEquals("Magpie Audio", list.author)
        assertEquals(77, list.videoCount)
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

    @Test
    fun youtubeCatalogTitlesFromTheLibraryStillOfferListening() = kotlinx.coroutines.runBlocking {
        // The library keeps catalog titles without their links: the title page must still get "Listen online".
        val source = YouTubeSource(YouTubeClient { okhttp3.OkHttpClient() })
        val stored = app.yomu.source.SItem(url = "https://www.youtube.com/watch?v=A4TU2h_rDlM", title = "Atomic Habits")
        val links = source.getDownloadLinks(stored)
        assertEquals(listOf("https://www.youtube.com/watch?v=A4TU2h_rDlM"), links.map { it.url })
        assertTrue(links.single().isAudioStream)
        assertTrue(source.getDownloadLinks(stored.copy(url = "https://example.com/x")).isEmpty())
    }
}
