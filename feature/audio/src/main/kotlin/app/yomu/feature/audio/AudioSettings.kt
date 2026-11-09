package app.yomu.feature.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Per-book player settings, kept under `"audio"` in the book's reader settings JSON. */
data class AudioSettings(val speed: Float = 1f, val skipSilence: Boolean = false) {

    /** [json] with these settings in place of the old ones; other keys stay. */
    fun merge(json: String?): String {
        val old = json?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        val audio = buildJsonObject {
            put("speed", speed)
            put("skipSilence", skipSilence)
        }
        return JsonObject(old + ("audio" to audio)).toString()
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f

        fun of(json: String?): AudioSettings {
            val audio = json?.let { runCatching { Json.parseToJsonElement(it).jsonObject["audio"]?.jsonObject }.getOrNull() } ?: return AudioSettings()
            return AudioSettings(
                speed = audio["speed"]?.jsonPrimitive?.floatOrNull?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f,
                skipSilence = audio["skipSilence"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }
}
