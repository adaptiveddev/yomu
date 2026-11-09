package app.yomu.data.audio

import app.yomu.domain.model.AudioChapter
import app.yomu.domain.model.AudioTrack
import app.yomu.domain.model.AudiobookManifest
import app.yomu.domain.model.AudioOrigin
import app.yomu.domain.model.ChapterMarks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException

/** A YouTube address: a video, a playlist, or a video inside a playlist. */
data class YouTubeRef(val videoId: String?, val playlistId: String?)

/** A playable stream of a video: progressive audio or an HLS manifest, with the headers its server expects. */
data class YouTubeStream(val url: String, val mimeType: String?, val isHls: Boolean, val headers: Map<String, String>)

class YouTubeException(val reason: Reason, message: String? = null) : IOException(message ?: reason.name) {
    enum class Reason { NOT_FOUND, UNPLAYABLE, BLOCKED }
}

/**
 * A small client of YouTube's internal API (InnerTube), without signature deciphering: it asks the player of clients
 * that return direct addresses (see yt-dlp's client table) and keeps the first stream that answers. YouTube changes
 * these rules often, so [CLIENTS] is the single place to update.
 */
class YouTubeClient(private val client: () -> OkHttpClient) {

    private data class Client(val name: String, val id: Int, val version: String, val userAgent: String, val extra: Map<String, Any> = emptyMap())

    suspend fun video(videoId: String): AudiobookManifest = withContext(Dispatchers.IO) {
        val player = runCatching { player(videoId).second }.getOrNull()
        // The watch page's data: official chapters, and the title when the player asks for a sign-in.
        val next = runCatching { post("next", WEB, buildJsonObject { put("videoId", videoId) }) }.onFailure { Timber.d(it, "YouTube next") }.getOrNull()
        val nextDetails = next?.let(::parseNextDetails)
        val details = player?.obj("videoDetails")
        val fallback by lazy { oembed(videoId) }
        val title = details?.str("title") ?: nextDetails?.first ?: fallback?.first ?: throw YouTubeException(YouTubeException.Reason.NOT_FOUND)
        val author = details?.str("author")
            ?: next?.let { n -> findAll(n, "videoOwnerRenderer").firstNotNullOfOrNull { text(it["title"]) } }
            ?: fallback?.second
        val durationMs = (details?.str("lengthSeconds")?.toLongOrNull() ?: 0) * 1000
        val description = details?.str("shortDescription") ?: nextDetails?.second
        val chapters = next?.let(::parseChapters).orEmpty().ifEmpty { ChapterMarks.parse(description, durationMs) }
        AudiobookManifest(
            title = title,
            authors = listOfNotNull(author),
            description = description,
            coverUrl = thumbnail(videoId),
            origin = AudioOrigin.YOUTUBE,
            sourceUrl = "https://www.youtube.com/watch?v=$videoId",
            tracks = listOf(AudioTrack(title, AudiobookManifest.youtubeUri(videoId), durationMs, chapters)),
        )
    }

    /** The videos of a playlist (at most [limit]); a playlist's videos are its chapters. */
    suspend fun playlist(playlistId: String, limit: Int = MAX_PLAYLIST): AudiobookManifest = withContext(Dispatchers.IO) {
        val first = post("browse", WEB, buildJsonObject { put("browseId", "VL$playlistId") })
        val title = findAll(first, "playlistMetadataRenderer").firstNotNullOfOrNull { it.str("title") }
            ?: findAll(first, "pageHeaderViewModel").firstNotNullOfOrNull { it.obj("title")?.obj("dynamicTextViewModel")?.obj("text")?.str("content") }
            ?: throw YouTubeException(YouTubeException.Reason.NOT_FOUND)
        val author = findAll(first, "videoOwnerRenderer").firstNotNullOfOrNull { text(it["title"]) }
        val videos = LinkedHashMap<String, AudioTrack>()
        var page: JsonElement = first
        repeat(MAX_PAGES) {
            parsePlaylistVideos(page).forEach { videos.putIfAbsent(AudiobookManifest.youtubeId(it.uri)!!, it) }
            if (videos.size >= limit) return@repeat
            val token = findAll(page, "continuationCommand").firstNotNullOfOrNull { it.str("token") } ?: return@repeat
            page = post("browse", WEB, buildJsonObject { put("continuation", token) })
        }
        if (videos.isEmpty()) throw YouTubeException(YouTubeException.Reason.NOT_FOUND)
        AudiobookManifest(
            title = title,
            authors = listOfNotNull(author),
            coverUrl = videos.keys.firstOrNull()?.let(::thumbnail),
            origin = AudioOrigin.YOUTUBE,
            sourceUrl = "https://www.youtube.com/playlist?list=$playlistId",
            tracks = videos.values.take(limit),
        )
    }

