package app.yomu.data.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Recent behaviour of a source, collected from catalog calls (kept in memory for the running session). */
data class SourceHealth(
    /** Failures in a row since the last success. */
    val failures: Int = 0,
    val lastError: Throwable? = null,
    val lastFailureAt: Long = 0,
    val lastSuccessAt: Long = 0,
    /** Duration of the last successful call. */
    val lastLatencyMs: Long = 0,
) {
    /** Failed repeatedly and has not answered since: shown as "not responding" in the source list and global search. */
    val unstable: Boolean get() = failures >= UNSTABLE_AFTER

    /** Answered but slowly (the last call took longer than [SLOW_MS]). */
    val slow: Boolean get() = failures == 0 && lastLatencyMs >= SLOW_MS

    companion object {
        const val UNSTABLE_AFTER = 2
        const val SLOW_MS = 8_000L
    }
}

/** Tracks successes and failures per source id. Device-offline errors are not the source's fault and are not counted. */
class SourceHealthTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow<Map<Long, SourceHealth>>(emptyMap())
    val state: StateFlow<Map<Long, SourceHealth>> = _state.asStateFlow()

    operator fun get(sourceId: Long): SourceHealth = _state.value[sourceId] ?: SourceHealth()

    fun success(sourceId: Long, latencyMs: Long) {
        _state.update { it + (sourceId to SourceHealth(lastSuccessAt = clock(), lastLatencyMs = latencyMs)) }
    }

    fun failure(sourceId: Long, error: Throwable) {
        _state.update { m ->
            val old = m[sourceId] ?: SourceHealth()
            m + (sourceId to old.copy(failures = old.failures + 1, lastError = error, lastFailureAt = clock()))
        }
    }

    fun reset(sourceId: Long) {
        _state.update { it - sourceId }
    }
}
