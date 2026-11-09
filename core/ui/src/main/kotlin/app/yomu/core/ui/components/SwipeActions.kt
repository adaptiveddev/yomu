package app.yomu.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.yomu.core.ui.theme.Dimens
import app.yomu.core.ui.theme.YomuTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One swipe action. [removesItem] actions leave the row to the list (it disappears once the data changes);
 * otherwise the row snaps back right after [onTrigger].
 */
class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val container: Color,
    val onContainer: Color,
    val removesItem: Boolean = false,
    val onTrigger: () -> Unit,
) {
    companion object {
        @Composable
        fun destructive(label: String, onTrigger: () -> Unit) = SwipeAction(
            Icons.Default.Delete, label,
            MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
            removesItem = true, onTrigger = onTrigger,
        )

        @Composable
        fun neutral(icon: ImageVector, label: String, onTrigger: () -> Unit) = SwipeAction(
            icon, label,
            MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
            onTrigger = onTrigger,
        )
    }
}

/** Swipe-to-act wrapper for list rows. [start] is revealed by swiping towards the end, [end] the opposite; RTL is handled by the material component. */
@Composable
fun SwipeActionsBox(
    modifier: Modifier = Modifier,
    start: SwipeAction? = null,
    end: SwipeAction? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (start == null && end == null) {
        Box(modifier) { content() }
        return
    }
    val state = rememberSwipeToDismissBoxState()
    val haptic = rememberHaptic()
    val scope = rememberCoroutineScope()
    val currentStart by rememberUpdatedState(start)
    val currentEnd by rememberUpdatedState(end)

    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptic.tick()
    }

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = enabled && start != null,
        enableDismissFromEndToStart = enabled && end != null,
        onDismiss = { value ->
            val action = if (value == SwipeToDismissBoxValue.StartToEnd) currentStart else currentEnd
            action?.onTrigger()
            scope.launch {
                // A removed row leaves composition (cancelling this); a failed removal snaps back.
                if (action?.removesItem == true) delay(600)
                state.reset()
            }
        },
        backgroundContent = {
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> start
                SwipeToDismissBoxValue.EndToStart -> end
                else -> null
            }
            if (action != null) {
                val armed = state.targetValue != SwipeToDismissBoxValue.Settled
                val scale by animateFloatAsState(if (armed) 1.25f else 1f, label = "swipeIconScale")
                Box(
                    Modifier.fillMaxSize().padding(vertical = Dimens.xs).clip(RoundedCornerShape(Dimens.m)).background(action.container)
                        .padding(horizontal = Dimens.l),
                    contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    Icon(
                        action.icon,
                        action.label,
                        Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                        tint = action.onContainer,
                    )
                }
            }
        },
        content = { Box(Modifier.background(MaterialTheme.colorScheme.background)) { content() } },
    )
}

@PreviewLightDark
@Composable
private fun SwipeActionsBoxPreview() = YomuTheme {
    Column {
        SwipeActionsBox(
            start = SwipeAction.neutral(Icons.Default.Refresh, "Retry") {},
            end = SwipeAction.destructive("Delete") {},
        ) {
            ListItem(headlineContent = { Text("Swipe me") }, supportingContent = { Text("Both directions") })
        }
    }
}
