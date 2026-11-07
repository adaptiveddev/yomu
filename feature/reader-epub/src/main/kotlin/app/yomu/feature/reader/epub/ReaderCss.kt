package app.yomu.feature.reader.epub

import app.yomu.domain.model.ReaderTheme
import app.yomu.domain.model.TextAlignment
import app.yomu.domain.model.TextReaderSettings
import java.io.File

/** Builds the user stylesheet appended to every chapter. */
object ReaderCss {
    val BUILT_IN_FONTS = listOf("serif", "sans-serif", "monospace", "georgia", "literata", "roboto")

    private fun family(name: String): String = when (name) {
        "serif" -> "\"Noto Serif\", Georgia, serif"
        "sans-serif" -> "Roboto, \"Noto Sans\", sans-serif"
        "monospace" -> "\"Roboto Mono\", \"Droid Sans Mono\", monospace"
        "georgia" -> "Georgia, serif"
        "literata" -> "Literata, \"Noto Serif\", serif"
        "roboto" -> "Roboto, sans-serif"
        else -> "\"yomu-${name.hashCode()}\", serif"
    }

    private fun hex(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)

    fun isDark(s: TextReaderSettings): Boolean {
        val bg = s.background
        val r = (bg shr 16) and 0xFF
        val g = (bg shr 8) and 0xFF
        val b = bg and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) < 128
    }

    fun build(s: TextReaderSettings, customFonts: List<File>, accent: Long): String = buildString {
        customFonts.forEach { f ->
            append("@font-face{font-family:\"yomu-${f.name.hashCode()}\";src:url('/__fonts/${f.name}');}\n")
        }
        val dark = isDark(s)
        append(":root{--yomu-accent:${hex(accent)};--yomu-blend:${if (dark) "screen" else "multiply"};}\n")
        append("html,body{background:${hex(s.background)}!important;color:${hex(s.foreground)}!important;}\n")
        append("body{font-size:${s.fontSizePercent}%!important;line-height:${s.lineHeight}!important;font-weight:${s.fontWeight};")
        append("-webkit-hyphens:${if (s.hyphenation) "auto" else "manual"};hyphens:${if (s.hyphenation) "auto" else "manual"};")
        if (s.letterSpacing != 0f) append("letter-spacing:${s.letterSpacing}em;")
        if (s.wordSpacing != 0f) append("word-spacing:${s.wordSpacing}em;")
        if (!s.publisherStyles || s.fontFamily !in setOf("serif", "")) append("font-family:${family(s.fontFamily)}!important;")
        append("}\n")
        val align = when (s.align) {
            TextAlignment.START -> "start"
            TextAlignment.JUSTIFY -> "justify"
            TextAlignment.CENTER -> "center"
        }
        append("p,li,dd,blockquote{text-align:$align!important;}\n")
        if (!s.publisherStyles) {
            append("p,li,div,span,a,td,th,dd,dt,blockquote,em,strong,i,b,small,big{font-family:inherit!important;line-height:inherit!important;}\n")
            append("p,li,div,span,td,dd,blockquote{font-size:inherit;}\n")
            append("p{margin-top:0!important;margin-bottom:${s.paragraphSpacing}em!important;text-indent:${s.indent}em!important;}\n")
            append("h1,h2,h3,h4,h5,h6{line-height:1.25!important;text-indent:0!important;}\n")
        }
        if (dark || !s.publisherStyles || s.theme != ReaderTheme.LIGHT) {
            append("body *{color:inherit!important;background-color:transparent!important;border-color:currentColor;}\n")
        }
        append("a,a *{color:${hex(accent)}!important;text-decoration:none;}\n")
        if (dark) append("img{filter:brightness(.88);}\n")
        append("::selection{background:${hex(accent)}55;}\n")
    }
}
