package app.yomu.feature.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A thin bar under the status bar while an audiobook is loaded, like the incognito banner: tap opens the player,
 * the buttons rewind, play/pause and stop.
 */
@Composable
fun NowPlayingBar(
    state: PlayerSnapshot,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onRewind: () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).statusBarsPadding()
            .clickable(onClick = onOpen).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Column(Modifier.weight(1f)) {
            Text(state.bookTitle, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(state.trackTitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        IconButton(onClick = onRewind) { Icon(Icons.Default.Replay, stringResource(R.string.audio_rewind), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        IconButton(onClick = onToggle) {
            if (state.playWhenReady) Icon(Icons.Default.Pause, stringResource(R.string.audio_pause), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            else Icon(Icons.Default.PlayArrow, stringResource(R.string.audio_play), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        IconButton(onClick = onStop) { Icon(Icons.Default.Close, stringResource(R.string.audio_stop), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
    }
}
