package app.yomu.profile

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.yomu.R
import app.yomu.core.common.ScreenErrors
import app.yomu.core.database.TrackEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.ui.components.CoverImage
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.tracking.TrackerManager
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import app.yomu.feature.catalog.formatTrackScore
import app.yomu.feature.catalog.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.yomu.feature.catalog.R as CatalogR

/** An account of a tracking service as the Tracking tab shows it. */
data class TrackerAccount(val service: TrackService, val signedIn: Boolean, val userName: String?)

/** A title of the online library with its entries on the services it is linked to. */
data class TrackedTitle(
    val itemId: Long,
    val title: String,
    val cover: String?,
    val entries: List<TrackEntity>,
    val updatedAt: Long,
    val isBook: Boolean = false,
) {
    fun has(status: TrackStatus): Boolean = entries.any { it.libraryId != TrackerManager.NEEDS_REMOTE_REFRESH && it.status == status.code }
}

data class TrackingUi(val accounts: List<TrackerAccount>, val titles: List<TrackedTitle>, val pending: Int) {
    val anySignedIn: Boolean get() = accounts.any { it.signedIn }

    /** Statuses with at least one title, in the services' order, with the number of titles. */
    val statuses: List<Pair<TrackStatus, Int>>
        get() = TrackStatus.entries.map { s -> s to titles.count { it.has(s) } }.filter { it.second > 0 }
}

sealed interface TrackingEvent {
    data class Refreshed(val failed: Int, val error: Throwable?) : TrackingEvent
    data class Sent(val left: Int) : TrackingEvent
}

/**
 * The profile's Tracking tab (2.0.1): every title linked to MyAnimeList, AniList or Shikimori with its status and
 * progress there, the accounts, and changes that wait to be sent.
 */
class ProfileTrackingViewModel(db: YomuDatabase, private val trackers: TrackerManager) : ViewModel() {
    val state: StateFlow<TrackingUi?> = combine(db.tracks().observeWithItems(), trackers.store.version) { rows, _ ->
        val accounts = trackers.trackers.map { t -> TrackerAccount(t.service, t.loggedIn(), t.userName()) }
        val titles = rows.groupBy { it.isBook to (it.track.bookId ?: it.track.itemId!!) }.map { (key, list) ->
            val first = list.first()
            TrackedTitle(key.second, first.itemTitle, first.itemCover, list.map { it.track }.sortedBy { it.service }, list.maxOf { it.track.updatedAt }, key.first)
        }.sortedByDescending { it.updatedAt }
        // Changes for a signed-out service are sent at sign-in; the entry shows it, the Send button cannot help.
        val signedIn = accounts.filter { it.signedIn }.map { it.service.id }.toSet()
        TrackingUi(accounts, titles, rows.count { it.track.pending && it.track.service in signedIn })
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<TrackingEvent>(Channel.BUFFERED)
    val events: Flow<TrackingEvent> = _events.receiveAsFlow()

    /** Reads every entry from the services again (and sends the changes that wait). */
    fun refreshAll() = work {
        val (failed, error) = trackers.refreshAll()
        _events.send(TrackingEvent.Refreshed(failed, error))
    }

    fun sendPending() = work { _events.send(TrackingEvent.Sent(trackers.pushPending())) }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(ScreenErrors) {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Top-bar actions of the Tracking tab: refresh from the services, tracking settings. */
@Composable
fun RowScope.TrackingActions(viewModel: ProfileTrackingViewModel, onOpenSettings: () -> Unit) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    if (busy) {
        CircularProgressIndicator(Modifier.padding(horizontal = Dimens.m).size(20.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
    } else {
        IconButton(onClick = viewModel::refreshAll) { Icon(Icons.Outlined.Refresh, stringResource(R.string.profile_track_refresh)) }
    }
    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.profile_track_settings)) }
}

@Composable
fun TrackingPane(
    viewModel: ProfileTrackingViewModel,
    snackbar: SnackbarHostState,
    onOpenTitle: (Long) -> Unit,
    onBookDetails: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<TrackStatus?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e -> snackbar.showSnackbar(eventMessage(context, e)) }
    }
    val s = state ?: return
    val shown = remember(s, filter) { filter?.let { f -> s.titles.filter { it.has(f) } } ?: s.titles }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = Dimens.l, end = Dimens.l, top = Dimens.m, bottom = navBarBottomPadding(Dimens.xl)),
        verticalArrangement = Arrangement.spacedBy(Dimens.m),
    ) {
        item(key = "accounts") { AccountsCard(s, busy, onOpenSettings, onSend = viewModel::sendPending) }
        if (s.titles.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.profile_track_empty),
                    message = stringResource(if (s.anySignedIn) R.string.profile_track_empty_hint else R.string.profile_track_empty_signed_out),
                    icon = Icons.Outlined.TrackChanges,
                    actionLabel = if (s.anySignedIn) null else stringResource(R.string.profile_track_sign_in),
                    onAction = if (s.anySignedIn) null else onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            return@LazyColumn
        }
        val statuses = s.statuses
        if (statuses.size > 1 || filter != null) {
            item(key = "filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.profile_track_all, s.titles.size)) })
                    statuses.forEach { (status, n) ->
                        FilterChip(
                            selected = filter == status,
                            onClick = { filter = if (filter == status) null else status },
                            label = { Text("${status.label()} · $n") },
                        )
                    }
                }
            }
        }
        items(shown, key = { "${it.isBook}:${it.itemId}" }) { t ->
            TrackedTitleCard(t, s.accounts, onClick = { if (t.isBook) onBookDetails(t.itemId) else onOpenTitle(t.itemId) })
        }
    }
}

