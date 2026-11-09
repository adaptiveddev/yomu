package app.yomu.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import app.yomu.core.ui.theme.accentScheme
import app.yomu.core.ui.theme.hueSaturation

/**
 * The current scheme re-tinted with the hue of [seed] (a cover colour from [rememberCoverColor]): background, surfaces
 * and accent follow the cover with the same contrast rules as the accent palettes, so text stays readable. Null or
 * grey seeds keep the app's scheme. Colours animate when the cover arrives.
 */
@Composable
fun CoverTheme(seed: Color?, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.4f
    val amoled = base.background == Color.Black
    val target = remember(seed, base, dark) {
        val (hue, sat) = seed?.let { hueSaturation(it.toArgb().toLong() and 0xFFFFFFFFL) } ?: (0f to 0f)
        if (seed == null || sat < 0.12f) base else accentScheme(hue, sat.coerceIn(0.25f, 0.7f), dark, amoled)
    }
    MaterialTheme(colorScheme = animated(target), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

@Composable
private fun animated(s: ColorScheme): ColorScheme {
    @Composable fun a(c: Color) = animateColorAsState(c, tween(400), label = "coverTheme").value
    return s.copy(
        primary = a(s.primary), onPrimary = a(s.onPrimary),
        primaryContainer = a(s.primaryContainer), onPrimaryContainer = a(s.onPrimaryContainer),
        secondary = a(s.secondary), onSecondary = a(s.onSecondary),
        secondaryContainer = a(s.secondaryContainer), onSecondaryContainer = a(s.onSecondaryContainer),
        tertiary = a(s.tertiary), tertiaryContainer = a(s.tertiaryContainer),
        background = a(s.background), onBackground = a(s.onBackground),
        surface = a(s.surface), onSurface = a(s.onSurface),
        surfaceVariant = a(s.surfaceVariant), onSurfaceVariant = a(s.onSurfaceVariant),
        surfaceContainerLowest = a(s.surfaceContainerLowest), surfaceContainerLow = a(s.surfaceContainerLow),
        surfaceContainer = a(s.surfaceContainer), surfaceContainerHigh = a(s.surfaceContainerHigh),
        surfaceContainerHighest = a(s.surfaceContainerHighest),
        outline = a(s.outline), outlineVariant = a(s.outlineVariant),
    )
}
