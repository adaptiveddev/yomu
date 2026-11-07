package app.yomu.feature.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.yomu.data.book.EpubBuilder
import app.yomu.data.book.EpubMeta
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class OutFormat(val ext: String, val mime: String) { JPEG("jpg", "image/jpeg"), PNG("png", "image/png"), WEBP("webp", "image/webp") }

/** Fractions of the width/height removed from each side. */
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    val isEmpty: Boolean get() = left <= 0f && top <= 0f && right <= 0f && bottom <= 0f
}

/** Colour correction: brightness -1..1, contrast 0.5..2, levels black 0..0.5 / white 0.5..1. */
data class Adjust(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val grayscale: Boolean = false,
    val blackPoint: Float = 0f,
    val whitePoint: Float = 1f,
) {
    val isIdentity: Boolean get() = this == Adjust()

    /** 4x5 colour matrix (Android / Compose layout, offsets in 0..255). */
    fun matrix(): FloatArray {
        val levelScale = 1f / (whitePoint - blackPoint).coerceAtLeast(0.05f)
        val levelOffset = -blackPoint * 255f * levelScale
        val k = levelScale * contrast
        val o = levelOffset * contrast + 128f * (1f - contrast) + brightness * 255f
        return if (grayscale) {
            floatArrayOf(
                0.299f * k, 0.587f * k, 0.114f * k, 0f, o,
                0.299f * k, 0.587f * k, 0.114f * k, 0f, o,
                0.299f * k, 0.587f * k, 0.114f * k, 0f, o,
                0f, 0f, 0f, 1f, 0f,
            )
        } else {
            floatArrayOf(
                k, 0f, 0f, 0f, o,
                0f, k, 0f, 0f, o,
                0f, 0f, k, 0f, o,
                0f, 0f, 0f, 1f, 0f,
            )
        }
    }
}

data class Recompress(val format: OutFormat = OutFormat.JPEG, val quality: Int = 85, val maxWidth: Int = 0)

object ImageOps {
    private const val MAX_PIXELS = 24_000_000L

    fun formatOf(file: File): OutFormat = when (file.extension.lowercase()) {
        "png" -> OutFormat.PNG
        "webp" -> OutFormat.WEBP
        else -> OutFormat.JPEG
    }

    fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    fun isImage(bytes: ByteArray): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    fun decode(file: File, maxSide: Int = 0): Bitmap {
        val (w, h) = bounds(file)
        require(w > 0 && h > 0) { "Not an image: ${file.name}" }
        var sample = 1
        while ((w.toLong() / sample) * (h.toLong() / sample) > MAX_PIXELS || (maxSide > 0 && max(w, h) / (sample * 2) >= maxSide)) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw IllegalArgumentException("Cannot decode ${file.name}")
    }

    @Suppress("DEPRECATION")
    fun encode(bmp: Bitmap, format: OutFormat, quality: Int): ByteArray = ByteArrayOutputStream().also { out ->
        val f = when (format) {
            OutFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutFormat.PNG -> Bitmap.CompressFormat.PNG
            OutFormat.WEBP -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        }
        if (format == OutFormat.JPEG && bmp.hasAlpha()) {
            // JPEG has no alpha: flatten on white.
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            flat.compress(f, quality, out)
            flat.recycle()
        } else {
            bmp.compress(f, quality, out)
        }
    }.toByteArray()

    fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun crop(bmp: Bitmap, r: CropRect): Bitmap {
        val l = (bmp.width * r.left).roundToInt().coerceIn(0, bmp.width - 1)
        val t = (bmp.height * r.top).roundToInt().coerceIn(0, bmp.height - 1)
        val w = (bmp.width * (1f - r.left - r.right)).roundToInt().coerceIn(1, bmp.width - l)
        val h = (bmp.height * (1f - r.top - r.bottom)).roundToInt().coerceIn(1, bmp.height - t)
        return Bitmap.createBitmap(bmp, l, t, w, h)
    }

