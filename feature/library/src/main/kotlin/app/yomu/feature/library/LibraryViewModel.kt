package app.yomu.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import app.yomu.core.common.ScreenErrors
import app.yomu.core.datastore.AppSettings
import app.yomu.core.datastore.stateIn
import app.yomu.data.catalog.CatalogRepository
import app.yomu.data.download.DownloadOverview
import app.yomu.data.download.DownloadSummary
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.data.library.LibraryTabs
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.data.work.LibraryScanWorker
import app.yomu.domain.catalog.NaturalOrder
import app.yomu.domain.model.BatchMetadata
import app.yomu.domain.model.Book
import app.yomu.domain.model.CatalogEntry
import app.yomu.domain.model.BookFormat
import app.yomu.domain.model.LibraryFilter
import app.yomu.domain.model.LibraryQuery
import app.yomu.domain.model.LibrarySort
import app.yomu.domain.model.ReadingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import app.yomu.data.audio.AudiobookStore
import app.yomu.data.audio.YouTubeResult

/** "Type" options of the library filter: groups of book formats and the online titles. */
enum class FormatGroup(val formats: Set<BookFormat>) {
    BOOKS(setOf(BookFormat.EPUB, BookFormat.FB2, BookFormat.TXT, BookFormat.HTML, BookFormat.DOCX, BookFormat.MOBI)),
    PDF(setOf(BookFormat.PDF)),
    COMICS(setOf(BookFormat.CBZ, BookFormat.CBR, BookFormat.CB7, BookFormat.FOLDER)),
    AUDIO(setOf(BookFormat.AUDIO)),
    ONLINE(emptySet()),
}

/** An item of "Continue reading": a book or an online title. */
sealed interface ContinueItem {
    val time: Long

    data class BookItem(val book: Book) : ContinueItem {
        override val time: Long get() = book.lastReadAt ?: 0
    }

    data class OnlineItem(val entry: CatalogEntry) : ContinueItem {
        override val time: Long get() = entry.lastReadAt
    }
}

/** A YouTube search started from "Audiobook by link". */
data class AudioSearch(val query: String, val loading: Boolean = true, val results: List<YouTubeResult> = emptyList(), val error: Throwable? = null)

/**
 * The tab picker of the library: the [books] or the online title [online] it is for, and how many of them each tab
 * already holds.
 */
data class TabPick(val books: Set<Long> = emptySet(), val online: Long? = null, val counts: Map<Long, Int> = emptyMap(), val title: String? = null) {
    val size: Int get() = if (online != null) 1 else books.size
}

/** Reading state of an online title in the library's status filter. */
internal fun CatalogEntry.readingStatus(): ReadingStatus = when {
    lastReadAt <= 0 -> ReadingStatus.NEW
    unread > 0 -> ReadingStatus.READING
    else -> ReadingStatus.FINISHED
}

/** Online titles of the current tab that pass the search and filters, in the library's order. */
internal fun List<CatalogEntry>.forLibrary(
    filter: LibraryFilter,
    types: Set<FormatGroup>,
    search: String,
    tabsOf: Map<Long, Set<Long>>,
    sort: LibrarySort,
    ascending: Boolean,
): List<CatalogEntry> {
    if (types.isNotEmpty() && FormatGroup.ONLINE !in types) return emptyList()
    // Titles of the sources carry no language: a language filter is about the books.
    if (filter.language != null) return emptyList()
    val q = search.trim()
    val shown = filter { e ->
        (filter.collectionId == null || tabsOf[e.id]?.contains(filter.collectionId) == true) &&
            (q.isEmpty() || e.title.contains(q, ignoreCase = true) || e.author?.contains(q, ignoreCase = true) == true) &&
            (filter.statuses.isEmpty() || e.readingStatus() in filter.statuses) &&
            (filter.tag == null || e.genres.any { it.equals(filter.tag, ignoreCase = true) })
    }
    val byTitle = compareBy(NaturalOrder) { e: CatalogEntry -> e.title }
    val primary: Comparator<CatalogEntry> = when (sort) {
        LibrarySort.TITLE -> byTitle
        LibrarySort.AUTHOR -> compareBy(NaturalOrder) { e: CatalogEntry -> e.author.orEmpty() }
        LibrarySort.ADDED -> compareBy { it.addedAt }
        LibrarySort.LAST_READ -> compareBy { it.lastReadAt }
        LibrarySort.PROGRESS -> compareBy { it.readFraction() }
    }
    return shown.sortedWith((if (ascending) primary else primary.reversed()).then(byTitle))
}

