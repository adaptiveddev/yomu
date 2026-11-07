package app.yomu.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaletteTest {
    private fun schemes(): List<Pair<String, ColorScheme>> = buildList {
        val customs = listOf(DEFAULT_CUSTOM_COLOR, 0xFFFFEB3B, 0xFF00FF00, 0xFF0000FF, 0xFFFF0000, 0xFF808080)
        for (accent in Accent.entries) {
            for ((mode, dark, amoled) in listOf(Triple("light", false, false), Triple("dark", true, false), Triple("amoled", true, true))) {
                if (accent == Accent.CUSTOM) {
                    customs.forEach { c -> add("CUSTOM ${c.toString(16)} $mode" to schemeFor(accent, dark, amoled, c)) }
                } else {
                    add("$accent $mode" to schemeFor(accent, dark, amoled))
                }
            }
        }
    }

    @Test
    fun `text pairs keep WCAG AA contrast in every accent`() {
        val failures = mutableListOf<String>()
        for ((name, s) in schemes()) {
            fun check(pair: String, fg: Color, bg: Color, min: Double = 4.5) {
                val ratio = contrastRatio(fg, bg)
                if (ratio < min) failures += "$name $pair %.2f".format(ratio)
            }
            check("onPrimary/primary", s.onPrimary, s.primary)
            check("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer)
            check("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer)
            check("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer)
            check("onSurface/surface", s.onSurface, s.surface)
            check("onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
            check("onSurface/surfaceContainerHigh", s.onSurface, s.surfaceContainerHigh)
            check("onSurfaceVariant/surfaceContainerHigh", s.onSurfaceVariant, s.surfaceContainerHigh)
            check("primary/surface", s.primary, s.surface, 3.0)
            check("primary/surfaceContainerHigh", s.primary, s.surfaceContainerHigh, 3.0)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the default palette is lavender on graphite`() {
        val dark = schemeFor(Accent.YOMU, dark = true)
        val (hue, saturation) = hueSaturation(dark.primary.toArgb().toLong() and 0xFFFFFFFFL)
        assertTrue(hue in 230f..290f, "hue $hue")
        assertTrue(saturation > 0.2f, "saturation $saturation")
        assertTrue(dark.background.luminance() < 0.02f)
        assertEquals(ThemeMode.SYSTEM, ThemeConfig().mode) // the app's default (DARK) lives in the settings
    }

    @Test
    fun `custom colours keep their hue`() {
        val (hue, _) = hueSaturation(0xFFE91E63)
        val primary = schemeFor(Accent.CUSTOM, dark = true, customColor = 0xFFE91E63).primary
        val (primaryHue, _) = hueSaturation(primary.toArgb().toLong() and 0xFFFFFFFFL)
        assertEquals(hue, primaryHue, 2f)
    }
}
