package app.yomu.data.backup

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The conflicts screen survives process death: the records must round-trip and stay bounded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncProblemsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store get() = SyncProblemStore(context)

    @After fun cleanup() { store.clear() }

    @Test fun roundTripAndDismissal() {
        assertTrue(store.load().isEmpty())
        val problem = FolderSyncProblem(FolderSyncProblem.Kind.LEGACY, "Старая папка", "no portable identity").record(123L)
        assertEquals("folder_legacy", problem.kind)
        assertEquals("Старая папка", problem.subject)
        store.save(listOf(problem, AnnotationSyncProblem(AnnotationSyncProblem.Kind.NO_PROOF, "no proof").record(456L)))
        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals("annotation_no_proof", loaded[1].kind)
        assertEquals(123L, loaded[0].at)
        assertEquals("no portable identity", loaded[0].detail)

        store.save(loaded.filterNot { it.id == problem.id })
        assertEquals(listOf("annotation_no_proof"), store.load().map { it.kind })
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test fun listAndTextStayBounded() {
        val many = (1..80L).map { SyncProblemInfo(id = "error#$it", kind = "error", subject = "s".repeat(2000), detail = "d".repeat(2000), at = it) }
        store.save(many)
        val loaded = store.load()
        assertEquals(SyncProblemStoreLimits, loaded.size)
        assertTrue(loaded.all { it.subject.length <= 500 && it.detail.length <= 500 })
        assertEquals(80L, loaded.last().at)
    }

    @Test fun corruptRecordIsIgnored() {
        File(context.filesDir, "sync").apply { mkdirs() }
        File(context.filesDir, "sync/problems.json").writeText("{broken")
        assertTrue(store.load().isEmpty())
        // And writing over the corruption works.
        store.save(listOf(SyncProblemInfo("error", "error", "", "later", 1L)))
        assertEquals("later", store.load().single().detail)
    }
}

private const val SyncProblemStoreLimits = 50
