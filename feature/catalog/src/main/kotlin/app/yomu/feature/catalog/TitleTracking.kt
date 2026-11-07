package app.yomu.feature.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.Share
import app.yomu.core.database.TrackEntity
import app.yomu.core.ui.components.CoverImage
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.TextInputDialog
import app.yomu.core.ui.components.YomuSheet
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.tracking.TrackerManager
import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import app.yomu.core.ui.R as CoreR

/** The "link a title" search of one service. */
data class TrackSearchState(
    val service: TrackService,
    val query: String,
    val loading: Boolean = false,
    val results: List<TrackSearchResult>? = null,
    val error: Throwable? = null,
)

/** Shared editor for online titles and local books; the host owns persistence and errors. */
interface TrackingViewModel {
    val tracks: StateFlow<List<TrackEntity>>
    val trackerVersion: StateFlow<Int>
    val trackSearch: StateFlow<TrackSearchState?>
    val localFile: Boolean get() = false
    fun trackerSignedIn(service: TrackService): Boolean
    fun refreshTracks(): Job
    fun openTrackSearch(service: TrackService)
    fun closeTrackSearch()
    fun searchTrack(query: String)
    fun bindTrack(result: TrackSearchResult): Job
    fun updateTrack(track: TrackEntity, transform: (TrackEntry) -> TrackEntry): Job
    fun unbindTrack(track: TrackEntity, removeRemote: Boolean): Job
}

/** "Tracking" button of the title page: the number of linked services when there are any. */
@Composable
fun TrackingButton(count: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Outlined.TrackChanges, null, Modifier.size(18.dp))
        Text(if (count > 0) stringResource(R.string.track_button_n, count) else stringResource(R.string.track_button), Modifier.padding(start = Dimens.s))
    }
}

/** Name of the status in the user's list (also the profile's Tracking tab). */
@Composable
fun TrackStatus.label(): String = stringResource(
    when (this) {
        TrackStatus.READING -> R.string.track_status_reading
        TrackStatus.PLAN_TO_READ -> R.string.track_status_plan
        TrackStatus.COMPLETED -> R.string.track_status_completed
        TrackStatus.ON_HOLD -> R.string.track_status_on_hold
        TrackStatus.DROPPED -> R.string.track_status_dropped
        TrackStatus.REREADING -> R.string.track_status_rereading
    },
)

