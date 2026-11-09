package app.yomu.domain.export

/** Note types of the Anki decks Yomu writes; [id] is fixed so that a new export updates the cards of an older one. */
enum class AnkiNoteType(val id: Long, val title: String, val fields: List<String>, val front: String, val back: String) {
    WORD(
        1_712_000_000_101, "Yomu word", listOf("Word", "Meaning", "Context", "Source"),
        front = "<div class=word>{{Word}}</div>",
        back = "{{FrontSide}}<hr id=answer>{{Meaning}}{{#Context}}<div class=context>{{Context}}</div>{{/Context}}{{#Source}}<div class=source>{{Source}}</div>{{/Source}}",
    ),
    HIGHLIGHT(
        1_712_000_000_102, "Yomu highlight", listOf("Text", "Note", "Source"),
        front = "<div class=context>{{Text}}</div>",
        back = "{{FrontSide}}<hr id=answer>{{Note}}{{#Source}}<div class=source>{{Source}}</div>{{/Source}}",
    ),
    ;

    companion object {
        const val CSS = ".card{font-family:sans-serif;font-size:22px;text-align:center;color:black;background-color:white}" +
            ".word{font-size:32px}.context{font-style:italic;margin-top:12px}.source{font-size:14px;color:#888;margin-top:12px}"
    }
}

/** One note: [fields] follow its note type; [key] identifies it across exports (Anki updates instead of duplicating). */
data class AnkiNote(val key: String, val fields: List<String>, val tags: List<String>)

data class AnkiDeck(val name: String, val type: AnkiNoteType, val notes: List<AnkiNote>)

internal object AnkiText {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    /** Anki tags cannot contain spaces. */
    fun tags(tags: List<String>) = (tags + "yomu").map { it.trim().replace(Regex("\\s+"), "_") }.filter { it.isNotEmpty() }.distinct()

    /** Escaped [context] with every occurrence of [term] in bold. */
    fun withBold(context: String, term: String): String {
        if (term.isBlank()) return esc(context)
        val out = StringBuilder()
        var i = 0
        while (true) {
            val at = context.indexOf(term, i, ignoreCase = true)
            if (at < 0) break
            out.append(esc(context.substring(i, at))).append("<b>").append(esc(context.substring(at, at + term.length))).append("</b>")
            i = at + term.length
        }
        return out.append(esc(context.substring(i))).toString()
    }
}
