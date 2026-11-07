package app.yomu.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How the original lettering under a text block is covered. */
enum class TypesetFill { AUTO, WHITE, BLACK, NONE }

/** Colour of the new lettering; AUTO contrasts with the background. */
enum class TypesetInk { AUTO, BLACK, WHITE }

/**
 * A block of translated text to letter onto a manga page (2.0.0). The frame is given in fractions of the page;
 * [original] is the recognized text, [text] what is drawn.
 */
data class TypesetBlock(
    val id: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val original: String,
    val text: String,
    val fill: TypesetFill = TypesetFill.AUTO,
    val ink: TypesetInk = TypesetInk.AUTO,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Pixel rectangle, right and bottom exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun union(o: PixelRect) = PixelRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
}

/** The pixel work of lettering, on plain ARGB arrays (so it is testable without Android). */
object TypesetMath {
    /** The most common colour inside [r] of the [width]-wide image [px] (colours bucketed by 4 bits a channel). */
    fun dominantColor(px: IntArray, width: Int, r: PixelRect): Int {
        val counts = HashMap<Int, IntArray>()
        val stepX = max(1, r.width / 96)
        val stepY = max(1, r.height / 96)
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val c = px[y * width + x]
                val key = ((c shr 20) and 0xF shl 8) or ((c shr 12) and 0xF shl 4) or ((c shr 4) and 0xF)
                val acc = counts.getOrPut(key) { IntArray(4) }
                acc[0] += (c shr 16) and 0xFF
                acc[1] += (c shr 8) and 0xFF
                acc[2] += c and 0xFF
                acc[3]++
                x += stepX
            }
            y += stepY
        }
        val best = counts.values.maxByOrNull { it[3] } ?: return WHITE
        val n = best[3]
        return (0xFF shl 24) or ((best[0] / n) shl 16) or ((best[1] / n) shl 8) or (best[2] / n)
    }

    fun distance(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))

    fun luminance(c: Int): Float = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f

    /**
     * The speech bubble around the text frame [text]: the area of background-coloured pixels connected to the frame,
     * searched within a window around it. Null when the area is not closed (text over open art or a plain page), then the
     * frame itself is used.
     */
    fun bubble(px: IntArray, width: Int, height: Int, text: PixelRect, background: Int, tolerance: Int = 60): PixelRect? {
        val padX = max(12, text.width * 3 / 2)
        val padY = max(12, text.height * 3 / 2)
        val win = PixelRect(max(0, text.left - padX), max(0, text.top - padY), min(width, text.right + padX), min(height, text.bottom + padY))
        val ww = win.width
        val wh = win.height
        if (ww <= 0 || wh <= 0) return null
        val seen = BooleanArray(ww * wh)
        val queue = IntArray(ww * wh)
        var head = 0
        var tail = 0
        fun similar(x: Int, y: Int) = distance(px[y * width + x], background) <= tolerance
        // Seeds: background pixels of the frame's outline (the lettering itself is inside).
        for (x in max(text.left, win.left) until min(text.right, win.right)) {
            for (y in intArrayOf(text.top, text.bottom - 1)) {
                if (y in win.top until win.bottom && similar(x, y)) {
                    val i = (y - win.top) * ww + (x - win.left)
                    if (!seen[i]) { seen[i] = true; queue[tail++] = i }
                }
            }
        }
        for (y in max(text.top, win.top) until min(text.bottom, win.bottom)) {
            for (x in intArrayOf(text.left, text.right - 1)) {
                if (x in win.left until win.right && similar(x, y)) {
                    val i = (y - win.top) * ww + (x - win.left)
                    if (!seen[i]) { seen[i] = true; queue[tail++] = i }
                }
            }
        }
        if (tail == 0) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        val dx = intArrayOf(1, -1, 0, 0)
        val dy = intArrayOf(0, 0, 1, -1)
        while (head < tail) {
            val i = queue[head++]
            val lx = i % ww
            val ly = i / ww
            minX = min(minX, lx); maxX = max(maxX, lx); minY = min(minY, ly); maxY = max(maxY, ly)
            for (k in 0 until 4) {
                val nx = lx + dx[k]
                val ny = ly + dy[k]
                if (nx < 0 || ny < 0 || nx >= ww || ny >= wh) continue
                val j = ny * ww + nx
                if (seen[j]) continue
                if (!similar(nx + win.left, ny + win.top)) continue
                seen[j] = true
                queue[tail++] = j
            }
        }
        // Touching two or more sides of the window: not a closed bubble.
        var sides = 0
        if (minX == 0) sides++
        if (maxX == ww - 1) sides++
        if (minY == 0) sides++
        if (maxY == wh - 1) sides++
        if (sides >= 2) return null
        return PixelRect(minX + win.left, minY + win.top, maxX + win.left + 1, maxY + win.top + 1)
    }

    /** Where the new text goes: inside the bubble (with a margin) but at least the recognized frame. */
    fun textArea(frame: PixelRect, bubble: PixelRect?): PixelRect {
        if (bubble == null) return frame
        val mx = (bubble.width * 0.12f).roundToInt()
        val my = (bubble.height * 0.12f).roundToInt()
        val inner = PixelRect(bubble.left + mx, bubble.top + my, bubble.right - mx, bubble.bottom - my)
        return if (inner.width <= 0 || inner.height <= 0) frame else inner.union(frame)
    }

    /**
     * The largest font size (px) for which [measure] (size → laid-out height, or null when a word does not fit the width)
     * fits [maxHeight]; between [min] and [max].
     */
    fun fitSize(maxHeight: Int, min: Float, max: Float, measure: (Float) -> Int?): Float {
        var lo = min
        var hi = max
        if (measure(lo)?.let { it <= maxHeight } != true) return lo
        repeat(14) {
            val mid = (lo + hi) / 2f
            if (measure(mid)?.let { it <= maxHeight } == true) lo = mid else hi = mid
        }
        return lo
    }

    const val WHITE = -0x1
    const val BLACK = -0x1000000
}

