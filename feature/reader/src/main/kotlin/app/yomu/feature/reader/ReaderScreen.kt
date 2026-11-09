package app.yomu.feature.reader

import android.provider.Settings
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.Share
import app.yomu.core.common.UiState
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.ErrorState
import app.yomu.core.ui.components.KeyBus
import app.yomu.core.ui.components.OneTimeHint
import app.yomu.core.ui.components.SkeletonBox
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.components.rememberHaptic
import app.yomu.domain.model.BookFormat
import app.yomu.domain.model.CustomAction
import app.yomu.domain.model.HighlightColor
import app.yomu.domain.model.HighlightStyle
import app.yomu.domain.model.Locator
import app.yomu.domain.model.MangaDirection
import app.yomu.domain.model.TapZoneLayout
import app.yomu.domain.model.SelectionAction
import app.yomu.domain.model.WebSearchEngine
import app.yomu.feature.annotations.NoteDialog
import app.yomu.feature.annotations.QuoteImage
import app.yomu.feature.annotations.SelectionMenu
import app.yomu.feature.annotations.icon
import app.yomu.feature.annotations.label
import app.yomu.core.ui.components.QuickActionMenu
import app.yomu.core.ui.components.QuickMenuItem
import app.yomu.domain.model.QuickActions
import app.yomu.domain.model.QuickMenuStyle
import app.yomu.feature.annotations.copyToClipboard
import app.yomu.feature.dictionary.LookupRequest
import app.yomu.feature.dictionary.WordLookupViewModel
import app.yomu.feature.dictionary.WordPopup
import app.yomu.feature.reader.epub.EpubReaderView
import app.yomu.feature.reader.manga.MangaReaderView
import app.yomu.feature.reader.pdf.PdfReaderView
import app.yomu.feature.translator.Speaker
import app.yomu.feature.translator.TranslationSheet
import app.yomu.feature.translator.TranslationViewModel
import kotlinx.coroutines.delay
import java.io.File
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import java.net.URLEncoder
import java.util.Locale
import app.yomu.core.ui.R as CoreR
import app.yomu.core.ui.theme.Dimens

internal enum class ReaderSheet { NONE, TOC, SEARCH, SETTINGS, TTS }

