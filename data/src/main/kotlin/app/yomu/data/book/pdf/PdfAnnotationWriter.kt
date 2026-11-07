package app.yomu.data.book.pdf

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The kind of a text markup annotation (ISO 32000-1, 12.5.6.10). */
enum class MarkupKind(val subtype: String) { HIGHLIGHT("Highlight"), UNDERLINE("Underline"), STRIKEOUT("StrikeOut") }

/**
 * A highlight of Yomu as a PDF annotation. [rects] are in PDF user space: left, bottom, right, top (one per line of
 * text). [extra] goes into the private /Yomu dictionary so that Yomu can read the highlight back.
 */
data class PdfMarkup(
    val id: String,
    val page: Int,
    val kind: MarkupKind,
    val rects: List<FloatArray>,
    val rgb: Int,
    val opacity: Float,
    val contents: String?,
    val created: Long,
    val modified: Long,
    val extra: Map<String, PdfObject> = emptyMap(),
)

/** A Yomu annotation found in a PDF file. */
data class PdfFoundMarkup(val id: String, val page: Int, val kind: MarkupKind?, val contents: String?, val rgb: Int?, val extra: PdfDictionary?)

/**
 * Writes Yomu's highlights into a PDF as standard annotations with an incremental update (ISO 32000-1, 7.5.6):
 * the original bytes stay as they are, the new page versions, annotations, their appearance streams and a
 * cross-reference section are appended. Annotations Yomu wrote before (/NM starting with [ID_PREFIX]) are replaced,
 * those of other apps are kept.
 */
object PdfAnnotationWriter {
    const val ID_PREFIX = "yomu-"
    const val MARKER = "%Yomu annotations"
    const val AUTHOR = "Yomu"

    /**
     * The bytes to append to [bytes] so that its pages carry exactly [markups] of Yomu, or null when there is
     * nothing to change (no markups and none written before). Fails on encrypted or unreadable files.
     */
    fun increment(bytes: PdfBytes, markups: List<PdfMarkup>): ByteArray? {
        val pdf = PdfFile(bytes).load()
        if (pdf.encrypted) throw PdfFormatException("Encrypted PDF")
        val pages = pdf.pages()
        val byPage = markups.filter { it.page in pages.indices && it.rects.isNotEmpty() }.groupBy { it.page }
        // Pages that carry Yomu annotations of an earlier update, with the annotations they keep.
        val rewrite = LinkedHashMap<Int, List<PdfObject>>()
        pages.forEachIndexed { i, ref ->
            val page = pdf.getObject(ref.number) as? PdfDictionary ?: return@forEachIndexed
            val annots = pdf.resolve(page["Annots"]) as? PdfArray
            val kept = annots?.items?.filterNot { isYomu(pdf, it) }.orEmpty()
            if (i in byPage || (annots != null && kept.size != annots.size)) rewrite[i] = kept
        }
        if (rewrite.isEmpty()) return null

        val out = ByteArrayOutputStream()
        val base = bytes.length
        // The previous version may end without an end of line after %%EOF.
        out.ascii("\n$MARKER\n")
        val offsets = sortedMapOf<Int, Pair<Long, Int>>()
        var next = pdf.size
        fun begin(num: Int, gen: Int) {
            offsets[num] = (base + out.size()) to gen
            out.ascii("$num $gen obj\n")
        }

        rewrite.forEach { (index, kept) ->
            val ref = pages[index]
            val refs = ArrayList<PdfObject>(kept)
            byPage[index].orEmpty().forEach { m ->
                val annot = next++
                val ap = next++
                begin(ap, 0)
                writeAppearance(m, out)
                out.ascii("\nendobj\n")
                begin(annot, 0)
                PdfWriter.write(annotation(m, ref, PdfReference(ap, 0)), out)
                out.ascii("\nendobj\n")
                refs += PdfReference(annot, 0)
            }
            val page = (pdf.getObject(ref.number) as PdfDictionary).copy()
            if (refs.isEmpty()) page.entries.remove("Annots") else page["Annots"] = PdfArray(refs)
            begin(ref.number, ref.generation)
            PdfWriter.write(page, out)
            out.ascii("\nendobj\n")
        }

        val trailer = PdfDictionary()
        listOf("Root", "Info", "ID").forEach { k -> pdf.trailer[k]?.let { trailer[k] = it } }
        trailer["Prev"] = PdfNumber.of(pdf.startXref)
        if (pdf.usesXrefStream) {
            val self = next++
            val xrefAt = base + out.size()
            offsets[self] = xrefAt to 0
            val widest = offsets.values.maxOf { it.first }
            val w2 = if (widest > 0xFFFFFFFFL) 8 else 4
            val data = ByteArrayOutputStream()
            offsets.forEach { (_, v) ->
                data.write(1)
                for (s in (w2 - 1) downTo 0) data.write(((v.first ushr (8 * s)) and 0xFF).toInt())
                data.write((v.second ushr 8) and 0xFF)
                data.write(v.second and 0xFF)
            }
            val d = PdfDictionary()
            d["Type"] = PdfName("XRef")
            d["Size"] = PdfNumber.of(next)
            d["W"] = PdfArray(mutableListOf(PdfNumber.of(1), PdfNumber.of(w2), PdfNumber.of(2)))
            d["Index"] = PdfArray(subsections(offsets.keys).flatMap { (s, c) -> listOf(PdfNumber.of(s), PdfNumber.of(c)) }.toMutableList())
            trailer.entries.forEach { (k, v) -> d[k] = v }
            d["Length"] = PdfNumber.of(data.size())
            out.ascii("$self 0 obj\n")
            PdfWriter.write(d, out)
            out.ascii("\nstream\n")
            out.write(data.toByteArray())
            out.ascii("\nendstream\nendobj\n")
            out.ascii("startxref\n$xrefAt\n%%EOF\n")
        } else {
            val xrefAt = base + out.size()
            out.ascii("xref\n")
            subsections(offsets.keys).forEach { (start, count) ->
                out.ascii("$start $count\n")
                for (n in start until start + count) {
                    val (off, gen) = offsets.getValue(n)
                    out.ascii("%010d %05d n\r\n".format(off, gen))
                }
            }
            trailer["Size"] = PdfNumber.of(next)
            out.ascii("trailer\n")
            PdfWriter.write(trailer, out)
            out.ascii("\nstartxref\n$xrefAt\n%%EOF\n")
        }
        return out.toByteArray()
    }

