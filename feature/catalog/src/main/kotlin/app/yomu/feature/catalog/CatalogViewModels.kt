package app.yomu.feature.catalog

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import app.yomu.core.common.AppError
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.runCatchingApp
import app.yomu.core.datastore.AppSettings
import app.yomu.core.datastore.stateIn
import app.yomu.data.catalog.CatalogRepository
import app.yomu.data.audio.AudiobookStore
import app.yomu.data.library.ImportOutcome
import app.yomu.data.catalog.CatalogRepository.BrowseMode
import app.yomu.data.catalog.GlobalSearchResult
import app.yomu.data.catalog.HistoryEntry
import app.yomu.data.download.DownloadRepository
import app.yomu.data.source.SourceHealth
import app.yomu.data.source.SourceInfo
import app.yomu.data.source.SourceManager
import app.yomu.data.tracking.TrackerManager
import app.yomu.core.database.TrackEntity
import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.data.work.UpdateWorker
import app.yomu.domain.catalog.ChapterDisplay
import app.yomu.domain.catalog.ChapterOrder
import app.yomu.domain.catalog.ChapterSortMode
import app.yomu.domain.catalog.LibraryDisplay
import app.yomu.domain.catalog.LibrarySortMode
import app.yomu.domain.catalog.NaturalOrder
import app.yomu.domain.catalog.SourceListFilter
import app.yomu.domain.catalog.SourceSortMode
import app.yomu.domain.model.BookCollection
import app.yomu.domain.model.CatalogEntry
import app.yomu.domain.model.ChapterInfo
import app.yomu.domain.model.ContentType
import app.yomu.domain.model.DownloadItem
import app.yomu.domain.model.DownloadStatus
import app.yomu.source.CatalogSource
import app.yomu.source.FilterList
import app.yomu.source.HttpSource
import app.yomu.source.ItemsPage
import app.yomu.source.SBookLink
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A screen state from [this] flow. The mapping, filtering and sorting run on a background thread (1.8.0): they ran on
 * the main thread before, so opening the catalog with thousands of sources froze the app for a moment.
 */
private fun <T> Flow<T>.state(vm: ViewModel, initial: T): StateFlow<T> =
    flowOn(Dispatchers.Default).stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

/** Waits for a pause in typing before filtering again (no work on every key press); clearing applies at once. */
@OptIn(FlowPreview::class)
private fun Flow<String>.debounceTyping(): Flow<String> = debounce { if (it.isEmpty()) 0L else 150L }

/** One-shot UI events shared by catalog screens. */
sealed interface CatalogEvent {
    data class Message(val res: Int, val arg: String? = null) : CatalogEvent

    /** A plural message with a count ([res] is a plurals resource). */
    data class Count(val res: Int, val count: Int) : CatalogEvent
    data class Failure(val error: Throwable) : CatalogEvent
    data class OpenTitle(val entryId: Long) : CatalogEvent

    /** A library book to open (an audiobook added from a catalog title). */
    data class OpenBook(val bookId: Long) : CatalogEvent
}

// region Catalog home

/** Pinned sources first, then by [sort]; ties by name. */
internal fun sourceComparator(sort: SourceSortMode, deviceLanguage: String = java.util.Locale.getDefault().language): Comparator<SourceInfo> {
    val byName = compareBy(NaturalOrder) { s: SourceInfo -> s.name }
    val main: Comparator<SourceInfo> = when (sort) {
        SourceSortMode.RECENT -> compareByDescending { it.lastUsedAt }
        SourceSortMode.NAME -> byName
        SourceSortMode.LANGUAGE -> compareBy<SourceInfo> { s -> if (s.lang == deviceLanguage || s.lang.startsWith("$deviceLanguage-")) 0 else 1 }
            .thenBy { languageName(it.lang).lowercase() }
    }
    return compareByDescending<SourceInfo> { it.pinned }.then(main).then(byName)
}

