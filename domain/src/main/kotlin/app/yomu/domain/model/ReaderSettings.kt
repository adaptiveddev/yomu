package app.yomu.domain.model

import kotlinx.serialization.Serializable

enum class ReaderTheme(val bg: Long, val fg: Long) {
    LIGHT(0xFFFFFFFF, 0xFF1C1B1F),
    SEPIA(0xFFF4ECD8, 0xFF5B4636),
    GRAY(0xFF4A4A4F, 0xFFE6E6E6),
    DARK(0xFF1E1E1E, 0xFFD9D9D9),
    BLACK(0xFF000000, 0xFFBDBDBD),
    CUSTOM(0xFFFFFFFF, 0xFF000000),
}

/** How paged text moves between pages. */
enum class PageTurnAnimation { SLIDE, FADE, NONE }

enum class TextAlignment { START, JUSTIFY, CENTER }

@Serializable
data class TextReaderSettings(
    val fontFamily: String = "serif",
    val fontSizePercent: Int = 100,
    val lineHeight: Float = 1.5f,
    val marginHorizontal: Int = 20,
    val marginVertical: Int = 28,
    val paragraphSpacing: Float = 0.4f,
    val indent: Float = 1.2f,
    val align: TextAlignment = TextAlignment.JUSTIFY,
    val hyphenation: Boolean = true,
    val fontWeight: Int = 400,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val customBackground: Long = 0xFFFFF8E7,
    val customText: Long = 0xFF222222,
    val paged: Boolean = true,
    val verticalText: Boolean = false,
    val rtl: Boolean = false,
    val publisherStyles: Boolean = false,
    val bilingual: Boolean = false,
    val highlightSavedWords: Boolean = false,
    /** Extra space between letters and between words, in em. */
    val letterSpacing: Float = 0f,
    val wordSpacing: Float = 0f,
    val pageAnimation: PageTurnAnimation = PageTurnAnimation.SLIDE,
    /** Uses [nightTheme] instead of [theme] while the app is in dark mode. */
    val autoNight: Boolean = false,
    val nightTheme: ReaderTheme = ReaderTheme.DARK,
) {
    val background: Long get() = if (theme == ReaderTheme.CUSTOM) customBackground else theme.bg
    val foreground: Long get() = if (theme == ReaderTheme.CUSTOM) customText else theme.fg
}

/** Page order of the image reader. Left to right (like a book) is the default since 1.2.0. */
enum class MangaDirection { LTR, RTL, VERTICAL, WEBTOON }
enum class ScaleType { FIT_SCREEN, FIT_WIDTH, FIT_HEIGHT, ORIGINAL }
enum class TapZoneLayout { L_SHAPE, KINDLE, EDGES, LEFT_RIGHT, DISABLED }

/** Colour around and between manga pages. */
enum class MangaBackground(val argb: Long) { BLACK(0xFF000000), GRAY(0xFF2B2B2E), WHITE(0xFFFFFFFF) }

@Serializable
data class MangaSettings(
    val direction: MangaDirection = MangaDirection.LTR,
    val scale: ScaleType = ScaleType.FIT_SCREEN,
    val dualPageLandscape: Boolean = true,
    val cropBorders: Boolean = false,
    val tapZones: TapZoneLayout = TapZoneLayout.L_SHAPE,
    val invertTapZones: Boolean = false,
    val volumeKeys: Boolean = true,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val grayscale: Boolean = false,
    val invert: Boolean = false,
    val preload: Int = 3,
    val showPageNumber: Boolean = true,
    val background: MangaBackground = MangaBackground.BLACK,
    /** Slide animation when pages are turned by a tap or a key (off = instant turns). */
    val pageAnimation: Boolean = true,
    /** Volume up turns forward instead of back. */
    val invertVolumeKeys: Boolean = false,
    /** Webtoon mode: empty space on both sides of the strip, percent of the screen width (0..30). */
    val webtoonSidePadding: Int = 0,
    /** Webtoon mode: small gap between pages instead of a seamless strip. */
    val webtoonGaps: Boolean = false,
    /** Double tap zooms the webtoon strip; off by default because single taps then wait for the double-tap timeout. */
    val webtoonDoubleTapZoom: Boolean = false,
    /**
     * Shows where to tap for a few seconds when a manga opens. Off by default since 1.8.0: the overlay covered the
     * first page every time. A new name on purpose, so the "on" stored by earlier versions is not carried over (the
     * old key is ignored when the settings are read); the preview button in the settings still shows the zones.
     */
    val showTapZonesOnOpen: Boolean = false,
) {
    val hasImageFilters: Boolean get() = brightness != 0f || contrast != 1f || grayscale || invert
}

