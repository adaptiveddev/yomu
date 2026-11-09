package app.yomu.feature.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A saved spot in an audiobook: [track] and [positionMs] within it, with an optional [note]. */
data class Bookmark(val track: Int, val positionMs: Long, val note: String = "", val createdAt: Long = 0)

/** Per-book player settings, kept under `"audio"` in the book's reader settings JSON. */
data class AudioSettings(
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val skipBackSec: Int = DEFAULT_SKIP_BACK,
    val skipForwardSec: Int = DEFAULT_SKIP_FORWARD,
    val bookmarks: List<Bookmark> = emptyList(),
) {

    /** [json] with these settings in place of the old ones; other keys stay. */
    fun merge(json: String?): String {
        val old = json?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        val audio = buildJsonObject {
            put("speed", speed)
            put("skipSilence", skipSilence)
            put("skipBack", skipBackSec)
            put("skipForward", skipForwardSec)
            put("bookmarks", encodeBookmarks(bookmarks))
        }
        return JsonObject(old + ("audio" to audio)).toString()
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f
        const val DEFAULT_SKIP_BACK = 10
        const val DEFAULT_SKIP_FORWARD = 30
        val SKIP_BACK_OPTIONS = listOf(5, 10, 15, 30)
        val SKIP_FORWARD_OPTIONS = listOf(10, 15, 30, 60)

        fun of(json: String?): AudioSettings {
            val audio = json?.let { runCatching { Json.parseToJsonElement(it).jsonObject["audio"]?.jsonObject }.getOrNull() } ?: return AudioSettings()
            return AudioSettings(
                speed = audio["speed"]?.jsonPrimitive?.floatOrNull?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f,
                skipSilence = audio["skipSilence"]?.jsonPrimitive?.booleanOrNull ?: false,
                skipBackSec = audio["skipBack"]?.jsonPrimitive?.intOrNull?.takeIf { it in SKIP_BACK_OPTIONS } ?: DEFAULT_SKIP_BACK,
                skipForwardSec = audio["skipForward"]?.jsonPrimitive?.intOrNull?.takeIf { it in SKIP_FORWARD_OPTIONS } ?: DEFAULT_SKIP_FORWARD,
                bookmarks = decodeBookmarks(audio["bookmarks"]?.let { runCatching { it.jsonArray }.getOrNull() }),
            )
        }

        fun encodeBookmarks(list: List<Bookmark>): JsonArray = JsonArray(
            list.map {
                buildJsonObject {
                    put("track", it.track)
                    put("ms", it.positionMs)
                    put("note", it.note)
                    put("at", it.createdAt)
                }
            },
        )

        fun decodeBookmarks(array: JsonArray?): List<Bookmark> = array.orEmpty().mapNotNull { e ->
            val o = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            val track = o["track"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val ms = o["ms"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (track < 0 || ms < 0) return@mapNotNull null
            Bookmark(track, ms, o["note"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["at"]?.jsonPrimitive?.longOrNull ?: 0)
        }.sortedWith(compareBy({ it.track }, { it.positionMs }))

        fun decodeBookmarks(json: String?): List<Bookmark> =
            decodeBookmarks(json?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() })
    }
}
