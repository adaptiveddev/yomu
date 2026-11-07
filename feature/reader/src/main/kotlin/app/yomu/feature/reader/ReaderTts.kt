package app.yomu.feature.reader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Text-to-speech queue for the reader. Sentences are queued with their index as utterance id so the
 * reader can highlight the sentence being spoken.
 */
class ReaderTts(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queue: List<String> = emptyList()
    private var index = 0
    private var pendingStart: (() -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    var onSentence: ((Int) -> Unit)? = null
    var onQueueFinished: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    var speed = 1f
    var pitch = 1f
    var voiceName: String? = null
    var language: String? = null

    private fun engine(onReady: () -> Unit) {
        val existing = tts
        if (existing != null && ready) {
            onReady()
            return
        }
        pendingStart = onReady
        if (existing != null) return
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            main.post {
                if (ready) {
                    tts?.setOnUtteranceProgressListener(listener)
                    pendingStart?.invoke()
                } else {
                    onError?.invoke()
                }
                pendingStart = null
            }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val i = utteranceId?.toIntOrNull() ?: return
            main.post {
                index = i
                onSentence?.invoke(i)
            }
        }

        override fun onDone(utteranceId: String?) {
            val i = utteranceId?.toIntOrNull() ?: return
            if (i == queue.lastIndex) main.post {
                if (_playing.value) onQueueFinished?.invoke()
            }
        }

        @Suppress("OVERRIDE_DEPRECATION") // abstract in UtteranceProgressListener; its default onError(String, Int) delegates here
        override fun onError(utteranceId: String?) {
            main.post { onError?.invoke() }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
    }

    fun voices(): List<Voice> = runCatching {
        val lang = language
        tts?.voices.orEmpty().filter { lang == null || it.locale.language == Locale.forLanguageTag(lang).language }
            .sortedBy { it.name }
    }.getOrDefault(emptyList())

    /** Speaks [sentences] starting at [from]. */
    fun play(sentences: List<String>, from: Int = 0) {
        queue = sentences
        index = from
        _playing.value = true
        engine { speakFrom(index) }
    }

    private fun speakFrom(start: Int) {
        val e = tts ?: return
        e.stop()
        e.setSpeechRate(speed)
        e.setPitch(pitch)
        language?.let { l ->
            val loc = Locale.forLanguageTag(l)
            if (e.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) e.language = loc
        }
        voiceName?.let { v -> e.voices?.firstOrNull { it.name == v }?.let { e.voice = it } }
        if (queue.isEmpty()) {
            main.post { onQueueFinished?.invoke() }
            return
        }
        for (i in start until queue.size) {
            e.speak(queue[i], TextToSpeech.QUEUE_ADD, Bundle(), i.toString())
        }
    }

    fun pause() {
        _playing.value = false
        tts?.stop()
    }

    fun resume() {
        if (queue.isEmpty()) return
        _playing.value = true
        engine { speakFrom(index) }
    }

    /** Re-applies speed/pitch/voice to the rest of the queue. */
    fun restartCurrent() {
        if (_playing.value) engine { speakFrom(index) }
    }

    fun stop() {
        _playing.value = false
        queue = emptyList()
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
