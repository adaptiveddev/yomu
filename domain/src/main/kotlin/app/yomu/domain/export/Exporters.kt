package app.yomu.domain.export

import app.yomu.domain.model.BookAnnotations
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.HighlightColor
import app.yomu.domain.model.Locator
import app.yomu.domain.model.Word
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class AnnotationFormat(val extension: String, val mime: String, val binary: Boolean = false) {
    MARKDOWN("md", "text/markdown"),
    JSON("json", "application/json"),
    HTML("html", "text/html"),
    TXT("txt", "text/plain"),
    READWISE_CSV("csv", "text/csv"),
    /** Highlights as Anki cards: a text file for Anki's "Import File". */
    ANKI_TSV("txt", "text/plain"),
    /** Highlights as a ready Anki deck (.apkg). */
    ANKI_APKG("apkg", "application/octet-stream", binary = true),
}

object AnnotationExporter {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun export(books: List<BookAnnotations>, format: AnnotationFormat): String = when (format) {
        AnnotationFormat.MARKDOWN -> books.joinToString("\n\n---\n\n") { markdown(it) }
        AnnotationFormat.JSON -> json.encodeToString(books.map { it.toDto() })
        AnnotationFormat.HTML -> html(books)
        AnnotationFormat.TXT -> books.joinToString("\n\n") { txt(it) }
        AnnotationFormat.READWISE_CSV -> readwiseCsv(books)
        AnnotationFormat.ANKI_TSV -> buildString {
            appendLine("#separator:tab")
            appendLine("#html:true")
            appendLine("#tags column:3")
            ankiDeck(books).notes.forEach { n ->
                val back = listOf(n.fields[1], n.fields[2].takeIf { it.isNotEmpty() }?.let { "<small>$it</small>" }.orEmpty())
                    .filter { it.isNotEmpty() }.joinToString("<br><br>")
                appendLine(listOf(n.fields[0], back, n.tags.joinToString(" ")).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') })
            }
        }
        AnnotationFormat.ANKI_APKG -> throw IllegalArgumentException("An Anki package is binary: use ankiDeck() and the package writer")
    }

