package app.yomu.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import app.yomu.core.ui.components.FloatingNavBar
import app.yomu.core.ui.components.LocalReduceMotion
import app.yomu.core.ui.components.NavItem
import app.yomu.core.ui.components.YomuNavRail

/** Material 3 "emphasized" curves: a quick start for elements that leave, a long soft landing for those that arrive. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private const val BAR_ENTER_MS = 380
private const val BAR_EXIT_MS = 200

/**
 * App shell: the content and, depending on [type], a floating navigation bar below it or a rail beside it. Both slots
 * keep their place in the composition when the bar hides (detail screens) or the window changes size, so the
 * navigation host below is never recreated. The content consumes the insets the bar or the rail already covers.
 *
 * The bar itself slides and fades on its own graphics layer: the screens below are measured once per change instead
 * of on every frame of the animation (that relayout of the whole navigation host was what made the old expand/shrink
 * animation stutter).
 *
 * The floating bar (1.8.0) reserves no strip of its own: the content takes the whole height and the bar is drawn over
 * it, so the screens show through around the pill. The top-level screens leave room for it in their lists, FABs and
 * snackbars through [app.yomu.core.ui.components.LocalNavBarInset]. A rail still takes its width beside the content.
 */
@Composable
fun NavShell(
    type: NavigationSuiteType,
    visible: Boolean,
    items: List<NavItem>,
    selected: Int,
    showLabels: Boolean,
    onSelect: (Int) -> Unit,
    onPress: (Int) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val horizontal = type == NavigationSuiteType.NavigationRail || type == NavigationSuiteType.NavigationDrawer
    val reduce = LocalReduceMotion.current
    val shown = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible, reduce) {
        val target = if (visible) 1f else 0f
        when {
            reduce -> shown.snapTo(target)
            visible -> shown.animateTo(target, tween(BAR_ENTER_MS, delayMillis = 40, easing = EmphasizedDecelerate))
            else -> shown.animateTo(target, tween(BAR_EXIT_MS, easing = EmphasizedAccelerate))
        }
    }
    // Composed while visible or still sliding away.
    val onScreen by remember { derivedStateOf { shown.value > 0f } }
    val slide = Modifier.graphicsLayer {
        val p = shown.value
        alpha = p
        if (horizontal) translationX = -(1f - p) * size.width * 0.6f else translationY = (1f - p) * size.height
    }
    val bar: @Composable () -> Unit = {
        if (visible || onScreen) {
            if (horizontal) {
                YomuNavRail(items, selected, onSelect, slide, onPress = onPress)
            } else if (type == NavigationSuiteType.NavigationBar) {
                // A fade from the page colour behind the pill, so lists don't run into it. It has no pointer input:
                // touches above the pill still reach the screen below.
                val bg = MaterialTheme.colorScheme.background
                Box(
                    slide.background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to bg.copy(alpha = 0.82f), 1f to bg))
                        .padding(top = 20.dp),
                ) {
                    FloatingNavBar(items, selected, showLabels, onSelect, onPress = onPress)
                }
            }
        }
    }
    val consumed = when {
        !visible -> WindowInsets(0, 0, 0, 0)
        horizontal -> WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
        type == NavigationSuiteType.NavigationBar -> WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
        else -> WindowInsets(0, 0, 0, 0)
    }
    Layout(
        contents = listOf(bar, { Box(Modifier.consumeWindowInsets(consumed)) { content() } }),
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
    ) { (barMeasurables, contentMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val barPlaceables = barMeasurables.map { it.measure(loose) }
        if (horizontal) {
            val reserved = if (visible) barPlaceables.maxOfOrNull { it.width } ?: 0 else 0
            val contentPlaceables = contentMeasurables.map { it.measure(Constraints.fixed((width - reserved).coerceAtLeast(0), height)) }
            layout(width, height) {
                contentPlaceables.forEach { it.place(reserved, 0) }
                barPlaceables.forEach { it.place(0, 0) }
            }
        } else {
            val contentPlaceables = contentMeasurables.map { it.measure(Constraints.fixed(width, height)) }
            layout(width, height) {
                contentPlaceables.forEach { it.place(0, 0) }
                barPlaceables.forEach { it.place(0, height - it.height) }
            }
        }
    }
}
