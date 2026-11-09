package app.yomu.feature.annotations

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.UiState
import app.yomu.data.repository.AnnotationRepositoryImpl
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.domain.export.AnnotationExporter
import app.yomu.data.export.AnkiPackageWriter
import app.yomu.domain.export.AnnotationFormat
import app.yomu.domain.model.Bookmark
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.HighlightColor
import app.yomu.domain.model.LibraryQuery
import app.yomu.domain.model.Locator
import app.yomu.domain.model.Note
import app.yomu.domain.text.TextTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AnnotationTab { HIGHLIGHTS, NOTES, BOOKMARKS, QUOTES }
enum class AnnotationSort { POSITION, DATE }

/** One row of the annotations list. */
sealed interface AnnotationEntry {
    val key: String
    val bookId: Long
    val bookTitle: String
    val createdAt: Long
    val locator: Locator?
    val text: String

    data class H(val highlight: Highlight, override val bookTitle: String) : AnnotationEntry {
        override val key get() = "h${highlight.id}"
        override val bookId get() = highlight.bookId
        override val createdAt get() = highlight.createdAt
        override val locator get() = highlight.locator
        override val text get() = highlight.text
    }

    data class N(val note: Note, override val bookTitle: String) : AnnotationEntry {
        override val key get() = "n${note.id}"
        override val bookId get() = note.bookId
        override val createdAt get() = note.createdAt
        override val locator get() = note.locator
        override val text get() = note.text
    }

    data class B(val bookmark: Bookmark, override val bookTitle: String) : AnnotationEntry {
        override val key get() = "b${bookmark.id}"
        override val bookId get() = bookmark.bookId
        override val createdAt get() = bookmark.createdAt
        override val locator get() = bookmark.locator
        override val text get() = bookmark.title
    }
}

data class AnnotationsFilter(
    val tab: AnnotationTab = AnnotationTab.HIGHLIGHTS,
    val query: String = "",
    val color: HighlightColor? = null,
    val favoritesOnly: Boolean = false,
    val sort: AnnotationSort = AnnotationSort.POSITION,
)

sealed interface AnnotationsEvent {
    data class Deleted(val entry: AnnotationEntry) : AnnotationsEvent
    data class Imported(val added: Int, val skipped: Int) : AnnotationsEvent
    data object Exported : AnnotationsEvent
    data class Failed(val error: Throwable) : AnnotationsEvent
}

