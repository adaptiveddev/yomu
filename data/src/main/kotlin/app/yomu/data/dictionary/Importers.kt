package app.yomu.data.dictionary

import app.yomu.core.database.DictionaryEntryEntity
import app.yomu.domain.model.DictFormat
import app.yomu.domain.text.Lemmatizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/** Parsed dictionary entry before it is stored. */
data class RawEntry(
    val headword: String,
    val definitions: List<String>,
    val reading: String? = null,
    val pos: String? = null,
    val ipa: String? = null,
    val examples: List<String> = emptyList(),
) {
    fun toEntity(dictId: Long) = DictionaryEntryEntity(
        dictId = dictId,
        headword = headword,
        normalized = Lemmatizer.normalize(headword),
        reading = reading,
        pos = pos,
        ipa = ipa,
        body = definitions.joinToString(SEP_DEF) + if (examples.isNotEmpty()) SEP_EX + examples.joinToString(SEP_DEF) else "",
    )

    companion object {
        const val SEP_DEF = "\u001e"
        const val SEP_EX = "\u001d"
    }
}

data class DictHeader(val name: String, val format: DictFormat, val sourceLang: String? = null, val targetLang: String? = null)

/** A dictionary found in a folder of extracted files. */
interface DictImporter {
    val header: DictHeader
    /** Streams entries to [sink]. */
    fun read(sink: (RawEntry) -> Unit)
}

private fun openMaybeGz(file: File): InputStream {
    val input = file.inputStream().buffered()
    input.mark(2)
    val b1 = input.read()
    val b2 = input.read()
    input.reset()
    return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(input, 64 * 1024).buffered() else input
}

