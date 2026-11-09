package app.yomu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.yomu.core.datastore.AppSettings
import app.yomu.feature.settings.AppUpdates
import app.yomu.feature.settings.ReleaseInfo
import app.yomu.feature.settings.UpdateBackground
import app.yomu.feature.settings.UpdateChecksumException
import app.yomu.feature.settings.UpdateDownloadWorker
import app.yomu.feature.settings.UpdateState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/** The update download handed to a background runner: it outlives the screens and survives a process restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateBackgroundTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val settings: AppSettings get() = GlobalContext.get().get()

    @After
    fun tearDown() {
        stopKoin()
    }

    private class FakeBackground : UpdateBackground {
        val started = mutableListOf<ReleaseInfo>()
        var cancelled = 0
        override fun start(release: ReleaseInfo) { started += release }
        override fun cancel() { cancelled++ }
    }

    private val release = ReleaseInfo(
        "9.9.0", "https://example.com/r", "https://example.com/yomu-v9.9.0-arm64-v8a.apk", apkSize = 3,
        checksumsUrl = "https://example.com/SHA256SUMS.txt", notes = "> notes \"quoted\"\n- item",
    )

    @Test
    fun downloadIsHandedOverAndTheReleaseSurvivesAProcessRestart() = runBlocking {
        val bg = FakeBackground()
        val updates = AppUpdates(context, settings, fetch = { release }, downloader = { r, dir, p ->
            p(1f)
            File(dir.apply { mkdirs() }, r.apkName!!).apply { writeText("apk") }
        }, installer = {}, background = bg)
        updates.download(release)
        assertEquals(listOf(release), bg.started)
        assertTrue(updates.state.value is UpdateState.Downloading)
        // A new process (a new instance) finds the same release on disk.
        val restarted = AppUpdates(context, settings, installer = {}, background = bg)
        assertEquals(release, restarted.pendingRelease())
        // The worker's attempt drives the state of the instance it runs in.
        val result = updates.runDownload(release)
        assertTrue(result is UpdateState.ReadyToInstall)
        assertEquals(result, updates.state.value)
        updates.clearPending()
        assertNull(updates.pendingRelease())
    }

    @Test
    fun cancelStopsTheBackgroundWorkAndForgetsTheRelease() {
        val bg = FakeBackground()
        val updates = AppUpdates(context, settings, fetch = { release }, installer = {}, background = bg)
        updates.download(release)
        updates.cancelDownload()
        assertEquals(1, bg.cancelled)
        assertNull(updates.pendingRelease())
        assertEquals(UpdateState.Available(release), updates.state.value)
    }

    @Test
    fun onlyNetworkTroubleIsRetried() {
        assertTrue(UpdateDownloadWorker.retryable(SocketTimeoutException("timeout")))
        assertTrue(UpdateDownloadWorker.retryable(IOException("Incomplete update download")))
        assertTrue(UpdateDownloadWorker.retryable(IOException("HTTP 503")))
        assertFalse(UpdateDownloadWorker.retryable(IOException("HTTP 404")))
        assertFalse(UpdateDownloadWorker.retryable(UpdateChecksumException("a.apk")))
        assertFalse(UpdateDownloadWorker.retryable(IllegalStateException("x")))
    }
}
