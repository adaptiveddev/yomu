package app.yomu.feature.library

import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.Files
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.runCatchingApp
import app.yomu.core.database.TrackEntity
import app.yomu.data.tracking.TrackerManager
import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.feature.catalog.TrackSearchState
import app.yomu.feature.catalog.TrackingViewModel
import app.yomu.data.repository.AnnotationRepositoryImpl
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.data.repository.StatsRepositoryImpl
import app.yomu.data.translation.BookTranslationWorker
import app.yomu.data.translation.BookTranslator
import app.yomu.data.translation.TranslationService
import app.yomu.domain.model.Book
import app.yomu.domain.model.ReadingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class BookExtras(val highlights: Int = 0, val notes: Int = 0, val bookmarks: Int = 0, val readingMs: Long = 0, val collections: Set<Long> = emptySet())

class BookDetailsViewModel(
    private val bookId: Long,
    private val context: Context,
    private val books: BookRepositoryImpl,
    private val annotations: AnnotationRepositoryImpl,
    private val stats: StatsRepositoryImpl,
    private val translation: TranslationService,
    private val bookTranslator: BookTranslator,
    private val trackers: TrackerManager,
) : ViewModel(), TrackingViewModel {
    /** null while loading; a deleted book emits [Missing]. */
    val book: StateFlow<Book?> = books.observeBook(bookId).map { it ?: MISSING }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val collections = books.observeCollections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allTags = books.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val refresh = MutableStateFlow(0)
    val extras: StateFlow<BookExtras> = combine(
        annotations.observeHighlights(bookId), annotations.observeNotes(bookId), annotations.observeBookmarks(bookId), refresh,
    ) { h, n, b, _ ->
        BookExtras(h.size, n.size, b.size, stats.timeForBook(bookId), books.collectionsOf(bookId).toSet())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookExtras())

    override val localFile: Boolean = true
    override val tracks = trackers.observeBook(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    override val trackerVersion = trackers.store.version
    override val trackSearch = MutableStateFlow<TrackSearchState?>(null)
    private var trackSearchJob: Job? = null
    override fun trackerSignedIn(service: TrackService) = trackers.tracker(service).loggedIn()
    override fun refreshTracks() = viewModelScope.launch(ScreenErrors) { runCatchingApp { trackers.refreshBook(bookId) } }
    override fun openTrackSearch(service: TrackService) {
        trackSearch.value = TrackSearchState(service, book.value?.title.orEmpty())
        searchTrack(trackSearch.value!!.query)
    }
    override fun closeTrackSearch() { trackSearchJob?.cancel(); trackSearch.value = null }
    override fun searchTrack(query: String) {
        val state = trackSearch.value ?: return
        trackSearchJob?.cancel()
        if (query.isBlank()) { trackSearch.value = state.copy(query = query, loading = false, results = null, error = null); return }
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
        runCatchingApp { trackers.bindBook(bookId, state.service, result) }
            .onSuccess { trackSearch.value = null; Toast.makeText(context, context.getString(app.yomu.feature.catalog.R.string.track_linked, state.service.displayName), Toast.LENGTH_SHORT).show() }
            .onFailure { e -> trackSearch.update { it?.copy(loading = false, error = e) } }
    }
    override fun updateTrack(track: TrackEntity, transform: (TrackEntry) -> TrackEntry) = viewModelScope.launch(ScreenErrors) {
        runCatchingApp { trackers.edit(track, transform) }
            .onSuccess { if (!it) Toast.makeText(context, app.yomu.feature.catalog.R.string.track_pending, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, it.message ?: "Tracking error", Toast.LENGTH_LONG).show() }
    }
    override fun unbindTrack(track: TrackEntity, removeRemote: Boolean) = viewModelScope.launch(ScreenErrors) {
        runCatchingApp { trackers.unbind(track, removeRemote) }.onFailure { Toast.makeText(context, it.message ?: "Tracking error", Toast.LENGTH_LONG).show() }
    }

    fun save(updated: Book) = viewModelScope.launch(ScreenErrors) { books.update(updated) }
    fun setStatus(status: ReadingStatus) = viewModelScope.launch(ScreenErrors) { books.setStatus(listOf(bookId), status) }
    fun setManga(value: Boolean) = viewModelScope.launch(ScreenErrors) { books.setManga(bookId, value) }
    fun setTags(tags: List<String>) = viewModelScope.launch(ScreenErrors) { books.setTags(bookId, tags) }

    fun toggleCollection(id: Long, member: Boolean) = viewModelScope.launch(ScreenErrors) {
        if (member) books.removeFromCollection(listOf(bookId), id) else books.addToCollection(listOf(bookId), id)
        refresh.value++
    }

    fun setCover(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: return@launch
        books.setCover(bookId, bytes)
    }

    fun delete(deleteFiles: Boolean) = viewModelScope.launch(ScreenErrors) { books.delete(listOf(bookId), deleteFiles) }

    suspend fun defaultTarget(): String = translation.targetLanguage()

    /** [chapters] (indexes of the reading order) limits the translation to them; null translates the whole book. */
    fun translateBook(target: String, bilingual: Boolean, chapters: Set<Int>? = null) {
        BookTranslationWorker.start(context, bookId, target, bilingual, null, chapters)
    }

    /** The book's chapters for choosing what to translate (empty when the file cannot be read). */
    suspend fun translationChapters(): List<BookTranslator.Chapter> = runCatching { bookTranslator.chapters(bookId) }.getOrDefault(emptyList())

    /** Intent that shares the original file (app copy through the FileProvider, SAF links directly). */
    suspend fun shareIntent(): Intent? = withContext(Dispatchers.IO) {
        val b = books.getBook(bookId) ?: return@withContext null
        val uri = Uri.parse(b.uri)
        val shareUri = if (uri.scheme == "file") {
            val file = File(uri.path!!)
            if (file.isDirectory) return@withContext null
            FileProvider.getUriForFile(context, context.packageName + ".files", file)
        } else {
            uri
        }
        Intent(Intent.ACTION_SEND).setType(Files.mimeOf(b.format.extensions.firstOrNull().orEmpty()))
            .putExtra(Intent.EXTRA_STREAM, shareUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        val MISSING = Book(id = -1, title = "", format = app.yomu.domain.model.BookFormat.UNKNOWN, uri = "")
    }
}
