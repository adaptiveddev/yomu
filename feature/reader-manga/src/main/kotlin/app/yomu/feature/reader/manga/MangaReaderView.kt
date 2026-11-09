package app.yomu.feature.reader.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yomu.domain.model.MangaBackground
import app.yomu.domain.model.MangaDirection
import app.yomu.domain.model.MangaSettings
import app.yomu.domain.model.ScaleType
import app.yomu.domain.model.TapZoneLayout
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.transformations
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore

interface MangaListener {
    fun onMenu() {}
    fun onPageChanged(page: Int) {}
    fun onLongPress(page: Int) {}

    /** A long press at [windowPosition] (window coordinates, for the long-press menu); by default [onLongPress]. */
    fun onLongPressAt(page: Int, windowPosition: Offset) = onLongPress(page)
    fun onChapterEdge(next: Boolean) {}
    fun onFinished() {}
    fun onBlockTap(page: Int, block: OverlayBlock) {}
}

/** Pages fetched at the same time by the preloader. */
private const val PRELOAD_PARALLEL = 3

/** Queued preloads this far behind the reading position are dropped (the reader jumped elsewhere). */
private const val PRELOAD_SLACK = 2

/** Pages shown by the pager: optional previous-chapter page, the images, then an end page. */
private class Layout(val count: Int, val hasPrev: Boolean, val spreads: List<IntArray>?) {
    val offset = if (hasPrev) 1 else 0
    val imageSlots = spreads?.size ?: count
    val total = offset + imageSlots + 1
    fun slotOf(page: Int): Int = offset + (spreads?.indexOfFirst { page in it }?.coerceAtLeast(0) ?: page)
    fun pageOf(slot: Int): Int? {
        val i = slot - offset
        if (i < 0 || i >= imageSlots) return null
        return spreads?.get(i)?.first() ?: i
    }
}