    /** Yomu annotations in [bytes] (written by this or another device). */
    fun read(bytes: PdfBytes): List<PdfFoundMarkup> {
        val pdf = PdfFile(bytes).load()
        if (pdf.encrypted) return emptyList()
        val out = ArrayList<PdfFoundMarkup>()
        pdf.pages().forEachIndexed { i, ref ->
            val page = pdf.getObject(ref.number) as? PdfDictionary ?: return@forEachIndexed
            val annots = pdf.resolve(page["Annots"]) as? PdfArray ?: return@forEachIndexed
            annots.items.forEach { a ->
                val d = pdf.resolve(a) as? PdfDictionary ?: return@forEach
                val id = (pdf.resolve(d["NM"]) as? PdfString)?.text() ?: return@forEach
                if (!id.startsWith(ID_PREFIX)) return@forEach
                val kind = MarkupKind.entries.firstOrNull { it.subtype == d.name("Subtype") }
                val c = (pdf.resolve(d["C"]) as? PdfArray)?.items?.mapNotNull { (it as? PdfNumber)?.double }
                val rgb = if (c != null && c.size == 3) {
                    c.fold(0) { acc, v -> (acc shl 8) or Math.round(v.coerceIn(0.0, 1.0) * 255).toInt() }
                } else {
                    null
                }
                out += PdfFoundMarkup(id, i, kind, (pdf.resolve(d["Contents"]) as? PdfString)?.text(), rgb, pdf.resolve(d["Yomu"]) as? PdfDictionary)
            }
        }
        return out
    }

    private fun isYomu(pdf: PdfFile, annot: PdfObject): Boolean {
        val d = runCatching { pdf.resolve(annot) }.getOrNull() as? PdfDictionary ?: return false
        val nm = pdf.resolve(d["NM"]) as? PdfString ?: return false
        return nm.text().startsWith(ID_PREFIX)
    }

    /** Runs of consecutive object numbers: (first, count). */
    private fun subsections(numbers: Collection<Int>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        var count = 0
        numbers.sorted().forEach { n ->
            if (start >= 0 && n == start + count) {
                count++
            } else {
                if (start >= 0) out += start to count
                start = n
                count = 1
            }
        }
        if (start >= 0) out += start to count
        return out
    }