    /** Detects uniform margins (white or black borders, scanner noise tolerated). */
    fun autoCropRect(bmp: Bitmap, tolerance: Int = 28): CropRect {
        val scale = 300f / max(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt().coerceAtLeast(1), (bmp.height * scale).roundToInt().coerceAtLeast(1), true) else bmp
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        val corners = listOf(px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1])
        val bg = corners.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: px[0]
        fun close(c: Int) = abs(Color.red(c) - Color.red(bg)) <= tolerance && abs(Color.green(c) - Color.green(bg)) <= tolerance && abs(Color.blue(c) - Color.blue(bg)) <= tolerance
        fun rowBlank(y: Int) = (0 until w).count { !close(px[y * w + it]) } <= w / 100
        fun colBlank(x: Int) = (0 until h).count { !close(px[it * w + x]) } <= h / 100
        var top = 0
        while (top < h / 2 && rowBlank(top)) top++
        var bottom = 0
        while (bottom < h / 2 && rowBlank(h - 1 - bottom)) bottom++
        var left = 0
        while (left < w / 2 && colBlank(left)) left++
        var right = 0
        while (right < w / 2 && colBlank(w - 1 - right)) right++
        val pad = 2 // keep a small margin
        fun frac(v: Int, total: Int) = ((v - pad).coerceAtLeast(0).toFloat() / total).coerceAtMost(0.4f)
        return CropRect(frac(left, w), frac(top, h), frac(right, w), frac(bottom, h))
    }

    fun adjust(bmp: Bitmap, a: Adjust): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(a.matrix())) }
        Canvas(out).drawBitmap(bmp, 0f, 0f, paint)
        return out
    }

    fun resize(bmp: Bitmap, maxWidth: Int): Bitmap {
        if (maxWidth <= 0 || bmp.width <= maxWidth) return bmp
        val h = (bmp.height.toLong() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
    }

    /** Splits a double page spread into (left, right) halves. */
    fun split(bmp: Bitmap): Pair<Bitmap, Bitmap> {
        val half = bmp.width / 2
        return Bitmap.createBitmap(bmp, 0, 0, half, bmp.height) to Bitmap.createBitmap(bmp, half, 0, bmp.width - half, bmp.height)
    }

    /** Joins two pages side by side (scaled to the same height). */
    fun join(left: Bitmap, right: Bitmap): Bitmap {
        val h = max(left.height, right.height)
        val lw = left.width * h / left.height
        val rw = right.width * h / right.height
        val out = Bitmap.createBitmap(lw + rw, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(left, null, android.graphics.Rect(0, 0, lw, h), p)
        c.drawBitmap(right, null, android.graphics.Rect(lw, 0, lw + rw, h), p)
        return out
    }

    /** Bytes of a page with its rotation applied (used by exports). */
    fun rendered(file: File, rotation: Int): Pair<ByteArray, String> {
        val format = formatOf(file).let { if (it == OutFormat.PNG) it else OutFormat.JPEG }
        val bmp = decode(file)
        val rotated = rotate(bmp, rotation)
        val bytes = encode(rotated, format, 92)
        if (rotated !== bmp) rotated.recycle()
        bmp.recycle()
        return bytes to format.ext
    }
}

/** Simple exports that need Android graphics. */
object EditorExports {
    /** Text-only PDF (A5 pages, one chapter per page break). */
    fun textPdf(title: String, chapters: List<Pair<String, String>>, out: OutputStream) {
        val pageW = 420
        val pageH = 595
        val margin = 36
        val width = pageW - margin * 2
        val doc = PdfDocument()
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        val heading = TextPaint(body).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = margin
        }
        fun draw(text: String, paint: TextPaint, spacingAfter: Int) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
            var line = 0
            while (line < layout.lineCount) {
                if (page == null || y + (layout.getLineBottom(line) - layout.getLineTop(line)) > pageH - margin) newPage()
                // Draw as many lines as fit on the current page.
                val startTop = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && y + layout.getLineBottom(end) - startTop <= pageH - margin) end++
                if (end == line) end = line + 1
                val canvas = page!!.canvas
                canvas.save()
                canvas.translate(margin.toFloat(), (y - startTop).toFloat())
                canvas.clipRect(0, startTop, width, layout.getLineBottom(end - 1))
                layout.draw(canvas)
                canvas.restore()
                y += layout.getLineBottom(end - 1) - startTop
                line = end
            }
            y += spacingAfter
        }
        newPage()
        draw(title, TextPaint(heading).apply { textSize = 22f }, 24)
        chapters.forEach { (chapterTitle, text) ->
            newPage()
            if (chapterTitle.isNotBlank()) draw(chapterTitle, heading, 12)
            text.split("\n").filter { it.isNotBlank() }.forEach { draw(it, body, 4) }
        }
        page?.let { doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }

    /** One image per PDF page (pages are downscaled to 1600 px on the long side). */
    fun mangaPdf(ws: MangaWorkspace, out: OutputStream, progress: (Float) -> Unit) {
        val doc = PdfDocument()
        ws.pages.forEachIndexed { i, p ->
            val bmp = ImageOps.decode(ws.file(p), maxSide = 1600)
            val rotated = ImageOps.rotate(bmp, p.rotation)
            val page = doc.startPage(PdfDocument.PageInfo.Builder(rotated.width, rotated.height, i + 1).create())
            page.canvas.drawBitmap(rotated, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(page)
            if (rotated !== bmp) rotated.recycle()
            bmp.recycle()
            progress((i + 1f) / ws.pages.size)
        }
        doc.writeTo(out)
        doc.close()
    }

    /** Image EPUB (one page per document, right-to-left when the manga flag is set). */
    fun mangaEpub(ws: MangaWorkspace, fallbackTitle: String, out: OutputStream, progress: (Float) -> Unit) {
        val info = ws.info
        val builder = EpubBuilder(
            EpubMeta(
                title = info.title.ifBlank { info.series.ifBlank { fallbackTitle } },
                authors = info.writer.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                language = info.languageIso.ifBlank { null },
                description = info.summary.ifBlank { null },
                series = info.series.ifBlank { null },
                seriesIndex = info.number.toFloatOrNull(),
                subjects = info.genre.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            ),
        )
        builder.rtl = info.manga
        builder.css = "body { margin: 0; padding: 0; text-align: center; }\nimg { max-width: 100%; max-height: 100vh; }\n"
        ws.pages.forEachIndexed { i, p ->
            val (bytes, ext) = if (p.rotation == 0) ws.file(p).readBytes() to ws.file(p).extension.lowercase() else ImageOps.rendered(ws.file(p), p.rotation)
            val href = builder.addResource("p%04d.%s".format(i + 1, ext), MediaTypes.of("x.$ext"), bytes)
            if (i == 0) builder.coverHref = href
            builder.addChapter("${i + 1}", "<div><img src=\"../$href\" alt=\"\"/></div>", alreadyXhtml = true)
            progress((i + 1f) / ws.pages.size)
        }
        builder.write(out)
    }
}
