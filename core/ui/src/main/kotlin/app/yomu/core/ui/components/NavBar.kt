package app.yomu.core.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import app.yomu.core.ui.R
import app.yomu.core.ui.theme.Dimens
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** One destination of the navigation bar or rail; [badge] puts a small dot on its icon (an update in Settings). */
@Immutable
data class NavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val badge: Boolean = false)

/**
 * The top-level sections in their default order. In Settings → Navigation bar the user can reorder them and hide all
 * but [SETTINGS], so the settings (and with them the way back) always stay reachable.
 */
enum class NavTab(@StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    LIBRARY(R.string.nav_library, Icons.Outlined.CollectionsBookmark, Icons.Filled.CollectionsBookmark),
    /** The profile since 1.8.0 (statistics and history are its tabs); the name stays for stored tab orders. */
    HISTORY(R.string.nav_history, Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
    CATALOG(R.string.nav_catalog, Icons.Outlined.Explore, Icons.Filled.Explore),
    DICTIONARY(R.string.nav_dictionary, Icons.Outlined.Translate, Icons.Filled.Translate),
    SETTINGS(R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
    ;

    val canHide: Boolean get() = this != SETTINGS

    companion object {
        /** All tabs in the stored order: unknown names are dropped, tabs missing there keep their default place at the end. */
        fun ordered(order: List<String>): List<NavTab> {
            val stored = order.mapNotNull { name -> entries.firstOrNull { it.name == name } }.distinct()
            return stored + entries.filter { it !in stored }
        }

        /** The tabs shown in the bar, in order. Never empty: [SETTINGS] cannot be hidden. */
        fun visible(order: List<String>, hidden: Set<String>): List<NavTab> = ordered(order).filter { !it.canHide || it.name !in hidden }
    }
}

@Composable
fun NavTab.item(): NavItem = NavItem(stringResource(label), icon, selectedIcon)

/**
 * Motion of the bar: a soft spring without visible bounce. Unlike a fixed-length tween it keeps its speed when the
 * next tap interrupts it, so quick taps on several tabs flow into each other instead of jumping.
 */
private val NavBarSpring: AnimationSpec<Float> = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow * 1.6f)

/**
 * A floating pill above the system navigation. Its box is transparent: on the top-level screens the content runs on
 * under it (see [LocalNavBarInset]) and touches beside the pill reach that content. The selected destination is a lavender capsule with its icon and name
 * that slides and stretches to the tapped one while the neighbours make room; [showLabels] keeps the names under every
 * icon instead and only the small indicator behind the icon slides.
 *
 * Everything moves in the layout and draw phases (no recomposition per frame), so the motion stays smooth even while
 * the screen below is busy. [onPress] comes as soon as a finger is down on an item, before [onSelect] on release: the
 * app uses that moment to compose the tab (1.9.0).
 */
@Composable
fun FloatingNavBar(
    items: List<NavItem>,
    selected: Int,
    showLabels: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
    onPress: (Int) -> Unit = {},
) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(windowInsets)
            .padding(start = Dimens.l, end = Dimens.l, top = Dimens.xs, bottom = Dimens.m),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
        ) {
            NavBarItems(
                items = items,
                selected = selected,
                showLabels = showLabels,
                onSelect = onSelect,
                onPress = onPress,
                modifier = Modifier.height(FloatingNavBarDefaults.pillHeight(showLabels)).padding(6.dp),
            )
        }
    }
}