@Composable
fun MangaReaderView(
    controller: MangaController,
    listener: MangaListener,
    landscape: Boolean,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    modifier: Modifier = Modifier,
) {
    val s = controller.settings
    val context = LocalContext.current
    // Preloads the next pages, a few at a time and nearest first. Turning a page does not restart fetches in flight;
    // pages left far behind are skipped, failed ones are tried again when the reader gets near them.
    LaunchedEffect(controller) {
        val loader = context.imageLoader
        val gate = Semaphore(PRELOAD_PARALLEL)
        val started = HashSet<Int>()
        snapshotFlow { controller.page to controller.preloadAhead }.collect { (page, ahead) ->
            for (i in page + 1..(page + ahead).coerceAtMost(controller.count - 1)) {
                if (!started.add(i)) continue
                launch {
                    val ok = gate.withPermit {
                        val now = controller.page
                        if (i <= now || i > now + controller.preloadAhead + PRELOAD_SLACK) return@withPermit false
                        runCatching { loader.enqueue(request(context, controller.file(i), controller.settings.cropBorders)) }.isSuccess
                    }
                    if (!ok) started.remove(i)
                }
            }
        }
    }
    var zonesVisible by remember { mutableStateOf(false) }
    LaunchedEffect(controller.tapZonesHint) {
        if (controller.tapZonesHint > 0) {
            zonesVisible = true
            delay(3_000)
            zonesVisible = false
        }
    }
    Box(modifier.fillMaxSize().background(Color(s.background.argb))) {
        if (s.direction == MangaDirection.WEBTOON) {
            Webtoon(controller, listener, hasPrevChapter, hasNextChapter)
        } else {
            val spreads = if (landscape && s.dualPageLandscape && s.direction != MangaDirection.VERTICAL) spreads(controller.count) else null
            Paged(controller, listener, Layout(controller.count, hasPrevChapter, spreads), hasNextChapter)
        }
        AnimatedVisibility(zonesVisible && s.tapZones != TapZoneLayout.DISABLED, enter = fadeIn(), exit = fadeOut()) {
            TapZonesOverlay(s.tapZones, s.invertTapZones, rtl = s.direction == MangaDirection.RTL)
        }
        if (s.showPageNumber && controller.count > 0) {
            Text(
                "${controller.page + 1} / ${controller.count}",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** Text colour that stays readable on the chosen page background. */
private fun onBackground(s: MangaSettings): Color = if (s.background == MangaBackground.WHITE) Color(0xFF202020) else Color.White

private fun spreads(count: Int): List<IntArray> {
    val out = mutableListOf<IntArray>()
    if (count > 0) out += intArrayOf(0)
    var i = 1
    while (i < count) {
        out += if (i + 1 < count) intArrayOf(i, i + 1) else intArrayOf(i)
        i += 2
    }
    return out
}

internal fun request(context: android.content.Context, file: File, crop: Boolean): ImageRequest =
    ImageRequest.Builder(context).data(file).apply { if (crop) transformations(CropBordersTransformation()) }.build()

@Composable
private fun Paged(controller: MangaController, listener: MangaListener, layout: Layout, hasNext: Boolean) {
    val s = controller.settings
    val rtl = s.direction == MangaDirection.RTL
    val pager = rememberPagerState(initialPage = layout.slotOf(controller.page)) { layout.total }
    val scope = rememberCoroutineScope()
    val animate by rememberUpdatedState(s.pageAnimation)
    suspend fun go(slot: Int) = if (animate) pager.animateScrollToPage(slot) else pager.scrollToPage(slot)
    LaunchedEffect(layout.spreads?.size, layout.hasPrev) { pager.scrollToPage(layout.slotOf(controller.page)) }
    LaunchedEffect(Unit) {
        controller.pageRequests.collect { target ->
            when {
                target < 0 -> if (layout.hasPrev) go(0) else listener.onChapterEdge(false)
                target >= controller.count -> go(layout.total - 1)
                else -> {
                    val slot = layout.slotOf(target)
                    if (slot == pager.currentPage && layout.spreads != null) {
                        val next = if (target > controller.page) pager.currentPage + 1 else pager.currentPage - 1
                        go(next.coerceIn(0, layout.total - 1))
                    } else if (kotlin.math.abs(slot - pager.currentPage) > 1) {
                        // Long jumps (slider, table of contents) never animate through every page.
                        pager.scrollToPage(slot)
                    } else {
                        go(slot)
                    }
                }
            }
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.distinctUntilChanged().collect { slot ->
            layout.pageOf(slot)?.let { controller.page = it; listener.onPageChanged(it) }
            if (slot == layout.total - 1) {
                controller.page = (controller.count - 1).coerceAtLeast(0)
                listener.onFinished()
            }
        }
    }
    val onTap: (Offset, IntSize) -> Unit = { pos, size ->
        val action = TapZones.action(s.tapZones, s.invertTapZones, rtl, pos.x / size.width, pos.y / size.height)
        when (action) {
            TapAction.MENU -> listener.onMenu()
            TapAction.NEXT -> scope.launch { go((pager.currentPage + 1).coerceAtMost(layout.total - 1)) }
            TapAction.PREV -> scope.launch {
                if (pager.currentPage == 0 && !layout.hasPrev) listener.onChapterEdge(false) else go((pager.currentPage - 1).coerceAtLeast(0))
            }
        }
    }
    val content: @Composable (Int) -> Unit = { slot ->
        when {
            layout.hasPrev && slot == 0 -> EdgePage(next = false, available = true, onGo = { listener.onChapterEdge(false) }, onMenu = listener::onMenu)
            slot == layout.total - 1 -> EdgePage(next = true, available = hasNext, onGo = { listener.onChapterEdge(true) }, onMenu = listener::onMenu)
            else -> {
                val i = slot - layout.offset
                val group = layout.spreads?.get(i) ?: intArrayOf(i)
                if (group.size == 2) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        val order = if (rtl) group.reversedArray() else group
                        order.forEach { p -> PageImage(controller, listener, p, Modifier.weight(1f).fillMaxHeight(), onTap) }
                    }
                } else {
                    PageImage(controller, listener, group[0], Modifier.fillMaxSize(), onTap)
                }
            }
        }
    }
    if (s.direction == MangaDirection.VERTICAL) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { content(it) }
    } else {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), reverseLayout = rtl, beyondViewportPageCount = 1, key = { it }) { content(it) }
    }
}

