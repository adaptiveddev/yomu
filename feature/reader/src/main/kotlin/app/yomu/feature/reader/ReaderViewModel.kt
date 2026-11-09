package app.yomu.feature.reader

import android.content.Context
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.AppError
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.UiState
import app.yomu.core.datastore.AppSettings
import app.yomu.core.datastore.SettingsJson
import app.yomu.core.datastore.stateIn
import app.yomu.core.network.DataSaver
import app.yomu.data.book.Containers
import app.yomu.data.book.PageSource
import app.yomu.data.book.PageSources
import app.yomu.data.book.PdfPasswordRequired
import app.yomu.data.book.PdfSource
import app.yomu.data.book.pdf.PdfAnnotationStore
import app.yomu.data.book.pdf.PdfAnnotationWorker
import app.yomu.data.catalog.CatalogRepository
import app.yomu.data.download.DownloadRepository
import app.yomu.data.library.BookFiles
import app.yomu.data.ocr.OcrService
import app.yomu.data.repository.AnnotationRepositoryImpl
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.data.repository.StatsRepositoryImpl
import app.yomu.data.repository.WordRepositoryImpl
import app.yomu.data.translation.TranslationService
import app.yomu.domain.catalog.ChapterOrder
import app.yomu.domain.model.Book
import app.yomu.domain.model.BookFormat
import app.yomu.domain.model.BookReaderSettings
import app.yomu.domain.model.Bookmark
import app.yomu.domain.model.ChapterInfo
import app.yomu.domain.model.CommonReaderSettings
import app.yomu.domain.model.ContentType
import app.yomu.domain.model.EngineType
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.HighlightColor
import app.yomu.domain.model.HighlightStyle
import app.yomu.domain.model.Locator
import app.yomu.domain.model.MangaDirection
import app.yomu.domain.model.MangaSettings
import app.yomu.domain.model.Note
import app.yomu.domain.model.PageAction
import app.yomu.domain.model.PdfSettings
import app.yomu.domain.model.QuickActions
import app.yomu.domain.model.QuickMenuStyle
import app.yomu.domain.model.ReadingStatus
import app.yomu.domain.model.SearchHit
import app.yomu.domain.model.TapZoneLayout
import app.yomu.domain.model.TextPreset
import app.yomu.domain.model.TextReaderSettings
import app.yomu.feature.reader.manga.TapAction
import app.yomu.feature.reader.manga.TapZones
import app.yomu.domain.model.TocItem
import app.yomu.domain.text.TextTools
import app.yomu.feature.reader.epub.ChapterText
import app.yomu.feature.reader.epub.EpubController
import app.yomu.feature.reader.epub.EpubListener
import app.yomu.feature.reader.epub.EpubProgress
import app.yomu.feature.reader.epub.EpubSelection
import app.yomu.feature.reader.epub.EpubTarget
import app.yomu.feature.reader.epub.EpubTextDocument
import app.yomu.feature.reader.epub.EpubWord
import app.yomu.feature.reader.manga.MangaController
import app.yomu.feature.reader.manga.MangaListener
import app.yomu.feature.reader.manga.OverlayBlock
import app.yomu.feature.reader.pdf.PdfController
import app.yomu.feature.reader.pdf.PdfListener
import app.yomu.feature.reader.pdf.PdfSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import timber.log.Timber
import java.io.File

/** What the reader opens: a library book, or an online chapter of a catalog entry. */
data class ReaderArgs(val bookId: Long = -1, val locatorJson: String? = null, val chapterId: Long = -1, val positionReference: String? = null)

sealed interface ReaderContent {
    data class Text(val controller: EpubController) : ReaderContent
    data class Pdf(val controller: PdfController) : ReaderContent
    data class Manga(val controller: MangaController) : ReaderContent
}

/** Current text selection in any engine, in window pixels. */
data class ReaderSelection(
    val text: String,
    val anchor: Rect,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val sentence: String = "",
    val highlightId: Long? = null,
)

data class WordTap(val word: String, val sentence: String, val anchor: Rect?, val locator: Locator)

data class ReaderPosition(
    val chapter: Int = 0,
    val chapterCount: Int = 1,
    val page: Int = 0,
    val pages: Int = 1,
    val progression: Float = 0f,
    val total: Float = 0f,
    val chapterTitle: String? = null,
    val charsLeftInChapter: Int = 0,
    val charsLeftInBook: Long = 0,
    /** 1-based page in the whole book: exact for PDF and comics, estimated from the chapter sizes for text books. */
    val bookPage: Int = 0,
    val bookPages: Int = 0,
    val approximate: Boolean = false,
)

/** Where a slider position would lead: shown in the bubble while dragging. */
data class SeekPreview(val page: Int, val pages: Int, val approximate: Boolean, val chapterTitle: String?)

/** The place before a jump (slider, contents, search, "go to page"), to return with one tap. */
data class JumpBack(val locator: Locator, val page: Int)

data class SearchState(val query: String = "", val loading: Boolean = false, val hits: List<SearchHit> = emptyList(), val done: Boolean = false)

sealed interface ReaderEvent {
    data class BookmarkAdded(val bookmark: Bookmark) : ReaderEvent
    data class BookmarkRemoved(val bookmark: Bookmark) : ReaderEvent
    data class HighlightDeleted(val highlight: Highlight) : ReaderEvent
    data class WordSaved(val term: String) : ReaderEvent
    data class Message(val res: Int) : ReaderEvent
    data class Error(val error: Throwable) : ReaderEvent
    data class OpenUrl(val url: String) : ReaderEvent
    data object Finished : ReaderEvent
    /** Open the note dialog for the current page (long-press menu). */
    data object PageNote : ReaderEvent
    /** Saving to the gallery is not possible here (Android 9 and older): ask where to save the page. */
    data class SavePageAs(val file: java.io.File, val name: String, val mime: String) : ReaderEvent
}

/** The long-press menu of a manga or comic page, opened at [anchor] (window coordinates). */
data class PageMenuRequest(val page: Int, val anchor: Offset)

