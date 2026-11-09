package app.yomu.feature.dictionary

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.AppError
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.Share
import app.yomu.core.common.UiState
import app.yomu.core.datastore.AppSettings
import app.yomu.core.datastore.stateIn
import app.yomu.data.dictionary.DictionaryRepository
import app.yomu.data.export.AnkiPackageWriter
import app.yomu.data.repository.WordRepositoryImpl
import app.yomu.domain.export.WordExporter
import app.yomu.domain.export.WordFormat
import app.yomu.domain.model.DictionaryInfo
import app.yomu.domain.model.LookupResult
import app.yomu.domain.model.ReviewMode
import app.yomu.domain.model.Word
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Dictionary search screen: suggestions, results and lookup history. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DictionaryViewModel(private val dict: DictionaryRepository, private val words: WordRepositoryImpl, private val settings: AppSettings) : ViewModel() {
    val query = MutableStateFlow("")
    private val _result = MutableStateFlow<UiState<LookupResult>?>(null)
    val result: StateFlow<UiState<LookupResult>?> = _result
    val history: StateFlow<List<String>> = dict.observeHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val dictionaries: StateFlow<List<DictionaryInfo>?> = dict.observeDictionaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val suggestions: StateFlow<List<String>> = query.debounce(150).map { q -> runCatching { dict.suggest(q.trim()).take(8) }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val dueCount: StateFlow<Int> = words.observeDueCount(System.currentTimeMillis()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val wordCount: StateFlow<Int> = words.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    private val _savedId = MutableStateFlow<Long?>(null)
    val savedId: StateFlow<Long?> = _savedId
    private var job: Job? = null

    fun setQuery(q: String) {
        query.value = q
        if (q.isBlank()) _result.value = null
    }

    fun search(q: String = query.value) {
        val text = q.trim()
        if (text.isEmpty()) return
        query.value = text
        job?.cancel()
        job = viewModelScope.launch(ScreenErrors) {
            _result.value = UiState.Loading
            _savedId.value = words.find(text, null)?.id
            _result.value = runCatching { dict.lookup(text) }.fold(
                { if (it.entries.isEmpty()) UiState.Empty else UiState.Data(it) },
                { UiState.Error(AppError.from(it)) },
            )
        }
    }

    fun toggleSave() = viewModelScope.launch(ScreenErrors) {
        val r = (_result.value as? UiState.Data)?.value ?: return@launch
        val id = _savedId.value
        if (id != null) {
            words.delete(listOf(id))
            _savedId.value = null
        } else {
            val e = r.entries.firstOrNull()
            _savedId.value = words.save(Word(term = r.matched ?: r.query, definition = e?.definitions?.take(3)?.joinToString("; "), context = e?.examples?.firstOrNull()))
        }
    }

    fun clearHistory() = viewModelScope.launch(ScreenErrors) { dict.clearHistory() }
}

enum class WordSort { DATE, ALPHA, LEVEL, DUE }
enum class LevelFilter(val max: Int?, val min: Int) { ALL(null, 0), NEW(0, 0), LEARNING(3, 1), KNOWN(null, 4) }

sealed interface WordsEvent {
    data class Deleted(val words: List<Word>) : WordsEvent
    data class Imported(val count: Int) : WordsEvent
    data object Exported : WordsEvent
    data class Failed(val error: Throwable) : WordsEvent
}

/** "My words": list, filters, bulk actions, export and import. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class WordsViewModel(private val context: Context, private val words: WordRepositoryImpl, private val settings: AppSettings) : ViewModel() {
    val query = MutableStateFlow("")
    val tag = MutableStateFlow<String?>(null)
    val level = MutableStateFlow(LevelFilter.ALL)
    val sort = MutableStateFlow(WordSort.DATE)
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    val tags: StateFlow<List<String>> = words.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val events = Channel<WordsEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val items: StateFlow<UiState<List<Word>>> = combine(query.debounce(200), tag, level) { q, t, l -> Triple(q, t, l) }
        .flatMapLatest { (q, t, l) -> words.observeWords(q, null, t, l.max).map { list -> list.filter { it.level >= l.min } } }
        .combine(sort) { list, s ->
            val sorted = when (s) {
                WordSort.DATE -> list.sortedByDescending { it.createdAt }
                WordSort.ALPHA -> list.sortedBy { it.term.lowercase() }
                WordSort.LEVEL -> list.sortedWith(compareBy<Word> { it.level }.thenBy { it.term.lowercase() })
                WordSort.DUE -> list.sortedBy { it.srs.dueAt }
            }
            if (sorted.isEmpty()) UiState.Empty else UiState.Data(sorted)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun toggleSelect(id: Long) = selection.update { if (id in it) it - id else it + id }
    fun clearSelection() { selection.value = emptySet() }
    fun selectAll() {
        val list = (items.value as? UiState.Data)?.value ?: return
        selection.value = list.map { it.id }.toSet()
    }

    fun delete(ids: Set<Long>) = viewModelScope.launch(ScreenErrors) {
        val all = (items.value as? UiState.Data)?.value.orEmpty().filter { it.id in ids }
        words.delete(ids)
        selection.value = emptySet()
        events.send(WordsEvent.Deleted(all))
    }

    fun restore(list: List<Word>) = viewModelScope.launch(ScreenErrors) { list.forEach { words.save(it.copy(id = 0)) } }

    fun addTag(ids: Set<Long>, tag: String) = viewModelScope.launch(ScreenErrors) {
        val t = tag.trim().ifEmpty { return@launch }
        (items.value as? UiState.Data)?.value.orEmpty().filter { it.id in ids }.forEach { w -> words.save(w.copy(tags = (w.tags + t).distinct())) }
        selection.value = emptySet()
    }

    fun update(word: Word) = viewModelScope.launch(ScreenErrors) { words.save(word) }

    fun resetProgress(word: Word) = viewModelScope.launch(ScreenErrors) { words.save(word.copy(level = 0, srs = app.yomu.domain.model.SrsState(dueAt = System.currentTimeMillis()))) }

    private suspend fun selectedOrAll(): List<Word> {
        val sel = selection.value
        val all = words.all()
        return if (sel.isEmpty()) all else all.filter { it.id in sel }
    }

    fun export(format: WordFormat, uri: Uri) = viewModelScope.launch(ScreenErrors) {
        runCatching {
            withContext(Dispatchers.IO) {
                val list = selectedOrAll()
                context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                    if (format == WordFormat.ANKI_APKG) AnkiPackageWriter.write(WordExporter.ankiDeck(list), out, File(context.cacheDir, "anki"))
                    else out.write(WordExporter.export(list, format).toByteArray())
                }
            }
        }.onSuccess { events.send(WordsEvent.Exported) }.onFailure { events.send(WordsEvent.Failed(it)) }
    }

    /** Sends an Anki TSV file to AnkiDroid (or any app that accepts text files) through the share sheet. */
    fun sendToAnkiDroid() = viewModelScope.launch(ScreenErrors) {
        runCatching {
            val file = withContext(Dispatchers.IO) {
                File(Share.shareDir(context), "yomu-words.txt").also { it.writeText(WordExporter.export(selectedOrAll(), WordFormat.ANKI_TSV)) }
            }
            val uri = Share.uriFor(context, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/tab-separated-values"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(ANKIDROID)
            }
            if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
            else context.startActivity(Share.fileIntent(context, file, "text/plain"))
        }.onFailure { events.send(WordsEvent.Failed(it)) }
    }

    fun importCsv(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        runCatching {
            withContext(Dispatchers.IO) {
                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                val list = WordExporter.importCsv(text)
                list.forEach { words.save(it) }
                list.size
            }
        }.onSuccess { events.send(WordsEvent.Imported(it)) }.onFailure { events.send(WordsEvent.Failed(it)) }
    }

    companion object {
        const val ANKIDROID = "com.ichi2.anki"
    }
}

data class ReviewCard(val word: Word, val mode: ReviewMode)

data class ReviewUi(
    val loading: Boolean = true,
    val queue: List<ReviewCard> = emptyList(),
    val index: Int = 0,
    val flipped: Boolean = false,
    val reviewedToday: Int = 0,
    val goal: Int = 20,
    val known: Int = 0,
    val unknown: Int = 0,
    val practice: Boolean = false,
) {
    val current: ReviewCard? get() = queue.getOrNull(index)
    val finished: Boolean get() = !loading && current == null
}

/** Spaced-repetition session (SM-2) mixing the enabled card modes. */
class ReviewViewModel(private val words: WordRepositoryImpl, private val settings: AppSettings) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUi())
    val state: StateFlow<ReviewUi> = _state
    private var modes: List<ReviewMode> = ReviewMode.entries

    init {
        load(practice = false)
    }

    fun load(practice: Boolean) = viewModelScope.launch(ScreenErrors) {
        _state.update { it.copy(loading = true) }
        modes = settings.reviewModes.get().mapNotNull { m -> ReviewMode.entries.firstOrNull { it.name == m } }.ifEmpty { listOf(ReviewMode.WORD_TO_TRANSLATION) }
        val goal = settings.dailyGoal.get()
        val today = words.reviewedToday(startOfToday())
        val now = System.currentTimeMillis()
        val list = if (practice) words.all().sortedBy { it.level }.take(20).shuffled() else words.dueWords(now, 100)
        _state.value = ReviewUi(loading = false, queue = list.map { ReviewCard(it, pickMode(it)) }, reviewedToday = today, goal = goal, practice = practice)
    }

    private fun pickMode(w: Word): ReviewMode {
        val usable = modes.filter { m ->
            when (m) {
                ReviewMode.CLOZE -> w.context?.contains(w.term, ignoreCase = true) == true
                ReviewMode.TRANSLATION_TO_WORD -> !w.translation.isNullOrBlank() || !w.definition.isNullOrBlank()
                ReviewMode.WORD_TO_TRANSLATION -> true
            }
        }.ifEmpty { listOf(ReviewMode.WORD_TO_TRANSLATION) }
        return usable.random()
    }

    fun flip() = _state.update { it.copy(flipped = !it.flipped) }

    fun answer(knew: Boolean) {
        val s = _state.value
        val card = s.current ?: return
        viewModelScope.launch(ScreenErrors) {
            val updated = words.review(card.word, knew, card.mode)
            _state.update { st ->
                val queue = if (!knew && !st.practice) st.queue + ReviewCard(updated, pickMode(updated)) else st.queue
                st.copy(
                    queue = queue,
                    index = st.index + 1,
                    flipped = false,
                    reviewedToday = st.reviewedToday + 1,
                    known = st.known + if (knew) 1 else 0,
                    unknown = st.unknown + if (knew) 0 else 1,
                )
            }
        }
    }
}

/** Installed offline dictionaries: import, enable, order, delete. */
class DictionariesViewModel(private val dict: DictionaryRepository, private val settings: AppSettings) : ViewModel() {
    val dictionaries: StateFlow<List<DictionaryInfo>?> = dict.observeDictionaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val progress = dict.importProgress
    val online = settings.onlineDictionary.stateIn(viewModelScope)
    private val events = Channel<Result<Int>>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(ScreenErrors) {
            _importing.value = true
            events.send(runCatching { dict.import(uris) })
            delay(200)
            _importing.value = false
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch(ScreenErrors) { dict.setEnabled(id, enabled) }
    fun delete(id: Long) = viewModelScope.launch(ScreenErrors) { dict.delete(id) }
    fun move(id: Long, delta: Int) = viewModelScope.launch(ScreenErrors) {
        val list = dictionaries.value?.toMutableList() ?: return@launch
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@launch
        java.util.Collections.swap(list, i, j)
        dict.reorder(list.map { it.id })
    }
    fun setOnline(v: Boolean) = viewModelScope.launch(ScreenErrors) { settings.onlineDictionary.set(v) }
}
