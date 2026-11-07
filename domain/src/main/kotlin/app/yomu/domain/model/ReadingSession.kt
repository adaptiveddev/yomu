package app.yomu.domain.model

/** One continuous stretch of reading: a book ([bookId]) or an online title ([catalogItemId]). */
data class ReadingSession(
    val id: Long = 0,
    val bookId: Long? = null,
    val catalogItemId: Long? = null,
    val startedAt: Long,
    val durationMs: Long,
    val pages: Int = 0,
    val chars: Long = 0,
) {
    val endedAt: Long get() = startedAt + durationMs

    /** "b:<id>" for books, "c:<id>" for online titles, null for sessions without a title. */
    val titleKey: String? get() = bookId?.let { "b:$it" } ?: catalogItemId?.let { "c:$it" }
}
