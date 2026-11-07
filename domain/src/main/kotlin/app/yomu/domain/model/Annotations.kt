package app.yomu.domain.model

enum class HighlightColor(val argb: Long) {
    YELLOW(0xFFFFE066), GREEN(0xFF9BE08F), BLUE(0xFF8EC9FF), PINK(0xFFFF9EC4), PURPLE(0xFFC9A7FF);

    val cssHex: String get() = "#%06X".format(argb and 0xFFFFFF)
}

enum class HighlightStyle { HIGHLIGHT, UNDERLINE, STRIKETHROUGH }

data class Highlight(
    val id: Long = 0,
    val bookId: Long,
    val locator: Locator,
    val text: String,
    val color: HighlightColor = HighlightColor.YELLOW,
    val style: HighlightStyle = HighlightStyle.HIGHLIGHT,
    val isQuote: Boolean = false,
    val isFavorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

data class Note(
    val id: Long = 0,
    val bookId: Long,
    val highlightId: Long? = null,
    val locator: Locator? = null,
    val text: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val locator: Locator,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Everything annotated in one book, used for screens and export. */
data class BookAnnotations(
    val bookTitle: String,
    val authors: List<String>,
    val highlights: List<Highlight>,
    val notes: List<Note>,
    val bookmarks: List<Bookmark>,
)
