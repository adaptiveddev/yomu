package app.yomu.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Value being changed by an edge swipe: screen brightness (0..1) or the warm filter (0..[WARM_MAX]). */
internal data class EdgeAdjust(val brightness: Boolean, val value: Float)

internal const val WARM_MAX = 0.8f
private const val EDGE_FRACTION = 0.12f

/**
 * Vertical drag that starts in the left (brightness) or right (warm filter) edge strip. Changes are consumed only
 * once the drag is clearly vertical, so taps and horizontal page swipes keep reaching the page.
 */
internal fun Modifier.edgeAdjust(
    enabled: Boolean,
    startValue: (brightness: Boolean) -> Float,
    onChange: (EdgeAdjust) -> Unit,
    onEnd: (EdgeAdjust) -> Unit,
): Modifier = if (!enabled) this else composed {
    val start by rememberUpdatedState(startValue)
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    val slop = LocalViewConfiguration.current.touchSlop
    pointerInput(slop) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val brightness = when {
                down.position.x < size.width * EDGE_FRACTION -> true
                down.position.x > size.width * (1f - EDGE_FRACTION) -> false
                else -> return@awaitEachGesture
            }
            val range = if (brightness) 1f else WARM_MAX
            var total = Offset.Zero
            var anchorY = 0f
            var from = 0f
            var active = false
            var last: EdgeAdjust? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                if (!active && event.changes.size > 1) break
                total += c.positionChange()
                if (!active) {
                    if (abs(total.y) > slop && abs(total.y) > abs(total.x) * 1.5f) {
                        active = true
                        anchorY = total.y
                        from = start(brightness)
                    } else if (abs(total.x) > slop) break
                }
                if (active) {
                    c.consume()
                    val v = (from - (total.y - anchorY) / (size.height * 0.6f) * range)
                        .coerceIn(if (brightness) 0.01f else 0f, range)
                    last = EdgeAdjust(brightness, v)
                    change(last)
                }
            }
            if (active) last?.let(end)
        }
    }
}

@Composable
internal fun EdgeAdjustPill(edge: EdgeAdjust?, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible && edge != null, modifier, enter = fadeIn(tween(120)), exit = fadeOut(tween(300))) {
        if (edge == null) return@AnimatedVisibility
        val percent = if (edge.brightness) edge.value else edge.value / WARM_MAX
        Row(
            Modifier.background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (edge.brightness) Icons.Filled.BrightnessMedium else Icons.Filled.WbTwilight,
                stringResource(if (edge.brightness) R.string.rs_brightness_pill else R.string.rs_warm_pill),
                tint = MaterialTheme.colorScheme.inverseOnSurface,
            )
            Text(
                "${(percent * 100).toInt()}%",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