private fun htmlToText(s: String): String = if (s.contains('<')) {
    Jsoup.parse(s.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</(p|div|li)>"), "\n")).wholeText().trim()
} else {
    s.trim()
}

object DictionaryDetector {
    fun find(dir: File): List<DictImporter> {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val out = mutableListOf<DictImporter>()
        files.filter { it.name.endsWith(".ifo", true) }.forEach { ifo -> StarDictImporter.create(ifo)?.let { out += it } }
        files.filter { it.name.endsWith(".dsl", true) || it.name.endsWith(".dsl.dz", true) }.forEach { out += DslImporter(it) }
        files.filter { it.name.contains("jmdict", true) && (it.name.endsWith(".xml", true) || it.name.endsWith(".gz", true) || !it.name.contains('.')) }
            .forEach { out += JmdictImporter(it) }
        files.filter { (it.name.endsWith(".json", true) || it.name.endsWith(".jsonl", true) || it.name.endsWith(".jsonl.gz", true) || it.name.endsWith(".json.gz", true)) && !it.name.contains("jmdict", true) }
            .forEach { out += WiktionaryJsonImporter(it) }
        return out
    }
}

class StarDictImporter private constructor(
    private val idx: File,
    private val dict: File,
    private val offsetBits: Int,
    private val sameType: String?,
    override val header: DictHeader,
) : DictImporter {
    override fun read(sink: (RawEntry) -> Unit) {
        val dictBytes: RandomAccessFile?
        val dictData: ByteArray?
        if (dict.name.endsWith(".dz", true) || dict.name.endsWith(".gz", true)) {
            dictData = openMaybeGz(dict).use { it.readBytes() }
            dictBytes = null
        } else {
            dictData = null
            dictBytes = RandomAccessFile(dict, "r")
        }
        try {
            DataInputStream(openMaybeGz(idx)).use { input ->
                val word = java.io.ByteArrayOutputStream()
                while (true) {
                    word.reset()
                    var b = input.read()
                    if (b < 0) break
                    while (b > 0) {
                        word.write(b)
                        b = input.read()
                    }
                    val offset = if (offsetBits == 64) input.readLong() else input.readInt().toLong() and 0xFFFFFFFFL
                    val size = input.readInt()
                    val data = ByteArray(size)
                    if (dictData != null) {
                        System.arraycopy(dictData, offset.toInt(), data, 0, size.coerceAtMost(dictData.size - offset.toInt()))
                    } else {
                        dictBytes!!.seek(offset)
                        dictBytes.readFully(data)
                    }
                    val text = decode(data)
                    val headword = word.toString(Charsets.UTF_8.name())
                    val (ipa, body) = extractTranscription(text)
                    sink(RawEntry(headword, body.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(40), ipa = ipa))
                }
            }
        } finally {
            dictBytes?.close()
        }
    }

    private fun decode(data: ByteArray): String {
        val types = sameType
        if (types != null) {
            val s = String(data, Charsets.UTF_8)
            return if (types.any { it == 'h' || it == 'g' || it == 'x' }) htmlToText(s) else s
        }
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val type = data[i].toInt().toChar()
            i++
            if (type.isLowerCase()) {
                var end = i
                while (end < data.size && data[end] != 0.toByte()) end++
                val s = String(data, i, end - i, Charsets.UTF_8)
                sb.append(if (type == 'h' || type == 'g' || type == 'x') htmlToText(s) else s).append('\n')
                i = end + 1
            } else {
                if (i + 4 > data.size) break
                val len = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
                i += 4 + len
            }
        }
        return sb.toString()
    }

    private fun extractTranscription(text: String): Pair<String?, String> {
        val m = Regex("^\\s*\\[([^\\]]{1,60})]").find(text) ?: Regex("^\\s*/([^/]{1,60})/").find(text)
        return if (m != null) m.groupValues[1] to text.substring(m.range.last + 1) else null to text
    }

    companion object {
        fun create(ifo: File): StarDictImporter? {
            val props = ifo.readLines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            val base = ifo.path.removeSuffix(".ifo").removeSuffix(".IFO")
            val idx = listOf("$base.idx", "$base.idx.gz").map(::File).firstOrNull { it.exists() } ?: return null
            val dict = listOf("$base.dict", "$base.dict.dz").map(::File).firstOrNull { it.exists() } ?: return null
            return StarDictImporter(
                idx, dict,
                props["idxoffsetbits"]?.toIntOrNull() ?: 32,
                props["sametypesequence"],
                DictHeader(props["bookname"] ?: ifo.nameWithoutExtension, DictFormat.STARDICT),
            )
        }
    }
}

class DslImporter(private val file: File) : DictImporter {
    private var name = file.name.removeSuffix(".dz").removeSuffix(".dsl")
    private var from: String? = null
    private var to: String? = null

    override val header: DictHeader
        get() {
            reader().use { r ->
                repeat(10) {
                    val line = (r.readLine() ?: return@repeat).removePrefix("\uFEFF")
                    if (line.startsWith("#NAME")) name = line.substringAfter('"').substringBeforeLast('"')
                    if (line.startsWith("#INDEX_LANGUAGE")) from = lang(line.substringAfter('"').substringBeforeLast('"'))
                    if (line.startsWith("#CONTENTS_LANGUAGE")) to = lang(line.substringAfter('"').substringBeforeLast('"'))
                }
            }
            return DictHeader(name, DictFormat.DSL, from, to)
        }

    private fun lang(name: String): String? = when (name.lowercase()) {
        "english" -> "en"; "russian" -> "ru"; "german" -> "de"; "french" -> "fr"; "spanish" -> "es"; "italian" -> "it"
        "ukrainian" -> "uk"; "japanese" -> "ja"; "chinese" -> "zh"; "polish" -> "pl"; "portuguese" -> "pt"; else -> null
    }

