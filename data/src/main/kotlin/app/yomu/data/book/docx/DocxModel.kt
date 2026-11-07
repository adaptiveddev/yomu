package app.yomu.data.book.docx

import org.jsoup.nodes.Element
import java.util.Locale

/*
 * Formatting model of WordprocessingML (ECMA-376) as far as the print layout needs it. Lengths are converted to CSS px
 * at 96 dpi on parsing: twips / 15, eighths of a point * 96 / 576, EMU / 9525. Font sizes stay in points.
 */

internal fun Element.child(name: String): Element? = children().firstOrNull { it.tagName() == name }
internal fun Element.kids(name: String): List<Element> = children().filter { it.tagName() == name }
internal fun Element.wAttr(name: String): String? = attr("w:$name").takeIf { hasAttr("w:$name") }

/** On/off property: present without w:val, or with a true value. */
internal fun Element?.onOff(): Boolean? {
    if (this == null) return null
    val v = wAttr("val") ?: return true
    return v != "0" && v != "false" && v != "off" && v != "none"
}

internal fun twips(v: String?): Double? = v?.trim()?.toDoubleOrNull()?.div(15.0)
internal fun Double.css(): String = if (this == Math.floor(this) && Math.abs(this) < 1e7) toLong().toString() else String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')

internal fun color(v: String?): String? {
    if (v == null || v.equals("auto", true) || v.isBlank()) return null
    val hex = v.trim().removePrefix("#")
    return if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) "#" + hex.uppercase() else null
}

/** Named highlight colors of w:highlight. */
internal fun highlight(v: String?): String? = when (v) {
    null, "none" -> null
    "yellow" -> "#FFFF00"; "green" -> "#00FF00"; "cyan" -> "#00FFFF"; "magenta" -> "#FF00FF"; "blue" -> "#0000FF"
    "red" -> "#FF0000"; "darkBlue" -> "#000080"; "darkCyan" -> "#008080"; "darkGreen" -> "#008000"
    "darkMagenta" -> "#800080"; "darkRed" -> "#800000"; "darkYellow" -> "#808000"; "darkGray" -> "#808080"
    "lightGray" -> "#C0C0C0"; "black" -> "#000000"; "white" -> "#FFFFFF"
    else -> null
}

/** Background of w:shd: fill, or the pattern color mixed in for solid/percent patterns. */
internal fun shading(e: Element?): String? {
    if (e == null) return null
    val fill = color(e.wAttr("fill"))
    val pattern = e.wAttr("val").orEmpty()
    val fore = color(e.wAttr("color")) ?: "#000000"
    return when {
        pattern == "solid" -> fore
        pattern.startsWith("pct") -> {
            val pct = pattern.removePrefix("pct").toIntOrNull() ?: return fill
            mix(fill ?: "#FFFFFF", fore, pct / 100.0)
        }
        pattern == "nil" -> null
        else -> fill
    }
}

private fun mix(a: String, b: String, t: Double): String {
    fun ch(s: String, i: Int) = s.substring(1 + i * 2, 3 + i * 2).toInt(16)
    val r = (0..2).map { i -> (ch(a, i) * (1 - t) + ch(b, i) * t).toInt().coerceIn(0, 255) }
    return "#%02X%02X%02X".format(r[0], r[1], r[2])
}

internal data class Border(val style: String, val widthPx: Double, val color: String?, val spacePx: Double) {
    val none: Boolean get() = style == "none" || style == "nil"

    fun css(): String {
        if (none) return "none"
        val css = when (style) {
            "double", "triple", "thinThickSmallGap", "thickThinSmallGap", "thinThickMediumGap", "thickThinMediumGap" -> "double"
            "dotted" -> "dotted"
            "dashed", "dashSmallGap", "dotDash", "dotDotDash" -> "dashed"
            "inset" -> "inset"
            "outset" -> "outset"
            else -> "solid"
        }
        val w = if (css == "double") maxOf(widthPx, 2.5) else maxOf(widthPx, 0.75)
        return "${w.css()}px $css ${color ?: "#000000"}"
    }

    companion object {
        fun parse(e: Element?): Border? {
            if (e == null) return null
            val style = e.wAttr("val") ?: return null
            val eighths = e.wAttr("sz")?.toDoubleOrNull() ?: 4.0
            return Border(style, eighths / 6.0, color(e.wAttr("color")), (e.wAttr("space")?.toDoubleOrNull() ?: 0.0) * 96 / 72)
        }

        fun parseAll(e: Element?, sides: List<String>): Map<String, Border> {
            if (e == null) return emptyMap()
            val out = LinkedHashMap<String, Border>()
            for (s in sides) {
                val b = parse(e.child("w:$s") ?: alias(e, s)) ?: continue
                out[s] = b
            }
            return out
        }

        private fun alias(e: Element, side: String): Element? = when (side) {
            "left" -> e.child("w:start")
            "right" -> e.child("w:end")
            else -> null
        }
    }
}

