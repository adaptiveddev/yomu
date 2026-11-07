package app.yomu.feature.annotations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.yomu.core.common.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Renders a quote as a shareable PNG card (1080 px wide) and opens the share sheet. */
object QuoteImage {
    data class Style(val background: Int, val backgroundEnd: Int, val text: Int, val accent: Int)

    val LIGHT = Style(0xFFFBF7EF.toInt(), 0xFFF1E6D2.toInt(), 0xFF2B2622.toInt(), 0xFF9A6B2F.toInt())
    val DARK = Style(0xFF1C1B22.toInt(), 0xFF2C2A38.toInt(), 0xFFECE7F5.toInt(), 0xFFB9A7FF.toInt())

    fun render(quote: String, title: String, author: String?, style: Style, width: Int = 1080): Bitmap {
        val pad = width / 11
        val inner = width - pad * 2
        val textSize = when {
            quote.length < 120 -> width / 15f
            quote.length < 300 -> width / 19f
            else -> width / 24f
        }
        val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.text
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.accent
            this.textSize = width / 32f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val body = quote.trim().take(1200)
        val quoteLayout = StaticLayout.Builder.obtain(body, 0, body.length, quotePaint, inner)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f).build()
        val meta = listOfNotNull(title.takeIf { it.isNotBlank() }, author?.takeIf { it.isNotBlank() }).joinToString(" — ")
        val metaLayout = StaticLayout.Builder.obtain(meta, 0, meta.length, metaPaint, inner)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val markSize = width / 6f
        val height = (pad + markSize * 0.7f + quoteLayout.height + pad * 0.6f + metaLayout.height + pad).toInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), style.background, style.backgroundEnd, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.accent
            this.textSize = markSize
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        c.drawText("“", pad.toFloat() - markSize * 0.08f, pad + markSize * 0.7f, mark)
        c.save()
        c.translate(pad.toFloat(), pad + markSize * 0.7f)
        quoteLayout.draw(c)
        c.restore()
        c.save()
        c.translate(pad.toFloat(), pad + markSize * 0.7f + quoteLayout.height + pad * 0.6f)
        metaLayout.draw(c)
        c.restore()
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.text
            alpha = 90
            this.textSize = width / 40f
            textAlign = Paint.Align.RIGHT
        }
        c.drawText("Yomu", (width - pad / 2).toFloat(), (height - pad / 3).toFloat(), brand)
        return bmp
    }

    suspend fun share(context: Context, quote: String, title: String, author: String?, dark: Boolean, chooserTitle: String) {
        val file = withContext(Dispatchers.IO) {
            val bmp = render(quote, title, author, if (dark) DARK else LIGHT)
            File(Share.shareDir(context), "quote_${System.currentTimeMillis()}.png").also { f ->
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
        }
        Share.shareFile(context, file, "image/png", chooserTitle)
    }
}
