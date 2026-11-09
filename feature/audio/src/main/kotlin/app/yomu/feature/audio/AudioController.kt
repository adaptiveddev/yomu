package app.yomu.feature.audio

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the player is doing, for the player screen and the "now playing" bar. */
data class PlayerSnapshot(
    val bookId: Long,
    val bookTitle: String,
    val trackTitle: String,
    val artworkUri: String?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val playWhenReady: Boolean,
    val track: Int,
    val trackCount: Int,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val sleepAt: Long,
    val sleepEndOfChapter: Boolean,
    val skipSilence: Boolean,
    val error: String?,
)

/** The app's connection to [AudioPlaybackService]. Created on first use; all calls on the main thread. */
class AudioController(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val _state = MutableStateFlow<PlayerSnapshot?>(null)

    /** Null while no book is loaded (or the service has stopped). */
    val state: StateFlow<PlayerSnapshot?> = _state.asStateFlow()

    /** [state] without the moving position, for views that do not show it (they would recompose on every tick). */
    val nowPlaying: Flow<PlayerSnapshot?> = _state.map { it?.copy(positionMs = 0, durationMs = 0, isBuffering = false) }.distinctUntilChanged()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    suspend fun connect(): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        val f = future ?: MediaController.Builder(context, SessionToken(context, ComponentName(context, AudioPlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) = refresh()
                override fun onDisconnected(controller: MediaController) {
                    this@AudioController.controller = null
                    future = null
                    _state.value = null
                }
            })
            .buildAsync().also { future = it }
        val c = suspendCancellableCoroutine { cont ->
            f.addListener({
                runCatching { f.get() }.onSuccess { cont.resume(it) }.onFailure { cont.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(context))
        }
        if (controller !== c) {
            c.addListener(listener)
            controller = c
        }
        refresh()
        return c
    }

    /** Loads [bookId] into the player (keeping it if it is already there) and starts it when [play]. */
    suspend fun open(bookId: Long, play: Boolean) {
        val c = connect()
        c.sendCustomCommand(
            SessionCommand(AudioPlaybackService.CMD_LOAD, Bundle.EMPTY),
            Bundle().apply {
                putLong(AudioPlaybackService.KEY_BOOK, bookId)
                putBoolean(AudioPlaybackService.KEY_PLAY, play)
            },
        )
    }

    /** Minutes until pause; [AudioPlaybackService.SLEEP_END_OF_CHAPTER] for the end of the chapter, 0 cancels. */
    fun sleep(minutes: Int) {
        controller?.sendCustomCommand(
            SessionCommand(AudioPlaybackService.CMD_SLEEP, Bundle.EMPTY),
            Bundle().apply { putInt(AudioPlaybackService.KEY_MINUTES, minutes) },
        )
    }

    /** Shortens pauses in speech; kept per book like the speed. */
    fun setSkipSilence(enabled: Boolean) {
        controller?.sendCustomCommand(
            SessionCommand(AudioPlaybackService.CMD_SKIP_SILENCE, Bundle.EMPTY),
            Bundle().apply { putBoolean(AudioPlaybackService.KEY_ENABLED, enabled) },
        )
    }

    fun setSkipIntervals(backSec: Int, forwardSec: Int) {
        controller?.sendCustomCommand(
            SessionCommand(AudioPlaybackService.CMD_SKIP_INTERVALS, Bundle.EMPTY),
            Bundle().apply {
                putInt(AudioPlaybackService.KEY_SKIP_BACK, backSec)
                putInt(AudioPlaybackService.KEY_SKIP_FORWARD, forwardSec)
            },
        )
    }

    /** Replaces the book's bookmarks; the service keeps them with the other per-book settings. */
    fun setBookmarks(list: List<Bookmark>) {
        controller?.sendCustomCommand(
            SessionCommand(AudioPlaybackService.CMD_BOOKMARKS, Bundle.EMPTY),
            Bundle().apply { putString(AudioPlaybackService.KEY_BOOKMARKS, AudioSettings.encodeBookmarks(list).toString()) },
        )
    }

    /** Ends playback; the position is saved by the service before the playlist is cleared. */
    fun stop() {
        val p = player() ?: return
        p.pause()
        p.stop()
        p.clearMediaItems()
    }

    fun player(): Player? = controller?.takeIf { it.isConnected }

    fun togglePlay() {
        val p = player() ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
        if (p.playWhenReady) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
    }

    /** Position updates are not events: the screen calls this on its own clock. */
    fun refresh() {
        val c = controller?.takeIf { it.isConnected } ?: return
        val extras = c.sessionExtras
        val bookId = extras.getLong(AudioPlaybackService.KEY_BOOK, -1)
        if (bookId < 0) {
            _state.value = null
            return
        }
        val meta = c.mediaMetadata
        _state.value = PlayerSnapshot(
            bookId = bookId,
            bookTitle = meta.albumTitle?.toString().orEmpty(),
            trackTitle = meta.title?.toString().orEmpty(),
            artworkUri = meta.artworkUri?.toString(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            playWhenReady = c.playWhenReady,
            track = c.currentMediaItemIndex,
            trackCount = c.mediaItemCount,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
            speed = c.playbackParameters.speed,
            sleepAt = extras.getLong(AudioPlaybackService.KEY_SLEEP_AT, 0),
            sleepEndOfChapter = extras.getBoolean(AudioPlaybackService.KEY_SLEEP_CHAPTER, false),
            skipSilence = extras.getBoolean(AudioPlaybackService.KEY_SKIP_SILENCE, false),
            error = extras.getString(AudioPlaybackService.KEY_ERROR) ?: c.playerError?.let { it.localizedMessage ?: it.errorCodeName },
        )
    }
}
