package app.yomu.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderMergeTest {
    private val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
    private fun entry(vararg pages: String) = FolderEntry(id, "Manga", pages.map { FolderPage(it, "a".repeat(64), 1) })
    @Test fun mergesOneSidedAdditionsAndRemovals() {
        val old = entry("1.jpg", "2.jpg")
        val updated = entry("0.jpg", "1.jpg")
        assertEquals(FolderMerge.Decision.UPLOAD, FolderMerge.decide(old, updated, old))
        assertEquals(FolderMerge.Decision.DOWNLOAD, FolderMerge.decide(old, old, updated))
        assertEquals(FolderMerge.Decision.SAME, FolderMerge.decide(old, updated, updated))
    }
    @Test fun concurrentChangesAndMissingKnownRemoteFailClosed() {
        val old = entry("1.jpg")
        assertEquals(FolderMerge.Decision.CONFLICT, FolderMerge.decide(old, entry("2.jpg"), entry("3.jpg")))
        assertEquals(FolderMerge.Decision.CONFLICT, FolderMerge.decide(old, old, null))
        assertEquals(FolderMerge.Decision.CONFLICT, FolderMerge.decide(old, null, entry("2.jpg")))
        assertEquals(FolderMerge.Decision.LOCALLY_MISSING, FolderMerge.decide(old, null, old))
    }
    @Test fun newEntriesAndUnrelatedIdsAreHandledSafely() {
        val e = entry("1.jpg")
        assertEquals(FolderMerge.Decision.UPLOAD, FolderMerge.decide(null, e, null))
        assertEquals(FolderMerge.Decision.DOWNLOAD, FolderMerge.decide(null, null, e))
        assertEquals(FolderMerge.Decision.CONFLICT, FolderMerge.decide(null, e, entry("2.jpg")))
        assertEquals(FolderMerge.Decision.CONFLICT, FolderMerge.decide(null, e, e.copy(id = "other")))
    }
}
