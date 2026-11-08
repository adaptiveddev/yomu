package app.yomu.data.tracking

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.yomu.core.database.TrackEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackProgress
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Tracking (2.0.0): the online library's titles linked to their entries on MyAnimeList, AniList and Shikimori.
 * Reading a chapter moves the entry forward; a change that cannot be sent (offline, error) stays pending and is retried
 * by [TrackSyncWorker].
 */
class TrackerManager(
    private val context: Context?,
    private val db: YomuDatabase,
    val store: TrackerStore,
    keys: TrackerKeys,
    client: () -> OkHttpClient,
    private val settings: AppSettings?,
    val trackers: List<Tracker> = listOf(
        AniListTracker(store, keys, client),
        MyAnimeListTracker(store, keys, client),
        ShikimoriTracker(store, keys, client),
        KitsuTracker(store, keys, client),
        MangaUpdatesTracker(store, keys, client),
    ),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun tracker(service: TrackService): Tracker = trackers.first { it.service == service }

    /** The tracker whose redirect this is (`yomu://anilist-auth…`). */
    fun trackerForRedirect(uri: String): Tracker? = trackers.firstOrNull { !it.passwordLogin && uri.startsWith(it.redirectUri) }

    fun observe(itemId: Long): Flow<List<TrackEntity>> = db.tracks().observe(itemId)
    fun observeBook(bookId: Long): Flow<List<TrackEntity>> = db.tracks().observeBook(bookId)

    /** Highest chapter number read in the title (0 = none). */
    private suspend fun readUpTo(itemId: Long): Float = db.catalog().chapters(itemId).filter { it.read && it.number > 0 }.maxOfOrNull { it.number } ?: 0f

    /**
     * Links the title to [result]. An entry the user already has there is kept; the larger progress wins (Yomu's read
     * chapters or the service's); a new entry starts as reading (or planned when nothing is read yet).
     */
    suspend fun bind(itemId: Long, service: TrackService, result: TrackSearchResult): TrackEntity = mutex.withLock {
        val t = tracker(service)
        val now = System.currentTimeMillis()
        val read = readUpTo(itemId)
        val remote = t.find(result.remoteId)
        var entry = (remote ?: TrackEntry(service, result.remoteId, title = result.title, url = result.url, status = TrackStatus.PLAN_TO_READ))
            .let { it.copy(title = it.title.ifBlank { result.title }, url = it.url.ifBlank { result.url }, totalChapters = it.totalChapters.takeIf { n -> n > 0 } ?: result.totalChapters) }
        TrackProgress.afterReading(entry, read, now)?.let { entry = it }
        val saved = if (remote == null || entry != remote) t.save(entry).withLocal(entry) else entry
        val old = db.tracks().get(itemId, service.id)
        val entity = saved.toEntity(itemId, old?.id ?: 0, now)
        if (old == null) db.tracks().insert(entity) else db.tracks().update(entity)
        db.tracks().get(itemId, service.id)!!
    }

    /** Local files have no reliable service chapter numbering: preserve remote progress, do not infer it from pages. */
    suspend fun bindBook(bookId: Long, service: TrackService, result: TrackSearchResult): TrackEntity = mutex.withLock {
        require(db.books().get(bookId) != null) { "Book not found" }
        val t = tracker(service)
        val remote = t.find(result.remoteId)
        val entry = (remote ?: TrackEntry(service, result.remoteId, title = result.title, url = result.url, status = TrackStatus.PLAN_TO_READ))
            .let { it.copy(title = it.title.ifBlank { result.title }, url = it.url.ifBlank { result.url }, totalChapters = it.totalChapters.takeIf { n -> n > 0 } ?: result.totalChapters) }
        val saved = if (remote == null) t.save(entry).withLocal(entry) else entry
        val old = db.tracks().getBook(bookId, service.id)
        val row = saved.toEntity(null, old?.id ?: 0, System.currentTimeMillis(), bookId)
        if (old == null) db.tracks().insert(row) else db.tracks().update(row)
        db.tracks().getBook(bookId, service.id)!!
    }

    suspend fun refreshBook(bookId: Long) {
        var error: Exception? = null
        db.tracks().forBook(bookId).forEach { track ->
            try { refreshEntry(track) } catch (e: CancellationException) { throw e } catch (e: Exception) { if (error == null) error = e }
        }
        error?.let { throw it }
    }

    /** Serialises edits and sends so an older in-flight response cannot overwrite a newer local change. */
    suspend fun update(track: TrackEntity, entry: TrackEntry): Boolean = mutex.withLock {
        val current = db.tracks().byId(track.id) ?: return@withLock false
        // A full entry from a stale UI could contain placeholder values and overwrite remote state.
        if (current.libraryId == NEEDS_REMOTE_REFRESH) throw TrackerException("Refresh this tracking entry before editing it")
        saveEdit(current, entry)
    }

    /** Apply a UI edit to the latest row, not a stale snapshot captured before another edit. */
    suspend fun edit(track: TrackEntity, transform: (TrackEntry) -> TrackEntry): Boolean = mutex.withLock {
        val current = db.tracks().byId(track.id) ?: return@withLock false
        val ready = resolveImported(current)
        saveEdit(ready, transform(ready.toEntry()))
    }

    private suspend fun saveEdit(track: TrackEntity, entry: TrackEntry): Boolean {
        val local = entry.toEntity(track.itemId, track.id, System.currentTimeMillis(), track.bookId).copy(pending = true)
        db.tracks().update(local)
        return push(local)
    }

    /** Unlinks the title; [removeRemote] also deletes the entry from the user's list on the service. */
    suspend fun unbind(track: TrackEntity, removeRemote: Boolean) = mutex.withLock {
        val current = db.tracks().byId(track.id) ?: return@withLock
        if (removeRemote) TrackService.of(current.service)?.let { tracker(it).delete(current.toEntry()) }
        db.tracks().deleteById(current.id)
    }

    /** Reads the entries from the services again (pending local changes are sent instead); the first error is rethrown. */
    suspend fun refresh(itemId: Long) {
        var error: Exception? = null
        db.tracks().forItem(itemId).forEach { track ->
            try {
                refreshEntry(track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (error == null) error = e
            }
        }
        error?.let { throw it }
    }

    /**
     * Reads every linked entry from the services again (the profile's Tracking tab): one service failing does not stop the
     * others. Returns the number of entries that could not be read and the first error.
     */
    suspend fun refreshAll(): Pair<Int, Exception?> {
        var failed = 0
        var first: Exception? = null
        db.tracks().all().forEach { track ->
            try {
                refreshEntry(track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Tracking refresh")
                failed++
                if (first == null) first = e
            }
        }
        return failed to first
    }

    /** A synced binding is only an identity, not a service account's state. */
    private suspend fun resolveImported(track: TrackEntity): TrackEntity {
        if (track.libraryId != NEEDS_REMOTE_REFRESH) return track
        val service = TrackService.of(track.service) ?: throw TrackerException("Unsupported tracking service")
        val t = tracker(service)
        if (!t.loggedIn()) throw TrackerException("${service.displayName}: sign in and refresh this tracking entry before editing it")
        val remote = t.find(track.remoteId)
        val local = track.toEntry().copy(libraryId = 0)
        val ready = (remote?.withLocal(local) ?: local).toEntity(track.itemId, track.id, System.currentTimeMillis(), track.bookId)
        db.tracks().update(ready)
        return ready
    }

    private suspend fun refreshEntry(snapshot: TrackEntity) = mutex.withLock {
        val track = db.tracks().byId(snapshot.id) ?: return@withLock
        val service = TrackService.of(track.service) ?: return@withLock
        val t = tracker(service)
        if (!t.loggedIn()) return@withLock
        if (track.libraryId == NEEDS_REMOTE_REFRESH) {
            resolveImported(track)
            return@withLock
        }
        if (track.pending) {
            push(track)
            return@withLock
        }
        val remote = t.find(track.remoteId) ?: return@withLock
        val fresh = remote.withLocal(track.toEntry()).toEntity(track.itemId, track.id, track.updatedAt, track.bookId)
        if (fresh != track) db.tracks().update(fresh.copy(updatedAt = System.currentTimeMillis()))
    }

    /** After reading or marking chapters as read: moves the title's entries forward (in the background). */
    fun onChaptersRead(itemId: Long) {
        scope.launch {
            runCatching {
                if (settings != null && !settings.trackAutoUpdate.get()) return@runCatching
                advance(itemId)
            }.onFailure { Timber.w(it, "Tracking update") }
        }
    }

    /**
     * Applies the read chapters to every entry of the title; returns the number of entries changed. One title at a time:
     * two chapters finished in a row must not send the older progress last.
     */
    suspend fun advance(itemId: Long): Int = mutex.withLock {
        val tracks = db.tracks().forItem(itemId)
        if (tracks.isEmpty()) return@withLock 0
        val read = readUpTo(itemId)
        val now = System.currentTimeMillis()
        var changed = 0
        tracks.forEach { track ->
            // Never send default placeholder values over an existing service entry.
            if (track.libraryId == NEEDS_REMOTE_REFRESH && !tracker(TrackService.of(track.service) ?: return@forEach).loggedIn()) return@forEach
            val ready = resolveImported(track)
            val next = TrackProgress.afterReading(ready.toEntry(), read, now) ?: return@forEach
            val local = next.toEntity(itemId, ready.id, now).copy(pending = true)
            db.tracks().update(local)
            push(local)
            changed++
        }
        changed
    }

    /**
     * Sends every pending change; returns how many are still pending on services the user is signed in to. Changes for a
     * signed-out service wait for the sign-in (which sends them), so the background retry does not run for them forever.
     */
    suspend fun pushPending(): Int = mutex.withLock {
        db.tracks().pending().forEach { push(it) }
        db.tracks().pending().count { track -> TrackService.of(track.service)?.let { tracker(it).loggedIn() } == true }
    }

    private suspend fun push(track: TrackEntity): Boolean {
        val service = TrackService.of(track.service) ?: return false
        val t = tracker(service)
        if (!t.loggedIn()) return false
        return try {
            val saved = t.save(track.toEntry())
            db.tracks().update(saved.withLocal(track.toEntry()).toEntity(track.itemId, track.id, System.currentTimeMillis(), track.bookId).copy(pending = false))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerException) {
            if (e.unauthorized) t.logout() else context?.let { TrackSyncWorker.schedule(it) }
            Timber.w(e, "Tracking ${service.displayName}")
            false
        } catch (e: Exception) {
            Timber.w(e, "Tracking ${service.displayName}")
            context?.let { TrackSyncWorker.schedule(it) }
            false
        }
    }

    /** What the service answered, with the values it does not keep (dates on Shikimori, names) from the local entry. */
    private fun TrackEntry.withLocal(local: TrackEntry) = copy(
        title = title.ifBlank { local.title },
        url = url.ifBlank { local.url },
        totalChapters = totalChapters.takeIf { it > 0 } ?: local.totalChapters,
        startedAt = startedAt.takeIf { it > 0 } ?: local.startedAt,
        finishedAt = finishedAt.takeIf { it > 0 } ?: local.finishedAt,
        libraryId = libraryId.takeIf { it > 0 } ?: local.libraryId,
    )

    companion object {
        /** Local-only sentinel: the binding was synced, but its account state has not been fetched. */
        const val NEEDS_REMOTE_REFRESH = -1L

        fun TrackEntity.toEntry() = TrackEntry(
            service = TrackService.of(service) ?: TrackService.ANILIST,
            remoteId = remoteId,
            libraryId = libraryId,
            title = title,
            url = url,
            status = TrackStatus.of(status),
            score = score,
            lastChapterRead = lastChapterRead,
            totalChapters = totalChapters,
            startedAt = startedAt,
            finishedAt = finishedAt,
        )

        fun TrackEntry.toEntity(itemId: Long?, id: Long, now: Long, bookId: Long? = null) = TrackEntity(
            id = id, itemId = itemId, bookId = bookId, service = service.id, remoteId = remoteId, libraryId = libraryId, title = title, url = url,
            status = status.code, score = score, lastChapterRead = lastChapterRead, totalChapters = totalChapters,
            startedAt = startedAt, finishedAt = finishedAt, updatedAt = now,
        )
    }
}

/** Sends tracking changes that failed (no network, service down) once the device is online. */
class TrackSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val trackers: TrackerManager by inject()

    override suspend fun doWork(): Result = runCatching { trackers.pushPending() }.fold({ left -> if (left == 0) Result.success() else Result.retry() }, { Result.retry() })

    companion object {
        private const val NAME = "tracking_sync"

        /** [restart]: start over without the backoff of earlier failures (after signing in). */
        fun schedule(context: Context, restart: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<TrackSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}