/** Share of the known chapters that are read. */
internal fun CatalogEntry.readFraction(): Float = if (chapterCount <= 0) 0f else ((chapterCount - unread).coerceAtLeast(0).toFloat() / chapterCount)

sealed interface LibraryEvent {
    data class Imported(val added: Int, val duplicates: Int, val failed: Int, val firstError: Throwable?, val singleBookId: Long?) : LibraryEvent
    data class Deleted(val ids: Set<Long>) : LibraryEvent
    data class Message(val text: Int) : LibraryEvent
    data class OpenBook(val book: app.yomu.domain.model.Book) : LibraryEvent
    data class ReadChapter(val chapterId: Long) : LibraryEvent
    data class OpenTitle(val itemId: Long) : LibraryEvent
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class LibraryViewModel(
    private val context: Context,
    private val books: BookRepositoryImpl,
    private val importer: ImportService,
    private val settings: AppSettings,
    private val tabs: LibraryTabs,
    private val catalog: CatalogRepository,
    private val audiobooks: AudiobookStore,
    private val history: app.yomu.data.repository.ReadingHistoryRepository,
    downloads: DownloadOverview,
) : ViewModel() {
    private val search = MutableStateFlow("")
    private val filter = MutableStateFlow(LibraryFilter())
    private val types = MutableStateFlow<Set<FormatGroup>>(emptySet())
    private val _pick = MutableStateFlow<TabPick?>(null)
    private val pendingDelete = MutableStateFlow<Set<Long>>(emptySet())
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)

    val events: Flow<LibraryEvent> = _events.receiveAsFlow()
    val searchText: StateFlow<String> = search
    val filterState: StateFlow<LibraryFilter> = filter
    val selection: StateFlow<Set<Long>> = _selection
    val sort = settings.librarySort.stateIn(viewModelScope)
    val ascending = settings.libraryAscending.stateIn(viewModelScope)
    val grid = settings.libraryGrid.stateIn(viewModelScope)
    val columns = settings.gridColumns.stateIn(viewModelScope)
    val showContinue = settings.showContinueReading.stateIn(viewModelScope)
    val incognito = settings.incognito.stateIn(viewModelScope)

