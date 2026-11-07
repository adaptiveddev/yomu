package app.yomu.data.book.docx

import app.yomu.core.common.AppError
import app.yomu.data.book.EpubParser
import app.yomu.data.book.EpubPaths
import app.yomu.data.book.ResourceContainer
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.math.abs
import kotlin.math.max

/**
 * Print layout of a DOCX document, "as in Word": the document is rendered into one HTML page with absolutely formatted blocks, the page
 * geometry of every section and header/footer templates. The pagination runs in the WebView (assets/docx/paginate.js), because only the
 * browser knows where the lines break.
 *
 * Supported: styles (document defaults, basedOn chains, character styles, table styles with conditional formatting), theme fonts,
 * numbering, paragraph spacing, indents, borders, shading, tab stops and keep options, runs (fonts, sizes, colors, highlight, underline,
 * strike, caps, super/subscript, hidden text), fields (PAGE, NUMPAGES, cached results of the others), hyperlinks and bookmarks, tables
 * (grid, merged cells, borders, shading, cell margins, repeated header rows), inline and floating images, text boxes, footnotes and
 * endnotes, sections (page size and orientation, margins, headers/footers with a different first page and even pages, page numbering).
 * Approximated: text wrapping around floating objects, frames; not supported: columns, charts/SmartArt/EMF (placeholders), equations
 * (plain text), comments and tracked changes (the final text is shown).
 */
