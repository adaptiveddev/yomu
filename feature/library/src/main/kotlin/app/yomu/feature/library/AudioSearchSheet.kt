package app.yomu.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.ErrorState
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.YomuSheet
import app.yomu.data.audio.YouTubeResult
import coil3.compose.AsyncImage

/** YouTube search for words typed in "Audiobook by link": the chosen video or playlist becomes an audiobook. */
@Composable
internal fun AudioSearchSheet(state: AudioSearch, onPick: (YouTubeResult) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    YomuSheet(onDismiss = onDismiss) {
        SheetTitle(stringResource(R.string.library_audio_search_title, state.query))
        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> ErrorState(state.error, Modifier.fillMaxWidth().heightIn(min = 200.dp), onRetry = onRetry)
            state.results.isEmpty() -> EmptyState(stringResource(R.string.library_audio_search_empty), Modifier.fillMaxWidth().heightIn(min = 200.dp))
            else -> LazyColumn(Modifier.heightIn(max = 560.dp)) {
                items(state.results, key = { it.id }) { r -> ResultRow(r) { onPick(r) } }
            }
        }
    }
}

@Composable
private fun ResultRow(r: YouTubeResult, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = r.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(112.dp).height(63.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val kind = if (r.isPlaylist) stringResource(R.string.library_audio_playlist) else null
            val size = if (r.isPlaylist) r.videoCount?.let { pluralStringResource(R.plurals.library_audio_videos, it, it) } else length(r.durationMs)
            val meta = listOfNotNull(r.author, kind, size).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "1:02:03" or "4:05"; null when unknown. */
private fun length(ms: Long): String? {
    if (ms <= 0) return null
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, t / 3600, t % 3600 / 60, t % 60) else "%d:%02d".format(java.util.Locale.ROOT, t / 60, t % 60)
}
