package app.yomu.domain.translation

enum class TranslationMode { OFFLINE_ONLY, ONLINE_ONLY, OFFLINE_FIRST }

/** A translation backend. Implementations: ML Kit (offline), Google, DeepL, LibreTranslate, LLM endpoint. */
interface TranslationEngine {
    val id: String
    val displayName: String
    val isOffline: Boolean

    /** [from] null means auto-detect. */
    suspend fun translate(text: String, from: String?, to: String): String
    suspend fun detectLanguage(text: String): String?
    suspend fun supportedLanguages(): List<String>
    /** False when the engine needs configuration (API key, address) that is missing. */
    suspend fun isConfigured(): Boolean = true
}

data class TranslationResult(
    val source: String,
    val translated: String,
    val from: String?,
    val to: String,
    val engineId: String,
    val fromCache: Boolean = false,
)

class TranslationException(message: String, val kind: Kind, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NO_NETWORK, NOT_CONFIGURED, MODEL_MISSING, RATE_LIMITED, SERVER, UNSUPPORTED_LANGUAGE }
}

/** Languages offered in pickers (ISO 639-1). Engines may support more. */
val CommonLanguages = listOf(
    "en", "ru", "uk", "be", "de", "fr", "es", "it", "pt", "pl", "cs", "nl", "sv", "fi", "tr", "ar", "he",
    "hi", "ja", "zh", "ko", "vi", "id", "th", "el", "hu", "ro", "bg", "kk",
)