class DocxLayout private constructor(
    private val container: ResourceContainer,
    private val media: (name: String, bytes: ByteArray) -> Unit,
) {
    class Result(val html: String, val title: String?, val sections: Int, val images: Int, val footnotes: Int)

    companion object {
        /** Folder of the images next to the HTML file. */
        const val MEDIA_DIR = "m"

        /** Renders the document; images are passed to [media] with file names inside [MEDIA_DIR]. */
        fun render(container: ResourceContainer, media: (name: String, bytes: ByteArray) -> Unit): Result = DocxLayout(container, media).run()

        private val TABLE_SIDES = listOf("top", "left", "bottom", "right", "insideH", "insideV")
        private val SIDES = listOf("top", "left", "bottom", "right")
        private const val DEFAULT_CELL_MARGIN = 108 / 15.0
        private val IMAGE_TYPES = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg")
        private const val RUSSIAN = "АБВГДЕЖЗИКЛМНОПРСТУФХЦЧШЩЭЮЯ"

        internal fun attr(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        internal fun roman(n: Int): String {
            if (n <= 0 || n >= 4000) return n.toString()
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n
            return buildString { for (i in v.indices) while (x >= v[i]) { append(s[i]); x -= v[i] } }
        }

        internal fun numberText(n: Int, format: String): String = when (format) {
            "lowerLetter" -> letters(n, ('a'..'z').joinToString(""))
            "upperLetter" -> letters(n, ('A'..'Z').joinToString(""))
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            "russianLower" -> letters(n, RUSSIAN.lowercase())
            "russianUpper" -> letters(n, RUSSIAN)
            "decimalZero" -> if (n in 0..9) "0$n" else n.toString()
            "decimalEnclosedCircle", "decimalEnclosedCircleChinese" -> if (n in 1..20) ('\u2460' + (n - 1)).toString() else n.toString()
            "none" -> ""
            else -> n.toString()
        }

        /** Word repeats the letter after the alphabet: a … z, aa … zz. */
        private fun letters(n: Int, alphabet: String): String {
            if (n <= 0) return n.toString()
            val i = (n - 1) % alphabet.length
            return alphabet[i].toString().repeat((n - 1) / alphabet.length + 1)
        }

        /** Characters of the Symbol and Wingdings fonts (private use area F0xx) mapped to Unicode. */
        internal fun symbolChar(ch: Char, font: String?): String {
            val code = ch.code
            val c = if (code in 0xF000..0xF0FF) code - 0xF000 else code
            val f = font.orEmpty().lowercase()
            return when {
                f.startsWith("wingdings") -> when (c) {
                    0xA7 -> "▪"; 0xA8 -> "◻"; 0xD8 -> "➢"; 0x76 -> "❖"; 0xFC -> "✓"; 0xFB -> "✗"; 0xFE -> "☑"; 0x6C -> "●"
                    0x6E -> "■"; 0x6F -> "□"; 0x71 -> "❑"; 0x75 -> "◆"; 0xA1 -> "○"; 0x9F -> "•"; 0xE0 -> "→"; 0xE8 -> "➔"
                    0xF0 -> "⇨"; 0x4A -> "☺"; 0x4C -> "☹"; 0x28 -> "☎"; 0x2A -> "✉"
                    else -> if (code in 0xF000..0xF0FF || c < 0x80) "•" else ch.toString()
                }
                f.startsWith("symbol") -> when (c) {
                    0xB7 -> "•"; 0x2D -> "−"; 0xB4 -> "×"; 0xB0 -> "°"; 0xB1 -> "±"; 0xAE -> "→"; 0xAC -> "←"; 0xA3 -> "≤"; 0xB3 -> "≥"
                    0xB9 -> "≠"; 0xBB -> "≈"; 0xA5 -> "∞"; 0xD6 -> "√"; 0xE5 -> "∑"; 0xF2 -> "∫"; 0xA8 -> "♦"; 0xA7 -> "♣"; 0xA9 -> "♥"; 0xAA -> "♠"
                    in 0x41..0x5A, in 0x61..0x7A -> greek(c)
                    else -> if (code in 0xF000..0xF0FF) "•" else ch.toString()
                }
                code in 0xF000..0xF0FF -> "•"
                else -> ch.toString()
            }
        }

        private fun greek(c: Int): String {
            val latin = "ABGDEZHQIKLMNXOPRSTUFCYW"
            val greek = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ"
            val upper = c in 0x41..0x5A
            val i = latin.indexOf((if (upper) c else c - 32).toChar())
            if (i < 0) return c.toChar().toString()
            return if (upper) greek[i].toString() else greek[i].lowercase()
        }

        internal fun isSymbolFont(name: String?): Boolean {
            val f = name.orEmpty().lowercase()
            return f.startsWith("symbol") || f.startsWith("wingdings") || f.startsWith("webdings")
        }

        /** Height of a single line relative to the font size, as Word computes it (ascent + descent + line gap of the font). */
        internal fun lineRatio(font: String): Double {
            val f = font.lowercase()
            return when {
                f.startsWith("calibri") || f == "carlito" -> 1.2207
                f.startsWith("cambria") -> 1.1724
                f.startsWith("times") || f.startsWith("liberation") || f == "arial" || f.startsWith("arial ") || f == "tinos" || f == "arimo" -> 1.149
                f.startsWith("courier") -> 1.133
                f.startsWith("verdana") -> 1.2153
                f.startsWith("tahoma") -> 1.2075
                f.startsWith("georgia") -> 1.1362
                f.startsWith("segoe") -> 1.33
                f.startsWith("trebuchet") -> 1.1611
                f.startsWith("pt ") -> 1.16
                else -> 1.17
            }
        }
    }

    private class Rel(val target: String, val type: String, val external: Boolean)
    private class Part(val path: String, val rels: Map<String, Rel>)
    private class Ctx(val part: Part, val cell: CellFormat?, val width: Double, val sectionLeft: Double)
    private class Num(var abstractId: String, val levels: Map<Int, Level>, val starts: Map<Int, Int>)
    private class Field(val instr: StringBuilder = StringBuilder(), var separated: Boolean = false, var kind: String? = null)
    private class TableStyle(val borders: Map<String, Border>, val margins: Map<String, Double>, val parts: Map<String, CellFormat>, val rowBand: Int, val colBand: Int)

    private val styles = HashMap<String, Style>()
    private var defaultParagraphStyle: String? = null
    private var defaultTableStyle: String? = null
    private var defaultCharStyle: String? = null
    private var docRPr = RPr(sizePt = 10.0)
    private var docPPr = PPr()
    private var minorFont = "Calibri"
    private var majorFont = "Calibri Light"
    private val fontFamilies = HashMap<String, String>()
    private val abstractLevels = HashMap<String, Map<Int, Level>>()
    private val nums = HashMap<String, Num>()
    private val counters = HashMap<String, IntArray>()
    private var defaultTab = 48.0
    private var evenAndOdd = false
    private var compatMode = 12
    private var autoHyphen = false
    private var lang: String? = null
    private val mediaNames = HashMap<String, String>()
    private val footnoteEls = HashMap<String, Element>()
    private val endnoteEls = HashMap<String, Element>()
    private var footnotePart: Part? = null
    private var endnotePart: Part? = null
    private val footnoteNo = LinkedHashMap<String, Int>()
    private val endnoteNo = LinkedHashMap<String, Int>()
    private var currentNote: String? = null
    private var fields = ArrayList<Field>()
    private val pPrCache = HashMap<String, PPr>()
    private val rPrCache = HashMap<String, RPr>()
    private val partCache = HashMap<String, String>()

    private fun xml(path: String): Document? = container.read(path)?.let { EpubParser.parseXml(it) }

    private fun relsOf(path: String): Map<String, Rel> {
        val dir = path.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + path.substringAfterLast('/') + ".rels"
        val doc = xml(relsPath) ?: return emptyMap()
        return doc.getElementsByTag("Relationship").associate { r ->
            val external = r.attr("TargetMode").equals("External", true)
            val target = r.attr("Target")
            r.attr("Id") to Rel(if (external) target else EpubPaths.resolve(path, target), r.attr("Type").substringAfterLast('/'), external)
        }
    }

    private fun run(): Result {
        val mainPath = xml("_rels/.rels")?.getElementsByTag("Relationship")
            ?.firstOrNull { it.attr("Type").endsWith("/officeDocument") }?.attr("Target")?.removePrefix("/") ?: "word/document.xml"
        val doc = xml(mainPath) ?: xml("word/document.xml") ?: throw AppError.CorruptedFile("Not a DOCX document")
        val part = Part(mainPath, relsOf(mainPath))
        fun related(type: String) = part.rels.values.firstOrNull { it.type == type && !it.external }?.target
        loadSettings(related("settings") ?: "word/settings.xml")
        loadTheme(related("theme") ?: "word/theme/theme1.xml")
        loadFonts(related("fontTable") ?: "word/fontTable.xml")
        loadStyles(related("styles") ?: "word/styles.xml")
        loadNumbering(related("numbering") ?: "word/numbering.xml")
        related("footnotes")?.let { p -> footnotePart = Part(p, relsOf(p)); loadNotes(p, "w:footnote", footnoteEls) }
        related("endnotes")?.let { p -> endnotePart = Part(p, relsOf(p)); loadNotes(p, "w:endnote", endnoteEls) }
        val title = xml("docProps/core.xml")?.getElementsByTag("dc:title")?.firstOrNull()?.text()?.takeIf { it.isNotBlank() }
        val body = doc.getElementsByTag("w:body").firstOrNull() ?: throw AppError.CorruptedFile("Empty DOCX")

        val sectionsHtml = StringBuilder()
        val blocks = flatBlocks(body.children())
        val ranges = ArrayList<Pair<Element?, IntRange>>()
        var start = 0
        blocks.forEachIndexed { i, e ->
            val sp = if (e.tagName() == "w:p") e.child("w:pPr")?.child("w:sectPr") else null
            if (sp != null) {
                ranges += sp to (start..i)
                start = i + 1
            }
        }
        ranges += body.child("w:sectPr") to (start until blocks.size)
        var refs = emptyMap<String, String>()
        var maxWidth = 0.0
        ranges.forEachIndexed { index, (sectPr, range) ->
            if (range.isEmpty() && index > 0 && index == ranges.lastIndex && sectPr == null) return@forEachIndexed
            val g = Geometry.parse(sectPr)
            maxWidth = max(maxWidth, g.w)
            refs = refs + headerRefs(sectPr, part)
            val textWidth = g.w - g.ml - g.mr
            sectionsHtml.append("<section class=\"sec\"").append(g.data())
            if (sectPr?.child("w:titlePg").onOff() == true) sectionsHtml.append(" data-tp=\"1\"")
            sectPr?.child("w:pgNumType")?.let { n ->
                n.wAttr("start")?.toIntOrNull()?.let { sectionsHtml.append(" data-ps=\"").append(it).append('"') }
                n.wAttr("fmt")?.let { sectionsHtml.append(" data-pf=\"").append(attr(it)).append('"') }
            }
            sectionsHtml.append('>')
            for ((key, path) in refs) {
                val html = headerHtml(path, textWidth, g.ml)
                sectionsHtml.append("<template data-k=\"").append(key).append("\">").append(html).append("</template>")
            }
            sectionsHtml.append("<div class=\"flow\" style=\"width:").append(textWidth.css()).append("px\">")
            val ctx = Ctx(part, null, textWidth, g.ml)
            for (i in range) {
                val e = blocks[i]
                when (e.tagName()) {
                    "w:p" -> paragraph(e, ctx, sectionsHtml, blocks.getOrNull(i - 1), blocks.getOrNull(i + 1))
                    "w:tbl" -> table(e, ctx, sectionsHtml)
                }
            }
            if (index == ranges.lastIndex) endnotes(sectionsHtml, Ctx(endnotePart ?: part, null, textWidth, g.ml))
            sectionsHtml.append("</div></section>")
        }

        val notes = StringBuilder()
        val fnPart = footnotePart
        if (fnPart != null) {
            for ((id, _) in footnoteNo.entries.toList()) {
                val el = footnoteEls[id] ?: continue
                currentNote = footnoteNo[id]?.toString()
                notes.append("<div class=\"fn\" data-fn=\"").append(attr(id)).append("\">")
                blocksOf(el.children(), Ctx(fnPart, null, 600.0, 0.0), notes)
                notes.append("</div>")
            }
            currentNote = null
        }

        val out = StringBuilder(sectionsHtml.length + notes.length + 1024)
        out.append("<!DOCTYPE html><html lang=\"").append(attr(lang ?: "en")).append("\"><head><meta charset=\"utf-8\">")
        out.append("<meta name=\"viewport\" content=\"width=").append((maxWidth + 24).toInt()).append("\">")
        out.append("<title>").append(esc(title ?: "")).append("</title><link rel=\"stylesheet\" href=\"layout.css\"></head>")
        out.append("<body data-dt=\"").append(defaultTab.css()).append("\" data-eo=\"").append(if (evenAndOdd) 1 else 0).append('"')
        if (autoHyphen) out.append(" class=\"hy\"")
        out.append("><div id=\"src\">").append(sectionsHtml)
        out.append("<div id=\"fnstore\">").append(notes).append("</div></div><div id=\"pages\"></div>")
        out.append("<script src=\"paginate.js\"></script></body></html>")
        return Result(out.toString(), title, ranges.size, mediaNames.size, footnoteNo.size)
    }

    // ------------------------------------------------------------------------------------------------------------------ document parts

    private fun loadSettings(path: String) {
        val doc = xml(path) ?: return
        twips(doc.getElementsByTag("w:defaultTabStop").firstOrNull()?.wAttr("val"))?.takeIf { it > 1 }?.let { defaultTab = it }
        evenAndOdd = doc.getElementsByTag("w:evenAndOddHeaders").firstOrNull().onOff() == true
        autoHyphen = doc.getElementsByTag("w:autoHyphenation").firstOrNull().onOff() == true
        compatMode = doc.getElementsByTag("w:compatSetting").firstOrNull { it.wAttr("name") == "compatibilityMode" }?.wAttr("val")?.toIntOrNull() ?: 12
    }

    private fun loadTheme(path: String) {
        val doc = xml(path) ?: return
        doc.getElementsByTag("a:minorFont").firstOrNull()?.child("a:latin")?.attr("typeface")?.takeIf { it.isNotBlank() }?.let { minorFont = it }
        doc.getElementsByTag("a:majorFont").firstOrNull()?.child("a:latin")?.attr("typeface")?.takeIf { it.isNotBlank() }?.let { majorFont = it }
    }

    private fun loadFonts(path: String) {
        val doc = xml(path) ?: return
        for (f in doc.getElementsByTag("w:font")) {
            val name = f.wAttr("name") ?: continue
            fontFamilies[name.lowercase()] = f.child("w:family")?.wAttr("val") ?: "auto"
        }
    }

    private fun loadStyles(path: String) {
        val doc = xml(path) ?: return
        val dd = doc.getElementsByTag("w:docDefaults").firstOrNull()
        val defR = dd?.child("w:rPrDefault")?.child("w:rPr")
        docRPr = RPr(sizePt = 10.0).merge(RPr.parse(defR))
        docPPr = PPr.parse(dd?.child("w:pPrDefault")?.child("w:pPr")) ?: PPr()
        lang = defR?.child("w:lang")?.wAttr("val")
        for (s in doc.getElementsByTag("w:style")) {
            val id = s.wAttr("styleId") ?: continue
            val type = s.wAttr("type") ?: "paragraph"
            val isDefault = s.wAttr("default").let { it == "1" || it == "true" }
            var borders = emptyMap<String, Border>()
            var margins = emptyMap<String, Double>()
            val parts = HashMap<String, CellFormat>()
            var rowBand = 1
            var colBand = 1
            if (type == "table") {
                val tblPr = s.child("w:tblPr")
                borders = Border.parseAll(tblPr?.child("w:tblBorders"), TABLE_SIDES)
                margins = cellMargins(tblPr?.child("w:tblCellMar"))
                rowBand = tblPr?.child("w:tblStyleRowBandSize")?.wAttr("val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                colBand = tblPr?.child("w:tblStyleColBandSize")?.wAttr("val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                parts["wholeTable"] = cellFormat(s.child("w:tcPr"), s.child("w:rPr"), s.child("w:pPr"))
                for (c in s.kids("w:tblStylePr")) {
                    val t = c.wAttr("type") ?: continue
                    parts[t] = (parts[t] ?: CellFormat()).merge(cellFormat(c.child("w:tcPr"), c.child("w:rPr"), c.child("w:pPr")))
                }
            }
            styles[id] = Style(
                id, type, s.child("w:name")?.wAttr("val") ?: id, s.child("w:basedOn")?.wAttr("val"), isDefault,
                if (type == "table") null else PPr.parse(s.child("w:pPr")), if (type == "table") null else RPr.parse(s.child("w:rPr")),
                borders, margins, parts, rowBand, colBand,
            )
            if (isDefault) when (type) {
                "paragraph" -> defaultParagraphStyle = id
                "table" -> defaultTableStyle = id
                "character" -> defaultCharStyle = id
            }
        }
    }

    private fun cellFormat(tcPr: Element?, rPr: Element?, pPr: Element?) = CellFormat(
        shading = shading(tcPr?.child("w:shd")),
        borders = Border.parseAll(tcPr?.child("w:tcBorders"), TABLE_SIDES),
        rPr = RPr.parse(rPr),
        pPr = PPr.parse(pPr),
        vAlign = tcPr?.child("w:vAlign")?.wAttr("val"),
    )

    private fun cellMargins(e: Element?): Map<String, Double> {
        if (e == null) return emptyMap()
        val out = HashMap<String, Double>()
        for ((side, alias) in listOf("top" to "top", "left" to "start", "bottom" to "bottom", "right" to "end")) {
            val m = e.child("w:$side") ?: e.child("w:$alias") ?: continue
            if (m.wAttr("type").let { it == null || it == "dxa" }) twips(m.wAttr("w"))?.let { out[side] = it }
        }
        return out
    }

    private fun loadNumbering(path: String) {
        val doc = xml(path) ?: return
        val abstracts = HashMap<String, Element>()
        for (a in doc.getElementsByTag("w:abstractNum")) {
            val id = a.wAttr("abstractNumId") ?: continue
            abstracts[id] = a
            abstractLevels[id] = a.kids("w:lvl").associate { l -> (l.wAttr("ilvl")?.toIntOrNull() ?: 0) to level(l) }
        }
        for (n in doc.getElementsByTag("w:num")) {
            val id = n.wAttr("numId") ?: continue
            val abs = n.child("w:abstractNumId")?.wAttr("val") ?: continue
            val starts = HashMap<Int, Int>()
            val levels = HashMap<Int, Level>()
            for (o in n.kids("w:lvlOverride")) {
                val il = o.wAttr("ilvl")?.toIntOrNull() ?: continue
                o.child("w:startOverride")?.wAttr("val")?.toIntOrNull()?.let { starts[il] = it }
                o.child("w:lvl")?.let { levels[il] = level(it) }
            }
            nums[id] = Num(abs, levels, starts)
        }
        // Lists defined through a numbering style: the abstract numbering only links to the style, whose num has the levels.
        for (num in nums.values) {
            val link = abstracts[num.abstractId]?.child("w:numStyleLink")?.wAttr("val") ?: continue
            val linkedNum = styles[link]?.pPr?.numId ?: continue
            nums[linkedNum]?.abstractId?.takeIf { it != num.abstractId }?.let { num.abstractId = it }
        }
    }

    private fun level(l: Element) = Level(
        start = l.child("w:start")?.wAttr("val")?.toIntOrNull() ?: 0,
        format = l.child("w:numFmt")?.wAttr("val") ?: "decimal",
        text = l.child("w:lvlText")?.wAttr("val") ?: "",
        jc = l.child("w:lvlJc")?.wAttr("val") ?: "left",
        pPr = PPr.parse(l.child("w:pPr")),
        rPr = RPr.parse(l.child("w:rPr")),
        suffix = l.child("w:suff")?.wAttr("val") ?: "tab",
        legal = l.child("w:isLgl").onOff() == true,
    )

    private fun loadNotes(path: String, tag: String, into: MutableMap<String, Element>) {
        val doc = xml(path) ?: return
        for (n in doc.getElementsByTag(tag)) {
            val id = n.wAttr("id") ?: continue
            val type = n.wAttr("type")
            if (type == null || type == "normal") into[id] = n
        }
    }

    /** Header and footer parts referenced by a section, as template keys ("hdr-default", "ftr-first", …) to part paths. */
    private fun headerRefs(sectPr: Element?, part: Part): Map<String, String> {
        if (sectPr == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (ref in sectPr.children()) {
            val prefix = when (ref.tagName()) {
                "w:headerReference" -> "hdr"
                "w:footerReference" -> "ftr"
                else -> continue
            }
            val rel = part.rels[ref.attr("r:id")] ?: continue
            out["$prefix-${ref.wAttr("type") ?: "default"}"] = rel.target
        }
        return out
    }

    private fun headerHtml(path: String, width: Double, left: Double): String = partCache.getOrPut(path) {
        val doc = xml(path) ?: return@getOrPut ""
        val root = doc.children().firstOrNull() ?: return@getOrPut ""
        val saved = fields
        fields = ArrayList()
        val sb = StringBuilder()
        blocksOf(root.children(), Ctx(Part(path, relsOf(path)), null, width, left), sb)
        fields = saved
        sb.toString()
    }

    private fun endnotes(sb: StringBuilder, ctx: Ctx) {
        if (endnoteNo.isEmpty()) return
        sb.append("<div class=\"en-sep\"></div>")
        for ((id, n) in endnoteNo.entries.toList()) {
            val el = endnoteEls[id] ?: continue
            currentNote = roman(n).lowercase()
            blocksOf(el.children(), ctx, sb)
        }
        currentNote = null
    }

    // ------------------------------------------------------------------------------------------------------------------ styles

    private fun chain(id: String): List<Style> {
        val out = ArrayList<Style>()
        var cur: String? = id
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            val s = styles[cur] ?: break
            out.add(0, s)
            cur = s.basedOn
        }
        return out
    }

    private fun stylePPr(id: String?): PPr {
        if (id == null) return PPr()
        return pPrCache.getOrPut(id) { chain(id).fold(PPr()) { acc, s -> acc.merge(s.pPr) } }
    }

    private fun styleRPr(id: String?): RPr {
        if (id == null) return RPr()
        return rPrCache.getOrPut(id) { chain(id).fold(RPr()) { acc, s -> acc.merge(s.rPr) } }
    }

    private fun tableStyle(id: String?): TableStyle {
        var borders = emptyMap<String, Border>()
        var margins = emptyMap<String, Double>()
        val parts = HashMap<String, CellFormat>()
        var rowBand = 1
        var colBand = 1
        for (s in id?.let { chain(it) }.orEmpty()) {
            if (s.type != "table") continue
            borders = borders + s.tableBorders
            margins = margins + s.cellMargins
            s.parts.forEach { (k, v) -> parts[k] = (parts[k] ?: CellFormat()).merge(v) }
            rowBand = s.rowBand
            colBand = s.colBand
        }
        return TableStyle(borders, margins, parts, rowBand, colBand)
    }

    /** Paragraph properties of a style; in table cells the table style overrides the default paragraph style, as in Word. */
    private fun basePPr(styleId: String?, ctx: Ctx): PPr {
        val cell = ctx.cell?.pPr
        return if (cell != null && styleId == defaultParagraphStyle) docPPr.merge(stylePPr(styleId)).merge(cell)
        else docPPr.merge(cell).merge(stylePPr(styleId))
    }

    private fun baseRPr(styleId: String?, ctx: Ctx): RPr {
        val cell = ctx.cell?.rPr
        return if (cell != null && styleId == defaultParagraphStyle) docRPr.merge(styleRPr(styleId)).merge(cell)
        else docRPr.merge(cell).merge(styleRPr(styleId))
    }

    private fun fontName(r: RPr): String = r.font ?: r.fontTheme?.let { if (it.startsWith("major")) majorFont else minorFont } ?: minorFont

    private fun fontStack(name: String): String {
        val n = name.trim().replace("\"", "")
        val key = n.lowercase()
        val fallback = when {
            key.startsWith("calibri") || key == "carlito" -> "Carlito,\"Liberation Sans\",sans-serif"
            key.startsWith("times") || key == "liberation serif" || key == "tinos" || key == "cambria" || key == "georgia" || key == "garamond" ||
                key == "book antiqua" || key == "palatino linotype" || key == "constantia" || key == "pt serif" || key == "bookman old style" ||
                key == "century" || key == "century schoolbook" -> "\"Liberation Serif\",serif"
            key.startsWith("arial") || key == "helvetica" || key == "liberation sans" || key == "arimo" || key == "verdana" || key == "tahoma" ||
                key.startsWith("segoe") || key == "trebuchet ms" || key == "pt sans" || key == "open sans" || key == "roboto" -> "\"Liberation Sans\",sans-serif"
            key.startsWith("courier") || key == "consolas" || key == "lucida console" || key == "liberation mono" -> "\"Liberation Mono\",\"Courier New\",monospace"
            else -> when (fontFamilies[key]) {
                "roman" -> "\"Liberation Serif\",serif"
                "modern" -> "monospace"
                else -> "\"Liberation Sans\",sans-serif"
            }
        }
        return "\"$n\",$fallback"
    }

    // ------------------------------------------------------------------------------------------------------------------ numbering

    /** Level and text of the list label of a paragraph; advances the counters. */
    private fun listLabel(numId: String, ilvl: Int): Pair<Level, String>? {
        val num = nums[numId] ?: return null
        val levels = abstractLevels[num.abstractId] ?: return null
        fun lvl(i: Int): Level? = num.levels[i] ?: levels[i]
        val level = lvl(ilvl) ?: return null
        val key = if (num.starts.isNotEmpty()) "n$numId" else "a${num.abstractId}"
        val ctr = counters.getOrPut(key) { IntArray(9) { Int.MIN_VALUE } }
        val i0 = ilvl.coerceIn(0, 8)
        ctr[i0] = if (ctr[i0] == Int.MIN_VALUE) (num.starts[i0] ?: level.start) else ctr[i0] + 1
        for (i in i0 + 1 until 9) ctr[i] = Int.MIN_VALUE
        if (level.format == "bullet") return level to level.text.map { symbolChar(it, level.rPr?.font) }.joinToString("")
        val text = Regex("%([1-9])").replace(level.text) { m ->
            val i = m.groupValues[1].toInt() - 1
            val l = lvl(i) ?: return@replace ""
            val v = if (ctr[i] == Int.MIN_VALUE) (num.starts[i] ?: l.start) else ctr[i]
            numberText(v, if (level.legal && i < i0) "decimal" else l.format)
        }
        return level to text
    }

    // ------------------------------------------------------------------------------------------------------------------ blocks

    /** Block level content with structure-only wrappers (content controls, custom XML, insertions) unwrapped. */
    private fun flatBlocks(els: List<Element>): List<Element> {
        val out = ArrayList<Element>()
        for (e in els) when (e.tagName()) {
            "w:p", "w:tbl" -> out.add(e)
            "w:sdt" -> e.child("w:sdtContent")?.let { out.addAll(flatBlocks(it.children())) }
            "w:customXml", "w:ins", "w:moveTo", "w:smartTag" -> out.addAll(flatBlocks(e.children()))
            "mc:AlternateContent" -> alternate(e)?.let { out.addAll(flatBlocks(it.children())) }
        }
        return out
    }

    private fun blocksOf(els: List<Element>, ctx: Ctx, sb: StringBuilder) {
        val list = flatBlocks(els)
        for ((i, e) in list.withIndex()) when (e.tagName()) {
            "w:p" -> paragraph(e, ctx, sb, list.getOrNull(i - 1), list.getOrNull(i + 1))
            "w:tbl" -> table(e, ctx, sb)
        }
    }

    private fun alternate(e: Element): Element? {
        val supported = setOf("wps", "wp14", "w14", "w15", "a14", "v", "w16se")
        return e.kids("mc:Choice").firstOrNull { ch -> ch.attr("Requires").split(' ').filter { it.isNotBlank() }.all { it in supported } }
            ?: e.child("mc:Fallback")
    }

    // ------------------------------------------------------------------------------------------------------------------ sections

    private class Geometry(val w: Double, val h: Double, val mt: Double, val mb: Double, val ml: Double, val mr: Double, val hd: Double, val fd: Double, val type: String) {
        fun data(): String = " data-w=\"${w.css()}\" data-h=\"${h.css()}\" data-mt=\"${mt.css()}\" data-mb=\"${mb.css()}\" data-ml=\"${ml.css()}\"" +
            " data-mr=\"${mr.css()}\" data-hd=\"${hd.css()}\" data-fd=\"${fd.css()}\" data-type=\"${attr(type)}\""

        companion object {
            /** Page size and margins of a section; A4 with Word's default margins when the document does not say. */
            fun parse(sp: Element?): Geometry {
                val sz = sp?.child("w:pgSz")
                val mar = sp?.child("w:pgMar")
                val w = twips(sz?.wAttr("w"))?.takeIf { it > 50 } ?: (11906 / 15.0)
                val h = twips(sz?.wAttr("h"))?.takeIf { it > 50 } ?: (16838 / 15.0)
                val gutter = twips(mar?.wAttr("gutter")) ?: 0.0
                return Geometry(
                    w, h,
                    abs(twips(mar?.wAttr("top")) ?: 75.6), abs(twips(mar?.wAttr("bottom")) ?: 75.6),
                    (twips(mar?.wAttr("left")) ?: 113.4) + gutter, twips(mar?.wAttr("right")) ?: 56.7,
                    twips(mar?.wAttr("header")) ?: 47.2, twips(mar?.wAttr("footer")) ?: 47.2,
                    sp?.child("w:type")?.wAttr("val") ?: "nextPage",
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ paragraphs

    private fun styleOf(p: Element?): String? =
        if (p?.tagName() == "w:p") p.child("w:pPr")?.child("w:pStyle")?.wAttr("val") ?: defaultParagraphStyle else null

    private fun bordersOf(p: Element?, ctx: Ctx): Map<String, Border>? {
        if (p?.tagName() != "w:p") return null
        val direct = PPr.parse(p.child("w:pPr"))
        return basePPr(direct?.style ?: defaultParagraphStyle, ctx).merge(direct).borders?.filterValues { !it.none }?.takeIf { it.isNotEmpty() }
    }

    private fun paragraph(p: Element, ctx: Ctx, sb: StringBuilder, prev: Element?, next: Element?) {
        val pPrEl = p.child("w:pPr")
        val direct = PPr.parse(pPrEl)
        val styleId = direct?.style ?: defaultParagraphStyle
        var ppr = basePPr(styleId, ctx)
        val numId = direct?.numId ?: ppr.numId
        val ilvl = direct?.ilvl ?: ppr.ilvl ?: 0
        val label = if (numId != null && numId != "0") listLabel(numId, ilvl) else null
        if (label != null) ppr = ppr.merge(label.first.pPr)
        ppr = ppr.merge(direct)
        val baseR = baseRPr(styleId, ctx)
        val markDirect = RPr.parse(pPrEl?.child("w:rPr"))
        val markR = baseR.merge(styleRPr(markDirect?.style)).merge(markDirect)

        val inl = Inline()
        if (label != null) {
            val (level, text) = label
            val lr = markR.merge(level.rPr?.let { if (isSymbolFont(it.font)) it.copy(font = null, fontTheme = null) else it })
                .copy(underline = null, strike = null, highlight = null, shading = null)
            if (text.isNotEmpty()) inl.styled(runCss(lr, baseR), "<span class=\"ll\">" + esc(text) + "</span>")
            when (level.suffix) {
                "tab" -> inl.tab("")
                "space" -> inl.text(" ", "")
            }
        }
        inline(p.children(), ctx, inl, baseR)
        val parts = inl.finish()
        val visible = inl.visibleParts
        if (parts.size == 1 && !visible[0] && markR.vanish == true) return

        var before = if (ppr.beforeAuto == true) 14 * 4 / 3.0 else ppr.before ?: 0.0
        var after = if (ppr.afterAuto == true) 14 * 4 / 3.0 else ppr.after ?: 0.0
        if (ppr.contextual == true) {
            if (styleOf(prev) == styleId) before = 0.0
            if (styleOf(next) == styleId) after = 0.0
        }
        val borders = ppr.borders?.filterValues { !it.none }.orEmpty()
        val groupPrev = borders.isNotEmpty() && bordersOf(prev, ctx) == borders
        val groupNext = borders.isNotEmpty() && bordersOf(next, ctx) == borders
        val jc = when (ppr.jc) {
            "center" -> "center"
            "right", "end" -> "right"
            "both", "distribute", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> "justify"
            else -> null
        }
        val data = StringBuilder()
        if (ppr.keepNext == true) data.append(" data-kn=\"1\"")
        if (ppr.keepLines == true) data.append(" data-kl=\"1\"")
        if (ppr.widowControl != false) data.append(" data-wc=\"1\"")
        if (inl.tabs > 0) {
            val stops = ppr.tabs.orEmpty().filter { it.align != "clear" && it.align != "bar" }
            if (stops.isNotEmpty()) {
                data.append(" data-tabs=\"").append(stops.joinToString(";") { "${it.posPx.css()},${tabAlign(it.align)},${it.leader}" }).append('"')
            }
            data.append(" data-il=\"").append((ppr.indLeft ?: 0.0).css()).append('"')
            ppr.hanging?.takeIf { it > 0 }?.let { data.append(" data-hang=\"").append(it.css()).append('"') }
        }
        for ((i, html) in parts.withIndex()) {
            if (i > 0) sb.append("<div class=\"pb\"></div>")
            if (parts.size > 1 && !visible[i]) continue
            val first = i == 0
            val last = i == parts.lastIndex
            sb.append("<div class=\"p")
            if (inl.tabs > 0) sb.append(" ht")
            if (jc == "justify") sb.append(" j")
            sb.append("\" style=\"")
            blockCss(
                sb, ppr, if (visible[i]) baseR else markR, jc,
                marginTop = if (first && !groupPrev) before else 0.0,
                marginBottom = if (last && !groupNext) after else 0.0,
                firstLine = first,
                borderTop = !(groupPrev && first), borderBottom = !(groupNext && last),
                padTop = if (groupPrev && first) before else 0.0, padBottom = if (groupNext && last) after else 0.0,
            )
            sb.append('"').append(data)
            if (first && ppr.pageBreakBefore == true) sb.append(" data-pbb=\"1\"")
            sb.append("><span class=\"ts\">").append(html).append("</span>")
            if (!visible[i]) sb.append("<br>")
            sb.append("</div>")
        }
    }

    private fun tabAlign(a: String) = when (a) {
        "right", "end" -> "right"
        "center" -> "center"
        "decimal" -> "decimal"
        else -> "left"
    }

    private fun blockCss(
        s: StringBuilder, ppr: PPr, r: RPr, jc: String?, marginTop: Double, marginBottom: Double, firstLine: Boolean,
        borderTop: Boolean, borderBottom: Boolean, padTop: Double, padBottom: Double,
    ) {
        val fam = fontName(r).let { if (isSymbolFont(it)) minorFont else it }
        val size = r.sizePt ?: 10.0
        s.append("font-family:").append(attr(fontStack(fam))).append(";font-size:").append(size.css()).append("pt;")
        if (r.bold == true) s.append("font-weight:bold;")
        if (r.italic == true) s.append("font-style:italic;")
        r.color?.takeIf { it != "auto" }?.let { s.append("color:").append(it).append(';') }
        if (r.caps == true) s.append("text-transform:uppercase;") else if (r.smallCaps == true) s.append("font-variant:small-caps;")
        r.spacingPx?.takeIf { it != 0.0 }?.let { s.append("letter-spacing:").append(it.css()).append("px;") }
        s.append("line-height:").append(lineHeight(ppr, fam, size)).append(';')
        val bl = ppr.borders?.get("left")?.takeIf { !it.none }
        val br = ppr.borders?.get("right")?.takeIf { !it.none }
        val left = (ppr.indLeft ?: 0.0) - (bl?.let { it.widthPx + it.spacePx } ?: 0.0)
        val right = (ppr.indRight ?: 0.0) - (br?.let { it.widthPx + it.spacePx } ?: 0.0)
        if (left != 0.0) s.append("margin-left:").append(left.css()).append("px;")
        if (right != 0.0) s.append("margin-right:").append(right.css()).append("px;")
        if (marginTop != 0.0) s.append("margin-top:").append(marginTop.css()).append("px;")
        if (marginBottom != 0.0) s.append("margin-bottom:").append(marginBottom.css()).append("px;")
        if (firstLine) {
            val ti = ppr.hanging?.let { -it } ?: ppr.firstLine ?: 0.0
            if (ti != 0.0) s.append("text-indent:").append(ti.css()).append("px;")
        }
        jc?.let { s.append("text-align:").append(it).append(';') }
        if (ppr.jc == "distribute") s.append("text-align-last:justify;")
        var pt = padTop
        var pb = padBottom
        ppr.borders?.forEach { (side, b) ->
            if (b.none) return@forEach
            if (side == "top" && !borderTop || side == "bottom" && !borderBottom) return@forEach
            s.append("border-").append(side).append(':').append(b.css()).append(';')
            when (side) {
                "top" -> pt += b.spacePx
                "bottom" -> pb += b.spacePx
                else -> s.append("padding-").append(side).append(':').append(b.spacePx.css()).append("px;")
            }
        }
        if (pt > 0) s.append("padding-top:").append(pt.css()).append("px;")
        if (pb > 0) s.append("padding-bottom:").append(pb.css()).append("px;")
        ppr.shading?.let { s.append("background:").append(it).append(';') }
        if (ppr.bidi == true) s.append("direction:rtl;")
    }

    private fun lineHeight(ppr: PPr, fam: String, sizePt: Double): String {
        val ratio = lineRatio(fam)
        val line = ppr.line ?: return String.format(java.util.Locale.US, "%.3f", ratio)
        return when (ppr.lineRule) {
            "exact" -> (abs(line) / 15).css() + "px"
            "atLeast" -> max(line / 15, ratio * sizePt * 4 / 3).css() + "px"
            else -> String.format(java.util.Locale.US, "%.3f", max(line, 1.0) / 240 * ratio)
        }
    }

    private fun runCss(r: RPr, base: RPr): String {
        val s = StringBuilder()
        val fam = fontName(r)
        if (!isSymbolFont(fam) && fam != fontName(base)) s.append("font-family:").append(attr(fontStack(fam))).append(';')
        val size = r.sizePt ?: 10.0
        when (r.vertAlign) {
            "superscript" -> s.append("font-size:").append((size * 0.65).css()).append("pt;vertical-align:").append((size * 0.34).css()).append("pt;line-height:0;")
            "subscript" -> s.append("font-size:").append((size * 0.65).css()).append("pt;vertical-align:-").append((size * 0.14).css()).append("pt;line-height:0;")
            else -> if (size != (base.sizePt ?: 10.0)) s.append("font-size:").append(size.css()).append("pt;")
        }
        if ((r.bold == true) != (base.bold == true)) s.append(if (r.bold == true) "font-weight:bold;" else "font-weight:normal;")
        if ((r.italic == true) != (base.italic == true)) s.append(if (r.italic == true) "font-style:italic;" else "font-style:normal;")
        val c = r.color?.takeIf { it != "auto" }
        if (c != base.color?.takeIf { it != "auto" }) s.append("color:").append(c ?: "#000000").append(';')
        if ((r.caps == true) != (base.caps == true)) s.append(if (r.caps == true) "text-transform:uppercase;" else "text-transform:none;")
        if ((r.smallCaps == true) != (base.smallCaps == true)) s.append(if (r.smallCaps == true) "font-variant:small-caps;" else "font-variant:normal;")
        if ((r.spacingPx ?: 0.0) != (base.spacingPx ?: 0.0)) s.append("letter-spacing:").append((r.spacingPx ?: 0.0).css()).append("px;")
        val u = r.underline?.takeIf { it != "none" }
        val lines = listOfNotNull(u?.let { "underline" }, if (r.strike == true || r.dstrike == true) "line-through" else null)
        if (lines.isNotEmpty()) {
            s.append("text-decoration:").append(lines.joinToString(" "))
            when {
                u == "double" || r.dstrike == true -> s.append(" double")
                u?.startsWith("dotted") == true || u == "dotDash" || u == "dotDotDash" -> s.append(" dotted")
                u?.startsWith("dash") == true -> s.append(" dashed")
                u?.startsWith("wav") == true -> s.append(" wavy")
            }
            s.append(';')
            if (u == "thick") s.append("text-decoration-thickness:2px;")
        }
        (r.highlight ?: r.shading)?.let { s.append("background:").append(it).append(';') }
        return s.toString()
    }

    /** Inline HTML of one paragraph; tab characters split it into measured segments, page breaks into parts. */
    private class Inline {
        val parts = ArrayList<String>()
        val visibleParts = ArrayList<Boolean>()
        private var sb = StringBuilder()
        private val links = ArrayList<String>()
        private var spanCss: String? = null
        private var visible = false
        private var lastBr = false
        var tabs = 0

        private fun closeSpan() {
            if (spanCss != null) {
                sb.append("</span>")
                spanCss = null
            }
        }

        fun text(t: String, css: String) {
            if (t.isEmpty()) return
            if (css != (spanCss ?: "")) {
                closeSpan()
                if (css.isNotEmpty()) {
                    sb.append("<span style=\"").append(css).append("\">")
                    spanCss = css
                }
            }
            sb.append(esc(t))
            visible = true
            lastBr = false
        }

        fun styled(css: String, html: String, counts: Boolean = true) {
            closeSpan()
            if (css.isEmpty()) sb.append(html) else sb.append("<span style=\"").append(css).append("\">").append(html).append("</span>")
            if (counts) {
                visible = true
                lastBr = false
            }
        }

        fun raw(html: String, counts: Boolean) = styled("", html, counts)

        fun br() {
            closeSpan()
            sb.append("<br>")
            visible = true
            lastBr = true
        }

        fun openLink(href: String) {
            closeSpan()
            val tag = "<a href=\"" + attr(href) + "\">"
            sb.append(tag)
            links += tag
        }

        fun closeLink() {
            closeSpan()
            if (links.isNotEmpty()) {
                links.removeAt(links.lastIndex)
                sb.append("</a>")
            }
        }

        fun tab(attrs: String) {
            closeSpan()
            repeat(links.size) { sb.append("</a>") }
            sb.append("</span><span class=\"tab\"").append(attrs).append("></span><span class=\"ts\">")
            links.forEach { sb.append(it) }
            tabs++
            visible = true
            lastBr = false
        }

        private fun endPart() {
            closeSpan()
            // A line break at the end of a paragraph still starts a new (empty) line in Word.
            if (lastBr) sb.append("<br>")
            repeat(links.size) { sb.append("</a>") }
            parts += sb.toString()
            visibleParts += visible
        }

        fun pageBreak() {
            endPart()
            sb = StringBuilder()
            visible = false
            lastBr = false
            links.forEach { sb.append(it) }
        }

        fun finish(): List<String> {
            endPart()
            links.clear()
            return parts
        }
    }

    private val suppressed: Boolean get() = fields.any { !it.separated || it.kind != null }

    private fun fieldKind(instr: String): String? = when (instr.trim().substringBefore(' ').uppercase()) {
        "PAGE" -> "page"
        "NUMPAGES", "SECTIONPAGES" -> "pages"
        else -> null
    }

    private fun inline(nodes: List<Element>, ctx: Ctx, inl: Inline, base: RPr) {
        for (e in nodes) when (e.tagName()) {
            "w:r" -> run(e, ctx, inl, base)
            "w:hyperlink" -> {
                val rid = e.attr("r:id")
                val href = rid.takeIf { it.isNotEmpty() }?.let { ctx.part.rels[it]?.takeIf { r -> r.external }?.target }
                    ?: e.wAttr("anchor")?.let { "#$it" }
                val open = href != null && !suppressed
                if (open) inl.openLink(href)
                inline(e.children(), ctx, inl, base)
                if (open) inl.closeLink()
            }
            "w:fldSimple" -> {
                val kind = fieldKind(e.wAttr("instr").orEmpty())
                if (kind != null) {
                    if (!suppressed) {
                        val r = e.getElementsByTag("w:r").firstOrNull()
                        val direct = RPr.parse(r?.child("w:rPr"))
                        inl.styled(runCss(base.merge(styleRPr(direct?.style)).merge(direct), base), "<span class=\"f-$kind\">1</span>")
                    }
                } else inline(e.children(), ctx, inl, base)
            }
            "w:smartTag", "w:customXml", "w:ins", "w:moveTo", "w:dir", "w:bdo" -> inline(e.children(), ctx, inl, base)
            "w:sdt" -> e.child("w:sdtContent")?.let { inline(it.children(), ctx, inl, base) }
            "w:bookmarkStart" -> e.wAttr("name")?.takeIf { it.isNotBlank() && !it.startsWith("_GoBack") }?.let {
                if (!suppressed) inl.raw("<a class=\"bm\" id=\"" + attr(it) + "\"></a>", counts = false)
            }
            "m:oMathPara", "m:oMath" -> if (!suppressed) {
                inl.text(e.getElementsByTag("m:t").joinToString("") { it.wholeText() }, runCss(base.copy(italic = true), base))
            }
            "mc:AlternateContent" -> alternate(e)?.let { inline(it.children(), ctx, inl, base) }
        }
    }

    private fun run(r: Element, ctx: Ctx, inl: Inline, base: RPr) {
        val direct = RPr.parse(r.child("w:rPr"))
        val eff = base.merge(styleRPr(direct?.style ?: defaultCharStyle)).merge(direct)
        val hidden = eff.vanish == true
        val css = runCss(eff, base)
        runChildren(r.children(), eff, css, hidden, ctx, inl)
    }

    private fun runChildren(children: List<Element>, eff: RPr, css: String, hidden: Boolean, ctx: Ctx, inl: Inline) {
        val font = fontName(eff)
        val symbol = isSymbolFont(font)
        for (c in children) {
            when (c.tagName()) {
                "w:fldChar" -> fieldChar(c, inl, css)
                "w:instrText" -> fields.lastOrNull()?.takeIf { !it.separated }?.instr?.append(c.wholeText())
                else -> {
                    if (hidden || suppressed) continue
                    when (c.tagName()) {
                        "w:t" -> {
                            val t = c.wholeText()
                            inl.text(if (symbol) t.map { symbolChar(it, font) }.joinToString("") else t, css)
                        }
                        "w:tab" -> inl.tab("")
                        "w:ptab" -> inl.tab(" data-pt=\"" + attr(c.wAttr("alignment") ?: "left") + "\" data-ld=\"" + attr(c.wAttr("leader") ?: "none") + "\"")
                        "w:br" -> when (c.wAttr("type")) {
                            "page", "column" -> inl.pageBreak()
                            else -> inl.br()
                        }
                        "w:cr" -> inl.br()
                        "w:noBreakHyphen" -> inl.text("\u2011", css)
                        "w:softHyphen" -> inl.text("\u00AD", css)
                        "w:sym" -> c.wAttr("char")?.toIntOrNull(16)?.let { inl.text(symbolChar(it.toChar(), c.wAttr("font")), css) }
                        "w:drawing" -> drawing(c, ctx, inl)
                        "w:pict", "w:object" -> vml(c, ctx, inl)
                        "mc:AlternateContent" -> alternate(c)?.let { runChildren(it.children(), eff, css, hidden, ctx, inl) }
                        "w:footnoteReference" -> {
                            val id = c.wAttr("id").orEmpty()
                            if (c.wAttr("customMarkFollows").let { it == "1" || it == "true" }) continue
                            val n = footnoteNo.getOrPut(id) { footnoteNo.size + 1 }
                            inl.styled(css, "<span class=\"fnr\" data-fn=\"" + attr(id) + "\">" + n + "</span>")
                        }
                        "w:endnoteReference" -> {
                            val id = c.wAttr("id").orEmpty()
                            val n = endnoteNo.getOrPut(id) { endnoteNo.size + 1 }
                            inl.styled(css, "<span class=\"enr\">" + roman(n).lowercase() + "</span>")
                        }
                        "w:footnoteRef", "w:endnoteRef" -> currentNote?.let { inl.text(it, css) }
                    }
                }
            }
        }
    }

    private fun fieldChar(c: Element, inl: Inline, css: String) {
        when (c.wAttr("fldCharType")) {
            "begin" -> fields.add(Field())
            "separate" -> fields.lastOrNull()?.let { f ->
                f.separated = true
                f.kind = fieldKind(f.instr.toString())
                val outerSuppressed = fields.dropLast(1).any { !it.separated || it.kind != null }
                if (f.kind != null && !outerSuppressed) inl.styled(css, "<span class=\"f-${f.kind}\">1</span>")
            }
            "end" -> if (fields.isNotEmpty()) {
                val f = fields.removeAt(fields.lastIndex)
                if (!f.separated) {
                    val kind = fieldKind(f.instr.toString())
                    if (kind != null && !suppressed) inl.styled(css, "<span class=\"f-$kind\">1</span>")
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ objects

    private class Placement(val cls: String, val css: String, val data: String)

    private fun emitObject(html: String, place: Placement?, inl: Inline) {
        when (place?.cls) {
            null -> inl.raw(html, counts = true)
            "abs" -> inl.raw("<span class=\"abs\"" + place.data + ">" + html + "</span>", counts = false)
            else -> inl.raw("<span class=\"" + place.cls + "\" style=\"" + place.css + "\">" + html + "</span>", counts = false)
        }
    }

    private fun drawing(d: Element, ctx: Ctx, inl: Inline) {
        val box = d.child("wp:inline") ?: d.child("wp:anchor") ?: return
        val ext = box.child("wp:extent")
        val w = (ext?.attr("cx")?.toDoubleOrNull() ?: 0.0) / 9525
        val h = (ext?.attr("cy")?.toDoubleOrNull() ?: 0.0) / 9525
        val place = if (box.tagName() == "wp:anchor") anchorPlacement(box, w, ctx) else null
        val txbx = box.getElementsByTag("wps:txbx").firstOrNull()?.child("w:txbxContent")
        val blip = box.getElementsByTag("a:blip").firstOrNull()
        val html = when {
            txbx != null -> textBox(txbx, w, h, ctx, box)
            blip != null -> image(ctx.part, blip.attr("r:embed").ifEmpty { blip.attr("r:link") }, w, h)
            box.getElementsByTag("c:chart").isNotEmpty() -> placeholder(w, h, "chart")
            box.getElementsByTag("wps:wsp").isNotEmpty() -> return
            else -> placeholder(w, h, "object")
        }
        emitObject(html, place, inl)
    }

    private fun anchorPlacement(a: Element, w: Double, ctx: Ctx): Placement {
        fun emu(s: String?) = s?.trim()?.toDoubleOrNull()?.div(9525)
        val ph = a.child("wp:positionH")
        val pv = a.child("wp:positionV")
        val hAlign = ph?.child("wp:align")?.text()?.trim()
        val hOff = emu(ph?.child("wp:posOffset")?.text())
        val hRel = ph?.attr("relativeFrom").orEmpty()
        val vAlign = pv?.child("wp:align")?.text()?.trim()
        val vOff = emu(pv?.child("wp:posOffset")?.text())
        val vRel = pv?.attr("relativeFrom").orEmpty()
        val behind = a.attr("behindDoc") == "1"
        fun dist(n: String) = emu(a.attr(n)) ?: 0.0
        if (a.child("wp:wrapNone") != null || behind) {
            return Placement("abs", "", absData(hRel, hAlign, hOff, vRel, vAlign, vOff, behind))
        }
        // Horizontal position inside the text column.
        val x = when (hAlign) {
            null -> (hOff ?: 0.0) - if (hRel == "page") ctx.sectionLeft else 0.0
            "center" -> (ctx.width - w) / 2
            "right", "outside" -> ctx.width - w
            else -> 0.0
        }
        if (a.child("wp:wrapTopAndBottom") != null) {
            val ml = if (hAlign == "center") "auto" else "${max(0.0, x).css()}px"
            return Placement("blk", "display:block;margin:${dist("distT").css()}px auto ${dist("distB").css()}px $ml;", "")
        }
        val right = x + w / 2 > ctx.width / 2
        val css = if (right) "float:right;margin:${dist("distT").css()}px ${max(0.0, ctx.width - x - w).css()}px ${dist("distB").css()}px ${dist("distL").css()}px;"
        else "float:left;margin:${dist("distT").css()}px ${dist("distR").css()}px ${dist("distB").css()}px ${max(0.0, x).css()}px;"
        return Placement("flt", css, "")
    }

    private fun absData(hRel: String, hAlign: String?, hOff: Double?, vRel: String, vAlign: String?, vOff: Double?, behind: Boolean): String {
        val s = StringBuilder(" data-hr=\"").append(attr(hRel)).append("\" data-vr=\"").append(attr(vRel)).append('"')
        if (hAlign != null) s.append(" data-ha=\"").append(attr(hAlign)).append('"') else s.append(" data-hx=\"").append((hOff ?: 0.0).css()).append('"')
        if (vAlign != null) s.append(" data-va=\"").append(attr(vAlign)).append('"') else s.append(" data-vy=\"").append((vOff ?: 0.0).css()).append('"')
        if (behind) s.append(" data-z=\"b\"")
        return s.toString()
    }

    private fun image(part: Part, rid: String, w: Double, h: Double): String {
        val name = mediaFor(part, rid) ?: return placeholder(w, h, "image")
        return "<img class=\"im\" src=\"$MEDIA_DIR/" + attr(name) + "\" style=\"width:" + w.css() + "px;height:" + h.css() + "px\" alt=\"\">"
    }

    private fun placeholder(w: Double, h: Double, kind: String) =
        "<span class=\"ph\" data-k=\"$kind\" style=\"width:${max(w, 16.0).css()}px;height:${max(h, 16.0).css()}px\"></span>"

    private fun mediaFor(part: Part, rid: String): String? {
        val rel = part.rels[rid] ?: return null
        if (rel.external) return null
        mediaNames[rel.target]?.let { return it }
        val ext = rel.target.substringAfterLast('.', "").lowercase()
        if (ext !in IMAGE_TYPES) return null
        val bytes = container.read(rel.target) ?: return null
        val name = "${mediaNames.size + 1}.$ext"
        media(name, bytes)
        mediaNames[rel.target] = name
        return name
    }

    private fun textBox(content: Element, w: Double, h: Double, ctx: Ctx, shape: Element?): String {
        val sb = StringBuilder()
        val saved = fields
        fields = ArrayList()
        val spPr = shape?.getElementsByTag("wps:spPr")?.firstOrNull()
        val bodyPr = shape?.getElementsByTag("wps:bodyPr")?.firstOrNull()
        fun inset(n: String, d: Double) = (bodyPr?.attr(n)?.toDoubleOrNull() ?: d) / 9525
        val padL = inset("lIns", 91440.0)
        val padR = inset("rIns", 91440.0)
        blocksOf(content.children(), Ctx(ctx.part, null, max(10.0, w - padL - padR), ctx.sectionLeft), sb)
        fields = saved
        val ln = spPr?.child("a:ln")
        val styleLine = shape?.getElementsByTag("a:lnRef")?.firstOrNull()?.attr("idx")?.let { it != "0" } == true
        val border = when {
            ln != null && ln.child("a:noFill") == null -> {
                val width = max(0.75, (ln.attr("w").toDoubleOrNull() ?: 9525.0) / 9525)
                val col = ln.getElementsByTag("a:srgbClr").firstOrNull()?.attr("val")?.let { color(it) } ?: "#000000"
                "border:${width.css()}px solid $col;"
            }
            ln == null && styleLine -> "border:0.75px solid #000000;"
            else -> ""
        }
        val fill = spPr?.child("a:solidFill")?.child("a:srgbClr")?.attr("val")?.let { color(it) }?.let { "background:$it;" } ?: ""
        return "<span class=\"tb\" style=\"width:${w.css()}px;min-height:${h.css()}px;$border$fill" +
            "padding:${inset("tIns", 45720.0).css()}px ${padR.css()}px ${inset("bIns", 45720.0).css()}px ${padL.css()}px\"><span class=\"tbc\">$sb</span></span>"
    }

    private fun vmlLen(style: String, key: String): Double? {
        val m = Regex("(?:^|;)\\s*" + Regex.escape(key) + "\\s*:\\s*(-?[\\d.]+)\\s*(pt|px|in|cm|mm)?").find(style) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) {
            "pt" -> v * 4 / 3
            "in" -> v * 96
            "cm" -> v * 96 / 2.54
            "mm" -> v * 96 / 25.4
            else -> v
        }
    }

    private fun vml(e: Element, ctx: Ctx, inl: Inline) {
        val shape = e.getElementsByTag("v:shape").firstOrNull() ?: e.getElementsByTag("v:rect").firstOrNull()
            ?: e.getElementsByTag("v:roundrect").firstOrNull() ?: return
        val style = shape.attr("style")
        if (shape.attr("id").contains("WaterMark", true) || style.contains("visibility:hidden")) return
        val w = vmlLen(style, "width") ?: 0.0
        val h = vmlLen(style, "height") ?: 0.0
        val imageData = e.getElementsByTag("v:imagedata").firstOrNull()
        val tb = e.getElementsByTag("w:txbxContent").firstOrNull()
        val html = when {
            imageData != null -> image(ctx.part, imageData.attr("r:id").ifEmpty { imageData.attr("r:pict") }, w, h)
            tb != null -> textBox(tb, w, h, ctx, null)
            else -> return
        }
        if (!style.contains("position:absolute")) {
            inl.raw(html, counts = true)
            return
        }
        fun prop(name: String) = Regex(Regex.escape(name) + "\\s*:\\s*([a-z-]+)").find(style)?.groupValues?.get(1)
        val wrap = e.getElementsByTag("w10:wrap").firstOrNull()?.attr("type")
        val hRel = when (prop("mso-position-horizontal-relative")) {
            "page" -> "page"; "left-margin-area" -> "leftMargin"; "right-margin-area" -> "rightMargin"; "char" -> "character"; else -> "column"
        }
        val vRel = when (prop("mso-position-vertical-relative")) {
            "page" -> "page"; "margin" -> "margin"; "top-margin-area" -> "topMargin"; "bottom-margin-area" -> "bottomMargin"; else -> "paragraph"
        }
        val hAlign = prop("mso-position-horizontal")?.takeIf { it != "absolute" }
        val vAlign = prop("mso-position-vertical")?.takeIf { it != "absolute" }
        val x = vmlLen(style, "margin-left") ?: vmlLen(style, "left") ?: 0.0
        val y = vmlLen(style, "margin-top") ?: vmlLen(style, "top") ?: 0.0
        if (wrap == "square" || wrap == "tight" || wrap == "through") {
            val right = hAlign == "right" || (hAlign == null && x + w / 2 > ctx.width / 2)
            emitObject(html, Placement("flt", if (right) "float:right;margin:0 0 4px 8px;" else "float:left;margin:0 8px 4px 0;", ""), inl)
        } else if (wrap == "topAndBottom") {
            emitObject(html, Placement("blk", "display:block;margin:0 auto 0 ${max(0.0, x).css()}px;", ""), inl)
        } else {
            emitObject(html, Placement("abs", "", absData(hRel, hAlign, x, vRel, vAlign, y, style.contains("z-index:-"))), inl)
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ tables

    private class Cell(val tc: Element, val col: Int, val span: Int, val vMerge: String?) {
        var rowSpan = 1
        var covered = false
    }

    private fun rowsOf(t: Element): List<Element> {
        val out = ArrayList<Element>()
        fun walk(els: List<Element>) {
            for (e in els) when (e.tagName()) {
                "w:tr" -> out.add(e)
                "w:sdt" -> e.child("w:sdtContent")?.let { c -> walk(c.children()) }
                "w:customXml", "w:ins", "w:moveTo" -> walk(e.children())
            }
        }
        walk(t.children())
        return out
    }

    private fun cellsOf(tr: Element): List<Element> {
        val out = ArrayList<Element>()
        fun walk(els: List<Element>) {
            for (e in els) when (e.tagName()) {
                "w:tc" -> out.add(e)
                "w:sdt" -> e.child("w:sdtContent")?.let { c -> walk(c.children()) }
                "w:customXml", "w:ins", "w:moveTo" -> walk(e.children())
            }
        }
        walk(tr.children())
        return out
    }

    private fun table(t: Element, ctx: Ctx, sb: StringBuilder) {
        val tblPr = t.child("w:tblPr")
        val ts = tableStyle(tblPr?.child("w:tblStyle")?.wAttr("val") ?: defaultTableStyle)
        val look = tblPr?.child("w:tblLook")
        fun lookFlag(name: String, bit: Int, def: Boolean): Boolean {
            look?.wAttr(name)?.let { return it == "1" || it == "true" }
            val v = look?.wAttr("val")?.toIntOrNull(16) ?: return def
            return v and bit != 0
        }
        val firstRow = lookFlag("firstRow", 0x20, true)
        val lastRow = lookFlag("lastRow", 0x40, false)
        val firstCol = lookFlag("firstColumn", 0x80, true)
        val lastCol = lookFlag("lastColumn", 0x100, false)
        val noHBand = lookFlag("noHBand", 0x200, false)
        val noVBand = lookFlag("noVBand", 0x400, true)
        val borders = ts.borders + Border.parseAll(tblPr?.child("w:tblBorders"), TABLE_SIDES)
        val margins = mapOf("top" to 0.0, "bottom" to 0.0, "left" to DEFAULT_CELL_MARGIN, "right" to DEFAULT_CELL_MARGIN) + ts.margins +
            cellMargins(tblPr?.child("w:tblCellMar"))
        val tableShading = shading(tblPr?.child("w:shd"))
        val rows = rowsOf(t)
        if (rows.isEmpty()) return
        val cells = rows.map { tr ->
            var col = tr.child("w:trPr")?.child("w:gridBefore")?.wAttr("val")?.toIntOrNull() ?: 0
            cellsOf(tr).map { tc ->
                val tcPr = tc.child("w:tcPr")
                val span = tcPr?.child("w:gridSpan")?.wAttr("val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val vm = tcPr?.child("w:vMerge")?.let { it.wAttr("val") ?: "continue" }
                Cell(tc, col, span, vm).also { col += span }
            }
        }
        for (r in cells.indices) for (c in cells[r]) if (c.vMerge == "restart") {
            var n = 1
            while (r + n < cells.size) {
                val below = cells[r + n].firstOrNull { it.col == c.col }
                if (below?.vMerge != "continue") break
                below.covered = true
                n++
            }
            c.rowSpan = n
        }
        val grid = t.child("w:tblGrid")?.kids("w:gridCol")?.map { twips(it.wAttr("w")) ?: 0.0 }.orEmpty().toMutableList()
        val cols = max(grid.size, cells.maxOf { row -> row.lastOrNull()?.let { it.col + it.span } ?: 0 })
        while (grid.size < cols) grid += 0.0
        if (grid.any { it <= 0.0 }) {
            for (row in cells) for (c in row) {
                val w = c.tc.child("w:tcPr")?.child("w:tcW")?.takeIf { it.wAttr("type").let { t -> t == null || t == "dxa" } }?.wAttr("w")?.let { twips(it) } ?: continue
                if (c.span == 1 && grid[c.col] <= 0.0) grid[c.col] = w
            }
            val known = grid.filter { it > 0 }.sum()
            val missing = grid.count { it <= 0 }
            if (missing > 0) {
                val each = max(24.0, (ctx.width - known) / missing)
                for (i in grid.indices) if (grid[i] <= 0) grid[i] = each
            }
        }
        val total = grid.sum()
        val css = StringBuilder("width:").append(total.css()).append("px;")
        when (tblPr?.child("w:jc")?.wAttr("val")) {
            "center" -> css.append("align-self:center;")
            "right", "end" -> css.append("align-self:flex-end;")
            else -> {
                val ind = tblPr?.child("w:tblInd")?.takeIf { it.wAttr("type").let { t -> t == null || t == "dxa" } }?.let { twips(it.wAttr("w")) } ?: 0.0
                val shift = if (compatMode < 15) margins["left"] ?: 0.0 else 0.0
                val ml = ind - shift
                if (ml != 0.0) css.append("margin-left:").append(ml.css()).append("px;")
            }
        }
        sb.append("<table class=\"tbl\" style=\"").append(css).append("\"><colgroup>")
        grid.forEach { sb.append("<col style=\"width:").append(it.css()).append("px\">") }
        sb.append("</colgroup><tbody>")
        var header = true
        for ((r, tr) in rows.withIndex()) {
            val trPr = tr.child("w:trPr")
            header = header && trPr?.child("w:tblHeader").onOff() == true
            sb.append("<tr")
            if (header) sb.append(" data-hdr=\"1\"")
            if (trPr?.child("w:cantSplit").onOff() == true) sb.append(" data-cs=\"1\"")
            twips(trPr?.child("w:trHeight")?.wAttr("val"))?.takeIf { it > 0 }?.let { sb.append(" style=\"height:").append(it.css()).append("px\"") }
            sb.append('>')
            val gapBefore = trPr?.child("w:gridBefore")?.wAttr("val")?.toIntOrNull() ?: 0
            if (gapBefore > 0) sb.append("<td class=\"gap\" data-c=\"0\" colspan=\"").append(gapBefore).append("\"></td>")
            var end = gapBefore
            for (c in cells[r]) {
                end = c.col + c.span
                if (c.covered) continue
                val tcPr = c.tc.child("w:tcPr")
                val rowEnd = r + c.rowSpan - 1
                val isFirstRow = firstRow && r == 0
                val isLastRow = lastRow && rowEnd == rows.lastIndex
                val isFirstCol = firstCol && c.col == 0
                val isLastCol = lastCol && c.col + c.span >= cols
                var f = ts.parts["wholeTable"] ?: CellFormat()
                if (!noVBand && !isFirstCol && !isLastCol) {
                    val ci = c.col - if (firstCol) 1 else 0
                    if (ci >= 0) f = f.merge(ts.parts[if ((ci / ts.colBand) % 2 == 0) "band1Vert" else "band2Vert"])
                }
                if (!noHBand && !isFirstRow && !isLastRow) {
                    val ri = r - if (firstRow) 1 else 0
                    if (ri >= 0) f = f.merge(ts.parts[if ((ri / ts.rowBand) % 2 == 0) "band1Horz" else "band2Horz"])
                }
                if (isFirstCol) f = f.merge(ts.parts["firstCol"])
                if (isLastCol) f = f.merge(ts.parts["lastCol"])
                if (isFirstRow) f = f.merge(ts.parts["firstRow"])
                if (isLastRow) f = f.merge(ts.parts["lastRow"])
                if (isFirstRow && isFirstCol) f = f.merge(ts.parts["nwCell"])
                if (isFirstRow && isLastCol) f = f.merge(ts.parts["neCell"])
                if (isLastRow && isFirstCol) f = f.merge(ts.parts["swCell"])
                if (isLastRow && isLastCol) f = f.merge(ts.parts["seCell"])
                val own = Border.parseAll(tcPr?.child("w:tcBorders"), TABLE_SIDES)
                val st = StringBuilder()
                for (side in SIDES) {
                    val outer = when (side) {
                        "top" -> r == 0
                        "bottom" -> rowEnd == rows.lastIndex
                        "left" -> c.col == 0
                        else -> c.col + c.span >= cols
                    }
                    val inner = if (side == "top" || side == "bottom") "insideH" else "insideV"
                    val b = own[side] ?: f.borders[side] ?: (if (!outer) f.borders[inner] else null) ?: (if (outer) borders[side] else borders[inner])
                    if (b != null && !b.none) st.append("border-").append(side).append(':').append(b.css()).append(';')
                }
                val cm = margins + cellMargins(tcPr?.child("w:tcMar"))
                st.append("padding:").append((cm["top"] ?: 0.0).css()).append("px ").append((cm["right"] ?: 0.0).css()).append("px ")
                    .append((cm["bottom"] ?: 0.0).css()).append("px ").append((cm["left"] ?: 0.0).css()).append("px;")
                (shading(tcPr?.child("w:shd")) ?: f.shading ?: tableShading)?.let { st.append("background:").append(it).append(';') }
                when (tcPr?.child("w:vAlign")?.wAttr("val") ?: f.vAlign) {
                    "center" -> st.append("vertical-align:middle;")
                    "bottom" -> st.append("vertical-align:bottom;")
                }
                val width = grid.subList(c.col.coerceAtMost(cols), (c.col + c.span).coerceAtMost(cols)).sum() - (cm["left"] ?: 0.0) - (cm["right"] ?: 0.0)
                sb.append("<td data-c=\"").append(c.col).append('"')
                if (c.span > 1) sb.append(" colspan=\"").append(c.span).append('"')
                if (c.rowSpan > 1) sb.append(" rowspan=\"").append(c.rowSpan).append('"')
                sb.append(" style=\"").append(st).append("\"><div class=\"cc\">")
                blocksOf(c.tc.children(), Ctx(ctx.part, f, max(10.0, width), ctx.sectionLeft), sb)
                sb.append("</div></td>")
            }
            val gapAfter = trPr?.child("w:gridAfter")?.wAttr("val")?.toIntOrNull() ?: 0
            if (gapAfter > 0) sb.append("<td class=\"gap\" data-c=\"").append(end).append("\" colspan=\"").append(gapAfter).append("\"></td>")
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
    }
}
