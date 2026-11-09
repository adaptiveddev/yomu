package app.yomu.feature.audio

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.yomu.core.network.NetworkClients
import app.yomu.data.audio.AudiobookStore
import app.yomu.domain.model.AudioNavEntry
import app.yomu.domain.model.AudioPositions
import app.yomu.domain.model.AudioTrack
import app.yomu.domain.model.AudiobookManifest
import app.yomu.domain.model.Book
import app.yomu.domain.model.ReadingStatus
import app.yomu.domain.repository.BookRepository
import app.yomu.domain.repository.StatsRepository
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    private var retriedIndex = -1
    private var pausedAt = 0L

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
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(withHeaders))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        // Video of YouTube streams is never shown: do not decode it.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        player.addListener(listener)
        session = MediaSession.Builder(this, player).setCallback(callback).build()
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
            return
        }
        savePosition(blocking = false)
        endListening()
        loadJob?.cancel()
        loadJob = scope.launch {
            publish(error = null, loading = bookId)
            runCatching {
                val b = withContext(Dispatchers.IO) { books.getBook(bookId) } ?: error("Book $bookId not found")
                val list = store.tracks(b)
                val saved = withContext(Dispatchers.IO) { books.getProgress(bookId) }
                val start = saved?.chapter?.coerceIn(0, list.lastIndex) ?: 0
                val position = saved?.let(AudioPositions::positionMs) ?: 0L
                book = b
                tracks = list
                nav = AudioPositions.navigation(list)
                durations.clear()
                list.forEachIndexed { i, t -> if (t.durationMs > 0) durations[i] = t.durationMs }
                resolved.clear()
                retriedIndex = -1
                pausedAt = 0
                var startError: Throwable? = null
                val items = list.mapIndexed { i, t ->
                    val stream = if (i == start) runCatching { streamFor(i, t, eager = true) }.onFailure { startError = it }.getOrNull() else streamFor(i, t, eager = false)
                    item(b, i, t, stream)
                }
                player.setMediaItems(items, start, position)
                player.skipSilenceEnabled = skipSilenceOf(b)
                player.setPlaybackParameters(PlaybackParameters(speedOf(b)))
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

    /** A resolved stream for YouTube tracks (only for the start track unless [eager]); other tracks play their own address. */
    private suspend fun streamFor(index: Int, track: AudioTrack, eager: Boolean): Pair<String, String?>? {
        val playable = AudiobookStore.playableUri(track)
        if (playable != track.uri) {
            // A downloaded copy: nothing to resolve.
            resolved += index
            return playable to null
        }
        val id = AudiobookManifest.youtubeId(track.uri) ?: return track.uri to null
        if (!eager) return null
        val s = store.youtube.stream(id)
        s.headers.takeIf { it.isNotEmpty() }?.let { streamHeaders["googlevideo.com"] = it; streamHeaders["youtube.com"] = it }
        resolved += index
        return s.url to if (s.isHls) MimeTypes.APPLICATION_M3U8 else s.mimeType
    }

    private fun item(b: Book, index: Int, t: AudioTrack, stream: Pair<String, String?>?): MediaItem {
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
            .setUri(stream?.first ?: t.uri)
            .setMimeType(stream?.second)
            .setMediaMetadata(metadata)
            .build()
    }

    /** Resolves the current YouTube track (when the user jumped to it) and the next one before it is needed. */
    private fun resolveAhead() {
        val b = book ?: return
        val current = player.currentMediaItemIndex
        listOf(current, current + 1).filter { it in tracks.indices && it !in resolved && AudiobookManifest.youtubeId(tracks[it].uri) != null }.forEach { i ->
            scope.launch {
                runCatching { streamFor(i, tracks[i], eager = true) }
                    .onSuccess { stream ->
                        if (book?.id != b.id || stream == null) return@onSuccess
                        val isCurrent = player.currentMediaItemIndex == i
                        val position = player.currentPosition
                        val wasPlaying = player.playWhenReady
                        player.replaceMediaItem(i, item(b, i, tracks[i], stream))
                        if (isCurrent) {
                            player.seekTo(i, position)
                            player.prepare()
                            player.playWhenReady = wasPlaying
                        }
                    }
                    .onFailure { if (player.currentMediaItemIndex == i) publish(error = it.message ?: "YouTube") }
            }
        }
    }

    /** The book's own player settings (`{"audio": {"speed", "skipSilence"}}` in its reader settings). */
    private fun audioSettings(b: Book): JsonObject? = runCatching {
        Json.parseToJsonElement(b.readerSettingsJson ?: return null).jsonObject["audio"]?.jsonObject
    }.getOrNull()

    private fun speedOf(b: Book): Float = audioSettings(b)?.get("speed")?.jsonPrimitive?.floatOrNull?.coerceIn(0.5f, 3f) ?: 1f

    private fun skipSilenceOf(b: Book): Boolean = audioSettings(b)?.get("skipSilence")?.jsonPrimitive?.booleanOrNull ?: false

    private fun saveAudioSettings() {
        val b = book ?: return
        val json = buildJsonObject {
            put("audio", buildJsonObject {
                put("speed", player.playbackParameters.speed)
                put("skipSilence", player.skipSilenceEnabled)
            })
        }.toString()
        book = b.copy(readerSettingsJson = json)
        scope.launch(Dispatchers.IO) { books.saveReaderSettings(b.id, json) }
    }

    private fun setSkipSilence(enabled: Boolean) {
        player.skipSilenceEnabled = enabled
        saveAudioSettings()
        publish()
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
                book = null
                tracks = emptyList()
                nav = emptyList()
                sleep(0)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retriedIndex = -1
            savePosition(blocking = false)
            resolveAhead()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                val d = player.duration
                if (d > 0) durations[player.currentMediaItemIndex] = d
                // A stream that played may expire again hours later: allow a fresh retry then.
                retriedIndex = -1
            }
            if (state == Player.STATE_ENDED) {
                val b = book ?: return
                scope.launch(Dispatchers.IO) {
                    books.saveProgress(b.id, AudioPositions.locator(tracks.lastIndex.coerceAtLeast(0), 0, 0, 1f, tracks.lastOrNull()?.title))
                    books.setStatus(listOf(b.id), ReadingStatus.FINISHED)
                }
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = saveAudioSettings()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                pausedAt = System.currentTimeMillis()
                return
            }
            // After a long pause the last sentence is gone from memory: go back a few seconds.
            val paused = if (pausedAt > 0) System.currentTimeMillis() - pausedAt else 0L
            pausedAt = 0
            val rewind = when {
                paused >= LONG_PAUSE_MS -> LONG_REWIND_MS
                paused >= SHORT_PAUSE_MS -> SHORT_REWIND_MS
                else -> 0L
            }
            if (rewind > 0 && player.mediaItemCount > 0) player.seekTo((player.currentPosition - rewind).coerceAtLeast(0))
        }

        override fun onPlayerError(error: PlaybackException) {
            val index = player.currentMediaItemIndex
            // Expired or refused YouTube addresses: ask once more for a fresh one, then report.
            if (index in tracks.indices && AudiobookManifest.youtubeId(tracks[index].uri) != null && retriedIndex != index) {
                retriedIndex = index
                resolved -= index
                resolveAhead()
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
        sleepAt = 0
        sleepChapter = false
        when {
            minutes > 0 -> {
                sleepAt = System.currentTimeMillis() + minutes * 60_000L
                sleepJob = scope.launch {
                    delay(minutes * 60_000L)
                    player.pause()
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
                putBoolean(KEY_SKIP_SILENCE, player.skipSilenceEnabled)
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
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_LOAD -> load(args.getLong(KEY_BOOK, -1), args.getBoolean(KEY_PLAY, false))
                CMD_SLEEP -> sleep(args.getInt(KEY_MINUTES, 0))
                CMD_SKIP_SILENCE -> setSkipSilence(args.getBoolean(KEY_ENABLED, false))
                else -> return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        const val CMD_LOAD = "app.yomu.audio.LOAD"
        const val CMD_SLEEP = "app.yomu.audio.SLEEP"
        const val CMD_SKIP_SILENCE = "app.yomu.audio.SKIP_SILENCE"
        const val KEY_ENABLED = "enabled"
        const val KEY_SKIP_SILENCE = "skipSilence"
        const val KEY_BOOK = "book"
        const val KEY_PLAY = "play"
        const val KEY_MINUTES = "minutes"
        const val KEY_SLEEP_AT = "sleepAt"
        const val KEY_SLEEP_CHAPTER = "sleepChapter"
        const val KEY_ERROR = "error"
        const val SLEEP_END_OF_CHAPTER = -1
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
        private const val SAVE_EVERY_MS = 10_000L
        private const val MIN_SESSION_MS = 15_000L
        private const val SHORT_PAUSE_MS = 5 * 60_000L
        private const val LONG_PAUSE_MS = 60 * 60_000L
        private const val SHORT_REWIND_MS = 5_000L
        private const val LONG_REWIND_MS = 20_000L
    }
}