    private fun reader(): BufferedReader {
        val input = openMaybeGz(file)
        input.mark(4)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        val charset = when {
            b1 == 0xFF && b2 == 0xFE -> Charsets.UTF_16LE
            b1 == 0xFE && b2 == 0xFF -> Charsets.UTF_16BE
            b2 == 0x00 -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        return input.bufferedReader(charset)
    }

    override fun read(sink: (RawEntry) -> Unit) {
        reader().use { r ->
            val heads = mutableListOf<String>()
            val body = mutableListOf<String>()
            fun flush() {
                if (heads.isNotEmpty() && body.isNotEmpty()) {
                    val parsed = parseBody(body)
                    heads.forEach { h -> sink(parsed.copy(headword = cleanHead(h))) }
                }
                heads.clear()
                body.clear()
            }
            r.lineSequence().forEach { raw ->
                val line = raw.trimEnd().removePrefix("\uFEFF")
                when {
                    line.startsWith("#") -> Unit
                    line.isEmpty() -> Unit
                    line[0] == ' ' || line[0] == '\t' -> body += line.trim()
                    else -> {
                        if (body.isNotEmpty()) flush()
                        heads += line
                    }
                }
            }
            flush()
        }
    }

    private fun cleanHead(h: String) = h.replace(Regex("\\{[^}]*\\}"), "").replace("\\", "").trim()

    private fun parseBody(lines: List<String>): RawEntry {
        var ipa: String? = null
        var pos: String? = null
        val defs = mutableListOf<String>()
        val examples = mutableListOf<String>()
        lines.forEach { line ->
            Regex("\\[t](.*?)\\[/t]").find(line)?.let { if (ipa == null) ipa = strip(it.groupValues[1]) }
            Regex("\\[p](.*?)\\[/p]").find(line)?.let { if (pos == null) pos = strip(it.groupValues[1]) }
            val withoutTranscription = line.replace(Regex("\\[t].*?\\[/t]"), "")
            val text = strip(withoutTranscription)
            // Lines that only carry a transcription and/or a part-of-speech label are not definitions.
            if (text.isBlank() || strip(withoutTranscription.replace(Regex("\\[p].*?\\[/p]"), "")).isBlank()) return@forEach
            if (line.contains("[ex]") || line.contains("[*]")) examples += text else defs += text
        }
        return RawEntry("", defs.take(40), pos = pos, ipa = ipa, examples = examples.take(10))
    }

    private fun strip(s: String): String = s
        .replace(Regex("\\[/?(m\\d?|trn|!trs|ex|com|\\*|p|i|b|u|c[^]]*|lang[^]]*|ref[^]]*|url|sub|sup|s|'|trs|t)]"), "")
        .replace(Regex("\\[[^]]*]"), "")
        .replace("\\[", "[").replace("\\]", "]").replace("<<", "").replace(">>", "")
        .replace(Regex("\\s+"), " ").trim()
}

class WiktionaryJsonImporter(private val file: File) : DictImporter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    override val header = DictHeader(file.name.substringBefore('.'), DictFormat.WIKTIONARY)

