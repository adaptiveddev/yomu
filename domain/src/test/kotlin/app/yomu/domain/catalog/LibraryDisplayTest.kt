package app.yomu.domain.catalog

import app.yomu.domain.model.CatalogEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryDisplayTest {
    private fun entry(title: String, unread: Int = 0, lastUpdate: Long = 0, lastRead: Long = 0, downloaded: Int = 0, status: Int = 1) =
        CatalogEntry(id = title.hashCode().toLong(), sourceId = 1, url = title, title = title, unread = unread, lastUpdateAt = lastUpdate, lastReadAt = lastRead, downloaded = downloaded, status = status)

    private val entries = listOf(
        entry("Vol 10", unread = 1, lastUpdate = 5, lastRead = 1),
        entry("vol 2", unread = 3, lastUpdate = 5, lastRead = 9, downloaded = 2),
        entry("Alpha", unread = 0, lastUpdate = 1, lastRead = 5, status = LibraryDisplay.STATUS_COMPLETED),
    )

    @Test
    fun `natural order compares numbers and ignores case`() {
        assertTrue(NaturalOrder.compare("vol 2", "Vol 10") < 0)
        assertTrue(NaturalOrder.compare("Chapter 9", "chapter 10") < 0)
        assertEquals(listOf("Alpha", "vol 2", "Vol 10"), listOf("Vol 10", "Alpha", "vol 2").sortedWith(NaturalOrder))
    }

    @Test
    fun `sorts by key with title as tie breaker`() {
        assertEquals(listOf("vol 2", "Vol 10", "Alpha"), LibraryDisplay(LibrarySortMode.LAST_UPDATE).apply(entries).map { it.title })
        assertEquals(listOf("vol 2", "Vol 10", "Alpha"), LibraryDisplay(LibrarySortMode.UNREAD).apply(entries).map { it.title })
        assertEquals(listOf("vol 2", "Alpha", "Vol 10"), LibraryDisplay(LibrarySortMode.LAST_READ).apply(entries).map { it.title })
        assertEquals(listOf("Alpha", "vol 2", "Vol 10"), LibraryDisplay(LibrarySortMode.TITLE, descending = false).apply(entries).map { it.title })
    }

    @Test
    fun `filters`() {
        assertEquals(listOf("vol 2", "Vol 10"), LibraryDisplay(unreadOnly = true).apply(entries).map { it.title })
        assertEquals(listOf("vol 2"), LibraryDisplay(downloadedOnly = true).apply(entries).map { it.title })
        assertEquals(listOf("Alpha"), LibraryDisplay(completedOnly = true).apply(entries).map { it.title })
    }
}
