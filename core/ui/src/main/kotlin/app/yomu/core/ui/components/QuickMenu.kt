package app.yomu.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.yomu.core.ui.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One entry of a long-press menu. */
data class QuickMenuItem(val key: String, val icon: ImageVector, val label: String, val tint: Color? = null, val onClick: () -> Unit)

/** The popup covers the whole window; its content is placed in window coordinates. */
private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

/**
 * Long-press menu (2.0.0) at [anchor] (window coordinates): a [radial] ring of round buttons around the finger, or a
 * list next to it. A tap outside closes it.
 */
@Composable
fun QuickActionMenu(radial: Boolean, anchor: Offset, items: List<QuickMenuItem>, onDismiss: () -> Unit, title: String? = null) {
    if (items.isEmpty()) return
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "quickMenu")
    Popup(popupPositionProvider = WindowOrigin, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = (if (radial) 0.35f else 0.12f) * progress))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            if (radial) RadialMenu(anchor, items, progress, title, onDismiss) else ListMenu(anchor, items, progress, title, onDismiss)
        }
    }
}

@Composable
private fun ListMenu(anchor: Offset, items: List<QuickMenuItem>, progress: Float, title: String?, onDismiss: () -> Unit) {
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    Layout(
        content = {
            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.widthIn(min = 200.dp, max = 300.dp).heightIn(max = 460.dp).alpha(progress).scale(0.9f + 0.1f * progress),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    if (title != null) {
                        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    items.forEach { item ->
                        Surface(onClick = { onDismiss(); item.onClick() }, color = Color.Transparent) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Icon(item.icon, null, tint = item.tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = (anchor.x.roundToInt() - placeable.width / 2).coerceIn(margin, (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin))
            val below = anchor.y.roundToInt() + margin
            val y = if (below + placeable.height <= constraints.maxHeight - margin) below else (anchor.y.roundToInt() - placeable.height - margin).coerceAtLeast(margin)
            placeable.place(x, y)
        }
    }
}

@Composable
private fun RadialMenu(anchor: Offset, items: List<QuickMenuItem>, progress: Float, title: String?, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val n = items.size
    val radiusDp = when {
        n <= 5 -> 92.dp
        n <= 7 -> 106.dp
        else -> 122.dp
    }
    val button = 52.dp
    val labelWidth = 84.dp
    Layout(
        content = {
            Surface(onClick = onDismiss, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp, modifier = Modifier.size(44.dp).alpha(progress)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, androidx.compose.ui.res.stringResource(R.string.close)) }
            }
            items.forEach { item ->
                Column(Modifier.widthIn(max = labelWidth).alpha(progress).scale(0.6f + 0.4f * progress), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = { onDismiss(); item.onClick() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shadowElevation = 8.dp,
                        modifier = Modifier.size(button).semantics { contentDescription = item.label },
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(item.icon, null, tint = item.tint ?: MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        // A translucent colour is not one of the scheme's: its content colour has to be given.
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (title != null) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(max = 280.dp).alpha(progress)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        },
    ) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val placeables = measurables.map { it.measure(loose) }
        val center = placeables.first()
        val itemPlaceables = placeables.subList(1, 1 + n)
        val titlePlaceable = if (title != null) placeables.last() else null
        val radius = with(density) { radiusDp.toPx() } * (0.7f + 0.3f * progress)
        val buttonPx = with(density) { button.toPx() }
        val reach = with(density) { (radiusDp + button / 2 + 28.dp).toPx() }
        // The ring stays on screen: its centre moves away from the edges.
        val cx = anchor.x.coerceIn(reach.coerceAtMost(constraints.maxWidth / 2f), (constraints.maxWidth - reach).coerceAtLeast(constraints.maxWidth / 2f))
        val cy = anchor.y.coerceIn(reach.coerceAtMost(constraints.maxHeight / 2f), (constraints.maxHeight - reach).coerceAtLeast(constraints.maxHeight / 2f))
        layout(constraints.maxWidth, constraints.maxHeight) {
            center.place((cx - center.width / 2f).roundToInt(), (cy - center.height / 2f).roundToInt())
            itemPlaceables.forEachIndexed { i, p ->
                val angle = -PI / 2 + 2 * PI * i / n
                val x = cx + radius * cos(angle).toFloat()
                val y = cy + radius * sin(angle).toFloat()
                // The button's centre is on the ring; its label hangs below it.
                p.place((x - p.width / 2f).roundToInt(), (y - buttonPx / 2f).roundToInt())
            }
            titlePlaceable?.let { p ->
                val top = cy - reach - p.height
                val y = if (top > 0) top else cy + reach
                p.place((cx - p.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0)), y.roundToInt())
            }
        }
    }
}
