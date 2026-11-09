package app.yomu.feature.audio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.yomu.core.ui.components.CoverImage
import app.yomu.core.ui.components.ErrorState
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.YomuSheet
import app.yomu.domain.model.AudioNavEntry
import app.yomu.domain.model.AudioOrigin
import app.yomu.domain.model.AudioPositions
import java.io.File
import java.util.Locale
import app.yomu.core.ui.R as CoreR

private val SPEEDS = listOf(0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private const val SPEED_STEP = 0.05f
private val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60, 90)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AudioPlayerScreen(viewModel: AudioPlayerViewModel, onBack: () -> Unit) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val origin by viewModel.origin.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val playing = player?.isPlaying == true
    // Paused, the position only moves on seeks, which are player events: no need to poll.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playing, lifecycle) { if (playing) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.tickWhilePlaying() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) } },
                actions = {
                    IconButton(onClick = { sheet = "chapters" }, enabled = chapters.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, stringResource(R.string.audio_chapters))
                    }
                },
            )
        },
    ) { padding ->
        val error = loadError
        if (error != null) {
            ErrorState(error, Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }
        val b = book
        val s = player
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.widthIn(max = 260.dp).fillMaxWidth()) {
                CoverImage(b?.coverPath?.let(::File), b?.title.orEmpty(), Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(20.dp))
            Text(
                b?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(b?.authorsText?.ifBlank { null }, originLabel(origin)).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            val chapterIndex = s?.let { AudioPositions.current(chapters, it.track, it.positionMs) } ?: -1
            val chapterTitle = chapters.getOrNull(chapterIndex)?.title ?: s?.trackTitle.orEmpty()
            Text(
                chapterTitle, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(enabled = chapters.isNotEmpty()) { sheet = "chapters" },
            )
            if (s != null && s.trackCount > 1) {
                Text(
                    stringResource(R.string.audio_track_of, s.track + 1, s.trackCount),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            SeekBar(s, viewModel::seekTo)
            s?.let(viewModel::timeLeftMs)?.let { left ->
                Text(
                    stringResource(R.string.audio_book_left, duration(left)),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            s?.error?.let {
                Text(
                    stringResource(R.string.audio_play_error, it), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::previous, enabled = s != null) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.audio_previous)) }
                IconButton(onClick = viewModel::back, enabled = s != null) { Icon(Icons.Default.Replay10, stringResource(R.string.audio_back_10)) }
                FilledIconButton(
                    onClick = viewModel::playPause, enabled = s != null, modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    when {
                        s == null || s.isBuffering && s.playWhenReady -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
                        s.playWhenReady -> Icon(Icons.Default.Pause, stringResource(R.string.audio_pause), Modifier.size(36.dp))
                        else -> Icon(Icons.Default.PlayArrow, stringResource(R.string.audio_play), Modifier.size(36.dp))
                    }
                }
                IconButton(onClick = viewModel::forward, enabled = s != null) { Icon(Icons.Default.Forward30, stringResource(R.string.audio_forward_30)) }
                IconButton(onClick = viewModel::next, enabled = s != null) { Icon(Icons.Default.SkipNext, stringResource(R.string.audio_next)) }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                AssistChip(
                    onClick = { sheet = "speed" },
                    label = { Text(speedLabel(s?.speed ?: 1f)) },
                    leadingIcon = { Icon(Icons.Default.Speed, null) },
                )
                AssistChip(
                    onClick = { sheet = "sleep" },
                    label = { Text(sleepLabel(s)) },
                    leadingIcon = { Icon(Icons.Default.Bedtime, null) },
                )
                FilterChip(
                    selected = s?.skipSilence == true,
                    onClick = viewModel::toggleSkipSilence,
                    enabled = s != null,
                    label = { Text(stringResource(R.string.audio_skip_silence)) },
                    leadingIcon = { Icon(Icons.Default.GraphicEq, null) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (sheet) {
        "chapters" -> YomuSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.audio_chapters))
            val current = player?.let { AudioPositions.current(chapters, it.track, it.positionMs) } ?: -1
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
            LazyColumn(state = state, modifier = Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(chapters) { i, entry -> ChapterRow(entry, i == current) { viewModel.jump(entry); sheet = null } }
            }
        }
        "speed" -> YomuSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.audio_speed))
            val current = player?.speed ?: 1f
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.setSpeed(current - SPEED_STEP) }, enabled = current > AudioSettings.MIN_SPEED + 0.001f) {
                    Icon(Icons.Default.Remove, stringResource(R.string.audio_speed_down))
                }
                Text(speedLabel(current), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.setSpeed(current + SPEED_STEP) }, enabled = current < AudioSettings.MAX_SPEED - 0.001f) {
                    Icon(Icons.Default.Add, stringResource(R.string.audio_speed_up))
                }
            }
            SPEEDS.forEach { v ->
                OptionRow(speedLabel(v), selected = kotlin.math.abs(current - v) < 0.01f) { viewModel.setSpeed(v); sheet = null }
            }
        }
        "sleep" -> YomuSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.audio_sleep))
            OptionRow(stringResource(R.string.audio_sleep_off), selected = player?.let { it.sleepAt == 0L && !it.sleepEndOfChapter } ?: true) { viewModel.sleep(0); sheet = null }
            OptionRow(stringResource(R.string.audio_sleep_chapter), selected = player?.sleepEndOfChapter == true) {
                viewModel.sleep(AudioPlaybackService.SLEEP_END_OF_CHAPTER); sheet = null
            }
            SLEEP_MINUTES.forEach { m -> OptionRow(stringResource(R.string.audio_sleep_minutes, m), selected = false) { viewModel.sleep(m); sheet = null } }
        }
    }
}