internal data class TabStop(val posPx: Double, val align: String, val leader: String)

/** Run (character) properties; null means "inherit". */
internal data class RPr(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: String? = null,
    val strike: Boolean? = null,
    val dstrike: Boolean? = null,
    val caps: Boolean? = null,
    val smallCaps: Boolean? = null,
    val vanish: Boolean? = null,
    val vertAlign: String? = null,
    val sizePt: Double? = null,
    val color: String? = null,
    val highlight: String? = null,
    val shading: String? = null,
    val font: String? = null,
    val fontTheme: String? = null,
    val spacingPx: Double? = null,
    val style: String? = null,
) {
    fun merge(o: RPr?): RPr = if (o == null) this else RPr(
        bold = o.bold ?: bold, italic = o.italic ?: italic, underline = o.underline ?: underline, strike = o.strike ?: strike,
        dstrike = o.dstrike ?: dstrike, caps = o.caps ?: caps, smallCaps = o.smallCaps ?: smallCaps, vanish = o.vanish ?: vanish,
        vertAlign = o.vertAlign ?: vertAlign, sizePt = o.sizePt ?: sizePt, color = o.color ?: color, highlight = o.highlight ?: highlight,
        shading = o.shading ?: shading,
        font = if (o.font != null || o.fontTheme != null) o.font else font,
        fontTheme = if (o.font != null || o.fontTheme != null) o.fontTheme else fontTheme,
        spacingPx = o.spacingPx ?: spacingPx, style = o.style ?: style,
    )

    companion object {
        fun parse(e: Element?): RPr? {
            if (e == null) return null
            val fonts = e.child("w:rFonts")
            val u = e.child("w:u")
            return RPr(
                bold = e.child("w:b").onOff(),
                italic = e.child("w:i").onOff(),
                underline = u?.let { it.wAttr("val") ?: "single" },
                strike = e.child("w:strike").onOff(),
                dstrike = e.child("w:dstrike").onOff(),
                caps = e.child("w:caps").onOff(),
                smallCaps = e.child("w:smallCaps").onOff(),
                vanish = e.child("w:vanish").onOff(),
                vertAlign = e.child("w:vertAlign")?.wAttr("val"),
                sizePt = e.child("w:sz")?.wAttr("val")?.toDoubleOrNull()?.div(2),
                color = e.child("w:color")?.let { c -> color(c.wAttr("val")) ?: if (c.wAttr("val") == "auto") "auto" else null },
                highlight = highlight(e.child("w:highlight")?.wAttr("val")),
                shading = shading(e.child("w:shd")),
                font = fonts?.let { it.wAttr("ascii") ?: it.wAttr("hAnsi") },
                fontTheme = fonts?.let { it.wAttr("asciiTheme") ?: it.wAttr("hAnsiTheme") },
                spacingPx = twips(e.child("w:spacing")?.wAttr("val")),
                style = e.child("w:rStyle")?.wAttr("val"),
            )
        }
    }
}

