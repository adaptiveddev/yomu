package app.yomu.data.download

import android.content.Context
import app.yomu.core.common.AppError
import app.yomu.core.common.Files
import app.yomu.core.database.DownloadTaskEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.core.network.NetworkClients
import app.yomu.core.network.NetworkMonitor
import app.yomu.data.R
import app.yomu.data.book.Xhtml
import app.yomu.data.catalog.CatalogRepository
import app.yomu.data.catalog.ImageSniffer
import app.yomu.data.catalog.NotAnImageException
import app.yomu.data.catalog.PageFetcher
import app.yomu.data.catalog.UpdateCheckResult
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.data.repository.toDomain
import app.yomu.data.source.SourceCalls
import app.yomu.data.work.DownloadWorker
import app.yomu.domain.catalog.ChapterOrder
import app.yomu.domain.model.DownloadItem
import app.yomu.domain.model.DownloadKind
import app.yomu.domain.model.DownloadStatus
import app.yomu.source.HttpStatusException
import app.yomu.source.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import timber.log.Timber
import java.io.File
import kotlin.random.Random
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class StorageUsage(val books: Long, val chapters: Long, val covers: Long, val cache: Long, val converted: Long) {
    val total: Long get() = books + chapters + covers + cache + converted
}

/** Progress of the queue shown in the notification. */
data class QueueProgress(val title: String, val fraction: Float, val remaining: Int)

/** How a run of the queue ended. [outOfSpace]: the storage is full, the rest of the queue was paused. */
data class QueueOutcome(val done: Int, val failed: Int, val waitingForNetwork: Boolean, val outOfSpace: Boolean = false)

/**
 * Download queue persisted in Room; processed by [DownloadWorker] with pause, resume, HTTP Range resume, automatic
 * retries of transient errors, a per-source limit of parallel downloads and validation of page images.
 */
