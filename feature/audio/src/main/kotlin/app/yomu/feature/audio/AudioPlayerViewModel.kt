package app.yomu.feature.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yomu.core.datastore.AppSettings
import app.yomu.data.audio.AudioDownloadProgress
import app.yomu.data.audio.AudioDownloadState
import app.yomu.data.audio.AudiobookDownloader
import app.yomu.data.audio.AudiobookStore
import app.yomu.domain.model.AudioNavEntry
import app.yomu.domain.model.AudioOrigin
import app.yomu.domain.model.AudioPositions
import app.yomu.domain.model.Book
import app.yomu.domain.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioPlayerViewModel(
    val bookId: Long,
    private val books: BookRepository,
    private val store: AudiobookStore,
    private val audio: AudioController,
    private val downloader: AudiobookDownloader,
    private val settings: AppSettings,
) : ViewModel() {
    val book: StateFlow<Book?> = books.observeBook(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The chapter list: tracks, or the chapter marks inside them. */
    private val _chapters = MutableStateFlow<List<AudioNavEntry>>(emptyList())
    val chapters: StateFlow<List<AudioNavEntry>> = _chapters.asStateFlow()

    private val _origin = MutableStateFlow(AudioOrigin.FILES)
    val origin: StateFlow<AudioOrigin> = _origin.asStateFlow()

    private val _loadError = MutableStateFlow<Throwable?>(null)
    val loadError: StateFlow<Throwable?> = _loadError.asStateFlow()

    /** Offline copies of an online book; null for books whose files are already on the device. */
    private val _offline = MutableStateFlow<AudioDownloadState?>(null)
    val offline: StateFlow<AudioDownloadState?> = _offline.asStateFlow()

    val download: StateFlow<AudioDownloadProgress?> = downloader.progress(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Track lengths known before playback (playlists, M4B headers); 0 where unknown. */
    private var trackDurations: List<Long> = emptyList()

    /** The player, only while it holds this book. */
    val player: StateFlow<PlayerSnapshot?> = audio.state.map { s -> s?.takeIf { it.bookId == bookId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            runCatching {
                val b = withContext(Dispatchers.IO) { books.getBook(bookId) } ?: return@launch
                _origin.value = withContext(Dispatchers.IO) { store.origin(b) }
                val tracks = store.tracks(b)
                trackDurations = tracks.map { it.durationMs }
                _chapters.value = AudioPositions.navigation(tracks)
                refreshOffline()
                audio.open(bookId, play = false)
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else _loadError.value = it }
        }
        // Each finished track changes what is on the device.
        viewModelScope.launch {
            var lastTrack = -1
            download.collect { p ->
                if (p == null) return@collect
                if (!p.running || p.track != lastTrack) refreshOffline()
                lastTrack = p.track
            }
        }
    }

    /** Positions move without player events: the screen ticks while it is shown and the book plays. */
    suspend fun tickWhilePlaying() {
        while (true) {
            audio.refresh()
            delay(TICK_MS)
        }
    }

    private suspend fun refreshOffline() {
        val b = withContext(Dispatchers.IO) { books.getBook(bookId) } ?: return
        _offline.value = withContext(Dispatchers.IO) { downloader.state(b) }
    }

    fun playPause() = audio.togglePlay()

    fun seekTo(positionMs: Long) { audio.player()?.seekTo(positionMs) }

    fun back() { audio.player()?.seekBack() }

    fun forward() { audio.player()?.seekForward() }

    fun jump(entry: AudioNavEntry) {
        val p = audio.player() ?: return
        p.seekTo(entry.track, entry.startMs)
        if (!p.playWhenReady) p.play()
    }

    /** The current chapter's start, or the previous chapter within the first seconds of one. */
    fun previous() {
        val s = player.value ?: return
        val list = chapters.value
        val i = AudioPositions.current(list, s.track, s.positionMs)
        val entry = list.getOrNull(i) ?: return
        val target = if (s.positionMs - entry.startMs > RESTART_WINDOW_MS || i == 0) entry else list[i - 1]
        audio.player()?.seekTo(target.track, target.startMs)
    }

    fun next() {
        val s = player.value ?: return
        val list = chapters.value
        list.getOrNull(AudioPositions.current(list, s.track, s.positionMs) + 1)?.let { audio.player()?.seekTo(it.track, it.startMs) }
    }

    fun setSpeed(speed: Float) {
        // Steps of ±0.05 add float error; keep two decimals.
        val v = Math.round(speed.coerceIn(AudioSettings.MIN_SPEED, AudioSettings.MAX_SPEED) * 100) / 100f
        audio.player()?.setPlaybackSpeed(v)
    }

    /** Time left in the book at the current speed, or null while some track length is unknown. */
    fun timeLeftMs(s: PlayerSnapshot): Long? {
        val durations = trackDurations.toMutableList().takeIf { it.size == s.trackCount } ?: return null
        if (s.track !in durations.indices) return null
        if (s.durationMs > 0) durations[s.track] = s.durationMs
        return AudioPositions.remaining(durations, s.track, s.positionMs)?.let { (it / s.speed.coerceAtLeast(0.1f)).toLong() }
    }

    fun toggleSkipSilence() { player.value?.let { audio.setSkipSilence(!it.skipSilence) } }

    fun sleep(minutes: Int) = audio.sleep(minutes)

    fun startDownload() = viewModelScope.launch { downloader.start(bookId, settings.downloadWifiOnly.get()) }

    fun cancelDownload() = downloader.cancel(bookId)

    fun removeDownload() = viewModelScope.launch {
        val b = withContext(Dispatchers.IO) { books.getBook(bookId) } ?: return@launch
        downloader.remove(b)
        refreshOffline()
    }

    private companion object {
        const val TICK_MS = 500L
        const val RESTART_WINDOW_MS = 3_000L
    }
}
