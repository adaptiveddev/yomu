package app.yomu.navigation

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.yomu.core.common.ErrorLog
import app.yomu.core.ui.components.LocalSharedElementsEnabled

/**
 * Which top-level tab is on screen and which ones are composed. [start] is the tab the app opened with: Back from any
 * other tab returns to it, as it did when every tab was a destination of the navigation graph.
 */
@Stable
class TabsState(initial: TopLevel, val start: TopLevel = initial) {
    var selected: TopLevel by mutableStateOf(initial)
        private set

    /** Tabs composed so far, in the order they were opened (or prepared). */
    internal val composed = mutableStateListOf(initial)

    /** Composes [tab] off screen, so that showing it later costs no more than a layout pass. */
    fun prepare(tab: TopLevel) {
        if (tab !in composed) composed += tab
    }

    fun select(tab: TopLevel) {
        prepare(tab)
        if (tab == selected) return
        selected = tab
        ErrorLog.screen(tab.trailName)
    }

    /** The tabs were disposed (a screen opened over them): when they come back, only the selected one is composed. */
    internal fun keepOnlySelected() {
        composed.retainAll { it == selected }
        if (composed.isEmpty()) composed += selected
    }

    companion object {
        /** Only the tab on screen and the start tab are saved: the others are composed again when they are opened. */
        val Saver: Saver<TabsState, Any> = listSaver(
            save = { listOf(it.start.name, it.selected.name) },
            restore = { names ->
                val start = TopLevel.entries.firstOrNull { it.name == names.getOrNull(0) } ?: TopLevel.LIBRARY
                val selected = TopLevel.entries.firstOrNull { it.name == names.getOrNull(1) } ?: start
                TabsState(selected, start)
            },
        )
    }
}

/** Length of the switch: the old tab fades out in its first third, the new one fades and grows in (Material fade through). */
private const val SWITCH_MS = 280
private const val OUT_PART = 0.35f
private const val IN_DELAY = 0.15f
private const val IN_SCALE = 0.965f
private const val BACK_SETTLE_MS = 160
private const val BACK_SHIFT = 24
private const val BACK_PEEK_SCALE = 0.94f
private const val BACK_CARD_SHRINK = 0.1f

/**
 * The top-level tabs inside one destination (1.9.0).
 *
 * When every tab was a destination of its own, a tap on the bar threw away the screen on display and composed the next
 * one from scratch (its whole list and all its images) in one long frame: that frame was the lag felt on every switch,
 * and the bar's animation stalled with it. Now a tab, once opened, stays composed while another one is on screen, so
 * switching back to it is a layout pass. A tab that was never opened is composed and measured while the finger is
 * still on the bar ([TabsState.prepare] on press), so by the time it lifts the new tab is ready.
 *
 * A tab off screen is not placed: it is not drawn, gets no touches and has no semantics. Its lifecycle stops at
 * STARTED (its data keeps coming, so it is up to date when it comes back; nothing in the tabs waits for "resumed"),
 * its back handlers are switched off and its covers take no part in shared element transitions. Every tab keeps a
 * ViewModel store and saved state of its own, as it had as a destination.
 */
