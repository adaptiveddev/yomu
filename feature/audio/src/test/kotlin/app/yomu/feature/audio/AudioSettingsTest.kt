package app.yomu.feature.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioSettingsTest {
    @Test
    fun settingsRoundTripAndKeepOtherKeys() {
        val json = AudioSettings(1.35f, skipSilence = true).merge("""{"fontSize":18,"audio":{"speed":2.0}}""")
        assertEquals(AudioSettings(1.35f, true), AudioSettings.of(json))
        assertTrue("fontSize" in Json.parseToJsonElement(json).jsonObject)
    }

    @Test
    fun oldAndBrokenSettingsFallBack() {
        assertEquals(AudioSettings(2f, false), AudioSettings.of("""{"audio":{"speed":2.0}}"""))
        assertEquals(AudioSettings(3f, false), AudioSettings.of("""{"audio":{"speed":9.0}}"""))
        assertEquals(AudioSettings(), AudioSettings.of("not json"))
        assertEquals(AudioSettings(), AudioSettings.of(null))
    }

    @Test
    fun networkRetriesBackOffUpToAMinute() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (0..6).map(AudioPlaybackService::networkBackoff))
    }

    @Test
    fun rewindGrowsWithThePause() {
        assertEquals(0L, AudioPlaybackService.rewindAfter(30_000))
        assertEquals(3_000L, AudioPlaybackService.rewindAfter(5 * 60_000L))
        assertEquals(10_000L, AudioPlaybackService.rewindAfter(30 * 60_000L))
        assertEquals(20_000L, AudioPlaybackService.rewindAfter(5 * 60 * 60_000L))
        assertEquals(30_000L, AudioPlaybackService.rewindAfter(3 * 24 * 60 * 60_000L))
    }
}
