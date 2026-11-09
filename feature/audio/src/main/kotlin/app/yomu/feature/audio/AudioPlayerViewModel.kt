package app.yomu.feature.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    books: BookRepository,
    private val store: AudiobookStore,
    private val audio: AudioController,
) : ViewModel() {
    val book: StateFlow<Book?> = books.observeBook(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The chapter list: tracks, or the chapter marks inside them. */
    private val _chapters = MutableStateFlow<List<AudioNavEntry>>(emptyList())
    val chapters: StateFlow<List<AudioNavEntry>> = _chapters.asStateFlow()

    private val _origin = MutableStateFlow(AudioOrigin.FILES)
    val origin: StateFlow<AudioOrigin> = _origin.asStateFlow()

    private val _loadError = MutableStateFlow<Throwable?>(null)
    val loadError: StateFlow<Throwable?> = _loadError.asStateFlow()

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
                audio.open(bookId, play = false)
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else _loadError.value = it }
        }
        // Positions move without player events.
        viewModelScope.launch {
            while (true) {
                audio.refresh()
                delay(TICK_MS)
            }
        }
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

    fun setSkipSilence(enabled: Boolean) = audio.setSkipSilence(enabled)

    /** Time left in the book at the current speed, or null while some track length is unknown. */
    fun timeLeftMs(s: PlayerSnapshot): Long? {
        val durations = trackDurations.toMutableList().takeIf { it.size == s.trackCount } ?: return null
        if (s.track !in durations.indices) return null
        if (s.durationMs > 0) durations[s.track] = s.durationMs
        return AudioPositions.remaining(durations, s.track, s.positionMs)?.let { (it / s.speed.coerceAtLeast(0.1f)).toLong() }
    }

    fun sleep(minutes: Int) = audio.sleep(minutes)

    private companion object {
        const val TICK_MS = 500L
        const val RESTART_WINDOW_MS = 3_000L
    }
}
