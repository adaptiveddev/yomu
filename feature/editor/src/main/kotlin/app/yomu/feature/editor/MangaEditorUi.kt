package app.yomu.feature.editor

import androidx.compose.material.icons.filled.Translate
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.ScreenErrors
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.SheetAction
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.TextInputDialog
import app.yomu.core.ui.components.YomuSheet
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import kotlin.math.roundToInt
import app.yomu.core.ui.R as CoreR

private sealed interface MangaDialog {
    data object Delete : MangaDialog
    data object MoveTo : MangaDialog
    data object Crop : MangaDialog
    data object Adjust : MangaDialog
    data class Recompress(val all: Boolean) : MangaDialog
    data object Info : MangaDialog
    data object More : MangaDialog
    data object AutoCropAll : MangaDialog
    data object TranslatePages : MangaDialog
}

/** Page grid of a CBZ/CBR/folder working copy: reorder, rotate, crop, colour-correct, split/join, recompress, ComicInfo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MangaEditor(viewModel: EditorViewModel, status: EditorStatus, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val state by viewModel.manga.collectAsStateWithLifecycle()
    val book by viewModel.book.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(setOf<Int>()) }
    var dialog by remember { mutableStateOf<MangaDialog?>(null) }
    var preview by remember { mutableStateOf<Int?>(null) }
    var discard by remember { mutableStateOf(false) }
    var insertAt by remember { mutableIntStateOf(0) }
    var typeset by remember { mutableStateOf<Int?>(null) }
    val exports = listOf(ExportType.CBZ, ExportType.PDF, ExportType.EPUB)
    val export = rememberExportLauncher(viewModel, exports)
    val insertPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.insertPages(insertAt, uris)
    }
    val s = state ?: return
    val pages = s.pages
    LaunchedEffect(pages.size) { selection = selection.filter { it < pages.size }.toSet() }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    fun insert(at: Int) {
        insertAt = at
        insertPicker.launch(arrayOf("image/*"))
    }

    /** Moves the selection so that it starts at [position] of the remaining pages and keeps it selected. */
    fun moveSelection(position: Int) {
        if (selection.isEmpty()) return
        val count = selection.size
        val target = position.coerceIn(0, pages.size - count)
        viewModel.movePages(selection, target)
        selection = (target until target + count).toSet()
    }

    Scaffold(
        topBar = {
            EditorTopBar(
                title = s.info.title.ifBlank { book?.title.orEmpty() },
                subtitle = (
                    if (selection.isNotEmpty()) pluralStringResource(R.plurals.editor_selected, selection.size, selection.size)
                    else pluralStringResource(R.plurals.editor_pages, pages.size, pages.size)
                    ) + if (status.dirty) " · " + stringResource(R.string.editor_unsaved) else "",
                status = status,
                onBack = onBack,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onSave = { viewModel.saveToBook() },
            ) { dismiss ->
                SaveMenuItems(viewModel, exports, export, { discard = true }, dismiss) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_comic_info)) },
                        leadingIcon = { Icon(Icons.Default.Info, null) },
                        onClick = { dismiss(); dialog = MangaDialog.Info },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.select_all)) },
                        leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                        onClick = { dismiss(); selection = pages.indices.toSet() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_insert_start)) },
                        leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null) },
                        onClick = { dismiss(); insert(0) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_insert_end)) },
                        leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null) },
                        onClick = { dismiss(); insert(pages.size) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_auto_crop_all)) },
                        leadingIcon = { Icon(Icons.Default.AutoFixHigh, null) },
                        onClick = { dismiss(); dialog = MangaDialog.AutoCropAll },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_recompress_all)) },
                        leadingIcon = { Icon(Icons.Default.Compress, null) },
                        onClick = { dismiss(); dialog = MangaDialog.Recompress(all = true) },
                    )
                }
            }
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                BottomAppBar(
                    actions = {
                        val enabled = !status.busy
                        IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Default.Close, stringResource(CoreR.string.clear)) }
                        IconButton(onClick = { moveSelection(selection.min() - 1) }, enabled = enabled && selection.min() > 0) {
                            Icon(Icons.Default.ChevronLeft, stringResource(R.string.editor_move_back))
                        }
                        IconButton(onClick = { moveSelection(selection.min() + 1) }, enabled = enabled && selection.max() < pages.lastIndex) {
                            Icon(Icons.Default.ChevronRight, stringResource(R.string.editor_move_forward))
                        }
                        IconButton(onClick = { viewModel.rotatePages(selection, 90) }, enabled = enabled) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.editor_rotate))
                        }
                        IconButton(onClick = { dialog = MangaDialog.Crop }, enabled = enabled) { Icon(Icons.Default.Crop, stringResource(R.string.editor_crop)) }
                        IconButton(onClick = { dialog = MangaDialog.Adjust }, enabled = enabled) { Icon(Icons.Default.Tune, stringResource(R.string.editor_adjust)) }
                        IconButton(onClick = { dialog = MangaDialog.Delete }, enabled = enabled && selection.size < pages.size) {
                            Icon(Icons.Default.Delete, stringResource(CoreR.string.delete))
                        }
                        IconButton(onClick = { dialog = MangaDialog.More }) { Icon(Icons.Default.MoreVert, stringResource(CoreR.string.more)) }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(pages, key = { _, p -> p.file }) { i, page ->
                val selected = i in selection
                PageThumb(
                    page = page,
                    root = s,
                    number = i + 1,
                    cover = i == 0,
                    selected = selected,
                    modifier = Modifier.animateItem().combinedClickable(
                        onClick = {
                            if (selection.isNotEmpty()) selection = if (selected) selection - i else selection + i else preview = i
                        },
                        onLongClick = { selection = if (selected) selection - i else selection + i },
                    ),
                )
            }
        }
    }

    preview?.let { start -> PagePreview(s, start, onTranslate = { page -> preview = null; typeset = page }) { preview = null } }
    typeset?.let { page -> TypesetScreen(viewModel, s, page) { typeset = null } }
    if (discard) DiscardDialog(viewModel) { discard = false }

    when (val d = dialog) {
        MangaDialog.Delete -> ConfirmDialog(
            title = pluralStringResource(R.plurals.editor_delete_pages, selection.size, selection.size),
            text = stringResource(R.string.editor_delete_pages_text),
            confirmLabel = stringResource(CoreR.string.delete),
            onConfirm = {
                viewModel.deletePages(selection)
                selection = emptySet()
            },
            onDismiss = { dialog = null },
        )
        MangaDialog.MoveTo -> TextInputDialog(
            title = stringResource(R.string.editor_move_to_page),
            initial = (selection.minOrNull()?.plus(1) ?: 1).toString(),
            label = stringResource(R.string.editor_page_number, pages.size - selection.size + 1),
            onConfirm = { text ->
                text.trim().toIntOrNull()?.let { moveSelection(it - 1) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        MangaDialog.Crop -> CropDialog(viewModel, s, selection) { dialog = null }
        MangaDialog.Adjust -> AdjustDialog(viewModel, s, selection) { dialog = null }
        is MangaDialog.Recompress -> RecompressDialog(
            count = if (d.all) pages.size else selection.size,
            onApply = { r ->
                viewModel.recompressPages(if (d.all) pages.indices.toSet() else selection, r)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        MangaDialog.Info -> ComicInfoDialog(s.info, onSave = { viewModel.saveComicInfo(it); dialog = null }, onDismiss = { dialog = null })
        MangaDialog.TranslatePages -> ConfirmDialog(
            title = stringResource(R.string.editor_translate_pages),
            text = stringResource(R.string.editor_translate_pages_text, selection.size),
            confirmLabel = stringResource(R.string.editor_translate_start),
            onConfirm = { viewModel.translatePages(selection) },
            onDismiss = { dialog = null },
        )
        MangaDialog.AutoCropAll -> ConfirmDialog(
            title = stringResource(R.string.editor_auto_crop_all),
            text = stringResource(R.string.editor_auto_crop_all_text),
            confirmLabel = stringResource(R.string.editor_apply),
            onConfirm = { viewModel.cropPages(pages.indices.toSet(), null, auto = true) },
            onDismiss = { dialog = null },
        )
        MangaDialog.More -> YomuSheet(onDismiss = { dialog = null }) {
            SheetTitle(pluralStringResource(R.plurals.editor_selected, selection.size, selection.size))
            SheetAction(Icons.Default.SwapHoriz, stringResource(R.string.editor_move_to_page)) { dialog = MangaDialog.MoveTo }
            SheetAction(Icons.Default.AddPhotoAlternate, stringResource(R.string.editor_insert_after)) {
                dialog = null
                insert((selection.maxOrNull() ?: pages.lastIndex) + 1)
            }
            SheetAction(Icons.AutoMirrored.Filled.RotateLeft, stringResource(R.string.editor_rotate_left)) {
                dialog = null
                viewModel.rotatePages(selection, -90)
            }
            if (selection.size == 1) {
                SheetAction(Icons.Default.Translate, stringResource(R.string.editor_translate_page)) {
                    dialog = null
                    typeset = selection.first()
                }
            }
            SheetAction(Icons.Default.Translate, stringResource(R.string.editor_translate_pages)) { dialog = MangaDialog.TranslatePages }
            if (selection.size == 1) {
                val index = selection.first()
                SheetAction(Icons.Default.Image, stringResource(R.string.editor_set_cover)) {
                    dialog = null
                    viewModel.setCoverPage(index)
                    selection = setOf(0)
                }
                SheetAction(Icons.Default.VerticalSplit, stringResource(R.string.editor_split_spread)) {
                    dialog = null
                    viewModel.splitSpread(index)
                    selection = emptySet()
                }
                if (index < pages.lastIndex) {
                    SheetAction(Icons.AutoMirrored.Filled.CallMerge, stringResource(R.string.editor_join_next)) {
                        dialog = null
                        viewModel.joinPages(index, index + 1)
                        selection = setOf(index)
                    }
                }
            } else if (selection.size == 2) {
                val (a, b) = selection.sorted()
                SheetAction(Icons.AutoMirrored.Filled.CallMerge, stringResource(R.string.editor_join_pages)) {
                    dialog = null
                    viewModel.joinPages(a, b)
                    selection = setOf(a)
                }
            }
            SheetAction(Icons.Default.Compress, stringResource(R.string.editor_recompress)) { dialog = MangaDialog.Recompress(all = false) }
            SheetAction(Icons.Default.SelectAll, stringResource(CoreR.string.select_all)) {
                dialog = null
                selection = pages.indices.toSet()
            }
        }
        null -> Unit
    }
}

@Composable
private fun PageThumb(page: MangaPage, root: MangaState, number: Int, cover: Boolean, selected: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
        ) {
            AsyncImage(
                model = fileModel(java.io.File(root.root, page.file)),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(if (selected) 6.dp else 0.dp).rotated(page.rotation),
            )
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50)),
                )
            }
            if (cover) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(bottomEnd = 8.dp), modifier = Modifier.align(Alignment.TopStart)) {
                    Text(stringResource(R.string.editor_cover), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Text(number.toString(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Rotates content by [degrees]; for quarter turns the content is scaled so that it still fits the (portrait) box. */
private fun Modifier.rotated(degrees: Int): Modifier = if (degrees == 0) this else graphicsLayer {
    rotationZ = degrees.toFloat()
    if (degrees % 180 != 0) {
        scaleX = 0.7f
        scaleY = 0.7f
    }
}

@Composable
private fun PagePreview(s: MangaState, start: Int, onTranslate: (Int) -> Unit, onDismiss: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (s.pages.size - 1).coerceAtLeast(0))) { s.pages.size }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { s.pages[it].file }) { i ->
                val page = s.pages[i]
                ZoomableAsyncImage(
                    model = fileModel(java.io.File(s.root, page.file)),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = page.rotation.toFloat() },
                )
            }
            Text(
                "${pager.currentPage + 1} / ${s.pages.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Default.Close, stringResource(CoreR.string.close), tint = Color.White)
            }
            IconButton(onClick = { onTranslate(pager.currentPage) }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Default.Translate, stringResource(R.string.editor_translate_page), tint = Color.White)
            }
        }
    }
}