@Composable
private fun AccountsCard(s: TrackingUi, busy: Boolean, onOpenSettings: () -> Unit, onSend: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = Dimens.m)) {
            Row(Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.profile_track_accounts), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.profile_track_manage)) }
            }
            s.accounts.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = Dimens.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.service.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            a.signedIn && !a.userName.isNullOrBlank() -> a.userName
                            a.signedIn -> stringResource(R.string.profile_track_signed_in)
                            else -> stringResource(R.string.profile_track_signed_out)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (a.signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (s.pending > 0) {
                Row(Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.l, top = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(Dimens.s))
                    Text(
                        pluralStringResource(R.plurals.profile_track_pending, s.pending, s.pending),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = onSend, enabled = !busy && s.anySignedIn) { Text(stringResource(R.string.profile_track_send)) }
                }
            }
        }
    }
}

@Composable
private fun TrackedTitleCard(t: TrackedTitle, accounts: List<TrackerAccount>, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Dimens.m), verticalAlignment = Alignment.Top) {
            CoverImage(t.cover, t.title, Modifier.width(48.dp))
            Spacer(Modifier.width(Dimens.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                t.entries.forEach { e ->
                    val service = TrackService.of(e.service)
                    val signedIn = accounts.firstOrNull { it.service == service }?.signedIn == true
                    EntryLine(service?.displayName ?: "#${e.service}", e, signedIn)
                }
            }
        }
    }
}

@Composable
private fun EntryLine(service: String, e: TrackEntity, signedIn: Boolean) {
    val read = e.lastChapterRead.toInt()
    val chapters = if (e.totalChapters > 0) stringResource(CatalogR.string.track_chapters_of, read, e.totalChapters) else stringResource(CatalogR.string.track_chapters, read)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(service, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            if (e.libraryId == TrackerManager.NEEDS_REMOTE_REFRESH)
                " · " + stringResource(if (signedIn) CatalogR.string.track_refresh_required else CatalogR.string.track_sign_in_refresh)
            else " · ${TrackStatus.of(e.status).label()} · $chapters",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (e.score > 0) {
            Spacer(Modifier.width(Dimens.s))
            Icon(Icons.Outlined.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTrackScore(e.score), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (e.pending) {
            Spacer(Modifier.width(Dimens.s))
            Icon(
                Icons.Outlined.CloudOff,
                stringResource(if (signedIn) CatalogR.string.track_not_sent else CatalogR.string.track_not_sent_signed_out),
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun eventMessage(context: Context, e: TrackingEvent): String = when (e) {
    is TrackingEvent.Refreshed -> when {
        e.failed == 0 -> context.getString(R.string.profile_track_refreshed)
        else -> context.resources.getQuantityString(R.plurals.profile_track_refresh_failed, e.failed, e.failed, e.error?.let { errorMessage(context, it) }.orEmpty())
    }
    is TrackingEvent.Sent -> if (e.left == 0) {
        context.getString(R.string.profile_track_sent)
    } else {
        context.resources.getQuantityString(R.plurals.profile_track_left, e.left, e.left)
    }
}
