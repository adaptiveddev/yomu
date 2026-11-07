package app.yomu.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SharedBookRevisionTest {
    private val id = "a".repeat(40) + ":10"
    private val file = BookFileEntry(id, "b".repeat(64), 10, "book.txt")
    private val book = SyncBook(id, "Book", "{}", .5f, 1, "READING", listOf("B", "A"))
    private val note = SyncNote(id, "{}", "note", "", 1, 1)
    private val link = TrackLink(LinkOwner(bookHash = id), 2, 12, "Book", "https://example.org")
    private val state = SyncState(version = 2, books = listOf(book), notes = listOf(note))
    private fun capture(s: SyncState = state, l: List<TrackLink> = listOf(link), f: BookFileEntry = file) = SharedBookRevision.capture(id, s, l, file = f)

    @Test fun changingBookContentMetadataOrLinkChangesTheAppropriateRevision() {
        val original = capture()
        assertNotEquals(original.file, capture(f = file.copy(sha256 = "c".repeat(64))).file)
        assertNotEquals(original.metadata, capture(s = state.copy(notes = listOf(note.copy(text = "new")))).metadata)
        assertNotEquals(original.metadata, capture(s = state.copy(books = listOf(book.copy(progress = .7f)))).metadata)
        assertNotEquals(original.trackingLinks, capture(l = listOf(link.copy(remoteId = 13))).trackingLinks)
        assertNotEquals(original.metadata, capture(s = state.copy(deleted = mapOf(SyncKeys.note(id, 99) to 2L))).metadata)
    }

    @Test fun unrelatedChangesAndRecordOrderDoNotAffectThisBooksRevision() {
        val original = capture()
        val unrelated = SyncBook("other", "Other", null, 0f, 0, "NEW")
        val otherLink = TrackLink(LinkOwner(bookHash = "other"), 2, 99, "Other", "")
        val equivalent = state.copy(updatedAt = 999, device = "elsewhere", books = listOf(unrelated, book.copy(tabs = listOf("A", "B"))))
        assertEquals(original, capture(s = equivalent, l = listOf(otherLink, link)))
    }

    @Test fun missingOrMismatchedInputsFailClosed() {
        assertTrue(runCatching { SharedBookRevision.capture(id, state, listOf(link)) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { capture(s = state.copy(books = emptyList())) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { capture(f = file.copy(bookHash = "wrong")) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { capture(l = listOf(link, link)) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { capture(s = state.copy(deleted = mapOf("n|broken" to 1))) }.exceptionOrNull() is IOException)
    }
}
