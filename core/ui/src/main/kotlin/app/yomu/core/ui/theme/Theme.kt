package app.yomu.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Accent palettes, given as a hue and a saturation. [YOMU] is the app's own palette since 1.5.0: soft lavender on
 * near-black graphite. [CLASSIC] is the indigo ink and sepia palette of 1.0–1.4. [CUSTOM] takes the colour chosen in
 * the settings ([ThemeConfig.customColor]).
 */
enum class Accent(val hue: Float, val saturation: Float) {
    YOMU(251f, 0.42f),
    PURPLE(276f, 0.50f),
    INDIGO(232f, 0.52f),
    BLUE(208f, 0.58f),
    TEAL(174f, 0.45f),
    GREEN(130f, 0.38f),
    ORANGE(30f, 0.62f),
    RED(2f, 0.58f),
    PINK(330f, 0.52f),
    CLASSIC(233f, 0.48f),
    CUSTOM(251f, 0.42f),
}

@Immutable
data class ThemeConfig(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colours from the wallpaper (Android 12+). Off by default: Yomu has its own palette. */
    val dynamicColor: Boolean = false,
    val amoled: Boolean = false,
    val accent: Accent = Accent.YOMU,
    /** ARGB colour of [Accent.CUSTOM]. */
    val customColor: Long = DEFAULT_CUSTOM_COLOR,
)

const val DEFAULT_CUSTOM_COLOR: Long = 0xFF8F7FD6

/** Hue (0–360) and saturation (0–1) of an ARGB colour. */
fun hueSaturation(argb: Long): Pair<Float, Float> {
    val c = Color(argb)
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    val d = max - min
    val l = (max + min) / 2f
    if (d < 1e-4f) return 0f to 0f
    val s = d / (1f - kotlin.math.abs(2f * l - 1f))
    val h = when (max) {
        c.red -> ((c.green - c.blue) / d).mod(6f)
        c.green -> (c.blue - c.red) / d + 2f
        else -> (c.red - c.green) / d + 4f
    } * 60f
    return h.mod(360f) to s.coerceIn(0f, 1f)
}

private fun hsl(h: Float, s: Float, l: Float): Color = Color.hsl(h.mod(360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))

/** The colour at lightness [l] or darker, until white text on it (and it as text on light surfaces) reads well. */
private fun onWhite(h: Float, s: Float, l: Float, min: Double = 5.4): Color {
    var x = l
    var c = hsl(h, s, x)
    while (contrastRatio(c, Color.White) < min && x > 0.1f) {
        x -= 0.02f
        c = hsl(h, s, x)
    }
    return c
}

/**
 * Pastel accent on graphite, after the reference the palette was drawn from: a light, slightly muted accent with dark
 * text on it (buttons, the selected navigation item, highlighted cards), neutral near-black surfaces with a trace of
 * the accent hue, and a tertiary colour a quarter turn away for small highlights. Text pairs keep WCAG AA contrast.
 */
