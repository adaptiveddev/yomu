package app.yomu.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Application-lifetime scope for operations that must finish even when the screen that started them is closed. */
class AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    /** Runs [block] to completion in this scope; cancelling the caller only stops the waiting. */
    suspend fun <T> detached(block: suspend () -> T): T = async { block() }.await()
}
