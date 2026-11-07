package app.yomu.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FolderIdentityMigrationTest {
    private val old = "dir:content://provider/tree/comics|part"
    private val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
    private val mapping = mapOf(old to id)
    private val highlight = SyncHighlight(old, "{\"chapter\":0}", 0, "text", "yellow", "HIGHLIGHT", false, "", null, 11, 12)
    private val note = SyncNote(old, "{\"chapter\":0}", "note", "", 13, 14)
    private val bookmark = SyncBookmark(old, "{\"chapter\":0}", "mark", 0f, 15)
    private val state = SyncState(books = listOf(SyncBook(old, "Book", "{\"chapter\":0}", 0f, 10, "READING", listOf("Shelf"))),
        highlights = listOf(highlight), notes = listOf(note), bookmarks = listOf(bookmark),
        deleted = mapOf(SyncKeys.note(old, 8) to 9))
    private val base = SyncBase("target", keys = setOf(SyncKeys.highlight(old, 11, "text"), SyncKeys.bookmark(old, 15)),
        tombstones = mapOf(SyncKeys.note(old, 8) to 9), bookTabs = mapOf(old to listOf("Shelf")))
    private val link = TrackLink(LinkOwner(bookHash = old), 2, 123, "Book", "https://example.org/123", 10)

    @Test fun preservesMetadataDeletionsBasesAndTrackerBindings() {
        val migrated = FolderIdentityMigration.metadata(state, base, mapping)
        assertEquals(id, migrated.state.books.single().hash)
        assertEquals(highlight.copy(hash = id), migrated.state.highlights.single())
        assertEquals(note.copy(hash = id), migrated.state.notes.single())
        assertEquals(bookmark.copy(hash = id), migrated.state.bookmarks.single())
        assertEquals(mapOf(SyncKeys.note(id, 8) to 9L), migrated.state.deleted)
        assertEquals(setOf(SyncKeys.highlight(id, 11, "text"), SyncKeys.bookmark(id, 15)), migrated.base!!.keys)
        assertEquals(mapOf(SyncKeys.note(id, 8) to 9L), migrated.base!!.tombstones)
        assertEquals(listOf("Shelf"), migrated.base!!.bookTabs[id])
        assertEquals(link.copy(owner = LinkOwner(bookHash = id)), FolderIdentityMigration.links(listOf(link), mapping).single())
        assertEquals(state, FolderIdentityMigration.metadata(state, base, emptyMap()).state)
        // Replaying the pure transformation does not change an already transformed document.
        assertEquals(migrated, FolderIdentityMigration.metadata(migrated.state, migrated.base, mapping))
    }
    @Test fun refusesToMergeTwoExistingFolderIdentities() {
        val duplicate = state.copy(books = state.books + state.books.single().copy(hash = id, title = "Other"))
        assertTrue(runCatching { FolderIdentityMigration.metadata(duplicate, base, mapping) }.exceptionOrNull() is IOException)
        val other = link.copy(owner = LinkOwner(bookHash = id), service = 3)
        assertTrue(runCatching { FolderIdentityMigration.links(listOf(link, other), mapping) }.exceptionOrNull() is IOException)
    }
    @Test fun rejectsDuplicateDestinationsAndInvalidIds() {
        assertTrue(runCatching { FolderIdentityMigration.metadata(state, base, mapOf(old to "invalid")) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { FolderIdentityMigration.metadata(state, base, mapping + ("dir:another" to id)) }.exceptionOrNull() is IOException)
    }
    @Test fun refusesCollidingTombstonesRatherThanOverwritingEither() {
        val conflict = state.copy(deleted = state.deleted + (SyncKeys.note(id, 8) to 10L))
        assertTrue(runCatching { FolderIdentityMigration.metadata(conflict, base, mapping) }.exceptionOrNull() is IOException)
    }
}
