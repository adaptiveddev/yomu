package app.yomu.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What [FastScroller] needs from a lazy layout, so lists and grids share one implementation. */
class FastScrollTarget internal constructor(
    internal val itemCount: () -> Int,
    internal val firstVisibleIndex: () -> Int,
    internal val visibleCount: () -> Int,
    internal val isScrolling: () -> Boolean,
    internal val scrollToItem: suspend (Int) -> Unit,
)

@Composable
fun LazyListState.asFastScrollTarget(): FastScrollTarget = remember(this) {
    FastScrollTarget(
        { layoutInfo.totalItemsCount }, { firstVisibleItemIndex }, { layoutInfo.visibleItemsInfo.size },
        { isScrollInProgress }, { scrollToItem(it) },
    )
}

@Composable
fun LazyGridState.asFastScrollTarget(): FastScrollTarget = remember(this) {
    FastScrollTarget(
        { layoutInfo.totalItemsCount }, { firstVisibleItemIndex }, { layoutInfo.visibleItemsInfo.size },
        { isScrollInProgress }, { scrollToItem(it) },
    )
}

private val ThumbTouchHeight = 48.dp
private val ThumbTouchWidth = 32.dp
private const val MIN_SCREENS = 3
private const val HIDE_DELAY_MS = 1500L

/**
 * A draggable scroll thumb on the end edge, laid over a lazy list or grid (put it in a Box with the layout).
 * Shown while scrolling or dragging and only when the content is longer than a few screens. While dragging,
 * a bubble shows [label] for the item at the current position (null: no text).
 */
@Composable
fun FastScroller(
    target: FastScrollTarget,
    contentDescription: String,
    modifier: Modifier = Modifier,
    label: (index: Int) -> String?,
) {
    val count = target.itemCount()
    val visible = target.visibleCount()
    val long = visible > 0 && count > visible * MIN_SCREENS
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = target.isScrolling()
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true else { delay(HIDE_DELAY_MS); shown = false }
    }
    val haptic = rememberHaptic()
    val scope = rememberCoroutineScope()
    var trackPx by remember { mutableIntStateOf(0) }
    val thumbPx = with(LocalDensity.current) { ThumbTouchHeight.roundToPx() }
    val range = (trackPx - thumbPx).coerceAtLeast(1)

    val scrollable = (count - visible).coerceAtLeast(1)
    val fraction = if (dragging) dragFraction else (target.firstVisibleIndex().toFloat() / scrollable).coerceIn(0f, 1f)
    val index = (fraction * (count - 1).coerceAtLeast(0)).roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))
    val text = if (dragging) label(index) else null

    var lastText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        if (dragging && text != null && lastText != null && text != lastText) haptic.tick()
        lastText = text
    }

    Box(modifier.fillMaxHeight().onSizeChanged { trackPx = it.height }) {
        AnimatedVisibility(
            visible = long && (shown || dragging),
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Row(
                Modifier.offset { IntOffset(0, (fraction * range).roundToInt()) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (text != null) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 4.dp) {
                        Text(
                            text,
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                        )
                    }
                }
                Box(
                    Modifier
                        .size(ThumbTouchWidth, ThumbTouchHeight)
                        .semantics { this.contentDescription = contentDescription }
                        .pointerInput(count, range) {
                            detectVerticalDragGestures(
                                onDragStart = { dragFraction = fraction; dragging = true },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, dy ->
                                change.consume()
                                dragFraction = (dragFraction + dy / range).coerceIn(0f, 1f)
                                val i = (dragFraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
                                scope.launch { target.scrollToItem(i) }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(if (dragging) 8.dp else 6.dp).height(40.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else 0.7f), CircleShape),
                    )
                }
            }
        }
    }
}

