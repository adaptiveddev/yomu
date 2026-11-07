package app.yomu.core.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.flow.StateFlow

/** A launcher icon the user can pick (Settings → Appearance). [background] and [foreground] are 108 dp adaptive-icon layers. */
@Immutable
data class LauncherIcon(val id: String, @StringRes val label: Int, @DrawableRes val background: Int, @DrawableRes val foreground: Int)

/**
 * The app's launcher icons. Each one is an activity-alias of the main activity: picking one enables its alias and
 * disables the others once the app leaves the screen. Implemented in the app module, which owns the manifest.
 */
interface LauncherIcons {
    val icons: List<LauncherIcon>

    /** [LauncherIcon.id] of the picked icon (the launcher shows it from the next time the app is left). */
    val current: StateFlow<String>

    fun select(id: String)
}

val LocalLauncherIcons = staticCompositionLocalOf<LauncherIcons?> { null }

/** An adaptive icon drawn the way a launcher shows it: the middle 72 of its 108 dp, cut to [shape]. */
@Composable
fun LauncherIconImage(icon: LauncherIcon, modifier: Modifier = Modifier, shape: Shape = CircleShape) {
    Box(modifier.clip(shape)) {
        Image(painterResource(icon.background), null, Modifier.matchParentSize().scale(LAYER_SCALE))
        Image(painterResource(icon.foreground), null, Modifier.matchParentSize().scale(LAYER_SCALE))
    }
}

/** 108 / 72: the outer 18 dp of each layer only exist for the launcher's masks and parallax. */
private const val LAYER_SCALE = 1.5f