    fun setIncognito(on: Boolean) = viewModelScope.launch(ScreenErrors) { settings.incognito.set(on) }
    val importProgress = importer.progress
    val downloadSummary: StateFlow<DownloadSummary> = downloads.summary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadSummary())

    private val query: Flow<LibraryQuery> = combine(search.debounce(200), settings.librarySort.flow, settings.libraryAscending.flow, filter) { s, so, asc, f ->
        LibraryQuery(s, so, asc, f)
    }

    val paged: Flow<PagingData<Book>> = query.flatMapLatest { books.pagedBooks(it) }.cachedIn(viewModelScope)
        .combine(pendingDelete) { data, pending -> if (pending.isEmpty()) data else data.filter { it.id !in pending } }

    val continueReading = books.observeContinueReading(12)
        .combine(pendingDelete) { l, p -> l.filter { it.id !in p } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val collections = books.observeCollections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags = books.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val languages = books.observeLanguages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val total: StateFlow<Int> = books.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    /** The "Type" filter (book formats and online titles); empty shows everything. */
    val typeFilter: StateFlow<Set<FormatGroup>> = types

    /** False when the type filter leaves only the online titles. */
    val showBooks: StateFlow<Boolean> = types.map { t -> t.isEmpty() || t.any { it != FormatGroup.ONLINE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Number of active filters (type, status, tag, language), for the badge of the filter button. */
    val activeFilters: StateFlow<Int> = combine(filter, types) { f, t -> t.size + f.statuses.size + (if (f.tag != null) 1 else 0) + (if (f.language != null) 1 else 0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ---- online titles (added with the heart in the catalog) ----
    private val onlineAll: StateFlow<List<CatalogEntry>> = catalog.observeFavorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tabs of every online title: title id → tab ids. */
    val onlineTabs: StateFlow<Map<Long, Set<Long>>> = tabs.observeOnline().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    /** Number of online titles; -1 until they are loaded. */
    val onlineCount: StateFlow<Int> = catalog.observeFavorites().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    /** Online titles of the current tab that pass the search and the filters. */
    val onlineShown: StateFlow<List<CatalogEntry>> = combine(
        onlineAll, onlineTabs, filter, types,
        combine(search.debounce(200), settings.librarySort.flow, settings.libraryAscending.flow) { q, so, asc -> Triple(q, so, asc) },
    ) { list, tabsOf, f, t, (q, so, asc) -> list.forLibrary(f, t, q, tabsOf, so, asc) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Books and online titles in progress, the most recent first. */
    val continueItems: StateFlow<List<ContinueItem>> = combine(continueReading, onlineAll) { b, o ->
        (b.map { ContinueItem.BookItem(it) } + o.filter { it.lastReadAt > 0 && it.unread > 0 }.map { ContinueItem.OnlineItem(it) })
            .sortedByDescending { it.time }
            .take(CONTINUE_MAX)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The open tab picker, if any. */
    val tabPick: StateFlow<TabPick?> = _pick

    fun setSearch(text: String) { search.value = text }

    fun setSort(value: LibrarySort) = viewModelScope.launch(ScreenErrors) {
        if (settings.librarySort.get() == value) settings.libraryAscending.update { !it } else settings.librarySort.set(value)
    }

    fun setGrid(value: Boolean) = viewModelScope.launch(ScreenErrors) { settings.libraryGrid.set(value) }
    fun setColumns(value: Int) = viewModelScope.launch(ScreenErrors) { settings.gridColumns.set(value) }

    fun toggleGroup(group: FormatGroup) {
        val next = types.updateAndGet { if (group in it) it - group else it + group }
        filter.update { f -> f.copy(formats = next.flatMap { it.formats }.toSet()) }
    }

    fun toggleStatus(status: ReadingStatus) = filter.update { f ->
        f.copy(statuses = if (status in f.statuses) f.statuses - status else f.statuses + status)
    }

    fun setCollection(id: Long?) = filter.update { it.copy(collectionId = id) }
    fun setTag(tag: String?) = filter.update { it.copy(tag = tag) }
    fun setLanguage(lang: String?) = filter.update { it.copy(language = lang) }
    fun clearFilters() {
        types.value = emptySet()
        filter.update { LibraryFilter(collectionId = it.collectionId) }
    }

    // ---- selection ----
    fun toggleSelect(id: Long) = _selection.update { if (id in it) it - id else it + id }
    fun clearSelection() { _selection.value = emptySet() }

    fun selectAll() = viewModelScope.launch(ScreenErrors) {
        val q = LibraryQuery(search.value, settings.librarySort.get(), settings.libraryAscending.get(), filter.value)
        _selection.value = books.observeBooks(q).first().map { it.id }.filter { it !in pendingDelete.value }.toSet()
    }

    /** "Continue" on an online title opens the chapter to read next; the title page only when it has no chapters. */
    fun continueOnline(itemId: Long) = viewModelScope.launch(ScreenErrors) {
        val chapter = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { history.resumeChapter(itemId, null) }
        _events.send(if (chapter != null) LibraryEvent.ReadChapter(chapter) else LibraryEvent.OpenTitle(itemId))
    }

    // ---- import ----
    fun import(uris: List<Uri>, link: Boolean? = null) = viewModelScope.launch(ScreenErrors) {
        if (uris.isEmpty()) return@launch
        report(importer.import(uris, link))
    }

    /** A YouTube video or playlist, a podcast feed or an audio link becomes an audiobook, opened right away; words search YouTube. */
    fun addAudioLink(text: String) = viewModelScope.launch(ScreenErrors) {
        if (text.isBlank()) return@launch
        if (AudiobookStore.extractUrl(text) == null) {
            searchYouTube(text.trim())
            return@launch
        }
        _events.send(LibraryEvent.Message(R.string.library_audio_link_adding))
        val outcome = runCatching { audiobooks.addLink(text) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrElse { ImportOutcome.Failed(text, it) }
        openOrReport(outcome)
    }

    private val _audioSearch = MutableStateFlow<AudioSearch?>(null)
    val audioSearch: StateFlow<AudioSearch?> = _audioSearch
    private var audioSearchJob: kotlinx.coroutines.Job? = null

    fun searchYouTube(query: String) {
        audioSearchJob?.cancel()
        _audioSearch.value = AudioSearch(query)
        audioSearchJob = viewModelScope.launch {
            val result = runCatching { audiobooks.searchYouTube(query) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            _audioSearch.value = AudioSearch(query, loading = false, results = result.getOrDefault(emptyList()), error = result.exceptionOrNull())
        }
    }

    fun closeAudioSearch() {
        audioSearchJob?.cancel()
        _audioSearch.value = null
    }

    fun addYouTubeResult(result: YouTubeResult) = viewModelScope.launch(ScreenErrors) {
        closeAudioSearch()
        _events.send(LibraryEvent.Message(R.string.library_audio_link_adding))
        val outcome = runCatching { audiobooks.addYouTube(result) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrElse { ImportOutcome.Failed(result.title, it) }
        openOrReport(outcome)
    }

    private suspend fun openOrReport(outcome: ImportOutcome) {
        val id = (outcome as? ImportOutcome.Added)?.bookId ?: (outcome as? ImportOutcome.Duplicate)?.bookId
        val book = id?.let { books.getBook(it) }
        if (book != null) _events.send(LibraryEvent.OpenBook(book)) else report(listOf(outcome))
    }

    fun importFolderAsBook(tree: Uri) = viewModelScope.launch(ScreenErrors) {
        persist(tree)
        val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)?.name ?: "Folder"
        report(listOf(importer.importFolder(tree, name)))
    }

    fun addScanFolder(tree: Uri) = viewModelScope.launch(ScreenErrors) {
        persist(tree)
        settings.scanFolders.update { it + tree.toString() }
        report(importer.scanTree(tree))
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** Pull-to-refresh: scans the scan folders in place and reports what was found. */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch(ScreenErrors) {
            _refreshing.value = true
            try {
                val folders = settings.scanFolders.get()
                if (folders.isEmpty()) {
                    _events.send(LibraryEvent.Message(R.string.library_no_scan_folders))
                    return@launch
                }
                val outcomes = folders.flatMap { runCatching { importer.scanTree(Uri.parse(it)) }.getOrDefault(emptyList()) }
                if (outcomes.any { it is ImportOutcome.Added || it is ImportOutcome.Failed }) report(outcomes)
                else _events.send(LibraryEvent.Message(R.string.library_up_to_date))
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun rescan() = LibraryScanWorker.runNow(context).let { _events.trySend(LibraryEvent.Message(R.string.library_scan_started)) }

    private fun persist(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private suspend fun report(result: List<ImportOutcome>) {
        val added = result.filterIsInstance<ImportOutcome.Added>()
        val dup = result.count { it is ImportOutcome.Duplicate }
        val failed = result.filterIsInstance<ImportOutcome.Failed>()
        val single = if (result.size == 1) (result.first() as? ImportOutcome.Added)?.bookId ?: (result.first() as? ImportOutcome.Duplicate)?.bookId else null
        _events.send(LibraryEvent.Imported(added.size, dup, failed.size, failed.firstOrNull()?.error, single))
    }

    // ---- bulk actions ----
    fun setStatus(ids: Set<Long>, status: ReadingStatus) = viewModelScope.launch(ScreenErrors) { books.setStatus(ids, status); clearSelection() }

    fun addToCollection(ids: Set<Long>, collectionId: Long) = viewModelScope.launch(ScreenErrors) { books.addToCollection(ids, collectionId); clearSelection() }

    fun removeFromCollection(ids: Set<Long>, collectionId: Long) = viewModelScope.launch(ScreenErrors) { books.removeFromCollection(ids, collectionId); clearSelection() }

    /** Creates a tab at the end of the row (with the [add] books in it). */
    fun createCollection(name: String, add: Set<Long> = emptySet()) = viewModelScope.launch(ScreenErrors) {
        if (name.isBlank()) return@launch
        val id = tabs.create(name)
        if (add.isNotEmpty()) books.addToCollection(add, id)
        clearSelection()
    }

    fun renameCollection(id: Long, name: String) = viewModelScope.launch(ScreenErrors) { if (name.isNotBlank()) tabs.rename(id, name) }

    fun deleteCollection(id: Long) = viewModelScope.launch(ScreenErrors) {
        if (filter.value.collectionId == id) setCollection(null)
        tabs.delete(id)
    }

    /** Moves a tab one place left ([delta] < 0) or right. */
    fun moveCollection(id: Long, delta: Int) = viewModelScope.launch(ScreenErrors) { tabs.move(id, delta) }

    // ---- tab picker (books of the selection, or one online title) ----
    fun pickTabsForBooks(ids: Set<Long>) = viewModelScope.launch(ScreenErrors) {
        if (ids.isNotEmpty()) _pick.value = TabPick(books = ids, counts = bookCounts(ids))
    }

    fun pickTabsForOnline(itemId: Long, title: String? = null) = viewModelScope.launch(ScreenErrors) {
        _pick.value = TabPick(online = itemId, counts = tabs.onlineTabsOf(itemId).associateWith { 1 }, title = title)
    }

    /** Puts every item of the picker in the tab, or takes them all out when all of them are already there. */
    fun togglePickTab(tabId: Long) = viewModelScope.launch(ScreenErrors) {
        val p = _pick.value ?: return@launch
        val join = (p.counts[tabId] ?: 0) < p.size
        applyPick(p, tabId, join)
    }

    fun createTabInPick(name: String) = viewModelScope.launch(ScreenErrors) {
        if (name.isBlank()) return@launch
        val id = tabs.create(name)
        _pick.value?.let { applyPick(it, id, true) }
    }

    fun closeTabPick() {
        val p = _pick.value
        _pick.value = null
        if (p != null && p.books.isNotEmpty()) clearSelection()
    }

    private suspend fun applyPick(p: TabPick, tabId: Long, join: Boolean) {
        if (p.online != null) tabs.setOnline(listOf(p.online), tabId, join) else tabs.setBooks(p.books, tabId, join)
        val counts = if (p.online != null) tabs.onlineTabsOf(p.online).associateWith { 1 } else bookCounts(p.books)
        // The picker may have been closed meanwhile.
        _pick.update { current -> if (current == null) null else p.copy(counts = counts) }
    }

    private suspend fun bookCounts(ids: Set<Long>): Map<Long, Int> = ids.flatMap { tabs.bookTabsOf(it) }.groupingBy { it }.eachCount()

    /** Removes an online title from the library (and from its tabs). */
    fun removeOnline(itemId: Long) = viewModelScope.launch(ScreenErrors) { catalog.setFavorite(itemId, false) }

    fun addTag(ids: Set<Long>, tag: String) = viewModelScope.launch(ScreenErrors) { books.addTag(ids, tag); clearSelection() }

    // ---- batch metadata editor ----
    private val _batch = MutableStateFlow<List<Book>?>(null)

    /** The books the batch metadata editor is open for (null: closed). */
    val batch: StateFlow<List<Book>?> = _batch
    private var batchOrder: List<Long> = emptyList()

    fun openBatchEdit(ids: Set<Long>) = viewModelScope.launch(ScreenErrors) {
        if (ids.isEmpty()) return@launch
        batchOrder = ids.toList()
        _batch.value = ids.mapNotNull { books.getBook(it) }
    }

    fun closeBatchEdit() { _batch.value = null }

    fun applyBatchEdit(edit: BatchMetadata) = viewModelScope.launch(ScreenErrors) {
        val list = _batch.value ?: return@launch
        _batch.value = null
        if (!edit.isEmpty) {
            books.batchEdit(edit.ordered(list, batchOrder), edit)
            _events.send(LibraryEvent.Message(R.string.library_batch_saved))
        }
        clearSelection()
    }

    /** Hides the books at once; they are removed for real when the undo snackbar is dismissed. */
    fun requestDelete(ids: Set<Long>) {
        pendingDelete.update { it + ids }
        clearSelection()
        _events.trySend(LibraryEvent.Deleted(ids))
    }

    fun undoDelete(ids: Set<Long>) = pendingDelete.update { it - ids }

    fun commitDelete(ids: Set<Long>, deleteFiles: Boolean = false) = viewModelScope.launch(ScreenErrors) {
        books.delete(ids, deleteFiles)
        pendingDelete.update { it - ids }
        clearSelection()
    }

    override fun onCleared() {
        val pending = pendingDelete.value
        if (pending.isNotEmpty()) kotlinx.coroutines.GlobalScope.launch { books.delete(pending, false) }
        super.onCleared()
    }
}

/** Items of "Continue reading" (the large card and the row after it). */
private const val CONTINUE_MAX = 12
