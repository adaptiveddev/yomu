package app.yomu.feature.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.yomu.core.common.Files
import app.yomu.core.ui.components.CoverImage
import app.yomu.core.ui.components.ErrorState
import app.yomu.core.ui.components.SearchField
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.YomuSheet
import app.yomu.core.ui.components.coverTint
import app.yomu.core.ui.components.rememberCoverColor
import app.yomu.data.audio.AudioDownloadProgress
import app.yomu.data.audio.AudioDownloadState
import app.yomu.domain.model.AudioNavEntry
import app.yomu.domain.model.AudioOrigin
import app.yomu.domain.model.AudioPositions
import java.io.File
import kotlinx.coroutines.launch
import java.util.Locale
import app.yomu.core.ui.R as CoreR

private val SPEEDS = listOf(0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private const val SPEED_STEP = 0.05f
private val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60, 90)
private const val SLEEP_EXTRA_MIN = 5
private const val MAX_SLEEP_MIN = 24 * 60
private const val CHAPTER_SEARCH_MIN = 12

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(viewModel: AudioPlayerViewModel, onBack: () -> Unit) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val chapterLengths by viewModel.chapterLengths.collectAsStateWithLifecycle()
    val settings by viewModel.audioSettings.collectAsStateWithLifecycle()
    val origin by viewModel.origin.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val playing = player?.isPlaying == true
    // Paused, the position only moves on seeks, which are player events: no need to poll.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playing, lifecycle) { if (playing) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.tickWhilePlaying() } }

    val cover = book?.coverPath?.let(::File)
    val tint = rememberCoverColor(cover)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).coverTint(tint)) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {},
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) } },
                    actions = {
                        IconButton(onClick = { dialog = "bookmark" }, enabled = player != null) {
                            Icon(Icons.Default.BookmarkAdd, stringResource(R.string.audio_bookmark_add))
                        }
                        if (offline != null) {
                            IconButton(onClick = { sheet = "offline" }) {
                                val d = download
                                when {
                                    d?.running == true -> CircularProgressIndicator(progress = { d.fraction }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                    offline?.complete == true -> Icon(Icons.Default.DownloadDone, stringResource(R.string.audio_offline))
                                    else -> Icon(Icons.Outlined.Download, stringResource(R.string.audio_offline))
                                }
                            }
                        }
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
            val s = player
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                val wide = maxWidth >= 560.dp && maxWidth > maxHeight
                val chapterIndex = s?.let { AudioPositions.current(chapters, it.track, it.positionMs) } ?: -1
                val info: @Composable () -> Unit = {
                    TitleBlock(book?.title.orEmpty(), book?.authorsText.orEmpty(), originLabel(origin), chapters, chapterIndex, s?.trackTitle.orEmpty()) { sheet = "chapters" }
                }
                val controls: @Composable () -> Unit = {
                    Controls(viewModel, s, settings, chapters, chapterIndex, onSheet = { sheet = it })
                }
                if (wide) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Box(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Cover(cover, book?.title.orEmpty(), Modifier.fillMaxHeight(0.86f))
                        }
                        Column(
                            Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            info()
                            Spacer(Modifier.height(12.dp))
                            controls()
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                } else {
                    val fullHeight = maxHeight
                    val coverWidth = minOf(maxWidth * 0.72f, maxHeight * 0.3f).coerceAtLeast(140.dp)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Column(
                            Modifier.heightIn(min = fullHeight).padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Cover(cover, book?.title.orEmpty(), Modifier.width(coverWidth))
                            info()
                            controls()
                        }
                    }
                }
            }
        }
    }

    when (sheet) {
        "chapters" -> YomuSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.audio_chapters))
            val current = player?.let { AudioPositions.current(chapters, it.track, it.positionMs) } ?: -1
            var query by rememberSaveable { mutableStateOf("") }
            if (chapters.size > CHAPTER_SEARCH_MIN) {
                SearchField(
                    query, { query = it }, stringResource(R.string.audio_chapters_search),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val shown = remember(chapters, query) { PlayerMath.search(chapters, query) }
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.audio_chapters_none), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            LazyColumn(state = state, modifier = Modifier.heightIn(max = 520.dp)) {
                items(shown, key = { it }) { i ->
                    val entry = chapters[i]
                    ChapterRow(entry, chapterLengths.getOrElse(i) { 0 }, current = i == current, heard = current >= 0 && i < current) {
                        viewModel.jump(entry); sheet = null
                    }
                }
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
            // How the speed changes the time left, so the choice is easier.
            player?.let(viewModel::timeLeftMs)?.let { left ->
                Text(
                    stringResource(R.string.audio_book_left, duration(left)), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            ChipGrid(SPEEDS, label = { speedLabel(it) }, selected = { kotlin.math.abs(current - it) < 0.01f }) { viewModel.setSpeed(it); sheet = null }
        }
        "sleep" -> YomuSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.audio_sleep))
            val timerOn = (player?.sleepAt ?: 0L) > 0
            if (timerOn) OptionRow(stringResource(R.string.audio_sleep_extend), selected = false) { viewModel.extendSleep(SLEEP_EXTRA_MIN); sheet = null }
            OptionRow(stringResource(R.string.audio_sleep_off), selected = player?.let { it.sleepAt == 0L && !it.sleepEndOfChapter } ?: true) { viewModel.sleep(0); sheet = null }
            OptionRow(stringResource(R.string.audio_sleep_chapter), selected = player?.sleepEndOfChapter == true) {
                viewModel.sleep(AudioPlaybackService.SLEEP_END_OF_CHAPTER); sheet = null
            }
            ChipGrid(SLEEP_MINUTES, label = { stringResource(R.string.audio_sleep_minutes, it) }, selected = { false }) { viewModel.sleep(it); sheet = null }
            OptionRow(stringResource(R.string.audio_sleep_custom), selected = false) { sheet = null; dialog = "sleep" }
        }
        "bookmarks" -> YomuSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.audio_bookmarks))
            Button(
                onClick = { sheet = null; dialog = "bookmark" }, enabled = player != null,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.audio_bookmark_add))
            }
            // The sheet covers the screen's snackbar, so the undo for a deletion lives in the sheet itself.
            var deleted by remember { mutableStateOf<Bookmark?>(null) }
            deleted?.let { mark ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.audio_bookmark_deleted), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.addBookmark(mark); deleted = null }) { Text(stringResource(CoreR.string.undo)) }
                }
            }
            if (settings.bookmarks.isEmpty()) {
                Text(
                    stringResource(R.string.audio_bookmarks_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(settings.bookmarks) { _, mark ->
                        BookmarkRow(mark, chapters, onClick = { viewModel.jump(mark); sheet = null }, onDelete = { viewModel.removeBookmark(mark); deleted = mark })
                    }
                }
            }
        }
        "skip" -> YomuSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.audio_skip_intervals))
            SkipChoices(stringResource(R.string.audio_skip_back_title), AudioSettings.SKIP_BACK_OPTIONS, settings.skipBackSec) {
                viewModel.setSkipIntervals(it, settings.skipForwardSec)
            }
            SkipChoices(stringResource(R.string.audio_skip_forward_title), AudioSettings.SKIP_FORWARD_OPTIONS, settings.skipForwardSec) {
                viewModel.setSkipIntervals(settings.skipBackSec, it)
            }
            Spacer(Modifier.height(16.dp))
        }
        "offline" -> YomuSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.audio_offline))
            OfflinePanel(offline, download, viewModel::startDownload, viewModel::cancelDownload) { viewModel.removeDownload(); sheet = null }
        }
    }

    when (dialog) {
        "bookmark" -> {
            var note by rememberSaveable { mutableStateOf("") }
            // The spot is the one the listener heard when they asked for it, not where playback is once the note is typed.
            val track = rememberSaveable { player?.track ?: 0 }
            val at = rememberSaveable { player?.positionMs ?: 0L }
            val savedMsg = stringResource(R.string.audio_bookmark_added, time(at))
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.audio_bookmark_here, time(at))) },
                text = {
                    OutlinedTextField(
                        value = note, onValueChange = { note = it.take(200) }, singleLine = true,
                        label = { Text(stringResource(R.string.audio_bookmark_note)) },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.addBookmark(Bookmark(track, at, note.trim(), System.currentTimeMillis()))
                        dialog = null
                        scope.launch { snackbar.showSnackbar(savedMsg) }
                    }) { Text(stringResource(R.string.audio_bookmark_save)) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(CoreR.string.cancel)) } },
            )
        }
        "sleep" -> {
            var minutes by rememberSaveable { mutableStateOf("") }
            val value = minutes.toIntOrNull()?.takeIf { it in 1..MAX_SLEEP_MIN }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.audio_sleep_custom_title)) },
                text = {
                    OutlinedTextField(
                        value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(4) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.audio_minutes_label)) },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { value?.let(viewModel::sleep); dialog = null }, enabled = value != null) { Text(stringResource(R.string.audio_start)) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(CoreR.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun Cover(model: File?, title: String, modifier: Modifier) {
    Box(modifier.shadow(20.dp, MaterialTheme.shapes.small)) {
        CoverImage(model, title, Modifier.fillMaxWidth())
    }
}

@Composable
private fun TitleBlock(
    title: String,
    author: String,
    origin: String?,
    chapters: List<AudioNavEntry>,
    chapterIndex: Int,
    trackTitle: String,
    onChapters: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val subtitle = listOfNotNull(author.ifBlank { null }, origin).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        val chapterTitle = chapters.getOrNull(chapterIndex)?.title ?: trackTitle
        Text(
            chapterTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(enabled = chapters.isNotEmpty(), onClick = onChapters).heightIn(min = 32.dp).padding(horizontal = 8.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(
    viewModel: AudioPlayerViewModel,
    s: PlayerSnapshot?,
    settings: AudioSettings,
    chapters: List<AudioNavEntry>,
    chapterIndex: Int,
    onSheet: (String) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val span = PlayerMath.span(chapters, chapterIndex, s?.durationMs ?: 0)
        SeekBar(s, span) { viewModel.seekTo(it) }
        val progress = s?.let(viewModel::bookProgress)
        val position = listOfNotNull(
            if (chapters.size > 1 && chapterIndex >= 0) stringResource(R.string.audio_chapter_of, chapterIndex + 1, chapters.size) else null,
            progress?.let { stringResource(R.string.audio_progress_percent, (it * 100).toInt()) },
        ).joinToString(" · ")
        if (position.isNotEmpty()) Text(position, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::previous, enabled = s != null, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.audio_previous), Modifier.size(32.dp))
            }
            SkipButton(settings.skipBackSec, forward = false, enabled = s != null, onClick = viewModel::back)
            FilledIconButton(onClick = viewModel::playPause, enabled = s != null, modifier = Modifier.size(80.dp)) {
                when {
                    s == null || s.isBuffering && s.playWhenReady -> CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
                    s.playWhenReady -> Icon(Icons.Default.Pause, stringResource(R.string.audio_pause), Modifier.size(42.dp))
                    else -> Icon(Icons.Default.PlayArrow, stringResource(R.string.audio_play), Modifier.size(42.dp))
                }
            }
            SkipButton(settings.skipForwardSec, forward = true, enabled = s != null, onClick = viewModel::forward)
            IconButton(onClick = viewModel::next, enabled = s != null, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.audio_next), Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
            AssistChip(
                onClick = { onSheet("speed") },
                label = { Text(speedLabel(s?.speed ?: 1f)) },
                leadingIcon = { Icon(Icons.Default.Speed, null) },
            )
            FilterChip(
                selected = s != null && (s.sleepAt > 0 || s.sleepEndOfChapter),
                onClick = { onSheet("sleep") },
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
            AssistChip(
                onClick = { onSheet("bookmarks") },
                label = {
                    val count = settings.bookmarks.size
                    Text(stringResource(R.string.audio_bookmarks) + if (count > 0) " · $count" else "")
                },
                leadingIcon = { Icon(Icons.Default.Bookmarks, null) },
            )
            AssistChip(
                onClick = { onSheet("skip") },
                label = { Text("${settings.skipBackSec} / ${settings.skipForwardSec}") },
                leadingIcon = { Icon(Icons.Default.Tune, stringResource(R.string.audio_skip_intervals)) },
            )
        }
    }
}

@Composable
private fun SkipButton(seconds: Int, forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (forward) R.string.audio_forward_n else R.string.audio_back_n, seconds)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            // Forward is the rewind arrow mirrored; the number is drawn on top so it is not.
            Icon(Icons.Default.Replay, label, Modifier.size(40.dp).graphicsLayer { scaleX = if (forward) -1f else 1f })
            Text(seconds.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkipChoices(title: String, options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { sec ->
                FilterChip(selected = sec == selected, onClick = { onSelect(sec) }, label = { Text(stringResource(R.string.audio_seconds_short, sec)) })
            }
        }
    }
}

@Composable
private fun SeekBar(s: PlayerSnapshot?, span: ChapterSpan, onSeek: (Long) -> Unit) {
    val length = span.lengthMs
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    val position = s?.positionMs ?: 0
    LaunchedEffect(position, span) { if (!dragging && length > 0) value = span.progress(position) }
    Slider(
        value = if (length > 0) value.coerceIn(0f, 1f) else 0f,
        onValueChange = { dragging = true; value = it },
        onValueChangeFinished = { dragging = false; if (length > 0) onSeek(span.positionAt(value)) },
        enabled = length > 0,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val elapsed = if (length > 0) (value * length).toLong() else (position - span.startMs).coerceAtLeast(0)
        Text(time(elapsed), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text(if (length > 0) "-" + time(length - elapsed) else "", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ChapterRow(entry: AudioNavEntry, lengthMs: Long, current: Boolean, heard: Boolean, onClick: () -> Unit) {
    val bg = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().background(bg).heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            current -> Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            heard -> Icon(Icons.Default.Check, stringResource(R.string.audio_chapter_heard), Modifier.size(20.dp), tint = dim)
            else -> Spacer(Modifier.width(20.dp))
        }
        Text(
            entry.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = when {
                current -> MaterialTheme.colorScheme.primary
                heard -> dim
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        )
        val label = if (lengthMs > 0) time(lengthMs) else if (entry.isMark) time(entry.startMs) else null
        if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = dim)
    }
}

/** Presets as chips: a few rows instead of a long list in the sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGrid(options: List<T>, label: @Composable (T) -> String, selected: (T) -> Boolean, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { v ->
            FilterChip(selected = selected(v), onClick = { onSelect(v) }, label = { Text(label(v)) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable
private fun BookmarkRow(mark: Bookmark, chapters: List<AudioNavEntry>, onClick: () -> Unit, onDelete: () -> Unit) {
    val chapter = chapters.getOrNull(AudioPositions.current(chapters, mark.track, mark.positionMs))?.title.orEmpty()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(mark.note.ifBlank { chapter }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val where = listOf(chapter, time(mark.positionMs)).filter { it.isNotBlank() && it != mark.note }.joinToString(" · ")
            Text(where, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.audio_bookmark_delete)) }
    }
}

@Composable
private fun OfflinePanel(
    state: AudioDownloadState?,
    download: AudioDownloadProgress?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val active = download?.takeIf { it.running }
        val running = active != null
        val status = when {
            active != null && active.total > 0 -> stringResource(R.string.audio_offline_running, active.track + 1, active.total)
            running -> stringResource(R.string.audio_offline_waiting)
            state == null -> ""
            state.complete -> stringResource(R.string.audio_offline_complete, Files.humanSize(state.bytes))
            state.downloaded > 0 -> stringResource(R.string.audio_offline_state, state.downloaded, state.total, Files.humanSize(state.bytes))
            else -> stringResource(R.string.audio_offline_hint)
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (active != null) LinearProgressIndicator(progress = { active.fraction }, modifier = Modifier.fillMaxWidth())
        download?.error?.takeIf { !running }?.let {
            Text(stringResource(R.string.audio_offline_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                running -> OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.audio_download_cancel)) }
                state?.complete != true -> Button(onClick = onDownload) {
                    Text(stringResource(if ((state?.downloaded ?: 0) > 0) R.string.audio_download_rest else R.string.audio_download))
                }
            }
            if (!running && (state?.downloaded ?: 0) > 0) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.audio_download_remove)) }
            }
        }
        Spacer(Modifier.height(8.dp))
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
