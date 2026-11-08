package app.yomu.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FolderMetadataSnapshotTest {
    private val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
    private fun page(path: String, hash: Char) = FolderPage(path, hash.toString().repeat(64), 1)
    private val old = FolderEntry(id, "Manga", listOf(page("2.jpg", 'a'), page("3.jpg", 'b')))
    private val new = old.copy(pages = listOf(page("1.jpg", 'c')) + old.pages)
    private val state = SyncState(books = listOf(SyncBook(id, "Manga", "{\"chapter\":0}", 0f, 12, "READING")))
    private val snapshot = FolderMetadataSnapshot(state = state, revisions = listOf(old))

    @Test fun revisionAndMetadataRoundTripAndTranslateTogether() {
        val read = FolderMetadataSnapshotCodec.decode(FolderMetadataSnapshotCodec.encode(snapshot))
        val translated = FolderMetadataSnapshotCodec.translate(read, listOf(new))
        assertEquals(new, translated.revisions.single())
        assertTrue(translated.state.books.single().locator!!.contains("\"chapter\":1"))
        assertEquals(.5f, translated.state.books.single().progress, .001f)
        assertEquals(snapshot, read)
    }
    @Test fun unqualifiedOrDuplicateRevisionFailsClosed() {
        assertTrue(runCatching { FolderMetadataSnapshotCodec.encode(snapshot.copy(revisions = emptyList())) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { FolderMetadataSnapshotCodec.encode(snapshot.copy(revisions = listOf(old, old))) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { FolderMetadataSnapshotCodec.translate(snapshot, emptyList()) }.exceptionOrNull() is IOException)
    }
    @Test fun removedSavedPageResetsProgressInsteadOfAborting() {
        val removed = old.copy(pages = listOf(old.pages[1]))
        val translated = FolderMetadataSnapshotCodec.translate(snapshot, listOf(removed))
        // The reading position on the removed page restarts the book; no record is dropped.
        assertEquals(removed, translated.revisions.single())
        assertTrue(translated.state.books.single().locator!!.contains("\"chapter\":0"))
        assertEquals(old, snapshot.revisions.single())
        assertTrue(snapshot.state.books.single().locator!!.contains("\"chapter\":0"))
    }
    @Test fun missingBookRecordButPresentAnnotationStillNeedsRevision() {
        val note = SyncNote(id, "{\"chapter\":0}", "note", "", 1, 1)
        val noteOnly = FolderMetadataSnapshot(state = SyncState(notes = listOf(note)), revisions = emptyList())
        assertTrue(runCatching { FolderMetadataSnapshotCodec.encode(noteOnly) }.exceptionOrNull() is IOException)
    }
    @Test fun duplicateRecordsAndUnknownVersionsFailClosed() {
        val duplicate = snapshot.copy(state = state.copy(books = state.books + state.books))
        assertTrue(runCatching { FolderMetadataSnapshotCodec.encode(duplicate) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { FolderMetadataSnapshotCodec.encode(snapshot.copy(version = 4)) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { FolderMetadataSnapshotCodec.encode(snapshot.copy(state = state.copy(version = 99))) }.exceptionOrNull() is IOException)
    }

    @Test fun olderMetadataReaderRefusesRevisionedEnvelopeRatherThanErasingIt() {
        val bytes = FolderMetadataSnapshotCodec.encode(snapshot)
        assertTrue(runCatching { SyncManager.decodeRemote(bytes) }.exceptionOrNull() is IOException)
        assertEquals(3, FolderMetadataSnapshotCodec.decode(bytes).version)
    }

    @Test fun legacyReaderCanSeeOrdinaryRecordsRatherThanAnEmptyEnvelope() {
        val ordinary = SyncBook("ordinary", "Novel", null, 0f, 0, "NEW")
        val bytes = FolderMetadataSnapshotCodec.encode(snapshot.copy(state = state.copy(books = state.books + ordinary)))
        val oldReader = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val legacyView = oldReader.decodeFromString(SyncState.serializer(), bytes.decodeToString())
        assertEquals(setOf(id, "ordinary"), legacyView.books.map { it.hash }.toSet())
        // Even if a legacy writer strips the revision fields and rewrites version 2,
        // the modern manager recognises the folder positions as unqualified.
        val stripped = oldReader.encodeToString(SyncState.serializer(), legacyView.copy(version = 2)).toByteArray()
        val doc = SyncManager.decodeDocument(stripped)
        assertNull(doc.folderSnapshot)
        assertEquals(setOf(id), FolderMetadataSnapshotCodec.folderHashes(doc.state))
    }

    @Test fun readsEarlierUnreleasedNestedEnvelope() {
        val nested = kotlinx.serialization.json.Json { encodeDefaults = true }
            .encodeToString(FolderMetadataSnapshot.serializer(), snapshot).toByteArray()
        assertEquals(snapshot, FolderMetadataSnapshotCodec.decode(nested))
    }

    @Test fun flattenedEnvelopeMustContainValidStateVersionAndRevision() {
        val valid = FolderMetadataSnapshotCodec.encode(snapshot).decodeToString()
        val withoutRevision = valid.replace(Regex("\"folderRevisions\":\\[.*\\]"), "\"unexpected\":[]")
        assertTrue(runCatching { FolderMetadataSnapshotCodec.decode(withoutRevision.toByteArray()) }.exceptionOrNull() is IOException)
    }

}