class DownloadRepository(
    private val context: Context,
    private val db: YomuDatabase,
    private val settings: AppSettings,
    private val net: NetworkClients,
    private val catalog: CatalogRepository,
    private val importer: ImportService,
    private val network: NetworkMonitor? = null,
) {
    private val dao = db.downloads()
    private val claim = Mutex()
    /** Running downloads per source id (guarded by [claim]). */
    private val runningBySource = HashMap<Long, Int>()
    private val headersSerializer = MapSerializer(String.serializer(), String.serializer())

    val booksDir: File get() = File(context.filesDir, "downloads/books").also { it.mkdirs() }
    val chaptersDir: File get() = File(context.filesDir, "downloads/chapters").also { it.mkdirs() }

    fun observe(): Flow<List<DownloadItem>> = dao.observeAll().map { l -> l.map { it.toDomain() } }

    /** Titles of catalog entries, for grouping the queue by title. */
    suspend fun entryTitles(ids: Collection<Long>): Map<Long, String> =
        if (ids.isEmpty()) emptyMap() else db.catalog().items(ids).associate { it.id to it.title }

    suspend fun enqueueBook(title: String, url: String, mimeType: String?, sourceId: Long? = null, headers: Map<String, String> = emptyMap()): Long {
        // A second tap must not start a parallel download of the same file; a failed one is simply restarted.
        dao.activeForUrl(url)?.let { return it.id }
        dao.failedForUrl(url)?.let { failed ->
            dao.restart(listOf(failed.id), System.currentTimeMillis())
            DownloadWorker.start(context, settings.downloadWifiOnly.get())
            return failed.id
        }
        val now = System.currentTimeMillis()
        val id = dao.insert(
            DownloadTaskEntity(
                kind = DownloadKind.BOOK.name, title = title, url = url,
                headersJson = if (headers.isEmpty()) null else Json.encodeToString(headersSerializer, headers),
                status = DownloadStatus.QUEUED.name, bytesDownloaded = 0, bytesTotal = -1, pagesDone = 0, pagesTotal = 0, error = null,
                chapterId = null, itemId = null, sourceId = sourceId, destPath = null, mimeType = mimeType, createdAt = now, updatedAt = now,
            ),
        )
        DownloadWorker.start(context, settings.downloadWifiOnly.get())
        return id
    }

    /**
     * Queues chapters of [entryId] in the given order (pass them in reading order). Failed or paused downloads of these
     * chapters are restarted, chapters whose files disappeared are downloaded again. [top] puts them before the rest of
     * the queue. Returns the number of chapters queued.
     */
    suspend fun enqueueChapters(entryId: Long, chapterIds: List<Long>, top: Boolean = false): Int {
        if (chapterIds.isEmpty()) return 0
        val entry = catalog.entry(entryId) ?: return 0
        val chapters = catalog.chapters(entryId).associateBy { it.id }
        val pending = dao.pendingForChapters(chapterIds).associateBy { it.chapterId }
        val now = System.currentTimeMillis()
        val priority = if (top) dao.maxPriority() + 1 else 0
        val restart = mutableListOf<Long>()
        var queued = 0
        chapterIds.distinct().forEach { cid ->
            val ch = chapters[cid] ?: return@forEach
            if (ch.downloaded) {
                if (ch.localPath?.let { File(it).exists() } == true) return@forEach
                db.catalog().setDownloaded(cid, false, null) // files deleted outside the app
            }
            val task = pending[cid]
            when (task?.status) {
                DownloadStatus.QUEUED.name, DownloadStatus.RUNNING.name -> Unit
                DownloadStatus.PAUSED.name, DownloadStatus.FAILED.name -> {
                    restart += task.id
                    queued++
                }
                else -> {
                    dao.insert(
                        DownloadTaskEntity(
                            kind = DownloadKind.CHAPTER.name, title = "${entry.title} — ${ch.name}", url = ch.url, headersJson = null,
                            status = DownloadStatus.QUEUED.name, bytesDownloaded = 0, bytesTotal = -1, pagesDone = 0, pagesTotal = 0, error = null,
                            chapterId = cid, itemId = entryId, sourceId = entry.sourceId, destPath = null, mimeType = null,
                            createdAt = now, updatedAt = now, priority = priority,
                        ),
                    )
                    queued++
                }
            }
        }
        if (restart.isNotEmpty()) {
            dao.restart(restart, now)
            if (top) dao.setPriority(restart, priority, now)
        }
        if (queued > 0) DownloadWorker.start(context, settings.downloadWifiOnly.get())
        return queued
    }

    /**
     * Queues the new chapters found by an update check when "Download new chapters" is on: one release per chapter
     * (the group the user reads), without excluded groups and without chapters already read or downloaded in another
     * release. Returns the number of chapters queued.
     */
    suspend fun autoDownloadNew(result: UpdateCheckResult): Int {
        if (!settings.autoDownloadNew.get()) return 0
        if (savingData()) {
            Timber.i("Data saver: new chapters are not downloaded over mobile data")
            return 0
        }
        var total = 0
        result.updated.forEach { update ->
            runCatching {
                val excluded = catalog.chapterDisplay(update.entryId).excludedScanlators
                val all = catalog.chapters(update.entryId).filter { it.scanlator.isNullOrBlank() || it.scanlator !in excluded }
                val reading = ChapterOrder.reading(all)
                val preferred = ChapterOrder.preferredScanlator(reading)
                val fresh = update.newChapterIds.toHashSet()
                val picks = ChapterOrder.slots(reading)
                    .filter { slot -> slot.any { it.id in fresh } && slot.none { it.read || it.downloaded } }
                    .map { slot -> ChapterOrder.pick(slot.filter { it.id in fresh }, preferred) }
                total += enqueueChapters(update.entryId, picks.map { it.id })
            }.onFailure { Timber.w(it, "Auto download of ${update.title} failed") }
        }
        return total
    }

    /**
     * Queues every unread chapter of [entryId] that is not downloaded: one release per chapter (the group the user
     * reads), groups hidden by the chapter filter skipped. Returns the number of chapters queued.
     */
    suspend fun enqueueUnread(entryId: Long): Int {
        val excluded = catalog.chapterDisplay(entryId).excludedScanlators
        val reading = ChapterOrder.reading(catalog.chapters(entryId).filter { it.scanlator.isNullOrBlank() || it.scanlator !in excluded })
        val preferred = ChapterOrder.preferredScanlator(reading)
        val picks = ChapterOrder.slots(reading).filter { slot -> slot.none { it.read || it.downloaded } }.map { ChapterOrder.pick(it, preferred).id }
        return enqueueChapters(entryId, picks)
    }

    /**
     * "Download ahead" while reading [chapterId] of a library title: keeps the next [count] chapters (setting by default)
     * downloaded or queued. Returns the number of chapters queued.
     */
    suspend fun ensureAhead(entryId: Long, chapterId: Long, count: Int? = null): Int {
        val n = count ?: settings.downloadAhead.get()
        if (n <= 0) return 0
        // The data saver keeps automatic downloads for Wi-Fi; they catch up when a chapter is opened there.
        if (count == null && savingData()) return 0
        val entry = catalog.entry(entryId) ?: return 0
        if (!entry.favorite) return 0
        val excluded = catalog.chapterDisplay(entryId).excludedScanlators
        val reading = ChapterOrder.reading(catalog.chapters(entryId).filter { it.id == chapterId || it.scanlator.isNullOrBlank() || it.scanlator !in excluded })
        val next = ChapterOrder.upcoming(reading, chapterId, n, skipDownloaded = false).map { it.id }
        if (next.isEmpty()) return 0
        val pending = dao.pendingForChapters(next).filter { it.status != DownloadStatus.FAILED.name }.mapNotNullTo(HashSet()) { it.chapterId }
        return enqueueChapters(entryId, next.filter { it !in pending })
    }

    /** True while "Save mobile data" is on and the network is metered: automatic downloads wait for Wi-Fi. */
    private suspend fun savingData(): Boolean = settings.dataSaver.get() && network?.isMetered == true

    suspend fun pause(ids: Collection<Long>) = dao.setStatus(ids, DownloadStatus.PAUSED.name, null, System.currentTimeMillis())

    /** Resumes paused downloads and retries failed ones (with a fresh retry budget). */
    suspend fun resume(ids: Collection<Long>) {
        dao.restart(ids, System.currentTimeMillis())
        DownloadWorker.start(context, settings.downloadWifiOnly.get())
    }

    suspend fun cancel(ids: Collection<Long>) {
        ids.forEach { id -> dao.get(id)?.let { cleanUp(it) } }
        dao.setStatus(ids, DownloadStatus.CANCELLED.name, null, System.currentTimeMillis())
    }

    /** Removes every waiting, paused and failed download (running ones are cancelled) and their partial files. */
    suspend fun clearQueue() {
        val tasks = dao.withStatus(listOf(DownloadStatus.QUEUED.name, DownloadStatus.RUNNING.name, DownloadStatus.PAUSED.name, DownloadStatus.FAILED.name))
        tasks.forEach { cleanUp(it) }
        dao.setStatus(tasks.map { it.id }, DownloadStatus.CANCELLED.name, null, System.currentTimeMillis())
        dao.delete(tasks.map { it.id })
    }

    /** Moves downloads before the rest of the queue. */
    suspend fun moveToTop(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        dao.setPriority(ids, dao.maxPriority() + 1, System.currentTimeMillis())
    }

    suspend fun retryFailed() {
        val failed = dao.idsWithStatus(DownloadStatus.FAILED.name)
        if (failed.isNotEmpty()) resume(failed)
    }

    /** Paused by the user: messages of earlier attempts are dropped (an automatic pause keeps its reason, see [runQueue]). */
    suspend fun pauseAll() =
        dao.moveAllWithError(listOf(DownloadStatus.QUEUED.name, DownloadStatus.RUNNING.name), DownloadStatus.PAUSED.name, null, System.currentTimeMillis())

    suspend fun resumeAll() {
        val ids = dao.idsWithStatus(DownloadStatus.PAUSED.name) + dao.idsWithStatus(DownloadStatus.FAILED.name)
        if (ids.isNotEmpty()) dao.restart(ids, System.currentTimeMillis())
        DownloadWorker.start(context, settings.downloadWifiOnly.get())
    }

    suspend fun clearFinished() = dao.clearFinished()

    /** True when something is queued or running — the worker skips its notification otherwise. */
    suspend fun hasWork(): Boolean = dao.activeCount() > 0
    suspend fun remove(ids: Collection<Long>) = dao.delete(ids)

    /**
     * Called by the worker: processes the queue until it is empty with up to "parallel downloads" jobs, at most
     * [PER_SOURCE_LIMIT] of them for one source. Stops early when the device goes offline (tasks stay queued) or the
     * storage is full (the queue is paused).
     */
    suspend fun runQueue(onProgress: suspend (QueueProgress) -> Unit): QueueOutcome = coroutineScope {
        dao.moveAll(listOf(DownloadStatus.RUNNING.name), DownloadStatus.QUEUED.name, System.currentTimeMillis())
        claim.withLock { runningBySource.clear() }
        val done = AtomicInteger()
        val failed = AtomicInteger()
        val offline = AtomicBoolean(false)
        val noSpace = AtomicBoolean(false)
        val stop = { offline.get() || noSpace.get() }
        try {
            do {
                val parallel = settings.maxParallelDownloads.get().coerceIn(1, 5)
                List(parallel) { launch(Dispatchers.IO) { work(onProgress, done, failed, offline, noSpace, stop) } }.joinAll()
                // Tasks queued while the last jobs were finishing are picked up here instead of waiting for the next start.
            } while (isActive && !stop() && dao.nextQueued(1).isNotEmpty())
        } finally {
            withContext(NonCancellable) {
                // A full storage would fail every next task the same way: the queue is paused with the reason and kept for "Resume all".
                if (noSpace.get()) {
                    val reason = context.getString(R.string.download_error_space)
                    dao.moveAllWithError(listOf(DownloadStatus.RUNNING.name, DownloadStatus.QUEUED.name), DownloadStatus.PAUSED.name, reason, System.currentTimeMillis())
                } else {
                    dao.moveAll(listOf(DownloadStatus.RUNNING.name), DownloadStatus.QUEUED.name, System.currentTimeMillis())
                }
            }
        }
        QueueOutcome(done.get(), failed.get(), offline.get() && !noSpace.get(), noSpace.get())
    }

    private suspend fun CoroutineScope.work(
        onProgress: suspend (QueueProgress) -> Unit,
        done: AtomicInteger,
        failed: AtomicInteger,
        offline: AtomicBoolean,
        noSpace: AtomicBoolean,
        stop: () -> Boolean,
    ) {
        while (isActive && !stop()) {
            val task = claimNext()
            if (task == null) {
                // Nothing to start: finished when the queue is empty, otherwise every waiting task belongs to a busy source.
                if (dao.nextQueued(1).isEmpty()) return
                delay(SLOT_WAIT_MS)
                continue
            }
            try {
                process(task) { title, fraction -> onProgress(QueueProgress(title, fraction, dao.activeCount())) }
                if (dao.get(task.id)?.status == DownloadStatus.DONE.name) done.incrementAndGet()
            } catch (e: CancellationException) {
                ensureActive() // the worker was stopped; otherwise a source's own timeout/cancellation is a failed attempt
                onFailure(task, e, failed, offline, noSpace)
            } catch (e: Throwable) {
                onFailure(task, e, failed, offline, noSpace)
            } finally {
                withContext(NonCancellable) { release(task) }
            }
        }
    }

    private suspend fun claimNext(): DownloadTaskEntity? = claim.withLock {
        val busy = runningBySource.filterValues { it >= PER_SOURCE_LIMIT }.keys
        val task = dao.nextQueuedExcluding(busy) ?: return@withLock null
        dao.setStatus(listOf(task.id), DownloadStatus.RUNNING.name, null, System.currentTimeMillis())
        task.sourceId?.let { runningBySource[it] = (runningBySource[it] ?: 0) + 1 }
        task
    }

    private suspend fun release(task: DownloadTaskEntity) = claim.withLock {
        task.sourceId?.let { id -> runningBySource[id] = ((runningBySource[id] ?: 1) - 1).coerceAtLeast(0) }
    }

    private suspend fun onFailure(task: DownloadTaskEntity, e: Throwable, failed: AtomicInteger, offline: AtomicBoolean, noSpace: AtomicBoolean) {
        Timber.w(e, "Download failed: ${task.title}")
        val current = dao.get(task.id) ?: return
        if (current.status != DownloadStatus.RUNNING.name) return // paused or cancelled meanwhile
        val message = describe(e)
        val kind = RetryPolicy.classify(e)
        val now = System.currentTimeMillis()
        when {
            kind == FailureKind.NO_SPACE -> {
                // Not the task's fault either: it is paused with the reason, and so is the rest of the queue.
                dao.setStatus(listOf(task.id), DownloadStatus.PAUSED.name, message, now)
                noSpace.set(true)
            }
            network?.isOnline == false -> {
                // Not the task's fault: it waits in the queue and the worker retries when the network is back.
                dao.requeue(task.id, current.attempts, message, now)
                offline.set(true)
            }
            RetryPolicy.shouldRetry(kind, current.attempts, settings.downloadRetries.get().coerceIn(0, 10)) -> {
                // The reason stays visible in the queue while the task waits for its next attempt.
                dao.setStatus(listOf(task.id), DownloadStatus.RUNNING.name, message, now)
                delay(RetryPolicy.delayMs(RETRY_BASE_MS, current.attempts, Random.nextDouble()))
                if (dao.get(task.id)?.status == DownloadStatus.RUNNING.name) dao.requeue(task.id, current.attempts + 1, message, System.currentTimeMillis())
            }
            else -> {
                dao.setStatus(listOf(task.id), DownloadStatus.FAILED.name, message, now)
                failed.incrementAndGet()
            }
        }
    }

    /** Short, translated reason shown in the queue. */
    private fun describe(e: Throwable): String = when {
        e is NotAnImageException -> context.getString(R.string.download_error_not_image)
        e is UnexpectedContentException -> context.getString(R.string.download_error_not_file)
        e is NoPagesException -> context.getString(R.string.download_error_no_pages)
        e is kotlinx.coroutines.TimeoutCancellationException -> context.getString(R.string.download_error_timeout)
        else -> when (val a = AppError.from(e)) {
            is AppError.NoNetwork -> context.getString(R.string.download_error_network)
            is AppError.Timeout -> context.getString(R.string.download_error_timeout)
            is AppError.Http -> context.getString(R.string.download_error_http, a.code)
            is AppError.NeedsWebView -> context.getString(R.string.download_error_webview)
            is AppError.NoSpace -> context.getString(R.string.download_error_space)
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }

    private suspend fun stillRunning(id: Long) = dao.get(id)?.status == DownloadStatus.RUNNING.name

    private fun partFile(t: DownloadTaskEntity) = File(booksDir, "task_${t.id}.part")

    /** ETag or Last-Modified of the file being downloaded, sent as If-Range when the download is continued. */
    private fun validatorFile(t: DownloadTaskEntity) = File(booksDir, "task_${t.id}.validator")

    /** Deletes partial files of a task that will not continue. */
    private suspend fun cleanUp(t: DownloadTaskEntity) {
        partFile(t).delete()
        validatorFile(t).delete()
        if (t.kind == DownloadKind.CHAPTER.name) {
            val chapter = t.chapterId?.let { catalog.chapter(it) } ?: return
            val entry = catalog.entry(chapter.entryId) ?: return
            File(chapterFolder(entry), "${chapterBaseName(chapter)}.tmp").deleteRecursively()
            File(chapterFolder(entry), "${chapterBaseName(chapter)}.cbz.part").delete()
        }
    }

    private fun chapterFolder(entry: app.yomu.domain.model.CatalogEntry) = File(chaptersDir, "${entry.sourceId}/${Files.sanitize(entry.title)}")

    /** Stable file name of a chapter (the chapter id keeps releases with the same name apart, also after list changes). */
    private fun chapterBaseName(chapter: app.yomu.domain.model.ChapterInfo) = Files.sanitize("${chapter.name.take(80)} [${chapter.id}]")

    private suspend fun process(t: DownloadTaskEntity, onProgress: suspend (String, Float) -> Unit) {
        when (t.kind) {
            DownloadKind.BOOK.name -> downloadBook(t, onProgress)
            DownloadKind.CHAPTER.name -> downloadChapter(t, onProgress)
        }
    }

    private suspend fun downloadBook(t: DownloadTaskEntity, onProgress: suspend (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        val part = partFile(t)
        val start = if (part.exists()) part.length() else 0L
        val validatorFile = validatorFile(t)
        val validator = if (start > 0) runCatching { validatorFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() } else null
        val headers = t.headersJson?.let { runCatching { Json.decodeFromString(headersSerializer, it) }.getOrNull() }.orEmpty()
        val request = Request.Builder().url(t.url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (start > 0) {
                header("Range", "bytes=$start-")
                validator?.let { header("If-Range", it) }
            }
        }.build()
        net.downloadClient.newCall(request).await().use { r ->
            if (r.code == 416 && start > 0) {
                // The partial file does not match the server's file any more: start over.
                part.delete()
                validatorFile.delete()
                throw java.io.IOException("Range not satisfiable, restarting")
            }
            if (!r.isSuccessful) throw HttpStatusException(r.code, t.url)
            val append = RangeResume.canAppend(start, r.code, r.header("Content-Range"))
            if (r.code == 206 && !append) {
                part.delete()
                throw java.io.IOException("Server resumed at another position, restarting")
            }
            RangeResume.ifRange(r.header("ETag"), r.header("Last-Modified"))?.let { validatorFile.writeText(it) } ?: validatorFile.delete()
            val base = if (append) start else 0L
            val length = r.body.contentLength()
            val total = if (length > 0) base + length else -1L
            val disposition = r.header("Content-Disposition")?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(it)?.groupValues?.get(1) }
            val contentType = r.body.contentType()?.toString()
            val ext = extensionFor(t.mimeType ?: contentType, disposition ?: t.url)
            FileOutputStream(part, append).use { out ->
                val input = r.body.byteStream()
                val buf = ByteArray(Files.BUFFER)
                var done = base
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > REPORT_MS) {
                        lastReport = now
                        dao.progress(t.id, done, total, 0, 0, now)
                        onProgress(t.title, if (total > 0) done.toFloat() / total else 0f)
                        if (!stillRunning(t.id)) return@withContext
                    }
                }
                dao.progress(t.id, done, total, 0, 0, System.currentTimeMillis())
            }
            if (total > 0 && part.length() < total) throw java.io.IOException("Connection closed after ${part.length()} of $total bytes")
            // A sign-in or error page instead of the book: importing it would add a broken "book" to the library.
            val expectsText = ext == ".html" || ext == ".txt" || ext == ".fb2" || "html" in t.mimeType.orEmpty()
            if (!expectsText && contentType?.startsWith("text/html") == true && ImageSniffer.looksLikeText(ImageSniffer.head(part))) {
                part.delete()
                throw UnexpectedContentException("HTML instead of ${t.mimeType ?: ext}")
            }
            val name = Files.sanitize(disposition?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: (t.title + ext))
            // Always a fresh file: an existing library book may already point to a file with the same name.
            val dest = uniqueFile(booksDir, if (name.contains('.')) name else name + ext)
            validatorFile.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = false)
                part.delete()
            }
            val finalPath = when (val outcome = importer.importLocalFile(dest, dest.name)) {
                is ImportOutcome.Failed -> {
                    dest.delete()
                    throw outcome.error
                }
                // Same book already in the library: keep its file, or hand it this one if its file was lost.
                is ImportOutcome.Duplicate -> importer.adoptFileForDuplicate(outcome.bookId, dest) ?: dest.path
                is ImportOutcome.Added -> dest.path
            }
            dao.get(t.id)?.let { dao.update(it.copy(status = DownloadStatus.DONE.name, destPath = finalPath, error = null, updatedAt = System.currentTimeMillis())) }
        }
    }

    private suspend fun downloadChapter(t: DownloadTaskEntity, onProgress: suspend (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        val chapterId = t.chapterId ?: error("No chapter")
        val chapter = catalog.chapter(chapterId) ?: error("Chapter removed")
        val entry = catalog.entry(chapter.entryId) ?: error("Entry removed")
        val folder = chapterFolder(entry).also { it.mkdirs() }
        val baseName = chapterBaseName(chapter)
        if (entry.type == app.yomu.domain.model.ContentType.NOVEL) {
            val html = catalog.novelContent(chapterId)
            val file = File(folder, "$baseName.html")
            file.writeText(Xhtml.page(chapter.name, Xhtml.fromFragment(html), null, css = ""))
            db.catalog().setDownloaded(chapterId, true, file.path)
            dao.get(t.id)?.let { dao.update(it.copy(status = DownloadStatus.DONE.name, destPath = file.path, pagesDone = 1, pagesTotal = 1, error = null, updatedAt = System.currentTimeMillis())) }
            return@withContext
        }
        val (source, pages) = try {
            catalog.pages(chapterId)
        } catch (e: app.yomu.source.SourceException) {
            if (e.cause == null && e.message?.startsWith("The source returned no pages") == true) throw NoPagesException(e.message.orEmpty()) else throw e
        }
        val tmp = File(folder, "$baseName.tmp").also { it.mkdirs() }
        val width = maxOf(3, pages.size.toString().length)
        val client = net.downloadClient
        // Several pages at a time (the setting); a page that fails cancels the others and fails the chapter.
        val gate = Semaphore(settings.pageDownloadThreads.get().coerceIn(1, MAX_PAGE_THREADS))
        val saved = AtomicInteger()
        val reportLock = Mutex()
        var reported = 0
        val stopped = AtomicBoolean(false)
        coroutineScope {
            pages.forEachIndexed { i, page ->
                launch {
                    gate.withPermit {
                        if (stopped.get()) return@withPermit
                        val prefix = "%0${width}d.".format(i)
                        val existing = tmp.listFiles { f -> f.name.startsWith(prefix) && !f.name.endsWith(".part") }?.firstOrNull()
                        if (existing == null || existing.length() == 0L) {
                            val raw = File(tmp, "${prefix}img")
                            val ext = PageFetcher.fetch(context, client, source, page, raw)
                            val named = File(tmp, prefix + ext)
                            if (!raw.renameTo(named)) { raw.copyTo(named, overwrite = true); raw.delete() }
                        }
                        val n = saved.incrementAndGet()
                        reportLock.withLock {
                            if (n > reported) {
                                reported = n
                                dao.progress(t.id, 0, -1, n, pages.size, System.currentTimeMillis())
                                onProgress(t.title, n.toFloat() / pages.size)
                            }
                            // Paused or cancelled meanwhile: the pages in flight are dropped.
                            if (!stopped.get() && !stillRunning(t.id)) {
                                stopped.set(true)
                                this@coroutineScope.coroutineContext.cancelChildren()
                            }
                        }
                    }
                }
            }
        }
        if (stopped.get()) return@withContext
        val files = tmp.listFiles { f -> !f.name.endsWith(".part") }.orEmpty().sortedBy { it.name }
        if (files.size < pages.size) throw java.io.IOException("Only ${files.size} of ${pages.size} pages saved")
        val path = if (settings.packCbz.get()) {
            val cbz = File(folder, "$baseName.cbz")
            val partCbz = File(folder, "$baseName.cbz.part")
            try {
                ZipOutputStream(partCbz.outputStream().buffered()).use { zip ->
                    files.forEach { f ->
                        zip.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("ComicInfo.xml"))
                    zip.write(comicInfo(entry.title, chapter.name, chapter.number, entry.author, entry.description, pages.size).toByteArray())
                    zip.closeEntry()
                }
            } catch (e: Throwable) {
                partCbz.delete() // a half-written archive must not survive a full disk or a stop
                throw e
            }
            cbz.delete()
            if (!partCbz.renameTo(cbz)) { partCbz.copyTo(cbz, overwrite = true); partCbz.delete() }
            tmp.deleteRecursively()
            cbz.path
        } else {
            val dir = File(folder, baseName)
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) { tmp.copyRecursively(dir, overwrite = true); tmp.deleteRecursively() }
            dir.path
        }
        db.catalog().setDownloaded(chapterId, true, path)
        dao.get(t.id)?.let { dao.update(it.copy(status = DownloadStatus.DONE.name, destPath = path, error = null, updatedAt = System.currentTimeMillis())) }
    }

    private fun comicInfo(series: String, title: String, number: Float, writer: String?, summary: String?, pages: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
        append("<Title>${Xhtml.escape(title)}</Title>\n<Series>${Xhtml.escape(series)}</Series>\n")
        if (number >= 0) append("<Number>${if (number % 1f == 0f) number.toInt().toString() else number.toString()}</Number>\n")
        writer?.let { append("<Writer>${Xhtml.escape(it)}</Writer>\n") }
        summary?.let { append("<Summary>${Xhtml.escape(it)}</Summary>\n") }
        append("<PageCount>$pages</PageCount>\n<Manga>YesAndRightToLeft</Manga>\n</ComicInfo>\n")
    }

    /** Deletes the downloaded files of read chapters of [entryId] (all titles when null). Returns how many were deleted. */
    suspend fun deleteReadDownloads(entryId: Long? = null): Int {
        val ids = db.catalog().downloadedRead().filter { entryId == null || it.itemId == entryId }.map { it.id }
        deleteChapterFiles(ids)
        return ids.size
    }

    /** Deletes all downloaded chapters of [entryId] and drops its waiting downloads. Returns how many were deleted. */
    suspend fun deleteAllDownloads(entryId: Long): Int {
        val pending = dao.withStatus(listOf(DownloadStatus.QUEUED.name, DownloadStatus.PAUSED.name, DownloadStatus.FAILED.name)).filter { it.itemId == entryId }
        pending.forEach { cleanUp(it) }
        dao.delete(pending.map { it.id })
        val ids = catalog.chapters(entryId).filter { it.downloaded }.map { it.id }
        deleteChapterFiles(ids)
        return ids.size
    }

    /** Deletes downloaded files of chapters (used manually and by auto delete after reading). */
    suspend fun deleteChapterFiles(chapterIds: Collection<Long>) {
        chapterIds.forEach { id ->
            val c = catalog.chapter(id) ?: return@forEach
            c.localPath?.let { File(it).let { f -> if (f.isDirectory) f.deleteRecursively() else f.delete() } }
            db.catalog().setDownloaded(id, false, null)
        }
    }

    suspend fun onChapterRead(chapterId: Long) {
        if (settings.autoDeleteRead.get()) deleteChapterFiles(listOf(chapterId))
    }

    fun storageUsage(): StorageUsage = StorageUsage(
        books = Files.dirSize(File(context.filesDir, "books")) + Files.dirSize(booksDir),
        chapters = Files.dirSize(chaptersDir),
        covers = Files.dirSize(File(context.filesDir, "covers")),
        cache = Files.dirSize(context.cacheDir),
        converted = Files.dirSize(File(context.filesDir, "converted")),
    )

    fun clearCache() {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        const val REPORT_MS = 400L

        /** Parallel downloads from one source: more would mostly trigger rate limits and bans. */
        const val PER_SOURCE_LIMIT = 2

        /** Upper bound of the "pages of a chapter at once" setting. */
        const val MAX_PAGE_THREADS = 6

        /** Check interval of a job waiting for a busy source. */
        const val SLOT_WAIT_MS = 1_500L

        /** First pause before an automatic retry (doubled for every further attempt). */
        const val RETRY_BASE_MS = 5_000L

        /** [name] in [dir], or "name (1).ext", "name (2).ext"… when taken. Long names are shortened (255-byte limit). */
        fun uniqueFile(dir: File, name: String): File {
            val clean = Files.sanitize(name).ifBlank { "book" }
            val dot = clean.indexOf('.', startIndex = (clean.length - 9).coerceAtLeast(1)).takeIf { it > 0 } ?: clean.lastIndexOf('.')
            var base = if (dot > 0) clean.substring(0, dot) else clean
            val ext = if (dot > 0) clean.substring(dot) else ""
            while (base.toByteArray().size + ext.length > 200 && base.length > 8) base = base.dropLast(4)
            var f = File(dir, base + ext)
            var i = 1
            while (f.exists()) f = File(dir, "$base (${i++})$ext")
            return f
        }

        fun extensionFor(mime: String?, name: String): String {
            val m = mime.orEmpty().lowercase()
            val fromName = name.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return when {
                "epub" in m -> ".epub"
                "pdf" in m -> ".pdf"
                "fb2" in m || "fictionbook" in m -> if ("zip" in m) ".fb2.zip" else ".fb2"
                "mobipocket" in m || "x-mobi" in m -> ".mobi"
                "cbz" in m || "comicbook+zip" in m -> ".cbz"
                "cbr" in m -> ".cbr"
                "text/plain" in m -> ".txt"
                "audio/mpeg" in m -> ".mp3"
                "audio/mp4" in m || "audio/x-m4b" in m -> if (fromName == "m4b") ".m4b" else ".m4a"
                "audio/ogg" in m -> ".ogg"
                "audio/flac" in m -> ".flac"
                "application/zip" in m -> ".zip"
                "html" in m && fromName !in setOf("epub", "pdf", "fb2", "mobi") -> ".html"
                fromName.isNotEmpty() && fromName.length <= 5 -> ".$fromName"
                else -> ".epub"
            }
        }
    }
}

/** A web page (often a sign-in page) was served instead of the requested book file. */
class UnexpectedContentException(message: String) : Exception(message)

/** The source listed no pages for a chapter. */
class NoPagesException(message: String) : Exception(message)
