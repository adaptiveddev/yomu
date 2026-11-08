package app.yomu.data.book

/**
 * Geometric reflow of one PDF page's text lines into reading-order paragraphs.
 *
 * Input: the visual lines of a page ([PdfLine], from [PdfSource.lines]). An XY-cut over the
 * page's whitespace: the widest empty horizontal band or vertical gutter splits the lines
 * recursively; leaves become paragraphs (vertical gaps, first-line indentation, heading size
 * and bullets break them; hyphenated words are joined). Pure and deterministic: unit tests
 * cover the layouts, the native side only feeds the geometry.
 */
object PdfReflow {
    fun paragraphs(lines: List<PdfLine>, pageWidth: Float): List<String> {
        val cleaned = lines
            .map { it.copy(text = it.text.replace(Regex("\\s+"), " ").trim()) }
            .filter { it.text.isNotEmpty() }
        if (cleaned.isEmpty()) return emptyList()
        val body = cleaned.filterNot { it.isPageNumber(cleaned) }
        if (body.isEmpty()) return emptyList()
        val blocks = cut(body.toList(), pageWidth, depth = 0)
        val paragraphs = mutableListOf<String>()
        for (block in blocks) buildParagraphs(block, pageWidth, paragraphs)
        return paragraphs.filter { it.isNotBlank() }
    }

    /** Page numbers: a short number-only line in the top/bottom 10% of the page's text span. */
    private fun PdfLine.isPageNumber(all: List<PdfLine>): Boolean {
        if (!Regex("""^[\[\(\u2014\u2013 .-]*\d{1,4}[\]\)\u2014\u2013 .-]*$""").matches(text)) return false
        if (text.length > 8) return false
        val minTop = all.minOf { it.top }; val maxBottom = all.maxOf { it.bottom }
        val span = (maxBottom - minTop).coerceAtLeast(1f)
        return top - minTop < span * 0.12f || maxBottom - bottom < span * 0.12f
    }

    private data class Cut(val width: Float, val above: List<PdfLine>, val below: List<PdfLine>, val left: List<PdfLine>, val right: List<PdfLine>)

    /** XY-cut: recurse into the widest whitespace band; without one, the block is a leaf. */
    private fun cut(lines: List<PdfLine>, pageWidth: Float, depth: Int): List<List<PdfLine>> {
        if (lines.size < 2 || depth >= 4) return listOf(lines.sortedBy { it.top })
        val medianHeight = lines.map { it.height }.sorted()[lines.size / 2].coerceAtLeast(1f)
        val horizontal = horizontalCut(lines, medianHeight)
        val horizontalValid = horizontal != null && horizontal.above.isNotEmpty() && horizontal.below.isNotEmpty()
        val vertical = verticalGutter(lines, pageWidth)
        val verticalValid = vertical != null && vertical.left.size >= 2 && vertical.right.size >= 2 &&
            vertical.left.size + vertical.right.size == lines.size
        return when {
            // The widest whitespace band wins; an invalid vertical gutter falls back to the horizontal cut.
            verticalValid && (horizontal == null || !horizontalValid || vertical.width >= horizontal.width) ->
                cut(vertical.left, pageWidth, depth + 1) + cut(vertical.right, pageWidth, depth + 1)
            horizontalValid -> cut(horizontal.above, pageWidth, depth + 1) + cut(horizontal.below, pageWidth, depth + 1)
            else -> listOf(lines.sortedBy { it.top })
        }
    }

    /** The widest empty horizontal band between two line groups; both groups must be non-empty. */
    private fun horizontalCut(lines: List<PdfLine>, medianHeight: Float): Cut? {
        val sorted = lines.sortedBy { it.top }
        val minHeight = 0.4f * medianHeight
        var best: Cut? = null
        var bestWidth = minHeight
        for (i in 0 until sorted.size - 1) {
            val gap = sorted[i + 1].top - sorted[i].bottom
            if (gap <= bestWidth) continue
            val above = sorted.subList(0, i + 1)
            val below = sorted.subList(i + 1, sorted.size)
            best = Cut(gap, above, below, emptyList(), emptyList())
            bestWidth = gap
        }
        return best?.takeIf { it.width > minHeight }
    }

    /**
     * The widest vertical gap that no line crosses, is ~4% of the page wide, and whose sides
     * hold at least two lines with largely overlapping vertical spans (a real column split).
     */
    private fun verticalGutter(lines: List<PdfLine>, pageWidth: Float): Cut? {
        val edges = (lines.map { it.left } + lines.map { it.right }).distinct().sorted()
        val minSpan = (lines.maxOf { it.bottom } - lines.minOf { it.top }).coerceAtLeast(1f)
        val minGutter = 0.035f * pageWidth.coerceAtLeast(1f)
        var best: Cut? = null
        var bestWidth = minGutter
        for (i in 0 until edges.size - 1) {
            val a = edges[i]; val b = edges[i + 1]
            if (b - a < bestWidth) continue
            val x = (a + b) / 2f
            val left = lines.filter { it.right <= x + 1f }
            val right = lines.filter { it.left >= x - 1f }
            if (left.size < 2 || right.size < 2 || left.size + right.size != lines.size) continue
            val lSpan = (left.maxOf { it.bottom } - left.minOf { it.top }).coerceAtLeast(1f)
            val rSpan = (right.maxOf { it.bottom } - right.minOf { it.top }).coerceAtLeast(1f)
            val overlap = minOf(left.maxOf { it.bottom }, right.maxOf { it.bottom }) -
                maxOf(left.minOf { it.top }, right.minOf { it.top })
            // The two sides must share most of their vertical extent (a real column split,
            // not a heading above a full-width paragraph).
            if (overlap < 0.55f * minOf(lSpan, rSpan) || overlap < 0.45f * minSpan) continue
            best = Cut(b - a, emptyList(), emptyList(), left, right)
            bestWidth = b - a
        }
        return best
    }

    private fun buildParagraphs(column: List<PdfLine>, pageWidth: Float, out: MutableList<String>) {
        val sorted = column.sortedBy { it.top }
        if (sorted.size == 1) { out += sorted.single().text; return }
        val medianHeight = sorted.map { it.height }.sorted()[sorted.size / 2].coerceAtLeast(1f)
        val medianLeft = sorted.map { it.left }.sorted()[sorted.size / 2]

        var current = StringBuilder()
        var previous: PdfLine? = null
        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) out += text
            current = StringBuilder()
        }
        for (line in sorted) {
            val prev = previous
            val gap = if (prev == null) 0f else line.top - prev.bottom
            val isHeading = line.height > medianHeight * 1.3f && line.text.length <= 120
            val isIndented = line.left > medianLeft + 0.02f * pageWidth.coerceAtLeast(1f)
            val isBullet = Regex("""^([\u2022\u25CF\u25AA\u00B7-]|\(?\d{1,2}[.)]|[a-z][.)])\s""").containsMatchIn(line.text)
            if (prev != null && (gap > medianHeight * 0.7f || isIndented || isHeading || isBullet)) flush()
            val text = line.text
            if (current.isEmpty()) {
                current.append(text)
            } else {
                val endsHyphen = current.endsWith("-") || current.endsWith("\u00ad")
                val startsLower = text.firstOrNull()?.isLowerCase() == true
                when {
                    current.endsWith("\u00ad") -> {
                        current.setLength(current.length - 1); current.append(text)
                    }
                    endsHyphen && startsLower -> {
                        current.setLength(current.length - 1); current.append(text)
                    }
                    else -> current.append(' ').append(text)
                }
            }
            previous = line
        }
        flush()
    }
}