/** Draws [TypesetBlock]s onto a page: covers the original lettering and letters the new text fitted to the bubble. */
object Typesetter {
    fun apply(src: Bitmap, blocks: List<TypesetBlock>): Bitmap {
        if (blocks.none { it.text.isNotBlank() || it.fill != TypesetFill.NONE }) return src
        val out = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        val canvas = Canvas(out)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        blocks.forEach { b ->
            val frame = PixelRect(
                (b.left * w).roundToInt().coerceIn(0, w - 1), (b.top * h).roundToInt().coerceIn(0, h - 1),
                (b.right * w).roundToInt().coerceIn(1, w), (b.bottom * h).roundToInt().coerceIn(1, h),
            )
            if (frame.width <= 1 || frame.height <= 1) return@forEach
            val background = TypesetMath.dominantColor(px, w, frame)
            val bubble = TypesetMath.bubble(px, w, h, frame, background)
            val cover = when (b.fill) {
                TypesetFill.AUTO -> background
                TypesetFill.WHITE -> TypesetMath.WHITE
                TypesetFill.BLACK -> TypesetMath.BLACK
                TypesetFill.NONE -> null
            }
            if (cover != null) {
                val pad = max(2f, min(frame.width, frame.height) * 0.04f)
                fillPaint.color = cover
                canvas.drawRect(RectF(frame.left - pad, frame.top - pad, frame.right + pad, frame.bottom + pad), fillPaint)
            }
            val text = b.text.trim()
            if (text.isEmpty()) return@forEach
            val under = cover ?: background
            val ink = when (b.ink) {
                TypesetInk.BLACK -> TypesetMath.BLACK
                TypesetInk.WHITE -> TypesetMath.WHITE
                TypesetInk.AUTO -> if (TypesetMath.luminance(under) > 0.5f) TypesetMath.BLACK else TypesetMath.WHITE
            }
            val area = TypesetMath.textArea(frame, if (cover != null || b.fill == TypesetFill.NONE) bubble else null)
            drawFitted(canvas, text, area, ink, outline = b.fill == TypesetFill.NONE)
        }
        return out
    }

    private fun drawFitted(canvas: Canvas, text: String, area: PixelRect, ink: Int, outline: Boolean) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        val width = area.width.coerceAtLeast(8)
        fun layout(size: Float): StaticLayout {
            paint.textSize = size
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 0.92f)
                .setIncludePad(false)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
                .build()
        }
        val maxSize = (area.height * 0.9f).coerceAtLeast(8f).coerceAtMost(area.width * 0.5f + 8f)
        val size = TypesetMath.fitSize(area.height, 6f, maxSize) { s ->
            val l = layout(s)
            // A word wider than the line would be broken in the middle: too big.
            val longest = text.split(Regex("\\s+")).maxOfOrNull { paint.measureText(it) } ?: 0f
            if (longest > width) null else l.height
        }
        val l = layout(size)
        canvas.save()
        canvas.translate(area.left.toFloat(), area.top + (area.height - l.height) / 2f)
        if (outline) {
            // Without a cover the text needs a halo to stay readable over the art.
            val ink0 = paint.color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(2f, size / 7f)
            paint.color = if (ink0 == TypesetMath.BLACK) TypesetMath.WHITE else TypesetMath.BLACK
            l.draw(canvas)
            paint.style = Paint.Style.FILL
            paint.color = ink0
        }
        l.draw(canvas)
        canvas.restore()
    }
}
