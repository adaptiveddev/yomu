package app.yomu.feature.annotations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.yomu.domain.model.CustomAction
import app.yomu.domain.model.HighlightColor
import app.yomu.domain.model.HighlightStyle
import app.yomu.domain.model.SelectionAction
import app.yomu.domain.model.SelectionActionConfig

/** Icon and label of every selection action (also used by the settings screen to reorder them). */
fun SelectionAction.icon(): ImageVector = when (this) {
    SelectionAction.HIGHLIGHT -> Icons.Default.BorderColor
    SelectionAction.UNDERLINE -> Icons.Default.FormatUnderlined
    SelectionAction.STRIKE -> Icons.Default.FormatStrikethrough
    SelectionAction.NOTE -> Icons.Default.EditNote
    SelectionAction.QUOTE -> Icons.Default.FormatQuote
    SelectionAction.MEANING -> Icons.AutoMirrored.Filled.MenuBook
    SelectionAction.TRANSLATE -> Icons.Default.Translate
    SelectionAction.SAVE_WORD -> Icons.Default.StarOutline
    SelectionAction.COPY -> Icons.Default.ContentCopy
    SelectionAction.SHARE -> Icons.Default.Share
    SelectionAction.SHARE_IMAGE -> Icons.Default.Image
    SelectionAction.SEARCH_BOOK -> Icons.AutoMirrored.Filled.ManageSearch
    SelectionAction.SEARCH_WEB -> Icons.Default.Public
    SelectionAction.SPEAK -> Icons.AutoMirrored.Filled.VolumeUp
    SelectionAction.SELECT_SENTENCE -> Icons.AutoMirrored.Filled.ShortText
    SelectionAction.SELECT_PARAGRAPH -> Icons.AutoMirrored.Filled.Subject
    SelectionAction.BOOKMARK -> Icons.Default.Bookmark
    SelectionAction.CUSTOM -> Icons.AutoMirrored.Filled.OpenInNew
}

fun SelectionAction.label(): Int = when (this) {
    SelectionAction.HIGHLIGHT -> R.string.action_highlight
    SelectionAction.UNDERLINE -> R.string.action_underline
    SelectionAction.STRIKE -> R.string.action_strike
    SelectionAction.NOTE -> R.string.action_note
    SelectionAction.QUOTE -> R.string.action_quote
    SelectionAction.MEANING -> R.string.action_meaning
    SelectionAction.TRANSLATE -> R.string.action_translate
    SelectionAction.SAVE_WORD -> R.string.action_save_word
    SelectionAction.COPY -> R.string.action_copy
    SelectionAction.SHARE -> R.string.action_share
    SelectionAction.SHARE_IMAGE -> R.string.action_share_image
    SelectionAction.SEARCH_BOOK -> R.string.action_search_book
    SelectionAction.SEARCH_WEB -> R.string.action_search_web
    SelectionAction.SPEAK -> R.string.action_speak
    SelectionAction.SELECT_SENTENCE -> R.string.action_sentence
    SelectionAction.SELECT_PARAGRAPH -> R.string.action_paragraph
    SelectionAction.BOOKMARK -> R.string.action_bookmark
    SelectionAction.CUSTOM -> R.string.action_custom
}

/** Places the toolbar above the anchor, or below it when there is no room. */
private class AboveAnchor(private val anchor: Rect, private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchor.center.x - popupContentSize.width / 2f).toInt().coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchor.top.toInt() - popupContentSize.height - marginPx
        val y = if (above > marginPx * 4) above else (anchor.bottom.toInt() + marginPx * 3).coerceAtMost(windowSize.height - popupContentSize.height)
        return IntOffset(x, y)
    }
}

/**
 * Floating toolbar shown over a text selection. The first row offers the highlight colours, the second
 * row the configured actions in the user's order.
 */
@Composable
fun SelectionToolbar(
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
    val provider = remember(anchor) { AboveAnchor(anchor, 12) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 8.dp, modifier = Modifier.widthIn(max = 420.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HighlightColor.entries.forEach { c ->
                        val name = stringResource(c.label())
                        Box(
                            Modifier.size(32.dp).padding(2.dp).background(Color(c.argb), CircleShape)
                                .then(if (c == currentColor) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { onHighlight(c, HighlightStyle.HIGHLIGHT) }
                                .semantics { contentDescription = name },
                        )
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete_highlight)) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                    actions.filter { it.enabled && it.action != SelectionAction.CUSTOM }.forEach { cfg ->
                        val label = stringResource(cfg.action.label())
                        IconButton(onClick = {
                            when (cfg.action) {
                                SelectionAction.HIGHLIGHT -> onHighlight(currentColor ?: HighlightColor.YELLOW, HighlightStyle.HIGHLIGHT)
                                SelectionAction.UNDERLINE -> onHighlight(currentColor ?: HighlightColor.BLUE, HighlightStyle.UNDERLINE)
                                SelectionAction.STRIKE -> onHighlight(currentColor ?: HighlightColor.PINK, HighlightStyle.STRIKETHROUGH)
                                else -> onAction(cfg.action)
                            }
                        }) { Icon(cfg.action.icon(), label) }
                    }
                    if (actions.any { it.enabled && it.action == SelectionAction.CUSTOM }) {
                        customActions.forEach { ca ->
                            Surface(
                                onClick = { onCustom(ca) },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.padding(6.dp),
                            ) { Text(ca.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) }
                        }
                    }
                }
            }
        }
    }
}

fun HighlightColor.label(): Int = when (this) {
    HighlightColor.YELLOW -> R.string.color_yellow
    HighlightColor.GREEN -> R.string.color_green
    HighlightColor.BLUE -> R.string.color_blue
    HighlightColor.PINK -> R.string.color_pink
    HighlightColor.PURPLE -> R.string.color_purple
}