    override fun read(sink: (RawEntry) -> Unit) {
        val reader = openMaybeGz(file).bufferedReader()
        reader.use { r ->
            r.mark(16)
            val first = r.read().toChar()
            r.reset()
            if (first == '[' || (first == '{' && !isJsonLines())) {
                when (val root = json.parseToJsonElement(r.readText())) {
                    is JsonArray -> root.forEach { el -> (el as? JsonObject)?.let { obj -> entryOf(obj)?.let(sink) } }
                    is JsonObject -> emitObject(root, sink)
                    else -> Unit
                }
            } else {
                r.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()?.let { emitObject(it, sink) }
                }
            }
        }
    }

    /** One entry object (kaikki/Wiktextract style) or a simple `{"word": definitions}` map. */
    private fun emitObject(obj: JsonObject, sink: (RawEntry) -> Unit) {
        if (WORD_KEYS.any { it in obj }) {
            entryOf(obj)?.let(sink)
            return
        }
        obj.forEach { (word, value) ->
            val defs = when (value) {
                is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonObject -> entryOf(value)?.definitions.orEmpty()
            }
            if (defs.isNotEmpty()) sink(RawEntry(word, defs))
        }
    }

    private fun isJsonLines(): Boolean = openMaybeGz(file).bufferedReader().use { r ->
        val l1 = r.readLine() ?: return false
        l1.trim().startsWith("{") && l1.trim().endsWith("}")
    }

    private fun entryOf(obj: JsonObject): RawEntry? {
        val word = (obj["word"] ?: obj["headword"] ?: obj["term"])?.jsonPrimitive?.contentOrNull ?: return null
        val senses = obj["senses"] as? JsonArray
        val defs = mutableListOf<String>()
        val examples = mutableListOf<String>()
        senses?.forEach { s ->
            val so = s as? JsonObject ?: return@forEach
            (so["glosses"] as? JsonArray ?: so["raw_glosses"] as? JsonArray)?.lastOrNull()?.jsonPrimitive?.contentOrNull?.let { defs += it }
            (so["examples"] as? JsonArray)?.take(2)?.forEach { ex -> (ex as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull?.let { examples += it } }
        }
        (obj["definitions"] as? JsonArray)?.forEach { d -> (d as? JsonPrimitive)?.contentOrNull?.let { defs += it } ?: (d as? JsonObject)?.get("definition")?.jsonPrimitive?.contentOrNull?.let { defs += it } }
        (obj["definition"] as? JsonPrimitive)?.contentOrNull?.let { defs += it }
        if (defs.isEmpty()) return null
        val ipa = (obj["sounds"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.get("ipa")?.jsonPrimitive?.contentOrNull }
        val reading = (obj["forms"] as? JsonArray)?.firstNotNullOfOrNull { f ->
            val fo = f as? JsonObject
            if (fo?.get("tags")?.jsonArray?.any { it.jsonPrimitive.content == "romanization" || it.jsonPrimitive.content == "hiragana" } == true) fo["form"]?.jsonPrimitive?.contentOrNull else null
        }
        return RawEntry(word, defs.take(30), reading = reading, pos = obj["pos"]?.jsonPrimitive?.contentOrNull, ipa = ipa, examples = examples.take(6))
    }

    private companion object {
        val WORD_KEYS = listOf("word", "headword", "term")
    }
}

/** JMdict (XML, optionally gzip) parsed line by line, which is fast and avoids DTD entity handling. */
class JmdictImporter(private val file: File) : DictImporter {
    override val header = DictHeader("JMdict", DictFormat.JMDICT, "ja", null)

    override fun read(sink: (RawEntry) -> Unit) {
        val kebs = mutableListOf<String>()
        val rebs = mutableListOf<String>()
        val glosses = mutableListOf<String>()
        val pos = mutableListOf<String>()
        val tag = Regex("<(keb|reb|gloss|pos)(?: [^>]*)?>(.*?)</\\1>")
        openMaybeGz(file).bufferedReader().use { r ->
            r.lineSequence().forEach { line ->
                val t = line.trim()
                when {
                    t == "<entry>" -> { kebs.clear(); rebs.clear(); glosses.clear(); pos.clear() }
                    t == "</entry>" -> {
                        val reading = rebs.firstOrNull()
                        val defs = glosses.toList()
                        if (defs.isNotEmpty()) {
                            (kebs.ifEmpty { rebs }).forEach { k -> sink(RawEntry(k, defs, reading = reading, pos = pos.firstOrNull())) }
                            if (kebs.isNotEmpty()) rebs.forEach { rb -> sink(RawEntry(rb, defs, reading = rb, pos = pos.firstOrNull())) }
                        }
                    }
                    else -> tag.find(t)?.let { m ->
                        val value = unescape(m.groupValues[2])
                        when (m.groupValues[1]) {
                            "keb" -> kebs += value
                            "reb" -> rebs += value
                            "gloss" -> if (glosses.size < 20) glosses += value
                            "pos" -> pos += value.removePrefix("&").removeSuffix(";")
                        }
                    }
                }
            }
        }
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
