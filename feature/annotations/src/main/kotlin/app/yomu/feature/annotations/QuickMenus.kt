package app.yomu.feature.annotations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.yomu.core.ui.components.QuickActionMenu
import app.yomu.core.ui.components.QuickMenuItem
import app.yomu.domain.model.CustomAction
import app.yomu.domain.model.HighlightColor
import app.yomu.domain.model.HighlightStyle
import app.yomu.domain.model.PageAction
import app.yomu.domain.model.QuickMenuStyle
import app.yomu.domain.model.SelectionAction
import app.yomu.domain.model.SelectionActionConfig

/** Icon and label of the actions of the long-press menu on manga and comic pages (also used by the settings). */
fun PageAction.icon(): ImageVector = when (this) {
    PageAction.TRANSLATE -> Icons.Default.Translate
    PageAction.SAVE -> Icons.Default.SaveAlt
    PageAction.SHARE -> Icons.Default.Share
    PageAction.COPY_TEXT -> Icons.Default.ContentCopy
    PageAction.BOOKMARK -> Icons.Default.Bookmark
    PageAction.SET_COVER -> Icons.Default.Image
    PageAction.NOTE -> Icons.Default.EditNote
}

fun PageAction.label(): Int = when (this) {
    PageAction.TRANSLATE -> R.string.page_action_translate
    PageAction.SAVE -> R.string.page_action_save
    PageAction.SHARE -> R.string.page_action_share
    PageAction.COPY_TEXT -> R.string.page_action_copy_text
    PageAction.BOOKMARK -> R.string.page_action_bookmark
    PageAction.SET_COVER -> R.string.page_action_set_cover
    PageAction.NOTE -> R.string.page_action_note
}

/**
 * The menu over a text selection in the chosen [style]: the toolbar with colours (BAR, also for OFF), or the long-press
 * menu as a list or a ring around the selection.
 */
@Composable
fun SelectionMenu(
    style: QuickMenuStyle,
    anchor: Rect,
    actions: List<SelectionActionConfig>,
    customActions: List<CustomAction>,
    currentColor: HighlightColor?,
    onHighlight: (HighlightColor, HighlightStyle) -> Unit,
    onAction: (SelectionAction) -> Unit,
    onCustom: (CustomAction) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    if (style == QuickMenuStyle.BAR || style == QuickMenuStyle.OFF) {
        SelectionToolbar(anchor, actions, customActions, currentColor, onHighlight, onAction, onCustom, onDelete, onDismiss)
        return
    }
    val items = buildList {
        actions.filter { it.enabled && it.action != SelectionAction.CUSTOM }.forEach { cfg ->
            val a = cfg.action
            val tint = when (a) {
                SelectionAction.HIGHLIGHT -> Color((currentColor ?: HighlightColor.YELLOW).argb)
                else -> null
            }
            add(
                QuickMenuItem(a.name, a.icon(), stringResource(a.label()), tint) {
                    when (a) {
                        SelectionAction.HIGHLIGHT -> onHighlight(currentColor ?: HighlightColor.YELLOW, HighlightStyle.HIGHLIGHT)
                        SelectionAction.UNDERLINE -> onHighlight(currentColor ?: HighlightColor.BLUE, HighlightStyle.UNDERLINE)
                        SelectionAction.STRIKE -> onHighlight(currentColor ?: HighlightColor.PINK, HighlightStyle.STRIKETHROUGH)
                        else -> onAction(a)
                    }
                },
            )
        }
        if (actions.any { it.enabled && it.action == SelectionAction.CUSTOM }) {
            customActions.forEach { ca -> add(QuickMenuItem("custom:${ca.id}", Icons.AutoMirrored.Filled.OpenInNew, ca.label) { onCustom(ca) }) }
        }
        if (onDelete != null) add(QuickMenuItem("delete", Icons.Default.Delete, stringResource(R.string.action_delete_highlight)) { onDelete() })
    }
    // A selected item closes the menu without clearing the selection (the action decides); a tap outside clears it.
    QuickActionMenu(
        radial = style == QuickMenuStyle.RADIAL,
        anchor = Offset(anchor.center.x, anchor.bottom),
        items = items,
        onDismiss = onDismiss,
    )
}
