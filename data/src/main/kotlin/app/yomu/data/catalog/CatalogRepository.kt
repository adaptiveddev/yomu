package app.yomu.data.catalog

import android.content.Context
import androidx.room.withTransaction
import app.yomu.core.database.CatalogItemEntity
import app.yomu.core.database.ChapterEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.core.datastore.SettingsJson
import app.yomu.core.network.NetworkMonitor
import app.yomu.data.book.PageSource
import app.yomu.data.repository.joinList
import app.yomu.data.repository.toDomain
import app.yomu.data.source.SourceCalls
import app.yomu.data.source.SourceHealth
import app.yomu.data.source.SourceHealthTracker
import app.yomu.data.source.SourceManager
import app.yomu.domain.catalog.ChapterDisplay
import app.yomu.domain.model.CatalogEntry
import app.yomu.domain.model.ChapterInfo
import app.yomu.domain.model.ContentType
import app.yomu.source.CatalogSource
import app.yomu.source.ItemsPage
import app.yomu.source.Page
import app.yomu.source.SBookLink
import app.yomu.source.SChapter
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import app.yomu.source.SourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

data class GlobalSearchResult(val sourceId: Long, val sourceName: String, val items: List<SItem>, val error: Throwable? = null, val loading: Boolean = false)

data class HistoryEntry(val chapter: ChapterInfo, val entryTitle: String, val coverUrl: String?, val sourceId: Long, val readAt: Long)

/** New chapters of one title found by the update check. */
data class TitleUpdate(val entryId: Long, val title: String, val newChapterIds: List<Long>)

/** Outcome of [CatalogRepository.checkUpdates]. */
data class UpdateCheckResult(val updated: List<TitleUpdate>, val failed: List<Pair<String, Throwable>>, val checked: Int) {
    val newChapters: Int get() = updated.sumOf { it.newChapterIds.size }
}

