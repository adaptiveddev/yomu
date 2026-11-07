package app.yomu.data.book

/** Basic paragraph reconstruction from PDF text. PDF has no reliable semantic paragraph model. */
object PdfReflowText {
    fun paragraphs(text: String): List<String> = text
        .replace("\r\n", "\n").replace('\r', '\n')
        .split(Regex("\\n[\\t ]*\\n+"))
        .map { p ->
            p.replace(Regex("\\u00ad\\n[\\t ]*"), "")
                .replace(Regex("-\\n[\\t ]*(?=\\p{Ll})"), "-")
                .replace(Regex("\\n[\\t ]*"), " ")
                .trim()
        }.filter { it.isNotEmpty() }
}