fun accentScheme(hue: Float, saturation: Float, dark: Boolean, amoled: Boolean = false): ColorScheme {
    val h = hue
    val s = saturation.coerceIn(0.18f, 0.72f)
    val t = h + 75f
    return if (dark) {
        val bg = if (amoled) Color.Black else hsl(h, 0.05f, 0.063f)
        darkColorScheme(
            primary = hsl(h, s, 0.77f), onPrimary = hsl(h, 0.5f, 0.13f),
            primaryContainer = hsl(h, s, 0.84f), onPrimaryContainer = hsl(h, 0.5f, 0.12f),
            inversePrimary = hsl(h, s * 0.8f, 0.40f),
            secondary = hsl(h, 0.20f, 0.79f), onSecondary = hsl(h, 0.25f, 0.16f),
            secondaryContainer = hsl(h, 0.17f, 0.25f), onSecondaryContainer = hsl(h, 0.40f, 0.91f),
            tertiary = hsl(t, 0.45f, 0.78f), onTertiary = hsl(t, 0.5f, 0.15f),
            tertiaryContainer = hsl(t, 0.22f, 0.26f), onTertiaryContainer = hsl(t, 0.5f, 0.91f),
            background = bg, onBackground = hsl(h, 0.10f, 0.93f),
            surface = bg, onSurface = hsl(h, 0.10f, 0.93f),
            surfaceVariant = if (amoled) hsl(h, 0.05f, 0.14f) else hsl(h, 0.06f, 0.19f), onSurfaceVariant = hsl(h, 0.08f, 0.76f),
            surfaceTint = hsl(h, s, 0.77f),
            inverseSurface = hsl(h, 0.10f, 0.90f), inverseOnSurface = hsl(h, 0.06f, 0.16f),
            surfaceBright = hsl(h, 0.05f, 0.22f), surfaceDim = bg,
            surfaceContainerLowest = if (amoled) Color.Black else hsl(h, 0.04f, 0.045f),
            surfaceContainerLow = hsl(h, 0.04f, if (amoled) 0.045f else 0.095f),
            surfaceContainer = hsl(h, 0.045f, if (amoled) 0.07f else 0.12f),
            surfaceContainerHigh = hsl(h, 0.05f, if (amoled) 0.105f else 0.155f),
            surfaceContainerHighest = hsl(h, 0.055f, if (amoled) 0.14f else 0.19f),
            outline = hsl(h, 0.06f, 0.56f), outlineVariant = hsl(h, 0.06f, if (amoled) 0.20f else 0.26f),
        )
    } else {
        lightColorScheme(
            primary = onWhite(h, s, 0.40f), onPrimary = Color.White,
            primaryContainer = hsl(h, s + 0.1f, 0.89f), onPrimaryContainer = hsl(h, 0.5f, 0.14f),
            inversePrimary = hsl(h, s, 0.80f),
            secondary = hsl(h, 0.14f, 0.38f), onSecondary = Color.White,
            secondaryContainer = hsl(h, 0.26f, 0.90f), onSecondaryContainer = hsl(h, 0.30f, 0.14f),
            tertiary = onWhite(t, 0.38f, 0.38f), onTertiary = Color.White,
            tertiaryContainer = hsl(t, 0.50f, 0.90f), onTertiaryContainer = hsl(t, 0.50f, 0.14f),
            background = hsl(h, 0.14f, 0.972f), onBackground = hsl(h, 0.10f, 0.11f),
            surface = hsl(h, 0.14f, 0.972f), onSurface = hsl(h, 0.10f, 0.11f),
            surfaceVariant = hsl(h, 0.12f, 0.90f), onSurfaceVariant = hsl(h, 0.08f, 0.30f),
            surfaceTint = onWhite(h, s, 0.40f),
            inverseSurface = hsl(h, 0.06f, 0.18f), inverseOnSurface = hsl(h, 0.10f, 0.94f),
            surfaceBright = hsl(h, 0.14f, 0.972f), surfaceDim = hsl(h, 0.10f, 0.86f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsl(h, 0.14f, 0.955f), surfaceContainer = hsl(h, 0.13f, 0.94f),
            surfaceContainerHigh = hsl(h, 0.12f, 0.925f), surfaceContainerHighest = hsl(h, 0.12f, 0.905f),
            outline = hsl(h, 0.05f, 0.47f), outlineVariant = hsl(h, 0.08f, 0.80f),
        )
    }
}

/**
 * The palette of 1.0–1.4: indigo ink (ai-iro) on warm paper, a sepia secondary for quiet containers and a persimmon
 * tertiary for highlights. All pairs keep WCAG AA contrast.
 */
fun classicScheme(dark: Boolean, amoled: Boolean = false): ColorScheme = if (!dark) {
    lightColorScheme(
        primary = Color(0xFF3B4BA6), onPrimary = Color.White,
        primaryContainer = Color(0xFFDFE1FF), onPrimaryContainer = Color(0xFF101C63),
        inversePrimary = Color(0xFFBAC3FF),
        secondary = Color(0xFF6D5D46), onSecondary = Color.White,
        secondaryContainer = Color(0xFFF3E4CC), onSecondaryContainer = Color(0xFF261A08),
        tertiary = Color(0xFF9C4524), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDBCD), onTertiaryContainer = Color(0xFF390C00),
        background = Color(0xFFFCF9F4), onBackground = Color(0xFF1D1B18),
        surface = Color(0xFFFCF9F4), onSurface = Color(0xFF1D1B18),
        surfaceVariant = Color(0xFFE8E1D7), onSurfaceVariant = Color(0xFF4A463F),
        surfaceTint = Color(0xFF3B4BA6),
        inverseSurface = Color(0xFF32302C), inverseOnSurface = Color(0xFFF5F0E9),
        surfaceBright = Color(0xFFFCF9F4), surfaceDim = Color(0xFFDDD9D2),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F3ED),
        surfaceContainer = Color(0xFFF1EDE6), surfaceContainerHigh = Color(0xFFEBE7E0),
        surfaceContainerHighest = Color(0xFFE5E1DA),
        outline = Color(0xFF7C766D), outlineVariant = Color(0xFFCDC6BB),
    )
} else {
    darkColorScheme(
        primary = Color(0xFFBAC3FF), onPrimary = Color(0xFF1F2D75),
        primaryContainer = Color(0xFF36448D), onPrimaryContainer = Color(0xFFDFE1FF),
        inversePrimary = Color(0xFF3B4BA6),
        secondary = Color(0xFFD9C5A8), onSecondary = Color(0xFF3C2F1B),
        secondaryContainer = Color(0xFF4F4430), onSecondaryContainer = Color(0xFFF3E4CC),
        tertiary = Color(0xFFFFB597), onTertiary = Color(0xFF5A1C03),
        tertiaryContainer = Color(0xFF7C2F12), onTertiaryContainer = Color(0xFFFFDBCD),
        background = Color(0xFF121317), onBackground = Color(0xFFE5E2DC),
        surface = Color(0xFF121317), onSurface = Color(0xFFE5E2DC),
        surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC8C5CF),
        surfaceTint = Color(0xFFBAC3FF),
        inverseSurface = Color(0xFFE5E2DC), inverseOnSurface = Color(0xFF31302D),
        surfaceBright = Color(0xFF38393E), surfaceDim = Color(0xFF121317),
        surfaceContainerLowest = Color(0xFF0D0E12), surfaceContainerLow = Color(0xFF1A1B20),
        surfaceContainer = Color(0xFF1E1F24), surfaceContainerHigh = Color(0xFF292A2F),
        surfaceContainerHighest = Color(0xFF34343A),
        outline = Color(0xFF918F99), outlineVariant = Color(0xFF46464F),
    ).let { if (amoled) it.amoled() else it }
}

