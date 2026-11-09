package app.yomu.feature.editor

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.AppError
import app.yomu.core.common.AppScope
import app.yomu.core.common.Files
import app.yomu.core.common.ScreenErrors
import app.yomu.core.datastore.AppSettings
import app.yomu.data.book.Containers
import app.yomu.data.book.EpubBuilder
import app.yomu.data.book.EpubMeta
import app.yomu.data.book.EpubParser
import app.yomu.data.book.ZipContainer
import app.yomu.data.library.BookFiles
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.data.translation.BookTranslationWorker
import app.yomu.data.translation.BookTranslator
import app.yomu.data.translation.TranslationService
import app.yomu.data.ocr.OcrService
import app.yomu.domain.model.Book
import app.yomu.domain.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

enum class EditorKind { TEXT, MANGA }

enum class ExportType(val ext: String, val mime: String) {
    EPUB("epub", "application/epub+zip"),
    TXT("txt", "text/plain"),
    PDF("pdf", "application/pdf"),
    CBZ("cbz", "application/vnd.comicbook+zip"),
}

data class EditorStatus(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val progress: Float? = null,
)

data class EpubState(
    val version: Long,
    val root: File,
    val meta: EpubMeta,
    val chapters: List<EditorChapter>,
    val toc: List<TocEntry>,
    val images: List<ManifestImage>,
    val styles: List<String>,
    val fonts: List<String>,
    val coverPath: String?,
    val epub3: Boolean,
)

data class MangaState(val version: Long, val root: File, val pages: List<MangaPage>, val info: ComicInfo)

sealed interface EditorPhase {
    data object Loading : EditorPhase
    data class DraftFound(val modifiedAt: Long) : EditorPhase
    data class Failed(val error: AppError) : EditorPhase
    data object Unsupported : EditorPhase
    data class Ready(val kind: EditorKind) : EditorPhase
}

sealed interface EditorEvent {
    data class Message(@StringRes val res: Int, val args: List<Any> = emptyList()) : EditorEvent
    data class Error(val error: Throwable) : EditorEvent
    data object Close : EditorEvent
}

/**
 * Editor for one library book. Works on a persistent working copy in `files/editor/<bookId>` (the draft);
 * the original file is only touched by [saveToBook]. Every change is an undoable [History] step.
 */