    /** Highlights as Anki notes: the quote on the front, the note and the book on the back. */
    fun ankiDeck(books: List<BookAnnotations>, name: String = "Yomu highlights"): AnkiDeck = AnkiDeck(
        name, AnkiNoteType.HIGHLIGHT,
        books.flatMap { b ->
            b.highlights.map { h ->
                val source = listOfNotNull(
                    b.bookTitle,
                    b.authors.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    h.locator.chapterTitle?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                AnkiNote(
                    key = "h:${b.bookTitle}:${h.createdAt}:${h.text.hashCode()}",
                    fields = listOf(AnkiText.esc(h.text), h.note?.let(AnkiText::esc).orEmpty(), AnkiText.esc(source)),
                    tags = AnkiText.tags(h.tags + "highlight"),
                )
            }
        },
    )

    /** Obsidian compatible Markdown with a YAML header. */
    fun markdown(b: BookAnnotations): String = buildString {
        appendLine("---")
        appendLine("title: \"${b.bookTitle.replace("\"", "\\\"")}\"")
        appendLine("author: \"${b.authors.joinToString(", ").replace("\"", "\\\"")}\"")
        appendLine("tags: [yomu, highlights]")
        appendLine("highlights: ${b.highlights.size}")
        appendLine("exported: ${day.format(Date())}")
        appendLine("---")
        appendLine()
        appendLine("# ${b.bookTitle}")
        if (b.authors.isNotEmpty()) appendLine("*${b.authors.joinToString(", ")}*")
        appendLine()
        val byChapter = b.highlights.sortedWith(compareBy({ it.locator.chapter }, { it.locator.offset ?: 0 })).groupBy { it.locator.chapterTitle }
        for ((chapter, list) in byChapter) {
            if (!chapter.isNullOrBlank()) appendLine("## $chapter\n")
            for (h in list) {
                appendLine("> ${h.text.replace("\n", "\n> ")}")
                val meta = buildList {
                    add(colorName(h.color))
                    if (h.isQuote) add("quote")
                    if (h.isFavorite) add("favorite")
                    addAll(h.tags.map { "#" + it.replace(' ', '_') })
                }
                appendLine(">\n> — ${meta.joinToString(" ")} · ${percent(h.locator)}")
                h.note?.takeIf { it.isNotBlank() }?.let { appendLine("\n$it") }
                appendLine()
            }
        }
        val standalone = b.notes.filter { it.highlightId == null }
        if (standalone.isNotEmpty()) {
            appendLine("## Notes\n")
            standalone.forEach { appendLine("- ${it.text.replace("\n", "\n  ")}") }
            appendLine()
        }
        if (b.bookmarks.isNotEmpty()) {
            appendLine("## Bookmarks\n")
            b.bookmarks.forEach { appendLine("- ${it.title} (${percent(it.locator)})") }
        }
    }

    fun txt(b: BookAnnotations): String = buildString {
        appendLine(b.bookTitle.uppercase())
        if (b.authors.isNotEmpty()) appendLine(b.authors.joinToString(", "))
        appendLine()
        b.highlights.forEach { h ->
            appendLine("\"${h.text}\"")
            h.note?.takeIf { it.isNotBlank() }?.let { appendLine("  Note: $it") }
            appendLine("  ${h.locator.chapterTitle ?: ""} ${percent(h.locator)}".trimEnd())
            appendLine()
        }
        b.notes.filter { it.highlightId == null }.forEach { appendLine("* ${it.text}") }
        b.bookmarks.forEach { appendLine("[Bookmark] ${it.title} ${percent(it.locator)}") }
    }

    fun html(books: List<BookAnnotations>): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Yomu highlights</title>")
        append("<style>body{font-family:sans-serif;max-width:760px;margin:auto;padding:16px}blockquote{margin:12px 0;padding:8px 12px;border-left:4px solid #999}.n{color:#555;font-style:italic}</style></head><body>")
        for (b in books) {
            append("<h1>${esc(b.bookTitle)}</h1><p>${esc(b.authors.joinToString(", "))}</p>")
            for (h in b.highlights) {
                append("<blockquote style=\"border-color:${h.color.cssHex}\">${esc(h.text)}</blockquote>")
                h.note?.let { append("<p class=\"n\">${esc(it)}</p>") }
            }
            b.notes.filter { it.highlightId == null }.forEach { append("<p class=\"n\">${esc(it.text)}</p>") }
        }
        append("</body></html>")
    }

    /** CSV accepted by Readwise import: Highlight,Title,Author,URL,Note,Location,Date */
    fun readwiseCsv(books: List<BookAnnotations>): String = buildString {
        appendLine("Highlight,Title,Author,URL,Note,Location,Date")
        for (b in books) for (h in b.highlights) {
            appendLine(
                listOf(
                    h.text, b.bookTitle, b.authors.joinToString(", "), "", h.note.orEmpty(),
                    ((h.locator.totalProgression * 1000).toInt()).toString(), iso.format(Date(h.createdAt)),
                ).joinToString(",") { Csv.quote(it) },
            )
        }
    }

    data class ImportedHighlight(val title: String, val author: String, val text: String, val note: String?, val location: Float)

    fun parseReadwiseCsv(csv: String): List<ImportedHighlight> {
        val rows = Csv.parse(csv)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun idx(name: String) = header.indexOf(name)
        val hi = idx("highlight")
        val ti = idx("title")
        val ai = idx("author")
        val ni = idx("note")
        val li = idx("location")
        if (hi < 0 || ti < 0) return emptyList()
        return rows.drop(1).filter { it.size > hi }.map { r ->
            ImportedHighlight(
                title = r.getOrElse(ti) { "" },
                author = if (ai >= 0) r.getOrElse(ai) { "" } else "",
                text = r[hi],
                note = if (ni >= 0) r.getOrNull(ni)?.takeIf { it.isNotBlank() } else null,
                location = (if (li >= 0) r.getOrNull(li)?.toFloatOrNull() else null)?.div(1000f) ?: 0f,
            )
        }
    }

    private fun percent(l: Locator) = "${(l.totalProgression * 100).toInt()}%"
    private fun colorName(c: HighlightColor) = "#hl-" + c.name.lowercase()
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    @Serializable
    data class HighlightDto(
        val text: String, val note: String?, val color: String, val style: String, val quote: Boolean, val favorite: Boolean,
        val tags: List<String>, val chapter: Int, val chapterTitle: String?, val offset: Int?, val endOffset: Int?, val progress: Float,
        val created: String,
    )

    @Serializable
    data class BookDto(
        val title: String, val authors: List<String>, val highlights: List<HighlightDto>, val notes: List<String>,
        val bookmarks: List<String>,
    )

    private fun BookAnnotations.toDto() = BookDto(
        title = bookTitle, authors = authors,
        highlights = highlights.map { it.toDto() },
        notes = notes.filter { it.highlightId == null }.map { it.text },
        bookmarks = bookmarks.map { "${it.title} (${percent(it.locator)})" },
    )

    private fun Highlight.toDto() = HighlightDto(
        text, note, color.name, style.name, isQuote, isFavorite, tags, locator.chapter, locator.chapterTitle,
        locator.offset, locator.endOffset, locator.totalProgression, iso.format(Date(createdAt)),
    )
}

enum class WordFormat(val extension: String, val mime: String, val binary: Boolean = false) {
    CSV("csv", "text/csv"),
    ANKI_TSV("txt", "text/plain"),
    /** A ready Anki deck (.apkg) for Anki, AnkiDroid and AnkiMobile. */
    ANKI_APKG("apkg", "application/octet-stream", binary = true),
    /** Quizlet / Memrise / Knowt "Import": term, tab, definition. */
    QUIZLET("txt", "text/plain"),
    JSON("json", "application/json"),
    MARKDOWN("md", "text/markdown"),
}