/** The title's entries on every tracking service: link, edit status, chapters and score, unlink. */
@Composable
fun TrackingSheet(viewModel: TrackingViewModel, onDismiss: () -> Unit) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val version by viewModel.trackerVersion.collectAsStateWithLifecycle()
    val search by viewModel.trackSearch.collectAsStateWithLifecycle()
    var unlink by remember { mutableStateOf<TrackEntity?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshTracks() }

    YomuSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Dimens.l)) {
            SheetTitle(stringResource(R.string.track_title))
            if (viewModel.localFile) Text(
                stringResource(R.string.track_local_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s),
            )
            var anySignedIn = false
            TrackService.entries.forEach { service ->
                val signedIn = remember(version, service) { viewModel.trackerSignedIn(service) }
                if (signedIn) anySignedIn = true
                val track = tracks.firstOrNull { it.service == service.id }
                if (track != null) {
                    TrackCard(service, track, signedIn, viewModel, onUnlink = { unlink = track })
                } else {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.touch).padding(horizontal = Dimens.xl),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(service.displayName, style = MaterialTheme.typography.titleSmall)
                            if (!signedIn) {
                                Text(stringResource(R.string.track_signed_out), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        FilledTonalButton(onClick = { viewModel.openTrackSearch(service) }, enabled = signedIn) { Text(stringResource(R.string.track_link)) }
                    }
                }
            }
            if (!anySignedIn) {
                Text(
                    stringResource(R.string.track_sign_in_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s),
                )
            }
        }
    }

    search?.let { TrackSearchDialog(it, viewModel) }
    unlink?.let { track ->
        val service = TrackService.of(track.service)
        var remote by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { unlink = null },
            title = { Text(stringResource(R.string.track_unlink)) },
            text = {
                Column {
                    Text(stringResource(R.string.track_unlink_text, service?.displayName.orEmpty()))
                    if (service != null && viewModel.trackerSignedIn(service)) {
                        Row(Modifier.fillMaxWidth().clickable { remote = !remote }.padding(top = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = remote, onCheckedChange = { remote = it })
                            Text(stringResource(R.string.track_unlink_remote, service.displayName))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.unbindTrack(track, remote); unlink = null }) { Text(stringResource(R.string.track_unlink)) } },
            dismissButton = { TextButton(onClick = { unlink = null }) { Text(stringResource(CoreR.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackCard(service: TrackService, track: TrackEntity, signedIn: Boolean, viewModel: TrackingViewModel, onUnlink: () -> Unit) {
    val context = LocalContext.current
    var statusMenu by remember { mutableStateOf(false) }
    var scoreMenu by remember { mutableStateOf(false) }
    var chaptersDialog by remember { mutableStateOf(false) }
    val status = TrackStatus.of(track.status)
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs)) {
        Column(Modifier.padding(start = Dimens.l, end = Dimens.xs, top = Dimens.xs, bottom = Dimens.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(service.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (track.url.isNotBlank()) {
                    IconButton(onClick = { Share.openUrl(context, track.url) }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.track_open))
                    }
                }
                IconButton(onClick = onUnlink) { Icon(Icons.Outlined.LinkOff, stringResource(R.string.track_unlink)) }
            }
            if (track.libraryId == TrackerManager.NEEDS_REMOTE_REFRESH) {
                Text(stringResource(if (signedIn) R.string.track_refresh_required else R.string.track_sign_in_refresh),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (signedIn) TextButton(onClick = { viewModel.refreshTracks() }) { Text(stringResource(R.string.track_refresh_action)) }
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s), modifier = Modifier.padding(end = Dimens.m)) {
                Box {
                    AssistChip(onClick = { statusMenu = true }, label = { Text(status.label()) })
                    DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        TrackStatus.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label()) }, onClick = {
                                statusMenu = false
                                if (s != status) viewModel.updateTrack(track) { e -> e.copy(status = s) }
                            })
                        }
                    }
                }
                val read = track.lastChapterRead.toInt()
                AssistChip(
                    onClick = { chaptersDialog = true },
                    label = {
                        Text(
                            if (track.totalChapters > 0) stringResource(R.string.track_chapters_of, read, track.totalChapters)
                            else stringResource(R.string.track_chapters, read),
                        )
                    },
                )
                Box {
                    AssistChip(
                        onClick = { scoreMenu = true },
                        label = { Text(if (track.score > 0) formatTrackScore(track.score) else stringResource(R.string.track_no_score)) },
                        leadingIcon = { Icon(Icons.Outlined.Star, null, Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = scoreMenu, onDismissRequest = { scoreMenu = false }) {
                        for (n in 10 downTo 0) {
                            DropdownMenuItem(
                                text = { Text(if (n == 0) stringResource(R.string.track_no_score) else n.toString()) },
                                onClick = {
                                    scoreMenu = false
                                    viewModel.updateTrack(track) { e -> e.copy(score = n.toFloat()) }
                                },
                            )
                        }
                    }
                }
            }
            if (track.pending) {
                Row(Modifier.padding(top = Dimens.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(if (signedIn) R.string.track_not_sent else R.string.track_not_sent_signed_out),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (chaptersDialog) {
        TextInputDialog(
            title = stringResource(R.string.track_chapters_read),
            initial = track.lastChapterRead.toInt().toString(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onConfirm = { v ->
                v.toIntOrNull()?.takeIf { it >= 0 }?.let { n ->
                    val max = track.totalChapters.takeIf { it > 0 } ?: Int.MAX_VALUE
                    viewModel.updateTrack(track) { e -> e.copy(lastChapterRead = n.coerceAtMost(max).toFloat()) }
                }
            },
            onDismiss = { chaptersDialog = false },
        )
    }
}

/** A score of 0–10 without a needless ".0". */
fun formatTrackScore(score: Float): String = if (score % 1f == 0f) score.toInt().toString() else "%.1f".format(score)

@Composable
private fun TrackSearchDialog(state: TrackSearchState, viewModel: TrackingViewModel) {
    var query by rememberSaveable(state.service) { mutableStateOf(state.query) }
    AlertDialog(
        onDismissRequest = viewModel::closeTrackSearch,
        title = { Text(stringResource(R.string.track_link_on, state.service.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.searchTrack(query) }),
                    trailingIcon = {
                        IconButton(onClick = { viewModel.searchTrack(query) }) { Icon(Icons.Outlined.Search, stringResource(CoreR.string.search)) }
                    },
                )
                val results = state.results
                when {
                    state.loading -> Box(Modifier.fillMaxWidth().padding(Dimens.l), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.error != null -> Text(errorMessage(state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    results == null -> Unit
                    results.isEmpty() -> Text(stringResource(R.string.track_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(results, key = { it.remoteId }) { r -> TrackResultRow(r) { viewModel.bindTrack(r) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::closeTrackSearch) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

@Composable
private fun TrackResultRow(result: TrackSearchResult, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Dimens.s),
        horizontalArrangement = Arrangement.spacedBy(Dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(result.coverUrl, result.title, Modifier.size(width = 44.dp, height = 62.dp))
        Column(Modifier.weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            result.altTitle?.takeIf { it.isNotBlank() && it != result.title }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val meta = listOfNotNull(
                result.format?.takeIf { it.isNotBlank() },
                result.year?.toString(),
                result.totalChapters.takeIf { it > 0 }?.let { stringResource(R.string.track_result_chapters, it) },
                result.publishing?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