    /** A stream address of [videoId] (valid for a few hours). */
    suspend fun stream(videoId: String): YouTubeStream = withContext(Dispatchers.IO) {
        var lastError: YouTubeException? = null
        for (c in CLIENTS) {
            val response = runCatching { postPlayer(c, videoId) }.getOrNull() ?: continue
            val status = response.obj("playabilityStatus")?.str("status")
            if (status != "OK") {
                lastError = YouTubeException(
                    if (status == "LOGIN_REQUIRED") YouTubeException.Reason.BLOCKED else YouTubeException.Reason.UNPLAYABLE,
                    response.obj("playabilityStatus")?.str("reason"),
                )
                continue
            }
            val headers = mapOf("User-Agent" to c.userAgent)
            for (candidate in candidates(response, headers)) {
                if (reachable(candidate)) return@withContext candidate
            }
            lastError = YouTubeException(YouTubeException.Reason.BLOCKED, "No reachable stream from ${c.name}")
        }
        throw lastError ?: YouTubeException(YouTubeException.Reason.BLOCKED)
    }

    private fun player(videoId: String): Pair<Client, JsonObject> {
        var last: Throwable? = null
        for (c in CLIENTS) {
            val r = runCatching { postPlayer(c, videoId) }.onFailure { last = it }.getOrNull() ?: continue
            if (r.obj("videoDetails") != null) return c to r
        }
        throw last ?: YouTubeException(YouTubeException.Reason.NOT_FOUND)
    }

    private fun postPlayer(c: Client, videoId: String): JsonObject = post(
        "player", c,
        buildJsonObject {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        },
    ).jsonObject