@Composable
private fun NavBarItems(items: List<NavItem>, selected: Int, showLabels: Boolean, onSelect: (Int) -> Unit, onPress: (Int) -> Unit, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    val interactions = rememberPressReports(items.size, onPress)
    val haptic = rememberHaptic()
    val colors = MaterialTheme.colorScheme
    // How "selected" each item is, from 0 to 1: its width, the indicator, the icon and the name all follow this value.
    val progress = remember(items.map { it.label }) { List(items.size) { i -> Animatable(if (i == selected) 1f else 0f) } }
    LaunchedEffect(progress, selected, reduce) {
        progress.fastForEachIndexed { i, a ->
            val target = if (i == selected) 1f else 0f
            launch { if (reduce) a.snapTo(target) else a.animateTo(target, NavBarSpring) }
        }
    }
    val e: (Int) -> Float = { i -> progress[i].value.coerceIn(0f, 1f) }
    val badgeText = stringResource(R.string.nav_badge)
    val labelStyle = if (showLabels) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge
    val iconSize = if (showLabels) 22.dp else 24.dp

    Layout(
        contents = listOf(
            // 0: touch targets with the ripple, one per item, as wide as the item; under everything else
            {
                items.forEachIndexed { i, item ->
                    val isSelected = i == selected
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .selectable(
                                selected = isSelected,
                                onClick = { if (!isSelected) haptic.perform(); onSelect(i) },
                                role = Role.Tab,
                                interactionSource = interactions[i],
                                indication = ripple(),
                            )
                            .semantics { text = AnnotatedString(if (item.badge) "${item.label}, $badgeText" else item.label) },
                    )
                }
            },
            // 1: outlined icons, under the capsule: it covers them while it slides past instead of washing them out
            {
                items.forEachIndexed { i, item ->
                    Icon(
                        item.icon,
                        null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(iconSize).clearAndSetSemantics {}.graphicsLayer { alpha = 1f - e(i) }
                            .then(if (item.badge) Modifier.badgeDot(colors.error, colors.surfaceContainerHigh) else Modifier),
                    )
                }
            },
            // 2: the sliding indicator
            { Box(Modifier.clip(CircleShape).background(colors.primary)) },
            // 3: filled icons on the capsule; each fades in as its item becomes selected
            {
                items.forEachIndexed { i, item ->
                    Icon(
                        item.selectedIcon,
                        null,
                        tint = colors.onPrimary,
                        modifier = Modifier.size(iconSize).clearAndSetSemantics {}.graphicsLayer { alpha = e(i) }
                            .then(if (item.badge) Modifier.badgeDot(colors.error, colors.primary) else Modifier),
                    )
                }
            },
            // 4: names
            {
                items.forEachIndexed { i, item ->
                    if (showLabels) {
                        Text(
                            item.label,
                            style = labelStyle,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clearAndSetSemantics {}.graphicsLayer { alpha = 0.72f + 0.28f * e(i) },
                        )
                    } else {
                        // Clipped to the part of the name the opening capsule has room for; the text keeps its layout.
                        Box(Modifier.clipToBounds().clearAndSetSemantics {}.graphicsLayer { alpha = ((e(i) - 0.3f) / 0.7f).coerceIn(0f, 1f) }) {
                            Text(
                                item.label,
                                style = labelStyle,
                                color = colors.onPrimary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                            )
                        }
                    }
                }
            },
        ),
        modifier = modifier,
    ) { (targets, icons, indicator, filledIcons, labels), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val n = items.size
        if (n == 0 || width <= 0) return@Layout layout(width, height) {}
        val fractions = FloatArray(n) { e(it) }
        val total = fractions.sum()

        val slotLeft = FloatArray(n)
        val slotWidth = FloatArray(n)
        val iconPlaceables = arrayOfNulls<Placeable>(n)
        val filledPlaceables = arrayOfNulls<Placeable>(n)
        val labelPlaceables = arrayOfNulls<Placeable>(n)
        val iconX = IntArray(n)
        val iconY = IntArray(n)
        val labelX = IntArray(n)
        val labelY = IntArray(n)
        var indicatorLeft = 0f
        var indicatorTop = 0
        var indicatorWidth = 0f
        val indicatorHeight: Int

        if (showLabels) {
            // Equal slots; a 56×30 pill behind the icon, the name under it.
            val slot = width.toFloat() / n
            val pillW = minOf(56.dp.toPx(), slot - 4.dp.toPx())
            val pillH = 30.dp.roundToPx()
            indicatorHeight = pillH
            for (i in 0 until n) {
                slotLeft[i] = slot * i
                slotWidth[i] = slot
                val icon = icons[i].measure(Constraints())
                val label = labels[i].measure(Constraints(maxWidth = (slot - 4.dp.toPx()).roundToInt().coerceAtLeast(0)))
                iconPlaceables[i] = icon
                filledPlaceables[i] = filledIcons[i].measure(Constraints())
                labelPlaceables[i] = label
                val gap = 2.dp.roundToPx()
                val top = (height - (pillH + gap + label.height)) / 2
                indicatorTop = top
                iconX[i] = (slotLeft[i] + (slot - icon.width) / 2f).roundToInt()
                iconY[i] = top + (pillH - icon.height) / 2
                labelX[i] = (slotLeft[i] + (slot - label.width) / 2f).roundToInt()
                labelY[i] = top + pillH + gap
            }
            if (total > 0f) {
                var center = 0f
                for (i in 0 until n) center += fractions[i] * (slotLeft[i] + slotWidth[i] / 2f)
                center /= total
                indicatorLeft = center - pillW / 2f
                indicatorWidth = pillW
            }
        } else {
            // The selected item is as wide as its capsule (padding, icon, gap, name); the others share the rest.
            // While two items trade places, each one's extra width follows its progress, so the widths always add up.
            val padH = 16.dp.toPx()
            val gap = 8.dp.toPx()
            val minSlot = 44.dp.toPx()
            val base = width.toFloat() / n
            val maxCapsule = width - (n - 1) * minSlot
            val labelRoom = FloatArray(n)
            val extraFull = FloatArray(n)
            for (i in 0 until n) {
                val icon = icons[i].measure(Constraints())
                iconPlaceables[i] = icon
                filledPlaceables[i] = filledIcons[i].measure(Constraints())
                val natural = labels[i].maxIntrinsicWidth(height).toFloat()
                val capsule = minOf(2 * padH + icon.width + gap + natural, maxCapsule).coerceAtLeast(icon.width.toFloat())
                labelRoom[i] = (capsule - 2 * padH - icon.width - gap).coerceAtLeast(0f)
                extraFull[i] = if (n > 1) ((capsule - base) * n / (n - 1)).coerceAtLeast(0f) else 0f
            }
            var extraSum = 0f
            for (i in 0 until n) extraSum += fractions[i] * extraFull[i]
            val share = (width - extraSum) / n
            var x = 0f
            for (i in 0 until n) {
                slotLeft[i] = x
                slotWidth[i] = share + fractions[i] * extraFull[i]
                x += slotWidth[i]
                val icon = iconPlaceables[i]!!
                val f = fractions[i]
                val visible = (labelRoom[i] * f).roundToInt()
                val label = labels[i].measure(Constraints.fixedWidth(visible).copy(minHeight = 0, maxHeight = height))
                labelPlaceables[i] = label
                val content = icon.width + f * (gap + labelRoom[i])
                val start = slotLeft[i] + (slotWidth[i] - content) / 2f
                iconX[i] = start.roundToInt()
                iconY[i] = (height - icon.height) / 2
                labelX[i] = (start + icon.width + f * gap).roundToInt()
                labelY[i] = (height - label.height) / 2
            }
            indicatorHeight = height
            if (total > 0f) {
                for (i in 0 until n) {
                    indicatorLeft += fractions[i] * slotLeft[i]
                    indicatorWidth += fractions[i] * slotWidth[i]
                }
                indicatorLeft /= total
                indicatorWidth /= total
            }
        }

        val indicatorPlaceable = indicator.firstOrNull()?.measure(Constraints.fixed(indicatorWidth.roundToInt().coerceAtLeast(0), indicatorHeight))
        val targetPlaceables = targets.mapIndexed { i, m: Measurable ->
            val left = slotLeft[i].roundToInt()
            val right = (slotLeft[i] + slotWidth[i]).roundToInt()
            m.measure(Constraints.fixed((right - left).coerceAtLeast(0), height))
        }
        // Drawn in placing order: ripples, outlined icons, the capsule, then what sits on the capsule.
        layout(width, height) {
            targetPlaceables.forEachIndexed { i, p -> p.place(slotLeft[i].roundToInt(), 0) }
            for (i in 0 until n) iconPlaceables[i]?.place(iconX[i], iconY[i])
            indicatorPlaceable?.placeWithLayer(indicatorLeft.roundToInt(), indicatorTop) { alpha = total.coerceIn(0f, 1f) }
            for (i in 0 until n) {
                filledPlaceables[i]?.place(iconX[i], iconY[i])
                labelPlaceables[i]?.place(labelX[i], labelY[i])
            }
        }
    }
}