/** Scheme of an accent (without wallpaper colours). */
fun schemeFor(accent: Accent, dark: Boolean, amoled: Boolean = false, customColor: Long = DEFAULT_CUSTOM_COLOR): ColorScheme = when (accent) {
    Accent.CLASSIC -> classicScheme(dark, amoled)
    Accent.CUSTOM -> hueSaturation(customColor).let { (h, s) -> accentScheme(h, s, dark, amoled) }
    else -> accentScheme(accent.hue, accent.saturation, dark, amoled)
}

private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A), surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818), surfaceContainerHighest = Color(0xFF202020),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun YomuTheme(config: ThemeConfig = ThemeConfig(), forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val dark = forceDark ?: isDark(config.mode)
    val context = LocalContext.current
    val scheme = remember(config, dark) {
        when {
            config.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).let { if (dark && config.amoled) it.amoled() else it }
            else -> schemeFor(config.accent, dark, config.amoled, config.customColor)
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}

/** True when the current scheme is dark (by its background). */
@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.4f

/**
 * Clean sans-serif for the interface (titles in semibold, like the reference), a book serif for the large display
 * figures (counters, hero numbers) and for book text rendered by Compose.
 */
private val DisplayFamily = FontFamily.Serif

val AppTypography: Typography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = DisplayFamily),
        displayMedium = t.displayMedium.copy(fontFamily = DisplayFamily),
        displaySmall = t.displaySmall.copy(fontFamily = DisplayFamily),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Separate type scale for book text rendered by Compose (reflow, quotes, cards). */
object BookTypography {
    val body = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 28.sp)
    val quote = TextStyle(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 30.sp)
    val heading = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 32.sp)
}

/** Relative luminance contrast ratio (WCAG). */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.copy(alpha = 1f).luminance() + 0.05
    val lb = b.copy(alpha = 1f).luminance() + 0.05
    return if (la > lb) la / lb else lb / la
}
