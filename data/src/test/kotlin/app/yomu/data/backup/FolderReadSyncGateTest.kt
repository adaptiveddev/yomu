package app.yomu.data.backup

import app.yomu.core.database.YomuDatabase
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderReadSyncGateTest {
    private val db = YomuDatabase.build(RuntimeEnvironment.getApplication(), inMemory = true)
    @After fun close() = db.close()

    @Test fun newReaderWaitsForActualCleanupAndNeverOverlapsSync() = runBlocking {
        val first = FolderReadSyncGate.reader(db)
        val next = async { FolderReadSyncGate.readerAfterClose(db, 2_000) }
        delay(50)
        assertFalse(next.isCompleted)
        assertTrue(runCatching { FolderReadSyncGate.sync(db) }.exceptionOrNull() is IOException)
        first.close()
        val second = next.await()
        assertTrue(runCatching { FolderReadSyncGate.sync(db) }.exceptionOrNull() is IOException)
        second.close()
        val sync = FolderReadSyncGate.sync(db)
        assertTrue(runCatching { FolderReadSyncGate.readerAfterClose(db, 200) }.exceptionOrNull() is IOException)
        sync.close()
        FolderReadSyncGate.readerAfterClose(db, 200).close()
    }
    @Test fun cancelledWaiterDoesNotTakeOrReleaseLease() = runBlocking {
        val first = FolderReadSyncGate.reader(db)
        val waiting = async { FolderReadSyncGate.readerAfterClose(db, 2_000) }
        delay(50)
        waiting.cancel()
        runCatching { waiting.await() }
        assertTrue(runCatching { FolderReadSyncGate.sync(db) }.exceptionOrNull() is IOException)
        first.close()
        FolderReadSyncGate.sync(db).close()
    }

    @Test fun timeoutDoesNotReleaseAnotherReader() = runBlocking {
        val first = FolderReadSyncGate.reader(db)
        assertTrue(runCatching { FolderReadSyncGate.readerAfterClose(db, 50) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { FolderReadSyncGate.sync(db) }.exceptionOrNull() is IOException)
        first.close()
        FolderReadSyncGate.sync(db).close()
    }
}
