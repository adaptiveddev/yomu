package app.yomu.profile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.io.File

/**
 * The picture shared from the profile: name, total reading time, four numbers and the most read titles on the
 * colours of the app theme. Drawn with android.graphics, off the main thread (see [render]).
 */
object ProfileCard {
    const val WIDTH = 1080
    const val HEIGHT = 1350

    /** ARGB colours: the two ends of the background gradient and the text on it. */
    data class Palette(val start: Int, val end: Int, val content: Int)

    data class Content(
        val brand: String,
        val avatar: String,
        val name: String,
        val subtitle: String,
        val total: String,
        val totalLabel: String,
        /** Up to four value/label pairs for the tiles. */
        val tiles: List<Pair<String, String>>,
        val topLabel: String,
        val top: List<String>,
        val footer: String,
    )

    /** Draws the card into [file] as a PNG and returns it. Call on a background thread. */
    fun render(file: File, content: Content, palette: Palette): File {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap), content, palette)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun draw(c: Canvas, content: Content, palette: Palette) {
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val margin = 84f

        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w, h, palette.start, palette.end, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, this)
        }
        // Soft circles for depth.
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.content, 0.07f)
            c.drawCircle(w * 0.92f, h * 0.08f, 300f, this)
            c.drawCircle(w * 0.05f, h * 0.95f, 240f, this)
        }

        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.content }
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        // Brand
        text.textSize = 34f
        text.typeface = bold
        text.letterSpacing = 0.25f
        c.drawText(content.brand.uppercase(), margin, 132f, text)
        text.letterSpacing = 0f

        // Avatar and name
        val avatarCx = margin + 72f
        val avatarCy = 270f
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.content, 0.16f)
            c.drawCircle(avatarCx, avatarCy, 72f, this)
        }
        text.textSize = if (content.avatar.codePointCount(0, content.avatar.length) == 1 && content.avatar[0].isLetterOrDigit()) 64f else 76f
        text.typeface = bold
        text.textAlign = Paint.Align.CENTER
        val fm = text.fontMetrics
        c.drawText(content.avatar, avatarCx, avatarCy - (fm.ascent + fm.descent) / 2, text)
        text.textAlign = Paint.Align.LEFT

        val nameX = avatarCx + 72f + 36f
        val nameWidth = w - nameX - margin
        text.textSize = 56f
        text.typeface = bold
        c.drawText(ellipsize(content.name, text, nameWidth), nameX, 262f, text)
        text.textSize = 32f
        text.typeface = Typeface.DEFAULT
        text.color = withAlpha(palette.content, 0.78f)
        c.drawText(ellipsize(content.subtitle, text, nameWidth), nameX, 314f, text)
        text.color = palette.content

        // Total time
        text.textSize = 132f
        text.typeface = bold
        c.drawText(ellipsize(content.total, text, w - 2 * margin), margin, 528f, text)
        text.textSize = 38f
        text.typeface = Typeface.DEFAULT
        text.color = withAlpha(palette.content, 0.82f)
        c.drawText(ellipsize(content.totalLabel, text, w - 2 * margin), margin, 588f, text)
        text.color = palette.content

        // Tiles
        val gap = 28f
        val tileW = (w - 2 * margin - gap) / 2
        val tileH = 172f
        val tilesTop = 650f
        val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.content, 0.12f) }
        content.tiles.take(4).forEachIndexed { i, (value, label) ->
            val left = margin + (i % 2) * (tileW + gap)
            val top = tilesTop + (i / 2) * (tileH + gap)
            c.drawRoundRect(RectF(left, top, left + tileW, top + tileH), 40f, 40f, tilePaint)
            text.textSize = 64f
            text.typeface = bold
            c.drawText(ellipsize(value, text, tileW - 64f), left + 32f, top + 88f, text)
            text.textSize = 30f
            text.typeface = Typeface.DEFAULT
            text.color = withAlpha(palette.content, 0.8f)
            c.drawText(ellipsize(label, text, tileW - 64f), left + 32f, top + 136f, text)
            text.color = palette.content
        }

        // Most read
        var y = tilesTop + 2 * tileH + gap + 84f
        if (content.top.isNotEmpty()) {
            text.textSize = 30f
            text.typeface = bold
            text.color = withAlpha(palette.content, 0.8f)
            c.drawText(content.topLabel.uppercase(), margin, y, text)
            text.color = palette.content
            text.typeface = Typeface.DEFAULT
            text.textSize = 36f
            content.top.take(3).forEachIndexed { i, title ->
                y += 54f
                c.drawText(ellipsize("${i + 1}. $title", text, w - 2 * margin), margin, y, text)
            }
        }

        // Footer
        text.textSize = 28f
        text.typeface = Typeface.DEFAULT
        text.color = withAlpha(palette.content, 0.7f)
        c.drawText(ellipsize(content.footer, text, w - 2 * margin), margin, h - 64f, text)
    }

    private fun ellipsize(s: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(s, paint, width, TextUtils.TruncateAt.END).toString()

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