@Composable
private fun SeekBar(s: PlayerSnapshot?, onSeek: (Long) -> Unit) {
    val duration = s?.durationMs ?: 0
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(s?.positionMs, duration) { if (!dragging && duration > 0) value = s!!.positionMs.toFloat() / duration }
    Slider(
        value = if (duration > 0) value.coerceIn(0f, 1f) else 0f,
        onValueChange = { dragging = true; value = it },
        onValueChangeFinished = { dragging = false; if (duration > 0) onSeek((value * duration).toLong()) },
        enabled = duration > 0,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(time(if (duration > 0) (value * duration).toLong() else s?.positionMs ?: 0), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text(if (duration > 0) "-" + time(duration - (value * duration).toLong()) else "", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ChapterRow(entry: AudioNavEntry, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (entry.isMark) Text(time(entry.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

@Composable
private fun originLabel(origin: AudioOrigin): String? = when (origin) {
    AudioOrigin.FILES -> null
    AudioOrigin.CATALOG -> stringResource(R.string.audio_origin_catalog)
    AudioOrigin.YOUTUBE -> "YouTube"
    AudioOrigin.PODCAST -> stringResource(R.string.audio_origin_podcast)
    AudioOrigin.NOTEBOOKLM -> "NotebookLM"
    AudioOrigin.URL -> stringResource(R.string.audio_origin_link)
}

@Composable
private fun sleepLabel(s: PlayerSnapshot?): String = when {
    s == null -> stringResource(R.string.audio_sleep)
    s.sleepEndOfChapter -> stringResource(R.string.audio_sleep_chapter)
    s.sleepAt > 0 -> {
        val left = ((s.sleepAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0) + 1
        stringResource(R.string.audio_sleep_left, left.toInt())
    }
    else -> stringResource(R.string.audio_sleep)
}

private fun speedLabel(speed: Float): String {
    val rounded = Math.round(speed * 100) / 100f
    return String.format(Locale.ROOT, if (Math.round(rounded * 100) % 10 == 0) "%.1f×" else "%.2f×", rounded)
}

/** "3 h 05 min" style length for the time left. */
@Composable
private fun duration(ms: Long): String {
    val minutes = (ms + 59_999) / 60_000
    val h = (minutes / 60).toInt()
    val m = (minutes % 60).toInt()
    return if (h > 0) stringResource(R.string.audio_hours_minutes, h, m) else stringResource(R.string.audio_sleep_minutes, m)
}

internal fun time(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
