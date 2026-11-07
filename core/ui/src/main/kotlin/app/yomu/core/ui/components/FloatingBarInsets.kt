package app.yomu.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.yomu.core.ui.theme.Dimens

/**
 * How much of the bottom of a top-level screen the floating navigation bar covers, the system navigation under it
 * included; 0.dp on every other screen and beside a navigation rail.
 *
 * Since 1.8.0 the top-level screens are drawn under the bar, so it really floats over their content instead of sitting
 * on a strip of background: their lists add this as bottom content padding (the last row can still scroll above the
 * bar) and their FABs and snackbars stay above it ([topLevelContentInsets]).
 */
val LocalNavBarInset = compositionLocalOf { 0.dp }

/**
 * Set to true by a top-level screen to slide the floating bar away while it shows its own bottom bar (the actions
 * for the selected books in the library). See [HideNavBarWhile].
 */
val LocalNavBarHidden = staticCompositionLocalOf<MutableState<Boolean>?> { null }

object FloatingNavBarDefaults {
    /** Height of the pill itself. */
    fun pillHeight(showLabels: Boolean): Dp = if (showLabels) 68.dp else Dimens.navBar

    /** Height of the bar over the system navigation: the pill and the space above and below it. */
    fun height(showLabels: Boolean): Dp = pillHeight(showLabels) + Dimens.xs + Dimens.m
}

/** Scaffold insets of a top-level screen: the default ones plus the floating bar, so a FAB and snackbars sit above it. */
@Composable
fun topLevelContentInsets(): WindowInsets = ScaffoldDefaults.contentWindowInsets.add(WindowInsets(bottom = LocalNavBarInset.current))

/**
 * The body of a top-level Scaffold: [padding] on every side but the bottom, where the content runs on under the
 * floating bar. Inside, [LocalNavBarInset] is the bottom room to leave in lists (see [navBarBottomPadding]): the bar,
 * or the screen's own bottom bar while it shows one.
 */
@Composable
fun TopLevelBody(padding: PaddingValues, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val direction = LocalLayoutDirection.current
    CompositionLocalProvider(LocalNavBarInset provides padding.calculateBottomPadding()) {
        Box(
            modifier.padding(start = padding.calculateStartPadding(direction), top = padding.calculateTopPadding(), end = padding.calculateEndPadding(direction)),
            content = content,
        )
    }
}

/** Bottom content padding of a list on a top-level screen: [extra] plus the room the floating bar takes. */
@Composable
fun navBarBottomPadding(extra: Dp = 0.dp): Dp = extra + LocalNavBarInset.current

/** Hides the floating navigation bar while [hidden] is true (and puts it back when the caller leaves the screen). */
@Composable
fun HideNavBarWhile(hidden: Boolean) {
    val state = LocalNavBarHidden.current ?: return
    DisposableEffect(hidden, state) {
        if (hidden) state.value = true
        onDispose { if (hidden) state.value = false }
    }
}