enum class PdfMode { PAGED, SCROLL, DUAL }

@Serializable
data class CropMargins(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    val isEmpty: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f
}

@Serializable
data class PdfSettings(
    val mode: PdfMode = PdfMode.SCROLL,
    val night: Boolean = false,
    val autoCrop: Boolean = false,
    val manualCrop: CropMargins = CropMargins(),
    val reflow: Boolean = false,
)

enum class ScreenTimeout(val minutes: Int) { SYSTEM(0), MIN_2(2), MIN_5(5), MIN_10(10), MIN_30(30), ALWAYS(-1) }
enum class ReaderOrientation { AUTO, PORTRAIT, LANDSCAPE, LOCKED }

@Serializable
data class CommonReaderSettings(
    /** -1 = follow system brightness, otherwise 0..1. */
    val brightness: Float = -1f,
    val warmFilter: Float = 0f,
    val screenTimeout: ScreenTimeout = ScreenTimeout.MIN_10,
    val orientation: ReaderOrientation = ReaderOrientation.AUTO,
    val fullscreen: Boolean = true,
    val drawUnderCutout: Boolean = true,
    val autoHideMenuSeconds: Int = 4,
    val tapToTurn: Boolean = true,
    val volumeKeys: Boolean = false,
    val autoScrollSpeed: Int = 30,
    val ttsSpeed: Float = 1f,
    val ttsPitch: Float = 1f,
    val ttsVoice: String? = null,
    /** Thin line at the bottom of text and PDF pages with the page number and progress while the menu is hidden. */
    val footer: Boolean = true,
    val footerClock: Boolean = true,
    val footerChapter: Boolean = true,
    /** Vertical drag on the left screen edge changes brightness, on the right edge the warm filter. */
    val edgeGestures: Boolean = true,
    val textTapZones: TapZoneLayout = TapZoneLayout.LEFT_RIGHT,
)

/** A named set of text settings (built-in or saved by the user). */
@Serializable
data class TextPreset(val name: String, val settings: TextReaderSettings)

/** Settings stored for one particular book; null fields fall back to global defaults. */
@Serializable
data class BookReaderSettings(
    val text: TextReaderSettings? = null,
    val manga: MangaSettings? = null,
    val pdf: PdfSettings? = null,
)

/** Actions of the selection toolbar. Order and visibility are configurable. */
enum class SelectionAction {
    HIGHLIGHT, UNDERLINE, STRIKE, NOTE, QUOTE, MEANING, TRANSLATE, SAVE_WORD, COPY, SHARE, SHARE_IMAGE,
    SEARCH_BOOK, SEARCH_WEB, SPEAK, SELECT_SENTENCE, SELECT_PARAGRAPH, BOOKMARK, CUSTOM,
}

@Serializable
data class SelectionActionConfig(val action: SelectionAction, val enabled: Boolean = true)

@Serializable
data class CustomAction(
    val id: String,
    val label: String,
    /** URL template with {text}, or empty when [packageName] is used. */
    val urlTemplate: String = "",
    val packageName: String = "",
)

enum class WordTapGesture { OFF, SINGLE_TAP, DOUBLE_TAP }

enum class WebSearchEngine(val template: String) {
    GOOGLE("https://www.google.com/search?q={text}"),
    DUCKDUCKGO("https://duckduckgo.com/?q={text}"),
    YANDEX("https://yandex.ru/search/?text={text}"),
    WIKIPEDIA("https://{lang}.wikipedia.org/w/index.php?search={text}"),
    CUSTOM(""),
}
