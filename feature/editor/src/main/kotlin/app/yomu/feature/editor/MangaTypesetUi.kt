package app.yomu.feature.editor

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.yomu.core.ui.components.ConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import app.yomu.core.ui.R as CoreR

/**
 * Translation of a manga page by lettering (2.0.0): OCR finds the text (of the page or of a frame drawn around a bubble),
 * it is machine-translated, every block can be edited, and "Apply" draws the translation into the page itself (one
 * undoable step of the editor).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TypesetScreen(viewModel: EditorViewModel, s: MangaState, index: Int, onClose: () -> Unit) {
    if (s.pages.getOrNull(index) == null) {
        onClose()
        return
    }
    var base by remember { mutableStateOf<ImageBitmap?>(null) }
    var lettered by remember { mutableStateOf<ImageBitmap?>(null) }
    var blocks by remember { mutableStateOf<List<TypesetBlock>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TypesetBlock?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(index) {
        base = runCatching { viewModel.typesetPreview(index, emptyList()).asImageBitmap() }.getOrElse { e ->
            if (e is CancellationException) throw e
            viewModel.fail(e)
            null
        }
    }
    LaunchedEffect(showResult, blocks) {
        lettered = if (showResult && blocks.isNotEmpty()) runCatching { viewModel.typesetPreview(index, blocks).asImageBitmap() }.getOrNull() else null
    }

    fun recognize(region: RectF?) {
        scope.launch {
            busy = true
            val found = runCatching { viewModel.recognizeBlocks(index, region) }.getOrElse { e ->
                if (e is CancellationException) throw e
                viewModel.fail(e)
                emptyList()
            }
            blocks = if (region == null) found else blocks + found
            if (found.isEmpty()) viewModel.notify(R.string.editor_typeset_nothing)
            if (region != null) found.singleOrNull()?.takeIf { it.text.isBlank() }?.let { editing = it }
            busy = false
        }
    }

    val dismiss = { if (blocks.isEmpty()) onClose() else confirmClose = true }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { if (drawing) drawing = false else dismiss() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.editor_typeset_title, index + 1)) },
                    navigationIcon = { IconButton(onClick = dismiss) { Icon(Icons.Default.Close, stringResource(CoreR.string.close)) } },
                    actions = {
                        IconButton(onClick = { recognize(null) }, enabled = !busy) { Icon(Icons.Default.DocumentScanner, stringResource(R.string.editor_typeset_recognize)) }
                        IconButton(onClick = { drawing = !drawing; showResult = false }) {
                            Icon(Icons.Default.Crop, stringResource(R.string.editor_typeset_area), tint = if (drawing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { showResult = !showResult; drawing = false }, enabled = blocks.isNotEmpty()) {
                            Icon(if (showResult) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(R.string.editor_typeset_preview))
                        }
                        IconButton(onClick = { viewModel.applyTypeset(index, blocks); onClose() }, enabled = blocks.any { it.text.isNotBlank() } && !busy) {
                            Icon(Icons.Default.Check, stringResource(R.string.editor_typeset_apply))
                        }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                when {
                                    drawing -> R.string.editor_typeset_drawing_hint
                                    blocks.isEmpty() -> R.string.editor_typeset_hint
                                    else -> R.string.editor_typeset_edit_hint
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            },
        ) { padding ->
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                val image = base
                if (image == null) {
                    CircularProgressIndicator()
                    return@BoxWithConstraints
                }
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = min(boxW / image.width, boxH / image.height)
                val shownW = image.width * scale
                val shownH = image.height * scale
                Box(Modifier.size(with(density) { shownW.toDp() }, with(density) { shownH.toDp() })) {
                    Image(lettered ?: image, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                    if (!showResult) {
                        BlockOverlay(
                            blocks = blocks,
                            drawing = drawing,
                            size = Size(shownW, shownH),
                            onTap = { b -> editing = b },
                            onFrame = { r -> drawing = false; recognize(r) },
                        )
                    }
                }
            }
        }
    }

    editing?.let { block ->
        BlockDialog(
            block = block,
            viewModel = viewModel,
            onSave = { updated -> blocks = blocks.map { if (it.id == updated.id) updated else it }.let { l -> if (l.any { it.id == updated.id }) l else l + updated }; editing = null },
            onDelete = { blocks = blocks.filterNot { it.id == block.id }; editing = null },
            onDismiss = { editing = null },
        )
    }
    if (confirmClose) {
        ConfirmDialog(
            title = stringResource(R.string.editor_typeset_discard_title),
            text = stringResource(R.string.editor_typeset_discard_text),
            confirmLabel = stringResource(R.string.editor_discard_edits),
            onConfirm = { confirmClose = false; onClose() },
            onDismiss = { confirmClose = false },
        )
    }
}

/** Frames of the blocks over the page; taps pick a block, in drawing mode a drag draws a new frame. */
@Composable
private fun BlockOverlay(blocks: List<TypesetBlock>, drawing: Boolean, size: Size, onTap: (TypesetBlock) -> Unit, onFrame: (RectF) -> Unit) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxSize()
            .pointerInput(drawing, blocks) {
                if (drawing) {
                    detectDragGestures(
                        onDragStart = { o -> dragStart = o; dragEnd = o },
                        onDrag = { change, _ -> dragEnd = change.position },
                        onDragEnd = {
                            val a = dragStart
                            val b = dragEnd
                            dragStart = null
                            dragEnd = null
                            if (a != null && b != null && kotlin.math.abs(a.x - b.x) > 12 && kotlin.math.abs(a.y - b.y) > 12) {
                                onFrame(
                                    RectF(
                                        (min(a.x, b.x) / size.width).coerceIn(0f, 1f), (min(a.y, b.y) / size.height).coerceIn(0f, 1f),
                                        (max(a.x, b.x) / size.width).coerceIn(0f, 1f), (max(a.y, b.y) / size.height).coerceIn(0f, 1f),
                                    ),
                                )
                            }
                        },
                        onDragCancel = { dragStart = null; dragEnd = null },
                    )
                } else {
                    detectTapGestures { p ->
                        val x = p.x / size.width
                        val y = p.y / size.height
                        blocks.filter { x in it.left..it.right && y in it.top..it.bottom }.minByOrNull { it.width * it.height }?.let(onTap)
                    }
                }
            },
    ) {
        blocks.forEach { b ->
            val topLeft = Offset(b.left * size.width, b.top * size.height)
            val box = Size(b.width * size.width, b.height * size.height)
            drawRect(if (b.text.isBlank()) tertiary.copy(alpha = 0.18f) else primary.copy(alpha = 0.16f), topLeft, box)
            drawRect(if (b.text.isBlank()) tertiary else primary, topLeft, box, style = Stroke(width = 2.dp.toPx()))
        }
        val a = dragStart
        val e = dragEnd
        if (a != null && e != null) {
            val topLeft = Offset(min(a.x, e.x), min(a.y, e.y))
            val box = Size(kotlin.math.abs(a.x - e.x), kotlin.math.abs(a.y - e.y))
            drawRect(tertiary.copy(alpha = 0.2f), topLeft, box)
            drawRect(tertiary, topLeft, box, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockDialog(block: TypesetBlock, viewModel: EditorViewModel, onSave: (TypesetBlock) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var text by remember(block.id) { mutableStateOf(block.text) }
    var fill by remember(block.id) { mutableStateOf(block.fill) }
    var ink by remember(block.id) { mutableStateOf(block.ink) }
    var translating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_typeset_block)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (block.original.isNotBlank()) {
                    Text(stringResource(R.string.editor_typeset_original), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(block.original, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = !translating, onClick = {
                        scope.launch {
                            translating = true
                            text = viewModel.retranslate(block.original)
                            translating = false
                        }
                    }) { Text(stringResource(R.string.editor_typeset_retranslate)) }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.editor_typeset_text)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.editor_typeset_fill), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TypesetFill.entries.forEach { f ->
                        FilterChip(selected = fill == f, onClick = { fill = f }, label = { Text(stringResource(fillLabel(f))) })
                    }
                }
                Text(stringResource(R.string.editor_typeset_ink), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TypesetInk.entries.forEach { i ->
                        FilterChip(selected = ink == i, onClick = { ink = i }, label = { Text(stringResource(inkLabel(i))) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(block.copy(text = text, fill = fill, ink = ink)) }) { Text(stringResource(CoreR.string.ok)) } },
        dismissButton = { TextButton(onClick = onDelete) { Text(stringResource(CoreR.string.delete)) } },
    )
}

private fun fillLabel(f: TypesetFill) = when (f) {
    TypesetFill.AUTO -> R.string.editor_typeset_fill_auto
    TypesetFill.WHITE -> R.string.editor_typeset_fill_white
    TypesetFill.BLACK -> R.string.editor_typeset_fill_black
    TypesetFill.NONE -> R.string.editor_typeset_fill_none
}

private fun inkLabel(i: TypesetInk) = when (i) {
    TypesetInk.AUTO -> R.string.editor_typeset_ink_auto
    TypesetInk.BLACK -> R.string.editor_typeset_ink_black
    TypesetInk.WHITE -> R.string.editor_typeset_ink_white
}