enum class SleepTimer(val minutes: Int) { OFF(0), MIN_5(5), MIN_15(15), MIN_30(30), MIN_60(60), END_OF_CHAPTER(-1) }

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    val args: ReaderArgs,
    private val context: Context,
    private val books: BookRepositoryImpl,
    private val annotations: AnnotationRepositoryImpl,
    private val words: WordRepositoryImpl,
    private val stats: StatsRepositoryImpl,
    val settings: AppSettings,
    private val catalog: CatalogRepository,
    private val translation: TranslationService,
    private val ocr: OcrService,
    private val downloads: DownloadRepository,
    private val pdfAnnotations: PdfAnnotationStore,
) : ViewModel(), EpubListener, PdfListener, MangaListener {

    val bookId: Long get() = args.bookId
    var book: Book? = null
        private set
    val isOnline: Boolean get() = args.chapterId > 0

    private val _content = MutableStateFlow<UiState<ReaderContent>>(UiState.Loading)
    val content: StateFlow<UiState<ReaderContent>> = _content
    private val _password = MutableStateFlow(false)
    val passwordNeeded: StateFlow<Boolean> = _password
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    /** "Chapter 12 of 368" of the online chapter being read, shown under the title; null for books. */
    private var chapterOf: String? = null

    /** Pages are images (PDF, comics, online manga): while loading, show the black page canvas instead of the text theme. */
    private val _imageCanvas = MutableStateFlow(args.chapterId > 0)
    val imageCanvas: StateFlow<Boolean> = _imageCanvas
    private val _toc = MutableStateFlow<List<TocItem>>(emptyList())
    val toc: StateFlow<List<TocItem>> = _toc
    private val _position = MutableStateFlow(ReaderPosition())
    val position: StateFlow<ReaderPosition> = _position

    val chromeVisible = MutableStateFlow(false)
    val selection = MutableStateFlow<ReaderSelection?>(null)
    val wordTap = MutableStateFlow<WordTap?>(null)
    val footnote = MutableStateFlow<String?>(null)
    val image = MutableStateFlow<Any?>(null)
    val translateText = MutableStateFlow<String?>(null)
    val searchState = MutableStateFlow(SearchState())

    private val events = Channel<ReaderEvent>(Channel.BUFFERED)
    val eventFlow: Flow<ReaderEvent> = events.receiveAsFlow()

    // ------------------------------------------------------------------ settings
    private val bookOverride = MutableStateFlow(BookReaderSettings())
    val textSettings: StateFlow<TextReaderSettings> = combine(settings.textSettings.flow, bookOverride) { g, o -> o.text ?: g }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TextReaderSettings())
    private val appDark = MutableStateFlow(false)
    fun setAppDark(dark: Boolean) { appDark.value = dark }

    /** [textSettings] with the auto night theme applied; what the page, the PDF and the reader colours use. */
    val effectiveText: StateFlow<TextReaderSettings> = combine(textSettings, appDark) { s, dark ->
        if (s.autoNight && dark) s.copy(theme = s.nightTheme) else s
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TextReaderSettings())
    val mangaSettings: StateFlow<MangaSettings> = combine(settings.mangaSettings.flow, bookOverride) { g, o -> o.manga ?: g }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MangaSettings())
    val pdfSettings: StateFlow<PdfSettings> = combine(settings.pdfSettings.flow, bookOverride) { g, o -> o.pdf ?: g }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PdfSettings())
    val common: StateFlow<CommonReaderSettings> = settings.commonReader.stateIn(viewModelScope)
    val selectionActions = settings.selectionActions.stateIn(viewModelScope)
    val selectionMenuStyle = settings.selectionMenuStyle.stateIn(viewModelScope)
    val pageMenuStyle = settings.pageMenuStyle.stateIn(viewModelScope)
    val pageActions = settings.pageActions.stateIn(viewModelScope)
    val pageMenu = MutableStateFlow<PageMenuRequest?>(null)
    val customActions = settings.customActions.stateIn(viewModelScope)
    val perBook: StateFlow<Boolean> = combine(bookOverride, _content) { o, c ->
        when ((c as? UiState.Data)?.value) {
            is ReaderContent.Text -> o.text != null
            is ReaderContent.Pdf -> o.pdf != null
            is ReaderContent.Manga -> o.manga != null
            null -> false
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val highlights: StateFlow<List<Highlight>> = (if (args.bookId > 0) annotations.observeHighlights(args.bookId) else flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = (if (args.bookId > 0) annotations.observeBookmarks(args.bookId) else flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val savedWords: StateFlow<Set<String>> = words.observeSavedNormalized().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // ------------------------------------------------------------------ online chapters
    private var chapters: List<ChapterInfo> = emptyList()
    private val _chapterId = MutableStateFlow(args.chapterId)
    val chapterId: StateFlow<Long> = _chapterId
    val hasPrevChapter = MutableStateFlow(false)
    val hasNextChapter = MutableStateFlow(false)
    private var onlineType: ContentType = ContentType.MANGA

    // ------------------------------------------------------------------ text engine state
    private var initialTarget: Pair<Int, EpubTarget?>? = null
    private var attachedOnce = false
    private val chapterLengths = HashMap<Int, Int>()

    // ------------------------------------------------------------------ tts / autoscroll / translation
    val tts = ReaderTts(context)
    private val _ttsActive = MutableStateFlow(false)
    val ttsActive: StateFlow<Boolean> = _ttsActive
    val sleepTimer = MutableStateFlow(SleepTimer.OFF)
    val sleepRemainingMs = MutableStateFlow<Long?>(null)
    private var sleepJob: Job? = null
    private var ttsSentences: List<Triple<Int, Int, Int>> = emptyList()
    private var ttsChapter = 0

    val autoScroll = MutableStateFlow(false)
    private var autoScrollJob: Job? = null

    val bilingualProgress = MutableStateFlow<Float?>(null)
    private var bilingualJob: Job? = null
    val ocrRunning = MutableStateFlow(false)

    // ------------------------------------------------------------------ stats
    private var sessionStart = 0L
    private var sessionPages = 0
    private var sessionChars = 0L
    /** Last page turn / scroll / tap: time after it without any activity is not counted as reading. */
    private var lastActivity = 0L
    private var onlineEntryId: Long? = null
    private var lastOffset: Int? = null
    private var lastPage = -1
    private var tapZonesShown = false
    private var saveJob: Job? = null
    /** The position waiting for [scheduleSave]'s debounce; written right away when the reader is left. */
    @Volatile private var pendingLocator: Locator? = null
    private val openBookMutex = Mutex()
    private var folderReadSession: BookRepositoryImpl.FolderReadSession? = null

    init {
        tts.onSentence = { i -> onTtsSentence(i) }
        tts.onQueueFinished = { onTtsQueueFinished() }
        tts.onError = { events.trySend(ReaderEvent.Message(R.string.reader_tts_error)) }
        viewModelScope.launch(ScreenErrors) {
            runCatching { if (isOnline) openOnline(args.chapterId) else openBook(args.bookId, null) }
                .onFailure { e ->
                    if (e is PdfPasswordRequired) _password.value = true
                    else _content.value = UiState.Error(AppError.from(e))
                }
        }
        viewModelScope.launch(ScreenErrors) { effectiveText.collect { s -> (current() as? ReaderContent.Text)?.controller?.updateSettings(s) } }
        viewModelScope.launch(ScreenErrors) { mangaSettings.collect { s -> (current() as? ReaderContent.Manga)?.controller?.settings = s } }
        viewModelScope.launch(ScreenErrors) { pdfSettings.collect { s -> (current() as? ReaderContent.Pdf)?.controller?.settings = s } }
        viewModelScope.launch(ScreenErrors) { highlights.collect { pushHighlights() } }
        viewModelScope.launch(ScreenErrors) { savedWords.collect { w -> (current() as? ReaderContent.Text)?.controller?.setSavedWords(w.toList()) } }
        viewModelScope.launch(ScreenErrors) { settings.wordTap.flow.collect { g -> (current() as? ReaderContent.Text)?.controller?.let { it.wordTap = g; it.refreshConfig() } } }
        viewModelScope.launch(ScreenErrors) {
            common.collect { c ->
                tts.speed = c.ttsSpeed
                tts.pitch = c.ttsPitch
                tts.voiceName = c.ttsVoice
                (current() as? ReaderContent.Text)?.controller?.let { t ->
                    if (t.footerSpace != c.footer) { t.footerSpace = c.footer; t.refreshConfig() }
                }
            }
        }
    }

    fun current(): ReaderContent? = (_content.value as? UiState.Data)?.value

    // ================================================================== opening
    fun submitPassword(password: String) {
        _password.value = false
        _content.value = UiState.Loading
        viewModelScope.launch(ScreenErrors) {
            runCatching { openBook(args.bookId, password) }.onFailure { e ->
                if (e is PdfPasswordRequired) {
                    _password.value = true
                    events.send(ReaderEvent.Message(R.string.reader_wrong_password))
                } else {
                    _content.value = UiState.Error(AppError.from(e))
                }
            }
        }
    }

    /** True the first time a Word document is opened: the reader then offers the print layout. */
    fun takePageLayoutHint(id: Long): Boolean {
        val prefs = context.getSharedPreferences("docx_layout", Context.MODE_PRIVATE)
        val key = "hinted_$id"
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }

    /** Whether the error can be fixed by picking the book file again (moved, deleted or permission lost). */
    fun canRelink(error: Throwable): Boolean =
        !isOnline && args.bookId > 0 && (error is AppError.FileMissing || error is AppError.NoPermission)

    fun relink(uri: android.net.Uri) {
        _content.value = UiState.Loading
        viewModelScope.launch(ScreenErrors) {
            runCatching { books.relink(args.bookId, uri) }
            retry()
        }
    }

    fun retry() {
        _content.value = UiState.Loading
        viewModelScope.launch(ScreenErrors) {
            runCatching { if (isOnline) openOnline(_chapterId.value) else openBook(args.bookId, null) }
                .onFailure { e -> if (e is PdfPasswordRequired) _password.value = true else _content.value = UiState.Error(AppError.from(e)) }
        }
    }

    private suspend fun openBook(id: Long, password: String?) = openBookMutex.withLock {
        folderReadSession?.close()
        folderReadSession = null
        var lease = books.beginFolderReading(id)
        try {
        val b = books.getBook(id) ?: throw AppError.CorruptedFile("Book $id not found")
        book = b
        _title.value = b.title
        _imageCanvas.value = !b.format.isText
        bookOverride.value = b.readerSettingsJson?.let { runCatching { SettingsJson.decodeFromString(BookReaderSettings.serializer(), it) }.getOrNull() } ?: BookReaderSettings()
        if (lease != null && args.locatorJson != null && args.positionReference == null)
            throw java.io.IOException("This saved position may be outdated; reopen it from the current list")
        val saved = if (args.positionReference != null) {
            books.resolveSavedPosition(id, args.positionReference) ?: books.getProgress(id)
        } else {
            args.locatorJson?.let { runCatching { SettingsJson.decodeFromString(Locator.serializer(), it) }.getOrNull() } ?: books.getProgress(id)
        }
        if (b.status == ReadingStatus.NEW) books.setStatus(listOf(id), ReadingStatus.READING)
        when (b.engine) {
            // Navigation opens audiobooks in the player; the reader never gets one.
            EngineType.AUDIO -> throw AppError.UnsupportedFormat(b.format.name)
            EngineType.TEXT -> {
                val uri = BookFiles.readingUri(context, b)
                val doc = withContext(Dispatchers.IO) { EpubTextDocument.open(Containers.open(context, uri, BookFormat.EPUB)) }
                val c = newTextController(doc)
                _toc.value = doc.toc
                initialTarget = if (saved != null) {
                    if (saved.offset == null && saved.progression == 0f && saved.totalProgression > 0f) {
                        val (ch, p) = doc.locate(saved.totalProgression)
                        ch to EpubTarget.Progression(p)
                    } else {
                        saved.chapter to (saved.offset?.let { EpubTarget.Offset(it) } ?: EpubTarget.Progression(saved.progression))
                    }
                } else {
                    0 to null
                }
                _position.value = ReaderPosition(chapterCount = doc.chapterCount)
                _content.value = UiState.Data(ReaderContent.Text(c))
            }
            EngineType.PDF -> {
                val uri = Uri.parse(b.uri)
                val src = withContext(Dispatchers.IO) {
                    // Highlights another device wrote into the file come in first; the reader draws them itself, so it
                    // opens the file without Yomu's own annotations.
                    runCatching { pdfAnnotations.importFromFile(b.id) }.onFailure { Timber.w(it, "PDF annotations") }
                    PdfSource.open(context, uri, password, limit = pdfAnnotations.renderLimit(b.id, uri))
                }
                val c = PdfController(src)
                c.settings = pdfSettings.value
                c.page = (saved?.chapter ?: 0).coerceIn(0, (src.pageCount - 1).coerceAtLeast(0))
                c.highlights = highlights.value
                _toc.value = runCatching { src.toc() }.getOrDefault(emptyList())
                _position.value = ReaderPosition(chapter = c.page, chapterCount = src.pageCount, page = c.page, pages = src.pageCount)
                _content.value = UiState.Data(ReaderContent.Pdf(c))
                onPageChanged(c.page)
            }
            EngineType.MANGA -> {
                val uri = BookFiles.readingUri(context, b)
                val pages = withContext(Dispatchers.IO) { PageSources.open(context, b, uri, folderProofs = lease?.pages) }
                if (pages.count == 0) throw AppError.CorruptedFile("No pages")
                openManga(pages, (saved?.chapter ?: 0))
            }
        }
        sessionStart = System.currentTimeMillis()
        lastActivity = sessionStart
        folderReadSession = lease
        lease = null
        } finally { lease?.close() }
    }

    private fun newTextController(doc: app.yomu.feature.reader.epub.TextDocument): EpubController {
        val c = EpubController(context, doc, File(context.filesDir, "fonts").apply { mkdirs() })
        c.listener = this
        c.updateSettings(effectiveText.value, relayout = false)
        c.footerSpace = common.value.footer
        c.prevLabel = context.getString(R.string.reader_prev_chapter)
        c.nextLabel = context.getString(R.string.reader_next_chapter)
        viewModelScope.launch(ScreenErrors) {
            c.wordTap = settings.wordTap.get()
            c.reduceMotion = settings.reduceMotion.get()
        }
        attachedOnce = false
        return c
    }

    private fun openManga(pages: PageSource, page: Int, online: Boolean = false) {
        val c = MangaController(pages)
        c.settings = mangaSettings.value
        c.page = page.coerceIn(0, (pages.count - 1).coerceAtLeast(0))
        // "Save mobile data": on mobile data only the next page of a chapter read from the network is preloaded.
        if (online && DataSaver.active) c.preloadLimit = DataSaver.PRELOAD_PAGES
        _toc.value = emptyList()
        _position.value = ReaderPosition(chapter = c.page, chapterCount = pages.count, page = c.page, pages = pages.count, chapterTitle = chapterOf)
        _content.value = UiState.Data(ReaderContent.Manga(c))
        lastPage = c.page
        // Where to tap: shown once per opened title (not again for every chapter).
        val m = mangaSettings.value
        if (!tapZonesShown && m.showTapZonesOnOpen && m.tapZones != TapZoneLayout.DISABLED && m.direction != MangaDirection.WEBTOON) {
            tapZonesShown = true
            c.showTapZones()
        }
    }

    /** Shows the tap zone overlay of the open manga (settings preview). */
    fun previewTapZones() {
        (current() as? ReaderContent.Manga)?.controller?.showTapZones()
    }

    private suspend fun openOnline(chapterId: Long) {
        val ch = catalog.chapter(chapterId) ?: throw AppError.CorruptedFile("Chapter not found")
        val entry = catalog.entry(ch.entryId) ?: throw AppError.CorruptedFile("Entry not found")
        _chapterId.value = chapterId
        onlineEntryId = entry.id
        onlineType = entry.type
        // Reading order shared with the title page; groups hidden there are skipped (except the chapter being opened).
        val excluded = catalog.chapterDisplay(entry.id).excludedScanlators
        chapters = ChapterOrder.reading(catalog.chapters(entry.id).filter { it.id == chapterId || it.scanlator.isNullOrBlank() || it.scanlator !in excluded })
        hasPrevChapter.value = ChapterOrder.adjacent(chapters, chapterId, forward = false) != null
        hasNextChapter.value = ChapterOrder.adjacent(chapters, chapterId, forward = true) != null
        // "Download ahead": keep the next chapters of library titles downloaded while reading.
        viewModelScope.launch(ScreenErrors) { runCatching { downloads.ensureAhead(entry.id, chapterId) }.onFailure { Timber.w(it, "Download ahead failed") } }
        _title.value = entry.title + " · " + ch.name
        // "Chapter 12 of 368" under the title: the number in reading order, as on the title page.
        val slots = ChapterOrder.slots(chapters)
        // Typed explicitly: lint takes "unknown + 1" for a string and reports the %d of the format.
        val number: Int = slots.indexOfFirst { slot -> slot.any { it.id == chapterId } } + 1
        val total: Int = slots.size
        chapterOf = if (number > 0) context.getString(R.string.reader_chapter_of, number, total) else null
        _imageCanvas.value = entry.type != ContentType.NOVEL && entry.type != ContentType.BOOK
        bookOverride.value = catalog.readerSettings(entry.id)?.let { runCatching { SettingsJson.decodeFromString(BookReaderSettings.serializer(), it) }.getOrNull() } ?: BookReaderSettings()
        if (entry.type == ContentType.NOVEL || entry.type == ContentType.BOOK) {
            val html = catalog.novelContent(chapterId)
            val doc = SingleHtmlDocument(ch.name, html)
            val c = newTextController(doc)
            _toc.value = chapters.mapIndexed { i, it -> TocItem(it.name, i) }
            initialTarget = 0 to null
            _position.value = ReaderPosition(chapterCount = 1)
            _content.value = UiState.Data(ReaderContent.Text(c))
        } else {
            val local = ch.localPath?.let { File(it) }?.takeIf { it.exists() }
            val pages = if (local != null) {
                withContext(Dispatchers.IO) {
                    val fmt = if (local.isDirectory) BookFormat.FOLDER else BookFormat.CBZ
                    val fake = Book(id = -chapterId, title = ch.name, format = fmt, uri = Uri.fromFile(local).toString())
                    PageSources.open(context, fake, Uri.fromFile(local))
                }
            } else {
                val (source, list) = catalog.pages(chapterId)
                catalog.onlinePages(source, list, "ch_$chapterId")
            }
            openManga(pages, ch.lastPageRead.takeIf { !ch.read } ?: 0, online = local == null)
        }
        sessionStart = System.currentTimeMillis()
        lastActivity = sessionStart
    }

    /** Opens the previous/next chapter of an online entry. */
    fun openAdjacentChapter(next: Boolean) {
        // Other groups' releases of the current chapter are skipped; the current group is preferred.
        val target = ChapterOrder.adjacent(chapters, _chapterId.value, forward = next) ?: return
        viewModelScope.launch(ScreenErrors) {
            flushStats()
            if (next) catalog.saveProgress(_chapterId.value, 0, finished = true)
            _content.value = UiState.Loading
            runCatching { openOnline(target.id) }.onFailure { _content.value = UiState.Error(AppError.from(it)) }
        }
    }

    fun openChapterAt(index: Int) {
        val target = chapters.getOrNull(index) ?: return
        viewModelScope.launch(ScreenErrors) {
            flushStats()
            _content.value = UiState.Loading
            runCatching { openOnline(target.id) }.onFailure { _content.value = UiState.Error(AppError.from(it)) }
        }
    }

    /** Called by the screen every time a WebView is attached (first open and after configuration changes). */
    fun onWebViewAttached(c: EpubController) {
        if (!attachedOnce) {
            attachedOnce = true
            val (ch, target) = initialTarget ?: (0 to null)
            c.open(ch, target)
        } else {
            c.open(c.chapter, EpubTarget.Offset(c.progress.offset))
        }
    }

    // ================================================================== text listener
    override fun onReady(chapter: Int) {
        pushHighlights()
        val c = (current() as? ReaderContent.Text)?.controller ?: return
        c.setSavedWords(savedWords.value.toList())
        if (textSettings.value.bilingual) startBilingual()
        if (_ttsActive.value && ttsChapter != chapter) viewModelScope.launch(ScreenErrors) { delay(300); speakTextFrom(c, 0) }
    }

    override fun onProgress(chapter: Int, progress: EpubProgress) {
        val c = (current() as? ReaderContent.Text)?.controller ?: return
        val doc = c.document
        chapterLengths[chapter] = progress.total
        markActivity()
        if (lastPage != progress.page || lastOffset == null) sessionPages++
        lastOffset?.let { if (progress.offset > it) sessionChars += (progress.offset - it).coerceAtMost(5000) }
        lastOffset = progress.offset
        lastPage = progress.page
        val total = doc.totalProgression(chapter, progress.progression)
        val charsLeft = (progress.total - progress.offset).coerceAtLeast(0)
        val totalWeight = (0 until doc.chapterCount).sumOf { doc.weight(it).coerceAtLeast(1) }
        val (bookPage, bookPages) = estimatePages(doc, chapter, progress)
        hereLocator = Locator(chapter = chapter, progression = progress.progression, totalProgression = total, offset = progress.offset)
        _position.value = ReaderPosition(
            bookPage = bookPage,
            bookPages = bookPages,
            approximate = doc.chapterCount > 1,
            chapter = chapter,
            chapterCount = doc.chapterCount,
            page = progress.page,
            pages = progress.pages,
            progression = progress.progression,
            total = total,
            chapterTitle = doc.chapterTitle(chapter),
            charsLeftInChapter = charsLeft,
            charsLeftInBook = ((1f - total) * totalWeight).toLong().coerceAtLeast(charsLeft.toLong()),
        )
        scheduleSave(Locator(chapter = chapter, progression = progress.progression, totalProgression = total, offset = progress.offset, chapterTitle = doc.chapterTitle(chapter)))
    }

    override fun onTap(x: Float, y: Float) {
        markActivity()
        if (selection.value != null) return
        when (current()) {
            is ReaderContent.Text -> {
                val c = (current() as ReaderContent.Text).controller
                val turn = common.value.tapToTurn
                val rtl = textSettings.value.rtl || c.document.rtl
                val action = if (turn) TapZones.action(common.value.textTapZones, false, rtl, x, y, sideFraction = 0.25f) else TapAction.MENU
                when (action) {
                    TapAction.PREV -> c.prev()
                    TapAction.NEXT -> c.next()
                    TapAction.MENU -> toggleChrome()
                }
            }
            is ReaderContent.Pdf -> toggleChrome()
            else -> toggleChrome()
        }
    }

    private fun toggleChrome() {
        chromeVisible.value = !chromeVisible.value
    }

    var density = 1f

    private fun cssRect(l: Float, t: Float, r: Float, b: Float) = Rect(l * density, t * density, r * density, b * density)

    override fun onSelection(selection: EpubSelection?) {
        val c = (current() as? ReaderContent.Text)?.controller ?: return
        this.selection.value = selection?.takeIf { it.text.isNotBlank() }?.let {
            ReaderSelection(it.text, cssRect(it.left, it.top, it.right, it.bottom), c.chapter, it.start, it.end, it.sentence)
        }
    }

    override fun onWord(word: EpubWord) {
        val c = (current() as? ReaderContent.Text)?.controller ?: return
        wordTap.value = WordTap(
            word = word.word,
            sentence = word.sentence,
            anchor = cssRect(word.left, word.top, word.right, word.bottom),
            locator = Locator(chapter = c.chapter, offset = word.start, endOffset = word.end, text = word.word, chapterTitle = c.document.chapterTitle(c.chapter)),
        )
    }

    override fun onExternalLink(url: String) {
        events.trySend(ReaderEvent.OpenUrl(url))
    }

    override fun onImage(model: Any) {
        image.value = model
    }

    override fun onFootnote(html: String) {
        footnote.value = html
    }

    override fun onHighlightTap(id: Long, x: Float, y: Float) {
        val h = highlights.value.firstOrNull { it.id == id } ?: return
        selection.value = ReaderSelection(h.text, cssRect(x, y, x, y), h.locator.chapter, h.locator.offset ?: 0, h.locator.endOffset ?: 0, highlightId = id)
    }

    override fun onEdgeReached(forward: Boolean) {
        val c = (current() as? ReaderContent.Text)?.controller ?: return
        if (forward) {
            if (c.chapter < c.document.chapterCount - 1) c.nextChapter()
            else if (isOnline && hasNextChapter.value) openAdjacentChapter(true)
            else finishBook()
        } else {
            if (c.chapter > 0) c.prevChapter(toEnd = true)
            else if (isOnline && hasPrevChapter.value) openAdjacentChapter(false)
        }
    }

    // ================================================================== pdf listener
    override fun onSelection(selection: PdfSelection?, bounds: Rect?) {
        this.selection.value = if (selection == null || bounds == null || selection.text.isBlank()) null
        else ReaderSelection(selection.text, bounds, selection.page, selection.start, selection.end)
    }

    override fun onHighlightTap(highlight: Highlight, bounds: Rect) {
        selection.value = ReaderSelection(highlight.text, bounds, highlight.locator.chapter, highlight.locator.offset ?: 0, highlight.locator.endOffset ?: 0, highlightId = highlight.id)
    }

    override fun onLink(uri: String) {
        events.trySend(ReaderEvent.OpenUrl(uri))
    }

    override fun onPageChanged(page: Int) {
        val content = current()
        val count = when (content) {
            is ReaderContent.Pdf -> content.controller.pageCount
            is ReaderContent.Manga -> content.controller.count
            else -> return
        }
        markActivity()
        if (page != lastPage) sessionPages++
        lastPage = page
        val total = if (count <= 1) 1f else page / (count - 1).toFloat()
        hereLocator = Locator(chapter = page, progression = 0f, totalProgression = total)
        _position.value = ReaderPosition(
            chapter = page, chapterCount = count, page = page, pages = count, progression = total, total = total,
            bookPage = page + 1, bookPages = count, chapterTitle = chapterOf.takeIf { content is ReaderContent.Manga },
        )
        if (isOnline) {
            val id = _chapterId.value
            viewModelScope.launch(ScreenErrors) { catalog.saveProgress(id, page, finished = page >= count - 1) }
        } else {
            scheduleSave(Locator(chapter = page, progression = 0f, totalProgression = total))
        }
        if (page >= count - 1 && !isOnline) {
            viewModelScope.launch(ScreenErrors) { delay(400); if (lastPage >= count - 1) markFinishedQuietly() }
        }
        if (_ttsActive.value && current() is ReaderContent.Pdf && ttsChapter != page) speakPdfPage(page)
    }

    // ================================================================== manga listener
    override fun onMenu() = toggleChrome()

    override fun onLongPress(page: Int) = onLongPressAt(page, Offset.Unspecified)

    /** A long press on a manga page opens the long-press menu; with the menu off, its first action runs at once. */
    override fun onLongPressAt(page: Int, windowPosition: Offset) {
        if (pageMenuStyle.value == QuickMenuStyle.OFF || !windowPosition.isSpecified) {
            runPageAction(QuickActions.normalize(pageActions.value).firstOrNull { it.enabled }?.action ?: PageAction.TRANSLATE, page)
        } else {
            pageMenu.value = PageMenuRequest(page, windowPosition)
        }
    }

    fun runPageAction(action: PageAction, page: Int) {
        pageMenu.value = null
        when (action) {
            PageAction.TRANSLATE -> translateMangaPage(page)
            PageAction.SAVE -> savePage(page)
            PageAction.SHARE -> sharePage(page)
            PageAction.COPY_TEXT -> copyPageText(page)
            PageAction.BOOKMARK -> bookmarkPage()
            PageAction.SET_COVER -> setPageAsCover(page)
            PageAction.NOTE -> if (args.bookId > 0) events.trySend(ReaderEvent.PageNote) else events.trySend(ReaderEvent.Message(R.string.reader_online_no_annotations))
        }
    }

    private suspend fun pageFile(page: Int): java.io.File? = (current() as? ReaderContent.Manga)?.controller?.let { c -> withContext(Dispatchers.IO) { c.file(page) } }

    /** Image type of a page file (pages are cached without a file extension). */
    private fun imageType(file: java.io.File): Pair<String, String> {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
        return when (o.outMimeType) {
            "image/png" -> "image/png" to "png"
            "image/webp" -> "image/webp" to "webp"
            "image/gif" -> "image/gif" to "gif"
            "image/avif", "image/heif", "image/heic" -> (o.outMimeType ?: "image/heif") to "heif"
            else -> "image/jpeg" to "jpg"
        }
    }

    private fun pageName(page: Int, ext: String): String {
        val base = listOf(_title.value, chapterOf).filter { !it.isNullOrBlank() }.joinToString(" - ").ifBlank { "Yomu" }
        return app.yomu.core.common.Files.sanitize("$base - ${page + 1}.$ext")
    }

    /** Saves the page to the gallery (Pictures/Yomu). */
    private fun savePage(page: Int) = viewModelScope.launch(ScreenErrors) {
        runCatching {
            val file = pageFile(page) ?: return@launch
            val (mime, ext) = withContext(Dispatchers.IO) { imageType(file) }
            val name = pageName(page, ext)
            if (android.os.Build.VERSION.SDK_INT < 29) {
                events.send(ReaderEvent.SavePageAs(file, name, mime))
                return@launch
            }
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Yomu")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Cannot save the image")
                try {
                    (resolver.openOutputStream(uri) ?: error("Cannot save the image")).use { out -> file.inputStream().use { it.copyTo(out) } }
                    resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
            events.send(ReaderEvent.Message(R.string.reader_page_saved))
        }.onFailure { events.send(ReaderEvent.Error(it)) }
    }

    /** Writes a page picked with "Save as" (Android 9 and older). */
    fun savePageTo(file: java.io.File, target: android.net.Uri) = viewModelScope.launch(ScreenErrors) {
        runCatching {
            withContext(Dispatchers.IO) { (context.contentResolver.openOutputStream(target) ?: error("Cannot save the image")).use { out -> file.inputStream().use { it.copyTo(out) } } }
            events.send(ReaderEvent.Message(R.string.reader_page_saved))
        }.onFailure { events.send(ReaderEvent.Error(it)) }
    }

    private fun sharePage(page: Int) = viewModelScope.launch(ScreenErrors) {
        runCatching {
            val file = pageFile(page) ?: return@launch
            val shared = withContext(Dispatchers.IO) {
                val (mime, ext) = imageType(file)
                val out = java.io.File(app.yomu.core.common.Share.shareDir(context), pageName(page, ext))
                file.copyTo(out, overwrite = true)
                out to mime
            }
            app.yomu.core.common.Share.shareFile(context, shared.first, shared.second)
        }.onFailure { events.send(ReaderEvent.Error(it)) }
    }

    /** Recognises the text of the page and puts it on the clipboard. */
    private fun copyPageText(page: Int) = viewModelScope.launch(ScreenErrors) {
        ocrRunning.value = true
        runCatching {
            val file = pageFile(page) ?: return@runCatching
            val bmp = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file.absolutePath) } ?: error("Cannot decode page")
            val blocks = ocr.recognize(bmp, settings.ocrLanguage.get(), cacheKey = "${args.bookId}_${_chapterId.value}_$page")
            bmp.recycle()
            val text = blocks.joinToString("\n\n") { it.text.trim() }.trim()
            if (text.isEmpty()) {
                events.send(ReaderEvent.Message(R.string.reader_ocr_nothing))
            } else {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("yomu", text))
                events.send(ReaderEvent.Message(R.string.reader_page_text_copied))
            }
        }.onFailure { events.send(ReaderEvent.Error(it)) }
        ocrRunning.value = false
    }

    /** A bookmark at the page (books) or on the chapter (online titles). */
    private fun bookmarkPage() {
        if (args.bookId > 0) {
            toggleBookmark()
            return
        }
        viewModelScope.launch(ScreenErrors) {
            val id = _chapterId.value
            val ch = catalog.chapter(id) ?: return@launch
            catalog.setBookmarked(listOf(id), !ch.bookmarked)
            events.send(ReaderEvent.Message(if (ch.bookmarked) R.string.reader_chapter_unbookmarked else R.string.reader_chapter_bookmarked))
        }
    }

    private fun setPageAsCover(page: Int) {
        if (args.bookId <= 0) {
            events.trySend(ReaderEvent.Message(R.string.reader_cover_books_only))
            return
        }
        viewModelScope.launch(ScreenErrors) {
            runCatching {
                val file = pageFile(page) ?: return@launch
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                books.setCover(args.bookId, bytes) ?: error("Cannot save the cover")
                events.send(ReaderEvent.Message(R.string.reader_cover_set))
            }.onFailure { events.send(ReaderEvent.Error(it)) }
        }
    }

    override fun onChapterEdge(next: Boolean) {
        if (isOnline) openAdjacentChapter(next) else if (next) finishBook()
    }

    override fun onFinished() {
        if (isOnline && hasNextChapter.value) return
        finishBook()
    }

    override fun onBlockTap(page: Int, block: OverlayBlock) {
        translateText.value = block.original
    }

    // ================================================================== progress & stats
    private fun scheduleSave(locator: Locator) {
        if (args.bookId <= 0) return
        pendingLocator = locator
        saveJob?.cancel()
        saveJob = viewModelScope.launch(ScreenErrors) {
            delay(800)
            flushProgress()
        }
    }

    /** Writes the debounced position now; it must not be lost when the app goes to the background or is closed. */
    private suspend fun flushProgress() {
        val locator = pendingLocator ?: return
        pendingLocator = null
        // Incognito: the position is kept only while the reader is open.
        if (settings.incognito.get()) return
        withContext(NonCancellable) { books.saveProgress(args.bookId, locator) }
    }

    private fun markFinishedQuietly() {
        if (args.bookId <= 0) return
        // App-lifetime: finishing the last page and backing out at once must still mark the book.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            runCatching { books.setStatus(listOf(args.bookId), ReadingStatus.FINISHED) }
        }
    }

    private fun finishBook() {
        markFinishedQuietly()
        events.trySend(ReaderEvent.Finished)
    }

    fun onPause() {
        saveJob?.cancel()
        // Home or the recents screen may be followed by the process being killed: save before that can happen.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { runCatching { flushProgress() } }
        viewModelScope.launch(ScreenErrors) { flushStats() }
    }

    fun onResume() {
        sessionStart = System.currentTimeMillis()
        lastActivity = sessionStart
        sessionPages = 0
        sessionChars = 0
    }

    /** Called on every sign of reading; a long pause closes the session at the last activity and starts a new one. */
    private fun markActivity() {
        val now = System.currentTimeMillis()
        if (sessionStart > 0 && lastActivity > 0 && now - lastActivity > IDLE_MS) {
            val end = lastActivity + IDLE_TAIL_MS
            viewModelScope.launch(ScreenErrors) { flushStats(end) }
        }
        lastActivity = now
    }

    private suspend fun flushStats(endAt: Long? = null) {
        val start = sessionStart
        if (start == 0L) return
        val now = System.currentTimeMillis()
        // Without page turns for a long time the reader was probably left open: stop counting shortly after the last one.
        val end = endAt ?: if (lastActivity > start) minOf(now, lastActivity + IDLE_MS) else now
        val duration = (end - start).coerceAtLeast(0)
        val pages = sessionPages
        val chars = sessionChars
        sessionStart = now
        sessionPages = 0
        sessionChars = 0
        if (duration < 5_000) return
        // Incognito: no reading history or statistics.
        if (settings.incognito.get()) return
        withContext(NonCancellable) {
            stats.recordSession(args.bookId.takeIf { it > 0 }, start, duration, pages, chars, onlineEntryId.takeIf { isOnline })
            if (chars > 500 && duration > 60_000) {
                val cpm = chars * 60_000f / duration
                val old = settings.readingSpeedCpm.get()
                settings.readingSpeedCpm.set(if (old <= 0f) cpm else old * 0.8f + cpm * 0.2f)
            }
        }
    }

    suspend fun readingSpeed(): Float = settings.readingSpeedCpm.get().takeIf { it > 0f } ?: 1000f

    // ================================================================== navigation
    fun goToToc(item: TocItem) {
        rememberJump()
        when (val c = current()) {
            is ReaderContent.Text -> if (isOnline) openChapterAt(item.chapter) else c.controller.goTo(item.chapter, item.anchor?.let { EpubTarget.Anchor(it) } ?: EpubTarget.Progression(0f))
            is ReaderContent.Pdf -> c.controller.goTo(item.chapter)
            is ReaderContent.Manga -> c.controller.goTo(item.chapter)
            null -> Unit
        }
    }

    fun goToLocator(l: Locator) {
        rememberJump()
        navigate(l)
    }

    private fun navigate(l: Locator) {
        when (val c = current()) {
            is ReaderContent.Text -> c.controller.goTo(l.chapter, l.offset?.let { EpubTarget.Offset(it) } ?: EpubTarget.Progression(l.progression))
            is ReaderContent.Pdf -> c.controller.goTo(l.chapter)
            is ReaderContent.Manga -> c.controller.goTo(l.chapter)
            null -> Unit
        }
    }

    // ================================================================== page numbers & jumps
    /** Book weight units per screen page, measured on chapters long enough to be representative. */
    private var weightPerPage = 0f
    private var hereLocator: Locator? = null
    private val _jumpBack = MutableStateFlow<JumpBack?>(null)
    val jumpBack: StateFlow<JumpBack?> = _jumpBack

    /** Page of the whole book and page count, from the exact pages of this chapter and the sizes of the others. */
    private fun estimatePages(doc: app.yomu.feature.reader.epub.TextDocument, chapter: Int, progress: app.yomu.feature.reader.epub.EpubProgress): Pair<Int, Int> {
        val pages = progress.pages.coerceAtLeast(1)
        val w = doc.weight(chapter).coerceAtLeast(1).toFloat()
        if (pages >= 3 || weightPerPage <= 0f) weightPerPage = w / pages
        val per = weightPerPage.coerceAtLeast(1f)
        fun pagesOf(i: Int) = if (i == chapter) pages else kotlin.math.ceil(doc.weight(i).coerceAtLeast(1) / per).toInt().coerceAtLeast(1)
        val before = (0 until chapter).sumOf { pagesOf(it) }
        val all = before + (chapter until doc.chapterCount).sumOf { pagesOf(it) }
        return (before + progress.page + 1).coerceAtMost(all) to all
    }

    /** What the slider at [value] (0..1 of the book) points to. */
    fun seekPreview(value: Float): SeekPreview {
        val p = _position.value
        return when (val c = current()) {
            is ReaderContent.Text -> {
                val doc = c.controller.document
                val (ch, _) = doc.locate(value.coerceIn(0f, 1f))
                val pages = p.bookPages.coerceAtLeast(1)
                SeekPreview((value * (pages - 1)).toInt() + 1, pages, p.approximate, doc.chapterTitle(ch))
            }
            else -> {
                val pages = p.pages.coerceAtLeast(1)
                val page = (value * (pages - 1)).toInt()
                SeekPreview(page + 1, pages, false, _toc.value.lastOrNull { it.chapter <= page }?.title)
            }
        }
    }

    /** Jumps to the 1-based [page] of the whole book (estimated for text books). */
    fun goToPage(page: Int) {
        val p = _position.value
        when (val c = current()) {
            is ReaderContent.Text -> {
                val pages = p.bookPages.coerceAtLeast(1)
                seekTotal(((page - 1).toFloat() / (pages - 1).coerceAtLeast(1)).coerceIn(0f, 1f))
            }
            is ReaderContent.Pdf -> { rememberJump(); c.controller.goTo((page - 1).coerceIn(0, c.controller.pageCount - 1)) }
            is ReaderContent.Manga -> { rememberJump(); c.controller.goTo((page - 1).coerceIn(0, c.controller.count - 1)) }
            null -> Unit
        }
    }

    private fun rememberJump() {
        val l = hereLocator ?: return
        _jumpBack.value = JumpBack(l, _position.value.bookPage)
    }

    /** Returns to where the reader was before the last jump. */
    fun jumpBack() {
        val j = _jumpBack.value ?: return
        _jumpBack.value = null
        navigate(j.locator)
    }

    fun dismissJumpBack() {
        _jumpBack.value = null
    }

    /** Slider: 0..1 over the whole publication. */
    fun seekTotal(value: Float) {
        rememberJump()
        when (val c = current()) {
            is ReaderContent.Text -> {
                val (ch, p) = c.controller.document.locate(value)
                c.controller.goTo(ch, EpubTarget.Progression(p))
            }
            is ReaderContent.Pdf -> c.controller.goTo((value * (c.controller.pageCount - 1)).toInt())
            is ReaderContent.Manga -> c.controller.goTo((value * (c.controller.count - 1)).toInt())
            null -> Unit
        }
    }

    fun nextPage() {
        when (val c = current()) {
            is ReaderContent.Text -> c.controller.next()
            is ReaderContent.Pdf -> c.controller.goTo(c.controller.page + 1)
            is ReaderContent.Manga -> c.controller.next()
            null -> Unit
        }
    }

    fun prevPage() {
        when (val c = current()) {
            is ReaderContent.Text -> c.controller.prev()
            is ReaderContent.Pdf -> c.controller.goTo(c.controller.page - 1)
            is ReaderContent.Manga -> c.controller.prev()
            null -> Unit
        }
    }

    fun nextChapter() {
        when (val c = current()) {
            is ReaderContent.Text -> if (isOnline) openAdjacentChapter(true) else c.controller.nextChapter()
            is ReaderContent.Manga -> if (isOnline) openAdjacentChapter(true)
            else -> Unit
        }
    }

    fun prevChapter() {
        when (val c = current()) {
            is ReaderContent.Text -> if (isOnline) openAdjacentChapter(false) else c.controller.prevChapter()
            is ReaderContent.Manga -> if (isOnline) openAdjacentChapter(false)
            else -> Unit
        }
    }

    // ================================================================== annotations
    private fun pushHighlights() {
        when (val c = current()) {
            is ReaderContent.Text -> c.controller.setHighlights(highlights.value)
            is ReaderContent.Pdf -> c.controller.highlights = highlights.value
            else -> Unit
        }
    }

    private fun selectionLocator(s: ReaderSelection): Locator {
        val p = _position.value
        val title = when (val c = current()) {
            is ReaderContent.Text -> c.controller.document.chapterTitle(s.chapter)
            else -> null
        }
        return Locator(chapter = s.chapter, progression = p.progression, totalProgression = p.total, offset = s.start, endOffset = s.end, text = s.text.take(200), chapterTitle = title)
    }

    fun highlight(color: HighlightColor, style: HighlightStyle, quote: Boolean = false, note: String? = null) {
        val s = selection.value ?: return
        if (args.bookId <= 0) {
            clearSelection()
            events.trySend(ReaderEvent.Message(R.string.reader_online_no_annotations))
            return
        }
        viewModelScope.launch(ScreenErrors) {
            val existing = s.highlightId?.let { id -> highlights.value.firstOrNull { it.id == id } }
            if (existing != null) {
                annotations.saveHighlight(existing.copy(color = color, style = style, isQuote = existing.isQuote || quote, note = note ?: existing.note, tags = TextTools.hashtags(note ?: existing.note).ifEmpty { existing.tags }))
            } else {
                annotations.saveHighlight(Highlight(bookId = args.bookId, locator = selectionLocator(s), text = s.text, color = color, style = style, isQuote = quote, note = note, tags = TextTools.hashtags(note)))
            }
            clearSelection()
        }
    }

    fun saveNote(text: String) {
        val s = selection.value ?: return
        val existing = s.highlightId?.let { id -> highlights.value.firstOrNull { it.id == id } }
        if (existing != null) {
            viewModelScope.launch(ScreenErrors) { annotations.saveHighlight(existing.copy(note = text.ifBlank { null }, tags = TextTools.hashtags(text))); clearSelection() }
        } else {
            highlight(HighlightColor.YELLOW, HighlightStyle.HIGHLIGHT, note = text.ifBlank { null })
        }
    }

    fun deleteHighlight() {
        val id = selection.value?.highlightId ?: return
        val h = highlights.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(ScreenErrors) {
            annotations.deleteHighlight(id)
            clearSelection()
            events.send(ReaderEvent.HighlightDeleted(h))
        }
    }

    fun restoreHighlight(h: Highlight) = viewModelScope.launch(ScreenErrors) { annotations.restore(highlight = h) }

    fun noteOfSelection(): String? = selection.value?.highlightId?.let { id -> highlights.value.firstOrNull { it.id == id }?.note }

    fun clearSelection() {
        selection.value = null
        when (val c = current()) {
            is ReaderContent.Text -> c.controller.clearSelection()
            is ReaderContent.Pdf -> c.controller.selection = null
            else -> Unit
        }
    }

    fun expandSelection(paragraph: Boolean) {
        when (val c = current()) {
            is ReaderContent.Text -> c.controller.expand(paragraph)
            is ReaderContent.Pdf -> viewModelScope.launch(ScreenErrors) { c.controller.expand(paragraph) }
            else -> Unit
        }
    }

    /** Current bookmark at this position, if any. */
    fun bookmarkHere(): Bookmark? {
        val p = _position.value
        return bookmarks.value.firstOrNull { b ->
            b.locator.chapter == p.chapter && when (current()) {
                is ReaderContent.Text -> kotlin.math.abs(b.locator.progression - p.progression) < 0.5f / p.pages.coerceAtLeast(1)
                else -> true
            }
        }
    }

    fun toggleBookmark() {
        if (args.bookId <= 0) {
            events.trySend(ReaderEvent.Message(R.string.reader_online_no_annotations))
            return
        }
        val existing = bookmarkHere()
        viewModelScope.launch(ScreenErrors) {
            if (existing != null) {
                annotations.deleteBookmark(existing.id)
                events.send(ReaderEvent.BookmarkRemoved(existing))
            } else {
                val p = _position.value
                val text = (current() as? ReaderContent.Text)?.controller?.let { c -> runCatching { c.visibleText() }.getOrNull()?.take(120) }
                val title = text?.trim()?.ifBlank { null } ?: p.chapterTitle ?: context.getString(R.string.reader_page_n, p.page + 1)
                val offset = (current() as? ReaderContent.Text)?.controller?.progress?.offset
                val b = Bookmark(bookId = args.bookId, locator = Locator(p.chapter, p.progression, p.total, offset = offset, chapterTitle = p.chapterTitle), title = title)
                val id = annotations.saveBookmark(b)
                events.send(ReaderEvent.BookmarkAdded(b.copy(id = id)))
            }
        }
    }

    /** Free note attached to the current position (useful for PDF and comics, where there is no text selection). */
    fun addPageNote(text: String) {
        if (args.bookId <= 0 || text.isBlank()) return
        val p = _position.value
        val offset = (current() as? ReaderContent.Text)?.controller?.progress?.offset
        viewModelScope.launch(ScreenErrors) {
            val locator = Locator(p.chapter, p.progression, p.total, offset = offset, chapterTitle = p.chapterTitle)
            annotations.saveNote(Note(bookId = args.bookId, locator = locator, text = text.trim(), tags = TextTools.hashtags(text)))
            events.send(ReaderEvent.Message(R.string.reader_note_saved))
        }
    }

    fun undoBookmark(e: ReaderEvent) = viewModelScope.launch(ScreenErrors) {
        when (e) {
            is ReaderEvent.BookmarkAdded -> annotations.deleteBookmark(e.bookmark.id)
            is ReaderEvent.BookmarkRemoved -> annotations.restore(bookmark = e.bookmark)
            else -> Unit
        }
    }

    fun deleteBookmark(b: Bookmark) = viewModelScope.launch(ScreenErrors) {
        annotations.deleteBookmark(b.id)
        events.send(ReaderEvent.BookmarkRemoved(b))
    }

    /** Saves the selected word or phrase to "My words". */
    fun saveSelectionAsWord() {
        val s = selection.value ?: return
        viewModelScope.launch(ScreenErrors) {
            val tr = runCatching { translation.translate(s.text, null, null).translated }.getOrNull()
            words.save(
                app.yomu.domain.model.Word(
                    term = s.text.trim().take(120),
                    translation = tr,
                    context = s.sentence.ifBlank { null },
                    bookId = args.bookId.takeIf { it > 0 },
                    bookTitle = book?.title,
                    locator = selectionLocator(s),
                    lang = book?.language,
                ),
            )
            clearSelection()
            events.send(ReaderEvent.WordSaved(s.text.trim()))
        }
    }

    fun saveWord(term: String, translated: String, lang: String?) = viewModelScope.launch(ScreenErrors) {
        words.save(
            app.yomu.domain.model.Word(
                term = term.trim().take(120),
                translation = translated,
                lang = lang ?: book?.language,
                bookId = args.bookId.takeIf { it > 0 },
                bookTitle = book?.title,
                context = wordTap.value?.sentence ?: selection.value?.sentence,
            ),
        )
        events.send(ReaderEvent.WordSaved(term.trim()))
    }

    // ================================================================== settings
    fun updateText(transform: (TextReaderSettings) -> TextReaderSettings) = viewModelScope.launch(ScreenErrors) {
        if (perBook.value) saveOverride(bookOverride.value.copy(text = transform(textSettings.value)))
        else settings.textSettings.update(transform)
    }

    fun updateManga(transform: (MangaSettings) -> MangaSettings) = viewModelScope.launch(ScreenErrors) {
        val before = mangaSettings.value
        if (perBook.value) saveOverride(bookOverride.value.copy(manga = transform(mangaSettings.value)))
        else settings.mangaSettings.update(transform)
        // Changed where taps go: show the new zones right away.
        val after = transform(before)
        if (after.tapZones != TapZoneLayout.DISABLED &&
            (after.tapZones != before.tapZones || after.invertTapZones != before.invertTapZones || after.direction != before.direction)
        ) {
            previewTapZones()
        }
    }

    fun updatePdf(transform: (PdfSettings) -> PdfSettings) = viewModelScope.launch(ScreenErrors) {
        if (perBook.value) saveOverride(bookOverride.value.copy(pdf = transform(pdfSettings.value)))
        else settings.pdfSettings.update(transform)
    }

    val wordTapSetting = settings.wordTap.stateIn(viewModelScope)

    // ---------------------------------------------------------------- presets
    /** Text presets saved by the user (the built-in ones live in [TextPresets]). */
    val presets: StateFlow<List<TextPreset>> = settings.readerPresetsJson.flow
        .map { raw -> runCatching { SettingsJson.decodeFromString(ListSerializer(TextPreset.serializer()), raw) }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun applyPreset(preset: TextPreset) = updateText { TextPresets.apply(it, preset.settings, withColors = true) }

    fun savePreset(name: String) = viewModelScope.launch(ScreenErrors) {
        val n = name.trim().take(40).ifEmpty { return@launch }
        val list = presets.value.filterNot { it.name.equals(n, ignoreCase = true) } + TextPreset(n, textSettings.value)
        settings.readerPresetsJson.set(SettingsJson.encodeToString(ListSerializer(TextPreset.serializer()), list.takeLast(MAX_PRESETS)))
    }

    fun deletePreset(name: String) = viewModelScope.launch(ScreenErrors) {
        val list = presets.value.filterNot { it.name == name }
        settings.readerPresetsJson.set(SettingsJson.encodeToString(ListSerializer(TextPreset.serializer()), list))
    }

    /** Typography back to the defaults; theme, page mode and colors stay. */
    fun resetTextLayout() = updateText { TextPresets.apply(it, TextReaderSettings()) }

    fun setWordTap(g: app.yomu.domain.model.WordTapGesture) = viewModelScope.launch(ScreenErrors) { settings.wordTap.set(g) }

    fun updateCommon(transform: (CommonReaderSettings) -> CommonReaderSettings) = viewModelScope.launch(ScreenErrors) { settings.commonReader.update(transform) }

    /** Switches between global settings and settings stored only for this book. */
    fun setPerBook(enabled: Boolean) = viewModelScope.launch(ScreenErrors) {
        val o = bookOverride.value
        val next = when (current()) {
            is ReaderContent.Text -> o.copy(text = if (enabled) textSettings.value else null)
            is ReaderContent.Pdf -> o.copy(pdf = if (enabled) pdfSettings.value else null)
            is ReaderContent.Manga -> o.copy(manga = if (enabled) mangaSettings.value else null)
            null -> o
        }
        saveOverride(next)
    }

    private suspend fun saveOverride(o: BookReaderSettings) {
        bookOverride.value = o
        val json = if (o.text == null && o.manga == null && o.pdf == null) null else SettingsJson.encodeToString(BookReaderSettings.serializer(), o)
        if (isOnline) {
            catalog.chapter(_chapterId.value)?.let { catalog.setReaderSettings(it.entryId, json) }
        } else if (args.bookId > 0) {
            books.saveReaderSettings(args.bookId, json)
        }
    }

    // ================================================================== search
    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            searchState.value = SearchState(query)
            return
        }
        searchState.value = SearchState(query, loading = true)
        searchJob = viewModelScope.launch(ScreenErrors) {
            when (val c = current()) {
                is ReaderContent.Text -> {
                    val doc = c.controller.document
                    val hits = mutableListOf<SearchHit>()
                    withContext(Dispatchers.Default) {
                        for (i in 0 until doc.chapterCount) {
                            if (!isActive || hits.size >= 500) break
                            val text = ChapterText.of(doc.chapterHtml(i))
                            var from = 0
                            var occ = 0
                            while (hits.size < 500) {
                                val at = text.indexOf(q, from, ignoreCase = true)
                                if (at < 0) break
                                val s = (at - 40).coerceAtLeast(0)
                                val e = (at + q.length + 60).coerceAtMost(text.length)
                                hits += SearchHit(i, occ++, text.substring(s, e).replace('\n', ' '), at - s, q.length, doc.chapterTitle(i))
                                from = at + q.length
                            }
                            searchState.update { it.copy(hits = hits.toList()) }
                        }
                    }
                    searchState.value = SearchState(query, hits = hits, done = true)
                }
                is ReaderContent.Pdf -> {
                    val res = runCatching { c.controller.pdf.search(q) }.getOrDefault(emptyList())
                    c.controller.hits = res
                    searchState.value = SearchState(query, hits = res.mapIndexed { i, h -> SearchHit(h.page, i, h.snippet, 0, h.length, null) }, done = true)
                }
                else -> searchState.value = SearchState(query, done = true)
            }
        }
    }

    /** Positions the reader at a hit and marks it. */
    fun openHit(hit: SearchHit) {
        rememberJump()
        when (val c = current()) {
            is ReaderContent.Text -> viewModelScope.launch(ScreenErrors) {
                val doc = c.controller.document
                val text = ChapterText.of(doc.chapterHtml(hit.chapter))
                var from = 0
                var at = -1
                repeat(hit.occurrence + 1) {
                    at = text.indexOf(searchState.value.query.trim(), from, ignoreCase = true)
                    if (at < 0) return@repeat
                    from = at + hit.matchLength
                }
                if (at < 0) return@launch
                c.controller.goTo(hit.chapter, EpubTarget.Offset(at))
                delay(if (c.controller.chapter == hit.chapter) 50 else 600)
                c.controller.mark("search", at, at + hit.matchLength)
            }
            is ReaderContent.Pdf -> {
                val h = c.controller.hits.getOrNull(hit.occurrence) ?: return
                c.controller.currentHit = h
                c.controller.goTo(h.page)
            }
            else -> Unit
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchState.value = SearchState()
        when (val c = current()) {
            is ReaderContent.Text -> c.controller.mark("search", null, null)
            is ReaderContent.Pdf -> { c.controller.hits = emptyList(); c.controller.currentHit = null }
            else -> Unit
        }
    }

    // ================================================================== TTS
    fun startTts() {
        when (val c = current()) {
            is ReaderContent.Text -> {
                _ttsActive.value = true
                tts.language = c.controller.document.language ?: book?.language
                viewModelScope.launch(ScreenErrors) { speakTextFrom(c.controller, c.controller.progress.offset) }
            }
            is ReaderContent.Pdf -> {
                _ttsActive.value = true
                tts.language = book?.language
                speakPdfPage(c.controller.page)
            }
            else -> events.trySend(ReaderEvent.Message(R.string.reader_tts_unavailable))
        }
    }

    private suspend fun speakTextFrom(c: EpubController, offset: Int) {
        val list = runCatching { c.sentences(offset) }.getOrDefault(emptyList())
        ttsChapter = c.chapter
        ttsSentences = list.map { Triple(c.chapter, it.start, it.end) }
        if (list.isEmpty()) {
            onTtsQueueFinished()
            return
        }
        tts.play(list.map { it.text })
    }

    private fun speakPdfPage(page: Int) {
        val c = (current() as? ReaderContent.Pdf)?.controller ?: return
        viewModelScope.launch(ScreenErrors) {
            val text = runCatching { c.pdf.text(page) }.getOrDefault("")
            val ranges = sentenceRanges(text)
            ttsChapter = page
            ttsSentences = ranges.map { Triple(page, it.first, it.last + 1) }
            if (ranges.isEmpty()) {
                onTtsQueueFinished()
                return@launch
            }
            tts.play(ranges.map { text.substring(it.first, it.last + 1) })
        }
    }

    private fun onTtsSentence(i: Int) {
        val (ch, s, e) = ttsSentences.getOrNull(i) ?: return
        when (val c = current()) {
            is ReaderContent.Text -> if (c.controller.chapter == ch) c.controller.mark("tts", s, e, scroll = true)
            is ReaderContent.Pdf -> c.controller.ttsMark = Triple(ch, s, e)
            else -> Unit
        }
    }

    private fun onTtsQueueFinished() {
        if (!_ttsActive.value) return
        if (sleepTimer.value == SleepTimer.END_OF_CHAPTER) {
            stopTts()
            return
        }
        when (val c = current()) {
            is ReaderContent.Text -> {
                if (c.controller.chapter < c.controller.document.chapterCount - 1) {
                    ttsChapter = -1
                    c.controller.nextChapter()
                } else {
                    stopTts()
                    finishBook()
                }
            }
            is ReaderContent.Pdf -> {
                if (c.controller.page < c.controller.pageCount - 1) {
                    ttsChapter = -1
                    c.controller.goTo(c.controller.page + 1)
                } else {
                    stopTts()
                }
            }
            else -> stopTts()
        }
    }

    fun pauseTts() = tts.pause()
    fun resumeTts() = tts.resume()

    fun stopTts() {
        _ttsActive.value = false
        tts.stop()
        sleepJob?.cancel()
        sleepTimer.value = SleepTimer.OFF
        sleepRemainingMs.value = null
        when (val c = current()) {
            is ReaderContent.Text -> c.controller.mark("tts", null, null)
            is ReaderContent.Pdf -> c.controller.ttsMark = null
            else -> Unit
        }
    }

    fun setSleepTimer(t: SleepTimer) {
        sleepJob?.cancel()
        sleepTimer.value = t
        sleepRemainingMs.value = null
        if (t.minutes <= 0) return
        sleepJob = viewModelScope.launch(ScreenErrors) {
            val end = System.currentTimeMillis() + t.minutes * 60_000L
            while (isActive) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                sleepRemainingMs.value = left
                delay(1000)
            }
            stopTts()
            stopAutoScroll()
        }
    }

    fun ttsVoices() = tts.voices()

    // ================================================================== auto scroll
    fun toggleAutoScroll() = if (autoScroll.value) stopAutoScroll() else startAutoScroll()

    private fun startAutoScroll() {
        autoScroll.value = true
        chromeVisible.value = false
        autoScrollJob?.cancel()
        autoScrollJob = viewModelScope.launch(ScreenErrors) {
            while (isActive) {
                val speed = common.value.autoScrollSpeed.coerceIn(1, 100)
                when (val c = current()) {
                    is ReaderContent.Text -> if (textSettings.value.paged && !textSettings.value.verticalText) {
                        delay(pageInterval(speed))
                        c.controller.next()
                    } else {
                        delay(32)
                        c.controller.scrollStep((speed / 12f).coerceAtLeast(1f).toInt().coerceAtLeast(1))
                    }
                    is ReaderContent.Pdf -> { delay(pageInterval(speed)); c.controller.goTo(c.controller.page + 1) }
                    is ReaderContent.Manga -> { delay(pageInterval(speed)); c.controller.next() }
                    null -> delay(500)
                }
            }
        }
    }

    private fun pageInterval(speed: Int): Long = ((100 - speed) * 600L + 3_000L)

    fun stopAutoScroll() {
        autoScroll.value = false
        autoScrollJob?.cancel()
    }

    // ================================================================== translation
    fun toggleBilingual() {
        val on = !textSettings.value.bilingual
        updateText { it.copy(bilingual = on) }
        if (on) startBilingual() else {
            bilingualJob?.cancel()
            bilingualProgress.value = null
            (current() as? ReaderContent.Text)?.controller?.removeTranslations()
        }
    }

    private fun startBilingual() {
        val c = (current() as? ReaderContent.Text)?.controller ?: return
        bilingualJob?.cancel()
        bilingualJob = viewModelScope.launch(ScreenErrors) {
            delay(200)
            val blocks = runCatching { c.blocks(c.progress.offset) }.getOrDefault(emptyList()).filter { !it.translated && it.text.isNotBlank() }
            if (blocks.isEmpty()) return@launch
            bilingualProgress.value = 0f
            var failed = false
            blocks.forEachIndexed { i, b ->
                if (!isActive) return@launch
                val r = runCatching { translation.translate(b.text, book?.language, null) }
                r.onSuccess { c.insertTranslation(b.i, it.translated) }.onFailure { if (!failed) { failed = true; events.send(ReaderEvent.Error(it)) } }
                if (failed) return@forEachIndexed
                bilingualProgress.value = (i + 1f) / blocks.size
            }
            c.commitTranslations()
            bilingualProgress.value = null
        }
    }

    /** Translates the text currently visible (page translation). */
    fun translatePage() {
        when (val c = current()) {
            is ReaderContent.Text -> viewModelScope.launch(ScreenErrors) {
                val text = runCatching { c.controller.visibleText() }.getOrDefault("").trim()
                if (text.isNotEmpty()) translateText.value = text.take(4000)
            }
            is ReaderContent.Pdf -> viewModelScope.launch(ScreenErrors) {
                val page = c.controller.page
                val text = runCatching { c.controller.pdf.text(page) }.getOrDefault("").trim().ifEmpty { ocrPdfPage(c.controller.pdf, page) }
                if (text.isNotEmpty()) translateText.value = text.take(4000) else events.send(ReaderEvent.Message(R.string.reader_no_text))
            }
            is ReaderContent.Manga -> translateMangaPage(c.controller.page)
            null -> Unit
        }
    }

    /** Scanned PDF pages have no text layer: render the page and recognize it with OCR. */
    private suspend fun ocrPdfPage(pdf: PdfSource, page: Int): String {
        ocrRunning.value = true
        return try {
            runCatching {
                val bmp = pdf.render(page, 1600, annotations = false)
                try {
                    ocr.recognize(bmp, settings.ocrLanguage.get(), cacheKey = "pdf_${args.bookId}_$page").joinToString("\n") { it.text }.trim()
                } finally {
                    bmp.recycle()
                }
            }.getOrDefault("")
        } finally {
            ocrRunning.value = false
        }
    }

    /** Runs OCR on a manga page, translates the text blocks and shows them as an overlay. */
    fun translateMangaPage(page: Int) {
        val c = (current() as? ReaderContent.Manga)?.controller ?: return
        if (c.overlays.containsKey(page)) {
            c.overlayVisible = !c.overlayVisible
            return
        }
        viewModelScope.launch(ScreenErrors) {
            ocrRunning.value = true
            runCatching {
                val file = c.file(page)
                val bmp = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file.absolutePath) } ?: error("Cannot decode page")
                val w = bmp.width.toFloat()
                val h = bmp.height.toFloat()
                val lang = settings.ocrLanguage.get()
                val blocks = ocr.recognize(bmp, lang, cacheKey = "${args.bookId}_${_chapterId.value}_$page")
                bmp.recycle()
                if (blocks.isEmpty()) {
                    events.send(ReaderEvent.Message(R.string.reader_ocr_nothing))
                    return@runCatching
                }
                val translated = blocks.map { b ->
                    val t = runCatching { translation.translate(b.text, lang, null).translated }.getOrDefault(b.text)
                    OverlayBlock(b.left / w, b.top / h, b.right / w, b.bottom / h, b.text, t)
                }
                c.overlays[page] = translated
                c.overlayVisible = true
            }.onFailure { events.send(ReaderEvent.Error(it)) }
            ocrRunning.value = false
        }
    }

    fun sentenceRanges(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        val it = java.text.BreakIterator.getSentenceInstance()
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != java.text.BreakIterator.DONE) {
            val s = text.substring(start, end)
            if (s.isNotBlank()) {
                val lead = s.indexOfFirst { !it.isWhitespace() }
                val trail = s.indexOfLast { !it.isWhitespace() }
                out += (start + lead)..(start + trail)
            }
            start = end
            end = it.next()
        }
        return out
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        val folderLease = folderReadSession
        folderReadSession = null
        tts.shutdown()
        autoScrollJob?.cancel()
        val c = current()
        saveJob?.cancel()
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                runCatching { flushProgress() }
                runCatching { flushStatsBlocking() }
                when (c) {
                    is ReaderContent.Text -> runCatching { c.controller.document.close() }
                    is ReaderContent.Pdf -> {
                        runCatching { c.controller.close() }
                        runCatching { PdfAnnotationWorker.enqueue(context, bookId) }
                    }
                    is ReaderContent.Manga -> runCatching { (c.controller.pages as? java.io.Closeable)?.close() }
                    null -> Unit
                }
            } finally { folderLease?.close() }
        }
    }

    private suspend fun flushStatsBlocking() = flushStats()
}

/** A pause longer than this without page turns ends the counted reading time. */
private const val IDLE_MS = 5 * 60_000L
private const val MAX_PRESETS = 12

/** Reading time credited after the last activity when a session is closed for being idle. */
private const val IDLE_TAIL_MS = 60_000L
