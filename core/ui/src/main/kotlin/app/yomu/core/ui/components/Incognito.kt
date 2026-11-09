package app.yomu.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yomu.core.ui.R
import app.yomu.core.ui.theme.Dimens

/**
 * The strip shown above every screen while incognito mode is on. It covers the status bar itself, so the screen under
 * it must consume the status bar insets.
 */
@Composable
fun IncognitoBanner(onTurnOff: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.statusBarsPadding().heightIn(min = 36.dp).padding(start = Dimens.l, end = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.incognito_banner),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onTurnOff, contentPadding = PaddingValues(horizontal = Dimens.s)) {
                Text(stringResource(R.string.incognito_turn_off), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
