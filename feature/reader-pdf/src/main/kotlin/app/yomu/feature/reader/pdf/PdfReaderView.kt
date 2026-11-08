package app.yomu.feature.reader.pdf

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yomu.core.common.QuietErrors
import app.yomu.data.book.PdfReflow
import app.yomu.data.book.PdfReflowText
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.HighlightStyle
import app.yomu.domain.model.PdfMode
import app.yomu.domain.model.TextReaderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/** Callbacks of the PDF engine. Rectangles are in root coordinates (px). */
interface PdfListener {
    fun onTap(x: Float, y: Float) {}
    fun onSelection(selection: PdfSelection?, bounds: Rect?) {}
    fun onHighlightTap(highlight: Highlight, bounds: Rect) {}
    fun onLink(uri: String) {}
    fun onPageChanged(page: Int) {}
}

@Composable
fun PdfReaderView(controller: PdfController, listener: PdfListener, textSettings: TextReaderSettings, landscape: Boolean, modifier: Modifier = Modifier) {
    val settings = controller.settings
    LaunchedEffect(settings) { controller.clearCache() }
    Box(modifier.fillMaxSize().background(if (settings.night) Color.Black else Color(0xFF2B2B2B))) {
        when {
            settings.reflow -> ReflowView(controller, listener, textSettings)
            settings.mode == PdfMode.SCROLL -> ScrollView(controller, listener)
            settings.mode == PdfMode.DUAL && landscape -> PagedView(controller, listener, dual = true)
            else -> PagedView(controller, listener, dual = false)
        }
    }
}

@Composable
private fun ScrollView(controller: PdfController, listener: PdfListener) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = controller.page)
    var zoom by remember { mutableFloatStateOf(1f) }
    val hScroll = rememberScrollState()
    LaunchedEffect(Unit) { controller.pageRequests.collect { state.scrollToItem(it) } }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { controller.page = it; listener.onPageChanged(it) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().pinchToZoom { factor -> zoom = (zoom * factor).coerceIn(1f, 4f) }) {
        val width = maxWidth * zoom
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxHeight().horizontalScroll(hScroll, enabled = zoom > 1.01f).width(width),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(controller.pageCount, key = { it }) { index ->
                PdfPage(controller, index, listener, Modifier.fillMaxWidth(), renderScale = 1f)
            }
        }
    }
}

@Composable
private fun PagedView(controller: PdfController, listener: PdfListener, dual: Boolean) {
    val count = if (dual) (controller.pageCount + 1) / 2 else controller.pageCount
    val pager = rememberPagerState(initialPage = if (dual) controller.page / 2 else controller.page) { count }
    LaunchedEffect(Unit) { controller.pageRequests.collect { pager.scrollToPage(if (dual) it / 2 else it) } }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.distinctUntilChanged().collect {
            val page = if (dual) it * 2 else it
            controller.page = page
            listener.onPageChanged(page)
        }
    }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { p ->
        val zoomState = rememberZoomableState(zoomSpec = ZoomSpec(maximum = ZoomLimit(factor = 5f)))
        Box(Modifier.fillMaxSize().zoomable(zoomState), contentAlignment = Alignment.Center) {
            if (dual) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    PdfPage(controller, p * 2, listener, Modifier.weight(1f), renderScale = 1.5f)
                    if (p * 2 + 1 < controller.pageCount) PdfPage(controller, p * 2 + 1, listener, Modifier.weight(1f), renderScale = 1.5f)
                }
            } else {
                PdfPage(controller, p, listener, Modifier.fillMaxWidth(), renderScale = 2f, fitHeight = true)
            }
        }
    }
}

