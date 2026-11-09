package app.yomu.feature.reader.manga

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorMatrix
import app.yomu.data.book.PageSource
import app.yomu.domain.model.MangaSettings
import app.yomu.domain.model.TapZoneLayout
import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

/** A text block recognised on a page, with coordinates as fractions of the image. */
data class OverlayBlock(val left: Float, val top: Float, val right: Float, val bottom: Float, val original: String, val translated: String?)

/** State of the image based reader. */
class MangaController(val pages: PageSource) {
    val count: Int get() = pages.count
    var page by mutableIntStateOf(0)
    var settings by mutableStateOf(MangaSettings())
    val overlays = mutableStateMapOf<Int, List<OverlayBlock>>()
    var overlayVisible by mutableStateOf(true)

    /** Cap on the pages preloaded ahead (the data saver on mobile data); null = the preload setting alone. */
    var preloadLimit by mutableStateOf<Int?>(null)

    /** Pages preloaded ahead of the current one. */
    val preloadAhead: Int get() = preloadLimit?.let { minOf(it, settings.preload) } ?: settings.preload

    /** Incremented to show the tap zone overlay for a few seconds. */
    var tapZonesHint by mutableIntStateOf(0)
        private set

    fun showTapZones() {
        tapZonesHint++
    }
    private val requests = Channel<Int>(Channel.CONFLATED)
    val pageRequests: Flow<Int> = requests.receiveAsFlow()

    suspend fun file(index: Int): File = pages.file(index.coerceIn(0, (count - 1).coerceAtLeast(0)))

    fun goTo(index: Int) {
        requests.trySend(index)
    }

    fun next() = goTo(page + 1)
    fun prev() = goTo(page - 1)
}

enum class TapAction { PREV, NEXT, MENU }

object TapZones {
    /** Action for a tap at fractions [x], [y] of the reader. */
    fun action(layout: TapZoneLayout, inverted: Boolean, rtl: Boolean, x: Float, y: Float, sideFraction: Float = 0.4f): TapAction {
        val raw = when (layout) {
            TapZoneLayout.DISABLED -> TapAction.MENU
            TapZoneLayout.LEFT_RIGHT -> when {
                x < sideFraction -> TapAction.PREV
                x > 1f - sideFraction -> TapAction.NEXT
                else -> TapAction.MENU
            }
            TapZoneLayout.EDGES -> when {
                x < 0.2f || x > 0.8f -> TapAction.NEXT
                y < 0.2f -> TapAction.PREV
                else -> TapAction.MENU
            }
            TapZoneLayout.KINDLE -> when {
                y < 0.2f -> TapAction.MENU
                x < 0.33f -> TapAction.PREV
                else -> TapAction.NEXT
            }
            TapZoneLayout.L_SHAPE -> when {
                y < 0.33f && x < 0.66f -> TapAction.PREV
                x < 0.33f && y < 0.66f -> TapAction.PREV
                y > 0.66f || x > 0.66f -> TapAction.NEXT
                else -> TapAction.MENU
            }
        }
        val flipped = (inverted xor (rtl && layout != TapZoneLayout.EDGES))
        return if (!flipped || raw == TapAction.MENU) raw else if (raw == TapAction.NEXT) TapAction.PREV else TapAction.NEXT
    }
}

object ImageFilters {
    /** Colour matrix for brightness (-1..1), contrast (0.5..2), grayscale and invert. */
    fun matrix(s: MangaSettings): ColorMatrix? {
        if (s.brightness == 0f && s.contrast == 1f && !s.grayscale && !s.invert) return null
        val m = ColorMatrix()
        if (s.grayscale) m.setToSaturation(0f)
        val c = s.contrast
        val t = (1f - c) * 128f + s.brightness * 255f
        val contrast = ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
        m.timesAssign(contrast)
        if (s.invert) m.timesAssign(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        return m
    }
}

/** Removes uniform white or black borders around a scanned page. */
class CropBordersTransformation(private val threshold: Int = 28) : Transformation() {
    override val cacheKey: String = "crop-borders-$threshold"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 32 || h < 32) return input
        val step = maxOf(1, minOf(w, h) / 300)
        val ref = input.getPixel(0, 0)
        fun differs(c: Int): Boolean {
            val dr = ((c shr 16) and 0xFF) - ((ref shr 16) and 0xFF)
            val dg = ((c shr 8) and 0xFF) - ((ref shr 8) and 0xFF)
            val db = (c and 0xFF) - (ref and 0xFF)
            return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db) > threshold * 3
        }
        fun rowInk(y: Int) = (0 until w step step).any { differs(input.getPixel(it, y)) }
        fun colInk(x: Int) = (0 until h step step).any { differs(input.getPixel(x, it)) }
        val top = (0 until h step step).firstOrNull { rowInk(it) } ?: 0
        val bottom = (h - 1 downTo 0 step step).firstOrNull { rowInk(it) } ?: (h - 1)
        val left = (0 until w step step).firstOrNull { colInk(it) } ?: 0
        val right = (w - 1 downTo 0 step step).firstOrNull { colInk(it) } ?: (w - 1)
        val cw = right - left + 1
        val ch = bottom - top + 1
        if (cw < w / 3 || ch < h / 3 || (cw >= w - 2 && ch >= h - 2)) return input
        return Bitmap.createBitmap(input, left, top, cw, ch)
    }
}
