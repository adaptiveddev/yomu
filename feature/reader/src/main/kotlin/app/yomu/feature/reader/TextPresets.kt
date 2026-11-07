package app.yomu.feature.reader

import androidx.annotation.StringRes
import app.yomu.domain.model.ReaderTheme
import app.yomu.domain.model.TextAlignment
import app.yomu.domain.model.TextReaderSettings

/** Ready-made typography for the text reader and the rules for applying any preset. */
object TextPresets {

    data class BuiltIn(@StringRes val label: Int, val settings: TextReaderSettings)

    val builtIn: List<BuiltIn> = listOf(
        BuiltIn(R.string.rs_preset_classic, TextReaderSettings()),
        BuiltIn(
            R.string.rs_preset_large,
            TextReaderSettings(fontFamily = "sans-serif", fontSizePercent = 135, lineHeight = 1.6f, marginHorizontal = 16, marginVertical = 24,
                paragraphSpacing = 0.6f, indent = 0f, align = TextAlignment.START, fontWeight = 500),
        ),
        BuiltIn(
            R.string.rs_preset_compact,
            TextReaderSettings(fontFamily = "serif", fontSizePercent = 90, lineHeight = 1.3f, marginHorizontal = 12, marginVertical = 16,
                paragraphSpacing = 0.2f, indent = 1f),
        ),
        BuiltIn(
            R.string.rs_preset_airy,
            TextReaderSettings(fontFamily = "literata", fontSizePercent = 105, lineHeight = 1.8f, marginHorizontal = 32, marginVertical = 40,
                paragraphSpacing = 0.8f, indent = 0f, align = TextAlignment.START),
        ),
        BuiltIn(
            R.string.rs_preset_easy,
            TextReaderSettings(fontFamily = "sans-serif", fontSizePercent = 115, lineHeight = 1.7f, marginHorizontal = 24, marginVertical = 32,
                paragraphSpacing = 0.8f, indent = 0f, align = TextAlignment.START, hyphenation = false, letterSpacing = 0.05f, wordSpacing = 0.12f),
        ),
    )

    /** Typography of [preset] over [current]: page mode, writing direction and the other flags stay. */
    fun apply(current: TextReaderSettings, preset: TextReaderSettings, withColors: Boolean = false): TextReaderSettings {
        val typography = current.copy(
            fontFamily = preset.fontFamily,
            fontSizePercent = preset.fontSizePercent,
            lineHeight = preset.lineHeight,
            marginHorizontal = preset.marginHorizontal,
            marginVertical = preset.marginVertical,
            paragraphSpacing = preset.paragraphSpacing,
            indent = preset.indent,
            align = preset.align,
            hyphenation = preset.hyphenation,
            fontWeight = preset.fontWeight,
            letterSpacing = preset.letterSpacing,
            wordSpacing = preset.wordSpacing,
        )
        return if (withColors) typography.copy(theme = preset.theme, customBackground = preset.customBackground, customText = preset.customText) else typography
    }

    /** Whether [current] already looks like [preset] (used to mark the active chip). */
    fun matches(current: TextReaderSettings, preset: TextReaderSettings, withColors: Boolean = false): Boolean =
        apply(current, preset, withColors) == current

    /** Paper and ink colors offered for the custom theme. */
    val backgrounds: List<Long> = listOf(
        0xFFFFFFFF, 0xFFFFF8E7, 0xFFF4ECD8, 0xFFEFE6D2, 0xFFE8F3E8, 0xFFE3EEF7, 0xFFF1E9F7, 0xFFF9E8EA,
        0xFF2B2B2B, 0xFF1F2A36, 0xFF1E2A22, 0xFF3A2F28, 0xFF121212, 0xFF000000,
    )
    val inks: List<Long> = listOf(
        0xFF000000, 0xFF222222, 0xFF3C3C3C, 0xFF5B4636, 0xFF1F3A5F, 0xFF2E4A2E,
        0xFFE6E6E6, 0xFFBDBDBD, 0xFFF4ECD8, 0xFFFFD8A8, 0xFFB8D8B8, 0xFFA8C8E8,
    )

    /** WCAG contrast ratio of two opaque colors. */
    fun contrast(a: Long, b: Long): Double {
        fun lum(c: Long): Double {
            fun ch(v: Long): Double {
                val s = (v and 0xFF) / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(c shr 16) + 0.7152 * ch(c shr 8) + 0.0722 * ch(c)
        }
        val l1 = lum(a)
        val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** Themes offered in the quick row of the settings sheet. */
    val quickThemes = listOf(ReaderTheme.LIGHT, ReaderTheme.SEPIA, ReaderTheme.GRAY, ReaderTheme.DARK, ReaderTheme.BLACK)
}