/**
 * The reader: hosts one of the three engines and the shared chrome (top/bottom bars that auto-hide),
 * selection toolbar, dictionary popup, translation sheet, TOC / search / TTS / settings sheets.
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenAnnotations: (Long) -> Unit,
    onOpenDictionary: (String) -> Unit,
    onOpenPacks: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenEditor: ((Long) -> Unit)? = null,
    onOpenPageLayout: ((Long) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    val density = LocalDensity.current.density
    SideEffect { viewModel.density = density }

    val content by viewModel.content.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val toc by viewModel.toc.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val chrome by viewModel.chromeVisible.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val wordTap by viewModel.wordTap.collectAsStateWithLifecycle()
    val footnote by viewModel.footnote.collectAsStateWithLifecycle()
    val image by viewModel.image.collectAsStateWithLifecycle()
    val translateText by viewModel.translateText.collectAsStateWithLifecycle()
    val passwordNeeded by viewModel.passwordNeeded.collectAsStateWithLifecycle()
    val common by viewModel.common.collectAsStateWithLifecycle()
    val textSettings by viewModel.textSettings.collectAsStateWithLifecycle()
    val readerText by viewModel.effectiveText.collectAsStateWithLifecycle()
    val imageCanvas by viewModel.imageCanvas.collectAsStateWithLifecycle()
    val mangaSettings by viewModel.mangaSettings.collectAsStateWithLifecycle()
    val pdfSettings by viewModel.pdfSettings.collectAsStateWithLifecycle()
    val perBook by viewModel.perBook.collectAsStateWithLifecycle()
    val wordTapSetting by viewModel.wordTapSetting.collectAsStateWithLifecycle()
    val actions by viewModel.selectionActions.collectAsStateWithLifecycle()
    val customActions by viewModel.customActions.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val hasPrev by viewModel.hasPrevChapter.collectAsStateWithLifecycle()
    val hasNext by viewModel.hasNextChapter.collectAsStateWithLifecycle()
    val ttsActive by viewModel.ttsActive.collectAsStateWithLifecycle()
    val ttsPlaying by viewModel.tts.playing.collectAsStateWithLifecycle()
    val autoScroll by viewModel.autoScroll.collectAsStateWithLifecycle()
    val bilingual by viewModel.bilingualProgress.collectAsStateWithLifecycle()
    val ocrRunning by viewModel.ocrRunning.collectAsStateWithLifecycle()
    val webSearch by viewModel.settings.webSearch.flow.collectAsStateWithLifecycle(WebSearchEngine.GOOGLE)
    val customSearchUrl by viewModel.settings.customSearchUrl.flow.collectAsStateWithLifecycle("")
    val cpm by produceState(1000f) { value = viewModel.readingSpeed() }
    val jumpBack by viewModel.jumpBack.collectAsStateWithLifecycle()
    val selectionMenuStyle by viewModel.selectionMenuStyle.collectAsStateWithLifecycle()
    val pageMenu by viewModel.pageMenu.collectAsStateWithLifecycle()
    val pageMenuStyle by viewModel.pageMenuStyle.collectAsStateWithLifecycle()
    val pageActions by viewModel.pageActions.collectAsStateWithLifecycle()
    var savePageAs by remember { mutableStateOf<ReaderEvent.SavePageAs?>(null) }
    val savePageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = savePageAs
        savePageAs = null
        if (uri != null && pending != null) viewModel.savePageTo(pending.file, uri)
    }

    var sheet by rememberSaveable { mutableStateOf(ReaderSheet.NONE) }
    var noteFor by remember { mutableStateOf<ReaderSelection?>(null) }
    var pageNote by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf<LookupRequest?>(null) }
    var lookupAnchor by remember { mutableStateOf<Rect?>(null) }
    var searchSeed by rememberSaveable { mutableStateOf("") }
    var showFinished by rememberSaveable { mutableStateOf(false) }
    var seekDrag by remember { mutableStateOf<Float?>(null) }
    var quickPanel by rememberSaveable { mutableStateOf(false) }
    var tapZonesDone by rememberSaveable { mutableStateOf(false) }
    var goToPage by rememberSaveable { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val snackbar = remember { SnackbarHostState() }
    val speaker: Speaker = koinInject()
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val data = (content as? UiState.Data)?.value
    val bookLang = viewModel.book?.language
    val author = viewModel.book?.authors?.joinToString()

    // One haptic tick per chapter turn, whether by page turn, buttons or TTS. Skips the initial open.
    val chapterId by viewModel.chapterId.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        var lastChapter = -1
        var lastId = -1L
        var baselineAt = 0L
        snapshotFlow { Triple(content is UiState.Data, position.chapter, chapterId) }.collect { (ready, ch, id) ->
            if (!ready) { lastChapter = -1; baselineAt = 0L; return@collect }
            val now = System.currentTimeMillis()
            if (baselineAt == 0L) baselineAt = now
            val settled = now - baselineAt > 1_500
            val turned = if (lastId != -1L) id != lastId else lastChapter != -1 && settled && kotlin.math.abs(ch - lastChapter) == 1
            if (turned) haptic.tick()
            if (lastId == -1L || id != lastId) baselineAt = now
            lastChapter = ch
            lastId = id
        }
    }

    val appDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    LaunchedEffect(appDark) { viewModel.setAppDark(appDark) }

    // Live value of an edge swipe; written to the settings once, when the finger lifts.
    var edge by remember { mutableStateOf<EdgeAdjust?>(null) }
    var edgeShown by remember { mutableStateOf(false) }
    var edgeHold by remember { mutableStateOf<EdgeAdjust?>(null) }
    val liveCommon = edgeHold?.let { if (it.brightness) common.copy(brightness = it.value) else common.copy(warmFilter = it.value) } ?: common
    val context0 = LocalContext.current
    LaunchedEffect(edgeHold) { if (edgeShown && edgeHold == null) { delay(700); edgeShown = false } }

    ReaderWindowEffects(liveCommon, chrome || sheet != ReaderSheet.NONE, lastInteraction)

    // Panels hide by themselves after a few seconds of inactivity: reading comes first.
    LaunchedEffect(chrome, lastInteraction, sheet, quickPanel) {
        if (chrome && sheet == ReaderSheet.NONE && !quickPanel && common.autoHideMenuSeconds > 0) {
            delay(common.autoHideMenuSeconds * 1000L)
            viewModel.chromeVisible.value = false
        }
    }
    LaunchedEffect(chrome) { if (!chrome) quickPanel = false }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    // Manga have their own volume key switch (on by default) and may turn forward with volume up.
    val isManga = data is ReaderContent.Manga
    val volumeKeys = if (isManga) mangaSettings.volumeKeys else common.volumeKeys
    val volumeForward = isManga && mangaSettings.invertVolumeKeys
    DisposableEffect(volumeKeys, volumeForward) {
        KeyBus.volumeHandler = if (volumeKeys) { up -> if (up != volumeForward) viewModel.prevPage() else viewModel.nextPage(); true } else null
        onDispose { KeyBus.volumeHandler = null }
    }
    LaunchedEffect(wordTap) {
        val w = wordTap ?: return@LaunchedEffect
        lookup = LookupRequest(w.word, w.sentence, bookLang, viewModel.bookId.takeIf { it > 0 }, title, w.locator)
        lookupAnchor = w.anchor
        viewModel.wordTap.value = null
    }
    // Word documents: offer the print layout once per document.
    val pageLayoutBook = viewModel.book?.takeIf { data != null && onOpenPageLayout != null && it.format == BookFormat.DOCX && it.id > 0 }
    LaunchedEffect(pageLayoutBook?.id) {
        val b = pageLayoutBook ?: return@LaunchedEffect
        if (!viewModel.takePageLayoutHint(b.id)) return@LaunchedEffect
        delay(800)
        val r = snackbar.showSnackbar(
            resources.getString(R.string.reader_page_layout_hint),
            resources.getString(R.string.reader_page_layout_open),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (r == SnackbarResult.ActionPerformed) onOpenPageLayout?.invoke(b.id)
    }
    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { e ->
            suspend fun undoable(message: String, onUndo: () -> Unit) {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar(message, resources.getString(CoreR.string.undo), withDismissAction = true, duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) onUndo()
            }
            when (e) {
                is ReaderEvent.BookmarkAdded -> { haptic.perform(); launch { undoable(resources.getString(R.string.reader_bookmark_added)) { viewModel.undoBookmark(e) } } }
                is ReaderEvent.BookmarkRemoved -> launch { undoable(resources.getString(R.string.reader_bookmark_removed)) { viewModel.undoBookmark(e) } }
                is ReaderEvent.HighlightDeleted -> launch { undoable(resources.getString(R.string.reader_highlight_deleted)) { viewModel.restoreHighlight(e.highlight) } }
                is ReaderEvent.WordSaved -> { haptic.perform(); launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(resources.getString(R.string.reader_word_saved, e.term)) } }
                is ReaderEvent.Message -> launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(resources.getString(e.res)) }
                is ReaderEvent.Error -> launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(errorMessage(context, e.error)) }
                is ReaderEvent.OpenUrl -> Share.openUrl(context, e.url)
                ReaderEvent.Finished -> showFinished = true
                ReaderEvent.PageNote -> pageNote = true
                is ReaderEvent.SavePageAs -> {
                    savePageAs = e
                    runCatching { savePageLauncher.launch(e.name) }
                }
            }
        }
    }

    fun webSearchUrl(text: String): String {
        val template = if (webSearch == WebSearchEngine.CUSTOM) customSearchUrl.ifBlank { WebSearchEngine.GOOGLE.template } else webSearch.template
        val lang = bookLang?.take(2) ?: Locale.getDefault().language
        return template.replace("{text}", URLEncoder.encode(text, "UTF-8")).replace("{lang}", lang)
    }

    fun runCustom(action: CustomAction, text: String) {
        if (action.packageName.isNotBlank()) {
            val process = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage(action.packageName)
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            val ok = runCatching { context.startActivity(process.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (!ok) runCatching {
                context.startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(action.packageName).putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { scope.launch { snackbar.showSnackbar(resources.getString(R.string.reader_app_not_found)) } }
        } else if (action.urlTemplate.isNotBlank()) {
            Share.openUrl(context, action.urlTemplate.replace("{text}", URLEncoder.encode(text, "UTF-8")))
        }
        viewModel.clearSelection()
    }

    fun onAction(action: SelectionAction, sel: ReaderSelection) {
        when (action) {
            SelectionAction.HIGHLIGHT, SelectionAction.UNDERLINE, SelectionAction.STRIKE, SelectionAction.CUSTOM -> Unit
            SelectionAction.NOTE -> noteFor = sel
            SelectionAction.QUOTE -> { haptic.perform(); viewModel.highlight(HighlightColor.YELLOW, HighlightStyle.HIGHLIGHT, quote = true) }
            SelectionAction.MEANING -> {
                lookup = LookupRequest(sel.text, sel.sentence.ifBlank { null }, bookLang, viewModel.bookId.takeIf { it > 0 }, title,
                    Locator(chapter = sel.chapter, offset = sel.start, endOffset = sel.end, text = sel.text))
                lookupAnchor = sel.anchor
                viewModel.clearSelection()
            }
            SelectionAction.TRANSLATE -> { viewModel.translateText.value = sel.text; viewModel.clearSelection() }
            SelectionAction.SAVE_WORD -> viewModel.saveSelectionAsWord()
            SelectionAction.COPY -> {
                copyToClipboard(context, sel.text)
                viewModel.clearSelection()
            }
            SelectionAction.SHARE -> {
                val quote = buildString {
                    append('“').append(sel.text.trim()).append('”')
                    append("\n— ").append(listOfNotNull(author?.ifBlank { null }, title.ifBlank { null }).joinToString(", "))
                }
                Share.shareText(context, quote, resources.getString(CoreR.string.share))
                viewModel.clearSelection()
            }
            SelectionAction.SHARE_IMAGE -> {
                val dark = Color(readerText.background).luminance() < 0.5f
                scope.launch { runCatching { QuoteImage.share(context, sel.text.trim(), title, author, dark, resources.getString(CoreR.string.share)) } }
                viewModel.clearSelection()
            }
            SelectionAction.SEARCH_BOOK -> {
                searchSeed = sel.text.trim().take(100)
                viewModel.search(searchSeed)
                sheet = ReaderSheet.SEARCH
                viewModel.clearSelection()
            }
            SelectionAction.SEARCH_WEB -> {
                Share.openUrl(context, webSearchUrl(sel.text.trim()))
                viewModel.clearSelection()
            }
            SelectionAction.SPEAK -> speaker.speak(sel.text, bookLang)
            SelectionAction.SELECT_SENTENCE -> viewModel.expandSelection(paragraph = false)
            SelectionAction.SELECT_PARAGRAPH -> viewModel.expandSelection(paragraph = true)
            SelectionAction.BOOKMARK -> { viewModel.toggleBookmark(); viewModel.clearSelection() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Loading, errors and "no text" use the page colours too: no black flash before a light page, readable messages.
            .background(if (data is ReaderContent.Text || (data == null && !imageCanvas)) Color(readerText.background) else Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastInteraction = System.currentTimeMillis()
                    }
                }
            }
            .edgeAdjust(
                enabled = common.edgeGestures && (data is ReaderContent.Text || data is ReaderContent.Pdf) && !chrome && sheet == ReaderSheet.NONE && selection == null,
                startValue = { brightness ->
                    if (!brightness) common.warmFilter
                    else if (common.brightness >= 0f) common.brightness
                    else runCatching { Settings.System.getInt(context0.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
                },
                onChange = { edge = it; edgeHold = it; edgeShown = true },
                onEnd = { v ->
                    scope.launch {
                        viewModel.updateCommon { if (v.brightness) it.copy(brightness = v.value) else it.copy(warmFilter = v.value) }.join()
                        delay(200)
                        edgeHold = null
                    }
                },
            ),
    ) {
        val stateColor = if (imageCanvas) Color(0xFFE3E3E3) else Color(readerText.foreground)
        when (val s = content) {
            UiState.Loading -> ReaderSkeleton(stateColor)
            UiState.Empty -> CompositionLocalProvider(LocalContentColor provides stateColor) {
                EmptyState(stringResource(R.string.reader_no_text))
            }
            is UiState.Error -> CompositionLocalProvider(LocalContentColor provides stateColor) {
                // A moved or deleted file, or a permission lost after reinstalling: let the user point at it again.
                val relink = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.relink(uri) }
                if (viewModel.canRelink(s.error)) {
                    ErrorState(s.error, onRetry = { relink.launch(arrayOf("*/*")) }, retryLabel = stringResource(R.string.reader_relink),
                        secondaryLabel = stringResource(CoreR.string.back), onSecondary = onBack)
                } else {
                    ErrorState(s.error, onRetry = viewModel::retry, secondaryLabel = stringResource(CoreR.string.back), onSecondary = onBack)
                }
            }
            is UiState.Data -> when (val c = s.value) {
                is ReaderContent.Text -> key(c.controller) {
                    EpubReaderView(c.controller, onAttached = { viewModel.onWebViewAttached(c.controller) })
                }
                is ReaderContent.Pdf -> PdfReaderView(c.controller, viewModel, readerText, landscape)
                is ReaderContent.Manga -> MangaReaderView(c.controller, viewModel, landscape, hasPrev, hasNext)
            }
        }

        if (liveCommon.warmFilter > 0f) {
            Box(Modifier.matchParentSize().background(Color(0xFFFF8A1F).copy(alpha = (liveCommon.warmFilter * 0.35f).coerceIn(0f, 0.35f))))
        }

        EdgeAdjustPill(edge, edgeShown, Modifier.align(Alignment.Center))

        bilingual?.let { p ->
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding())
        }

        if (data is ReaderContent.Manga && !chrome) {
            // Below the tap zones that are shown on opening, so it doesn't cover their labels.
            OneTimeHint(
                "manga_gestures", stringResource(R.string.hint_manga_gestures),
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Dimens.xl).widthIn(max = 420.dp),
            )
        } else if (data != null && !chrome && sheet == ReaderSheet.NONE) {
            // Order: tap-zone diagram, then selection/menu text hint, then edge gestures.
            if (!tapZonesDone) {
                ReaderTapZonesHint(
                    layout = if (data is ReaderContent.Text && common.tapToTurn) common.textTapZones else TapZoneLayout.DISABLED,
                    rtl = textSettings.rtl || (data as? ReaderContent.Text)?.controller?.document?.rtl == true,
                    edgeGestures = common.edgeGestures,
                    onDone = { tapZonesDone = true },
                )
            } else {
                OneTimeHint(
                    "reader_gestures", stringResource(R.string.hint_reader_gestures), Modifier.align(Alignment.Center).widthIn(max = 420.dp),
                    after = "reader_tap_zones",
                )
                if (common.edgeGestures && (data is ReaderContent.Text || data is ReaderContent.Pdf)) {
                    OneTimeHint(
                        "reader_edge_gestures", stringResource(R.string.hint_reader_edges),
                        Modifier.align(Alignment.Center).widthIn(max = 420.dp), after = "reader_gestures",
                    )
                }
            }
        }

        // Status line while reading: page, progress, chapter, clock.
        if (data != null && common.footer && sheet == ReaderSheet.NONE && (data is ReaderContent.Text || data is ReaderContent.Pdf)) {
            AnimatedVisibility(
                visible = !chrome,
                enter = fadeIn(tween(250)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ReaderFooter(
                    position = position,
                    color = Color(readerText.foreground).copy(alpha = 0.55f),
                    showClock = common.footerClock,
                    showChapter = common.footerChapter,
                    horizontalPadding = readerText.marginHorizontal.coerceIn(12, 48),
                    pill = data is ReaderContent.Pdf,
                )
            }
        }

        seekDrag?.let { v ->
            val preview = remember(v, position.bookPages) { viewModel.seekPreview(v) }
            val manga = (data as? ReaderContent.Manga)?.controller
            // A short pause first: dragging across the book must not fetch every page on the way.
            val thumbnail by produceState<File?>(null, manga, preview.page) {
                value = null
                if (manga != null) {
                    delay(150)
                    value = runCatching { manga.file(preview.page - 1) }.getOrNull()
                }
            }
            SeekPreviewCard(preview, v, Modifier.align(Alignment.Center), thumbnail = thumbnail)
        }

        // ---------------------------------------------------------------- top bar
        AnimatedVisibility(
            visible = chrome && data != null,
            enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val bookmarked = remember(bookmarks, position) { viewModel.bookmarkHere() != null }
            ReaderTopBar(
                title = title,
                subtitle = position.chapterTitle,
                bookmarked = bookmarked,
                onBack = onBack,
                onBookmark = { haptic.perform(); viewModel.toggleBookmark() },
                menu = { dismiss ->
                    if (viewModel.bookId > 0) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.reader_annotations)) }, leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
                            onClick = { dismiss(); onOpenAnnotations(viewModel.bookId) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.reader_page_note)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null) },
                            onClick = { dismiss(); pageNote = true })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.reader_dictionary)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) },
                        onClick = { dismiss(); onOpenDictionary("") })
                    if (data is ReaderContent.Text) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.reader_bilingual)) }, leadingIcon = { Icon(Icons.Outlined.ViewAgenda, null) },
                            onClick = { dismiss(); viewModel.toggleBilingual() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.reader_language_packs)) }, leadingIcon = { Icon(Icons.Outlined.Language, null) },
                        onClick = { dismiss(); onOpenPacks() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.reader_stats)) }, leadingIcon = { Icon(Icons.Outlined.BarChart, null) },
                        onClick = { dismiss(); onOpenStats() })
                    if (onOpenPageLayout != null && viewModel.bookId > 0 && viewModel.book?.format == BookFormat.DOCX) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.reader_page_layout)) }, leadingIcon = { Icon(Icons.Outlined.Description, null) },
                            onClick = { dismiss(); onOpenPageLayout(viewModel.bookId) })
                    }
                    if (onOpenEditor != null && viewModel.bookId > 0 && !viewModel.isOnline) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.reader_edit_book)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { dismiss(); onOpenEditor(viewModel.bookId) })
                    }
                },
            )
        }

        // ---------------------------------------------------------------- bottom area
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            SnackbarHost(snackbar, Modifier.align(Alignment.CenterHorizontally))
            if (ttsActive && sheet != ReaderSheet.TTS) {
                MiniPill(Modifier.align(Alignment.CenterHorizontally)) {
                    IconButton(onClick = { if (ttsPlaying) viewModel.pauseTts() else viewModel.resumeTts() }) {
                        Icon(if (ttsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (ttsPlaying) R.string.tts_pause else R.string.tts_play))
                    }
                    IconButton(onClick = { sheet = ReaderSheet.TTS }) { Icon(Icons.Default.Headphones, stringResource(R.string.reader_tts)) }
                    IconButton(onClick = viewModel::stopTts) { Icon(Icons.Default.Stop, stringResource(R.string.tts_stop)) }
                }
            }
            if (autoScroll) {
                MiniPill(Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.reader_autoscroll_speed, common.autoScrollSpeed), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { viewModel.updateCommon { it.copy(autoScrollSpeed = (it.autoScrollSpeed - 5).coerceAtLeast(5)) } }) {
                        Icon(Icons.Default.Remove, stringResource(R.string.reader_autoscroll_slower))
                    }
                    IconButton(onClick = { viewModel.updateCommon { it.copy(autoScrollSpeed = (it.autoScrollSpeed + 5).coerceAtMost(200)) } }) {
                        Icon(Icons.Default.Add, stringResource(R.string.reader_autoscroll_faster))
                    }
                    IconButton(onClick = viewModel::stopAutoScroll) { Icon(Icons.Default.Stop, stringResource(R.string.reader_autoscroll_stop)) }
                }
            }
            if (ocrRunning) {
                MiniPill(Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.reader_ocr_running), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            AnimatedVisibility(
                visible = chrome && quickPanel && data != null && seekDrag == null,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
                exit = fadeOut(tween(120)),
            ) {
                ReaderQuickPanel(common, textSettings.takeIf { data is ReaderContent.Text }, viewModel, onClose = { quickPanel = false })
            }
            val jump = jumpBack
            AnimatedVisibility(
                visible = chrome && data != null && jump != null && seekDrag == null,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                JumpBackChip(page = jump?.page ?: 0, onBack = viewModel::jumpBack, onDismiss = viewModel::dismissJumpBack)
            }
            AnimatedVisibility(
                visible = chrome && data != null,
                enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            ) {
                ReaderBottomBar(
                    position = position,
                    marks = remember(bookmarks) { bookmarks.map { it.locator.totalProgression } },
                    isText = data is ReaderContent.Text,
                    reversed = data is ReaderContent.Manga && mangaSettings.direction == MangaDirection.RTL,
                    canPrev = position.chapter > 0 || hasPrev,
                    canNext = position.chapter < position.chapterCount - 1 || hasNext,
                    cpm = cpm,
                    ttsAvailable = data is ReaderContent.Text || data is ReaderContent.Pdf,
                    autoScroll = autoScroll,
                    onDrag = { seekDrag = it },
                    onSeek = viewModel::seekTotal,
                    onGoToPage = { goToPage = true },
                    onPrevChapter = viewModel::prevChapter,
                    onNextChapter = viewModel::nextChapter,
                    onToc = { sheet = ReaderSheet.TOC },
                    onSearch = { sheet = ReaderSheet.SEARCH },
                    onSettings = { sheet = ReaderSheet.SETTINGS },
                    onQuick = { quickPanel = !quickPanel },
                    onTts = { if (!ttsActive) viewModel.startTts(); sheet = ReaderSheet.TTS },
                    onAutoScroll = { viewModel.toggleAutoScroll(); viewModel.chromeVisible.value = false },
                    onTranslate = {
                        if (data is ReaderContent.Manga) viewModel.translateMangaPage(position.page) else viewModel.translatePage()
                    },
                )
            }
        }

        // ---------------------------------------------------------------- overlays
        val sel = selection
        if (sel != null && noteFor == null) {
            val currentColor = sel.highlightId?.let { id -> highlights.firstOrNull { it.id == id }?.color }
            SelectionMenu(
                style = selectionMenuStyle,
                anchor = sel.anchor,
                actions = actions,
                customActions = customActions,
                currentColor = currentColor,
                onHighlight = { color, style -> haptic.perform(); viewModel.highlight(color, style) },
                onAction = { onAction(it, sel) },
                onCustom = { runCustom(it, sel.text) },
                onDelete = sel.highlightId?.let { { viewModel.deleteHighlight() } },
                onDismiss = viewModel::clearSelection,
            )
        }
        pageMenu?.let { req ->
            val items = QuickActions.normalize(pageActions).filter { it.enabled }.map { cfg ->
                QuickMenuItem(cfg.action.name, cfg.action.icon(), stringResource(cfg.action.label())) { haptic.perform(); viewModel.runPageAction(cfg.action, req.page) }
            }
            QuickActionMenu(
                radial = pageMenuStyle == QuickMenuStyle.RADIAL,
                anchor = req.anchor,
                items = items,
                onDismiss = { viewModel.pageMenu.value = null },
                title = stringResource(R.string.reader_page_n, req.page + 1),
            )
        }
    }

    noteFor?.let { sel ->
        NoteDialog(
            initial = remember(sel) { viewModel.noteOfSelection().orEmpty() },
            quote = sel.text,
            onSave = { viewModel.saveNote(it); noteFor = null },
            onDelete = sel.highlightId?.let { { viewModel.deleteHighlight(); noteFor = null } },
            onDismiss = { noteFor = null; viewModel.clearSelection() },
        )
    }

    if (pageNote) {
        NoteDialog(
            initial = "",
            quote = null,
            onSave = { viewModel.addPageNote(it); pageNote = false },
            onDelete = null,
            onDismiss = { pageNote = false },
        )
    }

    lookup?.let { req ->
        val wordVm: WordLookupViewModel = koinViewModel()
        LaunchedEffect(req) { wordVm.lookup(req) }
        WordPopup(
            viewModel = wordVm,
            anchor = lookupAnchor,
            onDismiss = { lookup = null },
            onTranslate = { lookup = null; viewModel.translateText.value = it },
            onOpenDictionary = { lookup = null; onOpenDictionary(it) },
        )
    }

    translateText?.let { text ->
        val trVm: TranslationViewModel = koinViewModel()
        TranslationSheet(
            viewModel = trVm,
            text = text,
            sourceLang = bookLang,
            onDismiss = { viewModel.translateText.value = null },
            onSaveWord = { term, translated, lang -> viewModel.saveWord(term, translated, lang) },
            onOpenPacks = onOpenPacks,
        )
    }

    when (sheet) {
        ReaderSheet.NONE -> Unit
        ReaderSheet.TOC -> TocSheet(
            toc = toc,
            currentChapter = position.chapter,
            bookmarks = bookmarks,
            highlights = highlights,
            onToc = { viewModel.goToToc(it); sheet = ReaderSheet.NONE },
            onLocator = { viewModel.goToLocator(it); sheet = ReaderSheet.NONE },
            onDeleteBookmark = viewModel::deleteBookmark,
            onOpenAll = if (viewModel.bookId > 0) ({ sheet = ReaderSheet.NONE; onOpenAnnotations(viewModel.bookId) }) else null,
            onDismiss = { sheet = ReaderSheet.NONE },
        )
        ReaderSheet.SEARCH -> {
            val state by viewModel.searchState.collectAsStateWithLifecycle()
            SearchSheet(
                state = state,
                initial = searchSeed.ifBlank { state.query },
                onSearch = viewModel::search,
                onHit = { viewModel.openHit(it); sheet = ReaderSheet.NONE },
                onDismiss = { sheet = ReaderSheet.NONE; searchSeed = "" },
                onClear = viewModel::clearSearch,
            )
        }
        ReaderSheet.SETTINGS -> data?.let { c ->
            ReaderSettingsSheet(viewModel, c, textSettings, mangaSettings, pdfSettings, common, perBook, wordTapSetting, onDismiss = { sheet = ReaderSheet.NONE })
        }
        ReaderSheet.TTS -> TtsSheet(viewModel, common, onDismiss = { sheet = ReaderSheet.NONE })
    }

    footnote?.let { html -> FootnoteDialog(html) { viewModel.footnote.value = null } }
    image?.let { model -> ImageViewerDialog(model) { viewModel.image.value = null } }
    if (passwordNeeded) PasswordDialog(onSubmit = viewModel::submitPassword, onDismiss = onBack)
    if (showFinished) FinishedDialog(onBack = { showFinished = false; onBack() }, onDismiss = { showFinished = false })
    if (goToPage && data != null) {
        GoToPageDialog(
            page = position.bookPage,
            pages = position.bookPages.coerceAtLeast(1),
            approximate = position.approximate,
            percent = (position.total * 100).toInt().coerceIn(0, 100),
            onPage = { viewModel.goToPage(it); goToPage = false },
            onPercent = { viewModel.seekTotal(it); goToPage = false },
            onDismiss = { goToPage = false },
        )
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String?,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank() && subtitle != title) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val bookmarkLabel = stringResource(if (bookmarked) R.string.reader_remove_bookmark else R.string.reader_add_bookmark)
            IconButton(onClick = onBookmark, modifier = Modifier.semantics { stateDescription = bookmarkLabel }) {
                Icon(if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, bookmarkLabel,
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(CoreR.string.more)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    position: ReaderPosition,
    marks: List<Float>,
    isText: Boolean,
    /** Right-to-left manga: the slider starts on the right, like the pages. */
    reversed: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    cpm: Float,
    ttsAvailable: Boolean,
    autoScroll: Boolean,
    onDrag: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    onGoToPage: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onToc: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onQuick: () -> Unit,
    onTts: () -> Unit,
    onAutoScroll: () -> Unit,
    onTranslate: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            val shown = dragging ?: position.total
            val percent = (shown * 100).toInt().coerceIn(0, 100)
            val progressLabel = stringResource(R.string.reader_progress_percent, percent)
            // Where am I: page of the book (tap to jump to a page) and how much is left.
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (position.bookPages > 0) {
                    PageChip(position.bookPage, position.bookPages, position.approximate, onGoToPage)
                } else {
                    Text(stringResource(R.string.reader_page_of, position.page + 1, position.pages.coerceAtLeast(1)), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.weight(1f))
                val left = if (isText && position.charsLeftInBook > 0) {
                    stringResource(R.string.reader_left_book, formatMinutes((position.charsLeftInBook / cpm.coerceAtLeast(1f)).toInt()))
                } else null
                Text(listOfNotNull(left, progressLabel).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevChapter, enabled = canPrev) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.reader_prev_chapter)) }
                Box(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalLayoutDirection provides if (reversed) LayoutDirection.Rtl else LocalLayoutDirection.current) {
                        Slider(
                            value = shown,
                            onValueChange = { dragging = it; onDrag(it) },
                            onValueChangeFinished = { dragging?.let(onSeek); dragging = null; onDrag(null) },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel },
                        )
                    }
                    if (marks.isNotEmpty()) {
                        // Bookmarks as small ticks above the track.
                        val tick = MaterialTheme.colorScheme.tertiary
                        Canvas(Modifier.matchParentSize().padding(horizontal = 2.dp)) {
                            val w = 3.dp.toPx()
                            val h = 8.dp.toPx()
                            marks.forEach { m ->
                                val x = size.width * m.coerceIn(0f, 1f).let { if (reversed) 1f - it else it }
                                drawRect(tick, topLeft = Offset(x - w / 2, 2.dp.toPx()), size = Size(w, h))
                            }
                        }
                    }
                }
                IconButton(onClick = onNextChapter, enabled = canNext) { Icon(Icons.Default.SkipNext, stringResource(R.string.reader_next_chapter)) }
            }
            if (isText) {
                val chapterMin = (position.charsLeftInChapter / cpm.coerceAtLeast(1f)).toInt()
                val info = buildList {
                    if (position.chapterCount > 1) add(stringResource(R.string.reader_chapter_of, position.chapter + 1, position.chapterCount))
                    if (position.pages > 1) add(stringResource(R.string.reader_page_in_chapter, position.page + 1, position.pages))
                    if (position.charsLeftInChapter > 0 && position.chapterCount > 1) add(stringResource(R.string.reader_left_chapter, formatMinutes(chapterMin)))
                }.joinToString(" · ")
                if (info.isNotEmpty()) {
                    Text(info, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ChromeButton(Icons.AutoMirrored.Filled.FormatListBulleted, stringResource(R.string.reader_toc), onToc)
                ChromeButton(Icons.Default.Search, stringResource(CoreR.string.search), onSearch)
                ChromeButton(Icons.Default.TextFields, stringResource(R.string.reader_settings), onSettings)
                ChromeButton(Icons.Default.BrightnessMedium, stringResource(R.string.reader_quick_display), onQuick)
                if (ttsAvailable) ChromeButton(Icons.Default.Headphones, stringResource(R.string.reader_tts), onTts)
                ChromeButton(if (autoScroll) Icons.Default.Pause else Icons.Default.SlowMotionVideo, stringResource(R.string.reader_autoscroll), onAutoScroll)
                ChromeButton(Icons.Default.Translate, stringResource(R.string.reader_translate_page), onTranslate)
            }
        }
    }
}

@Composable
private fun formatMinutes(min: Int): String =
    if (min >= 60) stringResource(R.string.reader_hours_minutes, min / 60, min % 60) else stringResource(R.string.reader_minutes, min.coerceAtLeast(1))

@Composable
private fun ChromeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, label) }
    }
}

@Composable
private fun MiniPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 4.dp, shadowElevation = 4.dp,
        modifier = modifier.padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) { content() }
    }
}

@Composable
private fun ReaderSkeleton(textColor: Color) {
    // Lines tinted with the theme's text colour, so a dark theme doesn't flash bright bars while the book opens.
    val base = textColor.copy(alpha = 0.12f)
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
        SkeletonBox(Modifier.fillMaxWidth(0.6f).height(28.dp), base)
        Spacer(Modifier.height(24.dp))
        repeat(14) { i ->
            SkeletonBox(Modifier.fillMaxWidth(if (i % 5 == 4) 0.55f else 1f).height(14.dp), base)
            Spacer(Modifier.height(12.dp))
        }
    }
}