class CatalogHomeViewModel(
    private val context: Context,
    private val sources: SourceManager,
    private val catalog: CatalogRepository,
    private val settings: AppSettings,
    private val downloads: DownloadRepository,
) : ViewModel() {
    private val _events = Channel<CatalogEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Shown languages; contains [SourceManager.ALL_LANGUAGES] when every language is shown. */
    val languages: StateFlow<Set<String>> = sources.languages
    val ready: StateFlow<Boolean> = sources.ready

    /** Device language and English first, then the others by name: the chips do not jump around when toggled. */
    val allLanguages: StateFlow<List<String>> = sources.infos
        .map { infos -> infos.map { it.lang }.distinct() }
        .distinctUntilChanged()
        .map { langs ->
            val device = java.util.Locale.getDefault().language
            langs.filter { it.isNotBlank() && it != "all" }.sortedWith(
                compareBy<String> { l -> if (l == device || l.startsWith("$device-")) 0 else if (l == "en") 1 else if (l == "multi") 3 else 2 }
                    .thenBy { l -> languageName(l).lowercase() },
            )
        }
        .state(this, emptyList())

    /** Number of sources per language, for the language list of the filters. */
    val languageCounts: StateFlow<Map<String, Int>> = sources.infos
        .map { infos -> infos.groupingBy { it.lang }.eachCount() }
        .state(this, emptyMap())

    /** Filters of the source list besides the languages: content, kind, pinned, 18+… (kept in the settings). */
    val sourceFilter: StateFlow<SourceListFilter> = settings.catalogSourceFilter.stateIn(viewModelScope)

    /** Order of the source list. */
    val sourceSort: StateFlow<SourceSortMode> = settings.sourceSort.stateIn(viewModelScope)

    /** Filter of the source list by name. */
    val sourceQuery = MutableStateFlow("")

    /** Recent failures per source, for the "not responding" hint. */
    val health: StateFlow<Map<Long, SourceHealth>> = catalog.health

    /** Ids of the sources that do not answer, for "hide sources that don't respond". */
    private val unstableIds = catalog.health.map { h -> h.filterValues { it.unstable }.keys }.distinctUntilChanged()

    val sourceList: StateFlow<List<SourceInfo>> = combine(
        sources.infos,
        sources.languages,
        settings.sourceSort.flow,
        sourceQuery.debounceTyping(),
        combine(settings.catalogSourceFilter.flow, unstableIds) { f, u -> f to u },
    ) { infos, langs, sort, q, (filter, unstable) ->
        val query = q.trim()
        infos.filter { s ->
            sources.isShown(s, langs) &&
                filter.matches(s.content.name, s.kind.name, s.pinned, s.enabled, s.isNsfw, s.id in unstable, s.hidden) &&
                (query.isEmpty() || s.name.contains(query, ignoreCase = true) || s.extensionName?.contains(query, ignoreCase = true) == true)
        }.sortedWith(sourceComparator(sort))
    }.state(this, emptyList())

    /** Number of sources before any filter: "12 of 340" in the filter bar. */
    val sourceTotal: StateFlow<Int> = sources.infos.map { it.size }.state(this, 0)

    /** Sort and filters of the library tab. */
    val libraryDisplay: StateFlow<LibraryDisplay> = settings.libraryDisplay.stateIn(viewModelScope)
    private val allFavorites: StateFlow<List<CatalogEntry>> = catalog.observeFavorites().state(this, emptyList())

    /** Library titles, sorted and filtered by [libraryDisplay]. */
    val favorites: StateFlow<List<CatalogEntry>> = combine(allFavorites, settings.libraryDisplay.flow) { list, d -> d.apply(list) }.state(this, emptyList())

    /** Number of library titles before filtering. */
    val libraryTotal: StateFlow<Int> = allFavorites.map { it.size }.state(this, 0)
    /** The library tabs (shared with the books) and the tabs of each online title. */
    val tabs: StateFlow<List<BookCollection>> = catalog.tabs.observe().state(this, emptyList())
    val tabMembers: StateFlow<Map<Long, Set<Long>>> = catalog.tabs.observeOnline().state(this, emptyMap())
    val updates: StateFlow<List<HistoryEntry>> = catalog.observeUpdates().state(this, emptyList())
    val history: StateFlow<List<HistoryEntry>> = catalog.observeHistory().state(this, emptyList())
    val newCount: StateFlow<Int> = catalog.observeNewCount().state(this, 0)
    val incognito: StateFlow<Boolean> = settings.incognito.stateIn(viewModelScope)
    val localConfigured: Boolean get() = sources.local.isConfigured

    fun toggleLanguage(lang: String) = viewModelScope.launch(ScreenErrors) {
        settings.catalogLanguages.update { stored ->
            val base = SourceManager.effectiveLanguages(stored, allLanguages.value) - SourceManager.ALL_LANGUAGES
            (if (lang in base) base - lang else base + lang).ifEmpty { setOf(SourceManager.ALL_LANGUAGES) }
        }
    }

    /** "All languages". */
    fun clearLanguages() = viewModelScope.launch(ScreenErrors) { settings.catalogLanguages.set(setOf(SourceManager.ALL_LANGUAGES)) }

    /** Shows only the sources in [langs] (an empty set: all languages). */
    fun setLanguages(langs: Set<String>) = viewModelScope.launch(ScreenErrors) {
        settings.catalogLanguages.set(langs.ifEmpty { setOf(SourceManager.ALL_LANGUAGES) })
    }

    /** The device language and English, as on the first start. */
    fun defaultLanguages() = viewModelScope.launch(ScreenErrors) { settings.catalogLanguages.set(emptySet()) }

    fun updateSourceFilter(transform: (SourceListFilter) -> SourceListFilter) = viewModelScope.launch(ScreenErrors) {
        settings.catalogSourceFilter.update(transform)
    }

    /** Every source again: no filters, all languages. */
    fun resetSourceFilters() = viewModelScope.launch(ScreenErrors) {
        settings.catalogSourceFilter.set(SourceListFilter())
        settings.catalogLanguages.set(setOf(SourceManager.ALL_LANGUAGES))
    }
    fun setPinned(id: Long, pinned: Boolean) = viewModelScope.launch(ScreenErrors) { sources.setPinned(id, pinned) }
    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch(ScreenErrors) { sources.setEnabled(id, enabled) }
    fun setHidden(id: Long, hidden: Boolean) = viewModelScope.launch(ScreenErrors) { sources.setHidden(id, hidden) }
    fun setExtensionHidden(pkg: String, hidden: Boolean) = viewModelScope.launch(ScreenErrors) { sources.setExtensionHidden(pkg, hidden) }
    fun setIncognito(value: Boolean) = viewModelScope.launch(ScreenErrors) { settings.incognito.set(value) }

    fun addOpds(name: String, url: String, user: String?, password: String?) = viewModelScope.launch(ScreenErrors) {
        runCatchingApp { sources.addOpds(name.trim().ifBlank { url.substringAfter("://").substringBefore('/') }, url.trim(), user?.ifBlank { null }, password?.ifBlank { null }) }
            .onSuccess { _events.send(CatalogEvent.Message(R.string.catalog_opds_added)) }
            .onFailure { _events.send(CatalogEvent.Failure(it)) }
    }

    fun removeOpds(id: Long) = viewModelScope.launch(ScreenErrors) { sources.removeOpds(id) }

    fun configureLocal(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatchingApp { sources.configureLocal(uri.toString()) }
            .onSuccess { _events.send(CatalogEvent.Message(R.string.catalog_local_configured)) }
            .onFailure { _events.send(CatalogEvent.Failure(it)) }
    }

    fun checkUpdates() {
        UpdateWorker.runNow(context)
        _events.trySend(CatalogEvent.Message(R.string.catalog_updates_started))
    }

    fun removeHistory(chapterId: Long) = viewModelScope.launch(ScreenErrors) { catalog.removeHistory(chapterId) }
    fun clearHistory() = viewModelScope.launch(ScreenErrors) { catalog.clearHistory() }
    /** Creates a library tab; [addEntries] join it right away. */
    fun createTab(name: String, addEntries: Collection<Long> = emptyList()) = viewModelScope.launch(ScreenErrors) {
        if (name.isBlank()) return@launch
        val id = catalog.tabs.create(name)
        catalog.tabs.setOnline(addEntries, id, true)
    }
    fun renameTab(id: Long, name: String) = viewModelScope.launch(ScreenErrors) { if (name.isNotBlank()) catalog.tabs.rename(id, name) }
    fun deleteTab(id: Long) = viewModelScope.launch(ScreenErrors) { catalog.tabs.delete(id) }
    fun moveTab(id: Long, delta: Int) = viewModelScope.launch(ScreenErrors) { catalog.tabs.move(id, delta) }

    /** Adds the titles to the tab or takes them out of it. */
    fun setTab(entryIds: Collection<Long>, tabId: Long, member: Boolean) = viewModelScope.launch(ScreenErrors) { catalog.tabs.setOnline(entryIds, tabId, member) }
    fun removeFromLibrary(entryIds: Collection<Long>) = viewModelScope.launch(ScreenErrors) { entryIds.forEach { catalog.setFavorite(it, false) } }

    fun setSourceSort(mode: SourceSortMode) = viewModelScope.launch(ScreenErrors) { settings.sourceSort.set(mode) }

    /** Sorts the library by [mode]; picking the current mode again flips the direction. */
    fun setLibrarySort(mode: LibrarySortMode) = viewModelScope.launch(ScreenErrors) {
        settings.libraryDisplay.update { d -> if (d.sort == mode) d.copy(descending = !d.descending) else d.copy(sort = mode, descending = mode.defaultDescending) }
    }

    fun updateLibraryDisplay(transform: (LibraryDisplay) -> LibraryDisplay) = viewModelScope.launch(ScreenErrors) { settings.libraryDisplay.update(transform) }

    /** Queues the unread chapters of the titles (one release each, hidden groups skipped). */
    fun downloadUnread(entryIds: Collection<Long>) = viewModelScope.launch(ScreenErrors) {
        runCatchingApp { entryIds.sumOf { downloads.enqueueUnread(it) } }
            .onSuccess { n ->
                _events.send(if (n > 0) CatalogEvent.Count(R.plurals.title_download_queued_count, n) else CatalogEvent.Message(R.string.title_download_nothing))
            }
            .onFailure { _events.send(CatalogEvent.Failure(it)) }
    }
}

