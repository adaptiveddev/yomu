package app.yomu.domain.catalog

import app.yomu.domain.model.ChapterInfo
import kotlinx.serialization.Serializable

/** How the chapter list of a title is sorted. */
@Serializable
enum class ChapterSortMode {
    /** Reading order by chapter number (see [ChapterOrder.reading]). */
    NUMBER,

    /** The order of the source list. */
    SOURCE,

    /** Upload date; chapters without a date stay next to their neighbours. */
    UPLOAD_DATE,
}

/** Sort and filters of a title's chapter list; stored per title (and as the default for new titles). */
@Serializable
data class ChapterDisplay(
    val sort: ChapterSortMode = ChapterSortMode.NUMBER,
    /** Latest chapters at the top. */
    val descending: Boolean = true,
    val unreadOnly: Boolean = false,
    val downloadedOnly: Boolean = false,
    val bookmarkedOnly: Boolean = false,
    /** One release per chapter when several groups translated it. */
    val hideDuplicates: Boolean = false,
    val excludedScanlators: Set<String> = emptySet(),
) {
    val isFiltered: Boolean
        get() = unreadOnly || downloadedOnly || bookmarkedOnly || hideDuplicates || excludedScanlators.isNotEmpty()

    /** The chapters shown on the title page, top to bottom. */
    fun apply(chapters: List<ChapterInfo>): List<ChapterInfo> {
        val reading = ChapterOrder.reading(chapters)
        var list = when (sort) {
            ChapterSortMode.NUMBER -> reading
            ChapterSortMode.SOURCE -> ChapterOrder.bySource(chapters)
            ChapterSortMode.UPLOAD_DATE -> byDate(reading)
        }
        if (excludedScanlators.isNotEmpty()) list = list.filter { it.scanlator.isNullOrBlank() || it.scanlator !in excludedScanlators }
        if (hideDuplicates) {
            val kept = ChapterOrder.distinct(ChapterOrder.reading(list)).mapTo(HashSet()) { it.id }
            list = list.filter { it.id in kept }
        }
        if (unreadOnly) list = list.filter { !it.read }
        if (downloadedOnly) list = list.filter { it.downloaded }
        if (bookmarkedOnly) list = list.filter { it.bookmarked }
        return if (descending) list.asReversed().toList() else list
    }

    /** Groups that may be excluded: every scanlator of [chapters], most chapters first. */
    fun scanlatorsOf(chapters: List<ChapterInfo>): List<String> =
        chapters.mapNotNull { it.scanlator?.takeIf { s -> s.isNotBlank() } }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() }).map { it.key }

    private fun byDate(reading: List<ChapterInfo>): List<ChapterInfo> {
        // Chapters without a date take the date of the previous chapter, so they stay where the source put them.
        var last = 0L
        val dates = reading.map { c -> (if (c.uploadDate > 0) c.uploadDate else last).also { last = it } }
        return reading.indices.sortedWith(compareBy<Int> { dates[it] }.thenBy { it }).map { reading[it] }
    }
}
