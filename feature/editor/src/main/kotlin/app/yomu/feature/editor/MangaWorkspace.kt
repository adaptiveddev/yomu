package app.yomu.feature.editor

import app.yomu.data.book.Xhtml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A page of the manga working copy. [rotation] (0/90/180/270) is applied on export. */
data class MangaPage(val file: String, val rotation: Int = 0)

/** Subset of ComicInfo.xml (Anansi schema v2) edited in the app; unknown elements are preserved in [extra]. */
data class ComicInfo(
    val title: String = "",
    val series: String = "",
    val number: String = "",
    val volume: String = "",
    val writer: String = "",
    val penciller: String = "",
    val genre: String = "",
    val languageIso: String = "",
    val summary: String = "",
    val year: String = "",
    /** Right-to-left manga ("YesAndRightToLeft"). */
    val manga: Boolean = false,
    val extra: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = this == ComicInfo()
}

object ComicInfoXml {
    private val KNOWN = setOf("Title", "Series", "Number", "Volume", "Writer", "Penciller", "Genre", "LanguageISO", "Summary", "Year", "Manga", "PageCount", "Pages")

    fun parse(xml: String): ComicInfo {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val root = doc.children().firstOrNull { it.tagName().equals("ComicInfo", true) } ?: return ComicInfo()
        fun v(name: String) = root.children().firstOrNull { it.tagName().equals(name, true) }?.wholeText()?.trim().orEmpty()
        val extra = root.children().filter { el -> KNOWN.none { it.equals(el.tagName(), true) } && el.children().isEmpty() }
            .associate { it.tagName() to it.wholeText().trim() }
        return ComicInfo(
            title = v("Title"), series = v("Series"), number = v("Number"), volume = v("Volume"), writer = v("Writer"),
            penciller = v("Penciller"), genre = v("Genre"), languageIso = v("LanguageISO"), summary = v("Summary"), year = v("Year"),
            manga = v("Manga").equals("YesAndRightToLeft", true), extra = extra,
        )
    }

    fun write(info: ComicInfo, pageCount: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<ComicInfo xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
        fun el(name: String, value: String) {
            if (value.isNotBlank()) append("  <$name>").append(Xhtml.escape(value.trim())).append("</$name>\n")
        }
        el("Title", info.title)
        el("Series", info.series)
        el("Number", info.number)
        el("Volume", info.volume)
        el("Summary", info.summary)
        el("Year", info.year)
        el("Writer", info.writer)
        el("Penciller", info.penciller)
        el("Genre", info.genre)
        el("LanguageISO", info.languageIso)
        info.extra.forEach { (k, v) -> if (k.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) el(k, v) }
        el("PageCount", pageCount.toString())
        el("Manga", if (info.manga) "YesAndRightToLeft" else "No")
        append("</ComicInfo>\n")
    }
}

/**
 * Manga working copy: page images in `img/`, their order in `pages.json` and metadata in `comic.json`.
 * Image operations never overwrite a file; they write a new one and update `pages.json`, so undo only has to
 * restore `pages.json` (and caches keyed by file name stay valid).
 */
class MangaWorkspace(val root: File) {
    var pages: List<MangaPage> = emptyList()
        private set
    var info: ComicInfo = ComicInfo()
        private set

    init {
        load()
    }

    fun load() {
        pages = runCatching {
            Json.parseToJsonElement(File(root, PAGES).readText()).jsonArray.map { e ->
                val o = e.jsonObject
                MangaPage(o["file"]!!.jsonPrimitive.content, o["rotation"]?.jsonPrimitive?.intOrNull ?: 0)
            }
        }.getOrDefault(emptyList()).filter { File(root, it.file).isFile }
        info = runCatching { infoFromJson(Json.parseToJsonElement(File(root, INFO).readText()).jsonObject) }.getOrDefault(ComicInfo())
    }

    fun file(page: MangaPage) = File(root, page.file)

    fun newImageName(ext: String): String = "$IMG_DIR/${UUID.randomUUID().toString().substring(0, 13)}.${ext.lowercase()}"

    fun savePages(list: List<MangaPage>) {
        val json = JsonArray(list.map { p -> buildJsonObject { put("file", p.file); if (p.rotation != 0) put("rotation", p.rotation) } })
        File(root, PAGES).writeText(json.toString())
        pages = list
    }