// endregion

// region Source browsing

/**
 * Paging source over a catalog source: pages are numbered from 1. Titles already shown on an earlier page are dropped
 * (sites often shift their lists while you scroll), and paging stops when a site returns a page of repeats only
 * (some ignore the page number and send the first page again).
 */
class SourcePagingSource(private val loader: suspend (Int) -> ItemsPage) : PagingSource<Int, SItem>() {
    private val seen = HashSet<String>()

    override fun getRefreshKey(state: PagingState<Int, SItem>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SItem> {
        val page = params.key ?: 1
        return try {
            val result = loader(page)
            val fresh = synchronized(seen) { result.items.filter { seen.add(it.url) } }
            val more = result.hasNextPage && fresh.isNotEmpty()
            LoadResult.Page(fresh, prevKey = null, nextKey = if (more) page + 1 else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Errors too (an extension built for another app version): the list shows them instead of crashing.
            LoadResult.Error(AppError.from(e))
        }
    }
}

enum class BrowseTab { POPULAR, LATEST, SEARCH }

private val NotLoading: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
private val NoVariants: StateFlow<List<SourceVariant>> = MutableStateFlow(emptyList<SourceVariant>()).asStateFlow()

/** One language of a source that comes in several (an extension with a source per language). */
data class SourceVariant(val sourceId: Long, val lang: String)

/** The languages of one source (1.9.0): the sources of one extension or the built-in ones with the same name and content. */
internal object SourceVariants {
    fun of(list: List<SourceInfo>, id: Long): List<SourceVariant> {
        val current = list.firstOrNull { it.id == id } ?: return emptyList()
        if (current.kind != SourceInfo.Kind.EXTENSION && current.kind != SourceInfo.Kind.BUILT_IN) return emptyList()
        val same = list.filter {
            it.kind == current.kind && it.extensionPkg == current.extensionPkg && it.content == current.content &&
                it.name.equals(current.name, ignoreCase = true) && (it.enabled || it.id == id)
        }
        // One per language; the open one stands for its language when two share it.
        val perLang = same.groupBy { it.lang.lowercase() }.values.map { group -> group.firstOrNull { it.id == id } ?: group.first() }
        if (perLang.size < 2) return emptyList()
        return perLang
            .sortedWith(compareBy<SourceInfo>({ !isMultiLang(it.lang) }, { languageName(it.lang) }))
            .map { SourceVariant(it.id, it.lang) }
    }

    fun isMultiLang(lang: String): Boolean = lang.isBlank() || lang == "all" || lang == "multi"
}

/** One run of the Search tab: its own list of pages, made when the search was started. */
class SearchResults(val id: Int, val query: String, val items: Flow<PagingData<SItem>>)

/** What the browse screen of a source needs from its view model (the screenshot tests use a fake). */
interface BrowseModel {
    val sourceId: Long
    val exists: Boolean
    val title: String
    val isNavigation: Boolean
    val supportsSearch: Boolean
    val content: SourceContent
    val webUrl: String?
    /** Tabs of the screen in order: popular, latest (when the source has it), search. A navigation list has one. */
    val tabs: List<BrowseTab>
    val initialTab: BrowseTab
    /** Popular titles, or the entries of a navigation list. */
    val popular: Flow<PagingData<SItem>>
    val latest: Flow<PagingData<SItem>>
    /** The last search; null until the first one (the Search tab then shows recent searches and the filters). */
    val results: StateFlow<SearchResults?>
    val query: MutableStateFlow<String>
    val filters: StateFlow<FilterList>
    /** Bumped on every filter edit so that Compose re-reads the mutable filter states. */
    val filterVersion: StateFlow<Int>
    /**
     * Bumped only when the filters are replaced or put back (reset, reload, tags that arrived): the panel then reads
     * every control again. A single edit redraws only its own control (1.8.0).
     */
    val filterEpoch: StateFlow<Int> get() = filterVersion
    /** The source is still building its filters (some extensions load their tags in the background). */
    val filtersLoading: StateFlow<Boolean> get() = NotLoading
    /** Filters that differ from the source's defaults (chips above the results, the badge on the filter button). */
    val activeFilters: StateFlow<List<ActiveFilter>>
    /** The same source in its other languages, for the language switch of the top bar (1.9.0); empty for most. */
    val variants: StateFlow<List<SourceVariant>> get() = NoVariants
    /** Saves the chosen filters as those of [targetId], another language of this source, before switching to it. */
    suspend fun carryFiltersTo(targetId: Long) = Unit
    val searchHistory: StateFlow<List<String>>
    val inLibrary: StateFlow<Set<String>>
    val events: Flow<CatalogEvent>
    fun submit()
    fun searchFromHistory(q: String)
    fun clearSearchHistory()
    fun onFilterChanged()
    fun resetFilters()
    /** Asks the source for its filters again, keeping the choices (for "press reset to load the tags" sources). */
    fun reloadFilters() = Unit
    fun resetFilter(path: List<Int>)
    fun clearFilters()
    fun open(item: SItem)
    fun toggleLibrary(item: SItem)
}

/**
 * Browsing one source. Popular, latest and every search have their own pager (1.6.0): before, one shared list was
 * re-pointed at the chosen tab, so while "Latest" loaded (or failed) the screen kept showing the popular titles and
 * no error, and the tab looked like it did nothing.
 */
class SourceBrowseViewModel(
    override val sourceId: Long,
    initialQuery: String?,
    val navUrl: String?,
    navTitle: String?,
    /** The tab to open with (a [BrowseTab] name): the switch to another language keeps the tab on display. */
    initialTabName: String?,
    private val context: Context,
    private val catalog: CatalogRepository,
    sources: SourceManager,
    private val settings: AppSettings,
) : ViewModel(), BrowseModel {
    private val source = sources.get(sourceId)
    override val exists: Boolean = source != null
    override val title: String = navTitle ?: source?.name.orEmpty()
    val supportsLatest: Boolean = source?.supportsLatest == true
    override val supportsSearch: Boolean = source?.supportsSearch != false
    override val isNavigation: Boolean = navUrl != null
    override val content: SourceContent = source?.content ?: SourceContent.MANGA
    override val webUrl: String? = (source as? CatalogSource)?.homeUrl ?: (source as? HttpSource)?.baseUrl

    private val filtersFile = File(context.filesDir, "filters/$sourceId.json")

    /** The filters of the panel; empty until the source has built them (off the main thread, see [loadFilters]). */
    override val filters = MutableStateFlow(FilterList())
    override val filterVersion = MutableStateFlow(0)
    private val epoch = MutableStateFlow(0)
    override val filterEpoch: StateFlow<Int> = epoch.asStateFlow()
    private val loading = MutableStateFlow(source != null)
    override val filtersLoading: StateFlow<Boolean> = loading.asStateFlow()

    /** Done once the first filter list is there: a search started before that (from another screen) waits for it. */
    private val filtersReady = CompletableDeferred<Unit>()
    private var filterJob: Job? = null
    private var filterRun = 0

    /** Writes of the saved filters, one after another. */
    private val fileWrites = Dispatchers.IO.limitedParallelism(1)

    /** A fresh filter list of the source: what "set" is compared with. */
    @Volatile
    private var defaults: FilterList = FilterList()

    override val tabs: List<BrowseTab> = when {
        isNavigation -> listOf(BrowseTab.POPULAR)
        else -> buildList {
            add(BrowseTab.POPULAR)
            if (supportsLatest) add(BrowseTab.LATEST)
            // Only a source without text search is asked for its filters here, to know whether it has any.
            if (supportsSearch || runCatching { source?.getFilterList()?.isNotEmpty() == true }.getOrDefault(false)) add(BrowseTab.SEARCH)
        }
    }
    override val initialTab: BrowseTab = tabs.firstOrNull { it.name == initialTabName }
        ?: if (!initialQuery.isNullOrBlank() && BrowseTab.SEARCH in tabs) BrowseTab.SEARCH else BrowseTab.POPULAR

    /** Runs right away when the screen opens: a search from another screen or the one of the language left. */
    private val initialSearch: String? = initialQuery?.trim()?.takeIf { initialTab == BrowseTab.SEARCH }
    override val query = MutableStateFlow(initialQuery.orEmpty())

    override val activeFilters: StateFlow<List<ActiveFilter>> = combine(filters, filterVersion) { list, _ -> SourceFilterState.active(list, defaults) }
        .state(this, emptyList())

    /** Recent searches in sources, newest first. */
    override val searchHistory: StateFlow<List<String>> = settings.sourceSearchHistory.flow.state(this, emptyList())

    override val inLibrary: StateFlow<Set<String>> = catalog.observeFavorites()
        .map { list -> list.filter { it.sourceId == sourceId }.map { it.url }.toSet() }
        .state(this, emptySet())

    private fun pager(load: suspend (Int) -> ItemsPage): Flow<PagingData<SItem>> =
        Pager(PagingConfig(pageSize = 20, prefetchDistance = 10, enablePlaceholders = false)) { SourcePagingSource(load) }.flow

    override val popular: Flow<PagingData<SItem>> = pager { page ->
        if (navUrl != null) catalog.browse(sourceId, BrowseMode.NAVIGATE, page, url = navUrl) else catalog.browse(sourceId, BrowseMode.POPULAR, page)
    }.cachedIn(viewModelScope)

    override val latest: Flow<PagingData<SItem>> = pager { page -> catalog.browse(sourceId, BrowseMode.LATEST, page) }.cachedIn(viewModelScope)

    private val _results = MutableStateFlow<SearchResults?>(null)
    override val results: StateFlow<SearchResults?> = _results.asStateFlow()
    /** Scope of the current search's cached pages; cancelled when the next search starts. */
    private var searchScope: CoroutineScope? = null
    private var searchCount = 0

    private val _events = Channel<CatalogEvent>(Channel.BUFFERED)
    override val events = _events.receiveAsFlow()

    override val variants: StateFlow<List<SourceVariant>> = if (isNavigation) NoVariants else sources.infos
        .map { SourceVariants.of(it, sourceId) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .state(this, emptyList())

    init {
        loadFilters(restoreSaved = true)
        initialSearch?.let(::startSearch)
    }

    /** Only chosen filters are carried: defaults would overwrite what the other language has saved. */
    override suspend fun carryFiltersTo(targetId: Long) {
        if (targetId == sourceId || !filtersReady.isCompleted || activeFilters.value.isEmpty()) return
        val json = runCatching { filters.value.saveState().toString() }.getOrNull() ?: return
        withContext(fileWrites) {
            runCatching { File(context.filesDir, "filters/$targetId.json").apply { parentFile?.mkdirs() }.writeText(json) }
        }
    }

    /** The source's filter list, built off the main thread; null when the source failed to build it. */
    private suspend fun freshFilters(): FilterList? {
        val s = source ?: return null
        return withContext(Dispatchers.Default) { runCatching { s.getFilterList() }.getOrNull() }
    }

    /**
     * Builds the filters off the main thread (1.8.0): extensions build them as they like, some slowly or with network
     * calls, which froze the screen when the source opened (or failed: no network on the main thread).
     */
    private fun loadFilters(restoreSaved: Boolean) {
        if (source == null) {
            filtersReady.complete(Unit)
            return
        }
        filterTask {
            try {
                val list = freshFilters() ?: FilterList()
                val fresh = list.deepCopy()
                if (restoreSaved) {
                    withContext(Dispatchers.IO) {
                        runCatching { if (filtersFile.isFile) list.restoreState(Json.parseToJsonElement(filtersFile.readText())) }
                    }
                }
                replaceFilters(list, fresh)
                filtersReady.complete(Unit)
                awaitLoadedFilters(list)
            } finally {
                filtersReady.complete(Unit)
            }
        }
    }

    /** Runs [block] instead of the filter work under way; [filtersLoading] is on until the last one is done. */
    private fun filterTask(block: suspend () -> Unit) {
        filterJob?.cancel()
        val run = ++filterRun
        loading.value = true
        filterJob = viewModelScope.launch(ScreenErrors) {
            try {
                block()
            } finally {
                if (run == filterRun) loading.value = false
            }
        }
    }

    private fun replaceFilters(list: FilterList, fresh: FilterList) {
        defaults = fresh
        filters.value = list
        filterVersion.value++
        epoch.value++
    }

    /**
     * Many extensions load their tags in the background and, until they have them, show "Press 'Reset' to attempt to
     * load tags" instead. Yomu asks them again a few times and takes the new list when it came, keeping what was
     * chosen meanwhile (1.8.0); before, the tags only appeared after Reset, and Reset was greyed out while nothing
     * was chosen.
     */
    private suspend fun awaitLoadedFilters(start: FilterList) {
        if (!SourceFilterState.hasReloadHint(start)) return
        var current = SourceFilterState.signature(start)
        for (wait in RELOAD_DELAYS_MS) {
            delay(wait)
            val next = freshFilters() ?: continue
            val signature = SourceFilterState.signature(next)
            if (signature == current) continue
            current = signature
            adopt(next)
            if (!SourceFilterState.hasReloadHint(next)) return
        }
    }

    /** Switches to [next], a newer list of the source, with the choices of the current one carried over by name. */
    private suspend fun adopt(next: FilterList) {
        val fresh = next.deepCopy()
        val chosen = filters.value.saveState()
        withContext(Dispatchers.Default) { runCatching { next.restoreState(chosen) } }
        replaceFilters(next, fresh)
    }

    override fun reloadFilters() {
        if (source == null) return
        filterTask {
            val next = freshFilters() ?: return@filterTask
            adopt(next)
            awaitLoadedFilters(next)
        }
    }

    private fun saveFilters() {
        // Not before the list is there: an empty list would overwrite the saved choices.
        if (!filtersReady.isCompleted) return
        val json = runCatching { filters.value.saveState().toString() }.getOrNull() ?: return
        viewModelScope.launch(fileWrites) {
            runCatching {
                filtersFile.parentFile?.mkdirs()
                filtersFile.writeText(json)
            }
        }
    }

    /** A copy of the filters as they are now: editing the panel later doesn't change the pages of a running search. */
    private fun snapshotFilters(): FilterList = filters.value.deepCopy()

    private fun startSearch(q: String) {
        searchScope?.cancel()
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        searchScope = scope
        // Normally the filters are there; a search opened from another screen right away waits for them.
        val snapshot = CompletableDeferred<FilterList>()
        if (filtersReady.isCompleted) snapshot.complete(snapshotFilters()) else scope.launch { filtersReady.await(); snapshot.complete(snapshotFilters()) }
        val items = pager { page -> catalog.browse(sourceId, BrowseMode.SEARCH, page, q, snapshot.await()) }.cachedIn(scope)
        _results.value = SearchResults(++searchCount, q, items)
    }

    /** Searches for the text of the field with the filters of the panel (an empty text searches by filters only). */
    override fun submit() {
        val q = query.value.trim()
        saveFilters()
        startSearch(q)
        if (q.isNotEmpty()) rememberSearch(q)
    }

    override fun searchFromHistory(q: String) {
        query.value = q
        submit()
    }

    override fun clearSearchHistory() {
        viewModelScope.launch(ScreenErrors) { settings.sourceSearchHistory.set(emptyList()) }
    }

    /** Adds [q] to the top of the recent searches (not in incognito mode). */
    private fun rememberSearch(q: String) = viewModelScope.launch(ScreenErrors) {
        if (settings.incognito.get()) return@launch
        val old = settings.sourceSearchHistory.get()
        settings.sourceSearchHistory.set((listOf(q) + old.filterNot { it.equals(q, ignoreCase = true) }).take(SEARCH_HISTORY_MAX))
    }

    override fun onFilterChanged() { filterVersion.value++ }

    /** The source's defaults, asked from the source again (which is what makes some extensions load their tags). */
    override fun resetFilters() {
        resetThen(null)
    }

    private fun resetThen(after: (() -> Unit)?) {
        if (source == null) return
        filterTask {
            withContext(fileWrites) { runCatching { filtersFile.delete() } }
            val list = freshFilters() ?: FilterList()
            replaceFilters(list, list.deepCopy())
            after?.invoke()
            awaitLoadedFilters(list)
        }
    }

    /** Puts one filter back to its default (its chip above the results was tapped) and searches again. */
    override fun resetFilter(path: List<Int>) {
        if (SourceFilterState.reset(filters.value, defaults, path)) {
            filterVersion.value++
            epoch.value++
            if (_results.value != null) submit() else saveFilters()
        }
    }

    /** "Reset all" above the results: the source's defaults, searched again. */
    override fun clearFilters() {
        resetThen { if (_results.value != null) submit() }
    }

    /** Stores the item and asks the screen to open its title page. */
    override fun open(item: SItem) {
        viewModelScope.launch(ScreenErrors) {
            runCatchingApp { catalog.upsert(sourceId, item) }
                .onSuccess { _events.send(CatalogEvent.OpenTitle(it)) }
                .onFailure { _events.send(CatalogEvent.Failure(it)) }
        }
    }

    override fun toggleLibrary(item: SItem) {
        viewModelScope.launch(ScreenErrors) {
            runCatchingApp {
                val id = catalog.upsert(sourceId, item)
                val fav = item.url in inLibrary.value
                catalog.setFavorite(id, !fav)
                !fav
            }.onSuccess { added -> _events.send(CatalogEvent.Message(if (added) R.string.title_added else R.string.title_removed)) }
                .onFailure { _events.send(CatalogEvent.Failure(it)) }
        }
    }
}

// endregion

/** Recent searches kept for the search field of a source. */
private const val SEARCH_HISTORY_MAX = 10

/** Pauses before asking a source again for filters it is still loading: about half a minute in all. */
private val RELOAD_DELAYS_MS = longArrayOf(1_500, 3_000, 5_000, 8_000, 12_000)

// region Global search and migration

class GlobalSearchViewModel(
    initialQuery: String?,
    /** When set, picking a result migrates this entry to the picked title instead of just opening it. */
    val migrateFrom: Long?,
    private val catalog: CatalogRepository,
    sources: SourceManager,
    private val settings: AppSettings,
) : ViewModel() {
    /** Source metadata by id, for the logos next to each result row. */
    val sourceInfos: StateFlow<Map<Long, SourceInfo>> = sources.infos.map { list -> list.associateBy { it.id } }.state(this, emptyMap())
    val query = MutableStateFlow(initialQuery.orEmpty())
    val content = MutableStateFlow<SourceContent?>(null)
    private val submitted = MutableStateFlow(initialQuery.orEmpty())
    val migrating = MutableStateFlow(false)
    private val _events = Channel<CatalogEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Hide sources that found nothing or failed. */
    val onlyWithResults: StateFlow<Boolean> = settings.globalSearchOnlyResults.stateIn(viewModelScope)
    val health: StateFlow<Map<Long, SourceHealth>> = catalog.health

    /** Rows searched again with "Retry", replacing the rows of the running search. */
    private val retried = MutableStateFlow<Map<Long, GlobalSearchResult>>(emptyMap())
    private val retryJobs = mutableListOf<Job>()

    val results: StateFlow<List<GlobalSearchResult>?> = combine(submitted, content) { q, c -> q to c }
        .flatMapLatest { (q, c) -> if (q.isBlank()) flowOf(null) else catalog.globalSearch(q, c) }
        .let { base -> combine(base, retried) { list, overrides -> list?.map { overrides[it.sourceId] ?: it } } }
        .state(this, null)

    fun submit() {
        retryJobs.forEach { it.cancel() }
        retryJobs.clear()
        retried.value = emptyMap()
        submitted.value = query.value.trim()
    }

    fun setOnlyWithResults(value: Boolean) = viewModelScope.launch(ScreenErrors) { settings.globalSearchOnlyResults.set(value) }

    /** Searches one source again (after an error or a timeout). */
    fun retry(sourceId: Long) {
        val q = submitted.value
        val row = results.value?.firstOrNull { it.sourceId == sourceId } ?: return
        if (q.isBlank() || row.loading) return
        retryJobs += viewModelScope.launch(ScreenErrors) {
            retried.update { it + (sourceId to row.copy(items = emptyList(), error = null, loading = true)) }
            val r = try {
                Result.success(catalog.searchSource(sourceId, q))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            retried.update { it + (sourceId to row.copy(items = r.getOrNull().orEmpty(), error = r.exceptionOrNull(), loading = false)) }
        }
    }

    fun setContent(c: SourceContent?) {
        content.value = c
        submit()
    }

    fun open(sourceId: Long, item: SItem) = viewModelScope.launch(ScreenErrors) {
        val from = migrateFrom
        if (from != null) migrating.value = true
        runCatchingApp { if (from != null) catalog.migrate(from, sourceId, item) else catalog.upsert(sourceId, item) }
            .onSuccess {
                if (from != null) _events.send(CatalogEvent.Message(R.string.migrate_done))
                _events.send(CatalogEvent.OpenTitle(it))
            }
            .onFailure { _events.send(CatalogEvent.Failure(it)) }
        migrating.value = false
    }
}

// endregion

// region Title page

private val ACTIVE = setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.PAUSED, DownloadStatus.FAILED)

/** Which task to show when a chapter has several: the one that is running, then waiting, paused, failed. */
private fun DownloadStatus.shownRank(): Int = when (this) {
    DownloadStatus.RUNNING -> 0
    DownloadStatus.QUEUED -> 1
    DownloadStatus.PAUSED -> 2
    else -> 3
}

/** Whether the chapter's group is not hidden by the chapter filter. */
private fun ChapterDisplay.allows(c: ChapterInfo): Boolean = c.scanlator.isNullOrBlank() || c.scanlator !in excludedScanlators

/**
 * Numbers of the chapters of a title in reading order ([positions], by chapter id; releases of one chapter share a
 * number) and the progress through it: [total] distinct chapters, of which [read] are read and [downloaded] downloaded.
 */
data class ChapterStats(val positions: Map<Long, Int> = emptyMap(), val total: Int = 0, val read: Int = 0, val downloaded: Int = 0) {
    companion object {
        fun of(chapters: List<ChapterInfo>): ChapterStats {
            val slots = ChapterOrder.slots(ChapterOrder.reading(chapters))
            val positions = HashMap<Long, Int>(chapters.size * 2)
            slots.forEachIndexed { i, slot -> slot.forEach { positions[it.id] = i + 1 } }
            return ChapterStats(
                positions = positions,
                total = slots.size,
                read = slots.count { slot -> slot.any { it.read } },
                downloaded = slots.count { slot -> slot.any { it.downloaded } },
            )
        }
    }
}

class TitleViewModel(
    val entryId: Long,
    private val catalog: CatalogRepository,
    private val downloads: DownloadRepository,
    private val sources: SourceManager,
    private val settings: AppSettings,
    private val trackers: TrackerManager,
    private val audiobooks: AudiobookStore,
) : ViewModel(), TrackingViewModel {
    val entry: StateFlow<CatalogEntry?> = catalog.observeEntry(entryId).state(this, null)
    private val allChapters: StateFlow<List<ChapterInfo>?> = catalog.observeChapters(entryId).state(this, null)

    /** Sort and filters of the chapter list (the title's own or the default of all titles). */
    val display: StateFlow<ChapterDisplay> = catalog.observeChapterDisplay(entryId).state(this, ChapterDisplay())

    /** Chapter numbers and reading progress, over the groups not hidden by the filter (the reader's order). */
    val chapterStats: StateFlow<ChapterStats> = combine(allChapters, display) { list, d ->
        val all = list.orEmpty()
        ChapterStats.of(all.filter { d.allows(it) }.ifEmpty { all })
    }.flowOn(Dispatchers.Default).state(this, ChapterStats())

    /** "Ask before reading online on mobile data". */
    val warnMobileReading: StateFlow<Boolean> = settings.warnMobileReading.flow.state(this, false)

    /** Chapters as shown, top to bottom. */
    val chapters: StateFlow<List<ChapterInfo>?> = combine(allChapters, display) { list, d -> list?.let(d::apply) }.state(this, null)

    /** Number of chapters before filtering. */
    val totalChapters: StateFlow<Int> = allChapters.map { it?.size ?: 0 }.state(this, 0)

    /** Scanlation groups of the title, most chapters first. */
    val scanlators: StateFlow<List<String>> = allChapters.map { ChapterDisplay().scanlatorsOf(it.orEmpty()) }.state(this, emptyList())
    /** The library tabs and the ones this title is in. */
    val tabs: StateFlow<List<BookCollection>> = catalog.tabs.observe().state(this, emptyList())
    val entryTabs: StateFlow<Set<Long>> = catalog.tabs.observeOnline().map { it[entryId].orEmpty() }.state(this, emptySet())
    val refreshing = MutableStateFlow(false)
    val loadError = MutableStateFlow<Throwable?>(null)
    val links = MutableStateFlow<List<SBookLink>?>(null)
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val _events = Channel<CatalogEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Download tasks of this title's chapters that are still pending, by chapter id. */
    val downloadStates: StateFlow<Map<Long, DownloadItem>> = downloads.observe()
        .map { list ->
            list.filter { it.entryId == entryId && it.chapterId != null && it.status in ACTIVE }
                .groupBy { it.chapterId!! }
                .mapValues { (_, tasks) -> tasks.minBy { it.status.shownRank() } }
        }
        .state(this, emptyMap())

    // region Tracking (2.0.0)

    /** The title's entries on Shikimori, AniList and MyAnimeList. */
    override val tracks: StateFlow<List<TrackEntity>> = trackers.observe(entryId).state(this, emptyList())

    /** Changes on every sign-in and sign-out, so the sheet shows who is signed in. */
    override val trackerVersion: StateFlow<Int> = trackers.store.version

    /** The open "link a title" search, null when closed. */
    override val trackSearch = MutableStateFlow<TrackSearchState?>(null)
    private var trackSearchJob: Job? = null

    override fun trackerSignedIn(service: TrackService): Boolean = trackers.tracker(service).loggedIn()

    /** Reads the entries from the services again when the sheet opens (quietly: offline is fine). */
    override fun refreshTracks() = viewModelScope.launch(ScreenErrors) { runCatchingApp { trackers.refresh(entryId) } }

    override fun openTrackSearch(service: TrackService) {
        trackSearch.value = TrackSearchState(service, entry.value?.title.orEmpty())
        searchTrack(trackSearch.value!!.query)
    }

    override fun closeTrackSearch() {
        trackSearchJob?.cancel()
        trackSearch.value = null
    }

    override fun searchTrack(query: String) {
        val state = trackSearch.value ?: return
        trackSearchJob?.cancel()
        if (query.isBlank()) {
            trackSearch.value = state.copy(query = query, loading = false, results = null, error = null)
            return
        }
        trackSearch.value = state.copy(query = query, loading = true, error = null)
        trackSearchJob = viewModelScope.launch(ScreenErrors) {
            runCatchingApp { trackers.tracker(state.service).search(query.trim()) }
                .onSuccess { found -> trackSearch.update { it?.copy(loading = false, results = found) } }
                .onFailure { e -> trackSearch.update { it?.copy(loading = false, error = e) } }
        }
    }

    override fun bindTrack(result: TrackSearchResult) = viewModelScope.launch(ScreenErrors) {
        val state = trackSearch.value ?: return@launch
        trackSearch.value = state.copy(loading = true, error = null)
        runCatchingApp { trackers.bind(entryId, state.service, result) }
            .onSuccess {
                trackSearch.value = null
                _events.send(CatalogEvent.Message(R.string.track_linked, state.service.displayName))
            }
            .onFailure { e -> trackSearch.update { it?.copy(loading = false, error = e) } }
    }

    /** Saves an edit of the entry (status, chapters, score) and sends it; offline it is sent later. */
    override fun updateTrack(track: TrackEntity, transform: (TrackEntry) -> TrackEntry) = viewModelScope.launch(ScreenErrors) {
        runCatchingApp { trackers.edit(track, transform) }
            .onSuccess { sent -> if (!sent) _events.send(CatalogEvent.Message(R.string.track_pending)) }
            .onFailure { _events.send(CatalogEvent.Failure(it)) }
    }

    override fun unbindTrack(track: TrackEntity, removeRemote: Boolean) = viewModelScope.launch(ScreenErrors) {
        runCatchingApp { trackers.unbind(track, removeRemote) }.onFailure { _events.send(CatalogEvent.Failure(it)) }
    }

    // endregion

    /** Absolute web page of the title, for opening it in WebView. */
    fun webUrl(): String? {
        val e = entry.value ?: return null
        val source = sources.get(e.sourceId)
        // The source knows its own page layout (for example MangaDex API ids map to /title/<id> on the site).
        (source as? CatalogSource)?.let { s ->
            runCatching { s.webUrl(SItem(url = e.url, title = e.title)) }.getOrNull()?.let { return it }
        }
        if (e.url.startsWith("http://") || e.url.startsWith("https://")) return e.url
        val base = (source as? CatalogSource)?.homeUrl ?: (source as? HttpSource)?.baseUrl ?: return null
        return base.trimEnd('/') + "/" + e.url.trimStart('/')
    }

    /** Name of the title's source; null while it is not installed (and for a moment at start, before the sources load). */
    val sourceName: StateFlow<String?> = combine(sources.sources, entry) { list, e ->
        e?.let { list.firstOrNull { s -> s.id == it.sourceId } }?.let { "${it.name} (${it.lang})" }
    }.state(this, null)

    /** True once the sources are loaded and the title's source is not among them (its extension was removed). */
    val sourceMissing: StateFlow<Boolean> = combine(sources.ready, sources.sources, entry) { ready, list, e ->
        ready && e != null && list.none { it.id == e.sourceId }
    }.state(this, false)

    init {
        viewModelScope.launch(ScreenErrors) {
            val e = catalog.entry(entryId) ?: return@launch
            if (e.type == ContentType.BOOK) loadLinks() else if (catalog.chapters(entryId).isEmpty()) refresh() else refreshDetailsQuietly()
            catalog.clearNew(entryId)
        }
    }

    private suspend fun refreshDetailsQuietly() {
        runCatching { catalog.refresh(entryId) }
    }

    fun refresh() = viewModelScope.launch(ScreenErrors) {
        refreshing.value = true
        loadError.value = null
        runCatchingApp { catalog.refresh(entryId) }
            .onSuccess { n -> if (n > 0) _events.send(CatalogEvent.Message(R.string.title_new_chapters, n.toString())) }
            .onFailure { loadError.value = it }
        if (entry.value?.type == ContentType.BOOK) loadLinks()
        refreshing.value = false
    }

    /** Download links of a catalog book: preferred formats first, duplicates removed. */
    private suspend fun loadLinks() {
        runCatchingApp { catalog.bookLinks(entryId) }
            .onSuccess { links.value = it }
            .onFailure { loadError.value = it; links.value = emptyList() }
    }

    /** Adds the title to a tab or takes it out. */
    fun setTab(tabId: Long, member: Boolean) = viewModelScope.launch(ScreenErrors) { catalog.tabs.setOnline(listOf(entryId), tabId, member) }

    /** Creates a tab and hands its id to [onCreated] (the tab picker ticks it). */
    fun createTab(name: String, onCreated: (Long) -> Unit = {}) = viewModelScope.launch(ScreenErrors) {
        if (name.isNotBlank()) onCreated(catalog.tabs.create(name))
    }

    fun setFavorite(favorite: Boolean, tabIds: Set<Long> = emptySet()) = viewModelScope.launch(ScreenErrors) {
        runCatchingApp { catalog.setFavorite(entryId, favorite, tabIds) }
            .onSuccess { _events.send(CatalogEvent.Message(if (favorite) R.string.title_added else R.string.title_removed)) }
            .onFailure { _events.send(CatalogEvent.Failure(it)) }
    }

    // Sort and filters

    fun updateDisplay(transform: (ChapterDisplay) -> ChapterDisplay) = viewModelScope.launch(ScreenErrors) {
        catalog.setChapterDisplay(entryId, transform(catalog.chapterDisplay(entryId)))
    }

    /** Sorts by [mode]; picking the current mode again flips the direction. */
    fun setSort(mode: ChapterSortMode) = updateDisplay { d -> if (d.sort == mode) d.copy(descending = !d.descending) else d.copy(sort = mode) }

    fun toggleScanlator(name: String) = updateDisplay { d ->
        d.copy(excludedScanlators = if (name in d.excludedScanlators) d.excludedScanlators - name else d.excludedScanlators + name)
    }

    fun resetFilters() = updateDisplay { it.copy(unreadOnly = false, downloadedOnly = false, bookmarkedOnly = false, hideDuplicates = false, excludedScanlators = emptySet()) }

    /** Makes the current sort and filters the default of every title. */
    fun useDisplayForAll() = viewModelScope.launch(ScreenErrors) {
        catalog.setChapterDisplayDefault(entryId, catalog.chapterDisplay(entryId))
        _events.send(CatalogEvent.Message(R.string.chapter_display_saved))
    }

    // Selection

    fun toggleSelect(id: Long) { selection.value = selection.value.let { if (id in it) it - id else it + id } }

    /** Selects every shown chapter. */
    fun selectAll() { selection.value = chapters.value.orEmpty().mapTo(LinkedHashSet()) { it.id } }
    fun selectUnread() { selection.value = chapters.value.orEmpty().filter { !it.read }.mapTo(LinkedHashSet()) { it.id } }

    fun invertSelection() {
        val current = selection.value
        selection.value = chapters.value.orEmpty().map { it.id }.filterNotTo(LinkedHashSet()) { it in current }
    }

    /** Adds every shown chapter between the first and the last selected one. */
    fun selectRange() {
        val shown = chapters.value.orEmpty()
        val current = selection.value
        val first = shown.indexOfFirst { it.id in current }
        val last = shown.indexOfLast { it.id in current }
        if (first < 0 || last <= first) return
        selection.value = current + shown.subList(first, last + 1).map { it.id }
    }

    fun clearSelection() { selection.value = emptySet() }

    fun setRead(ids: Collection<Long>, read: Boolean) = viewModelScope.launch(ScreenErrors) { catalog.setRead(ids, read); clearSelection() }
    fun setBookmarked(ids: Collection<Long>, value: Boolean) = viewModelScope.launch(ScreenErrors) { catalog.setBookmarked(ids, value); clearSelection() }

    /** Marks every chapter before [chapter] (in reading order, all groups) as read. */
    fun markPreviousRead(chapter: ChapterInfo) = viewModelScope.launch(ScreenErrors) {
        val slots = ChapterOrder.slots(ChapterOrder.reading(catalog.chapters(entryId)))
        val index = slots.indexOfFirst { s -> s.any { it.id == chapter.id } }
        if (index > 0) catalog.setRead(slots.take(index).flatten().map { it.id }, true)
        clearSelection()
    }

    // Downloads

    /** Chapters in reading order without the groups hidden by the filter. */
    private suspend fun downloadable(): List<ChapterInfo> {
        val d = catalog.chapterDisplay(entryId)
        return ChapterOrder.reading(catalog.chapters(entryId).filter { d.allows(it) })
    }

    /** Runs [queue] and reports how many chapters were queued. */
    private suspend fun reportQueued(queue: suspend () -> Int) {
        runCatchingApp { queue() }
            .onSuccess { n ->
                _events.send(if (n > 0) CatalogEvent.Count(R.plurals.title_download_queued_count, n) else CatalogEvent.Message(R.string.title_download_nothing))
            }
            .onFailure { _events.send(CatalogEvent.Failure(it)) }
    }

    private suspend fun enqueue(ids: List<Long>) = reportQueued { if (ids.isEmpty()) 0 else downloads.enqueueChapters(entryId, ids) }

    /** Downloads the given chapters (in reading order); failed downloads among them are retried. */
    fun download(ids: Collection<Long>) = viewModelScope.launch(ScreenErrors) {
        val wanted = ids.toHashSet()
        clearSelection()
        enqueue(ChapterOrder.reading(catalog.chapters(entryId)).filter { it.id in wanted }.map { it.id })
    }

    /** The next [n] chapters after the last read one that are not downloaded yet, one release each. */
    fun downloadNext(n: Int) = viewModelScope.launch(ScreenErrors) {
        if (n > 0) enqueue(ChapterOrder.upcoming(downloadable(), afterId = null, count = n).map { it.id })
    }

    /** Every unread chapter, one release each (the group the user reads). */
    fun downloadUnread() = viewModelScope.launch(ScreenErrors) { reportQueued { downloads.enqueueUnread(entryId) } }

    /** Every chapter that is not downloaded, one release each. */
    fun downloadAll() = viewModelScope.launch(ScreenErrors) {
        val reading = downloadable()
        val scanlator = ChapterOrder.preferredScanlator(reading)
        enqueue(ChapterOrder.slots(reading).filter { slot -> slot.none { it.downloaded } }.map { ChapterOrder.pick(it, scanlator).id })
    }

    /** [notify] false: the caller already told the user (the swipe's own "Undo" snackbar). */
    fun deleteDownloads(ids: Collection<Long>, notify: Boolean = true) = viewModelScope.launch(ScreenErrors) {
        downloads.deleteChapterFiles(ids)
        clearSelection()
        if (notify) _events.send(CatalogEvent.Message(R.string.title_downloads_deleted))
    }

    /** Deletes downloaded files of read chapters of this title. */
    fun deleteReadDownloads() = viewModelScope.launch(ScreenErrors) { reportDeleted(downloads.deleteReadDownloads(entryId)) }

    /** Deletes every downloaded chapter of this title and its waiting downloads. */
    fun deleteAllDownloads() = viewModelScope.launch(ScreenErrors) { reportDeleted(downloads.deleteAllDownloads(entryId)) }

    private suspend fun reportDeleted(n: Int) {
        _events.send(if (n > 0) CatalogEvent.Count(R.plurals.title_downloads_deleted_count, n) else CatalogEvent.Message(R.string.title_downloads_none))
    }

    /** The chapter to continue with (see [ChapterOrder.continueWith]); groups hidden by the filter are skipped. */
    fun continueChapter(): ChapterInfo? {
        val all = allChapters.value.orEmpty()
        val d = display.value
        val allowed = all.filter { d.allows(it) }.ifEmpty { all }
        return ChapterOrder.continueWith(ChapterOrder.reading(allowed))
    }

    fun downloadBook(link: SBookLink) = viewModelScope.launch(ScreenErrors) {
        val e = entry.value ?: return@launch
        if (link.isAudioStream) {
            // Streams are listened to online: the book joins the library and opens in the player.
            _events.send(CatalogEvent.Message(R.string.title_audio_adding))
            runCatchingApp { audiobooks.addStream(link.url, e.title, e.author, e.coverUrl, e.description) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is ImportOutcome.Added -> _events.send(CatalogEvent.OpenBook(outcome.bookId))
                        is ImportOutcome.Duplicate -> _events.send(CatalogEvent.OpenBook(outcome.bookId))
                        is ImportOutcome.Failed -> _events.send(CatalogEvent.Failure(outcome.error))
                    }
                }
                .onFailure { _events.send(CatalogEvent.Failure(it)) }
            return@launch
        }
        runCatchingApp {
            val headers = runCatching { catalog.source(e.sourceId).imageHeaders() }.getOrDefault(emptyMap())
            downloads.enqueueBook(e.title, link.url, link.mimeType, e.sourceId, headers)
        }.onSuccess { _events.send(CatalogEvent.Message(R.string.title_book_queued)) }
            .onFailure { _events.send(CatalogEvent.Failure(it)) }
    }
}

// endregion