/** One rendered page with highlight, search and selection overlays. */
@Composable
private fun PdfPage(controller: PdfController, index: Int, listener: PdfListener, modifier: Modifier, renderScale: Float, fitHeight: Boolean = false) {
    val scope = rememberCoroutineScope()
    var ratio by remember(index) { mutableFloatStateOf(1.414f) }
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var overlay by remember(index) { mutableStateOf<List<Pair<RectF, Color>>>(emptyList()) }
    var hlRects by remember(index) { mutableStateOf<List<Pair<Highlight, List<RectF>>>>(emptyList()) }
    val settings = controller.settings
    LaunchedEffect(index, settings) { ratio = controller.ratio(index) }
    LaunchedEffect(index, widthPx, settings) {
        if (widthPx > 0) bitmap = runCatching { controller.bitmap(index, (widthPx * renderScale).toInt()).asImageBitmap() }.getOrNull()
    }
    val selection = controller.selection
    val hit = controller.currentHit
    val tts = controller.ttsMark
    LaunchedEffect(index, selection, hit, tts, widthPx, settings) {
        val list = mutableListOf<Pair<RectF, Color>>()
        if (selection != null && selection.page == index) selection.rects.forEach { list += it to Color(0x553F51B5) }
        if (hit != null && hit.page == index) runCatching { controller.pdf.rects(index, hit.start, hit.length) }.getOrDefault(emptyList()).forEach { list += it to Color(0x66FFB300) }
        if (tts != null && tts.first == index) runCatching { controller.pdf.rects(index, tts.second, tts.third - tts.second) }.getOrDefault(emptyList()).forEach { list += it to Color(0x446750A4) }
        overlay = list
    }
    val highlights = controller.highlights
    LaunchedEffect(index, highlights) {
        hlRects = highlights.filter { it.locator.chapter == index && it.locator.offset != null }.map { h ->
            val start = h.locator.offset ?: 0
            val end = h.locator.endOffset ?: (start + h.text.length)
            h to runCatching { controller.pdf.rects(index, start, end - start) }.getOrDefault(emptyList())
        }
    }
    var viewRects by remember(index) { mutableStateOf<List<Triple<RectF, Color, HighlightStyle?>>>(emptyList()) }
    LaunchedEffect(overlay, hlRects, widthPx, ratio) {
        if (widthPx <= 0) return@LaunchedEffect
        val w = widthPx.toFloat()
        val h = w * ratio
        val out = mutableListOf<Triple<RectF, Color, HighlightStyle?>>()
        hlRects.forEach { (hl, rects) -> rects.forEach { out += Triple(controller.toView(index, it, w, h), Color(hl.color.argb).copy(alpha = 0.4f), hl.style) } }
        overlay.forEach { (r, c) -> out += Triple(controller.toView(index, r, w, h), c, null) }
        viewRects = out
    }
    val base = if (fitHeight) modifier.aspectRatio(1f / ratio, matchHeightConstraintsFirst = ratio > 1.3f) else modifier.aspectRatio(1f / ratio)
    Box(
        base
            .background(if (settings.night) Color.Black else Color.White)
            .onGloballyPositioned { coords = it; widthPx = it.size.width }
            .pointerInput(index) {
                detectTapGestures { pos ->
                    scope.launch(QuietErrors) {
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val (px, py) = controller.toPage(index, pos.x, pos.y, w, h)
                        controller.links(index).firstOrNull { it.bounds.contains(px, py) }?.let { link ->
                            when {
                                link.page != null -> controller.goTo(link.page!!)
                                link.uri != null -> listener.onLink(link.uri!!)
                            }
                            return@launch
                        }
                        hlRects.firstOrNull { (_, rects) -> rects.any { it.contains(px, py) } }?.let { (hl, _) ->
                            val root = coords?.boundsInRoot() ?: Rect.Zero
                            listener.onHighlightTap(hl, Rect(root.left + pos.x, root.top + pos.y, root.left + pos.x, root.top + pos.y))
                            return@launch
                        }
                        if (controller.selection != null) {
                            controller.selection = null
                            listener.onSelection(null, null)
                            return@launch
                        }
                        val root = coords?.boundsInRoot()
                        val rx = (root?.left ?: 0f) + pos.x
                        val ry = (root?.top ?: 0f) + pos.y
                        listener.onTap(rx, ry)
                    }
                }
            }
            .pointerInput(index) {
                var anchor = -1
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        scope.launch(QuietErrors) {
                            val (px, py) = controller.toPage(index, pos.x, pos.y, size.width.toFloat(), size.height.toFloat())
                            val ci = controller.pdf.charIndexAt(index, px, py, 12f)
                            anchor = ci
                            if (ci >= 0) controller.selectWord(index, ci)
                        }
                    },
                    onDrag = { change, _ ->
                        val pos = change.position
                        scope.launch(QuietErrors) {
                            if (anchor < 0) return@launch
                            val (px, py) = controller.toPage(index, pos.x, pos.y, size.width.toFloat(), size.height.toFloat())
                            val ci = controller.pdf.charIndexAt(index, px, py, 12f)
                            if (ci >= 0) controller.select(index, minOf(anchor, ci), maxOf(anchor, ci) + 1)
                        }
                    },
                    onDragEnd = {
                        scope.launch(QuietErrors) {
                            val sel = controller.selection ?: return@launch
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val rects = sel.rects.map { controller.toView(index, it, w, h) }
                            val root = coords?.boundsInRoot() ?: Rect.Zero
                            val union = rects.fold<RectF, RectF?>(null) { acc, r -> acc?.apply { union(r) } ?: RectF(r) } ?: RectF()
                            listener.onSelection(sel, Rect(root.left + union.left, root.top + union.top, root.left + union.right, root.top + union.bottom))
                        }
                    },
                )
            },
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        } else {
            Text("${index + 1}", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
        }
        if (viewRects.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                viewRects.forEach { (r, c, style) ->
                    when (style) {
                        HighlightStyle.UNDERLINE -> drawRect(c.copy(alpha = 0.9f), Offset(r.left, r.bottom - 3f), Size(r.width(), 3f))
                        HighlightStyle.STRIKETHROUGH -> drawRect(c.copy(alpha = 0.9f), Offset(r.left, r.centerY() - 1.5f), Size(r.width(), 3f))
                        else -> drawRect(c, Offset(r.left, r.top), Size(r.width(), r.height()))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReflowView(controller: PdfController, listener: PdfListener, s: TextReaderSettings) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = controller.page)
    LaunchedEffect(Unit) { controller.pageRequests.collect { state.scrollToItem(it) } }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { controller.page = it; listener.onPageChanged(it) }
    }
    val bg = Color(s.background)
    val fg = Color(s.foreground)
    val family = when (s.fontFamily) {
        "sans-serif", "roboto" -> FontFamily.SansSerif
        "monospace" -> FontFamily.Monospace
        else -> FontFamily.Serif
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().background(bg).pointerInput(Unit) { detectTapGestures { listener.onTap(it.x, it.y) } },
    ) {
        items(controller.pageCount, key = { it }) { index ->
            var paragraphs by remember(index) { mutableStateOf<List<String>?>(null) }
            var showPage by remember(index) { mutableStateOf(false) }
            var textError by remember(index) { mutableStateOf(false) }
            LaunchedEffect(index) {
                try {
                    // Geometric reflow from the page's text lines; a scanned page (no text layer)
                    // falls back to the plain-text reconstruction.
                    val pageLines = controller.pdf.lines(index)
                    paragraphs = if (pageLines.isEmpty()) {
                        PdfReflowText.paragraphs(controller.pdf.text(index).orEmpty())
                    } else {
                        val (width, _) = controller.pdf.pageSize(index)
                        PdfReflow.paragraphs(pageLines, width.toFloat())
                    }
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { textError = true; paragraphs = emptyList() }
            }
            Column(Modifier.padding(horizontal = s.marginHorizontal.dp, vertical = 8.dp)) {
                Text("— ${index + 1} —", color = fg.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                paragraphs?.forEach { p ->
                    Text(
                        p,
                        color = fg,
                        fontFamily = family,
                        fontSize = (16 * s.fontSizePercent / 100f).sp,
                        lineHeight = (16 * s.fontSizePercent / 100f * s.lineHeight).sp,
                        modifier = Modifier.padding(bottom = (s.paragraphSpacing * 16).dp),
                    )
                }
                if (paragraphs != null) {
                    if (paragraphs.orEmpty().isEmpty()) {
                        Text(stringResource(if (textError) R.string.pdf_reflow_text_error else R.string.pdf_reflow_no_text), color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                        PdfPage(controller, index, listener, Modifier.fillMaxWidth(), renderScale = 1f)
                    } else {
                        TextButton(onClick = { showPage = !showPage }) {
                            Text(stringResource(if (showPage) R.string.pdf_reflow_hide_page else R.string.pdf_reflow_show_page))
                        }
                        if (showPage) PdfPage(controller, index, listener, Modifier.fillMaxWidth(), renderScale = 1f)
                    }
                }
            }
        }
    }
}

/** Two-finger pinch detector that leaves single-finger scrolling to the content. */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var previous = 0f
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val a = pressed[0].position
                val b = pressed[1].position
                val distance = (a - b).getDistance()
                if (previous > 0f && distance > 0f) onZoom(distance / previous)
                previous = distance
                event.changes.forEach { it.consume() }
            } else {
                previous = 0f
            }
        }
    }
}
