package app.yomu

import android.content.Context
import android.content.Intent
import androidx.work.Configuration
import androidx.work.WorkManager
import app.yomu.core.database.DownloadTaskEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.data.work.DownloadActionReceiver
import app.yomu.data.work.Notifications
import app.yomu.domain.model.DownloadStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Buttons and taps of the download notifications: "Pause", "Resume", "Retry" act on the queue, a tap opens it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationActionsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // Workers must not process the queue under the test.
        runCatching { WorkManager.initialize(context, Configuration.Builder().setExecutor { }.build()) }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun press(action: String) =
        DownloadActionReceiver().onReceive(context, Intent(context, DownloadActionReceiver::class.java).setAction(action))

    private suspend fun YomuDatabase.awaitStatus(ids: List<Long>, status: DownloadStatus) = withTimeout(10_000) {
        while (ids.any { downloads().get(it)?.status != status.name }) delay(20)
    }

    @Test
    fun buttonsActOnTheQueue() = runBlocking {
        val db = GlobalContext.get().get<YomuDatabase>()
        val now = System.currentTimeMillis()
        fun task(n: Int, status: DownloadStatus) = DownloadTaskEntity(
            kind = "BOOK", title = "Book $n", url = "https://example.org/$n.epub", headersJson = null, status = status.name,
            bytesDownloaded = 0, bytesTotal = 0, pagesDone = 0, pagesTotal = 0, error = null, chapterId = null, itemId = null,
            sourceId = null, destPath = null, mimeType = "application/epub+zip", createdAt = now + n, updatedAt = now + n, attempts = 0,
        )
        val waiting = listOf(db.downloads().insert(task(1, DownloadStatus.QUEUED)), db.downloads().insert(task(2, DownloadStatus.RUNNING)))
        val failed = db.downloads().insert(task(3, DownloadStatus.FAILED))

        press(DownloadActionReceiver.ACTION_PAUSE)
        db.awaitStatus(waiting, DownloadStatus.PAUSED)
        assertEquals(DownloadStatus.FAILED.name, db.downloads().get(failed)?.status)

        press(DownloadActionReceiver.ACTION_RETRY)
        db.awaitStatus(listOf(failed), DownloadStatus.QUEUED)
        assertEquals(DownloadStatus.PAUSED.name, db.downloads().get(waiting.first())?.status)

        press(DownloadActionReceiver.ACTION_RESUME)
        db.awaitStatus(waiting, DownloadStatus.QUEUED)

        // Anything else sent to the receiver is ignored.
        press("app.yomu.action.SOMETHING_ELSE")
        delay(200)
        db.awaitStatus(waiting + failed, DownloadStatus.QUEUED)
    }

    @Test
    fun tapOpensTheQueue() = runBlocking {
        val koin = GlobalContext.get()
        val main = MainViewModel(koin.get(), koin.get(), koin.get(), koin.get(), koin.get())
        main.handleIntent(Intent(Notifications.ACTION_OPEN_DOWNLOADS))
        assertEquals(MainEvent.OpenDownloads, withTimeout(5_000) { main.events.first() })
    }
}
