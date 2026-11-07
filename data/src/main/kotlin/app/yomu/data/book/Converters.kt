package app.yomu.data.book

import app.yomu.core.common.AppError
import app.yomu.domain.text.Typography
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.ZipInputStream

/** Result of converting a non EPUB document into the internal EPUB model. */
class Converted(val builder: EpubBuilder, val cover: ByteArray?)

object TextDecoding {
    /** Decodes bytes using the BOM, strict UTF-8 or a Cyrillic/Latin fallback. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        try {
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            // fall through
        }
        val high = bytes.count { (it.toInt() and 0xFF) >= 0xC0 }
        val charset = if (high > bytes.size / 20) Charset.forName("windows-1251") else Charsets.ISO_8859_1
        return String(bytes, charset)
    }
}

object TxtConverter {
    private val heading = Regex(
        "^\\s*((глава|часть|книга|пролог|эпилог|chapter|part|book|prologue|epilogue|розділ|kapitel|chapitre|capítulo)(?![\\p{L}\\p{N}]).{0,80}|(\\d{1,3}|[IVXLC]{1,7})\\.?)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    fun convert(bytes: ByteArray, fileName: String): Converted {
        val text = TextDecoding.decode(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val title = fileName.substringBeforeLast('.')
        val builder = EpubBuilder(EpubMeta(title = title, language = guessLanguage(text)))
        val lines = text.split('\n')
        val chapters = mutableListOf<Pair<String, MutableList<String>>>()
        var current = Pair(title, mutableListOf<String>())
        val para = StringBuilder()
        val blankSeparated = lines.count { it.isBlank() } > lines.size / 8
        fun flushPara() {
            if (para.isNotBlank()) current.second += para.toString().trim()
            para.clear()
        }
        for (line in lines) {
            if (line.length < 90 && heading.matches(line) && line.isNotBlank()) {
                flushPara()
                if (current.second.isNotEmpty() || chapters.isNotEmpty()) chapters += current
                current = Pair(line.trim(), mutableListOf())
                continue
            }
            if (blankSeparated) {
                if (line.isBlank()) flushPara() else para.append(' ').append(line.trim())
            } else if (line.isNotBlank()) {
                current.second += line.trim()
            }
        }
        flushPara()
        chapters += current
        val finalChapters = if (chapters.size == 1 && chapters[0].second.sumOf { it.length } > SPLIT_SIZE) splitBySize(chapters[0].second) else chapters
        finalChapters.forEach { (name, paragraphs) ->
            val body = buildString {
                append("<h2>").append(Xhtml.escape(name)).append("</h2>")
                paragraphs.forEach { append("<p>").append(Xhtml.escape(it)).append("</p>") }
            }
            builder.addChapter(name, body, alreadyXhtml = true)
        }
        return Converted(builder, null)
    }

    private fun splitBySize(paragraphs: List<String>): List<Pair<String, MutableList<String>>> {
        val out = mutableListOf<Pair<String, MutableList<String>>>()
        var part = mutableListOf<String>()
        var size = 0
        paragraphs.forEach {
            part += it
            size += it.length
            if (size > SPLIT_SIZE) {
                out += Pair("${out.size + 1}", part)
                part = mutableListOf()
                size = 0
            }
        }
        if (part.isNotEmpty()) out += Pair("${out.size + 1}", part)
        return out
    }

    fun guessLanguage(text: String): String? {
        val sample = text.take(4000)
        val cyr = sample.count { it in 'а'..'я' || it in 'А'..'Я' }
        val lat = sample.count { it in 'a'..'z' || it in 'A'..'Z' }
        val jp = sample.count { it in '\u3040'..'\u30ff' }
        val cjk = sample.count { it in '\u4e00'..'\u9fff' }
        return when {
            jp > 20 -> "ja"
            cjk > 50 -> "zh"
            cyr > lat -> "ru"
            lat > 50 -> "en"
            else -> null
        }
    }

    private const val SPLIT_SIZE = 40_000
}

object HtmlConverter {
    fun convert(bytes: ByteArray, fileName: String): Converted {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        val title = doc.title().ifBlank { fileName.substringBeforeLast('.') }
        val lang = doc.selectFirst("html")?.attr("lang")?.takeIf { it.isNotBlank() } ?: TxtConverter.guessLanguage(doc.text())
        val author = doc.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it.isNotBlank() }
        val builder = EpubBuilder(EpubMeta(title = title, authors = listOfNotNull(author), language = lang))
        doc.select("script, style, iframe, object, embed, form, nav, noscript").remove()
        embedDataImages(doc.body(), builder)
        val body = doc.body()
        val headings = body.select("h1, h2")
        if (body.text().length > 60_000 && headings.size > 1) {
            var currentTitle = title
            var buffer = StringBuilder()
            body.children().forEach { el ->
                if (el.tagName() == "h1" || el.tagName() == "h2") {
                    if (buffer.isNotBlank()) builder.addChapter(currentTitle, buffer.toString())
                    buffer = StringBuilder()
                    currentTitle = el.text().ifBlank { currentTitle }
                }
                buffer.append(el.outerHtml())
            }
            if (buffer.isNotBlank()) builder.addChapter(currentTitle, buffer.toString())
        } else {
            builder.addChapter(title, body.html())
        }
        return Converted(builder, null)
    }

    internal fun embedDataImages(root: Element, builder: EpubBuilder, chapterDepth: String = "../") {
        root.select("img[src^=data:]").forEachIndexed { i, img ->
            val src = img.attr("src")
            val mime = src.substringAfter("data:").substringBefore(';')
            val data = runCatching { Base64.getMimeDecoder().decode(src.substringAfter(",")) }.getOrNull()
            if (data == null) {
                img.remove()
            } else {
                val href = builder.addResource("img$i." + mime.substringAfter('/'), mime, data)
                img.attr("src", chapterDepth + href)
            }
        }
    }
}

object Fb2Converter {
    fun convert(raw: ByteArray, fileName: String): Converted {
        val bytes = if (raw.size > 4 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte()) unzipFirstFb2(raw) else raw
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
        val titleInfo = doc.firstLocal("title-info")
        val authors = titleInfo?.childrenLocal("author").orEmpty().map { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { a.firstLocal(it)?.text()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .joinToString(" ").ifBlank { a.firstLocal("nickname")?.text().orEmpty() }
        }.filter { it.isNotBlank() }
        val sequence = titleInfo?.firstLocal("sequence")
        val meta = EpubMeta(
            title = titleInfo?.firstLocal("book-title")?.text()?.trim().orEmpty().ifBlank { fileName.substringBeforeLast('.') },
            authors = authors,
            language = titleInfo?.firstLocal("lang")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            description = titleInfo?.firstLocal("annotation")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            publisher = doc.firstLocal("publish-info")?.firstLocal("publisher")?.text()?.takeIf { it.isNotBlank() },
            series = sequence?.attr("name")?.takeIf { it.isNotBlank() },
            seriesIndex = sequence?.attr("number")?.toFloatOrNull(),
            subjects = titleInfo?.childrenLocal("genre").orEmpty().map { it.text() },
            date = titleInfo?.firstLocal("date")?.text(),
        )
        val builder = EpubBuilder(meta)
        val binaries = HashMap<String, String>()
        doc.allLocal("binary").forEach { bin ->
            val id = bin.attr("id")
            val type = bin.attr("content-type").ifBlank { "image/jpeg" }
            val data = runCatching { Base64.getMimeDecoder().decode(bin.text()) }.getOrNull() ?: return@forEach
            binaries[id] = builder.addResource(id, type, data)
        }
        val coverId = titleInfo?.firstLocal("coverpage")?.firstLocal("image")?.let { href(it) }?.removePrefix("#")
        val cover = coverId?.let { id -> builder.resources.firstOrNull { it.href == binaries[id] } }
        builder.coverHref = cover?.href
        val bodies = doc.allLocal("body")
        val main = bodies.firstOrNull { it.attr("name").isBlank() } ?: bodies.firstOrNull() ?: throw AppError.CorruptedFile("FB2 without body")
        val notes = bodies.filter { it !== main }
        val notesHref = if (notes.isNotEmpty()) "text/notes.xhtml" else null
        val ctx = Ctx(binaries, notesHref)
        val sections = main.childrenLocal("section")
        if (sections.isEmpty()) {
            builder.addChapter(meta.title, render(main, ctx, 1), alreadyXhtml = false)
        } else {
            val intro = StringBuilder()
            main.children().filter { !it.tagName().endsWith("section") }.forEach { intro.append(render(it, ctx, 1)) }
            sections.forEachIndexed { i, s ->
                val title = s.childrenLocal("title").firstOrNull()?.text()?.trim().orEmpty().ifBlank { "${i + 1}" }
                val html = (if (i == 0) intro.toString() else "") + render(s, ctx, 1)
                builder.addChapter(title, html)
                // Nested sections are kept inside the chapter but listed in the table of contents only at top level.
            }
        }
        if (notesHref != null) {
            val body = buildString {
                notes.forEach { nb ->
                    nb.childrenLocal("title").firstOrNull()?.let { append("<h2>").append(Xhtml.escape(it.text())).append("</h2>") }
                    nb.allLocal("section").forEach { s ->
                        append("<aside epub:type=\"footnote\" id=\"").append(Xhtml.escape(s.attr("id"))).append("\">")
                        s.children().forEach { append(render(it, ctx, 3)) }
                        append("</aside>")
                    }
                }
            }
            builder.addRawChapter(notes.first().attr("name").ifBlank { "Notes" }, notesHref, Xhtml.page("Notes", Xhtml.fromFragment(body), meta.language))
        }
        return Converted(builder, cover?.bytes)
    }

    private class Ctx(val binaries: Map<String, String>, val notesHref: String?)

    private fun href(el: Element): String = el.attributes().firstOrNull { it.key.endsWith("href") }?.value.orEmpty()

    private fun render(node: Node, ctx: Ctx, depth: Int): String {
        if (node is TextNode) return Xhtml.escape(node.wholeText)
        if (node !is Element) return ""
        val children = { node.childNodes().joinToString("") { render(it, ctx, depth + 1) } }
        return when (node.tagName().substringAfterLast(':')) {
            "section" -> {
                val id = node.attr("id").takeIf { it.isNotBlank() }?.let { " id=\"${Xhtml.escape(it)}\"" }.orEmpty()
                "<section$id>" + node.childNodes().joinToString("") { render(it, ctx, depth + 1) } + "</section>"
            }
            "title" -> {
                val level = depth.coerceIn(1, 4) + 1
                "<h$level>" + node.childrenLocal("p").joinToString("<br/>") { it.childNodes().joinToString("") { c -> render(c, ctx, depth) } } + "</h$level>"
            }
            "p" -> "<p>${children()}</p>"
            "subtitle" -> "<p class=\"subtitle\">${children()}</p>"
            "empty-line" -> "<p class=\"center\">&#160;</p>"
            "emphasis" -> "<em>${children()}</em>"
            "strong" -> "<strong>${children()}</strong>"
            "strikethrough" -> "<s>${children()}</s>"
            "sub" -> "<sub>${children()}</sub>"
            "sup" -> "<sup>${children()}</sup>"
            "code" -> "<code>${children()}</code>"
            "epigraph" -> "<blockquote class=\"epigraph\">${children()}</blockquote>"
            "cite" -> "<blockquote class=\"cite\">${children()}</blockquote>"
            "text-author" -> "<p class=\"subtitle\">${children()}</p>"
            "poem" -> "<div class=\"poem\">${children()}</div>"
            "stanza" -> "<div class=\"stanza\">${children()}</div>"
            "v" -> "<p class=\"v\">${children()}</p>"
            "table" -> "<table>${children()}</table>"
            "tr" -> "<tr>${children()}</tr>"
            "td" -> "<td>${children()}</td>"
            "th" -> "<th>${children()}</th>"
            "image" -> {
                val id = href(node).removePrefix("#")
                ctx.binaries[id]?.let { "<div class=\"center\"><img src=\"../$it\" alt=\"\"/></div>" }.orEmpty()
            }
            "a" -> {
                val target = href(node)
                if (target.startsWith("#") && ctx.notesHref != null) {
                    "<a epub:type=\"noteref\" href=\"${ctx.notesHref.removePrefix("text/")}$target\">${children()}</a>"
                } else if (target.startsWith("http")) {
                    "<a href=\"${Xhtml.escape(target)}\">${children()}</a>"
                } else {
                    children()
                }
            }
            "annotation" -> "<div class=\"cite\">${children()}</div>"
            else -> children()
        }
    }

    private fun unzipFirstFb2(bytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name.endsWith(".fb2", true)) return zip.readBytes()
            }
        }
        throw AppError.CorruptedFile("No .fb2 inside the archive")
    }
}

object DocxConverter {
    fun convert(container: ResourceContainer, fileName: String): Converted {
        val docXml = container.read("word/document.xml") ?: throw AppError.CorruptedFile("Not a DOCX document")
        val doc = EpubParser.parseXml(docXml)
        val core = container.read("docProps/core.xml")?.let { EpubParser.parseXml(it) }
        val meta = EpubMeta(
            title = core?.firstLocal("title")?.text()?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'),
            authors = listOfNotNull(core?.firstLocal("creator")?.text()?.takeIf { it.isNotBlank() }),
            language = core?.firstLocal("language")?.text()?.takeIf { it.isNotBlank() }?.substringBefore('-'),
            description = core?.firstLocal("description")?.text()?.takeIf { it.isNotBlank() },
        )
        val builder = EpubBuilder(meta)
        val rels = container.read("word/_rels/document.xml.rels")?.let { EpubParser.parseXml(it) }
            ?.allLocal("Relationship")?.associate { it.attr("Id") to it.attr("Target") }.orEmpty()
        val images = HashMap<String, String>()
        fun image(rid: String): String? = images.getOrPut(rid) {
            val target = rels[rid] ?: return null
            val path = EpubPaths.resolve("word/document.xml", target)
            val bytes = container.read(path) ?: return null
            val ext = path.substringAfterLast('.').lowercase()
            builder.addResource(path.substringAfterLast('/'), "image/" + if (ext == "jpg") "jpeg" else ext, bytes)
        }
        val body = doc.firstLocal("body") ?: throw AppError.CorruptedFile("Empty DOCX")
        var chapterTitle = meta.title
        var html = StringBuilder()
        fun flush() {
            if (html.isNotBlank()) builder.addChapter(chapterTitle, html.toString())
            html = StringBuilder()
        }
        fun runs(p: Element): String = buildString {
            p.getAllElements().filter { it.tagName() == "w:r" }.forEach { r ->
                val props = r.childrenLocal("rPr").firstOrNull()
                var text = buildString {
                    r.children().forEach { c ->
                        when (c.tagName()) {
                            "w:t" -> append(Xhtml.escape(c.text()))
                            "w:tab" -> append(' ')
                            "w:br" -> append("<br/>")
                            "w:drawing", "w:pict" -> c.getAllElements().firstOrNull { it.tagName() == "a:blip" || it.tagName() == "v:imagedata" }?.let { blip ->
                                val rid = blip.attr("r:embed").ifEmpty { blip.attr("r:id") }
                                image(rid)?.let { append("<img src=\"../$it\" alt=\"\"/>") }
                            }
                        }
                    }
                }
                if (props != null && text.isNotEmpty()) {
                    if (props.childrenLocal("b").any { it.attr("w:val") != "0" && it.attr("w:val") != "false" }) text = "<strong>$text</strong>"
                    if (props.childrenLocal("i").any { it.attr("w:val") != "0" && it.attr("w:val") != "false" }) text = "<em>$text</em>"
                    if (props.childrenLocal("u").any { it.attr("w:val") != "none" }) text = "<u>$text</u>"
                    if (props.childrenLocal("strike").isNotEmpty()) text = "<s>$text</s>"
                    when (props.childrenLocal("vertAlign").firstOrNull()?.attr("w:val")) {
                        "superscript" -> text = "<sup>$text</sup>"
                        "subscript" -> text = "<sub>$text</sub>"
                    }
                }
                append(text)
            }
        }
        body.children().forEach { block ->
            when (block.tagName()) {
                "w:p" -> {
                    val style = block.firstLocal("pStyle")?.attr("w:val").orEmpty().lowercase()
                    val content = runs(block)
                    val level = when {
                        style == "title" || style.endsWith("heading1") || style == "1" || style == "heading 1" -> 1
                        style.endsWith("heading2") || style == "2" -> 2
                        style.startsWith("heading") || style.endsWith("heading3") -> 3
                        else -> 0
                    }
                    when {
                        level == 1 && content.isNotBlank() -> {
                            flush()
                            chapterTitle = Jsoup.parse(content).text().ifBlank { chapterTitle }
                            html.append("<h1>").append(content).append("</h1>")
                        }
                        level > 1 -> html.append("<h$level>").append(content).append("</h$level>")
                        block.firstLocal("numPr") != null -> html.append("<p class=\"li\">• ").append(content).append("</p>")
                        content.isBlank() -> Unit
                        else -> html.append("<p>").append(content).append("</p>")
                    }
                }
                "w:tbl" -> {
                    html.append("<table>")
                    block.childrenLocal("tr").forEach { tr ->
                        html.append("<tr>")
                        tr.childrenLocal("tc").forEach { tc -> html.append("<td>").append(tc.childrenLocal("p").joinToString("<br/>") { runs(it) }).append("</td>") }
                        html.append("</tr>")
                    }
                    html.append("</table>")
                }
            }
        }
        flush()
        if (builder.chapters.isEmpty()) builder.addChapter(meta.title, "<p></p>")
        return Converted(builder, null)
    }
}

/** MOBI (PalmDOC compressed MOBI 6, best effort for KF8 text). HUFF/CDIC compression and DRM are not supported. */
object MobiConverter {
    fun convert(data: ByteArray, fileName: String): Converted {
        val buf = ByteBuffer.wrap(data)
        val numRecords = buf.getShort(76).toInt() and 0xFFFF
        val offsets = IntArray(numRecords) { buf.getInt(78 + it * 8) }
        fun record(i: Int): ByteArray {
            val start = offsets[i]
            val end = if (i + 1 < numRecords) offsets[i + 1] else data.size
            return data.copyOfRange(start, end.coerceAtLeast(start))
        }
        val r0 = ByteBuffer.wrap(record(0))
        val compression = r0.getShort(0).toInt() and 0xFFFF
        val textRecords = r0.getShort(8).toInt() and 0xFFFF
        val encryption = r0.getShort(12).toInt() and 0xFFFF
        if (encryption != 0) throw AppError.UnsupportedFormat("MOBI (DRM)")
        if (compression == 17480) throw AppError.UnsupportedFormat("MOBI (HUFF/CDIC)")
        val hasMobi = r0.capacity() > 20 && String(record(0), 16, 4, Charsets.ISO_8859_1) == "MOBI"
        val headerLength = if (hasMobi) r0.getInt(20) else 0
        val encoding = if (hasMobi) r0.getInt(28) else 1252
        val firstImage = if (hasMobi && headerLength >= 0x5C) r0.getInt(108) else -1
        val extraFlags = if (hasMobi && headerLength >= 0xE4) r0.getShort(242).toInt() and 0xFFFF else 0
        val exth = if (hasMobi && (r0.getInt(128) and 0x40) != 0) parseExth(record(0), 16 + headerLength) else emptyMap()
        val fullName = if (hasMobi) runCatching {
            val off = r0.getInt(84)
            val len = r0.getInt(88)
            String(record(0), off, len, if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252"))
        }.getOrNull() else null
        val out = java.io.ByteArrayOutputStream()
        for (i in 1..textRecords.coerceAtMost(numRecords - 1)) {
            val rec = record(i)
            val size = rec.size - trailingSize(rec, extraFlags)
            val chunk = rec.copyOf(size.coerceAtLeast(0))
            out.write(if (compression == 2) palmDoc(chunk) else chunk)
        }
        val charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        val html = String(out.toByteArray(), charset)
        val meta = EpubMeta(
            title = exth[503] ?: fullName?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'),
            authors = listOfNotNull(exth[100]),
            description = exth[103]?.let { Jsoup.parse(it).text() },
            publisher = exth[101],
            language = exth[524],
        )
        val builder = EpubBuilder(meta)
        val imageHrefs = HashMap<Int, String>()
        fun imageHref(recIndex: Int): String? {
            if (firstImage < 0) return null
            val idx = firstImage + recIndex - 1
            if (idx !in 0 until numRecords) return null
            return imageHrefs.getOrPut(idx) {
                val bytes = record(idx)
                val mime = when {
                    bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                    bytes.size > 3 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
                    bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
                    else -> return null
                }
                builder.addResource("img$idx.${mime.substringAfter('/')}", mime, bytes)
            }
        }
        val parts = html.split(Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE)).filter { it.isNotBlank() }
        val merged = mergeSmall(parts)
        merged.forEachIndexed { i, part ->
            val doc = Jsoup.parseBodyFragment(part)
            doc.select("img[recindex]").forEach { img ->
                val href = img.attr("recindex").toIntOrNull()?.let { imageHref(it) }
                if (href != null) img.attr("src", "../$href").removeAttr("recindex") else img.remove()
            }
            doc.select("img[src^=kindle:embed:]").forEach { img ->
                val code = img.attr("src").removePrefix("kindle:embed:").substringBefore('?')
                val href = base32(code)?.let { imageHref(it) }
                if (href != null) img.attr("src", "../$href") else img.remove()
            }
            doc.select("a[filepos]").forEach { it.unwrap() }
            doc.select("script, style, mbp|section").remove()
            val title = doc.selectFirst("h1, h2, h3, b")?.text()?.take(80)?.takeIf { it.isNotBlank() } ?: "${i + 1}"
            builder.addChapter(title, doc.body().html())
        }
        val cover = exth[201]?.toIntOrNull()?.let { coverOff ->
            if (firstImage >= 0 && firstImage + coverOff < numRecords) record(firstImage + coverOff) else null
        }
        if (cover != null) builder.coverHref = builder.addResource("cover.jpg", "image/jpeg", cover)
        if (builder.chapters.isEmpty()) throw AppError.CorruptedFile("MOBI without text")
        return Converted(builder, cover)
    }