class CatalogRepository(
    private val context: Context,
    private val db: YomuDatabase,
    private val sources: SourceManager,
    private val settings: AppSettings,
    private val client: () -> OkHttpClient,
    private val healthTracker: SourceHealthTracker = SourceHealthTracker(),
    private val network: NetworkMonitor? = null,
) {
    private val dao = db.catalog()

    /** Recent failures per source id (see [SourceHealth]). */
    val health: StateFlow<Map<Long, SourceHealth>> get() = healthTracker.state

    fun observeFavorites(): Flow<List<CatalogEntry>> = dao.observeFavorites().map { l ->
        l.map { it.item.toDomain(it.unread, it.downloadedCount, it.lastReadAt ?: 0, it.chapterCount) }
    }

    /** Chapter list sort and filters of [entryId]: its own, or the app default. */
    fun observeChapterDisplay(entryId: Long): Flow<ChapterDisplay> =
        kotlinx.coroutines.flow.combine(dao.observeChapterFlags(entryId).distinctUntilChanged(), settings.chapterDisplayDefault.flow) { json, default ->
            json?.let { runCatching { SettingsJson.decodeFromString(ChapterDisplay.serializer(), it) }.getOrNull() } ?: default
        }

    suspend fun chapterDisplay(entryId: Long): ChapterDisplay =
        dao.get(entryId)?.chapterFlags?.let { runCatching { SettingsJson.decodeFromString(ChapterDisplay.serializer(), it) }.getOrNull() }
            ?: settings.chapterDisplayDefault.get()

    suspend fun setChapterDisplay(entryId: Long, display: ChapterDisplay) =
        dao.setChapterFlags(entryId, SettingsJson.encodeToString(ChapterDisplay.serializer(), display))

    /** Makes [display] the default of every title and drops the titles' own settings (keeping only excluded groups). */
    suspend fun setChapterDisplayDefault(entryId: Long, display: ChapterDisplay) {
        settings.chapterDisplayDefault.set(display.copy(excludedScanlators = emptySet()))
        dao.setChapterFlags(entryId, if (display.excludedScanlators.isEmpty()) null else SettingsJson.encodeToString(ChapterDisplay.serializer(), display))
    }

    /**
     * A call to a source with an overall deadline, a retry on transient errors (timeouts, dropped connections, 429, 5xx)
     * while the device is online, and health tracking for the source list.
     */
    suspend fun <T> sourceCall(sourceId: Long, timeoutMs: Long = SourceCalls.CALL_TIMEOUT_MS, attempts: Int = 2, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                SourceCalls.withDeadline(timeoutMs) {
                    SourceCalls.retrying(attempts, retryIf = { SourceCalls.isTransient(it) && isOnline() }) { block() }
                }.also { healthTracker.success(sourceId, System.currentTimeMillis() - started) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isOnline()) healthTracker.failure(sourceId, e)
                throw e
            }
        }

    private fun isOnline(): Boolean = network?.isOnline ?: true
    fun observeEntry(id: Long): Flow<CatalogEntry?> = dao.observe(id).map { it?.toDomain() }
    fun observeChapters(entryId: Long): Flow<List<ChapterInfo>> = dao.observeChapters(entryId).map { l -> l.map { it.toDomain() } }
    fun observeNewCount(): Flow<Int> = dao.observeNewCount()
    suspend fun entry(id: Long): CatalogEntry? = dao.get(id)?.toDomain()
    suspend fun chapters(entryId: Long): List<ChapterInfo> = dao.chapters(entryId).map { it.toDomain() }
    suspend fun chapter(id: Long): ChapterInfo? = dao.chapter(id)?.toDomain()

    fun observeHistory(): Flow<List<HistoryEntry>> = dao.observeHistory().map { l ->
        l.map { HistoryEntry(it.chapter.toDomain(), it.itemTitle, it.coverUrl, it.sourceId, it.chapter.lastReadAt ?: 0) }
    }

    fun observeUpdates(): Flow<List<HistoryEntry>> = dao.observeUpdates().map { l ->
        l.map { HistoryEntry(it.chapter.toDomain(), it.itemTitle, it.coverUrl, it.sourceId, it.chapter.fetchedAt) }
    }

    suspend fun removeHistory(chapterId: Long) = dao.removeHistory(chapterId)
    suspend fun clearHistory() = dao.clearHistory()

    /** The library tabs (shared with the books since 1.5.0; online titles join them through `catalog_collections`). */
    val tabs = app.yomu.data.library.LibraryTabs(db)

    fun source(id: Long): CatalogSource = sources.get(id) ?: throw SourceException("Source $id is not installed")

    /** Stores (or refreshes) a catalog item seen in a listing and returns its local id. */
    suspend fun upsert(sourceId: Long, item: SItem): Long {
        rememberCoverHeaders(sourceId, item.thumbnailUrl)
        val existing = dao.find(sourceId, item.url)
        val type = when (item.type) {
            SourceContent.MANGA -> ContentType.MANGA
            SourceContent.NOVEL -> ContentType.NOVEL
            SourceContent.BOOK -> ContentType.BOOK
        }
        return if (existing == null) {
            dao.insert(
                CatalogItemEntity(
                    sourceId = sourceId, url = item.url, title = item.title, author = item.author, artist = item.artist,
                    description = item.description, genres = joinList(item.genres), status = item.status, coverUrl = item.thumbnailUrl,
                    type = type.name, favorite = false, categoryId = null, addedAt = 0, lastUpdateAt = 0, initialized = item.initialized,
                    memo = item.memo,
                ),
            )
        } else {
            if (item.initialized || existing.coverUrl == null || (item.memo != null && item.memo != existing.memo)) {
                dao.update(
                    existing.copy(
                        title = item.title.ifBlank { existing.title },
                        author = item.author ?: existing.author,
                        artist = item.artist ?: existing.artist,
                        description = item.description ?: existing.description,
                        genres = if (item.genres.isNotEmpty()) joinList(item.genres) else existing.genres,
                        status = if (item.initialized) item.status else existing.status,
                        coverUrl = item.thumbnailUrl ?: existing.coverUrl,
                        initialized = existing.initialized || item.initialized,
                        memo = item.memo ?: existing.memo,
                    ),
                )
            }
            existing.id
        }
    }

    fun toSItem(e: CatalogItemEntity) = SItem(
        url = e.url, title = e.title, author = e.author, artist = e.artist, description = e.description,
        genres = e.genres.split(',').filter { it.isNotBlank() }, status = e.status, thumbnailUrl = e.coverUrl,
        type = when (e.type) { "NOVEL" -> SourceContent.NOVEL; "BOOK" -> SourceContent.BOOK; else -> SourceContent.MANGA },
        initialized = e.initialized,
        memo = e.memo,
    )

    /** Covers of extension sources often need the source's Referer/User-Agent; the shared client adds them per host. */
    fun rememberCoverHeaders(sourceId: Long, url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http")) return
        val headers = runCatching { sources.get(sourceId)?.imageHeaders() }.getOrNull().orEmpty()
        if (headers.isNotEmpty()) app.yomu.core.network.HostHeaders.registerUrl(url, headers)
    }

    private fun ChapterEntity.toSChapter() = SChapter(url, name, number, uploadDate, scanlator, memo = memo)

    suspend fun sItem(entryId: Long): SItem? = dao.get(entryId)?.let { toSItem(it) }

    /** Fetches details and the chapter list from the source and stores them. Returns the number of new chapters. */
    suspend fun refresh(entryId: Long): Int = refreshNew(entryId).size

    /** Like [refresh], returning the ids of the new chapters (none on the first fetch of a title). */
    suspend fun refreshNew(entryId: Long, timeoutMs: Long = SourceCalls.CALL_TIMEOUT_MS): List<Long> = withContext(Dispatchers.IO) {
        val e = dao.get(entryId) ?: return@withContext emptyList()
        val src = source(e.sourceId)
        // One call: extensions (and MangaDex-like APIs) return details and chapters together.
        val (details, chapters) = sourceCall(e.sourceId, timeoutMs) { src.getDetailsAndChapters(toSItem(e)) }
        upsert(e.sourceId, details.copy(initialized = true))
        if (details.type == SourceContent.BOOK) return@withContext emptyList()
        syncChapters(entryId, chapters, markNew = e.favorite)
    }

    private suspend fun syncChapters(entryId: Long, remote: List<SChapter>, markNew: Boolean): List<Long> = db.withTransaction {
        val localList = dao.chapters(entryId)
        // An empty answer for a title with chapters is a failed page or a source glitch, not "every chapter removed":
        // keeping the list protects read marks and history.
        if (remote.isEmpty() && localList.isNotEmpty()) return@withTransaction emptyList()
        val local = localList.associateBy { it.url }
        val now = System.currentTimeMillis()
        val firstFetch = local.isEmpty()
        val remoteUrls = remote.mapTo(HashSet()) { it.url }
        // Chapters whose address changed (site redesigns): read marks move to the new entry with the same number and group.
        val carry = local.values.filter { it.url !in remoteUrls && it.number >= 0 && (it.read || it.bookmarked || it.lastReadAt != null) }
            .associateBy { it.number to it.scanlator.orEmpty() }
        val inserts = mutableListOf<ChapterEntity>()
        val carriedInsert = mutableListOf<Boolean>()
        val updates = mutableListOf<ChapterEntity>()
        val seen = HashSet<String>()
        remote.forEachIndexed { i, c ->
            if (!seen.add(c.url)) return@forEachIndexed // duplicate entry in the source list
            val old = local[c.url]
            if (old == null) {
                val prev = if (c.number >= 0) carry[c.number to c.scanlator.orEmpty()] else null
                inserts += ChapterEntity(
                    itemId = entryId, url = c.url, name = c.name, number = c.number, scanlator = c.scanlator, uploadDate = c.dateUpload,
                    read = prev?.read ?: false, bookmarked = prev?.bookmarked ?: false, lastPageRead = prev?.lastPageRead ?: 0,
                    downloaded = false, localPath = null, isNew = markNew && !firstFetch && prev == null, sourceOrder = i, fetchedAt = now,
                    lastReadAt = prev?.lastReadAt, memo = c.memo,
                )
                carriedInsert += prev != null
            } else if (old.name != c.name || old.sourceOrder != i || old.scanlator != c.scanlator || old.memo != c.memo ||
                old.uploadDate != c.dateUpload || old.number != c.number
            ) {
                updates += old.copy(name = c.name, sourceOrder = i, scanlator = c.scanlator, number = c.number, memo = c.memo ?: old.memo, uploadDate = c.dateUpload.takeIf { it > 0 } ?: old.uploadDate)
            }
        }
        val ids = if (inserts.isNotEmpty()) dao.insertChapters(inserts) else emptyList()
        if (updates.isNotEmpty()) dao.updateChapters(updates)
        val removed = local.keys - remoteUrls
        val carried = carry.values.mapTo(HashSet()) { it.id }
        // Downloaded chapters stay (their files are still readable); bookmarks stay unless they moved to the new entry.
        val removable = removed.mapNotNull { local[it] }.filter { !it.downloaded && (!it.bookmarked || it.id in carried) }.map { it.id }
        if (removable.isNotEmpty()) dao.deleteChapters(removable)
        val fresh = inserts.indices.filter { !carriedInsert[it] }.mapNotNull { ids.getOrNull(it)?.takeIf { id -> id > 0 } }
        if (fresh.isNotEmpty() && !firstFetch) dao.get(entryId)?.let { dao.update(it.copy(lastUpdateAt = now)) }
        if (firstFetch) emptyList() else fresh
    }

    /** Adds the title to the online library (in the [tabIds]) or removes it (it also leaves its tabs). */
    suspend fun setFavorite(entryId: Long, favorite: Boolean, tabIds: Set<Long> = emptySet()) {
        db.withTransaction {
            dao.setFavorite(entryId, favorite, null, System.currentTimeMillis())
            if (favorite) tabIds.forEach { tabs.setOnline(listOf(entryId), it, true) } else tabs.forgetOnline(listOf(entryId))
        }
        if (favorite && dao.chapters(entryId).isEmpty()) runCatching { refresh(entryId) }
    }

    /** Told the title id after its chapters were read or marked as read (tracking, 2.0.0). */
    var chaptersReadListener: ((Long) -> Unit)? = null

    suspend fun setRead(ids: Collection<Long>, read: Boolean) {
        dao.setRead(ids, read)
        if (read) chaptersReadListener?.let { listener -> ids.mapNotNullTo(HashSet()) { dao.chapter(it)?.itemId }.forEach(listener) }
    }
    suspend fun setBookmarked(ids: Collection<Long>, bookmarked: Boolean) = dao.setBookmarked(ids, bookmarked)
    suspend fun clearNew(entryId: Long) = dao.clearNew(entryId)
    suspend fun setReaderSettings(entryId: Long, json: String?) = dao.setReaderSettings(entryId, json)
    suspend fun readerSettings(entryId: Long): String? = dao.get(entryId)?.readerSettingsJson

    suspend fun saveProgress(chapterId: Long, page: Int, finished: Boolean) {
        val incognito = settings.incognito.get()
        if (incognito) {
            if (finished) dao.setRead(listOf(chapterId), true)
            return
        }
        dao.saveProgress(chapterId, page, System.currentTimeMillis(), finished)
        if (finished) chaptersReadListener?.let { listener -> dao.chapter(chapterId)?.itemId?.let(listener) }
    }

    /**
     * Checks all favourites for new chapters. Sources are checked in parallel, the titles of one source one after another
     * (with a short pause) so that a big library does not hit a single site with dozens of parallel requests.
     */
    suspend fun checkUpdates(onProgress: (Int, Int) -> Unit = { _, _ -> }): UpdateCheckResult = withContext(Dispatchers.IO) {
        val favorites = dao.favorites().filter { it.type != ContentType.BOOK.name && sources.get(it.sourceId) != null }
        val updated = mutableListOf<TitleUpdate>()
        val failed = mutableListOf<Pair<String, Throwable>>()
        val parallelSources = Semaphore(4)
        var done = 0
        coroutineScope {
            favorites.groupBy { it.sourceId }.values.map { group ->
                async {
                    parallelSources.withPermit {
                        group.forEachIndexed { i, f ->
                            if (i > 0) delay(UPDATE_PAUSE_MS)
                            val r = runCatching { refreshNew(f.id, SourceCalls.UPDATE_TIMEOUT_MS) }
                            // A cancelled check stops here; a CancellationException thrown by a source itself is just a failed title.
                            ensureActive()
                            synchronized(updated) {
                                r.onSuccess { ids -> if (ids.isNotEmpty()) updated += TitleUpdate(f.id, f.title, ids) }
                                r.exceptionOrNull()?.let { failed += f.title to it }
                                done++
                                onProgress(done, favorites.size)
                            }
                        }
                    }
                }
            }.forEach { it.await() }
        }
        UpdateCheckResult(updated.sortedBy { it.title.lowercase() }, failed, favorites.size)
    }

    /** Sources asked by global search, in the order of the source list: pinned, recently used, then by name. */
    private fun searchTargets(onlyContent: SourceContent?): List<CatalogSource> {
        // Only the languages the catalog shows: every MangaDex language would otherwise be asked the same question.
        val langs = sources.languages.value
        return sources.infos.value.filter { it.enabled && !it.hidden && sources.isShown(it, langs) && (onlyContent == null || it.content == onlyContent) }
            .sortedWith(compareByDescending<app.yomu.data.source.SourceInfo> { it.pinned }.thenByDescending { it.lastUsedAt }.thenBy { it.name.lowercase() })
            .mapNotNull { info -> sources.get(info.id)?.takeIf { it.supportsSearch } }
            .filter { it.id != sources.local.id || sources.local.isConfigured }
    }

    /** First page of [query] in one source, without duplicates and with the best title matches first. */
    suspend fun searchSource(sourceId: Long, query: String): List<SItem> {
        val s = source(sourceId)
        val page = sourceCall(sourceId, SourceCalls.SEARCH_TIMEOUT_MS) { s.search(query, s.getFilterList(), 1) }
        return SearchRanking.rank(page.items.distinctBy { it.url }, query)
    }

    /**
     * Searches all enabled sources in parallel. Rows keep the order of the source list while results arrive (the list
     * does not jump); the screen may hide empty rows.
     */
    fun globalSearch(query: String, onlyContent: SourceContent? = null): Flow<List<GlobalSearchResult>> = channelFlow {
        val targets = searchTargets(onlyContent)
        val results = LinkedHashMap<Long, GlobalSearchResult>()
        targets.forEach { results[it.id] = GlobalSearchResult(it.id, "${it.name} (${it.lang})", emptyList(), loading = true) }
        send(results.values.toList())
        val semaphore = Semaphore(5)
        targets.forEach { s ->
            launch(Dispatchers.IO) {
                val r = semaphore.withPermit { runCatching { searchSource(s.id, query) } }
                ensureActive() // the search was cancelled (new query, screen closed); otherwise any error belongs to this row
                val snapshot = synchronized(results) {
                    results[s.id] = GlobalSearchResult(s.id, "${s.name} (${s.lang})", r.getOrNull().orEmpty(), r.exceptionOrNull())
                    results.values.toList()
                }
                send(snapshot)
            }
        }
    }

    suspend fun browse(sourceId: Long, mode: BrowseMode, page: Int, query: String = "", filters: app.yomu.source.FilterList? = null, url: String? = null): ItemsPage {
        val s = source(sourceId)
        sources.touch(sourceId)
        return sourceCall(sourceId) {
            when (mode) {
                BrowseMode.POPULAR -> s.getPopular(page)
                BrowseMode.LATEST -> s.getLatest(page)
                BrowseMode.SEARCH -> s.search(query, filters ?: s.getFilterList(), page)
                BrowseMode.NAVIGATE -> s.browse(url.orEmpty(), page)
            }
        }
    }

    /** Download links of a catalog book: duplicates removed, preferred formats first (EPUB, FB2, MOBI/AZW3, PDF…). */
    suspend fun bookLinks(entryId: Long): List<SBookLink> {
        val e = dao.get(entryId) ?: error("Entry not found")
        val source = source(e.sourceId)
        val item = toSItem(e)
        val links = sourceCall(e.sourceId) {
            val details = runCatching { source.getDetails(item) }.getOrElse { if (it is CancellationException) throw it else item }
            source.getDownloadLinks(details.copy(links = details.links.ifEmpty { item.links }))
        }
        return SearchRanking.sortLinks(links)
    }

    /** Moves reading state and favourite flag from one entry to the same title in another source. */
    suspend fun migrate(fromEntryId: Long, targetSourceId: Long, target: SItem): Long {
        val from = dao.get(fromEntryId) ?: error("Entry not found")
        val toId = upsert(targetSourceId, target)
        refresh(toId)
        val oldChapters = dao.chapters(fromEntryId)
        val newChapters = dao.chapters(toId)
        val readNumbers = oldChapters.filter { it.read }.map { it.number }.toSet()
        val maxRead = oldChapters.filter { it.read }.maxOfOrNull { it.number } ?: -1f
        db.withTransaction {
            dao.updateChapters(newChapters.map { c -> if (c.number in readNumbers || (c.number >= 0 && c.number <= maxRead)) c.copy(read = true, isNew = false) else c.copy(isNew = false) })
            val fromTabs = tabs.onlineTabsOf(fromEntryId)
            dao.setFavorite(toId, true, null, System.currentTimeMillis())
            fromTabs.forEach { tabs.setOnline(listOf(toId), it, true) }
            dao.setFavorite(fromEntryId, false, null, from.addedAt)
            tabs.forgetOnline(listOf(fromEntryId))
        }
        return toId
    }

    suspend fun pages(chapterId: Long): Pair<CatalogSource, List<Page>> {
        val c = dao.chapter(chapterId) ?: error("Chapter not found")
        val e = dao.get(c.itemId) ?: error("Entry not found")
        val s = source(e.sourceId)
        val pages = sourceCall(e.sourceId) { s.getPages(c.toSChapter()) }
        if (pages.isEmpty()) throw SourceException("The source returned no pages for ${c.name}")
        return s to pages
    }

    suspend fun novelContent(chapterId: Long): String {
        val c = dao.chapter(chapterId) ?: error("Chapter not found")
        c.localPath?.let { p -> File(p).takeIf { it.exists() }?.let { return it.readText() } }
        val e = dao.get(c.itemId) ?: error("Entry not found")
        val text = sourceCall(e.sourceId, SourceCalls.UPDATE_TIMEOUT_MS) { source(e.sourceId).getContent(c.toSChapter()) }
        if (text.isBlank()) throw SourceException("The source returned an empty chapter: ${c.name}")
        return text
    }

    /** Online page source that caches page images as files (shared with the downloader). */
    fun onlinePages(source: CatalogSource, pages: List<Page>, cacheKey: String): PageSource = OnlinePageSource(context, client(), source, pages, cacheKey)

    enum class BrowseMode { POPULAR, LATEST, SEARCH, NAVIGATE }

    companion object {
        const val KIND_CATALOG = "CATALOG"

        /** Pause between two titles of the same source during the update check. */
        const val UPDATE_PAUSE_MS = 400L
    }
}

class OnlinePageSource(
    context: Context,
    private val client: OkHttpClient,
    private val source: CatalogSource,
    private val pages: List<Page>,
    cacheKey: String,
) : PageSource {
    private val context = context.applicationContext
    private val dir = app.yomu.data.book.PageSources.cacheDirFor(context, cacheKey)
    override val count: Int get() = pages.size
    override fun name(index: Int): String = "${index + 1}"

    /** One fetch per page: the page on screen and the preloader may ask for the same page at the same time. */
    private val locks = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.sync.Mutex>()

    override suspend fun file(index: Int): File = withContext(Dispatchers.IO) {
        val out = File(dir, "%05d.img".format(index))
        if (out.exists() && out.length() > 0) return@withContext out
        locks.getOrPut(index) { kotlinx.coroutines.sync.Mutex() }.withLock {
            if (!(out.exists() && out.length() > 0)) PageFetcher.fetch(context, client, source, pages[index], out)
        }
        out
    }

    override fun close() = Unit
}
