package app.yomu.core.ui.components

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Extended floating action button with a label that screen readers announce. Material's
 * [ExtendedFloatingActionButton] hides its text from accessibility services and relies on the icon's description, so
 * the label is used for both.
 */
@Composable
fun YomuFab(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
) {
    ExtendedFloatingActionButton(
        text = { Text(text) },
        icon = { Icon(icon, contentDescription = text) },
        onClick = onClick,
        modifier = modifier,
        expanded = expanded,
    )
}
