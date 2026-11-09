package app.yomu.core.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SAMPLE = 48
private const val NONE = Int.MIN_VALUE

// Caches the miss too (NONE), so covers without a usable colour aren't decoded again.
private val cache = LruCache<Any, Int>(64)

/**
 * A vibrant dominant colour of the cover. Null while loading; [fallback] (e.g. the generated placeholder's colour) when
 * there is no cover or it has no usable colour.
 */
@Composable
fun rememberCoverColor(model: Any?, fallback: Color? = null): Color? {
    val context = LocalContext.current
    val hidden = LocalHideCovers.current
    val state = produceState<Color?>(null, model, hidden, fallback) {
        if (model == null || hidden) { value = fallback; return@produceState }
        val cached = cache[model]
        val argb = cached ?: withContext(Dispatchers.Default) {
            val request = ImageRequest.Builder(context).data(model).size(Size(SAMPLE, SAMPLE)).allowHardware(false).build()
            val bitmap = ((SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image as? BitmapImage)?.bitmap
            (bitmap?.let(::vibrant) ?: NONE).also { cache.put(model, it) }
        }
        value = if (argb == NONE) fallback else Color(argb)
    }
    return state.value
}

/** A soft vertical wash of the cover [color] fading to transparent; the colour animates in. No-op for null. */
@Composable
fun Modifier.coverTint(color: Color?): Modifier {
    // The app's own theme decides, not the system one: the app may be forced dark or light.
    val alpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.35f else 0.25f
    val animated by animateColorAsState(color?.copy(alpha = alpha) ?: Color.Transparent, label = "coverTint")
    return this.background(Brush.verticalGradient(listOf(animated, Color.Transparent)))
}

/** Buckets pixels by hue and picks the heaviest bucket, weighting saturated mid-lightness pixels. */
private fun vibrant(src: Bitmap): Int {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
    val weight = FloatArray(12)
    val sums = Array(12) { FloatArray(3) }
    val hsv = FloatArray(3)
    for (p in pixels) {
        if ((p ushr 24) < 200) continue
        android.graphics.Color.colorToHSV(p, hsv)
        val s = hsv[1]
        val v = hsv[2]
        if (s < 0.2f || v < 0.25f || v > 0.95f) continue
        val bucket = (hsv[0] / 30f).toInt().coerceIn(0, 11)
        val wgt = s * (1f - kotlin.math.abs(v - 0.65f))
        weight[bucket] += wgt
        sums[bucket][0] += hsv[0] * wgt
        sums[bucket][1] += s * wgt
        sums[bucket][2] += v * wgt
    }
    val best = weight.indices.maxByOrNull { weight[it] } ?: return NONE
    if (weight[best] <= 0f) return NONE
    val n = weight[best]
    return android.graphics.Color.HSVToColor(floatArrayOf(sums[best][0] / n, (sums[best][1] / n).coerceIn(0.45f, 0.9f), (sums[best][2] / n).coerceIn(0.5f, 0.85f)))
}