    private fun bounds(m: PdfMarkup): FloatArray {
        val l = m.rects.minOf { it[0] }
        val b = m.rects.minOf { it[1] }
        val r = m.rects.maxOf { it[2] }
        val t = m.rects.maxOf { it[3] }
        // Room for the pen of underlines and strike-outs.
        return floatArrayOf(l - 1f, b - 1f, r + 1f, t + 1f)
    }

    private fun color(rgb: Int): MutableList<PdfObject> = mutableListOf(
        PdfNumber.of(((rgb shr 16) and 0xFF) / 255f),
        PdfNumber.of(((rgb shr 8) and 0xFF) / 255f),
        PdfNumber.of((rgb and 0xFF) / 255f),
    )

    private fun annotation(m: PdfMarkup, page: PdfReference, ap: PdfReference): PdfDictionary {
        val d = PdfDictionary()
        d["Type"] = PdfName("Annot")
        d["Subtype"] = PdfName(m.kind.subtype)
        d["Rect"] = PdfArray(bounds(m).map { PdfNumber.of(it) as PdfObject }.toMutableList())
        // Upper left, upper right, lower left, lower right of every line (the order viewers use).
        d["QuadPoints"] = PdfArray(
            m.rects.flatMap { r -> listOf(r[0], r[3], r[2], r[3], r[0], r[1], r[2], r[1]) }.map { PdfNumber.of(it) as PdfObject }.toMutableList(),
        )
        d["C"] = PdfArray(color(m.rgb))
        d["CA"] = PdfNumber.of(m.opacity)
        d["F"] = PdfNumber.of(4)
        d["P"] = page
        d["NM"] = PdfString.text(m.id)
        d["T"] = PdfString.text(AUTHOR)
        d["CreationDate"] = PdfString.text(date(m.created))
        d["M"] = PdfString.text(date(m.modified))
        m.contents?.takeIf { it.isNotBlank() }?.let { d["Contents"] = PdfString.text(it) }
        d["AP"] = PdfDictionary(linkedMapOf("N" to ap))
        if (m.extra.isNotEmpty()) d["Yomu"] = PdfDictionary(LinkedHashMap(m.extra))
        return d
    }

    /** A form XObject drawing the markup, for viewers that do not draw annotations without an appearance. */
    private fun writeAppearance(m: PdfMarkup, out: ByteArrayOutputStream) {
        val (r, g, b) = color(m.rgb).map { (it as PdfNumber).text }
        val content = StringBuilder()
        val real = { v: Float -> PdfNumber.real(v.toDouble()) }
        when (m.kind) {
            MarkupKind.HIGHLIGHT -> {
                content.append("/Y0 gs\n$r $g $b rg\n")
                m.rects.forEach { q ->
                    content.append("${real(q[0])} ${real(q[1])} m ${real(q[2])} ${real(q[1])} l ${real(q[2])} ${real(q[3])} l ${real(q[0])} ${real(q[3])} l h f\n")
                }
            }
            MarkupKind.UNDERLINE, MarkupKind.STRIKEOUT -> {
                content.append("$r $g $b RG\n")
                m.rects.forEach { q ->
                    val h = q[3] - q[1]
                    val width = maxOf(0.6f, h * 0.07f)
                    val y = if (m.kind == MarkupKind.UNDERLINE) q[1] + h * 0.12f else q[1] + h * 0.45f
                    content.append("${real(width)} w ${real(q[0])} ${real(y)} m ${real(q[2])} ${real(y)} l S\n")
                }
            }
        }
        val data = content.toString().toByteArray(Charsets.ISO_8859_1)
        val d = PdfDictionary()
        d["Type"] = PdfName("XObject")
        d["Subtype"] = PdfName("Form")
        d["FormType"] = PdfNumber.of(1)
        d["BBox"] = PdfArray(bounds(m).map { PdfNumber.of(it) as PdfObject }.toMutableList())
        if (m.kind == MarkupKind.HIGHLIGHT) {
            val gs = PdfDictionary(linkedMapOf("Type" to PdfName("ExtGState"), "BM" to PdfName("Multiply")))
            d["Resources"] = PdfDictionary(linkedMapOf("ExtGState" to PdfDictionary(linkedMapOf("Y0" to gs))))
        }
        d["Length"] = PdfNumber.of(data.size)
        PdfWriter.write(d, out)
        out.ascii("\nstream\n")
        out.write(data)
        out.ascii("\nendstream")
    }

    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

    fun date(millis: Long): String = "D:" + dateFormat.format(Instant.ofEpochMilli(millis)) + "Z"
}