object WordExporter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Serializable
    data class WordDto(
        val word: String, val translation: String?, val definition: String?, val context: String?, val book: String?,
        val language: String?, val tags: List<String>, val level: Int, val created: Long,
    )

    /** The deck written as .apkg (see `AnkiPackageWriter` in the data layer). */
    fun ankiDeck(words: List<Word>, name: String = "Yomu"): AnkiDeck = AnkiDeck(
        name, AnkiNoteType.WORD,
        words.map { w ->
            AnkiNote(
                key = "w:${w.lang.orEmpty()}:${w.term.lowercase()}",
                fields = listOf(
                    AnkiText.esc(w.term),
                    listOfNotNull(w.translation, w.definition).filter { it.isNotBlank() }.joinToString("<br>") { AnkiText.esc(it) },
                    w.context?.let { AnkiText.withBold(it, w.term) }.orEmpty(),
                    w.bookTitle?.let(AnkiText::esc).orEmpty(),
                ),
                tags = AnkiText.tags(w.tags),
            )
        },
    )

    fun export(words: List<Word>, format: WordFormat): String = when (format) {
        WordFormat.ANKI_APKG -> throw IllegalArgumentException("An Anki package is binary: use ankiDeck() and the package writer")
        WordFormat.QUIZLET -> buildString {
            words.forEach { w ->
                val back = listOfNotNull(w.translation, w.definition).filter { it.isNotBlank() }.joinToString("; ")
                appendLine(listOf(w.term, back).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') })
            }
        }
        WordFormat.JSON -> json.encodeToString(
            words.map { WordDto(it.term, it.translation, it.definition, it.context, it.bookTitle, it.lang, it.tags, it.level, it.createdAt) },
        )
        WordFormat.MARKDOWN -> buildString {
            fun cell(s: String?) = s.orEmpty().replace("|", "\\|").replace("\n", " ")
            appendLine("| Word | Translation | Context | Book |")
            appendLine("| --- | --- | --- | --- |")
            words.forEach { w ->
                appendLine("| **${cell(w.term)}** | ${cell(listOfNotNull(w.translation, w.definition).joinToString("; "))} | ${cell(w.context)} | ${cell(w.bookTitle)} |")
            }
        }
        WordFormat.CSV -> buildString {
            appendLine("word,translation,definition,context,book,language,tags,level")
            words.forEach { w ->
                appendLine(
                    listOf(w.term, w.translation.orEmpty(), w.definition.orEmpty(), w.context.orEmpty(), w.bookTitle.orEmpty(), w.lang.orEmpty(), w.tags.joinToString(" "), w.level.toString())
                        .joinToString(",") { Csv.quote(it) },
                )
            }
        }
        // Anki "Import File" format: front<TAB>back<TAB>tags, HTML allowed.
        WordFormat.ANKI_TSV -> buildString {
            appendLine("#separator:tab")
            appendLine("#html:true")
            appendLine("#tags column:3")
            words.forEach { w ->
                val back = listOfNotNull(w.translation, w.definition, w.context?.let { "<i>${it.replace(w.term, "<b>${w.term}</b>")}</i>" })
                    .joinToString("<br>")
                appendLine(listOf(w.term, back, (w.tags + "yomu").joinToString(" ")).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') })
            }
        }
    }

    fun importCsv(csv: String): List<Word> {
        val rows = Csv.parse(csv)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        val hasHeader = "word" in header || "term" in header
        val wi = if (hasHeader) header.indexOfFirst { it == "word" || it == "term" } else 0
        val ti = if (hasHeader) header.indexOf("translation") else 1
        val di = if (hasHeader) header.indexOf("definition") else -1
        val ci = if (hasHeader) header.indexOf("context") else 2
        val li = if (hasHeader) header.indexOf("language") else -1
        val gi = if (hasHeader) header.indexOf("tags") else -1
        return rows.drop(if (hasHeader) 1 else 0).mapNotNull { r ->
            val term = r.getOrNull(wi)?.trim().orEmpty()
            if (term.isEmpty()) return@mapNotNull null
            Word(
                term = term,
                translation = r.getOrNull(ti)?.takeIf { ti >= 0 && it.isNotBlank() },
                definition = r.getOrNull(di)?.takeIf { di >= 0 && it.isNotBlank() },
                context = r.getOrNull(ci)?.takeIf { ci >= 0 && it.isNotBlank() },
                lang = r.getOrNull(li)?.takeIf { li >= 0 && it.isNotBlank() },
                tags = r.getOrNull(gi)?.takeIf { gi >= 0 }?.split(' ', ',')?.filter { it.isNotBlank() }.orEmpty(),
            )
        }
    }
}

/** RFC 4180 CSV helpers. */
object Csv {
    fun quote(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        val t = text.removePrefix("\uFEFF")
        while (i < t.length) {
            val c = t[i]
            when {
                inQuotes && c == '"' && i + 1 < t.length && t[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row.add(cell.toString()); cell.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < t.length && t[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            if (row.any { it.isNotEmpty() }) rows.add(row)
        }
        return rows
    }
}