@Composable
private fun CropDialog(viewModel: EditorViewModel, s: MangaState, selection: Set<Int>, onDismiss: () -> Unit) {
    val first = selection.minOrNull() ?: return
    val page = s.pages.getOrNull(first) ?: return
    var rect by remember { mutableStateOf(CropRect()) }
    var autoEach by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_crop)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 280.dp).aspectRatio(0.7f).align(Alignment.CenterHorizontally)) {
                    AsyncImage(
                        model = fileModel(java.io.File(s.root, page.file)),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize().rotated(page.rotation),
                    )
                    if (!autoEach) {
                        val shade = Color.Black.copy(alpha = 0.55f)
                        val w = maxWidth
                        val h = maxHeight
                        Box(Modifier.size(w * rect.left, h).background(shade))
                        Box(Modifier.offset(x = w * (1 - rect.right)).size(w * rect.right, h).background(shade))
                        Box(Modifier.offset(x = w * rect.left).size(w * (1 - rect.left - rect.right), h * rect.top).background(shade))
                        Box(
                            Modifier.offset(x = w * rect.left, y = h * (1 - rect.bottom)).size(w * (1 - rect.left - rect.right), h * rect.bottom).background(shade),
                        )
                    }
                }
                if (selection.size > 1) {
                    Text(
                        pluralStringResource(R.plurals.editor_applies_to, selection.size, selection.size),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp).toggleable(value = autoEach, role = Role.Checkbox) { autoEach = it }) {
                    Checkbox(checked = autoEach, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(stringResource(R.string.editor_auto_crop_each), style = MaterialTheme.typography.bodyMedium)
                }
                if (!autoEach) {
                    OutlinedButton(onClick = { scope.launch(ScreenErrors) { rect = viewModel.autoCropPreview(first) } }) {
                        Icon(Icons.Default.AutoFixHigh, null)
                        Text(stringResource(R.string.editor_auto_detect), Modifier.padding(start = 8.dp))
                    }
                    CropSlider(stringResource(R.string.editor_crop_left), rect.left) { rect = rect.copy(left = it) }
                    CropSlider(stringResource(R.string.editor_crop_right), rect.right) { rect = rect.copy(right = it) }
                    CropSlider(stringResource(R.string.editor_crop_top), rect.top) { rect = rect.copy(top = it) }
                    CropSlider(stringResource(R.string.editor_crop_bottom), rect.bottom) { rect = rect.copy(bottom = it) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.cropPages(selection, if (autoEach) null else rect, auto = autoEach)
                    onDismiss()
                },
                enabled = autoEach || !rect.isEmpty,
            ) { Text(stringResource(R.string.editor_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

@Composable
private fun CropSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = 0f..0.4f)
    }
}

@Composable
private fun AdjustDialog(viewModel: EditorViewModel, s: MangaState, selection: Set<Int>, onDismiss: () -> Unit) {
    val first = selection.minOrNull() ?: return
    val page = s.pages.getOrNull(first) ?: return
    var a by remember { mutableStateOf(Adjust()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_adjust)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).aspectRatio(0.7f).align(Alignment.CenterHorizontally)) {
                    AsyncImage(
                        model = fileModel(java.io.File(s.root, page.file)),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = if (a.isIdentity) null else ColorFilter.colorMatrix(ColorMatrix(a.matrix())),
                        modifier = Modifier.fillMaxSize().rotated(page.rotation),
                    )
                }
                if (selection.size > 1) {
                    Text(
                        pluralStringResource(R.plurals.editor_applies_to, selection.size, selection.size),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                AdjustSlider(stringResource(R.string.editor_brightness), a.brightness, -0.5f..0.5f, "%+.0f%%".format(a.brightness * 100)) { a = a.copy(brightness = it) }
                AdjustSlider(stringResource(R.string.editor_contrast), a.contrast, 0.5f..2f, "%.0f%%".format(a.contrast * 100)) { a = a.copy(contrast = it) }
                AdjustSlider(stringResource(R.string.editor_black_point), a.blackPoint, 0f..0.45f, "%.0f%%".format(a.blackPoint * 100)) { a = a.copy(blackPoint = it) }
                AdjustSlider(stringResource(R.string.editor_white_point), a.whitePoint, 0.55f..1f, "%.0f%%".format(a.whitePoint * 100)) { a = a.copy(whitePoint = it) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().toggleable(value = a.grayscale, role = Role.Switch) { a = a.copy(grayscale = it) }) {
                    Text(stringResource(R.string.editor_grayscale), Modifier.weight(1f))
                    Switch(checked = a.grayscale, onCheckedChange = null)
                }
                TextButton(onClick = { a = Adjust() }, enabled = !a.isIdentity) { Text(stringResource(R.string.editor_reset)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.adjustPages(selection, a)
                    onDismiss()
                },
                enabled = !a.isIdentity,
            ) { Text(stringResource(R.string.editor_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

@Composable
private fun AdjustSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueLabel: String, onChange: (Float) -> Unit) {
    Column {
        Text("$label: $valueLabel", style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun RecompressDialog(count: Int, onApply: (Recompress) -> Unit, onDismiss: () -> Unit) {
    var format by remember { mutableStateOf(OutFormat.JPEG) }
    var quality by remember { mutableStateOf(85f) }
    var maxWidth by remember { mutableIntStateOf(0) }
    val widths = listOf(0, 2400, 1800, 1400, 1080)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_recompress)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.editor_applies_to, count, count), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.editor_format), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutFormat.entries.forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.name) }) }
                }
                if (format != OutFormat.PNG) {
                    Text(stringResource(R.string.editor_quality, quality.roundToInt()), style = MaterialTheme.typography.labelLarge)
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 40f..100f, steps = 11)
                }
                Text(stringResource(R.string.editor_max_width), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    widths.forEach { w ->
                        FilterChip(
                            selected = maxWidth == w,
                            onClick = { maxWidth = w },
                            label = { Text(if (w == 0) stringResource(R.string.editor_original) else w.toString(), maxLines = 1) },
                        )
                    }
                }
                Text(stringResource(R.string.editor_recompress_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(Recompress(format, quality.roundToInt(), maxWidth)) }) { Text(stringResource(R.string.editor_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

@Composable
private fun ComicInfoDialog(info: ComicInfo, onSave: (ComicInfo) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(info.title) }
    var series by remember { mutableStateOf(info.series) }
    var number by remember { mutableStateOf(info.number) }
    var volume by remember { mutableStateOf(info.volume) }
    var writer by remember { mutableStateOf(info.writer) }
    var penciller by remember { mutableStateOf(info.penciller) }
    var genre by remember { mutableStateOf(info.genre) }
    var language by remember { mutableStateOf(info.languageIso) }
    var year by remember { mutableStateOf(info.year) }
    var summary by remember { mutableStateOf(info.summary) }
    var manga by remember { mutableStateOf(info.manga) }
    val edited = info.copy(
        title = title.trim(), series = series.trim(), number = number.trim(), volume = volume.trim(), writer = writer.trim(),
        penciller = penciller.trim(), genre = genre.trim(), languageIso = language.trim(), year = year.trim(), summary = summary.trim(), manga = manga,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_comic_info)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.editor_meta_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(series, { series = it }, label = { Text(stringResource(R.string.editor_meta_series)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.editor_meta_number)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(volume, { volume = it }, label = { Text(stringResource(R.string.editor_meta_volume)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(writer, { writer = it }, label = { Text(stringResource(R.string.editor_meta_writer)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(penciller, { penciller = it }, label = { Text(stringResource(R.string.editor_meta_artist)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(genre, { genre = it }, label = { Text(stringResource(R.string.editor_meta_genre)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(language, { language = it }, label = { Text(stringResource(R.string.editor_meta_language)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        year, { year = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.editor_meta_year)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp),
                    )
                }
                OutlinedTextField(summary, { summary = it }, label = { Text(stringResource(R.string.editor_meta_description)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().toggleable(value = manga, role = Role.Switch) { manga = it }) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.editor_rtl))
                        Text(stringResource(R.string.editor_rtl_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = manga, onCheckedChange = null)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(edited) }, enabled = edited != info) { Text(stringResource(CoreR.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}
