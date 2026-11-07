package app.yomu.data.translation

import android.content.Context
import app.yomu.core.datastore.AppSettings
import app.yomu.domain.translation.CommonLanguages
import app.yomu.domain.translation.TranslationEngine
import app.yomu.domain.translation.TranslationException
import app.yomu.source.await
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

private val JsonType = "application/json; charset=utf-8".toMediaType()
private val Parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

internal suspend fun OkHttpClient.call(request: Request): String {
    val response: Response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw TranslationException(e.message ?: "Network error", TranslationException.Kind.NO_NETWORK, e)
    }
    response.use {
        val body = it.body.string()
        when {
            it.code == 429 -> throw TranslationException("Too many requests", TranslationException.Kind.RATE_LIMITED)
            it.code == 401 || it.code == 403 -> throw TranslationException("Access denied (${it.code}). Check the API key.", TranslationException.Kind.NOT_CONFIGURED)
            !it.isSuccessful -> throw TranslationException("Server error ${it.code}: ${body.take(200)}", TranslationException.Kind.SERVER)
        }
        return body
    }
}

/** Offline translation with ML Kit on-device models. */
class MlKitEngine(private val context: Context) : TranslationEngine {
    override val id = "mlkit"
    override val displayName = "ML Kit (offline)"
    override val isOffline = true
    private val translators = LinkedHashMap<String, Translator>()
    private val modelManager by lazy { RemoteModelManager.getInstance() }

    private fun code(lang: String): String = TranslateLanguage.fromLanguageTag(lang)
        ?: throw TranslationException("Language $lang is not supported offline", TranslationException.Kind.UNSUPPORTED_LANGUAGE)

    override suspend fun translate(text: String, from: String?, to: String): String {
        val source = code(from ?: detectLanguage(text) ?: throw TranslationException("Cannot detect language", TranslationException.Kind.UNSUPPORTED_LANGUAGE))
        val target = code(to)
        if (source == target) return text
        val downloaded = downloadedModels()
        val missing = listOf(source, target).filter { it != TranslateLanguage.ENGLISH && it !in downloaded }
        if (missing.isNotEmpty()) throw TranslationException("Offline model missing: ${missing.joinToString()}", TranslationException.Kind.MODEL_MISSING)
        val key = "$source>$target"
        val translator = synchronized(translators) {
            translators.getOrPut(key) {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
            }.also {
                if (translators.size > 3) translators.entries.first().let { e -> e.value.close(); translators.remove(e.key) }
            }
        }
        return translator.translate(text).awaitTask()
    }

    override suspend fun detectLanguage(text: String): String? = runCatching {
        LanguageIdentification.getClient().identifyLanguage(text.take(500)).awaitTask().takeIf { it != "und" }
    }.getOrNull()

    override suspend fun supportedLanguages(): List<String> = TranslateLanguage.getAllLanguages().toList()

    suspend fun downloadedModels(): Set<String> = runCatching {
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java).awaitTask().map { it.language }.toSet()
    }.getOrDefault(emptySet())

    suspend fun download(lang: String, wifiOnly: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        modelManager.download(TranslateRemoteModel.Builder(code(lang)).build(), conditions).awaitTask()
    }

    suspend fun delete(lang: String) {
        modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(code(lang)).build()).awaitTask()
    }

    fun close() = synchronized(translators) {
        translators.values.forEach { it.close() }
        translators.clear()
    }

    companion object {
        /** Approximate size of one ML Kit language pack. */
        const val MODEL_SIZE_MB = 30
    }
}

/** Free Google endpoint used by many open source readers (no key, rate limited). */
class GoogleFreeEngine(private val client: () -> OkHttpClient, private val baseUrl: String = "https://translate.googleapis.com") : TranslationEngine {
    override val id = "google"
    override val displayName = "Google Translate"
    override val isOffline = false

    override suspend fun translate(text: String, from: String?, to: String): String = request(text, from, to).first

    private suspend fun request(text: String, from: String?, to: String): Pair<String, String?> {
        val body = FormBody.Builder().add("q", text).build()
        val url = "$baseUrl/translate_a/single?client=gtx&sl=${from ?: "auto"}&tl=$to&dt=t&ie=UTF-8&oe=UTF-8"
        val json = Parser.parseToJsonElement(client().call(Request.Builder().url(url).post(body).build())).jsonArray
        val translated = json[0].jsonArray.joinToString("") { seg -> seg.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty() }
        val detected = json.getOrNull(2)?.let { (it as? JsonPrimitive)?.contentOrNull }
        return translated to detected
    }

    override suspend fun detectLanguage(text: String): String? = runCatching { request(text.take(300), null, "en").second }.getOrNull()
    override suspend fun supportedLanguages(): List<String> = CommonLanguages
}

