package app.yomu.data.download

import app.yomu.domain.model.DownloadItem
import app.yomu.domain.model.DownloadKind
import app.yomu.domain.model.DownloadStatus
import app.yomu.source.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class DownloadPolicyTest {
    private fun http(code: Int) = HttpStatusException(code, "https://example.org/f")

    @Test
    fun backoffDoublesAndIsCapped() {
        assertEquals(5_000, RetryPolicy.delayMs(5_000, 0))
        assertEquals(10_000, RetryPolicy.delayMs(5_000, 1))
        assertEquals(20_000, RetryPolicy.delayMs(5_000, 2))
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayMs(5_000, 9))
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayMs(5_000, 500))
    }

    @Test
    fun jitterAddsAtMostAQuarter() {
        assertEquals(12_500, RetryPolicy.delayMs(10_000, 0, jitter = 1.0))
        assertEquals(10_000, RetryPolicy.delayMs(10_000, 0, jitter = -3.0))
        assertEquals(12_500, RetryPolicy.delayMs(10_000, 0, jitter = 7.0))
    }

    @Test
    fun networkErrorsAreTransient() {
        listOf(
            SocketTimeoutException("timeout"), UnknownHostException("host"), ConnectException("refused"),
            SocketException("Connection reset"), SSLException("Connection closed by peer"), SSLHandshakeException("handshake"),
            IOException("Connection closed after 10 of 20 bytes"), IOException("wrapped", SocketException("reset")),
        ).forEach { assertEquals(it.toString(), FailureKind.TRANSIENT, RetryPolicy.classify(it)) }
    }

    @Test
    fun serverBusyIsTransientButClientErrorsAreNot() {
        listOf(408, 429, 500, 502, 503, 504).forEach { assertEquals("$it", FailureKind.TRANSIENT, RetryPolicy.classify(http(it))) }
        listOf(400, 401, 403, 404, 410, 501).forEach { assertEquals("$it", FailureKind.PERMANENT, RetryPolicy.classify(http(it))) }
        assertEquals(FailureKind.PERMANENT, RetryPolicy.classify(FileNotFoundException("gone")))
        assertEquals(FailureKind.PERMANENT, RetryPolicy.classify(UnexpectedContentException("html")))
    }

    @Test
    fun fullDiskPausesTheQueue() {
        assertEquals(FailureKind.NO_SPACE, RetryPolicy.classify(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun retriesStopAtTheBudgetAndNeverApplyToPermanentErrors() {
        assertTrue(RetryPolicy.shouldRetry(FailureKind.TRANSIENT, 0, 3))
        assertTrue(RetryPolicy.shouldRetry(FailureKind.TRANSIENT, 2, 3))
        assertFalse(RetryPolicy.shouldRetry(FailureKind.TRANSIENT, 3, 3))
        assertFalse(RetryPolicy.shouldRetry(FailureKind.TRANSIENT, 0, 0))
        assertFalse(RetryPolicy.shouldRetry(FailureKind.PERMANENT, 0, 3))
        assertFalse(RetryPolicy.shouldRetry(FailureKind.NO_SPACE, 0, 3))
    }

    @Test
    fun resumeAppendsOnlyWhenTheServerContinuesWhereWeStopped() {
        assertTrue(RangeResume.canAppend(1000, 206, "bytes 1000-1999/2000"))
        assertTrue(RangeResume.canAppend(1000, 206, null))
        assertFalse(RangeResume.canAppend(1000, 206, "bytes 0-1999/2000"))
        assertFalse(RangeResume.canAppend(1000, 206, "bytes */2000"))
        assertFalse(RangeResume.canAppend(1000, 200, null))
        assertFalse(RangeResume.canAppend(0, 206, "bytes 0-9/10"))
    }

    @Test
    fun ifRangePrefersStrongEtag() {
        assertEquals("\"abc\"", RangeResume.ifRange("\"abc\"", "Tue, 01 Jan 2030 00:00:00 GMT"))
        assertEquals("Tue, 01 Jan 2030 00:00:00 GMT", RangeResume.ifRange("W/\"abc\"", "Tue, 01 Jan 2030 00:00:00 GMT"))
        assertNull(RangeResume.ifRange(null, null))
        assertNull(RangeResume.ifRange(" ", ""))
    }

    private fun task(state: TaskProgress.State, fraction: Float? = null, created: Long = 0, updated: Long = created) =
        TaskProgress(state, fraction, created, updated)

    @Test
    fun summaryCountsStatesAndHidesWhenIdle() {
        assertFalse(DownloadSummary.of(emptyList()).visible)
        assertFalse(DownloadSummary.of(listOf(task(TaskProgress.State.DONE))).visible)
        val s = DownloadSummary.of(
            listOf(
                task(TaskProgress.State.RUNNING, 0.5f), task(TaskProgress.State.QUEUED), task(TaskProgress.State.QUEUED),
                task(TaskProgress.State.PAUSED), task(TaskProgress.State.FAILED),
            ),
        )
        assertEquals(1, s.running)
        assertEquals(2, s.queued)
        assertEquals(4, s.remaining)
        assertEquals(1, s.failed)
        assertTrue(s.visible && s.active)
        assertTrue(DownloadSummary.of(listOf(task(TaskProgress.State.FAILED))).visible)
    }

    @Test
    fun fractionIsAveragedOverTheBatchAndKeepsFinishedWork() {
        val s = DownloadSummary.of(
            listOf(
                task(TaskProgress.State.RUNNING, 0.5f, created = 100),
                task(TaskProgress.State.QUEUED, null, created = 100),
                task(TaskProgress.State.DONE, 1f, created = 90, updated = 150),
                task(TaskProgress.State.DONE, 1f, created = 10, updated = 20), // finished before this batch began
            ),
        )
        assertEquals((0.5f + 0f + 1f) / 3, s.fraction!!, 1e-6f)
    }

    @Test
    fun fractionIsUnknownUntilSomethingIsMeasurable() {
        assertNull(DownloadSummary.of(listOf(task(TaskProgress.State.RUNNING, null), task(TaskProgress.State.QUEUED))).fraction)
        assertNull(DownloadSummary.of(listOf(task(TaskProgress.State.RUNNING, 0f))).fraction)
        assertNull(DownloadSummary.of(listOf(task(TaskProgress.State.FAILED, 0.3f))).fraction)
    }

    @Test
    fun taskProgressFromAnItemUsesPagesOrBytesAndSkipsCancelled() {
        val pages = DownloadItem(kind = DownloadKind.CHAPTER, title = "c", url = "u", status = DownloadStatus.RUNNING, pagesDone = 3, pagesTotal = 12)
        assertEquals(0.25f, TaskProgress.of(pages)!!.fraction!!, 1e-6f)
        val unknown = DownloadItem(kind = DownloadKind.BOOK, title = "b", url = "u", status = DownloadStatus.RUNNING, bytesDownloaded = 500)
        assertNull(TaskProgress.of(unknown)!!.fraction)
        assertNull(TaskProgress.of(unknown.copy(status = DownloadStatus.CANCELLED)))
    }

    @Test
    fun networkWaitReasons() {
        assertEquals(NetworkWait.OFFLINE, NetworkWait.of(online = false, wifiOnly = false, metered = false))
        assertEquals(NetworkWait.OFFLINE, NetworkWait.of(online = false, wifiOnly = true, metered = true))
        assertEquals(NetworkWait.WIFI_ONLY, NetworkWait.of(online = true, wifiOnly = true, metered = true))
        assertEquals(NetworkWait.NONE, NetworkWait.of(online = true, wifiOnly = true, metered = false))
        assertEquals(NetworkWait.NONE, NetworkWait.of(online = true, wifiOnly = false, metered = true))
    }
}
