package app.yomu.data.backup

import app.yomu.core.database.YomuDatabase
import java.io.Closeable
import java.io.IOException
import java.util.IdentityHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Process-local exclusion between stable-folder readers and sync. Not a cross-process lock. */
internal object FolderReadSyncGate {
    private class State(var readers: Int = 0, var syncing: Boolean = false) {
        val readerFinished = CompletableDeferred<Unit>()
    }
    private val states = IdentityHashMap<YomuDatabase, State>()

    @Synchronized fun reader(db: YomuDatabase): Closeable {
        val state = states.getOrPut(db) { State() }
        if (state.syncing || state.readers > 0) throw IOException("Close the other image-folder reader or wait for synchronization")
        state.readers++
        return lease(db, state, reader = true)
    }

    /** Navigation can destroy the previous reader asynchronously. Wait for its actual
     * cleanup, but never steal its lease or overlap a sync. */
    suspend fun readerAfterClose(db: YomuDatabase, timeoutMs: Long = 3_000): Closeable {
        require(timeoutMs > 0)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val (lease, finished) = synchronized(this) {
                val state = states.getOrPut(db) { State() }
                if (state.syncing) throw IOException("Wait for synchronization before opening an image folder")
                if (state.readers == 0) reader(db) to null else null to state.readerFinished
            }
            // Timeout applies only to waiting, not to returning an acquired resource:
            // cancellation of withTimeout must not discard an already-acquired lease.
            if (lease != null) return lease
            val remaining = deadline - System.nanoTime()
            val millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)
            if (remaining <= 0 || withTimeoutOrNull(millis) { finished!!.await(); true } != true)
                throw IOException("Close the other image-folder reader before opening this one")
        }
    }

    @Synchronized fun sync(db: YomuDatabase): Closeable {
        val state = states.getOrPut(db) { State() }
        if (state.syncing || state.readers > 0) throw IOException("Close the image-folder reader before synchronizing")
        state.syncing = true
        return lease(db, state, reader = false)
    }

    private fun lease(db: YomuDatabase, state: State, reader: Boolean): Closeable {
        var closed = false
        return Closeable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    if (reader) {
                        state.readers--
                        if (state.readers == 0) state.readerFinished.complete(Unit)
                    } else state.syncing = false
                    if (!state.syncing && state.readers == 0) states.remove(db)
                }
            }
        }
    }
}
