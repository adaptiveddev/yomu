package app.yomu.feature.reader

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
import java.util.Date
import app.yomu.core.ui.R as CoreR

/** "Page 34 of ≈412": the page count of reflowable books is estimated, which the "≈" says honestly. */
@Composable
internal fun pageOfText(page: Int, pages: Int, approximate: Boolean): String =
    stringResource(R.string.reader_page_short, page.coerceAtLeast(1), if (approximate) "≈$pages" else "$pages")

/** The current page; a tap opens "Go to page". */
@Composable
internal fun PageChip(page: Int, pages: Int, approximate: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = pageOfText(page, pages, approximate)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoStories, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Big card in the middle of the screen while the slider is dragged: where the reader will land. */
@Composable
internal fun SeekPreviewCard(preview: SeekPreview, value: Float, modifier: Modifier = Modifier, thumbnail: File? = null) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 8.dp,
        modifier = modifier.widthIn(min = 180.dp, max = 320.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 150.dp, height = 210.dp).clip(MaterialTheme.shapes.small),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text("${preview.page}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.reader_of_pages, if (preview.approximate) "≈${preview.pages}" else "${preview.pages}"),
                style = MaterialTheme.typography.labelLarge,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
            preview.chapterTitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.reader_progress_percent, (value * 100).toInt().coerceIn(0, 100)),
                style = MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
        }
    }
}

/** "Back to page 12": undoes an accidental jump of the slider, contents or search. */
@Composable
internal fun JumpBackChip(page: Int, onBack: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
        modifier = modifier.padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)) {
                Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (page > 0) stringResource(R.string.reader_jump_back, page) else stringResource(R.string.reader_jump_back_generic))
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(CoreR.string.close)) }
        }
    }
}

/** Page number or percent; for reflowable books the page is an estimate, the dialog says so. */
@Composable
internal fun GoToPageDialog(
    page: Int,
    pages: Int,
    approximate: Boolean,
    percent: Int,
    onPage: (Int) -> Unit,
    onPercent: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var byPercent by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable(byPercent) { mutableStateOf(if (byPercent) "$percent" else "$page") }
    val max = if (byPercent) 100 else pages.coerceAtLeast(1)
    val min = if (byPercent) 0 else 1
    val value = text.trim().toIntOrNull()
    val valid = value != null && value in min..max
    val focus = remember { FocusRequester() }
    LaunchedEffect(byPercent) { runCatching { focus.requestFocus() } }
    fun submit() {
        val v = value ?: return
        if (!valid) return
        if (byPercent) onPercent(v / 100f) else onPage(v)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_go_to_page)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !byPercent, onClick = { byPercent = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                        Text(stringResource(R.string.reader_go_to_mode_page))
                    }
                    SegmentedButton(selected = byPercent, onClick = { byPercent = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text(stringResource(R.string.reader_go_to_mode_percent))
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    isError = text.isNotEmpty() && !valid,
                    label = { Text(if (byPercent) stringResource(R.string.reader_go_to_percent_hint) else stringResource(R.string.reader_go_to_page_hint, max)) },
                    supportingText = {
                        if (!byPercent) Text(pageOfText(page, pages, approximate) + if (approximate) "\n" + stringResource(R.string.reader_go_to_approx_note) else "")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = valid) { Text(stringResource(R.string.reader_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

/**
 * Thin status line at the bottom of the page while the menu is hidden: chapter, page and progress, clock and battery.
 */
@Composable
internal fun ReaderFooter(
    position: ReaderPosition,
    color: Color,
    showClock: Boolean,
    showChapter: Boolean,
    horizontalPadding: Int,
    pill: Boolean,
    modifier: Modifier = Modifier,
) {
    val pageInfo = buildString {
        if (position.bookPages > 0) {
            append(position.bookPage.coerceAtLeast(1)).append(" / ")
            if (position.approximate) append('≈')
            append(position.bookPages)
            append(" · ")
        }
        append((position.total * 100).toInt().coerceIn(0, 100)).append('%')
    }
    val clock = if (showClock) rememberClockText() else null
    val battery = if (showClock) rememberBatteryPercent() else null
    val style = MaterialTheme.typography.labelSmall
    if (pill) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            modifier = modifier.navigationBarsPadding().padding(bottom = 8.dp),
        ) {
            Text(listOfNotNull(clock, pageInfo).joinToString("  ·  "), style = style, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        return
    }
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(start = horizontalPadding.dp, end = horizontalPadding.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (clock != null) {
            Text(listOfNotNull(clock, battery?.let { "$it%" }).joinToString(" · "), style = style, color = color, maxLines = 1)
            Spacer(Modifier.width(12.dp))
        }
        val chapter = position.chapterTitle?.takeIf { showChapter && it.isNotBlank() }
        Text(chapter.orEmpty(), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(pageInfo, style = style, color = color, maxLines = 1)
    }
}

/** Wall clock in the user's 12/24-hour format, refreshed on the minute. */
@Composable
private fun rememberClockText(): String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    val time by produceState(format.format(Date()), format) {
        while (true) {
            value = format.format(Date())
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    return time
}

/** Battery level from the sticky broadcast; null when unknown. */
@Composable
private fun rememberBatteryPercent(): Int? {
    val context = LocalContext.current
    var level by remember { mutableIntStateOf(-1) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: Intent?) {
                val l = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val s = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                if (l >= 0 && s > 0) level = l * 100 / s
            }
        }
        val sticky = runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.getOrNull()
        sticky?.let { receiver.onReceive(context, it) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return level.takeIf { it >= 0 }
}