class DeepLEngine(private val client: () -> OkHttpClient, private val settings: AppSettings) : TranslationEngine {
    override val id = "deepl"
    override val displayName = "DeepL"
    override val isOffline = false

    override suspend fun isConfigured(): Boolean = settings.deeplKey.get().isNotBlank()

    private fun target(lang: String) = when (lang.lowercase()) {
        "en" -> "EN-US"
        "pt" -> "PT-BR"
        "zh" -> "ZH"
        else -> lang.uppercase()
    }

    override suspend fun translate(text: String, from: String?, to: String): String {
        val key = settings.deeplKey.get().trim()
        if (key.isEmpty()) throw TranslationException("DeepL API key is not set", TranslationException.Kind.NOT_CONFIGURED)
        val host = if (key.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val form = FormBody.Builder().add("text", text).add("target_lang", target(to)).apply { from?.let { add("source_lang", it.uppercase()) } }.build()
        val req = Request.Builder().url("$host/v2/translate").header("Authorization", "DeepL-Auth-Key $key").post(form).build()
        val json = Parser.parseToJsonElement(client().call(req)).jsonObject
        return json["translations"]!!.jsonArray.joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
    }

    override suspend fun detectLanguage(text: String): String? = null
    override suspend fun supportedLanguages(): List<String> = CommonLanguages
}

class LibreEngine(private val client: () -> OkHttpClient, private val settings: AppSettings) : TranslationEngine {
    override val id = "libre"
    override val displayName = "LibreTranslate"
    override val isOffline = false

    override suspend fun isConfigured(): Boolean = settings.libreUrl.get().isNotBlank()

    private suspend fun base() = settings.libreUrl.get().trim().trimEnd('/').ifEmpty {
        throw TranslationException("LibreTranslate address is not set", TranslationException.Kind.NOT_CONFIGURED)
    }

    override suspend fun translate(text: String, from: String?, to: String): String {
        val body = buildJsonObject {
            put("q", text)
            put("source", from ?: "auto")
            put("target", to)
            put("format", "text")
            settings.libreKey.get().takeIf { it.isNotBlank() }?.let { put("api_key", it) }
        }
        val req = Request.Builder().url("${base()}/translate").post(body.toString().toRequestBody(JsonType)).build()
        return Parser.parseToJsonElement(client().call(req)).jsonObject["translatedText"]!!.jsonPrimitive.content
    }

    override suspend fun detectLanguage(text: String): String? = runCatching {
        val body = buildJsonObject { put("q", text.take(300)) }
        val req = Request.Builder().url("${base()}/detect").post(body.toString().toRequestBody(JsonType)).build()
        Parser.parseToJsonElement(client().call(req)).jsonArray.firstOrNull()?.jsonObject?.get("language")?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun supportedLanguages(): List<String> = runCatching {
        val json = Parser.parseToJsonElement(client().call(Request.Builder().url("${base()}/languages").build())).jsonArray
        json.map { it.jsonObject["code"]!!.jsonPrimitive.content }
    }.getOrDefault(CommonLanguages)
}

/** Any OpenAI compatible chat completion endpoint (OpenAI, OpenRouter, local LLM servers). */
class LlmEngine(private val client: () -> OkHttpClient, private val settings: AppSettings) : TranslationEngine {
    override val id = "llm"
    override val displayName = "LLM (OpenAI compatible)"
    override val isOffline = false

    override suspend fun isConfigured(): Boolean = settings.llmUrl.get().isNotBlank() && settings.llmModel.get().isNotBlank()

    override suspend fun translate(text: String, from: String?, to: String): String {
        val base = settings.llmUrl.get().trim().trimEnd('/')
        if (base.isEmpty()) throw TranslationException("LLM endpoint is not set", TranslationException.Kind.NOT_CONFIGURED)
        val prompt = settings.llmPrompt.get().ifBlank { AppSettings.DEFAULT_LLM_PROMPT }
            .replace("{from}", from ?: "the detected language").replace("{to}", to).let { if ("{text}" in it) it.replace("{text}", text) else "$it\n\n$text" }
        val body = buildJsonObject {
            put("model", settings.llmModel.get())
            put("temperature", 0.2)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", prompt) }) })
        }
        val req = Request.Builder().url("$base/chat/completions").post(body.toString().toRequestBody(JsonType)).apply {
            settings.llmKey.get().takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }.build()
        val json = Parser.parseToJsonElement(client().call(req)).jsonObject
        val choice = (json["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
        return (choice?.get("message") as? JsonObject)?.get("content")?.jsonPrimitive?.content?.trim()
            ?: throw TranslationException("Unexpected LLM response", TranslationException.Kind.SERVER)
    }

    override suspend fun detectLanguage(text: String): String? = null
    override suspend fun supportedLanguages(): List<String> = CommonLanguages
}