/** A dot on the icon's top end corner, ringed in the colour under it so it stands off the glyph. */
private fun Modifier.badgeDot(dot: Color, ring: Color): Modifier = drawWithContent {
    drawContent()
    val r = 3.dp.toPx()
    val center = Offset(size.width - r, r)
    drawCircle(ring, r + 1.5.dp.toPx(), center)
    drawCircle(dot, r, center)
}

/**
 * One interaction source per item that reports a press to [onPress] as soon as it starts (a finger down), before the
 * click that follows on release.
 */
@Composable
private fun rememberPressReports(count: Int, onPress: (Int) -> Unit): List<MutableInteractionSource> {
    val sources = remember(count) { List(count) { MutableInteractionSource() } }
    val report by rememberUpdatedState(onPress)
    LaunchedEffect(sources) {
        sources.forEachIndexed { i, source ->
            launch { source.interactions.collect { if (it is PressInteraction.Press) report(i) } }
        }
    }
    return sources
}

/** Rail for wide windows, in the same colours as the floating bar. */
@Composable
fun YomuNavRail(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, onPress: (Int) -> Unit = {}) {
    val interactions = rememberPressReports(items.size, onPress)
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxHeight()) {
        Spacer(Modifier.height(Dimens.m))
        items.forEachIndexed { index, item ->
            val isSelected = index == selected
            NavigationRailItem(
                selected = isSelected,
                onClick = { onSelect(index) },
                interactionSource = interactions[index],
                icon = {
                    BadgedBox(badge = { if (item.badge) Badge() }) { Icon(if (isSelected) item.selectedIcon else item.icon, contentDescription = null) }
                },
                label = { Text(item.label) },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}
