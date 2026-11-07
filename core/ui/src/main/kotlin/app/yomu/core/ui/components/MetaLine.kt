package app.yomu.core.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/** A fact with a small leading icon, shown by [MetaLine]. */
data class MetaItem(val icon: ImageVector, val text: String)

/**
 * Small facts with leading icons on one line ("[calendar] 12.05.25   [group] Alpha"), shortened as a whole with an
 * ellipsis. An icon stays with its text when the line wraps ([maxLines] > 1).
 */
@Composable
fun MetaLine(
    items: List<MetaItem>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconTint: Color = color,
    maxLines: Int = 1,
) {
    if (items.isEmpty()) return
    val text = remember(items) {
        buildAnnotatedString {
            items.forEachIndexed { i, item ->
                if (i > 0) append("   ")
                appendInlineContent("icon$i", "·")
                append('\u00A0')
                // Non-breaking spaces keep short facts ("Page 12") in one piece.
                append(if (item.text.length <= 24) item.text.replace(' ', '\u00A0') else item.text)
            }
        }
    }
    val size = (if (style.fontSize.isSpecified) style.fontSize else 12.sp) * 1.15f
    val inline = items.withIndex().associate { (i, item) ->
        "icon$i" to InlineTextContent(Placeholder(size, size, PlaceholderVerticalAlign.TextCenter)) {
            Icon(item.icon, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = iconTint)
        }
    }
    Text(text, modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, inlineContent = inline)
}
