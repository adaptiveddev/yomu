package app.yomu.data.source

import app.yomu.core.common.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

/** Timeouts and retries shared by catalog calls, page loading and the downloader. */
object SourceCalls {
    /** Listing, search and details of a source. */
    const val CALL_TIMEOUT_MS = 45_000L

    /** One search request in global search (many sources at once: a slow one must not hold the screen). */
    const val SEARCH_TIMEOUT_MS = 30_000L

    /** Refreshing one title during the update check. */
    const val UPDATE_TIMEOUT_MS = 60_000L

    /**
     * Errors worth another attempt: timeouts, dropped connections, rate limiting (429) and server errors (5xx).
     * Not retried: verification pages (WebView), 4xx answers, broken data and errors of the source code itself.
     */
    fun isTransient(t: Throwable): Boolean {
        if (t is TimeoutCancellationException) return true
        if (t is CancellationException) return false
        return when (val e = AppError.from(t)) {
            is AppError.Timeout, is AppError.NoNetwork -> true
            is AppError.Http -> e.code == 408 || e.code == 425 || e.code == 429 || (e.code in 500..599 && e.code != 501)
            is AppError.Unknown -> generateSequence(t) { it.cause }.take(5).any { it is IOException }
            else -> false
        }
    }

    /** Runs [block] with a deadline; exceeding it is reported as [AppError.Timeout] instead of a cancellation. */
    suspend fun <T> withDeadline(timeoutMs: Long, block: suspend () -> T): T = try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        throw AppError.Timeout(SocketTimeoutException("No answer in ${timeoutMs / 1000} s"))
    }

    /**
     * Runs [block] up to [attempts] times while it fails with a transient error, waiting [baseDelayMs], 2×, 4×…
     * (with a little jitter) between attempts. [retryIf] can veto a retry (for example when the device is offline).
     */
    suspend fun <T> retrying(
        attempts: Int = 2,
        baseDelayMs: Long = 1_000,
        retryIf: (Throwable) -> Boolean = ::isTransient,
        block: suspend (attempt: Int) -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block(attempt)
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException && attempt + 1 < attempts && retryIf(e)) {
                    // Inner withTimeout of the block: treated like any other timeout below.
                } else {
                    throw e
                }
            } catch (e: Throwable) {
                if (attempt + 1 >= attempts || !retryIf(e)) throw e
            }
            delay(backoff(baseDelayMs, attempt))
            attempt++
        }
    }

    fun backoff(baseDelayMs: Long, attempt: Int): Long {
        val base = baseDelayMs shl attempt.coerceIn(0, 6)
        return base + Random.nextLong(0, (base / 4).coerceAtLeast(1))
    }
}
