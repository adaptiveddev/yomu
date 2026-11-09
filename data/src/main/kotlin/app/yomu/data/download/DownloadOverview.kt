package app.yomu.data.download

import app.yomu.core.datastore.AppSettings
import app.yomu.core.network.NetworkMonitor
import app.yomu.data.audio.AudioQueueEntry
import app.yomu.data.audio.AudiobookDownloader
import app.yomu.domain.repository.BookRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** An audiobook download with the book's title, as the downloads screen lists it. */
data class AudioDownload(val entry: AudioQueueEntry, val title: String)

/**
 * Everything that downloads (chapters and book files from the queue, offline audiobooks) seen as one stream, for the
 * library's downloads button and the downloads screen.
 */
class DownloadOverview(
    private val repo: DownloadRepository,
    private val audio: AudiobookDownloader,
    private val books: BookRepository,
    private val settings: AppSettings,
    private val network: NetworkMonitor,
) {
    private val titles = HashMap<Long, String>()

    /** Audiobook downloads in the queue; titles are looked up once per book. */
    val audioQueue: Flow<List<AudioDownload>> = audio.queue().map { list ->
        list.map { AudioDownload(it, titles.getOrPut(it.bookId) { books.getBook(it.bookId)?.title.orEmpty() }) }
    }

    val summary: Flow<DownloadSummary> = combine(repo.observe(), audio.queue()) { items, audioItems ->
        val now = System.currentTimeMillis()
        DownloadSummary.of(items.mapNotNull(TaskProgress::of) + audioItems.map { audioTask(it, now) })
    }.distinctUntilChanged()

    /** Why waiting downloads do not move: no connection, or "Wi-Fi only" while on mobile data. */
    val networkWait: Flow<NetworkWait> = combine(network.online, settings.downloadWifiOnly.flow, meteredUpdates()) { online, wifiOnly, metered ->
        NetworkWait.of(online, wifiOnly, metered)
    }.distinctUntilChanged()

    // NetworkMonitor has no flow for the metered state: it is sampled while someone watches.
    private fun meteredUpdates(): Flow<Boolean> = flow {
        while (true) {
            emit(network.isMetered)
            delay(METERED_POLL_MS)
        }
    }.distinctUntilChanged()

    private fun audioTask(e: AudioQueueEntry, now: Long): TaskProgress {
        val p = e.progress
        val state = when {
            p.error != null -> TaskProgress.State.FAILED
            p.waiting -> TaskProgress.State.QUEUED
            else -> TaskProgress.State.RUNNING
        }
        // WorkManager gives no creation time: queued audiobooks belong to the batch that is running now.
        return TaskProgress(state, p.fraction.takeIf { state == TaskProgress.State.RUNNING && it > 0f }, now, now)
    }

    private companion object {
        const val METERED_POLL_MS = 3_000L
    }
}
