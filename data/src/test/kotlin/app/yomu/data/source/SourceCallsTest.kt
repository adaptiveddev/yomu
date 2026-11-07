package app.yomu.data.source

import app.yomu.core.common.AppError
import app.yomu.source.HttpStatusException
import app.yomu.source.SourceException
import app.yomu.source.WebViewRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SourceCallsTest {
    @Test
    fun `transient errors are recognised`() {
        assertTrue(SourceCalls.isTransient(SocketTimeoutException("slow")))
        assertTrue(SourceCalls.isTransient(UnknownHostException("offline")))
        assertTrue(SourceCalls.isTransient(IOException("connection reset")))
        assertTrue(SourceCalls.isTransient(HttpStatusException(429, "https://example.org")))
        assertTrue(SourceCalls.isTransient(HttpStatusException(503, "https://example.org")))
        assertTrue(SourceCalls.isTransient(AppError.Timeout(SocketTimeoutException())))
    }

    @Test
    fun `permanent errors are not retried`() {
        assertFalse(SourceCalls.isTransient(HttpStatusException(404, "https://example.org")))
        assertFalse(SourceCalls.isTransient(HttpStatusException(501, "https://example.org")))
        assertFalse(SourceCalls.isTransient(WebViewRequiredException("https://example.org")))
        assertFalse(SourceCalls.isTransient(SourceException("Login required")))
        assertFalse(SourceCalls.isTransient(IllegalStateException("parser broke")))
        assertFalse(SourceCalls.isTransient(CancellationException("screen closed")))
        // An outdated extension fails the same way every time, even when wrapped in an IOException.
        assertFalse(SourceCalls.isTransient(IOException(NoSuchMethodError("okhttp3.Headers.get"))))
        assertFalse(SourceCalls.isTransient(SourceException("Broken (en) is not compatible", AbstractMethodError())))
    }

    @Test
    fun `retries transient failures until success`() = runBlocking {
        var calls = 0
        val result = SourceCalls.retrying(attempts = 3, baseDelayMs = 1) { attempt ->
            calls++
            if (attempt < 2) throw SocketTimeoutException("try again")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun `gives up after the last attempt with the last error`() {
        var calls = 0
        val e = assertThrows(HttpStatusException::class.java) {
            runBlocking {
                SourceCalls.retrying(attempts = 2, baseDelayMs = 1) {
                    calls++
                    throw HttpStatusException(if (calls == 1) 503 else 502, "u")
                }
            }
        }
        assertEquals(2, calls)
        assertEquals(502, e.code)
    }

    @Test
    fun `does not retry permanent errors or vetoed retries`() {
        var calls = 0
        assertThrows(HttpStatusException::class.java) {
            runBlocking { SourceCalls.retrying(attempts = 5, baseDelayMs = 1) { calls++; throw HttpStatusException(404, "u") } }
        }
        assertEquals(1, calls)

        calls = 0
        assertThrows(SocketTimeoutException::class.java) {
            runBlocking { SourceCalls.retrying(attempts = 5, baseDelayMs = 1, retryIf = { false }) { calls++; throw SocketTimeoutException() } }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `inner timeouts are retried`() = runBlocking {
        var calls = 0
        val result = SourceCalls.retrying(attempts = 2, baseDelayMs = 1) { attempt ->
            calls++
            withTimeout(20) { if (attempt == 0) delay(1_000) }
            "second"
        }
        assertEquals("second", result)
        assertEquals(2, calls)
    }

    @Test
    fun `deadline is reported as a timeout error`() {
        assertThrows(AppError.Timeout::class.java) {
            runBlocking { SourceCalls.withDeadline(20) { delay(1_000) } }
        }
    }

    @Test
    fun `backoff grows with attempts`() {
        val first = SourceCalls.backoff(100, 0)
        val third = SourceCalls.backoff(100, 2)
        assertTrue(first in 100..125, "first=$first")
        assertTrue(third in 400..500, "third=$third")
    }

    @Test
    fun `health marks repeatedly failing sources`() {
        var now = 1_000L
        val tracker = SourceHealthTracker { now }
        tracker.failure(1, SocketTimeoutException())
        assertFalse(tracker[1].unstable)
        now = 2_000
        tracker.failure(1, SocketTimeoutException())
        assertTrue(tracker[1].unstable)
        assertEquals(2_000, tracker[1].lastFailureAt)
        tracker.success(1, latencyMs = 9_000)
        assertFalse(tracker[1].unstable)
        assertTrue(tracker[1].slow)
        assertFalse(tracker[2].unstable)
        tracker.reset(1)
        assertEquals(SourceHealth(), tracker[1])
    }
}
