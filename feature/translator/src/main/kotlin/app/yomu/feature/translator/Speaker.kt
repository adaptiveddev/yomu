package app.yomu.feature.translator

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Small text-to-speech helper used by the translation sheet and the dictionary. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String?>? = null

    private fun engine(): TextToSpeech = tts ?: TextToSpeech(appContext, this).also { tts = it }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        pending?.let { (t, l) -> if (ready) speak(t, l) }
        pending = null
    }

    fun speak(text: String, lang: String?) {
        val e = engine()
        if (!ready) {
            pending = text to lang
            return
        }
        if (!lang.isNullOrBlank()) {
            val locale = Locale.forLanguageTag(lang)
            if (e.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) e.language = locale
        }
        e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yomu-say")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}

/** Localised language names for ISO codes. */
object Languages {
    fun name(code: String?): String {
        if (code.isNullOrBlank()) return ""
        val l = Locale.forLanguageTag(code)
        return l.getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifBlank { code }
    }
}