class EditorViewModel(
    private val context: Context,
    val bookId: Long,
    private val books: BookRepositoryImpl,
    private val importer: ImportService,
    private val settings: AppSettings,
    private val bookTranslator: BookTranslator,
    private val ocr: OcrService,
    private val translation: TranslationService,
    private val appScope: AppScope = AppScope(),
) : ViewModel() {
    private val draftDir = File(context.filesDir, "editor/$bookId")
    private val workDir = File(draftDir, "work")
    private val historyDir = File(draftDir, "history")
    private val draftFile = File(draftDir, "draft.json")
    private val mutex = Mutex()
    private var history: History? = null
    private var epubWs: EpubWorkspace? = null
    private var mangaWs: MangaWorkspace? = null
    private var version = 0L
    private var kind = EditorKind.TEXT

    private val _phase = MutableStateFlow<EditorPhase>(EditorPhase.Loading)
    val phase: StateFlow<EditorPhase> = _phase.asStateFlow()
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()
    private val _status = MutableStateFlow(EditorStatus())
    val status: StateFlow<EditorStatus> = _status.asStateFlow()
    private val _epub = MutableStateFlow<EpubState?>(null)
    val epub: StateFlow<EpubState?> = _epub.asStateFlow()
    private val _manga = MutableStateFlow<MangaState?>(null)
    val manga: StateFlow<MangaState?> = _manga.asStateFlow()
    private val _searchHits = MutableStateFlow<List<EditorSearchHit>?>(null)
    val searchHits: StateFlow<List<EditorSearchHit>?> = _searchHits.asStateFlow()
    private val _issues = MutableStateFlow<List<Issue>?>(null)
    val issues: StateFlow<List<Issue>?> = _issues.asStateFlow()
    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch(ScreenErrors) { start() }
    }

    // ---------------------------------------------------------------- lifecycle of the draft

    private suspend fun start() {
        val book = books.getBook(bookId)
        if (book == null) {
            _phase.value = EditorPhase.Failed(AppError.CorruptedFile("Book $bookId not found"))
            return
        }
        _book.value = book
        kind = when {
            book.format.isComic -> EditorKind.MANGA
            BookFiles.readingFormat(book) == BookFormat.EPUB -> EditorKind.TEXT
            else -> {
                _phase.value = EditorPhase.Unsupported
                return
            }
        }
        val draft = withContext(Dispatchers.IO) { readDraft() }
        if (draft != null && draft.second && workDir.isDirectory) {
            _phase.value = EditorPhase.DraftFound(draft.first)
        } else {
            open(fresh = true)
        }
    }

    fun resumeDraft() = viewModelScope.launch(ScreenErrors) { open(fresh = false) }

    fun startOver() = viewModelScope.launch(ScreenErrors) { open(fresh = true) }

    private suspend fun open(fresh: Boolean) {
        _phase.value = EditorPhase.Loading
        val book = _book.value ?: return
        runCatchingCancellable {
            withContext(Dispatchers.IO) {
                if (fresh) {
                    draftDir.deleteRecursively()
                    workDir.mkdirs()
                    if (kind == EditorKind.TEXT) extractEpub(book) else extractManga(book)
                    writeDraft(dirty = false)
                }
                history = History(workDir, historyDir)
                if (kind == EditorKind.TEXT) epubWs = EpubWorkspace(workDir) else mangaWs = MangaWorkspace(workDir)
                if (!fresh) _status.update { it.copy(dirty = readDraft()?.second ?: true) }
            }
        }.onSuccess {
            refresh()
            _phase.value = EditorPhase.Ready(kind)
        }.onFailure { e ->
            if (fresh) draftDir.deleteRecursively()
            _phase.value = EditorPhase.Failed(AppError.from(e))
        }
    }

    private suspend fun extractEpub(book: Book) {
        val uri = BookFiles.readingUri(context, book)
        Containers.open(context, uri, BookFormat.EPUB).use { c ->
            c.entries.forEach { name ->
                if (!safeEntry(name)) return@forEach
                val dest = File(workDir, name)
                dest.parentFile?.mkdirs()
                c.open(name)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun extractManga(book: Book) {
        Containers.open(context, Uri.parse(book.uri), book.format).use { c ->
            val images = Containers.imageEntries(c)
            if (images.isEmpty()) throw AppError.CorruptedFile("No images")
            val files = images.mapIndexedNotNull { i, name ->
                val ext = name.substringAfterLast('.', "jpg").lowercase().take(5)
                val rel = "${MangaWorkspace.IMG_DIR}/%05d.%s".format(i + 1, ext)
                val dest = File(workDir, rel)
                dest.parentFile?.mkdirs()
                c.open(name)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return@mapIndexedNotNull null
                rel
            }
            val infoEntry = c.entries.firstOrNull { it.substringAfterLast('/').equals("ComicInfo.xml", true) }
            val info = infoEntry?.let { runCatchingCancellable { ComicInfoXml.parse(String(c.read(it)!!)) }.getOrNull() }
                ?: ComicInfo(title = book.title, series = book.series.orEmpty(), writer = book.authorsText, languageIso = book.language.orEmpty())
            MangaWorkspace.create(workDir, files, info)
        }
    }

    private fun safeEntry(name: String) = name.isNotEmpty() && !name.startsWith("/") && name.split('/', '\\').none { it == ".." }

    private fun readDraft(): Pair<Long, Boolean>? = runCatchingCancellable {
        val o = Json.parseToJsonElement(draftFile.readText()).jsonObject
        (o["modifiedAt"]?.jsonPrimitive?.longOrNull ?: 0L) to (o["dirty"]?.jsonPrimitive?.booleanOrNull ?: false)
    }.getOrNull()

    private fun writeDraft(dirty: Boolean) {
        draftDir.mkdirs()
        writeTextAtomic(draftFile, buildJsonObject { put("bookId", bookId); put("kind", kind.name); put("modifiedAt", System.currentTimeMillis()); put("dirty", dirty) }.toString())
    }

    private fun setDirty(dirty: Boolean) {
        runCatchingCancellable { writeDraft(dirty) }
        _status.update { it.copy(dirty = dirty) }
    }

    /** Leaves the editor. [keepDraft] keeps unsaved changes for later. */
    fun close(keepDraft: Boolean) = viewModelScope.launch(ScreenErrors) {
        mutex.withLock {
            if (!keepDraft || !_status.value.dirty) withContext(Dispatchers.IO) { draftDir.deleteRecursively() }
            _events.send(EditorEvent.Close)
        }
    }

    // ---------------------------------------------------------------- state

    private suspend fun refresh() = withContext(Dispatchers.IO) {
        version++
        epubWs?.let { ws ->
            runCatchingCancellable { ws.reload() }
            _epub.value = EpubState(
                version = version,
                root = ws.root,
                meta = ws.meta,
                chapters = ws.chapters(),
                toc = ws.toc(),
                images = ws.images(),
                styles = ws.stylesheets(),
                fonts = ws.fontFiles(),
                coverPath = ws.pkg.coverPath,
                epub3 = ws.isEpub3,
            )
        }
        mangaWs?.let { ws ->
            ws.load()
            _manga.value = MangaState(version, ws.root, ws.pages, ws.info)
        }
        val h = history
        _status.update {
            it.copy(canUndo = h?.canUndo == true, canRedo = h?.canRedo == true, undoLabel = h?.undoLabel, redoLabel = h?.redoLabel)
        }
    }

    private fun message(@StringRes res: Int, vararg args: Any) {
        _events.trySend(EditorEvent.Message(res, args.toList()))
    }

    /** Runs [op] as one undoable step touching [files] (new files are detected automatically). */
    private fun <T> edit(@StringRes label: Int, files: () -> Collection<String>, onDone: (T) -> Unit = {}, op: () -> T) {
        viewModelScope.launch(ScreenErrors) {
            mutex.withLock {
                _status.update { it.copy(busy = true, progress = null) }
                val result = runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        val h = history ?: error("Editor is not ready")
                        h.change(context.getString(label), files()) { op() }
                    }
                }
                result.onSuccess { setDirty(true) }
                refresh()
                _status.update { it.copy(busy = false, progress = null) }
                result.onSuccess(onDone).onFailure { _events.send(EditorEvent.Error(it)) }
            }
        }
    }

    private fun progress(p: Float) = _status.update { it.copy(progress = p) }

    fun undo() = viewModelScope.launch(ScreenErrors) {
        mutex.withLock {
            val step = withContext(Dispatchers.IO) { history?.undo() } ?: return@withLock
            setDirty(true)
            refresh()
            message(R.string.editor_undone, step.label)
        }
    }

    fun redo() = viewModelScope.launch(ScreenErrors) {
        mutex.withLock {
            val step = withContext(Dispatchers.IO) { history?.redo() } ?: return@withLock
            setDirty(true)
            refresh()
            message(R.string.editor_redone, step.label)
        }
    }

    // ---------------------------------------------------------------- EPUB operations

    private val ws: EpubWorkspace get() = epubWs ?: error("No EPUB working copy")

    fun saveMeta(meta: EpubMeta) = edit(R.string.editor_step_metadata, { listOf(ws.opfPath) }) { ws.writeMeta(meta) }

    fun setCover(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        val image = readImage(uri) ?: return@launch
        edit(R.string.editor_step_cover, { listOf(ws.opfPath, ws.coverTarget(image.second)) }) { ws.setCover(image.first, image.second) }
    }

    private suspend fun readImage(uri: Uri): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val bytes = Files.open(context, uri).use { it.readBytes() }
            if (!ImageOps.isImage(bytes)) throw AppError.UnsupportedFormat(Files.displayName(context, uri) ?: "image")
            val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") }
                ?: MediaTypes.of(Files.displayName(context, uri) ?: "x.jpg")
            bytes to mime
        }.onFailure { _events.send(EditorEvent.Error(it)) }.getOrNull()
    }

    fun moveChapter(from: Int, to: Int) = edit(R.string.editor_step_move, { ws.structureFiles() }) { ws.moveChapter(from, to) }

    fun addChapter(after: Int, title: String) = edit(R.string.editor_step_add_chapter, { ws.structureFiles() }) { ws.addChapter(after, title) }

    fun deleteChapter(path: String) = edit(R.string.editor_step_delete_chapter, { ws.structureFiles() + path }) { ws.deleteChapter(path) }

    fun renameChapter(path: String, title: String) = edit(R.string.editor_step_rename, { ws.structureFiles() + path }) { ws.renameChapter(path, title) }

    fun splitChapter(path: String, at: List<Int>) = edit(
        R.string.editor_step_split,
        { ws.structureFiles() + ws.documents() },
        onDone = { paths: List<String> -> message(R.string.editor_split_done, paths.size + 1) },
    ) { ws.splitChapter(path, at) }

    fun mergeWithNext(path: String) = edit(R.string.editor_step_merge, { ws.structureFiles() + ws.documents() }) { ws.mergeWithNext(path) }

    suspend fun blocks(path: String): List<BlockInfo> = withContext(Dispatchers.IO) { runCatchingCancellable { ws.blocks(path) }.getOrDefault(emptyList()) }

    fun updateToc(entries: List<TocEntry>) = edit(R.string.editor_step_toc, { ws.structureFiles() }) { ws.writeToc(entries) }

    fun tocFromHeadings() = edit(R.string.editor_step_toc, { ws.structureFiles() + ws.spinePaths }) { ws.writeToc(ws.tocFromHeadings()) }

    fun tocFromChapters() = edit(R.string.editor_step_toc, { ws.structureFiles() }) { ws.writeToc(ws.tocFromChapters()) }

    /** Text of a file of the book; null (after an error message) when it cannot be read. */
    suspend fun readSource(path: String): String? = withContext(Dispatchers.IO) { runCatchingCancellable { ws.readText(path) } }
        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; _events.send(EditorEvent.Error(it)) }
        .getOrNull()

    suspend fun checkXml(text: String): String? = withContext(Dispatchers.Default) { EpubWorkspace.wellFormedError(text) }

    fun saveSource(path: String, text: String, onSaved: () -> Unit = {}) =
        edit(R.string.editor_step_source, { listOf(path) }, onDone = { _: Unit -> onSaved() }) { ws.writeText(path, text) }

    fun saveVisual(path: String, html: String, onSaved: () -> Unit = {}) =
        edit(R.string.editor_step_visual, { listOf(path) }, onDone = { _: Unit -> onSaved() }) { ws.replaceBody(path, html) }

    fun file(path: String): File = File(workDir, path)

    fun search(q: SearchQuery, scope: List<String>?) = viewModelScope.launch(ScreenErrors) {
        _searchHits.value = null
        runCatchingCancellable { withContext(Dispatchers.IO) { ws.search(q, scope ?: ws.spinePaths) } }
            .onSuccess { _searchHits.value = it }
            .onFailure {
                _searchHits.value = emptyList()
                _events.send(EditorEvent.Error(it))
            }
    }

    fun clearSearch() {
        _searchHits.value = null
    }

    fun replaceAll(q: SearchQuery, replacement: String, scope: List<String>?) = edit(
        R.string.editor_step_replace,
        { ws.search(q, scope ?: ws.spinePaths).map { it.path } },
        onDone = { n: Int ->
            message(R.string.editor_replaced, n)
            search(q, scope)
        },
    ) { ws.replaceAll(q, replacement, scope ?: ws.spinePaths) }

    fun cleanup(o: CleanupOptions, scope: List<String>?) = edit(
        R.string.editor_step_cleanup,
        {
            val docs = scope ?: ws.spinePaths
            if (!o.hyphenation) docs else docs + ws.opfPath + ws.mainStylesheetOrNew() + (if (ws.stylesheets().isEmpty()) ws.documents() else emptyList())
        },
        onDone = { n: Int -> message(R.string.editor_cleanup_done, n) },
    ) { ws.cleanup(o, scope ?: ws.spinePaths) }

    fun cleanStyles(o: StyleCleanup) = edit(
        R.string.editor_step_styles,
        { listOf(ws.opfPath) + ws.stylesheets() + ws.documents() + ws.fontFiles() },
        onDone = { n: Int -> message(R.string.editor_cleanup_done, n) },
    ) { ws.cleanStyles(o) }

    fun addImage(uri: Uri, insertInto: String?) = viewModelScope.launch(ScreenErrors) {
        val (bytes, mime) = readImage(uri) ?: return@launch
        val name = Files.displayName(context, uri)?.substringBeforeLast('.')?.let { "$it.${MediaTypes.extensionOf(mime)}" } ?: "image.${MediaTypes.extensionOf(mime)}"
        edit(R.string.editor_step_add_image, { listOfNotNull(ws.opfPath, insertInto) }) { ws.addImage(ws.newImagePath(name), bytes, mime, insertInto) }
    }

    fun replaceImage(path: String, uri: Uri) = viewModelScope.launch(ScreenErrors) {
        val (bytes, mime) = readImage(uri) ?: return@launch
        edit(R.string.editor_step_replace_image, { listOf(ws.opfPath, path) }) { ws.replaceImage(path, bytes, mime) }
    }

    fun deleteImage(path: String) = edit(R.string.editor_step_delete_image, { listOf(ws.opfPath, path) + ws.usagesOf(path) }) { ws.deleteImage(path) }

    fun validate() = viewModelScope.launch(ScreenErrors) {
        _issues.value = null
        _status.update { it.copy(busy = true) }
        runCatchingCancellable { withContext(Dispatchers.IO) { ws.validate() } }
            .onSuccess { _issues.value = it }
            .onFailure { _events.send(EditorEvent.Error(it)) }
        _status.update { it.copy(busy = false) }
    }

    fun fixIssues(list: List<Issue>) = edit(
        R.string.editor_step_fix,
        { ws.textFiles() },
        onDone = { n: Int ->
            message(R.string.editor_fixed, n)
            validate()
        },
    ) { ws.fix(list) }

    // ---------------------------------------------------------------- manga operations

    private val mws: MangaWorkspace get() = mangaWs ?: error("No manga working copy")
    private val pagesFile = listOf(MangaWorkspace.PAGES)

    fun movePages(indices: Set<Int>, position: Int) = edit(R.string.editor_step_move, { pagesFile }) { mws.moveTo(indices, position) }

    fun deletePages(indices: Set<Int>) = edit(R.string.editor_step_delete_pages, { pagesFile }) { mws.delete(indices) }

    fun rotatePages(indices: Set<Int>, delta: Int) = edit(R.string.editor_step_rotate, { pagesFile }) { mws.rotate(indices, delta) }

    fun setCoverPage(index: Int) = edit(R.string.editor_step_cover, { pagesFile }) { mws.setCover(index) }

    fun saveComicInfo(info: ComicInfo) = edit(R.string.editor_step_metadata, { listOf(MangaWorkspace.INFO) }) { mws.saveInfo(info) }

    fun insertPages(at: Int, uris: List<Uri>) = edit(R.string.editor_step_insert_pages, { pagesFile }) {
        val names = uris.mapNotNull { uri ->
            val bytes = Files.open(context, uri).use { it.readBytes() }
            if (!ImageOps.isImage(bytes)) return@mapNotNull null
            val mime = context.contentResolver.getType(uri) ?: MediaTypes.of(Files.displayName(context, uri) ?: "x.jpg")
            val name = mws.newImageName(MediaTypes.extensionOf(mime))
            File(workDir, name).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            name
        }
        mws.insert(at, names)
    }

    /** Applies a bitmap transformation to the selected pages, writing new files. */
    private fun transformPages(indices: Set<Int>, format: (MangaPage) -> Pair<OutFormat, Int>, transform: (android.graphics.Bitmap, Int) -> android.graphics.Bitmap) {
        val list = mws.pages.toMutableList()
        val targets = indices.sorted()
        targets.forEachIndexed { n, i ->
            val page = list[i]
            val src = ImageOps.decode(mws.file(page))
            val rotated = ImageOps.rotate(src, page.rotation)
            val out = transform(rotated, i)
            val (fmt, quality) = format(page)
            val name = mws.newImageName(fmt.ext)
            File(workDir, name).writeBytes(ImageOps.encode(out, fmt, quality))
            if (out !== rotated) out.recycle()
            if (rotated !== src) rotated.recycle()
            src.recycle()
            list[i] = MangaPage(name, 0)
            progress((n + 1f) / targets.size)
        }
        mws.savePages(list)
    }

    private fun keepFormat(page: MangaPage): Pair<OutFormat, Int> = ImageOps.formatOf(mws.file(page)) to 92

    fun cropPages(indices: Set<Int>, rect: CropRect?, auto: Boolean) = edit(R.string.editor_step_crop, { pagesFile }) {
        transformPages(indices, ::keepFormat) { bmp, _ ->
            val r = if (auto) ImageOps.autoCropRect(bmp) else rect ?: CropRect()
            if (r.isEmpty) bmp else ImageOps.crop(bmp, r)
        }
    }

    suspend fun autoCropPreview(index: Int): CropRect = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val page = mws.pages[index]
            val bmp = ImageOps.rotate(ImageOps.decode(mws.file(page), maxSide = 1200), page.rotation)
            ImageOps.autoCropRect(bmp).also { bmp.recycle() }
        }.getOrDefault(CropRect())
    }

    fun adjustPages(indices: Set<Int>, a: Adjust) = edit(R.string.editor_step_adjust, { pagesFile }) {
        transformPages(indices, ::keepFormat) { bmp, _ -> ImageOps.adjust(bmp, a) }
    }

    fun recompressPages(indices: Set<Int>, r: Recompress) = viewModelScope.launch(ScreenErrors) {
        val before = withContext(Dispatchers.IO) { indices.sumOf { mws.file(mws.pages[it]).length() } }
        edit(
            R.string.editor_step_recompress,
            { pagesFile },
            onDone = { _: Unit ->
                val after = indices.sumOf { mws.file(mws.pages[it]).length() }
                message(R.string.editor_recompress_done, Files.humanSize(before), Files.humanSize(after))
            },
        ) {
            transformPages(indices, { r.format to r.quality }) { bmp, _ -> ImageOps.resize(bmp, r.maxWidth) }
        }
    }

    fun splitSpread(index: Int) = edit(R.string.editor_step_split_page, { pagesFile }) {
        val page = mws.pages[index]
        val src = ImageOps.rotate(ImageOps.decode(mws.file(page)), page.rotation)
        val (left, right) = ImageOps.split(src)
        val (fmt, q) = keepFormat(page)
        val halves = if (mws.info.manga) listOf(right, left) else listOf(left, right)
        val names = halves.map { bmp ->
            mws.newImageName(fmt.ext).also { File(workDir, it).writeBytes(ImageOps.encode(bmp, fmt, q)) }
        }
        listOf(left, right, src).forEach { it.recycle() }
        mws.replace(index, names.map { MangaPage(it) })
    }

    fun joinPages(first: Int, second: Int) = edit(R.string.editor_step_join_pages, { pagesFile }) {
        val a = mws.pages[first]
        val b = mws.pages[second]
        val ba = ImageOps.rotate(ImageOps.decode(mws.file(a)), a.rotation)
        val bb = ImageOps.rotate(ImageOps.decode(mws.file(b)), b.rotation)
        val joined = if (mws.info.manga) ImageOps.join(bb, ba) else ImageOps.join(ba, bb)
        val (fmt, q) = keepFormat(a)
        val name = mws.newImageName(fmt.ext)
        File(workDir, name).writeBytes(ImageOps.encode(joined, fmt, q))
        listOf(ba, bb, joined).forEach { it.recycle() }
        val list = mws.pages.toMutableList()
        list[first] = MangaPage(name)
        list.removeAt(second)
        mws.savePages(list)
    }

    // ---------------------------------------------------------------- manga translation (2.0.0)

    private var typesetIds = 0L

    /** Messages and errors from screens that run their own work (the lettering screen). */
    fun notify(@StringRes res: Int) = message(res)

    fun fail(error: Throwable) {
        _events.trySend(EditorEvent.Error(error))
    }

    /** Size in pixels of a page as shown (after its rotation). */
    suspend fun pageSize(index: Int): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val page = mws.pages[index]
        val (w, h) = ImageOps.bounds(mws.file(page))
        if (page.rotation % 180 != 0) h to w else w to h
    }

    private fun decodeShown(page: MangaPage, maxSide: Int = 0): android.graphics.Bitmap {
        val src = ImageOps.decode(mws.file(page), maxSide)
        return ImageOps.rotate(src, page.rotation).also { if (it !== src) src.recycle() }
    }

    private suspend fun translateText(text: String, lang: String): String =
        runCatchingCancellable { translation.translate(text, lang, settings.targetLang.get().ifBlank { null }).translated }.getOrDefault(text)

    /**
     * Recognizes the text of a page and machine-translates it, in the OCR language of the settings. [region] (fractions of
     * the page) limits it to a frame drawn by the user: its lines become one block, or an empty block for lettering by hand
     * when nothing is found.
     */
    suspend fun recognizeBlocks(index: Int, region: android.graphics.RectF?): List<TypesetBlock> = withContext(Dispatchers.IO) {
        val bmp = decodeShown(mws.pages[index])
        try {
            val w = bmp.width
            val h = bmp.height
            val lang = settings.ocrLanguage.get()
            if (region == null) {
                ocr.recognize(bmp, lang).map { b ->
                    TypesetBlock(++typesetIds, b.left / w.toFloat(), b.top / h.toFloat(), b.right / w.toFloat(), b.bottom / h.toFloat(), b.text, translateText(b.text, lang))
                }
            } else {
                val l = (region.left * w).toInt().coerceIn(0, w - 1)
                val t = (region.top * h).toInt().coerceIn(0, h - 1)
                val r = (region.right * w).toInt().coerceIn(l + 1, w)
                val b = (region.bottom * h).toInt().coerceIn(t + 1, h)
                val part = android.graphics.Bitmap.createBitmap(bmp, l, t, r - l, b - t)
                val found = try { ocr.recognize(part, lang) } finally { if (part !== bmp) part.recycle() }
                if (found.isEmpty()) {
                    listOf(TypesetBlock(++typesetIds, region.left, region.top, region.right, region.bottom, "", ""))
                } else {
                    val joiner = if (lang == "ja" || lang == "zh") "" else " "
                    val text = found.sortedWith(compareBy({ it.top }, { it.left })).joinToString(joiner) { it.text }
                    listOf(
                        TypesetBlock(
                            ++typesetIds,
                            (l + found.minOf { it.left }) / w.toFloat(), (t + found.minOf { it.top }) / h.toFloat(),
                            (l + found.maxOf { it.right }) / w.toFloat(), (t + found.maxOf { it.bottom }) / h.toFloat(),
                            text, translateText(text, lang),
                        ),
                    )
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    suspend fun retranslate(original: String): String = withContext(Dispatchers.IO) { translateText(original, settings.ocrLanguage.get()) }

    /** The page as shown (downscaled), with [blocks] lettered onto it when there are any. */
    suspend fun typesetPreview(index: Int, blocks: List<TypesetBlock>): android.graphics.Bitmap = withContext(Dispatchers.Default) {
        val bmp = decodeShown(mws.pages[index], maxSide = 1600)
        if (blocks.isEmpty()) bmp else Typesetter.apply(bmp, blocks)
    }

    /** Letters [blocks] onto the page, as one undoable step. */
    fun applyTypeset(index: Int, blocks: List<TypesetBlock>) = edit(R.string.editor_step_typeset, { pagesFile }) {
        transformPages(setOf(index), ::keepFormat) { bmp, _ -> Typesetter.apply(bmp, blocks) }
    }

    /** Recognizes, translates and letters the pages [indices] without asking, as one undoable step. */
    fun translatePages(indices: Set<Int>) = viewModelScope.launch(ScreenErrors) {
        if (indices.isEmpty()) return@launch
        _status.update { it.copy(busy = true, progress = 0f) }
        val result = runCatchingCancellable {
            val sorted = indices.sorted()
            val out = HashMap<Int, List<TypesetBlock>>()
            sorted.forEachIndexed { n, i ->
                out[i] = recognizeBlocks(i, null)
                _status.update { it.copy(progress = (n + 1f) / sorted.size) }
            }
            out
        }
        _status.update { it.copy(busy = false, progress = null) }
        result.onSuccess { byPage ->
            val pages = byPage.filterValues { it.isNotEmpty() }
            val count = pages.values.sumOf { it.size }
            if (count == 0) {
                message(R.string.editor_typeset_nothing)
                return@onSuccess
            }
            edit(R.string.editor_step_typeset, { pagesFile }, onDone = { _: Unit -> message(R.string.editor_typeset_done, pages.size, count) }) {
                transformPages(pages.keys, ::keepFormat) { bmp, i -> Typesetter.apply(bmp, pages[i].orEmpty()) }
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; _events.send(EditorEvent.Error(it)) }
    }

    // ---------------------------------------------------------------- output

    /** Builds the edited book into [out] and checks that it can be opened again. */
    private fun buildOutput(out: File, progress: (Float) -> Unit = {}) {
        out.parentFile?.mkdirs()
        if (kind == EditorKind.TEXT) {
            EpubBuilder.zipDirectory(workDir, out)
            ZipContainer.open(out).use { c ->
                val pkg = EpubParser.parse(c)
                check(pkg.spine.isNotEmpty()) { "Empty spine" }
            }
        } else {
            out.outputStream().use { stream -> mws.exportCbz(stream, { p -> ImageOps.rendered(mws.file(p), p.rotation) }, progress) }
            ZipContainer.open(out).use { c -> check(Containers.imageEntries(c).size == mws.pages.size) { "Page count mismatch" } }
        }
    }

    private fun outputExt() = if (kind == EditorKind.TEXT) "epub" else "cbz"

    /**
     * Replaces the library book with the edited version. The output is built first; the write-back itself runs in the
     * app scope, so leaving the editor (or clearing the ViewModel) cannot cut it off halfway.
     */
    fun saveToBook(thenClose: Boolean = false) = viewModelScope.launch(ScreenErrors) {
        mutex.withLock {
            _status.update { it.copy(busy = true, progress = null) }
            val result = runCatchingCancellable {
                val tmp = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "editor/out_$bookId.${outputExt()}").also { buildOutput(it, ::progress) }
                }
                appScope.detached {
                    withContext(NonCancellable + Dispatchers.IO) {
                        writeBack(tmp)
                        setDirty(false)
                    }
                }
            }
            _status.update { it.copy(busy = false, progress = null) }
            result.onSuccess {
                _book.value = books.getBook(bookId)
                message(R.string.editor_saved)
                if (thenClose) {
                    withContext(Dispatchers.IO) { draftDir.deleteRecursively() }
                    _events.send(EditorEvent.Close)
                }
            }.onFailure { e ->
                _events.send(EditorEvent.Error(if (e is SecurityException) AppError.NoPermission(e.message) else e))
            }
        }
    }

    /** Publishes the finished [tmp] output; it is kept when something fails so the result is not lost. */
    private suspend fun writeBack(tmp: File) {
        val book = books.getBook(bookId) ?: throw AppError.CorruptedFile("Book $bookId not found")
        val ext = outputExt()
        val targetFormat = if (kind == EditorKind.TEXT) BookFormat.EPUB else BookFormat.CBZ
        val uri = Uri.parse(book.uri)
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        if (book.format == targetFormat) {
            if (uri.scheme == "file" || uri.scheme == null) {
                val dest = File(uri.path!!)
                val swap = File(dest.parentFile, dest.name + ".new")
                tmp.copyTo(swap, overwrite = true)
                if (!swap.renameTo(dest)) {
                    swap.copyTo(dest, overwrite = true)
                    swap.delete()
                }
                books.contentChanged(bookId, dest)
            } else {
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw AppError.NoPermission(book.uri)
                stream.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                books.contentChanged(bookId, null)
            }
        } else {
            var dest = File(booksDir, Files.sanitize(book.title) + ".$ext")
            if (dest.exists()) dest = File(booksDir, Files.sanitize(book.title) + " ${System.currentTimeMillis()}.$ext")
            tmp.copyTo(dest)
            books.replaceFile(bookId, dest, targetFormat)
            if (uri.scheme == "file") File(uri.path!!).takeIf { it.canonicalPath.startsWith(booksDir.canonicalPath) }?.delete()
        }
        tmp.delete()
        updateLibraryEntry(book)
    }

    private suspend fun updateLibraryEntry(book: Book) {
        val fresh = books.getBook(bookId) ?: book
        if (kind == EditorKind.TEXT) {
            val m = ws.meta
            books.update(
                fresh.copy(
                    title = m.title.ifBlank { fresh.title },
                    authors = m.authors.ifEmpty { fresh.authors },
                    series = m.series,
                    seriesIndex = m.seriesIndex,
                    language = m.language?.takeIf { it != "und" },
                    description = m.description,
                    publisher = m.publisher,
                ),
            )
            ws.pkg.coverPath?.let { p -> runCatchingCancellable { books.setCover(bookId, File(workDir, p).readBytes()) } }
        } else {
            val info = mws.info
            books.update(
                fresh.copy(
                    title = info.title.ifBlank { fresh.title },
                    series = info.series.ifBlank { null },
                    seriesIndex = info.number.toFloatOrNull(),
                    authors = info.writer.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { fresh.authors },
                    language = info.languageIso.ifBlank { null },
                    description = info.summary.ifBlank { null },
                ),
            )
            mws.pages.firstOrNull()?.let { p -> runCatchingCancellable { books.setCover(bookId, ImageOps.rendered(mws.file(p), p.rotation).first) } }
        }
    }

    /** Exports to a document picked with the system file picker. */
    fun export(type: ExportType, target: Uri) = viewModelScope.launch(ScreenErrors) {
        mutex.withLock {
            _status.update { it.copy(busy = true, progress = null) }
            val result = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    val tmp = File(context.cacheDir, "editor/export_$bookId.${type.ext}")
                    tmp.parentFile?.mkdirs()
                    when (type) {
                        ExportType.EPUB -> if (kind == EditorKind.TEXT) buildOutput(tmp) else tmp.outputStream().use { EditorExports.mangaEpub(mws, _book.value?.title.orEmpty(), it, ::progress) }
                        ExportType.CBZ -> buildOutput(tmp, ::progress)
                        ExportType.TXT -> tmp.writeText(ws.plainText())
                        ExportType.PDF -> tmp.outputStream().use {
                            if (kind == EditorKind.TEXT) EditorExports.textPdf(ws.meta.title.ifBlank { _book.value?.title.orEmpty() }, ws.plainTextChapters(), it) else EditorExports.mangaPdf(mws, it, ::progress)
                        }
                    }
                    val stream = context.contentResolver.openOutputStream(target, "wt") ?: throw AppError.NoPermission(target.toString())
                    stream.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                    tmp.delete()
                }
            }
            _status.update { it.copy(busy = false, progress = null) }
            result.onSuccess { message(R.string.editor_exported) }.onFailure { _events.send(EditorEvent.Error(it)) }
        }
    }

    fun suggestedName(type: ExportType): String = Files.sanitize(_book.value?.title ?: "book") + "." + type.ext

    /** Adds the edited version to the library as a separate book. */
    fun saveAsNewBook() = viewModelScope.launch(ScreenErrors) {
        mutex.withLock {
            _status.update { it.copy(busy = true, progress = null) }
            val result = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    val ext = outputExt()
                    val dir = File(context.filesDir, "books").apply { mkdirs() }
                    val base = Files.sanitize((_book.value?.title ?: "book") + " (edited)")
                    var dest = File(dir, "$base.$ext")
                    if (dest.exists()) dest = File(dir, "$base ${System.currentTimeMillis()}.$ext")
                    buildOutput(dest, ::progress)
                    val outcome = importer.importLocalFile(dest, dest.name)
                    if (outcome !is ImportOutcome.Added) dest.delete()
                    outcome
                }
            }
            _status.update { it.copy(busy = false, progress = null) }
            result.onSuccess { outcome ->
                when (outcome) {
                    is ImportOutcome.Added -> {
                        setDirty(false)
                        message(R.string.editor_added_copy)
                    }
                    is ImportOutcome.Duplicate -> message(R.string.editor_duplicate)
                    is ImportOutcome.Failed -> _events.send(EditorEvent.Error(outcome.error))
                }
            }.onFailure { _events.send(EditorEvent.Error(it)) }
        }
    }

    suspend fun defaultTargetLanguage(): String = settings.targetLang.get().ifBlank { java.util.Locale.getDefault().language }

    fun translate(target: String, bilingual: Boolean) {
        BookTranslationWorker.start(context, bookId, target.trim(), bilingual, null)
        message(R.string.editor_translation_started)
    }

    /** Translates the chosen chapters of the draft in place, as one undoable step (2.0.0). */
    fun translateChapters(paths: List<String>, target: String, bilingual: Boolean) = viewModelScope.launch(ScreenErrors) {
        if (paths.isEmpty()) return@launch
        _status.update { it.copy(busy = true, progress = 0f) }
        val result = runCatchingCancellable {
            val out = LinkedHashMap<String, String>()
            paths.forEachIndexed { i, path ->
                val doc = withContext(Dispatchers.IO) { EpubParser.parseXml(ws.readText(path).toByteArray()) }
                bookTranslator.translateDocument(doc, target.trim(), bilingual, null)
                out[path] = doc.outerHtml()
                _status.update { it.copy(progress = (i + 1f) / paths.size) }
            }
            out
        }
        _status.update { it.copy(busy = false, progress = null) }
        result.onSuccess { texts ->
            edit(R.string.editor_step_translate, { texts.keys }, onDone = { _: Unit -> message(R.string.editor_translated_chapters, texts.size) }) {
                texts.forEach { (path, text) -> ws.writeText(path, text) }
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; _events.send(EditorEvent.Error(it)) }
    }
}
