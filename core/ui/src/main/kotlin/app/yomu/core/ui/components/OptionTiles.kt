package app.yomu.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yomu.core.ui.theme.Dimens

/** One choice of [OptionTiles]: an icon or a custom [preview] (drawn in the content colour of the tile) and a label. */
data class TileOption<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
    val iconRotation: Float = 0f,
    val preview: (@Composable (selected: Boolean) -> Unit)? = null,
)

/**
 * Visual single choice for a handful of options (reading mode, page size, tap zones): equal tiles in a row,
 * the selected one filled with the primary container and outlined. Replaces "tap the row, pick in a dialog".
 */
@Composable
fun <T> OptionTiles(
    options: List<TileOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
    ) {
        options.forEach { o ->
            val isSelected = o.value == selected
            val content = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(
                selected = isSelected,
                onClick = { onSelect(o.value) },
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = content,
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, Color.Transparent),
                modifier = Modifier.weight(1f).heightIn(min = 76.dp).semantics { role = Role.RadioButton },
            ) {
                Column(
                    Modifier.padding(horizontal = Dimens.xs, vertical = Dimens.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.xs, Alignment.CenterVertically),
                ) {
                    Box(Modifier.heightIn(min = 28.dp), contentAlignment = Alignment.Center) {
                        when {
                            o.preview != null -> o.preview.invoke(isSelected)
                            o.icon != null -> Icon(
                                o.icon,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp).rotate(o.iconRotation),
                            )
                        }
                    }
                    Text(
                        o.label,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A row of colour swatches with labels (reader backgrounds). */
@Composable
fun ColorSwatches(
    options: List<Triple<Long, String, Any>>,
    selected: Any,
    onSelect: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    OptionTiles(
        options = options.map { (argb, label, key) ->
            TileOption(key, label, preview = { isSelected ->
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = Color(argb),
                    border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(28.dp),
                ) {}
            })
        },
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
    )
}
