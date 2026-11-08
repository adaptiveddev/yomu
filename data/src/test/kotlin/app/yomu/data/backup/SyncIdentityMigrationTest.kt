package app.yomu.data.backup

import org.junit.Assert.*
import org.junit.Test

/** The pure identity rewrite: keys follow the new identity, revisions are recomputed, proofs refuse. */
class SyncIdentityMigrationTest {
    private val valid = { id: String -> id.startsWith("book:") }
    private fun book(id: String) = SyncBook(hash = id, title = "t", locator = null, progress = 0f, updatedAt = 1, status = "NEW")
    private fun highlight(id: String, time: Long = 10, text: String = "x") = SyncHighlight(hash = id, locator = "{}", chapter = 0, text = text, color = "yellow", style = "HIGHLIGHT", isQuote = false, tags = "", note = null, createdAt = time, updatedAt = time)

    @Test fun keysAndRevisionsFollowTheNewIdentity() {
        val old = "book:old"
        val new = "book:new"
        val state = SyncState(version = 2, books = listOf(book(old)), highlights = listOf(highlight(old)), deleted = mapOf(SyncKeys.note(old, 5) to 99L))
        val base = SyncBase(
            syncId = "t",
            keys = setOf(SyncKeys.highlight(old, 10, "x"), SyncKeys.note(old, 5), "w|en|word", "c|1|u", "t|tab"),
            annotationRevisions = mapOf(
                SyncKeys.highlight(old, 10, "x") to "0".repeat(64),
                "w|en|word" to "1".repeat(64),
            ),
            privateAnnotationRevisions = mapOf(SyncKeys.highlight(old, 10, "x") to "2".repeat(64)),
        )
        val migrated = SyncIdentityMigration.metadata(state, base, mapOf(old to new), valid, recomputeRevisions = true)
        assertEquals(new, migrated.state.books.single().hash)
        assertEquals(new, migrated.state.highlights.single().hash)
        assertEquals(new, SyncKeys.bookOf(migrated.state.deleted.keys.single()))
        val newKey = SyncKeys.highlight(new, 10, "x")
        assertTrue(newKey in migrated.base!!.keys)
        assertTrue("w|en|word" in migrated.base!!.keys)
        // The recomputed revision matches what the rewritten record hashes to now.
        assertEquals(AnnotationRevisions.highlight(migrated.state.highlights.single()), migrated.base!!.annotationRevisions[newKey])
        // Unrelated records keep their revisions; private fingerprints keep their values.
        assertEquals("1".repeat(64), migrated.base!!.annotationRevisions["w|en|word"])
        assertEquals("2".repeat(64), migrated.base!!.privateAnnotationRevisions[newKey])
    }

    @Test fun deletionProofsRefuseAndMissingRecordsRefuse() {
        val old = "book:old"; val new = "book:new"
        val proofKey = SyncKeys.highlight(old, 10, "x")
        val withProof = SyncState(version = 2, books = listOf(book(old)), deleted = mapOf(proofKey to 5L), annotationDeletionProofs = mapOf(proofKey to "0".repeat(64)))
        try {
            SyncIdentityMigration.metadata(withProof, null, mapOf(old to new), valid, recomputeRevisions = true)
            fail("Deletion proofs must block the migration")
        } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("deletions")) }

        val state = SyncState(version = 2, books = listOf(book(old)))
        val base = SyncBase(syncId = "t", keys = setOf(SyncKeys.highlight(old, 10, "x")), annotationRevisions = mapOf(SyncKeys.highlight(old, 10, "x") to "0".repeat(64)))
        try {
            SyncIdentityMigration.metadata(state, base, mapOf(old to new), valid, recomputeRevisions = true)
            fail("A base revision without its record must block the migration")
        } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("recompute")) }
    }

    @Test fun trackerBindingsFollowAndConflictsRefuse() {
        val old = "book:old"; val new = "book:new"
        val links = listOf(TrackLink(LinkOwner(bookHash = old), service = 1, remoteId = 5, title = "t", url = "u"))
        val migrated = SyncIdentityMigration.links(links, mapOf(old to new), valid)
        assertEquals("b:8:book:new:s:1", migrated.single().key())
        val both = listOf(
            TrackLink(LinkOwner(bookHash = old), service = 1, remoteId = 5, title = "t", url = "u"),
            TrackLink(LinkOwner(bookHash = new), service = 1, remoteId = 6, title = "t", url = "u"),
        )
        try {
            SyncIdentityMigration.links(both, mapOf(old to new), valid)
            fail("Both identities having bindings must refuse")
        } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("bindings")) }
    }
}
