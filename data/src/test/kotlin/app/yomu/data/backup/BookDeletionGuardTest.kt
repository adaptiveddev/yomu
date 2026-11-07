package app.yomu.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BookDeletionGuardTest {
    private val before = BookDeletionGuard.SharedRevision("file-a", "notes-and-progress-a", "links-a")
    private val baseline = BookDeletionGuard.Baseline(before, "local-settings-a")
    private val unchanged = BookDeletionGuard.LocalRevision(before, "local-settings-a", false)

    @Test fun localDeletionRequiresAnUnchangedKnownRemoteRevision() {
        assertEquals(BookDeletionGuard.Decision.PUBLISH, BookDeletionGuard.propose(true, false, before, before))
        assertEquals(BookDeletionGuard.Decision.IGNORE, BookDeletionGuard.propose(false, false, before, before))
        assertEquals(BookDeletionGuard.Decision.IGNORE, BookDeletionGuard.propose(true, true, before, before))
        assertEquals(BookDeletionGuard.Decision.IGNORE, BookDeletionGuard.propose(true, false, null, before))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.propose(true, false, before, null))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.propose(true, false, before, before.copy(file = "new")))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.propose(true, false, before, before.copy(metadata = "new")))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.propose(true, false, before, before.copy(trackingLinks = "new")))
    }

    @Test fun incomingDeletionDoesNotDiscardNewOrUnknownData() {
        assertEquals(BookDeletionGuard.Decision.IGNORE, BookDeletionGuard.receive(false, before, before, baseline, unchanged))
        assertEquals(BookDeletionGuard.Decision.ALREADY_ABSENT, BookDeletionGuard.receive(true, before, before, baseline, null))
        assertEquals(BookDeletionGuard.Decision.APPLY, BookDeletionGuard.receive(true, before, before, baseline, unchanged))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.receive(true, before, before, null, unchanged))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.receive(true, before, before.copy(file = "new"), baseline, unchanged))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.receive(true, before, before, baseline, unchanged.copy(shared = before.copy(file = "new"))))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.receive(true, before, before, baseline, unchanged.copy(shared = before.copy(metadata = "new"))))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.receive(true, before, before, baseline, unchanged.copy(shared = before.copy(trackingLinks = "new"))))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.receive(true, before, before, baseline, unchanged.copy(privateState = "new")))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.receive(true, before, before, baseline, unchanged.copy(hasPendingTrackerChanges = true)))
        assertEquals(BookDeletionGuard.Decision.CONFLICT, BookDeletionGuard.receive(true, before, before.copy(metadata = "new"), baseline, null))
    }
}
