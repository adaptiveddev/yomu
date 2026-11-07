package app.yomu.feature.downloads

import app.yomu.domain.model.DownloadItem
import app.yomu.domain.model.DownloadKind
import app.yomu.domain.model.DownloadStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadSectionsTest {
    private fun item(id: Long, status: DownloadStatus, entryId: Long? = null, title: String = "T$id", updatedAt: Long = id) =
        DownloadItem(id = id, kind = if (entryId != null) DownloadKind.CHAPTER else DownloadKind.BOOK, title = title, url = "u$id",
            status = status, entryId = entryId, createdAt = id, updatedAt = updatedAt)

    @Test
    fun `queue keeps the order of the repository and groups chapters by title`() {
        // Repository order: running, queued (priority first), paused, failed, finished.
        val list = listOf(
            item(1, DownloadStatus.RUNNING, entryId = 10),
            item(7, DownloadStatus.QUEUED, entryId = 20),
            item(2, DownloadStatus.QUEUED, entryId = 10),
            item(8, DownloadStatus.QUEUED, entryId = 20),
            item(3, DownloadStatus.QUEUED),
            item(4, DownloadStatus.PAUSED, entryId = 10),
            item(5, DownloadStatus.FAILED, entryId = 10),
            item(6, DownloadStatus.DONE, updatedAt = 100),
            item(9, DownloadStatus.CANCELLED, updatedAt = 200),
        )
        val s = DownloadSections.of(list)
        assertEquals(listOf(1L), s.running.map { it.id })
        assertEquals(listOf(7L, 2L, 8L, 3L, 4L), s.queued.map { it.id })
        assertEquals(listOf(5L), s.failed.map { it.id })
        assertEquals(listOf(9L, 6L), s.finished.map { it.id })

        val groups = s.queueGroups
        assertEquals(listOf<Long?>(20, 10, null), groups.map { it.entryId })
        assertEquals(listOf(7L, 8L), groups[0].items.map { it.id })
        assertEquals(listOf(2L, 4L), groups[1].items.map { it.id })
        assertEquals(listOf(3L), groups[2].items.map { it.id })
        assertTrue(s.hasActive)
        assertTrue(s.hasPending)
    }

    @Test
    fun `paused and failed downloads offer resume`() {
        val s = DownloadSections.of(listOf(item(1, DownloadStatus.PAUSED, entryId = 1), item(2, DownloadStatus.FAILED, entryId = 1)))
        assertFalse(s.hasActive)
        assertTrue(s.hasPaused)
        assertFalse(DownloadSections.of(emptyList()).hasPending)
        assertTrue(DownloadSections.of(emptyList()).isEmpty)
    }

    @Test
    fun `chapter rows drop the title prefix inside a group`() {
        val d = item(1, DownloadStatus.QUEUED, entryId = 1, title = "One Piece — Chapter 1000")
        assertEquals("Chapter 1000", DownloadSections.shortTitle(d, "One Piece"))
        assertEquals("One Piece — Chapter 1000", DownloadSections.shortTitle(d, "Other"))
        assertEquals("One Piece — Chapter 1000", DownloadSections.shortTitle(d, null))
        assertEquals("One Piece — Chapter 1000", DownloadSections.shortTitle(d, ""))
    }
}
