package app.yomu.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FolderRemotePositionsTest {
    private val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
    private fun page(path: String, hash: Char) = FolderPage(path, hash.toString().repeat(64), 1)
    private val before = FolderEntry(id, "Manga", listOf(page("2.jpg", 'a'), page("3.jpg", 'b')))
    private val after = FolderEntry(id, "Manga", listOf(page("1.jpg", 'c'), page("2.jpg", 'a'), page("3.jpg", 'b')))
    private val state = SyncState(
        books = listOf(SyncBook(id, "Manga", "{\"chapter\":1,\"other\":42}", 1f, 10, "READING"),
            SyncBook("other", "Other", "{\"chapter\":0}", 0f, 10, "READING")),
        highlights = listOf(SyncHighlight(id, "{\"chapter\":0}", 0, "text", "yellow", "highlight", false, "", null, 1, 1)),
        notes = listOf(SyncNote(id, "{\"chapter\":0}", "note", "", 2, 2)),
        bookmarks = listOf(SyncBookmark(id, "{\"chapter\":0}", "mark", 0f, 3)),
    )
    @Test fun translatesAllRemoteLocationsWithoutChangingUnrelatedRecords() {
        val translated = FolderRemotePositions.translate(state, before, after)
        assertTrue(translated.books[0].locator!!.contains("\"chapter\":2"))
        assertTrue(translated.books[0].locator!!.contains("\"other\":42"))
        assertEquals(1f, translated.books[0].progress, .001f)
        assertEquals(state.books[1], translated.books[1])
        assertEquals(1, translated.highlights.single().chapter)
        assertTrue(translated.highlights.single().locator.contains("\"chapter\":1"))
        assertTrue(translated.notes.single().locator!!.contains("\"chapter\":1"))
        assertTrue(translated.bookmarks.single().locator.contains("\"chapter\":1"))
        assertEquals(.5f, translated.bookmarks.single().progress, .001f)
        assertTrue(state.books[0].locator!!.contains("\"chapter\":1"))
    }
    @Test fun missingReferencedPageResetsProgressAndKeepsRecords() {
        val shortened = before.copy(pages = listOf(before.pages[0]))
        val translated = FolderRemotePositions.translate(state, before, shortened)
        // The reading position on the removed page restarts; every record survives, none is dropped.
        assertTrue(translated.books[0].locator!!.contains("\"chapter\":0"))
        assertEquals(0f, translated.books[0].progress, 0.0001f)
        assertEquals(2, translated.books.size)
        assertEquals(1, translated.highlights.size)
        assertEquals(1, translated.notes.size)
        assertEquals(1, translated.bookmarks.size)
        // The input state is untouched (pure transform).
        assertTrue(state.books[0].locator!!.contains("\"chapter\":1"))
    }
    @Test fun wrongIdentityAndMalformedPositionAreRejected() {
        assertTrue(runCatching { FolderRemotePositions.translate(state, before, after.copy(id = "folder:11111111-1111-4111-8111-111111111111")) }.exceptionOrNull() is IOException)
        val broken = state.copy(notes = listOf(SyncNote(id, "{\"chapter\":\"bad\"}", "note", "", 2, 2)))
        assertTrue(runCatching { FolderRemotePositions.translate(broken, before, after) }.exceptionOrNull() is IOException)
    }
}
