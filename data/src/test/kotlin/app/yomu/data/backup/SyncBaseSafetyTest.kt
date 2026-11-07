package app.yomu.data.backup

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Exercise the on-disk base without constructing unrelated sync services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncBaseSafetyTest {
    private val context = RuntimeEnvironment.getApplication()
    @After fun cleanup() { SyncManager.clearBase(context) }

    @Test fun missingPreviouslySyncedFileFailsClosed() {
        val base = SyncBase(syncId = "target")
        assertTrue(runCatching { SyncManager.requireRemoteWhenKnown(null, base, "target") }.exceptionOrNull() is java.io.IOException)
        SyncManager.requireRemoteWhenKnown(null, null, "target")
        SyncManager.requireRemoteWhenKnown(null, base, "another")
        SyncManager.requireRemoteWhenKnown(SyncFile("{}".toByteArray(), "tag"), base, "target")
    }

    @Test fun mergeBaseRoundTripsAndRejectsCorruption() {
        SyncManager.clearBase(context)
        assertNull(SyncManager.loadBase(context))
        val expected = SyncBase(syncId = "target", keys = setOf("w|en|word"), tombstones = mapOf("t|old" to 123L))
        SyncManager.saveBase(context, expected)
        assertEquals(expected, SyncManager.loadBase(context))
        File(context.filesDir, "sync/base.json").writeText("{invalid")
        assertTrue(runCatching { SyncManager.loadBase(context) }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun orphanedPendingBaseFailsClosed() {
        SyncManager.clearBase(context)
        File(context.filesDir, "sync/base.json.new").apply { parentFile!!.mkdirs(); writeText("incomplete") }
        assertTrue(runCatching { SyncManager.loadBase(context) }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun restoreClearsAllMergeBasesButLeavesOtherFiles() {
        val base = File(context.filesDir, "sync/base.json").apply { parentFile!!.mkdirs(); writeText("base") }
        val backup = File(base.path + ".bak").apply { writeText("backup") }
        val pending = File(base.path + ".new").apply { writeText("pending") }
        val legacyPending = File(base.path + ".tmp").apply { writeText("old pending") }
        val tracking = File(context.filesDir, "sync-tracking/one.json").apply { parentFile!!.mkdirs(); writeText("tracking") }
        val unrelated = File(context.filesDir, "other-test-file").apply { writeText("keep") }
        try {
            SyncManager.clearBase(context)
            assertFalse(base.exists()); assertFalse(backup.exists()); assertFalse(pending.exists()); assertFalse(legacyPending.exists())
            assertFalse(tracking.exists()); assertTrue(unrelated.exists())
        } finally { unrelated.delete() }
    }
}
