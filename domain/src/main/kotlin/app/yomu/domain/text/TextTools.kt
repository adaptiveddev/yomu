package app.yomu.domain.text

/** Sentence and word helpers shared by the readers, TTS and the dictionary. */
object TextTools {
    private const val TERMINATORS = ".!?…。！？\n"
    private const val CLOSERS = "\"'»”’)]」』）"

    // Whitespace after a terminator (and optional closing quotes); CJK terminators split even without whitespace.
    private val sentenceEnd = Regex("(?<=[.!?…][\"'»”’)\\]]{0,3})\\s+|(?<=[。！？][」』）)]{0,2})(?![」』）)])\\s*")

    fun sentences(text: String): List<String> = text.split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }

    /** Sentence of [text] containing character index [index]. */
    fun sentenceAt(text: String, index: Int): String {
        if (text.isEmpty()) return ""
        val i = index.coerceIn(0, text.length - 1)
        var start = i
        while (start > 0 && text[start - 1] !in TERMINATORS) start--
        // Closing quotes right after the previous terminator belong to the previous sentence.
        if (start > 0) while (start < i && text[start] in CLOSERS) start++
        var end = i
        while (end < text.length && text[end] !in TERMINATORS) end++
        while (end < text.length && text[end] in TERMINATORS && text[end] != '\n') end++
        while (end < text.length && text[end] in CLOSERS) end++
        return text.substring(start, end).trim()
    }

    fun isCjk(c: Char): Boolean {
        val s = Character.UnicodeScript.of(c.code)
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA
    }

    private val hashtag = Regex("(?<![\\p{L}\\p{N}_&/#])#([\\p{L}\\p{N}_][\\p{L}\\p{N}_-]*)")

    /** `#tags` written in a note (lowercase, without `#`, in order of appearance). Markdown headings (`# Title`) are not tags. */
    fun hashtags(text: String?): List<String> =
        if (text.isNullOrBlank()) emptyList() else hashtag.findAll(text).map { it.groupValues[1].trimEnd('-').lowercase() }.distinct().toList()

    fun cleanSelection(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    fun isSingleWord(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && t.length <= 40 && t.none { it.isWhitespace() }
    }

    /** Expand a URL template containing {text} and optional {lang}. */
    fun expandTemplate(template: String, text: String, lang: String = "en"): String =
        template.replace("{text}", java.net.URLEncoder.encode(text, "UTF-8")).replace("{lang}", lang)
}

/** Plain or regex search and replace with preview, used by the book editor. */
object SearchReplace {
    data class Match(val start: Int, val end: Int, val preview: String)

    fun pattern(query: String, regex: Boolean, caseSensitive: Boolean, wholeWord: Boolean = false): Regex {
        val base = if (regex) query else Regex.escape(query)
        val withWord = if (wholeWord) "(?<![\\p{L}\\p{N}])(?:$base)(?![\\p{L}\\p{N}])" else base
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(withWord, options)
    }

    fun find(text: String, pattern: Regex, context: Int = 30, limit: Int = 500): List<Match> =
        pattern.findAll(text).take(limit).map { m ->
            val s = (m.range.first - context).coerceAtLeast(0)
            val e = (m.range.last + 1 + context).coerceAtMost(text.length)
            Match(m.range.first, m.range.last + 1, text.substring(s, e).replace('\n', ' '))
        }.toList()

    /** Returns new text and the number of replacements. In regex mode $1.. groups are supported. */
    fun replaceAll(text: String, pattern: Regex, replacement: String, regex: Boolean): Pair<String, Int> {
        var count = 0
        val result = pattern.replace(text) { m ->
            count++
            if (regex) {
                var r = replacement
                for (g in m.groupValues.indices.reversed()) r = r.replace("$$g", m.groupValues[g])
                r
            } else {
                replacement
            }
        }
        return result to count
    }
}

/** Typographic clean-up for plain text fragments (applied to text nodes by the editor). */
object Typography {
    fun collapseSpaces(s: String): String = s.replace(Regex("[ \\t\\u00A0]{2,}"), " ")

    fun fixDashes(s: String): String = s
        .replace(Regex("(?<=\\s)--?(?=\\s)"), "—")
        .replace(Regex("^--?(?=\\s)", RegexOption.MULTILINE), "—")

    fun fixQuotes(s: String, lang: String?): String {
        val (open, close) = if (lang?.startsWith("ru") == true || lang?.startsWith("uk") == true) "«" to "»" else "“" to "”"
        val sb = StringBuilder(s.length)
        var openNext = true
        for ((i, c) in s.withIndex()) {
            if (c == '"') {
                val prev = if (i > 0) s[i - 1] else ' '
                openNext = prev.isWhitespace() || prev in "([{—-"
                sb.append(if (openNext) open else close)
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Joins lines broken in the middle of a sentence (typical for TXT books with hard wraps). */
    fun unwrapLines(paragraphs: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (p in paragraphs) {
            val last = out.lastOrNull()
            if (last != null && last.isNotEmpty() && last.last() !in ".!?…:;\"»”)" && p.firstOrNull()?.isLowerCase() == true) {
                out[out.lastIndex] = "$last $p"
            } else {
                out += p
            }
        }
        return out
    }
}