    fun saveInfo(value: ComicInfo) {
        File(root, INFO).writeText(infoToJson(value).toString())
        info = value
    }

    // ---------------------------------------------------------------- page list operations

    /** Moves the selected pages (keeping their order) so that they start at [position] of the remaining list. */
    fun moveTo(indices: Set<Int>, position: Int) {
        val selected = pages.filterIndexed { i, _ -> i in indices }
        if (selected.isEmpty()) return
        val rest = pages.filterIndexed { i, _ -> i !in indices }.toMutableList()
        rest.addAll(position.coerceIn(0, rest.size), selected)
        savePages(rest)
    }

    fun delete(indices: Set<Int>) {
        check(indices.size < pages.size) { "At least one page must remain" }
        savePages(pages.filterIndexed { i, _ -> i !in indices })
    }

    fun rotate(indices: Set<Int>, delta: Int) {
        savePages(pages.mapIndexed { i, p -> if (i in indices) p.copy(rotation = ((p.rotation + delta) % 360 + 360) % 360) else p })
    }

    fun insert(at: Int, files: List<String>) {
        val list = pages.toMutableList()
        list.addAll(at.coerceIn(0, list.size), files.map { MangaPage(it) })
        savePages(list)
    }

    fun replace(index: Int, replacement: List<MangaPage>) {
        val list = pages.toMutableList()
        list.removeAt(index)
        list.addAll(index, replacement)
        savePages(list)
    }

    fun setCover(index: Int) = moveTo(setOf(index), 0)

    // ---------------------------------------------------------------- export

    /**
     * Writes a CBZ (pages renamed to 0001.ext… plus ComicInfo.xml). [render] produces the bytes of rotated pages;
     * pages without rotation are copied as they are.
     */
    fun exportCbz(out: OutputStream, render: (MangaPage) -> Pair<ByteArray, String>, progress: (Float) -> Unit = {}) {
        val digits = maxOf(4, pages.size.toString().length)
        ZipOutputStream(out.buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            pages.forEachIndexed { i, page ->
                val name = (i + 1).toString().padStart(digits, '0')
                if (page.rotation == 0) {
                    zip.putNextEntry(ZipEntry("$name.${file(page).extension.lowercase()}"))
                    file(page).inputStream().use { it.copyTo(zip) }
                } else {
                    val (bytes, ext) = render(page)
                    zip.putNextEntry(ZipEntry("$name.$ext"))
                    zip.write(bytes)
                }
                zip.closeEntry()
                progress((i + 1f) / pages.size)
            }
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry("ComicInfo.xml"))
            zip.write(ComicInfoXml.write(info, pages.size).toByteArray())
            zip.closeEntry()
        }
    }

    companion object {
        const val PAGES = "pages.json"
        const val INFO = "comic.json"
        const val IMG_DIR = "img"

        /** Creates a working copy from already extracted page files (relative to [root]). */
        fun create(root: File, files: List<String>, info: ComicInfo): MangaWorkspace {
            root.mkdirs()
            File(root, PAGES).writeText(JsonArray(files.map { buildJsonObject { put("file", it) } }).toString())
            File(root, INFO).writeText(infoToJson(info).toString())
            return MangaWorkspace(root)
        }

        private fun infoToJson(i: ComicInfo): JsonObject = buildJsonObject {
            put("title", i.title)
            put("series", i.series)
            put("number", i.number)
            put("volume", i.volume)
            put("writer", i.writer)
            put("penciller", i.penciller)
            put("genre", i.genre)
            put("languageIso", i.languageIso)
            put("summary", i.summary)
            put("year", i.year)
            put("manga", i.manga)
            put("extra", JsonObject(i.extra.mapValues { JsonPrimitive(it.value) }))
        }

        private fun infoFromJson(o: JsonObject): ComicInfo {
            fun s(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            return ComicInfo(
                title = s("title"), series = s("series"), number = s("number"), volume = s("volume"), writer = s("writer"),
                penciller = s("penciller"), genre = s("genre"), languageIso = s("languageIso"), summary = s("summary"), year = s("year"),
                manga = o["manga"]?.jsonPrimitive?.booleanOrNull ?: false,
                extra = o["extra"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            )
        }
    }
}