@Composable
private fun PageImage(controller: MangaController, listener: MangaListener, index: Int, modifier: Modifier, onTap: (Offset, IntSize) -> Unit) {
    val s = controller.settings
    val context = LocalContext.current
    var file by remember(index) { mutableStateOf<File?>(null) }
    var error by remember(index) { mutableStateOf<Throwable?>(null) }
    var attempt by remember(index) { mutableIntStateOf(0) }
    LaunchedEffect(index, attempt) {
        error = null
        loadPage(controller, index, onFile = { file = it }, onError = { error = it })
    }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    BoxWithConstraints(modifier.onGloballyPositioned { coords = it }, contentAlignment = Alignment.Center) {
        val boxSize = IntSize(constraints.maxWidth.coerceAtLeast(1), constraints.maxHeight.coerceAtLeast(1))
        size = boxSize
        val f = file
        when {
            error != null -> PageError(error, onBackground(s), onRetry = { attempt++ }, onMenu = listener::onMenu)
            f == null -> CircularProgressIndicator(color = onBackground(s).copy(alpha = 0.7f))
            else -> {
                val zoomState = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maximum = ZoomLimit(factor = 6f))))
                val filter = ImageFilters.matrix(s)?.let { ColorFilter.colorMatrix(it) }
                ZoomableAsyncImage(
                    model = request(context, f, s.cropBorders),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    state = zoomState,
                    colorFilter = filter,
                    contentScale = when (s.scale) {
                        ScaleType.FIT_SCREEN -> ContentScale.Fit
                        ScaleType.FIT_WIDTH -> ContentScale.FillWidth
                        ScaleType.FIT_HEIGHT -> ContentScale.FillHeight
                        ScaleType.ORIGINAL -> ContentScale.None
                    },
                    onClick = { pos -> onTap(pos, size) },
                    onLongClick = { pos -> listener.onLongPressAt(index, coords?.takeIf { it.isAttached }?.localToWindow(pos) ?: pos) },
                )
                val blocks = controller.overlays[index]
                if (controller.overlayVisible && !blocks.isNullOrEmpty()) {
                    // The replacement (coordinateSystem.contentBounds) is still experimental in telephoto 0.17.
                    @Suppress("DEPRECATION")
                    val bounds = zoomState.zoomableState.transformedContentBounds
                    val density = LocalDensity.current
                    if (bounds.width > 0f) {
                        Box(Modifier.fillMaxSize()) {
                            blocks.forEach { b ->
                                val x = bounds.left + b.left * bounds.width
                                val y = bounds.top + b.top * bounds.height
                                val w = (b.right - b.left) * bounds.width
                                val h = (b.bottom - b.top) * bounds.height
                                with(density) {
                                    Surface(
                                        color = Color.White.copy(alpha = 0.93f),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.offset(x.toDp(), y.toDp()).size(w.toDp().coerceAtLeast(24.dp), h.toDp().coerceAtLeast(16.dp))
                                            .clickable { listener.onBlockTap(index, b) },
                                    ) {
                                        Text(
                                            b.translated ?: b.original,
                                            color = Color.Black,
                                            fontSize = fitFont(b.translated ?: b.original, w, h, density.density).sp,
                                            lineHeight = (fitFont(b.translated ?: b.original, w, h, density.density) * 1.1f).sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Rough font size that fits [text] into a box of [w]x[h] px. */
private fun fitFont(text: String, w: Float, h: Float, density: Float): Float {
    val area = (w * h) / (density * density)
    val chars = text.length.coerceAtLeast(1)
    val size = kotlin.math.sqrt(area / chars / 0.6f)
    return size.coerceIn(7f, 18f)
}

/** Loads the file of page [index]; leaving the page (cancellation) is not reported as a page error. */
private suspend fun loadPage(controller: MangaController, index: Int, onFile: (File) -> Unit, onError: (Throwable) -> Unit) {
    try {
        onFile(controller.file(index))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        onError(e)
    }
}

@Composable
private fun PageError(error: Throwable?, color: Color, onRetry: () -> Unit, onMenu: () -> Unit) {
    Column(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onMenu() } }.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.BrokenImage, null, tint = color.copy(alpha = 0.8f), modifier = Modifier.size(48.dp))
        Text(
            stringResource(R.string.manga_page_error),
            color = color,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        val detail = error?.let { app.yomu.core.ui.components.errorMessage(it) }
        if (!detail.isNullOrBlank()) {
            Text(detail, color = color.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Refresh, null, tint = color, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(app.yomu.core.ui.R.string.retry), color = color)
        }
    }
}

@Composable
private fun EdgePage(next: Boolean, available: Boolean, onGo: () -> Unit, onMenu: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF121212)).pointerInput(Unit) { detectTapGestures { onMenu() } }.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (next) {
            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(56.dp))
            Text(stringResource(R.string.manga_chapter_finished), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            if (available) {
                Button(onClick = onGo, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.manga_next_chapter))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                Text(stringResource(R.string.manga_no_next), color = Color.LightGray, textAlign = TextAlign.Center)
            }
        } else {
            OutlinedButton(onClick = onGo, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.manga_prev_chapter), color = Color.White)
            }
        }
    }
}