@Composable
fun TabsHost(state: TabsState, reduceMotion: Boolean, modifier: Modifier = Modifier, content: @Composable (TopLevel) -> Unit) {
    val parentLifecycle = LocalLifecycleOwner.current
    val stores = viewModel { TabStores() }
    val saveable = rememberSaveableStateHolder()
    val backOwner = LocalOnBackPressedDispatcherOwner.current
    val focus = LocalFocusManager.current
    val selected = state.selected

    // The tab drawn on top (the selected one, a frame after the tap) and the one fading out under it.
    var onTop by remember { mutableStateOf(selected) }
    var leaving by remember { mutableStateOf<TopLevel?>(null) }
    val progress = remember { Animatable(1f) }
    // Predictive back: the start tab shows under the current one while the gesture is on; [back] is its progress.
    var peek by remember { mutableStateOf<TopLevel?>(null) }
    val back = remember { Animatable(0f) }
    var backDirection by remember { mutableFloatStateOf(1f) }
    var backCommitted by remember { mutableStateOf(false) }
    val shift = with(LocalDensity.current) { BACK_SHIFT.dp.toPx() }
    LaunchedEffect(selected) {
        if (selected == onTop) return@LaunchedEffect
        val from = onTop
        // A text field of the old tab must not keep the keyboard while its tab is gone.
        focus.clearFocus(force = true)
        onTop = selected
        // The gesture already played the exit of the old tab.
        if (reduceMotion || backCommitted) {
            backCommitted = false
            peek = null
            back.snapTo(0f)
            leaving = null
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        leaving = from
        progress.snapTo(0f)
        try {
            progress.animateTo(1f, tween(SWITCH_MS, easing = LinearEasing))
        } finally {
            if (leaving == from) leaving = null
        }
    }
    // Tabs measured at least once: a tab off screen is measured only until then (its list composes its first rows).
    val measured = remember { HashSet<TopLevel>() }
    // A screen opened over the tabs (a title, the reader) disposes all of them. Coming back composes only the tab on
    // screen, as before 1.9.0, so the frame of the return is no heavier than it was; the others are composed again when
    // they are pressed, with their state and ViewModels kept.
    DisposableEffect(state) { onDispose { state.keepOnlySelected() } }

    Layout(
        modifier = modifier,
        content = {
            for (tab in state.composed) {
                key(tab) {
                    val active = tab == selected
                    val shown = tab == onTop || tab == leaving || tab == peek
                    TabSlot(
                        tab = tab,
                        active = active,
                        parentLifecycle = parentLifecycle,
                        store = stores.of(tab),
                        backOwner = backOwner,
                        modifier = Modifier
                            .layoutId(tab)
                            .then(if (shown) Modifier else Modifier.clearAndSetSemantics {})
                            // The tab on top takes every touch in its bounds, so none reaches the one fading out below.
                            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                    ) {
                        val toStart = tab != state.start
                        if (reduceMotion) {
                            BackHandler(enabled = toStart) { state.select(state.start) }
                        } else {
                            PredictiveBackHandler(enabled = toStart) { events ->
                                state.prepare(state.start)
                                peek = state.start
                                try {
                                    events.collect { e ->
                                        backDirection = if (e.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                                        back.snapTo(e.progress)
                                    }
                                } catch (c: CancellationException) {
                                    withContext(NonCancellable) {
                                        back.animateTo(0f, tween(BACK_SETTLE_MS))
                                        peek = null
                                    }
                                    throw c
                                }
                                back.animateTo(1f, tween(BACK_SETTLE_MS))
                                backCommitted = true
                                state.select(state.start)
                            }
                        }
                        saveable.SaveableStateProvider(tab.name) { content(tab) }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val top = onTop
        val below = leaving ?: peek
        var topPlaceable: Placeable? = null
        var belowPlaceable: Placeable? = null
        for (m in measurables) {
            val tab = m.layoutId as? TopLevel ?: continue
            val shown = tab == top || tab == below
            if (!shown && tab in measured) continue
            measured += tab
            val p = m.measure(constraints)
            if (tab == top) topPlaceable = p else if (tab == below) belowPlaceable = p
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val peeking = peek != null && leaving == null
            belowPlaceable?.placeWithLayer(0, 0) {
                if (peeking) {
                    val s = BACK_PEEK_SCALE + (1f - BACK_PEEK_SCALE) * back.value
                    scaleX = s
                    scaleY = s
                } else {
                    alpha = (1f - progress.value / OUT_PART).coerceIn(0f, 1f)
                }
            }
            topPlaceable?.placeWithLayer(0, 0) {
                if (peeking) {
                    // Shrinks like a card toward the swiped edge and fades out as the gesture goes on.
                    val s = 1f - BACK_CARD_SHRINK * back.value
                    scaleX = s
                    scaleY = s
                    translationX = backDirection * shift * back.value
                    alpha = 1f - back.value
                    return@placeWithLayer
                }
                val t = FastOutSlowInEasing.transform(((progress.value - IN_DELAY) / (1f - IN_DELAY)).coerceIn(0f, 1f))
                alpha = t
                val s = IN_SCALE + (1f - IN_SCALE) * t
                scaleX = s
                scaleY = s
            }
        }
    }
}

/** One tab with its own lifecycle, ViewModel store and back handling. */
@Composable
private fun TabSlot(
    tab: TopLevel,
    active: Boolean,
    parentLifecycle: LifecycleOwner,
    store: ViewModelStore,
    backOwner: OnBackPressedDispatcherOwner?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val lifecycle = remember(parentLifecycle) { TabLifecycle(parentLifecycle) }
    DisposableEffect(lifecycle) { onDispose { lifecycle.destroy() } }
    SideEffect { lifecycle.active = active }
    val storeOwner = remember(store) { TabStoreOwner(store) }
    // The back handlers of a tab off screen go to a dispatcher nobody calls; on screen they come back on top.
    val inertBack = remember(lifecycle) { InertBackOwner(lifecycle) }
    // A state rather than a value: the covers of the tab read it only when a transition starts.
    val sharedElements = remember { mutableStateOf(active) }
    SideEffect { sharedElements.value = active }
    Box(modifier) {
        CompositionLocalProvider(
            LocalLifecycleOwner provides lifecycle,
            LocalViewModelStoreOwner provides storeOwner,
            LocalOnBackPressedDispatcherOwner provides (if (active && backOwner != null) backOwner else inertBack),
            LocalSharedElementsEnabled provides sharedElements,
            content = content,
        )
    }
}

/** The lifecycle of a tab: the destination's one, but no further than STARTED while the tab is off screen. */
internal class TabLifecycle(private val parent: LifecycleOwner) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    private var destroyed = false
    private val observer = LifecycleEventObserver { _, _ -> update() }

    init {
        parent.lifecycle.addObserver(observer)
    }

    private fun update() {
        if (destroyed) return
        val outer = parent.lifecycle.currentState
        if (outer == Lifecycle.State.DESTROYED) return destroy()
        val target = if (active) outer else minOf(outer, Lifecycle.State.STARTED)
        if (target == Lifecycle.State.INITIALIZED) return
        registry.currentState = target
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        parent.lifecycle.removeObserver(observer)
        // A registry still INITIALIZED cannot go to DESTROYED directly.
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

private class TabStoreOwner(override val viewModelStore: ViewModelStore) : ViewModelStoreOwner

private class InertBackOwner(owner: LifecycleOwner) : OnBackPressedDispatcherOwner {
    override val lifecycle: Lifecycle = owner.lifecycle
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
}

/** The ViewModel stores of the tabs, kept as long as the destination: two tabs never share a ViewModel. */
internal class TabStores : ViewModel() {
    private val stores = HashMap<TopLevel, ViewModelStore>()

    fun of(tab: TopLevel): ViewModelStore = stores.getOrPut(tab) { ViewModelStore() }

    override fun onCleared() {
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
    }
}