    private fun mergeSmall(parts: List<String>): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        parts.forEach {
            sb.append(it)
            if (sb.length > 3000) {
                out += sb.toString()
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun base32(code: String): Int? = runCatching { code.toInt(32) }.getOrNull()

    private fun parseExth(rec: ByteArray, offset: Int): Map<Int, String> {
        val b = ByteBuffer.wrap(rec)
        if (offset + 12 > rec.size || String(rec, offset, 4, Charsets.ISO_8859_1) != "EXTH") return emptyMap()
        val count = b.getInt(offset + 8)
        var pos = offset + 12
        val out = HashMap<Int, String>()
        repeat(count) {
            if (pos + 8 > rec.size) return out
            val type = b.getInt(pos)
            val len = b.getInt(pos + 4)
            if (len < 8 || pos + len > rec.size) return out
            val value = rec.copyOfRange(pos + 8, pos + len)
            out[type] = if (type == 201 || type == 202) ByteBuffer.wrap(value.copyOf(4)).int.toString() else String(value, Charsets.UTF_8)
            pos += len
        }
        return out
    }

    private fun trailingSize(data: ByteArray, flags: Int): Int {
        fun entry(size: Int): Int {
            var bitpos = 0
            var result = 0
            var s = size
            if (s <= 0) return 0
            while (true) {
                val v = data[s - 1].toInt() and 0xFF
                result = result or ((v and 0x7F) shl bitpos)
                bitpos += 7
                s--
                if ((v and 0x80) != 0 || bitpos >= 28 || s == 0) return result
            }
        }
        var num = 0
        var test = flags shr 1
        while (test != 0) {
            if (test and 1 != 0) num += entry(data.size - num)
            test = test shr 1
        }
        if (flags and 1 != 0 && data.size - num - 1 >= 0) num += (data[data.size - num - 1].toInt() and 0x3) + 1
        return num.coerceAtMost(data.size)
    }

    fun palmDoc(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(input.size * 2)
        val buffer = ArrayList<Byte>(input.size * 2)
        var i = 0
        while (i < input.size) {
            val c = input[i++].toInt() and 0xFF
            when {
                c in 1..8 -> {
                    repeat(c) { if (i < input.size) buffer.add(input[i++]) }
                }
                c < 128 -> buffer.add(c.toByte())
                c >= 192 -> {
                    buffer.add(' '.code.toByte())
                    buffer.add((c xor 0x80).toByte())
                }
                else -> if (i < input.size) {
                    val pair = (c shl 8) or (input[i++].toInt() and 0xFF)
                    val distance = (pair shr 3) and 0x07FF
                    val length = (pair and 7) + 3
                    if (distance in 1..buffer.size) {
                        val start = buffer.size - distance
                        repeat(length) { buffer.add(buffer[start + it]) }
                    }
                }
            }
        }
        out.write(buffer.toByteArray())
        return out.toByteArray()
    }
}

object TextCleanup {
    /** Joins hard wrapped lines of a converted text. */
    fun unwrap(paragraphs: List<String>): List<String> = Typography.unwrapLines(paragraphs)
}
