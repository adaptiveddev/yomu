package app.yomu.feature.reader

import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.ui.components.ChoiceDialog
import app.yomu.core.ui.components.PreferenceItem
import app.yomu.core.ui.components.PreferenceSlider
import app.yomu.core.ui.components.SearchField
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.YomuSheet
import app.yomu.domain.model.CommonReaderSettings
import app.yomu.domain.model.SearchHit
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import java.util.Locale
import kotlin.math.roundToInt
import app.yomu.core.ui.R as CoreR

/** Full-text search inside the open book with a list of hits; a tap jumps to the hit and highlights it. */
@Composable
internal fun SearchSheet(
    state: SearchState,
    initial: String,
    onSearch: (String) -> Unit,
    onHit: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (initial.isNotBlank() && initial != state.query) onSearch(initial.trim())
        if (initial.isBlank()) runCatching { focus.requestFocus() }
    }
    YomuSheet(onDismiss = onDismiss) {
        SheetTitle(stringResource(R.string.reader_search))
        SearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.reader_search_hint),
            modifier = Modifier.padding(horizontal = 16.dp),
            onSearch = { if (it.isNotBlank()) onSearch(it.trim()) },
            focusRequester = focus,
            trailing = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; onClear() }) { Icon(Icons.Default.Close, stringResource(CoreR.string.clear)) }
                }
            },
        )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        if (state.query.isNotBlank() && (state.done || state.hits.isNotEmpty())) {
            Text(
                pluralStringResource(R.plurals.reader_search_results, state.hits.size, state.hits.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (state.done && state.hits.isEmpty() && state.query.isNotBlank()) {
            EmptyLine(stringResource(R.string.reader_search_none))
        }
        val highlight = SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(state.hits, key = { "${it.chapter}:${it.occurrence}:${it.matchStart}" }) { hit ->
                ListItem(
                    overlineContent = { Text(hit.chapterTitle ?: stringResource(R.string.reader_chapter_n, hit.chapter + 1), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    headlineContent = { Text(snippet(hit, highlight), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.clickable { onHit(hit) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun snippet(hit: SearchHit, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    val s = hit.snippet
    val start = hit.matchStart.coerceIn(0, s.length)
    val end = (hit.matchStart + hit.matchLength).coerceIn(start, s.length)
    append(s.substring(0, start))
    withStyle(style) { append(s.substring(start, end)) }
    append(s.substring(end))
}

/** Read aloud controls: play / pause / stop, speed, pitch, voice and sleep timer. */
@Composable
internal fun TtsSheet(vm: ReaderViewModel, common: CommonReaderSettings, onDismiss: () -> Unit) {
    val active by vm.ttsActive.collectAsStateWithLifecycle()
    val playing by vm.tts.playing.collectAsStateWithLifecycle()
    val timer by vm.sleepTimer.collectAsStateWithLifecycle()
    val remaining by vm.sleepRemainingMs.collectAsStateWithLifecycle()
    var voicesOpen by remember { mutableStateOf(false) }
    YomuSheet(onDismiss = onDismiss) {
        SheetTitle(stringResource(R.string.reader_tts))
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isPlaying = active && playing
            FilledIconButton(
                onClick = { when { !active -> vm.startTts(); playing -> vm.pauseTts(); else -> vm.resumeTts() } },
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (isPlaying) R.string.tts_pause else R.string.tts_play),
                    Modifier.size(36.dp),
                )
            }
            OutlinedIconButton(onClick = vm::stopTts, enabled = active, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Stop, stringResource(R.string.tts_stop))
            }
        }
        PreferenceSlider(
            title = stringResource(R.string.tts_speed),
            value = common.ttsSpeed,
            range = 0.5f..2.5f,
            onValueChange = { v -> vm.updateCommon { it.copy(ttsSpeed = (v * 10).roundToInt() / 10f) } },
            valueLabel = { String.format(Locale.getDefault(), "%.1f×", it) },
            steps = 19,
        )
        PreferenceSlider(
            title = stringResource(R.string.tts_pitch),
            value = common.ttsPitch,
            range = 0.5f..2f,
            onValueChange = { v -> vm.updateCommon { it.copy(ttsPitch = (v * 10).roundToInt() / 10f) } },
            valueLabel = { String.format(Locale.getDefault(), "%.1f", it) },
            steps = 14,
        )
        PreferenceItem(
            title = stringResource(R.string.tts_voice),
            summary = common.ttsVoice ?: stringResource(R.string.tts_voice_default),
            icon = Icons.Outlined.RecordVoiceOver,
            onClick = { voicesOpen = true },
        )
        Text(
            stringResource(R.string.tts_sleep),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SleepTimer.entries.forEach { t ->
                FilterChip(selected = timer == t, onClick = { vm.setSleepTimer(t) }, label = { Text(sleepLabel(t)) })
            }
        }
        remaining?.let { ms ->
            val totalSec = (ms / 1000).coerceAtLeast(0)
            Text(
                stringResource(R.string.tts_remaining, "%d:%02d".format(totalSec / 60, totalSec % 60)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
    if (voicesOpen) {
        val voices = remember { vm.ttsVoices() }
        val options: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to stringResource(R.string.tts_voice_default)) +
            voices.map { it.name to voiceLabel(it) }
        ChoiceDialog(
            title = stringResource(R.string.tts_voice),
            options = options,
            selected = common.ttsVoice,
            onSelect = { v -> vm.updateCommon { it.copy(ttsVoice = v) }; voicesOpen = false },
            onDismiss = { voicesOpen = false },
        )
    }
}

private fun voiceLabel(v: Voice): String {
    val quality = if (v.isNetworkConnectionRequired) " ☁" else ""
    return "${v.locale.getDisplayName(Locale.getDefault())} · ${v.name}$quality"
}

@Composable
private fun sleepLabel(t: SleepTimer): String = when (t) {
    SleepTimer.OFF -> stringResource(R.string.tts_sleep_off)
    SleepTimer.END_OF_CHAPTER -> stringResource(R.string.tts_sleep_chapter)
    else -> stringResource(R.string.tts_sleep_min, t.minutes)
}

/** Footnote content shown in a popup instead of jumping to the end of the chapter. */
@Composable
internal fun FootnoteDialog(html: String, onDismiss: () -> Unit) {
    val text = remember(html) { runCatching { AnnotatedString.fromHtml(html) }.getOrElse { AnnotatedString(html) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
        title = { Text(stringResource(R.string.reader_footnote)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.close)) } },
    )
}

/** Full-screen illustration viewer with pinch zoom, double tap and panning. */
@Composable
internal fun ImageViewerDialog(model: Any, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableAsyncImage(
                model = model,
                contentDescription = stringResource(R.string.reader_image),
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Default.Close, stringResource(CoreR.string.close), tint = Color.White)
            }
        }
    }
}

/** Asked when a PDF is protected with a password. */
@Composable
internal fun PasswordDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, null) },
        title = { Text(stringResource(R.string.reader_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.reader_password_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reader_password_hint)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (value.isNotEmpty()) onSubmit(value) }),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                stringResource(if (visible) R.string.reader_password_hide else R.string.reader_password_show),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(value) }, enabled = value.isNotEmpty()) { Text(stringResource(R.string.reader_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

/** Shown once when the last page of a book is reached. */
@Composable
internal fun FinishedDialog(onBack: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Celebration, null) },
        title = { Text(stringResource(R.string.reader_finished_title)) },
        text = { Text(stringResource(R.string.reader_finished_text)) },
        confirmButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.reader_finished_back)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_finished_stay)) } },
    )
}

@Composable
private fun EmptyLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