/** Paragraph properties; null means "inherit". Lengths in px. */
internal data class PPr(
    val jc: String? = null,
    val indLeft: Double? = null,
    val indRight: Double? = null,
    val firstLine: Double? = null,
    val hanging: Double? = null,
    val before: Double? = null,
    val after: Double? = null,
    val beforeAuto: Boolean? = null,
    val afterAuto: Boolean? = null,
    val line: Double? = null,
    val lineRule: String? = null,
    val keepNext: Boolean? = null,
    val keepLines: Boolean? = null,
    val pageBreakBefore: Boolean? = null,
    val widowControl: Boolean? = null,
    val contextual: Boolean? = null,
    val shading: String? = null,
    val borders: Map<String, Border>? = null,
    val numId: String? = null,
    val ilvl: Int? = null,
    val tabs: List<TabStop>? = null,
    val clearedTabs: List<Double>? = null,
    val bidi: Boolean? = null,
    val style: String? = null,
    val outline: Int? = null,
) {
    fun merge(o: PPr?): PPr {
        if (o == null) return this
        val indentChanged = o.firstLine != null || o.hanging != null
        val mergedTabs = when {
            o.tabs == null && o.clearedTabs == null -> tabs
            else -> (tabs.orEmpty().filter { t -> o.clearedTabs.orEmpty().none { Math.abs(it - t.posPx) < 0.5 } } + o.tabs.orEmpty())
                .sortedBy { it.posPx }
        }
        return PPr(
            jc = o.jc ?: jc, indLeft = o.indLeft ?: indLeft, indRight = o.indRight ?: indRight,
            firstLine = if (indentChanged) o.firstLine else firstLine, hanging = if (indentChanged) o.hanging else hanging,
            before = o.before ?: before, after = o.after ?: after, beforeAuto = o.beforeAuto ?: beforeAuto, afterAuto = o.afterAuto ?: afterAuto,
            line = o.line ?: line, lineRule = if (o.line != null) o.lineRule else lineRule,
            keepNext = o.keepNext ?: keepNext, keepLines = o.keepLines ?: keepLines, pageBreakBefore = o.pageBreakBefore ?: pageBreakBefore,
            widowControl = o.widowControl ?: widowControl, contextual = o.contextual ?: contextual, shading = o.shading ?: shading,
            borders = if (o.borders != null) (borders.orEmpty() + o.borders) else borders,
            numId = o.numId ?: numId, ilvl = o.ilvl ?: ilvl, tabs = mergedTabs, clearedTabs = null, bidi = o.bidi ?: bidi,
            style = o.style ?: style, outline = o.outline ?: outline,
        )
    }

    companion object {
        fun parse(e: Element?): PPr? {
            if (e == null) return null
            val ind = e.child("w:ind")
            val sp = e.child("w:spacing")
            val num = e.child("w:numPr")
            val tabs = e.child("w:tabs")?.kids("w:tab")
            return PPr(
                jc = e.child("w:jc")?.wAttr("val"),
                indLeft = twips(ind?.let { it.wAttr("left") ?: it.wAttr("start") }),
                indRight = twips(ind?.let { it.wAttr("right") ?: it.wAttr("end") }),
                firstLine = twips(ind?.wAttr("firstLine")),
                hanging = twips(ind?.wAttr("hanging")),
                before = twips(sp?.wAttr("before")),
                after = twips(sp?.wAttr("after")),
                beforeAuto = sp?.wAttr("beforeAutospacing")?.let { it == "1" || it == "true" },
                afterAuto = sp?.wAttr("afterAutospacing")?.let { it == "1" || it == "true" },
                line = sp?.wAttr("line")?.toDoubleOrNull(),
                lineRule = sp?.wAttr("lineRule") ?: sp?.wAttr("line")?.let { "auto" },
                keepNext = e.child("w:keepNext").onOff(),
                keepLines = e.child("w:keepLines").onOff(),
                pageBreakBefore = e.child("w:pageBreakBefore").onOff(),
                widowControl = e.child("w:widowControl").onOff(),
                contextual = e.child("w:contextualSpacing").onOff(),
                shading = shading(e.child("w:shd")),
                borders = Border.parseAll(e.child("w:pBdr"), listOf("top", "left", "bottom", "right")).takeIf { it.isNotEmpty() },
                numId = num?.child("w:numId")?.wAttr("val"),
                ilvl = num?.child("w:ilvl")?.wAttr("val")?.toIntOrNull(),
                tabs = tabs?.filter { it.wAttr("val") != "clear" }?.mapNotNull { t ->
                    val pos = twips(t.wAttr("pos")) ?: return@mapNotNull null
                    TabStop(pos, t.wAttr("val") ?: "left", t.wAttr("leader") ?: "none")
                },
                clearedTabs = tabs?.filter { it.wAttr("val") == "clear" }?.mapNotNull { twips(it.wAttr("pos")) },
                bidi = e.child("w:bidi").onOff(),
                style = e.child("w:pStyle")?.wAttr("val"),
                outline = e.child("w:outlineLvl")?.wAttr("val")?.toIntOrNull(),
            )
        }
    }
}

/** Cell-level properties coming from a table style (whole table or a conditional part). */
internal data class CellFormat(
    val shading: String? = null,
    val borders: Map<String, Border> = emptyMap(),
    val rPr: RPr? = null,
    val pPr: PPr? = null,
    val vAlign: String? = null,
) {
    fun merge(o: CellFormat?): CellFormat = if (o == null) this else CellFormat(
        shading = o.shading ?: shading,
        borders = borders + o.borders,
        rPr = (rPr ?: RPr()).merge(o.rPr),
        pPr = (pPr ?: PPr()).merge(o.pPr),
        vAlign = o.vAlign ?: vAlign,
    )
}

internal class Style(
    val id: String,
    val type: String,
    val name: String,
    val basedOn: String?,
    val isDefault: Boolean,
    val pPr: PPr?,
    val rPr: RPr?,
    /** Table styles: borders and cell margins of the table, formatting of its parts. */
    val tableBorders: Map<String, Border> = emptyMap(),
    val cellMargins: Map<String, Double> = emptyMap(),
    val parts: Map<String, CellFormat> = emptyMap(),
    val rowBand: Int = 1,
    val colBand: Int = 1,
)

internal data class Level(
    val start: Int,
    val format: String,
    val text: String,
    val jc: String,
    val pPr: PPr?,
    val rPr: RPr?,
    val suffix: String,
    val legal: Boolean,
)
