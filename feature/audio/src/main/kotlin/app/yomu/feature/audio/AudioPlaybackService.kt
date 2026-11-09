package app.yomu.feature.audio

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.yomu.core.network.NetworkClients
import app.yomu.data.audio.AudiobookStore
import app.yomu.data.audio.YouTubeClient
import app.yomu.domain.model.AudioNavEntry
import app.yomu.domain.model.AudioPositions
import app.yomu.domain.model.AudioTrack
import app.yomu.domain.model.AudiobookManifest
import app.yomu.domain.model.Book
import app.yomu.domain.model.ReadingStatus
import app.yomu.domain.repository.BookRepository
import app.yomu.domain.repository.StatsRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Opens the app at a book from the notification and lock screen; provided by the app module. */
fun interface AudioSessionActivity {
    fun intent(context: Context, bookId: Long): PendingIntent?
}

/**
 * Plays audiobooks in the background with a media notification, lock-screen, headset and Bluetooth controls. The
 * service owns the playlist of the book: it loads tracks, resolves YouTube streams, restores and saves the position,
 * records listening time and runs the sleep timer.
 */
@OptIn(UnstableApi::class)
class AudioPlaybackService : MediaSessionService() {
    private val books: BookRepository by inject()
    private val store: AudiobookStore by inject()
    private val stats: StatsRepository by inject()
    private val network: NetworkClients by inject()
    private val sessionActivity: AudioSessionActivity by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    private var book: Book? = null
    private var tracks: List<AudioTrack> = emptyList()
    private var nav: List<AudioNavEntry> = emptyList()
    private val durations = mutableMapOf<Int, Long>()
    private val resolved = mutableSetOf<Int>()
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepAt = 0L
    private var sleepChapter = false
    private var listeningSince = 0L
    private var pausedAt = 0L
    private var skipSilence = false
    private var settings = AudioSettings()
    private val retriedAt = mutableMapOf<Int, Long>()
    private val expiresAt = mutableMapOf<Int, Long>()
    private val resolving = mutableSetOf<Int>()

    // Bumped on every load: stream lookups still running for an earlier book (or an earlier load of the same one) are dropped.
    private var generation = 0
    private var retryJob: Job? = null
    private var networkRetries = 0

