package app.yomu.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A tab with a readable unselected state: Material's [Tab] paints selected and unselected labels in the same colour,
 * so only the thin indicator tells them apart.
 */
@Composable
fun YomuTab(
    selected: Boolean,
    onClick: () -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        text = text,
        icon = icon,
        selectedContentColor = MaterialTheme.colorScheme.primary,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
