package app.yomu.feature.reader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.AppError
import app.yomu.core.common.Files
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.Share
import app.yomu.core.common.UiState
import app.yomu.core.ui.components.ErrorState
import app.yomu.core.ui.components.RequireWebView
import app.yomu.data.book.Containers
import app.yomu.data.book.docx.DocxLayout
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.domain.model.BookFormat
import app.yomu.domain.repository.StatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import app.yomu.core.ui.R as CoreR

/**
 * Print layout of a Word document: the pages as Word prints them (page size, margins, headers and footers, footnotes, tables), rendered
 * by [DocxLayout] and paginated in a WebView by assets/docx/paginate.js. The reflowable reader stays the main way to read; this view is
 * for documents where the layout matters (forms, contracts, tables).
 */
class DocxLayoutViewModel(
    val bookId: Long,
    private val context: Context,
    private val books: BookRepositoryImpl,
    private val stats: StatsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<File>>(UiState.Loading)
    val state: StateFlow<UiState<File>> = _state.asStateFlow()
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /** Current page (1-based) and the number of pages laid out so far. */
    private val _page = MutableStateFlow(0)
    val page: StateFlow<Int> = _page.asStateFlow()
    private val _pages = MutableStateFlow(0)
    val pages: StateFlow<Int> = _pages.asStateFlow()
    private val _laidOut = MutableStateFlow(false)
    val laidOut: StateFlow<Boolean> = _laidOut.asStateFlow()

    private val prefs = context.getSharedPreferences("docx_layout", Context.MODE_PRIVATE)
    private var sessionStart = 0L
    private var lastActivity = 0L
    private var sessionPages = 0

    init {
        load()
    }

    /** Page to show when the WebView is (re)created: the current one, or the one saved last time. */
    fun startPage(): Int = _page.value.takeIf { it > 0 } ?: prefs.getInt(key(), 1)

    fun load() {
        _state.value = UiState.Loading
        _laidOut.value = false
        viewModelScope.launch(ScreenErrors) {
            _state.value = runCatching { prepare() }.fold(
                onSuccess = { UiState.Data(it) },
                onFailure = { e -> Timber.w(e, "Print layout failed"); UiState.Error(AppError.from(e)) },
            )
        }
    }

    fun canRelink(error: Throwable): Boolean = error is AppError.FileMissing || error is AppError.NoPermission

    fun relink(uri: Uri) {
        _state.value = UiState.Loading
        viewModelScope.launch(ScreenErrors) {
            runCatching { books.relink(bookId, uri) }
            load()
        }
    }

    private suspend fun prepare(): File = withContext(Dispatchers.IO) {
        val book = books.getBook(bookId) ?: throw AppError.FileMissing(null)
        _title.value = book.title
        if (book.format != BookFormat.DOCX) throw AppError.UnsupportedFormat(book.format.name)
        val uri = Uri.parse(book.uri)
        val root = File(context.cacheDir, "docx-layout")
        val dir = File(root, bookId.toString())
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val stamp = "${book.uri}|${Files.size(context, uri)}|$installed"
        val stampFile = File(dir, ".stamp")
        if (File(dir, INDEX).exists() && stampFile.exists() && stampFile.readText() == stamp) {
            dir.setLastModified(System.currentTimeMillis())
            return@withContext dir
        }
        // Only the last few documents stay cached.
        root.listFiles()?.filter { it != dir }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.deleteRecursively() }
        dir.deleteRecursively()
        dir.mkdirs()
        val media = File(dir, DocxLayout.MEDIA_DIR)
        val result = Containers.open(context, uri, BookFormat.CBZ).use { container ->
            DocxLayout.render(container) { name, bytes ->
                File(media, name).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            }
        }
        File(dir, INDEX).writeText(result.html)
        stampFile.writeText(stamp)
        dir
    }

    // ------------------------------------------------------------------ bridge (called on the WebView's JavaBridge thread)

    internal fun onProgress(pages: Int) {
        if (!_laidOut.value) _pages.value = pages
    }

    internal fun onReady(pages: Int) {
        _pages.value = pages
        _laidOut.value = true
    }

    internal fun onPage(index: Int) {
        if (index == _page.value) return
        if (_page.value > 0) {
            sessionPages++
            lastActivity = System.currentTimeMillis()
        }
        _page.value = index
        prefs.edit().putInt(key(), index).apply()
    }

    // ------------------------------------------------------------------ statistics

    fun onResume() {
        sessionStart = System.currentTimeMillis()
        lastActivity = sessionStart
        sessionPages = 0
    }

    fun onPause() {
        val start = sessionStart
        if (start == 0L) return
        sessionStart = 0L
        val end = minOf(System.currentTimeMillis(), lastActivity + IDLE_MS)
        val duration = end - start
        val pages = sessionPages
        if (duration < 5_000) return
        viewModelScope.launch(ScreenErrors) { withContext(NonCancellable) { runCatching { stats.recordSession(bookId, start, duration, pages, 0, null) } } }
    }

    private fun key() = "page_$bookId"

    companion object {
        internal const val HOST = "docx.yomu"
        internal const val INDEX = "index.html"
        private const val IDLE_MS = 5 * 60_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocxLayoutScreen(viewModel: DocxLayoutViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val laidOut by viewModel.laidOut.collectAsStateWithLifecycle()
    var web by remember { mutableStateOf<WebView?>(null) }
    var goTo by rememberSaveable { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPause() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.layout_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) }
                },
                actions = {
                    if (state is UiState.Data && pages > 0) {
                        IconButton(onClick = { goTo = true }) { Icon(Icons.Outlined.FindInPage, stringResource(R.string.reader_go_to_page)) }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading, UiState.Empty -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.layout_preparing), style = MaterialTheme.typography.bodyMedium)
                }
                is UiState.Error -> {
                    val relink = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    ) { uri -> if (uri != null) viewModel.relink(uri) }
                    if (viewModel.canRelink(s.error)) {
                        ErrorState(
                            s.error,
                            onRetry = { relink.launch(arrayOf("*/*")) },
                            retryLabel = stringResource(R.string.reader_relink),
                            secondaryLabel = stringResource(CoreR.string.back),
                            onSecondary = onBack,
                        )
                    } else {
                        ErrorState(s.error, onRetry = viewModel::load, secondaryLabel = stringResource(CoreR.string.back), onSecondary = onBack)
                    }
                }
                is UiState.Data -> {
                    val dir = s.value
                    val context = LocalContext.current
                    RequireWebView {
                        AndroidView(
                            factory = { ctx ->
                                layoutWebView(ctx, dir, viewModel, background) { url -> Share.openUrl(context, url) }.also { view ->
                                    web = view
                                    val query = if (dark) "?dark=1" else ""
                                    view.loadUrl("https://${DocxLayoutViewModel.HOST}/${DocxLayoutViewModel.INDEX}$query#p=${viewModel.startPage()}")
                                }
                            },
                            onRelease = { view ->
                                if (web === view) web = null
                                view.destroy()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (!laidOut) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                    if (pages > 0) {
                        PageChip(
                            page = page.coerceIn(1, pages),
                            pages = pages,
                            approximate = !laidOut,
                            onClick = { goTo = true },
                            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }

    if (goTo && pages > 0) {
        GoToPageDialog(
            page = page.coerceIn(1, pages),
            pages = pages,
            approximate = false,
            percent = if (pages > 1) ((page - 1) * 100 / (pages - 1)).coerceIn(0, 100) else 0,
            onPage = { n -> goTo = false; web?.evaluateJavascript("window.yomuLayout&&yomuLayout.goTo($n)", null) },
            onPercent = { f ->
                goTo = false
                val n = (1 + Math.round(f * (pages - 1))).coerceIn(1, pages)
                web?.evaluateJavascript("window.yomuLayout&&yomuLayout.goTo($n)", null)
            },
            onDismiss = { goTo = false },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun layoutWebView(context: Context, dir: File, viewModel: DocxLayoutViewModel, background: Int, onExternal: (String) -> Unit): WebView =
    WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            mediaPlaybackRequiresUserGesture = true
        }
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(background)
        addJavascriptInterface(LayoutBridge(viewModel), "YomuLayout")
        webViewClient = LayoutClient(context, dir, onExternal)
    }

private class LayoutBridge(private val viewModel: DocxLayoutViewModel) {
    @JavascriptInterface
    fun onProgress(pages: Int) = viewModel.onProgress(pages)

    @JavascriptInterface
    fun onReady(pages: Int) = viewModel.onReady(pages)

    @JavascriptInterface
    fun onPage(index: Int, label: String?) = viewModel.onPage(index)
}

/** Serves the rendered document from the cache and the layout script, styles and fonts from the assets. */
private class LayoutClient(private val context: Context, private val dir: File, private val onExternal: (String) -> Unit) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != DocxLayoutViewModel.HOST) return notFound()
        val path = URLDecoder.decode(url.encodedPath.orEmpty().removePrefix("/"), "UTF-8")
        return try {
            when {
                path == DocxLayoutViewModel.INDEX -> WebResourceResponse("text/html", "UTF-8", FileInputStream(File(dir, path)))
                path.startsWith(DocxLayout.MEDIA_DIR + "/") -> {
                    val file = File(dir, path).canonicalFile
                    if (file.path.startsWith(dir.canonicalPath + File.separator) && file.isFile) {
                        WebResourceResponse(mime(path), null, FileInputStream(file))
                    } else {
                        notFound()
                    }
                }
                path == "layout.css" || path == "paginate.js" || (path.startsWith("fonts/") && !path.contains("..")) ->
                    WebResourceResponse(mime(path), if (path.endsWith(".ttf")) null else "UTF-8", context.assets.open("docx/$path"))
                else -> notFound()
            }
        } catch (e: Exception) {
            Timber.w(e, "Cannot serve $path")
            notFound()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.host != DocxLayoutViewModel.HOST) {
            val scheme = url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel") onExternal(url.toString())
        }
        return true
    }

    private fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))

    private fun mime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "css" -> "text/css"
        "js" -> "application/javascript"
        "html" -> "text/html"
        "ttf" -> "font/ttf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "tif", "tiff" -> "image/tiff"
        else -> "application/octet-stream"
    }
}