    // Stream servers of YouTube expect the user agent of the client that asked for the address.
    private val streamHeaders = ConcurrentHashMap<String, Map<String, String>>()

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpDataSource.Factory(network.downloadClient)
        val upstream = DefaultDataSource.Factory(this, http)
        val withHeaders = ResolvingDataSource.Factory(upstream) { spec ->
            val host = spec.uri.host.orEmpty()
            streamHeaders.entries.firstOrNull { host == it.key || host.endsWith(".${it.key}") }?.let { spec.withAdditionalHeaders(it.value) } ?: spec
        }
        // Speech is a few kB per second: buffer minutes ahead to ride out mobile dead zones, and keep the last
        // half minute so "back 10 s" does not download it again. The byte target (from the audio track) caps memory.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
            .setBackBuffer(BACK_BUFFER_MS, true)
            .build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(withHeaders).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_RETRIES)))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Video of YouTube streams is never shown: do not decode it.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        player.addListener(listener)
        // An ExoPlayer cannot change its seek increments once built; the skip intervals are per book.
        val seeking = object : ForwardingPlayer(player) {
            override fun getSeekBackIncrement() = settings.skipBackSec * 1000L
            override fun getSeekForwardIncrement() = settings.skipForwardSec * 1000L
            override fun seekBack() = seekTo((currentPosition - seekBackIncrement).coerceAtLeast(0))
            override fun seekForward() {
                val target = currentPosition + seekForwardIncrement
                seekTo(if (duration != C.TIME_UNSET) target.coerceAtMost(duration) else target)
            }
        }
        session = MediaSession.Builder(this, seeking)
            .setCallback(callback)
            .setMediaButtonPreferences(skipButtons())
            .build()
    }

    // Rewind/forward instead of previous/next in the notification: chapters of an audiobook are long.
    private fun skipButtons(): ImmutableList<CommandButton> {
        val back = CommandButton.Builder(
            when (settings.skipBackSec) {
                5 -> CommandButton.ICON_SKIP_BACK_5
                15 -> CommandButton.ICON_SKIP_BACK_15
                30 -> CommandButton.ICON_SKIP_BACK_30
                else -> CommandButton.ICON_SKIP_BACK_10
            },
        )
            .setDisplayName(getString(R.string.audio_back_n, settings.skipBackSec))
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forward = CommandButton.Builder(
            when (settings.skipForwardSec) {
                10 -> CommandButton.ICON_SKIP_FORWARD_10
                15 -> CommandButton.ICON_SKIP_FORWARD_15
                60 -> CommandButton.ICON_SKIP_FORWARD
                else -> CommandButton.ICON_SKIP_FORWARD_30
            },
        )
            .setDisplayName(getString(R.string.audio_forward_n, settings.skipForwardSec))
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        return ImmutableList.of(back, forward)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Swiping the app away while paused ends the session; while playing, the book keeps playing.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        savePosition(blocking = true)
        endListening()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- loading

    private fun load(bookId: Long, play: Boolean) {
        if (book?.id == bookId && player.mediaItemCount > 0) {
            if (play) player.play()
            resolveAhead()
            return
        }
        savePosition(blocking = false)
        endListening()
        loadJob?.cancel()
        retryJob?.cancel()
        generation++
        loadJob = scope.launch {
            publish(error = null, loading = bookId)
            runCatching {
                val b = withContext(Dispatchers.IO) { books.getBook(bookId) } ?: error("Book $bookId not found")
                val list = store.tracks(b).ifEmpty { throw app.yomu.core.common.AppError.FileMissing(b.uri) }
                val saved = withContext(Dispatchers.IO) { books.getProgress(bookId) }
                val start = saved?.chapter?.coerceIn(0, list.lastIndex) ?: 0
                // Coming back after a while: repeat the last seconds heard, like after a pause.
                val position = ((saved?.let(AudioPositions::positionMs) ?: 0L) - (b.lastReadAt?.let { rewindAfter(System.currentTimeMillis() - it) } ?: 0L))
                    .coerceAtLeast(0)
                book = b
                tracks = list
                nav = AudioPositions.navigation(list)
                durations.clear()
                list.forEachIndexed { i, t -> if (t.durationMs > 0) durations[i] = t.durationMs }
                resolved.clear()
                resolving.clear()
                retriedAt.clear()
                expiresAt.clear()
                pausedAt = 0
                networkRetries = 0
                var startError: Throwable? = null
                val first = try {
                    resolve(list[start], fresh = false)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    startError = e
                    null
                }
                first?.let { accept(start, it) }
                val items = list.mapIndexed { i, t -> item(b, i, t, if (i == start) first else null) }
                player.setMediaItems(items, start, position)
                settings = AudioSettings.of(b.readerSettingsJson)
                session?.setMediaButtonPreferences(skipButtons())
                player.setPlaybackParameters(PlaybackParameters(settings.speed))
                skipSilence = settings.skipSilence
                player.skipSilenceEnabled = skipSilence
                player.prepare()
                player.playWhenReady = play
                if (b.status == ReadingStatus.NEW) withContext(Dispatchers.IO) { books.setStatus(listOf(b.id), ReadingStatus.READING) }
                sessionActivity.intent(this@AudioPlaybackService, b.id)?.let { session?.setSessionActivity(it) }
                startError?.let { e -> publish(error = e.message ?: "YouTube"); return@launch }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Timber.w(it, "Audiobook load failed")
                book = null
                publish(error = it.message ?: it.javaClass.simpleName)
                return@launch
            }
            publish()
            resolveAhead()
        }
    }

    /** A playable address for a YouTube track (null for other tracks, which play their own address). */
    private class Resolved(val url: String, val mimeType: String?, val expiresAt: Long)

    private suspend fun resolve(track: AudioTrack, fresh: Boolean): Resolved? {
        if (AudiobookStore.playableUri(track) != track.uri) return null
        val id = AudiobookManifest.youtubeId(track.uri) ?: return null
        val s = store.youtube.stream(id, fresh = fresh)
        s.headers.takeIf { it.isNotEmpty() }?.let { streamHeaders["googlevideo.com"] = it; streamHeaders["youtube.com"] = it }
        return Resolved(s.url, if (s.isHls) MimeTypes.APPLICATION_M3U8 else s.mimeType, YouTubeClient.expiresAt(s.url))
    }

    /** Records a stream of track [index] of the current load. */
    private fun accept(index: Int, r: Resolved) {
        resolved += index
        expiresAt[index] = r.expiresAt
    }

    /** A YouTube track without an address yet, or whose address has expired or is about to. */
    private fun needsStream(i: Int): Boolean {
        if (!isYouTube(i)) return false
        if (i !in resolved) return true
        val expires = expiresAt[i] ?: 0
        return expires > 0 && expires < System.currentTimeMillis() + EXPIRY_MARGIN_MS
    }

    private fun item(b: Book, index: Int, t: AudioTrack, stream: Resolved?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(b.authorsText.ifBlank { null })
            .setAlbumTitle(b.title)
            .setTrackNumber(index + 1)
            .setTotalTrackCount(tracks.size.takeIf { it > 0 })
            .setArtworkUri(b.coverPath?.let { Uri.fromFile(File(it)) })
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .build()
        return MediaItem.Builder()
            .setMediaId("${b.id}/$index")
            // Not yet resolved YouTube tracks keep their "youtube:" address until just before they play.
            // Downloaded copies play from the device.
            .setUri(stream?.url ?: AudiobookStore.playableUri(t))
            .setMimeType(stream?.mimeType)
            .setMediaMetadata(metadata)
            .build()
    }

    /** Resolves the current YouTube track (when the user jumped to it or its address expired) and the next one before it is needed. */
    private fun resolveAhead(fresh: Boolean = false) {
        val b = book ?: return
        val gen = generation
        val current = player.currentMediaItemIndex
        listOf(current, current + 1).filter { it !in resolving && (needsStream(it) || fresh && it == current && isYouTube(it)) }.forEach { i ->
            resolving += i
            val track = tracks[i]
            scope.launch {
                val result = try {
                    Result.success(resolve(track, fresh && i == current))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                if (gen != generation) return@launch
                resolving -= i
                result.onSuccess { stream ->
                    if (stream == null || i >= player.mediaItemCount) return@onSuccess
                    accept(i, stream)
                    val isCurrent = player.currentMediaItemIndex == i
                    val position = player.currentPosition
                    val wasPlaying = player.playWhenReady
                    player.replaceMediaItem(i, item(b, i, track, stream))
                    if (isCurrent) {
                        player.seekTo(i, position)
                        player.prepare()
                        player.playWhenReady = wasPlaying
                    }
                }.onFailure { e ->
                    if (player.currentMediaItemIndex != i) return@onFailure
                    if (e is java.io.IOException && e !is app.yomu.data.audio.YouTubeException) retryLater { resolveAhead(fresh) }
                    else publish(error = e.message ?: "YouTube")
                }
            }
        }
    }

    /** A YouTube track that streams (not one with a downloaded copy). */
    private fun isYouTube(i: Int) =
        i in tracks.indices && AudiobookManifest.youtubeId(tracks[i].uri) != null && AudiobookStore.playableUri(tracks[i]) == tracks[i].uri

    /**
     * Tries again after a network failure, waiting longer each time (2 s up to a minute), while the user still wants
     * to listen; a successful start resets the wait.
     */
    private fun retryLater(action: () -> Unit) {
        if (retryJob?.isActive == true) return
        val wait = networkBackoff(networkRetries++)
        publish(error = getString(R.string.audio_reconnecting))
        val gen = generation
        retryJob = scope.launch {
            delay(wait)
            if (gen == generation && book != null && player.playWhenReady) action()
        }
    }

    private fun saveSettings() {
        val b = book ?: return
        val json = settings.copy(speed = player.playbackParameters.speed, skipSilence = skipSilence).merge(b.readerSettingsJson)
        book = b.copy(readerSettingsJson = json)
        scope.launch(Dispatchers.IO) { books.saveReaderSettings(b.id, json) }
    }

    // ---------------------------------------------------------------- state

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                listeningSince = System.currentTimeMillis()
                startSaving()
            } else {
                endListening()
                savePosition(blocking = false)
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // "Stop" clears the playlist: the book is no longer loaded.
            if (timeline.isEmpty && book != null && loadJob?.isActive != true) {
                endListening()
                generation++
                retryJob?.cancel()
                book = null
                tracks = emptyList()
                nav = emptyList()
                sleep(0)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            savePosition(blocking = false)
            resolveAhead()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                pausedAt = System.currentTimeMillis()
                return
            }
            val paused = pausedAt
            pausedAt = 0
            if (paused > 0) {
                val rewind = rewindAfter(System.currentTimeMillis() - paused)
                if (rewind > 0) player.seekTo(player.currentMediaItemIndex, (player.currentPosition - rewind).coerceAtLeast(0))
            }
            // Addresses expire after a few hours: a book paused overnight asks for a fresh one before it plays.
            resolveAhead()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                val d = player.duration
                if (d > 0) durations[player.currentMediaItemIndex] = d
                if (networkRetries > 0) {
                    networkRetries = 0
                    publish()
                }
            }
            if (state == Player.STATE_ENDED) {
                val b = book ?: return
                scope.launch(Dispatchers.IO) {
                    books.saveProgress(b.id, AudioPositions.locator(tracks.lastIndex.coerceAtLeast(0), 0, 0, 1f, tracks.lastOrNull()?.title))
                    books.setStatus(listOf(b.id), ReadingStatus.FINISHED)
                }
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = saveSettings()

        override fun onPlayerError(error: PlaybackException) {
            val index = player.currentMediaItemIndex
            // No connection (a tunnel, a lift): prepare again from the same position once the network may be back.
            if (error.errorCode in NETWORK_ERRORS && book != null) {
                Timber.d(error, "Audiobook network error, retrying")
                retryLater { player.prepare() }
                return
            }
            // Expired or refused YouTube addresses: ask for a fresh one (at most once a minute per track), then report.
            val now = System.currentTimeMillis()
            if (isYouTube(index) && now - (retriedAt[index] ?: 0) > RETRY_WINDOW_MS) {
                retriedAt[index] = now
                resolveAhead(fresh = true)
                return
            }
            Timber.w(error, "Audiobook playback error")
            publish(error = error.localizedMessage ?: error.errorCodeName)
        }
    }

    private fun startSaving() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            while (isActive && player.isPlaying) {
                delay(SAVE_EVERY_MS)
                savePosition(blocking = false)
            }
        }
    }

    private fun savePosition(blocking: Boolean) {
        val b = book ?: return
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex.coerceIn(tracks.indices.takeIf { !it.isEmpty() } ?: return)
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.takeIf { it > 0 } ?: durations[index] ?: 0
        if (duration > 0) durations[index] = duration
        val total = AudioPositions.total(tracks.indices.map { durations[it] ?: 0 }, index, position)
        val title = nav.getOrNull(AudioPositions.current(nav, index, position))?.title ?: tracks[index].title
        val locator = AudioPositions.locator(index, position, duration, total, title)
        if (blocking) {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { runCatching { books.saveProgress(b.id, locator) } }
        } else {
            scope.launch(Dispatchers.IO + NonCancellable) { runCatching { books.saveProgress(b.id, locator) } }
        }
    }

    /** Listening time counts in the reading statistics like reading time. */
    private fun endListening() {
        val since = listeningSince
        val b = book
        listeningSince = 0
        if (since == 0L || b == null) return
        val duration = System.currentTimeMillis() - since
        if (duration < MIN_SESSION_MS) return
        CoroutineScope(Dispatchers.IO).launch { runCatching { stats.recordSession(b.id, since, duration, 0, 0) } }
    }

    // ---------------------------------------------------------------- sleep timer

    private fun sleep(minutes: Int) {
        sleepJob?.cancel()
        player.volume = 1f
        sleepAt = 0
        sleepChapter = false
        when {
            minutes > 0 -> {
                sleepAt = System.currentTimeMillis() + minutes * 60_000L
                sleepJob = scope.launch {
                    delay(minutes * 60_000L - SLEEP_FADE_MS)
                    // Fade out instead of cutting off mid-sentence.
                    val steps = 20
                    for (i in 1..steps) {
                        player.volume = 1f - i.toFloat() / steps
                        delay(SLEEP_FADE_MS / steps)
                    }
                    player.pause()
                    player.volume = 1f
                    sleepAt = 0
                    publish()
                }
            }
            minutes == SLEEP_END_OF_CHAPTER -> {
                sleepChapter = true
                val start = currentNav()
                sleepJob = scope.launch {
                    while (isActive) {
                        delay(500)
                        if (currentNav() != start || player.playbackState == Player.STATE_ENDED) {
                            player.pause()
                            sleepChapter = false
                            publish()
                            break
                        }
                    }
                }
            }
        }
        publish()
    }

    private fun currentNav() = AudioPositions.current(nav, player.currentMediaItemIndex, player.currentPosition)

    private fun publish(error: String? = null, loading: Long? = null) {
        session?.setSessionExtras(
            Bundle().apply {
                putLong(KEY_BOOK, loading ?: book?.id ?: -1)
                putLong(KEY_SLEEP_AT, sleepAt)
                putBoolean(KEY_SLEEP_CHAPTER, sleepChapter)
                putBoolean(KEY_SKIP_SILENCE, skipSilence)
                error?.let { putString(KEY_ERROR, it) }
            },
        )
    }

    // ---------------------------------------------------------------- session

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_LOAD, Bundle.EMPTY))
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                .add(SessionCommand(CMD_SKIP_SILENCE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SKIP_INTERVALS, Bundle.EMPTY))
                .add(SessionCommand(CMD_BOOKMARKS, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_LOAD -> load(args.getLong(KEY_BOOK, -1), args.getBoolean(KEY_PLAY, false))
                CMD_SLEEP -> sleep(args.getInt(KEY_MINUTES, 0))
                CMD_SKIP_SILENCE -> {
                    skipSilence = args.getBoolean(KEY_ENABLED, false)
                    player.skipSilenceEnabled = skipSilence
                    saveSettings()
                    publish()
                }
                CMD_SKIP_INTERVALS -> {
                    settings = settings.copy(
                        skipBackSec = args.getInt(KEY_SKIP_BACK).takeIf { it in AudioSettings.SKIP_BACK_OPTIONS } ?: settings.skipBackSec,
                        skipForwardSec = args.getInt(KEY_SKIP_FORWARD).takeIf { it in AudioSettings.SKIP_FORWARD_OPTIONS } ?: settings.skipForwardSec,
                    )
                    session?.setMediaButtonPreferences(skipButtons())
                    saveSettings()
                }
                CMD_BOOKMARKS -> {
                    settings = settings.copy(bookmarks = AudioSettings.decodeBookmarks(args.getString(KEY_BOOKMARKS)))
                    saveSettings()
                }
                else -> return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        const val CMD_LOAD = "app.yomu.audio.LOAD"
        const val CMD_SLEEP = "app.yomu.audio.SLEEP"
        const val CMD_SKIP_SILENCE = "app.yomu.audio.SKIP_SILENCE"
        const val CMD_SKIP_INTERVALS = "app.yomu.audio.SKIP_INTERVALS"
        const val CMD_BOOKMARKS = "app.yomu.audio.BOOKMARKS"
        const val KEY_SKIP_BACK = "skipBack"
        const val KEY_SKIP_FORWARD = "skipForward"
        const val KEY_BOOKMARKS = "bookmarks"
        const val KEY_ENABLED = "enabled"
        const val KEY_SKIP_SILENCE = "skipSilence"
        const val KEY_BOOK = "book"
        const val KEY_PLAY = "play"
        const val KEY_MINUTES = "minutes"
        const val KEY_SLEEP_AT = "sleepAt"
        const val KEY_SLEEP_CHAPTER = "sleepChapter"
        const val KEY_ERROR = "error"
        const val SLEEP_END_OF_CHAPTER = -1
        private const val SAVE_EVERY_MS = 10_000L
        private const val MIN_SESSION_MS = 15_000L
        private const val SLEEP_FADE_MS = 20_000L
        private const val RETRY_WINDOW_MS = 60_000L
        private const val EXPIRY_MARGIN_MS = 5 * 60_000L
        private const val MIN_BUFFER_MS = 60_000
        private const val MAX_BUFFER_MS = 180_000
        private const val BACK_BUFFER_MS = 30_000
        private const val LOAD_RETRIES = 6
        private val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )

        /** Wait before network retry number [attempt] (from 0): 2, 4, 8, 16, 32 s, then a minute. */
        fun networkBackoff(attempt: Int): Long = (2_000L shl attempt.coerceIn(0, 5)).coerceAtMost(60_000L)

        /** How far to step back when playback resumes after [pausedMs]: nothing for short pauses, up to 30 s after a day. */
        fun rewindAfter(pausedMs: Long): Long = when {
            pausedMs < 60_000L -> 0
            pausedMs < 10 * 60_000L -> 3_000
            pausedMs < 60 * 60_000L -> 10_000
            pausedMs < 24 * 60 * 60_000L -> 20_000
            else -> 30_000
        }
    }
}
