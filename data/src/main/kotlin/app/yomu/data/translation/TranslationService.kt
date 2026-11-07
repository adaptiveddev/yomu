package app.yomu.data.translation

import app.yomu.core.database.TranslationCacheEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.core.network.NetworkMonitor
import app.yomu.domain.text.TextTools
import app.yomu.domain.translation.TranslationEngine
import app.yomu.domain.translation.TranslationException
import app.yomu.domain.translation.TranslationMode
import app.yomu.domain.translation.TranslationResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.Locale

/** Chooses engines according to the user mode, caches results and rate limits online calls. */
class TranslationService(
    private val settings: AppSettings,
    private val db: YomuDatabase,
    private val monitor: NetworkMonitor,
    client: () -> OkHttpClient,
    val mlKit: MlKitEngine,
) {
    val online: List<TranslationEngine> = listOf(
        GoogleFreeEngine(client),
        DeepLEngine(client, settings),
        LibreEngine(client, settings),
        LlmEngine(client, settings),
    )
    val engines: List<TranslationEngine> get() = listOf<TranslationEngine>(mlKit) + online

    private val rateMutex = Mutex()
    private var lastOnlineCall = 0L

    fun engine(id: String): TranslationEngine = engines.firstOrNull { it.id == id } ?: online.first()

    suspend fun targetLanguage(): String = settings.targetLang.get().ifBlank { Locale.getDefault().language.ifBlank { "en" } }

    suspend fun detect(text: String): String? = mlKit.detectLanguage(text) ?: if (monitor.isOnline) online.first().detectLanguage(text) else null

    /** Translates [text]. When [engineId] is given that engine is used directly, otherwise the configured mode decides. */
    suspend fun translate(text: String, from: String? = null, to: String? = null, engineId: String? = null): TranslationResult {
        val clean = text.trim()
        val target = to ?: targetLanguage()
        val source = from ?: settings.sourceLang.get().ifBlank { null }
        if (clean.isEmpty()) return TranslationResult(text, text, source, target, "none")
        val chain: List<TranslationEngine> = if (engineId != null) {
            listOf(engine(engineId))
        } else {
            val onlineEngine = engine(settings.onlineEngine.get())
            when (settings.translationMode.get()) {
                TranslationMode.OFFLINE_ONLY -> listOf(mlKit)
                TranslationMode.ONLINE_ONLY -> listOf(onlineEngine)
                TranslationMode.OFFLINE_FIRST -> listOf(mlKit, onlineEngine)
            }
        }
        val useCache = settings.translationCache.get()
        var lastError: Throwable? = null
        for (engine in chain) {
            val key = cacheKey(engine.id, source, target, clean)
            if (useCache) db.translationCache().get(key)?.let { return TranslationResult(clean, it.result, source, target, engine.id, fromCache = true) }
            if (!engine.isOffline && !monitor.isOnline) {
                lastError = TranslationException("No network", TranslationException.Kind.NO_NETWORK)
                continue
            }
            try {
                val translated = translateChunked(engine, clean, source, target)
                if (useCache) db.translationCache().put(TranslationCacheEntity(key, engine.id, clean.take(2000), translated, System.currentTimeMillis()))
                return TranslationResult(clean, translated, source, target, engine.id)
            } catch (e: TranslationException) {
                lastError = e
            } catch (e: Exception) {
                lastError = TranslationException(e.message ?: e.javaClass.simpleName, TranslationException.Kind.SERVER, e)
            }
        }
        throw lastError ?: TranslationException("Translation failed", TranslationException.Kind.SERVER)
    }

    private suspend fun translateChunked(engine: TranslationEngine, text: String, from: String?, to: String): String {
        val chunks = if (text.length <= CHUNK) listOf(text) else chunk(text)
        val parts = ArrayList<String>(chunks.size)
        for (part in chunks) parts += withRetry(engine) { engine.translate(part, from, to) }
        return parts.joinToString(" ")
    }

    private suspend fun <T> withRetry(engine: TranslationEngine, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            if (!engine.isOffline) {
                rateMutex.withLock {
                    val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastOnlineCall)
                    if (wait > 0) delay(wait)
                    lastOnlineCall = System.currentTimeMillis()
                }
            }
            try {
                return block()
            } catch (e: TranslationException) {
                val retryable = e.kind == TranslationException.Kind.RATE_LIMITED || e.kind == TranslationException.Kind.SERVER
                if (!retryable || attempt >= 2) throw e
                delay(800L shl attempt)
                attempt++
            }
        }
    }

    private fun chunk(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        TextTools.sentences(text).forEach { s ->
            if (sb.length + s.length > CHUNK && sb.isNotEmpty()) {
                out += sb.toString()
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    suspend fun clearCache() = db.translationCache().clear()
    fun observeCacheSize() = db.translationCache().observeCount()

    companion object {
        const val CHUNK = 3500
        const val MIN_INTERVAL_MS = 250L

        fun cacheKey(engine: String, from: String?, to: String, text: String): String {
            val md = MessageDigest.getInstance("SHA-1").digest("$engine|${from ?: "auto"}|$to|$text".toByteArray())
            return md.joinToString("") { "%02x".format(it) }
        }
    }
}