/** Annotations of one book ([bookId] >= 0) or of the whole library ([bookId] < 0). */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationsViewModel(
    val bookId: Long,
    private val context: Context,
    private val repo: AnnotationRepositoryImpl,
    private val books: BookRepositoryImpl,
) : ViewModel() {
    val allBooks: Boolean get() = bookId < 0

    private val _filter = MutableStateFlow(AnnotationsFilter())
    val filter: StateFlow<AnnotationsFilter> = _filter

    private val events = Channel<AnnotationsEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val title: StateFlow<String?> = (if (allBooks) kotlinx.coroutines.flow.flowOf(null) else books.observeBook(bookId).map { it?.title })
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val titles: Flow<Map<Long, String>> =
        if (allBooks) books.observeBooks(LibraryQuery()).map { l -> l.associate { it.id to it.title } }
        else books.observeBook(bookId).map { b -> if (b == null) emptyMap() else mapOf(b.id to b.title) }

    private val entries: Flow<List<AnnotationEntry>> = combine(
        if (allBooks) repo.observeAllHighlights() else repo.observeHighlights(bookId),
        if (allBooks) repo.observeAllNotes() else repo.observeNotes(bookId),
        if (allBooks) repo.observeAllBookmarks() else repo.observeBookmarks(bookId),
        titles,
    ) { hs, ns, bs, t ->
        hs.map { AnnotationEntry.H(it, t[it.bookId].orEmpty()) } +
            ns.map { AnnotationEntry.N(it, t[it.bookId].orEmpty()) } +
            bs.map { AnnotationEntry.B(it, t[it.bookId].orEmpty()) }
    }

    val counts: StateFlow<Map<AnnotationTab, Int>> = entries.map { all ->
        AnnotationTab.entries.associateWith { tab -> all.count { it.belongsTo(tab) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val items: StateFlow<UiState<List<AnnotationEntry>>> = combine(entries, _filter) { all, f ->
        val q = f.query.trim().lowercase()
        val list = all.asSequence()
            .filter { it.belongsTo(f.tab) }
            .filter { e ->
                when (e) {
                    is AnnotationEntry.H -> (f.color == null || e.highlight.color == f.color) && (!f.favoritesOnly || e.highlight.isFavorite)
                    else -> f.color == null && !f.favoritesOnly
                }
            }
            .filter { e ->
                q.isEmpty() || e.text.lowercase().contains(q) || e.bookTitle.lowercase().contains(q) ||
                    (e is AnnotationEntry.H && e.highlight.note.orEmpty().lowercase().contains(q)) ||
                    (e is AnnotationEntry.H && e.highlight.tags.any { it.lowercase().contains(q) })
            }
            .toList()
        val sorted = when (f.sort) {
            AnnotationSort.DATE -> list.sortedByDescending { it.createdAt }
            AnnotationSort.POSITION -> list.sortedWith(
                compareBy<AnnotationEntry> { if (allBooks) it.bookTitle.lowercase() else "" }
                    .thenBy { it.locator?.chapter ?: Int.MAX_VALUE }
                    .thenBy { it.locator?.offset ?: ((it.locator?.progression ?: 0f) * 1_000_000).toInt() },
            )
        }
        if (sorted.isEmpty()) UiState.Empty else UiState.Data(sorted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    private fun AnnotationEntry.belongsTo(tab: AnnotationTab) = when (tab) {
        AnnotationTab.HIGHLIGHTS -> this is AnnotationEntry.H
        AnnotationTab.QUOTES -> this is AnnotationEntry.H && highlight.isQuote
        AnnotationTab.NOTES -> this is AnnotationEntry.N || (this is AnnotationEntry.H && !highlight.note.isNullOrBlank())
        AnnotationTab.BOOKMARKS -> this is AnnotationEntry.B
    }

    fun setTab(tab: AnnotationTab) = _filter.update { copy(tab = tab) }
    fun setQuery(q: String) = _filter.update { copy(query = q) }
    fun setColor(c: HighlightColor?) = _filter.update { copy(color = c) }
    fun toggleFavorites() = _filter.update { copy(favoritesOnly = !favoritesOnly) }
    fun toggleSort() = _filter.update { copy(sort = if (sort == AnnotationSort.POSITION) AnnotationSort.DATE else AnnotationSort.POSITION) }

    private inline fun MutableStateFlow<AnnotationsFilter>.update(f: AnnotationsFilter.() -> AnnotationsFilter) { value = value.f() }

    fun delete(entry: AnnotationEntry) = viewModelScope.launch(ScreenErrors) {
        when (entry) {
            is AnnotationEntry.H -> repo.deleteHighlight(entry.highlight.id)
            is AnnotationEntry.N -> repo.deleteNote(entry.note.id)
            is AnnotationEntry.B -> repo.deleteBookmark(entry.bookmark.id)
        }
        events.send(AnnotationsEvent.Deleted(entry))
    }

    fun undoDelete(entry: AnnotationEntry) = viewModelScope.launch(ScreenErrors) {
        when (entry) {
            is AnnotationEntry.H -> repo.restore(highlight = entry.highlight)
            is AnnotationEntry.N -> repo.restore(note = entry.note)
            is AnnotationEntry.B -> repo.restore(bookmark = entry.bookmark)
        }
    }

    fun saveNote(entry: AnnotationEntry, text: String) = viewModelScope.launch(ScreenErrors) {
        when (entry) {
            is AnnotationEntry.H -> repo.saveHighlight(entry.highlight.copy(note = text.ifBlank { null }, tags = TextTools.hashtags(text)))
            is AnnotationEntry.N -> if (text.isBlank()) repo.deleteNote(entry.note.id) else repo.saveNote(entry.note.copy(text = text, tags = TextTools.hashtags(text)))
            is AnnotationEntry.B -> repo.saveBookmark(entry.bookmark.copy(title = text.ifBlank { entry.bookmark.title }))
        }
    }

    fun toggleFavorite(entry: AnnotationEntry.H) = viewModelScope.launch(ScreenErrors) {
        repo.saveHighlight(entry.highlight.copy(isFavorite = !entry.highlight.isFavorite))
    }

    fun toggleQuote(entry: AnnotationEntry.H) = viewModelScope.launch(ScreenErrors) {
        repo.saveHighlight(entry.highlight.copy(isQuote = !entry.highlight.isQuote))
    }

    fun recolor(entry: AnnotationEntry.H, color: HighlightColor) = viewModelScope.launch(ScreenErrors) {
        repo.saveHighlight(entry.highlight.copy(color = color))
    }

    suspend fun authorsOf(bookId: Long): String? = books.getBook(bookId)?.authorsText?.takeIf { it.isNotBlank() }

    fun export(format: AnnotationFormat, uri: Uri) = viewModelScope.launch(ScreenErrors) {
        runCatching {
            withContext(Dispatchers.IO) {
                val data = if (allBooks) repo.allAnnotations() else listOfNotNull(repo.bookAnnotations(bookId))
                context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                    if (format == AnnotationFormat.ANKI_APKG) {
                        AnkiPackageWriter.write(AnnotationExporter.ankiDeck(data), out, java.io.File(context.cacheDir, "anki"))
                    } else {
                        out.write(AnnotationExporter.export(data, format).toByteArray())
                    }
                }
            }
        }.onSuccess { events.send(AnnotationsEvent.Exported) }
            .onFailure { events.send(AnnotationsEvent.Failed(it)) }
    }

    /** Imports a Readwise CSV export, matching rows to library books by title. */
    fun importReadwise(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        runCatching {
            withContext(Dispatchers.IO) {
                val csv = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                val rows = AnnotationExporter.parseReadwiseCsv(csv)
                val library = books.observeBooks(LibraryQuery()).first()
                val byTitle = library.associateBy { it.title.trim().lowercase() }
                var added = 0
                var skipped = 0
                val existing = mutableMapOf<Long, Set<String>>()
                rows.forEach { r ->
                    val book = byTitle[r.title.trim().lowercase()]
                    if (book == null) {
                        skipped++
                        return@forEach
                    }
                    val known = existing.getOrPut(book.id) { repo.observeHighlights(book.id).first().map { it.text }.toSet() }
                    if (r.text in known) return@forEach
                    repo.saveHighlight(
                        Highlight(
                            bookId = book.id,
                            locator = Locator(totalProgression = r.location.coerceIn(0f, 1f), text = r.text),
                            text = r.text,
                            note = r.note,
                        ),
                    )
                    added++
                }
                added to skipped
            }
        }.onSuccess { (a, s) -> events.send(AnnotationsEvent.Imported(a, s)) }
            .onFailure { events.send(AnnotationsEvent.Failed(it)) }
    }
}
