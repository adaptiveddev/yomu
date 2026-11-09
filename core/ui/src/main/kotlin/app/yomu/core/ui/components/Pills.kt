package app.yomu.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.text
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yomu.core.ui.R
import app.yomu.core.ui.theme.Dimens

/** One pill of a [PillTabRow]: [count] is drawn after the label in a lighter tone. */
data class PillTab(val key: String, val label: String, val count: Int? = null, val icon: ImageVector? = null)

/**
 * A row of rounded tabs (the library's All · Online · user tabs): the selected one is filled with the accent, the
 * others sit on a soft surface. [onLongPress] opens a tab's actions, [onAdd] draws a trailing "+" pill.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PillTabRow(
    tabs: List<PillTab>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: ((String) -> Unit)? = null,
    addLabel: String? = null,
    onAdd: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimens.l),
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val isSelected = tab.key == selected
            val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
            val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            val longPress = onLongPress?.let { { it(tab.key) } }
            val spoken = tab.count?.let { "${tab.label} ($it)" } ?: tab.label
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .combinedClickable(onClick = { onSelect(tab.key) }, onLongClick = longPress)
                    .clearAndSetSemantics {
                        text = AnnotatedString(spoken)
                        role = Role.Tab
                        this.selected = isSelected
                        onClick { onSelect(tab.key); true }
                        if (longPress != null) onLongClick { longPress(); true }
                    }
                    .padding(horizontal = Dimens.l, vertical = Dimens.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tab.icon?.let { Icon(it, null, Modifier.size(18.dp), tint = fg) }
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                tab.count?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.72f)) }
            }
        }
        if (onAdd != null) {
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .combinedClickable(onClick = onAdd)
                    .semantics { addLabel?.let { contentDescription = it }; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A rounded toolbar button with an icon and a label (Filter · Sort); [badge] > 0 shows a small counter. */
@Composable
fun ToolbarPill(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0) {
    val active = badge > 0
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Row(
            Modifier.padding(start = Dimens.m, end = if (active) Dimens.s else Dimens.l, top = Dimens.s, bottom = Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (active) {
                Box(
                    Modifier.defaultMinSize(20.dp, 20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(badge.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

/** A round tonal icon button (screen headers). */
@Composable
fun CircleIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.minimumInteractiveComponentSize().size(Dimens.touch - 4.dp).semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
    }
}

/** An active filter shown under the toolbar; tapping it removes the filter. */
@Composable
fun RemovableChip(label: String, onRemove: () -> Unit, removeLabel: String = stringResource(R.string.clear)) {
    Surface(
        onClick = onRemove,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.heightIn(min = 32.dp).semantics { contentDescription = "$label, $removeLabel" },
    ) {
        Row(Modifier.padding(start = Dimens.m, end = Dimens.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
        }
    }
}

/** A tab in [TabsPickerSheet]: on, off, or partly on (only some of the chosen items are in it). */
data class TabOption(val id: Long, val name: String, val state: ToggleableState)

/**
 * Multi-select list of the library tabs with a "New tab" row. The caller owns the selection: [onToggle] flips a tab,
 * [onCreate] makes a new one (callers usually tick it right away). [onConfirm] defaults to closing the sheet.
 */
@Composable
fun TabsPickerSheet(
    title: String,
    tabs: List<TabOption>,
    onToggle: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
) {
    var creating by remember { mutableStateOf(false) }
    YomuSheet(onDismiss = onDismiss) {
        SheetTitle(title)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.xl).padding(bottom = Dimens.s),
            )
        }
        if (tabs.isEmpty()) {
            Text(
                stringResource(R.string.tabs_none_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s),
            )
        }
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            tabs.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
                        .triStateToggleable(state = t.state, role = Role.Checkbox, onClick = { onToggle(t.id) })
                        .padding(horizontal = Dimens.l, vertical = Dimens.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TriStateCheckbox(state = t.state, onClick = null)
                    Text(t.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Dimens.m).weight(1f))
                }
            }
        }
        SheetAction(Icons.Filled.Add, stringResource(R.string.tabs_new)) { creating = true }
        Button(
            onClick = { (onConfirm ?: onDismiss)() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.xl, vertical = Dimens.s).heightIn(min = Dimens.touch),
        ) { Text(confirmLabel ?: stringResource(R.string.done)) }
    }
    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.tabs_new),
            label = stringResource(R.string.tabs_name),
            onConfirm = onCreate,
            onDismiss = { creating = false },
        )
    }
}

/** Long-press actions of a library tab: rename, move left or right, delete (with a confirmation). */
@Composable
fun TabActionsSheet(
    name: String,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onRename: (String) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    YomuSheet(onDismiss = onDismiss) {
        SheetTitle(name)
        SheetAction(Icons.Filled.Edit, stringResource(R.string.rename)) { renaming = true }
        if (canMoveLeft) SheetAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.tabs_move_left)) { onMove(-1); onDismiss() }
        if (canMoveRight) SheetAction(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.tabs_move_right)) { onMove(1); onDismiss() }
        SheetAction(Icons.Filled.Delete, stringResource(R.string.tabs_delete), destructive = true) { deleting = true }
    }
    if (renaming) {
        TextInputDialog(
            title = stringResource(R.string.rename),
            initial = name,
            label = stringResource(R.string.tabs_name),
            onConfirm = { onRename(it); onDismiss() },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.tabs_delete),
            text = stringResource(R.string.tabs_delete_text, name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { onDelete(); onDismiss() },
            onDismiss = { deleting = false },
        )
    }
}

/** Tri-state of a tab for [chosen] items of which [inTab] are already in it. */
fun tabState(inTab: Int, chosen: Int): ToggleableState = when {
    chosen <= 0 || inTab <= 0 -> ToggleableState.Off
    inTab >= chosen -> ToggleableState.On
    else -> ToggleableState.Indeterminate
}