    private fun post(endpoint: String, c: Client, body: JsonObject): JsonElement {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", c.name)
                    put("clientVersion", c.version)
                    put("hl", "en")
                    put("gl", "US")
                    c.extra.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) }
                }
            }
            body.forEach { (k, v) -> put(k, v) }
        }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", c.userAgent)
            .header("X-YouTube-Client-Name", c.id.toString())
            .header("X-YouTube-Client-Version", c.version)
            .header("Origin", "https://www.youtube.com")
            .post(Json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON))
            .build()
        return client().newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("YouTube $endpoint: HTTP ${r.code}")
            json.parseToJsonElement(r.body.string())
        }
    }

    private fun reachable(s: YouTubeStream): Boolean = runCatching {
        val request = Request.Builder().url(s.url).apply {
            s.headers.forEach { (k, v) -> header(k, v) }
            if (!s.isHls) header("Range", "bytes=0-1")
        }.build()
        client().newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun oembed(videoId: String): Pair<String, String?>? = runCatching {
        val url = "https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=$videoId"
        client().newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = json.parseToJsonElement(r.body.string()).jsonObject
            (o.str("title") ?: return null) to o.str("author_name")
        }
    }.getOrNull()

    companion object {
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val MAX_PLAYLIST = 500
        private const val MAX_PAGES = 10

        private val WEB = Client(
            "WEB", 1, "2.20260708.01.00",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
        )

        // Clients without a JavaScript player, newest working first (yt-dlp, 2026-08: visionos is the JS-less default).
        private val CLIENTS = listOf(
            Client(
                "VISIONOS", 101, "1.02",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
                mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice17,1", "osName" to "visionOS", "osVersion" to "26.5.23O471"),
            ),
            Client(
                "ANDROID_VR", 28, "1.61.48",
                "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
            ),
            Client(
                "IOS", 5, "21.26.4",
                "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
            ),
        )

        fun thumbnail(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        /** Recognizes youtube.com/watch, youtu.be, shorts, live, music.youtube.com and playlist links. */
        fun parseUrl(text: String): YouTubeRef? {
            val url = text.trim().let { if (it.startsWith("http")) it else "https://$it" }.toHttpUrlOrNull() ?: return null
            val host = url.host.removePrefix("www.").removePrefix("m.").removePrefix("music.")
            val list = url.queryParameter("list")?.takeIf { ID.matches(it) }
            val video = when (host) {
                "youtu.be" -> url.pathSegments.firstOrNull()
                "youtube.com", "youtube-nocookie.com" -> when (url.pathSegments.firstOrNull()) {
                    "watch" -> url.queryParameter("v")
                    "shorts", "live", "embed", "v" -> url.pathSegments.getOrNull(1)
                    "playlist" -> null
                    else -> return null
                }
                else -> return null
            }?.takeIf { ID.matches(it) }
            if (video == null && list == null) return null
            return YouTubeRef(video, list)
        }

        private val ID = Regex("[A-Za-z0-9_-]{6,64}")

        /** Playable streams of a player response, best first: audio-only (AAC, then Opus), the 360p mp4 with sound, HLS. */
        internal fun candidates(response: JsonObject, headers: Map<String, String>): List<YouTubeStream> {
            val streaming = response.obj("streamingData") ?: return emptyList()
            fun formats(key: String) = (streaming[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.str("url") != null }
            val audio = originalLanguage(formats("adaptiveFormats").filter { it.str("mimeType")?.startsWith("audio/") == true })
                // DRC tracks are quieter copies; ExoPlayer plays mp4 everywhere, webm/opus since Android 5.
                .filter { it.str("xtags")?.contains("drc") != true }
                .sortedWith(compareByDescending<JsonObject> { it.str("mimeType")!!.startsWith("audio/mp4") }.thenByDescending { it["bitrate"]?.jsonPrimitive?.intOrNull ?: 0 })
                .map { YouTubeStream(it.str("url")!!, it.str("mimeType")?.substringBefore(';'), false, headers) }
            val muxed = formats("formats").filter { it["itag"]?.jsonPrimitive?.intOrNull == 18 }
                .map { YouTubeStream(it.str("url")!!, "video/mp4", false, headers) }
            val hls = listOfNotNull(streaming.str("hlsManifestUrl")?.let { YouTubeStream(it, "application/x-mpegURL", true, headers) })
            return audio + muxed + hls
        }

        /**
         * Videos with several audio languages (uploaded or automatic dubs) list each as its own formats; the default
         * one is the original voice, which an audiobook wants rather than a dub.
         */
        private fun originalLanguage(formats: List<JsonObject>): List<JsonObject> {
            if (formats.none { it.obj("audioTrack") != null }) return formats
            fun flag(f: JsonObject) = f.obj("audioTrack")?.get("audioIsDefault")?.jsonPrimitive?.booleanOrNull == true
            fun original(f: JsonObject) = f.obj("audioTrack")?.str("displayName")?.contains("original", ignoreCase = true) == true
            return formats.filter(::flag).ifEmpty { formats.filter(::original) }.ifEmpty { formats }
        }

        /**
         * The chapters YouTube shows on the progress bar (written by the uploader or generated), from a `next`
         * response: the player bar's markers map, else the chapters panel.
         */
        internal fun parseChapters(next: JsonElement): List<AudioChapter> {
            val fromBar = findAll(next, "multiMarkersPlayerBarRenderer").flatMap { bar ->
                (bar["markersMap"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .filter { it.str("key") in CHAPTER_KEYS }
                    .flatMap { findAll(it, "chapterRenderer") }
                    .mapNotNull { c ->
                        val start = c["timeRangeStartMillis"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                        AudioChapter(text(c["title"]) ?: return@mapNotNull null, start)
                    }
            }
            val chapters = fromBar.ifEmpty {
                findAll(next, "engagementPanelSectionListRenderer")
                    .filter { p -> (p.str("panelIdentifier") ?: p.str("targetId")).orEmpty().let { "description-chapters" in it || "auto-chapters" in it } }
                    .flatMap { findAll(it, "macroMarkersListItemRenderer") }
                    .mapNotNull { m ->
                        val start = findAll(m, "watchEndpoint").firstNotNullOfOrNull { it["startTimeSeconds"]?.jsonPrimitive?.longOrNull }?.times(1000)
                            ?: text(m["timeDescription"])?.let(ChapterMarks::parseTime) ?: return@mapNotNull null
                        AudioChapter(text(m["title"]) ?: return@mapNotNull null, start)
                    }
            }
            val sorted = chapters.distinctBy { it.startMs }.sortedBy { it.startMs }
            return if (sorted.size >= 2 && sorted.first().startMs == 0L) sorted else emptyList()
        }

        /** Title and description of a video from a `next` response (when the player refused to answer). */
        internal fun parseNextDetails(next: JsonElement): Pair<String?, String?> {
            val title = findAll(next, "videoPrimaryInfoRenderer").firstNotNullOfOrNull { text(it["title"]) }
            val description = findAll(next, "videoSecondaryInfoRenderer").firstNotNullOfOrNull { it.obj("attributedDescription")?.str("content") }
            return title to description
        }

        private val CHAPTER_KEYS = setOf("DESCRIPTION_CHAPTERS", "AUTO_CHAPTERS")

        internal fun parsePlaylistVideos(page: JsonElement): List<AudioTrack> {
            val classic = findAll(page, "playlistVideoRenderer").mapNotNull { r ->
                val id = r.str("videoId") ?: return@mapNotNull null
                if (r["isPlayable"]?.jsonPrimitive?.contentOrNull == "false") return@mapNotNull null
                AudioTrack(text(r["title"]) ?: id, AudiobookManifest.youtubeUri(id), (r.str("lengthSeconds")?.toLongOrNull() ?: 0) * 1000)
            }
            val lockups = findAll(page, "lockupViewModel").mapNotNull { r ->
                if (r.str("contentType")?.contains("VIDEO") != true) return@mapNotNull null
                val id = r.str("contentId") ?: return@mapNotNull null
                val title = r.obj("metadata")?.obj("lockupMetadataViewModel")?.obj("title")?.str("content") ?: id
                // The length is only written on the thumbnail's badge ("12:34").
                val length = findAll(r, "thumbnailBadgeViewModel").firstNotNullOfOrNull { b -> b.str("text")?.trim()?.let(ChapterMarks::parseTime) } ?: 0
                AudioTrack(title, AudiobookManifest.youtubeUri(id), length)
            }
            return classic + lockups
        }

        /** Every object stored under [key] anywhere in [root]. */
        internal fun findAll(root: JsonElement, key: String): List<JsonObject> {
            val out = mutableListOf<JsonObject>()
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonObject -> e.forEach { (k, v) -> if (k == key && v is JsonObject) out += v; walk(v) }
                    is JsonArray -> e.forEach(::walk)
                    else -> Unit
                }
            }
            walk(root)
            return out
        }

        /** Text of a `{simpleText}` or `{runs: [{text}]}` object. */
        private fun text(e: JsonElement?): String? {
            val o = e as? JsonObject ?: return null
            return o.str("simpleText") ?: (o["runs"] as? JsonArray)?.joinToString("") { (it as? JsonObject)?.str("text").orEmpty() }?.takeIf { it.isNotBlank() }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    }
}
