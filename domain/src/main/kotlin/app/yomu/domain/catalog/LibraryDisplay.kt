package app.yomu.domain.catalog

import app.yomu.domain.model.CatalogEntry
import kotlinx.serialization.Serializable

/** Sort of the online library (titles added from sources). */
@Serializable
enum class LibrarySortMode {
    LAST_UPDATE, TITLE, UNREAD, LAST_READ, DATE_ADDED, CHAPTERS;

    /** Direction picked when the mode is selected: A→Z for titles, the largest or latest value first otherwise. */
    val defaultDescending: Boolean get() = this != TITLE
}

/** Order of the source list (pinned sources always come first). */
@Serializable
enum class SourceSortMode { RECENT, NAME, LANGUAGE }

/** Sort and filters of the online library tab. */
@Serializable
data class LibraryDisplay(
    val sort: LibrarySortMode = LibrarySortMode.LAST_UPDATE,
    val descending: Boolean = true,
    val unreadOnly: Boolean = false,
    val downloadedOnly: Boolean = false,
    val completedOnly: Boolean = false,
) {
    val isFiltered: Boolean get() = unreadOnly || downloadedOnly || completedOnly

    fun apply(entries: List<CatalogEntry>): List<CatalogEntry> {
        var list = entries
        if (unreadOnly) list = list.filter { it.unread > 0 }
        if (downloadedOnly) list = list.filter { it.downloaded > 0 }
        if (completedOnly) list = list.filter { it.status == STATUS_COMPLETED }
        val byTitle = compareBy(NaturalOrder) { e: CatalogEntry -> e.title }
        val primary: Comparator<CatalogEntry> = when (sort) {
            LibrarySortMode.TITLE -> byTitle
            LibrarySortMode.LAST_UPDATE -> compareBy { it.lastUpdateAt }
            LibrarySortMode.UNREAD -> compareBy { it.unread }
            LibrarySortMode.LAST_READ -> compareBy { it.lastReadAt }
            LibrarySortMode.DATE_ADDED -> compareBy { it.addedAt }
            LibrarySortMode.CHAPTERS -> compareBy { it.chapterCount }
        }
        // Ties are always A→Z, whatever the direction of the main key.
        return list.sortedWith((if (descending) primary.reversed() else primary).then(byTitle))
    }

    companion object {
        /** SManga.COMPLETED of Mihon/Tachiyomi sources. */
        const val STATUS_COMPLETED = 2
    }
}

/** Case-insensitive order that compares digit runs as numbers: "Vol. 2" before "Vol. 10". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}
