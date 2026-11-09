package app.yomu.data.dictionary

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.yomu.core.common.AppError
import app.yomu.core.common.Files
import app.yomu.core.database.DictionaryEntity
import app.yomu.core.database.DictionaryEntryEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.core.network.NetworkMonitor
import app.yomu.domain.model.DictEntry
import app.yomu.domain.model.DictFormat
import app.yomu.domain.model.DictionaryInfo
import app.yomu.domain.model.LookupResult
import app.yomu.domain.text.JapaneseDeinflector
import app.yomu.domain.text.Lemmatizer
import app.yomu.domain.text.TextTools
import app.yomu.source.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.jsoup.Jsoup
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class DictImportProgress(val name: String, val entries: Int, val done: Boolean = false)

class DictionaryRepository(
    private val context: Context,
    private val db: YomuDatabase,
    private val settings: AppSettings,
    private val monitor: NetworkMonitor,
    private val client: () -> OkHttpClient,
) {
    private val dao = db.dictionaries()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _importProgress = MutableStateFlow<DictImportProgress?>(null)
    val importProgress: StateFlow<DictImportProgress?> = _importProgress

    fun observeDictionaries(): Flow<List<DictionaryInfo>> = dao.observe().map { l ->
        l.map { DictionaryInfo(it.id, it.name, runCatching { DictFormat.valueOf(it.format) }.getOrDefault(DictFormat.STARDICT), it.sourceLang, it.targetLang, it.entryCount, it.enabled, it.sortOrder) }
    }

    fun observeHistory(): Flow<List<String>> = dao.observeHistory().map { l -> l.map { it.query } }
    suspend fun clearHistory() = dao.clearHistory()
    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun reorder(ids: List<Long>) = db.withTransaction { ids.forEachIndexed { i, id -> dao.setOrder(id, i) } }

    /** Imports dictionaries from the picked documents (archives or loose files). Returns the number of dictionaries added. */
    suspend fun import(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "dict_import_${System.nanoTime()}").also { it.mkdirs() }
        try {
            uris.forEach { uri ->
                val name = Files.sanitize(Files.displayName(context, uri) ?: "dict")
                Files.open(context, uri).use { input -> unpack(input, name, work) }
            }
            val importers = DictionaryDetector.find(work)
            if (importers.isEmpty()) throw AppError.UnsupportedFormat("dictionary")
            importers.forEach { importOne(it) }
            importers.size
        } finally {
            work.deleteRecursively()
            _importProgress.value = null
        }
    }

    private fun unpack(input: InputStream, name: String, dir: File) {
        val lower = name.lowercase()
        when {
            lower.endsWith(".zip") -> ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val out = File(dir, Files.sanitize(e.name.substringAfterLast('/')))
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> untar(GZIPInputStream(input.buffered()), dir)
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> untar(BZip2CompressorInputStream(input.buffered()), dir)
            lower.endsWith(".tar.xz") -> untar(XZCompressorInputStream(input.buffered()), dir)
            lower.endsWith(".tar") -> untar(input, dir)
            else -> File(dir, name).outputStream().use { input.copyTo(it) }
        }
    }

    private fun untar(input: InputStream, dir: File) {
        TarArchiveInputStream(input).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isDirectory) continue
                File(dir, Files.sanitize(e.name.substringAfterLast('/'))).outputStream().use { tar.copyTo(it) }
            }
        }
    }

    private suspend fun importOne(importer: DictImporter) {
        val h = importer.header
        val id = dao.insert(
            DictionaryEntity(
                name = h.name, format = h.format.name, sourceLang = h.sourceLang, targetLang = h.targetLang,
                entryCount = 0, enabled = true, sortOrder = (System.currentTimeMillis() / 1000 % 100000).toInt(), importedAt = System.currentTimeMillis(),
            ),
        )
        val batch = ArrayList<DictionaryEntryEntity>(BATCH)
        var count = 0
        try {
            val pending = mutableListOf<List<DictionaryEntryEntity>>()
            importer.read { raw ->
                if (raw.headword.isBlank() || raw.definitions.isEmpty()) return@read
                batch += raw.toEntity(id)
                count++
                if (batch.size >= BATCH) {
                    pending += batch.toList()
                    batch.clear()
                }
                if (pending.size >= 4) {
                    val chunk = pending.toList()
                    pending.clear()
                    kotlinx.coroutines.runBlocking { db.withTransaction { chunk.forEach { dao.insertEntries(it) } } }
                    _importProgress.value = DictImportProgress(h.name, count)
                }
            }
            if (batch.isNotEmpty()) pending += batch.toList()
            db.withTransaction { pending.forEach { dao.insertEntries(it) } }
            dao.refreshCount(id)
            _importProgress.value = DictImportProgress(h.name, count, done = true)
        } catch (e: Throwable) {
            // Cancelled (the screen was left) or failed: the half-imported dictionary must not stay in the list.
            withContext(NonCancellable) { dao.delete(id) }
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw AppError.CorruptedFile(e.message, e)
        }
    }

    /** Removes dictionaries left half-imported by a killed process (called at start, when no import can be running). */
    suspend fun removeUnfinished() = withContext(Dispatchers.IO) { dao.deleteUnfinished() }

    suspend fun suggest(prefix: String): List<String> = if (prefix.isBlank()) emptyList() else dao.suggest(Lemmatizer.normalize(prefix))

    /**
     * Looks a word up in the enabled offline dictionaries, using lemmatisation and Japanese deinflection.
     * Falls back to online dictionaries when allowed.
     */
    suspend fun lookup(query: String, lang: String? = null, allowOnline: Boolean = true, record: Boolean = true): LookupResult = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext LookupResult(query, null, emptyList())
        if (record) dao.addHistory(q, System.currentTimeMillis())
        val japanese = q.any { TextTools.isCjk(it) }
        val offline = if (japanese) lookupJapanese(q) else lookupWord(q, lang)
        if (offline != null) return@withContext offline
        if (allowOnline && settings.onlineDictionary.get() && monitor.isOnline) {
            runCatching { online(q, lang ?: Lemmatizer.guessLang(q)) }.getOrNull()?.let { return@withContext it }
        }
        LookupResult(q, null, emptyList())
    }

    private suspend fun lookupWord(q: String, lang: String?): LookupResult? {
        val candidates = Lemmatizer.candidates(q, lang)
        if (candidates.isEmpty()) return null
        val rows = dao.lookup(candidates)
        if (rows.isEmpty()) return null
        val names = dao.enabled().associate { it.id to it.name }
        val sorted = rows.sortedBy { r -> candidates.indexOf(r.normalized).let { if (it < 0) 99 else it } }
        return LookupResult(q, sorted.first().headword, sorted.map { it.toEntry(names[it.dictId].orEmpty()) }.distinct())
    }

    /** Longest-prefix lookup for Japanese text starting at the tapped character. */
    private suspend fun lookupJapanese(text: String): LookupResult? {
        val names = dao.enabled().associate { it.id to it.name }
        for (prefix in JapaneseDeinflector.prefixes(text)) {
            val candidates = (listOf(prefix) + JapaneseDeinflector.deinflect(prefix)).distinct()
            val rows = dao.lookup(candidates)
            if (rows.isNotEmpty()) return LookupResult(text, prefix, rows.map { it.toEntry(names[it.dictId].orEmpty()) }.distinct())
        }
        return null
    }

    private fun DictionaryEntryEntity.toEntry(dict: String): DictEntry {
        val defs = body.substringBefore(RawEntry.SEP_EX).split(RawEntry.SEP_DEF).filter { it.isNotBlank() }
        val ex = if (RawEntry.SEP_EX in body) body.substringAfter(RawEntry.SEP_EX).split(RawEntry.SEP_DEF).filter { it.isNotBlank() } else emptyList()
        return DictEntry(headword, reading, pos, ipa, defs, ex, dict)
    }

    private suspend fun online(word: String, lang: String): LookupResult? {
        val w = word.trim().lowercase()
        if (lang == "en") {
            freeDictionary(w)?.let { return it }
        }
        return wiktionary(w, lang)
    }

    private suspend fun freeDictionary(word: String): LookupResult? {
        val url = "https://api.dictionaryapi.dev/api/v2/entries/en/".toHttpUrl().newBuilder().addPathSegment(word).build()
        val response = client().newCall(Request.Builder().url(url).build()).await()
        response.use { r ->
            if (!r.isSuccessful) return null
            val root = json.parseToJsonElement(r.body.string()) as? JsonArray ?: return null
            val entries = root.flatMap { el ->
                val o = el.jsonObject
                val head = o["word"]?.jsonPrimitive?.contentOrNull ?: word
                val ipa = o["phonetic"]?.jsonPrimitive?.contentOrNull
                    ?: (o["phonetics"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
                (o["meanings"] as? JsonArray).orEmpty().map { m ->
                    val mo = m.jsonObject
                    val defs = mo["definitions"]?.jsonArray.orEmpty().map { it.jsonObject }
                    DictEntry(
                        headword = head,
                        partOfSpeech = mo["partOfSpeech"]?.jsonPrimitive?.contentOrNull,
                        ipa = ipa,
                        definitions = defs.mapNotNull { it["definition"]?.jsonPrimitive?.contentOrNull }.take(8),
                        examples = defs.mapNotNull { it["example"]?.jsonPrimitive?.contentOrNull }.take(4),
                        dictionary = "Free Dictionary",
                    )
                }
            }
            return if (entries.isEmpty()) null else LookupResult(word, word, entries, online = true)
        }
    }

    private suspend fun wiktionary(word: String, lang: String): LookupResult? {
        val url = "https://en.wiktionary.org/api/rest_v1/page/definition/".toHttpUrl().newBuilder().addPathSegment(word).build()
        val response = client().newCall(Request.Builder().url(url).header("Accept", "application/json").build()).await()
        response.use { r ->
            if (!r.isSuccessful) return null
            val root = json.parseToJsonElement(r.body.string()).jsonObject
            val list = (root[lang] ?: root.values.firstOrNull()) as? JsonArray ?: return null
            val entries = list.map { el ->
                val o = el.jsonObject
                val defs = o["definitions"]?.jsonArray.orEmpty().map { it.jsonObject }
                DictEntry(
                    headword = word,
                    partOfSpeech = o["partOfSpeech"]?.jsonPrimitive?.contentOrNull,
                    definitions = defs.mapNotNull { d -> d["definition"]?.jsonPrimitive?.contentOrNull?.let { Jsoup.parse(it).text() } }.filter { it.isNotBlank() }.take(8),
                    examples = defs.flatMap { d -> (d["examples"] as? JsonArray).orEmpty().mapNotNull { e -> e.jsonPrimitive.contentOrNull?.let { Jsoup.parse(it).text() } } }.take(4),
                    dictionary = "Wiktionary",
                )
            }.filter { it.definitions.isNotEmpty() }
            return if (entries.isEmpty()) null else LookupResult(word, word, entries, online = true)
        }
    }

    companion object {
        const val BATCH = 2000
    }
}
