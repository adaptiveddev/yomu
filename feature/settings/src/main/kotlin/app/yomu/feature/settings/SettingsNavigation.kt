package app.yomu.feature.settings

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.components.NavTab
import app.yomu.core.ui.components.PreferenceHeader
import app.yomu.core.ui.theme.Dimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val TabRowHeight = 64.dp
private val LiftedShape = RoundedCornerShape(18.dp)

/** The order of the tab rows while one of them is dragged. */
@Stable
private class TabDragState(initial: List<NavTab>, private val rowPx: Float, private val scope: CoroutineScope) {
    var tabs by mutableStateOf(initial)
    var dragged by mutableStateOf<NavTab?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    fun move(tab: NavTab, by: Int): Boolean {
        val i = tabs.indexOf(tab)
        val j = i + by
        if (i < 0 || j !in tabs.indices) return false
        tabs = tabs.toMutableList().apply { add(j, removeAt(i)) }
        return true
    }

    fun start(tab: NavTab) {
        dragged = tab
        offset = 0f
    }

    fun drag(tab: NavTab, dy: Float) {
        if (dragged != tab) return
        offset += dy
        // Past half a row the tab trades places with its neighbour; the offset is corrected so it stays under the finger.
        if (offset > rowPx / 2 && move(tab, 1)) offset -= rowPx
        else if (offset < -rowPx / 2 && move(tab, -1)) offset += rowPx
    }

    /** Lets the row glide into its slot; returns whether a drag ended (and the order should be saved). */
    fun end(): Boolean {
        if (dragged == null) return false
        val from = offset
        scope.launch {
            animate(from, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)) { v, _ -> offset = v }
            dragged = null
        }
        return true
    }
}

/**
 * Settings → Navigation bar: names under all icons, and the tabs themselves: drag a tab by its handle (or after a long
 * press anywhere on the row) to move it, switch it off to hide it. Settings can be moved but not hidden. The bar at the
 * bottom of the screen shows every change at once.
 */
@Composable
internal fun NavigationSection(vm: SettingsViewModel) {
    val s = vm.settings
    val order by s.navTabOrder.state()
    val hidden by s.navTabsHidden.state()
    val rowPx = with(LocalDensity.current) { TabRowHeight.toPx() }
    val scope = rememberCoroutineScope()
    val drag = remember(rowPx, scope) { TabDragState(NavTab.ordered(order), rowPx, scope) }
    LaunchedEffect(order) { if (drag.dragged == null) drag.tabs = NavTab.ordered(order) }
    val save: () -> Unit = { vm.set(s.navTabOrder, drag.tabs.map { it.name }) }
    val firstShown = drag.tabs.firstOrNull { !it.canHide || it.name !in hidden }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
        item(key = "labels") { SwitchPref(vm, s.navLabels, R.string.set_nav_labels, R.string.set_nav_labels_sum) }
        item(key = "tabs-h") { PreferenceHeader(stringResource(R.string.set_nav_tabs)) }
        item(key = "tabs-hint") {
            Text(
                stringResource(R.string.set_nav_tabs_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.l, end = Dimens.l, bottom = Dimens.s),
            )
        }
        items(drag.tabs, key = { it.name }) { tab ->
            val isDragged = drag.dragged == tab
            val shownInBar = !tab.canHide || tab.name !in hidden
            val lift by animateFloatAsState(if (isDragged) 1f else 0f, label = "lift")
            val moveUp = stringResource(R.string.set_move_up)
            val moveDown = stringResource(R.string.set_move_down)
            NavTabRow(
                tab = tab,
                shown = shownInBar,
                note = when {
                    tab == firstShown -> stringResource(R.string.set_nav_start)
                    !tab.canHide -> stringResource(R.string.set_nav_always)
                    !shownInBar -> stringResource(R.string.set_nav_hidden)
                    else -> null
                },
                onShownChange = { on -> vm.update(s.navTabsHidden) { if (on) it - tab.name else it + tab.name } },
                handle = Modifier.pointerInput(tab, drag) {
                    detectDragGestures(
                        onDragStart = { drag.start(tab) },
                        onDrag = { change, amount -> change.consume(); drag.drag(tab, amount.y) },
                        onDragEnd = { if (drag.end()) save() },
                        onDragCancel = { if (drag.end()) save() },
                    )
                },
                modifier = Modifier
                    .then(if (isDragged) Modifier.zIndex(1f) else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                    .graphicsLayer {
                        translationY = if (isDragged) drag.offset else 0f
                        shadowElevation = lift * 6.dp.toPx()
                        shape = LiftedShape
                        clip = lift > 0f
                    }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = lift), LiftedShape)
                    .pointerInput(tab, drag) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { drag.start(tab) },
                            onDrag = { change, amount -> change.consume(); drag.drag(tab, amount.y) },
                            onDragEnd = { if (drag.end()) save() },
                            onDragCancel = { if (drag.end()) save() },
                        )
                    }
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(moveUp) { drag.move(tab, -1).also { if (it) save() } },
                            CustomAccessibilityAction(moveDown) { drag.move(tab, 1).also { if (it) save() } },
                        )
                    },
            )
        }
        item(key = "reset") {
            PreferenceItem(
                stringResource(R.string.set_nav_reset),
                icon = Icons.Outlined.RestartAlt,
                onClick = {
                    vm.set(s.navTabOrder, emptyList())
                    vm.set(s.navTabsHidden, emptySet())
                },
            )
        }
    }
}

@Composable
private fun NavTabRow(
    tab: NavTab,
    shown: Boolean,
    note: String?,
    onShownChange: (Boolean) -> Unit,
    handle: Modifier,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dim = if (shown) 1f else 0.55f
    Row(
        modifier.fillMaxWidth().height(TabRowHeight).padding(start = Dimens.xs, end = Dimens.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.m),
    ) {
        Box(handle.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.DragIndicator, stringResource(R.string.set_nav_drag), tint = colors.onSurfaceVariant)
        }
        Box(
            Modifier.size(36.dp).graphicsLayer { alpha = dim }.clip(CircleShape).background(colors.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tab.selectedIcon, null, Modifier.size(20.dp), tint = colors.primary)
        }
        Column(Modifier.weight(1f).graphicsLayer { alpha = dim }) {
            Text(stringResource(tab.label), style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Switch(checked = shown, onCheckedChange = onShownChange, enabled = tab.canHide)
    }
}