@Composable
private fun Webtoon(controller: MangaController, listener: MangaListener, hasPrev: Boolean, hasNext: Boolean) {
    val s = controller.settings
    val context = LocalContext.current
    val offset = if (hasPrev) 1 else 0
    val state = rememberLazyListState(initialFirstVisibleItemIndex = controller.page + offset)
    var zoom by remember { mutableFloatStateOf(1f) }
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val animate by rememberUpdatedState(s.pageAnimation)
    suspend fun scrollBy(px: Float) {
        if (animate) state.animateScrollBy(px) else state.scrollBy(px)
    }
    LaunchedEffect(Unit) {
        controller.pageRequests.collect { target ->
            when {
                target < 0 -> listener.onChapterEdge(false)
                target >= controller.count -> state.animateScrollToItem(controller.count + offset)
                target == controller.page + 1 -> scrollBy(state.layoutInfo.viewportSize.height * 0.85f)
                target == controller.page - 1 -> scrollBy(-state.layoutInfo.viewportSize.height * 0.85f)
                else -> state.scrollToItem(target + offset)
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { i ->
            val p = (i - offset).coerceIn(0, (controller.count - 1).coerceAtLeast(0))
            controller.page = p
            listener.onPageChanged(p)
            if (i >= controller.count + offset - 1 && !state.canScrollForward) listener.onFinished()
        }
    }
    val filter = ImageFilters.matrix(s)?.let { ColorFilter.colorMatrix(it) }
    var stripCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onGloballyPositioned { stripCoords = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var previous = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            val d = (pressed[0].position - pressed[1].position).getDistance()
                            if (previous > 0f) zoom = (zoom * d / previous).coerceIn(1f, 3f)
                            previous = d
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(s.tapZones, s.invertTapZones, s.webtoonDoubleTapZoom, zoom > 1.01f) {
                detectTapGestures(
                    onTap = { pos ->
                        val action = TapZones.action(s.tapZones, s.invertTapZones, false, pos.x / size.width, pos.y / size.height)
                        when (action) {
                            TapAction.MENU -> listener.onMenu()
                            TapAction.NEXT -> scope.launch { scrollBy(size.height * 0.8f) }
                            TapAction.PREV -> scope.launch { scrollBy(-size.height * 0.8f) }
                        }
                    },
                    // Without a double-tap handler single taps fire at once; zoomed in, a double tap always zooms back out.
                    onDoubleTap = if (s.webtoonDoubleTapZoom || zoom > 1.01f) { _ -> zoom = if (zoom > 1.01f) 1f else 2f } else null,
                    onLongPress = { pos -> listener.onLongPressAt(controller.page, stripCoords?.takeIf { it.isAttached }?.localToWindow(pos) ?: pos) },
                )
            },
    ) {
        val sidePadding = maxWidth * (s.webtoonSidePadding.coerceIn(0, 30) / 100f)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxHeight().horizontalScroll(hScroll, enabled = zoom > 1.01f).width(maxWidth * zoom),
            contentPadding = PaddingValues(horizontal = sidePadding * zoom),
            verticalArrangement = Arrangement.spacedBy(if (s.webtoonGaps) 10.dp else 0.dp),
        ) {
            if (hasPrev) item(key = "prev") { Box(Modifier.fillMaxWidth().heightIn(min = 240.dp)) { EdgePage(false, true, { listener.onChapterEdge(false) }, listener::onMenu) } }
            items(controller.count, key = { it }) { index ->
                var file by remember(index) { mutableStateOf<File?>(null) }
                var error by remember(index) { mutableStateOf<Throwable?>(null) }
                var attempt by remember(index) { mutableIntStateOf(0) }
                LaunchedEffect(index, attempt) {
                    error = null
                    loadPage(controller, index, onFile = { file = it }, onError = { error = it })
                }
                val f = file
                if (error != null) {
                    // A failed page keeps its place in the strip and can be retried (it used to spin forever).
                    Box(Modifier.fillMaxWidth().heightIn(min = 400.dp), contentAlignment = Alignment.Center) {
                        PageError(error, onBackground(s), onRetry = { attempt++ }, onMenu = listener::onMenu)
                    }
                } else if (f == null) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 400.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = onBackground(s).copy(alpha = 0.7f))
                    }
                } else {
                    AsyncImage(
                        model = request(context, f, s.cropBorders),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        colorFilter = filter,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    )
                }
            }
            item(key = "end") { Box(Modifier.fillMaxWidth().heightIn(min = 360.dp)) { EdgePage(true, hasNext, { listener.onChapterEdge(true) }, listener::onMenu) } }
        }
    }
}
