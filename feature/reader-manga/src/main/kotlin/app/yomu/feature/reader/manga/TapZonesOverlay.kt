package app.yomu.feature.reader.manga

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.yomu.domain.model.TapZoneLayout
import kotlin.math.roundToInt

/** Colours of the zones; fixed (not theme) colours because they are drawn over pages. */
internal val PrevZoneColor = Color(0xFFE5793F)
internal val NextZoneColor = Color(0xFF5468E0)

/** Samples [TapZones.action] on a grid, so the drawing always matches what a tap does. */
private class ZoneGrid(val cols: Int, val rows: Int, layout: TapZoneLayout, inverted: Boolean, rtl: Boolean, sideFraction: Float = 0.4f) {
    val cells: Array<TapAction> = Array(cols * rows) { i ->
        TapZones.action(layout, inverted, rtl, (i % cols + 0.5f) / cols, (i / cols + 0.5f) / rows, sideFraction)
    }

    operator fun get(c: Int, r: Int) = cells[r * cols + c]

    /** One label position per connected zone (the cell of the zone nearest to its centre), as fractions. */
    fun labels(): List<Triple<TapAction, Float, Float>> {
        val seen = BooleanArray(cells.size)
        val out = mutableListOf<Triple<TapAction, Float, Float>>()
        for (start in cells.indices) {
            if (seen[start]) continue
            val action = cells[start]
            val component = mutableListOf<Int>()
            val queue = ArrayDeque(listOf(start))
            seen[start] = true
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                component += i
                val c = i % cols
                val r = i / cols
                listOf(c - 1 to r, c + 1 to r, c to r - 1, c to r + 1).forEach { (nc, nr) ->
                    if (nc in 0 until cols && nr in 0 until rows) {
                        val j = nr * cols + nc
                        if (!seen[j] && cells[j] == action) {
                            seen[j] = true
                            queue += j
                        }
                    }
                }
            }
            if (component.size < cells.size / 50) continue
            val cx = component.sumOf { it % cols }.toFloat() / component.size
            val cy = component.sumOf { it / cols }.toFloat() / component.size
            val best = component.minBy { val dx = it % cols - cx; val dy = it / cols - cy; dx * dx + dy * dy }
            out += Triple(action, (best % cols + 0.5f) / cols, (best / cols + 0.5f) / rows)
        }
        return out
    }
}

/** Full screen hint over the pages: which part of the screen goes back, forward or opens the menu. */
@Composable
fun TapZonesOverlay(
    layout: TapZoneLayout,
    inverted: Boolean,
    rtl: Boolean,
    modifier: Modifier = Modifier,
    sideFraction: Float = 0.4f,
    label: @Composable (TapAction) -> String = {
        stringResource(
            when (it) {
                TapAction.PREV -> R.string.manga_tap_prev
                TapAction.NEXT -> R.string.manga_tap_next
                TapAction.MENU -> R.string.manga_tap_menu
            },
        )
    },
) {
    val grid = remember(layout, inverted, rtl, sideFraction) { ZoneGrid(40, 60, layout, inverted, rtl, sideFraction) }
    val labels = remember(grid) { grid.labels() }
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val cw = size.width / grid.cols
            val ch = size.height / grid.rows
            for (r in 0 until grid.rows) {
                var c = 0
                while (c < grid.cols) {
                    val action = grid[c, r]
                    var end = c
                    while (end + 1 < grid.cols && grid[end + 1, r] == action) end++
                    val color = when (action) {
                        TapAction.PREV -> PrevZoneColor.copy(alpha = 0.38f)
                        TapAction.NEXT -> NextZoneColor.copy(alpha = 0.38f)
                        TapAction.MENU -> Color.Black.copy(alpha = 0.12f)
                    }
                    drawRect(color, Offset(c * cw, r * ch), Size((end - c + 1) * cw + 0.5f, ch + 0.5f))
                    c = end + 1
                }
            }
        }
        labels.forEach { (action, fx, fy) ->
            Text(
                label(action),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.centerAt(fx, fy)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** Miniature of a tap zone layout for the settings tiles. */
@Composable
fun TapZonesDiagram(layout: TapZoneLayout, inverted: Boolean, rtl: Boolean, modifier: Modifier = Modifier, sideFraction: Float = 0.4f) {
    val grid = remember(layout, inverted, rtl, sideFraction) { ZoneGrid(12, 18, layout, inverted, rtl, sideFraction) }
    val frame = LocalContentColor.current
    Canvas(modifier.size(width = 20.dp, height = 30.dp)) {
        val cw = size.width / grid.cols
        val ch = size.height / grid.rows
        for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
            val color = when (grid[c, r]) {
                TapAction.PREV -> PrevZoneColor
                TapAction.NEXT -> NextZoneColor
                TapAction.MENU -> null
            } ?: continue
            drawRect(color, Offset(c * cw, r * ch), Size(cw + 0.5f, ch + 0.5f))
        }
        drawRoundRect(frame, style = Stroke(1.5.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()))
    }
}

/** Places the element with its centre at fractions [fx], [fy] of the parent, kept inside the parent. */
private fun Modifier.centerAt(fx: Float, fy: Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    layout(w, h) {
        val x = (fx * w - p.width / 2f).roundToInt().coerceIn(0, (w - p.width).coerceAtLeast(0))
        val y = (fy * h - p.height / 2f).roundToInt().coerceIn(0, (h - p.height).coerceAtLeast(0))
        p.place(x, y)
    }
}
