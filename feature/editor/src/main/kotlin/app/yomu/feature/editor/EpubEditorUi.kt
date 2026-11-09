package app.yomu.feature.editor

import app.yomu.data.book.EpubPaths
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import app.yomu.core.ui.components.ChapterPickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.Files
import app.yomu.core.ui.components.ChipItem
import app.yomu.core.ui.components.ChoiceDialog
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.FilterChipsRow
import app.yomu.core.ui.components.PreferenceHeader
import app.yomu.core.ui.components.PreferenceSwitch
import app.yomu.core.ui.components.SheetAction
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.TextInputDialog
import app.yomu.core.ui.components.YomuFab
import app.yomu.core.ui.components.YomuSheet
import app.yomu.core.ui.components.YomuTab
import app.yomu.data.book.EpubMeta
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import java.io.File
import app.yomu.core.ui.R as CoreR

private enum class EpubTab(@StringRes val label: Int) {
    CHAPTERS(R.string.editor_tab_chapters),
    CONTENTS(R.string.editor_tab_contents),
    METADATA(R.string.editor_tab_metadata),
    SEARCH(R.string.editor_tab_search),
    IMAGES(R.string.editor_tab_images),
    STYLES(R.string.editor_tab_styles),
    TOOLS(R.string.editor_tab_tools),
}

private sealed interface Overlay {
    data class Source(val path: String, val lang: CodeLang, val find: String? = null) : Overlay
    data class Visual(val path: String, val title: String) : Overlay
}

@Composable
internal fun fileModel(file: File): Any {
    val context = LocalContext.current
    return remember(file.path, file.lastModified()) {
        ImageRequest.Builder(context).data(file).memoryCacheKey("${file.path}@${file.lastModified()}").diskCachePolicy(CachePolicy.DISABLED).build()
    }
}

@Composable
internal fun EpubEditor(viewModel: EditorViewModel, status: EditorStatus, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val state by viewModel.epub.collectAsStateWithLifecycle()
    val book by viewModel.book.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(EpubTab.CHAPTERS) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var discard by remember { mutableStateOf(false) }
    var translate by remember { mutableStateOf(false) }
    var translateChapters by remember { mutableStateOf<Set<String>?>(null) }
    val exports = listOf(ExportType.EPUB, ExportType.TXT, ExportType.PDF)
    val export = rememberExportLauncher(viewModel, exports)
    val s = state ?: return

    when (val o = overlay) {
        is Overlay.Source -> {
            var text by remember(o) { mutableStateOf<String?>(null) }
            LaunchedEffect(o) {
                val read = viewModel.readSource(o.path)
                if (read == null) overlay = null else text = read
            }
            text?.let { initial ->
                SourceEditorScreen(
                    title = o.path.substringAfterLast('/'),
                    initialText = initial,
                    lang = o.lang,
                    initialFind = o.find,
                    checkXml = viewModel::checkXml,
                    onSave = { new -> viewModel.saveSource(o.path, new) { overlay = null } },
                    onClose = { overlay = null },
                )
            }
            return
        }
        is Overlay.Visual -> {
            val images = remember(s.version, o.path) {
                s.images.filter { it.mediaType.startsWith("image/") }.map { img ->
                    InsertableImage(EpubPaths.relative(o.path, img.path), viewModel.file(img.path), img.path.substringAfterLast('/'))
                }
            }
            VisualEditorScreen(
                title = o.title,
                file = viewModel.file(o.path),
                images = images,
                onSave = { html, openSource ->
                    viewModel.saveVisual(o.path, html) { overlay = if (openSource) Overlay.Source(o.path, CodeLang.MARKUP) else null }
                },
                onSource = { overlay = Overlay.Source(o.path, CodeLang.MARKUP) },
                onClose = { overlay = null },
            )
            return
        }
        null -> Unit
    }

    Scaffold(
        topBar = {
            EditorTopBar(
                title = s.meta.title.ifBlank { book?.title.orEmpty() },
                subtitle = pluralStringResource(R.plurals.editor_chapters, s.chapters.size, s.chapters.size) + if (status.dirty) " · " + stringResource(R.string.editor_unsaved) else "",
                status = status,
                onBack = onBack,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onSave = { viewModel.saveToBook() },
            ) { dismiss ->
                SaveMenuItems(viewModel, exports, export, { discard = true }, dismiss) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_translate)) },
                        leadingIcon = { Icon(Icons.Default.Translate, null) },
                        onClick = { dismiss(); translate = true },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                EpubTab.entries.forEach { t -> YomuTab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(t.label)) }) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    EpubTab.CHAPTERS -> ChaptersTab(
                        viewModel, s, status,
                        onEdit = { overlay = Overlay.Visual(it.path, it.title) },
                        onSource = { overlay = Overlay.Source(it, CodeLang.MARKUP) },
                        onTranslate = { translateChapters = setOf(it.path) },
                    )
                    EpubTab.CONTENTS -> TocTab(viewModel, s)
                    EpubTab.METADATA -> MetadataTab(viewModel, s)
                    EpubTab.SEARCH -> SearchTab(viewModel, s) { path, q -> overlay = Overlay.Source(path, CodeLang.MARKUP, q) }
                    EpubTab.IMAGES -> ImagesTab(viewModel, s)
                    EpubTab.STYLES -> StylesTab(viewModel, s) { overlay = Overlay.Source(it, CodeLang.CSS) }
                    EpubTab.TOOLS -> ToolsTab(viewModel, s) { translate = true }
                }
            }
        }
    }
    if (discard) DiscardDialog(viewModel) { discard = false }
    if (translate || translateChapters != null) TranslateDialog(viewModel, s, translateChapters) { translate = false; translateChapters = null }
}

// ---------------------------------------------------------------- chapters

private sealed interface ChapterDialog {
    data class Add(val after: Int) : ChapterDialog
    data class Rename(val chapter: EditorChapter) : ChapterDialog
    data class MoveTo(val chapter: EditorChapter) : ChapterDialog
    data class Split(val chapter: EditorChapter) : ChapterDialog
    data class Delete(val chapter: EditorChapter) : ChapterDialog
}

@Composable
private fun ChaptersTab(
    viewModel: EditorViewModel,
    s: EpubState,
    status: EditorStatus,
    onEdit: (EditorChapter) -> Unit,
    onSource: (String) -> Unit,
    onTranslate: (EditorChapter) -> Unit,
) {
    var selected by remember { mutableStateOf<EditorChapter?>(null) }
    var dialog by remember { mutableStateOf<ChapterDialog?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            itemsIndexed(s.chapters, key = { i, c -> "$i|${c.path}" }) { i, c ->
                ListItem(
                    headlineContent = { Text(c.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${c.path.substringAfterLast('/')} · ${Files.humanSize(c.size)}", maxLines = 1) },
                    leadingContent = { Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(28.dp)) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { viewModel.moveChapter(i, i - 1) }, enabled = i > 0 && !status.busy) {
                                Icon(Icons.Default.ArrowUpward, stringResource(R.string.editor_move_up))
                            }
                            IconButton(onClick = { viewModel.moveChapter(i, i + 1) }, enabled = i < s.chapters.lastIndex && !status.busy) {
                                Icon(Icons.Default.ArrowDownward, stringResource(R.string.editor_move_down))
                            }
                        }
                    },
                    modifier = Modifier.clickable { selected = c },
                )
            }
        }
        YomuFab(
            text = stringResource(R.string.editor_add_chapter),
            icon = Icons.Default.Add,
            onClick = { dialog = ChapterDialog.Add(s.chapters.lastIndex) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    selected?.let { c ->
        YomuSheet(onDismiss = { selected = null }) {
            SheetTitle(c.title)
            fun act(block: () -> Unit) {
                selected = null
                block()
            }
            SheetAction(Icons.Default.Edit, stringResource(R.string.editor_edit_visual)) { act { onEdit(c) } }
            SheetAction(Icons.Default.Code, stringResource(R.string.editor_edit_source)) { act { onSource(c.path) } }
            SheetAction(Icons.Default.Translate, stringResource(R.string.editor_translate_chapter)) { act { onTranslate(c) } }
            SheetAction(Icons.Default.DriveFileRenameOutline, stringResource(CoreR.string.rename)) { act { dialog = ChapterDialog.Rename(c) } }
            SheetAction(Icons.Default.Add, stringResource(R.string.editor_insert_after)) { act { dialog = ChapterDialog.Add(c.index) } }
            SheetAction(Icons.Default.SwapVert, stringResource(R.string.editor_move_to)) { act { dialog = ChapterDialog.MoveTo(c) } }
            SheetAction(Icons.Default.ContentCut, stringResource(R.string.editor_split)) { act { dialog = ChapterDialog.Split(c) } }
            if (c.index < s.chapters.lastIndex) {
                SheetAction(Icons.AutoMirrored.Filled.MergeType, stringResource(R.string.editor_merge_next)) { act { viewModel.mergeWithNext(c.path) } }
            }
            if (s.chapters.size > 1) {
                SheetAction(Icons.Default.Delete, stringResource(CoreR.string.delete), destructive = true) { act { dialog = ChapterDialog.Delete(c) } }
            }
        }
    }
    when (val d = dialog) {
        is ChapterDialog.Add -> TextInputDialog(
            title = stringResource(R.string.editor_add_chapter),
            initial = stringResource(R.string.editor_new_chapter),
            label = stringResource(R.string.editor_chapter_title),
            onConfirm = {
                dialog = null
                viewModel.addChapter(d.after, it.trim().ifEmpty { "…" })
            },
            onDismiss = { dialog = null },
        )
        is ChapterDialog.Rename -> TextInputDialog(
            title = stringResource(CoreR.string.rename),
            initial = d.chapter.title,
            label = stringResource(R.string.editor_chapter_title),
            onConfirm = {
                dialog = null
                if (it.isNotBlank()) viewModel.renameChapter(d.chapter.path, it.trim())
            },
            onDismiss = { dialog = null },
        )
        is ChapterDialog.MoveTo -> TextInputDialog(
            title = stringResource(R.string.editor_move_to),
            initial = "${d.chapter.index + 1}",
            label = stringResource(R.string.editor_position, s.chapters.size),
            onConfirm = {
                dialog = null
                it.trim().toIntOrNull()?.let { pos -> viewModel.moveChapter(d.chapter.index, (pos - 1).coerceIn(0, s.chapters.lastIndex)) }
            },
            onDismiss = { dialog = null },
        )
        is ChapterDialog.Split -> SplitDialog(viewModel, d.chapter) { dialog = null }
        is ChapterDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.editor_delete_chapter_title),
            text = stringResource(R.string.editor_delete_chapter_text, d.chapter.title),
            confirmLabel = stringResource(CoreR.string.delete),
            onConfirm = { viewModel.deleteChapter(d.chapter.path) },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun SplitDialog(viewModel: EditorViewModel, chapter: EditorChapter, onDismiss: () -> Unit) {
    var blocks by remember { mutableStateOf<List<BlockInfo>?>(null) }
    var checked by remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(chapter.path) { blocks = viewModel.blocks(chapter.path) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_split)) },
        text = {
            Column {
                Text(stringResource(R.string.editor_split_hint), style = MaterialTheme.typography.bodyMedium)
                val list = blocks.orEmpty().drop(1)
                if (list.any { it.heading }) {
                    TextButton(onClick = { checked = list.filter { it.heading }.map { it.index }.toSet() }) { Text(stringResource(R.string.editor_split_headings)) }
                }
                if (blocks != null && list.isEmpty()) Text(stringResource(R.string.editor_split_nothing), color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list, key = { it.index }) { b ->
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = b.index in checked, role = Role.Checkbox) { checked = if (b.index in checked) checked - b.index else checked + b.index },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = b.index in checked, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                            Text(
                                b.preview,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (b.heading) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = checked.isNotEmpty(), onClick = {
                viewModel.splitChapter(chapter.path, checked.sorted())
                onDismiss()
            }) { Text(stringResource(R.string.editor_split_into, checked.size + 1)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

// ---------------------------------------------------------------- table of contents

@Composable
private fun TocTab(viewModel: EditorViewModel, s: EpubState) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addFor by remember { mutableStateOf<EditorChapter?>(null) }
    val entries = s.toc
    fun update(list: List<TocEntry>) = viewModel.updateToc(list)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = viewModel::tocFromHeadings, label = { Text(stringResource(R.string.editor_toc_from_headings)) })
            AssistChip(onClick = viewModel::tocFromChapters, label = { Text(stringResource(R.string.editor_toc_from_chapters)) })
            AssistChip(onClick = { adding = true }, label = { Text(stringResource(R.string.editor_toc_add)) }, leadingIcon = { Icon(Icons.Default.Add, null) })
        }
        if (entries.isEmpty()) {
            EmptyState(title = stringResource(R.string.editor_toc_empty), modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(entries) { i, e ->
                    ListItem(
                        headlineContent = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(e.path.substringAfterLast('/') + (e.fragment?.let { "#$it" } ?: ""), maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.clickable { selected = i }.padding(start = (e.level * 20).dp),
                    )
                }
            }
        }
    }
    selected?.let { i ->
        val e = entries.getOrNull(i) ?: return@let
        YomuSheet(onDismiss = { selected = null }) {
            SheetTitle(e.title)
            fun act(list: List<TocEntry>) {
                selected = null
                update(list)
            }
            SheetAction(Icons.Default.DriveFileRenameOutline, stringResource(CoreR.string.rename)) { selected = null; renaming = i }
            if (i > 0) SheetAction(Icons.Default.ArrowUpward, stringResource(R.string.editor_move_up)) { act(entries.toMutableList().apply { add(i - 1, removeAt(i)) }) }
            if (i < entries.lastIndex) SheetAction(Icons.Default.ArrowDownward, stringResource(R.string.editor_move_down)) { act(entries.toMutableList().apply { add(i + 1, removeAt(i)) }) }
            if (i > 0 && e.level <= entries[i - 1].level) {
                SheetAction(Icons.AutoMirrored.Filled.FormatIndentIncrease, stringResource(R.string.editor_toc_indent)) { act(entries.toMutableList().apply { set(i, e.copy(level = e.level + 1)) }) }
            }
            if (e.level > 0) {
                SheetAction(Icons.AutoMirrored.Filled.FormatIndentDecrease, stringResource(R.string.editor_toc_outdent)) { act(entries.toMutableList().apply { set(i, e.copy(level = e.level - 1)) }) }
            }
            SheetAction(Icons.Default.Delete, stringResource(CoreR.string.delete), destructive = true) { act(entries.filterIndexed { j, _ -> j != i }) }
        }
    }
    renaming?.let { i ->
        val e = entries.getOrNull(i) ?: return@let
        TextInputDialog(
            title = stringResource(CoreR.string.rename),
            initial = e.title,
            onConfirm = {
                renaming = null
                if (it.isNotBlank()) update(entries.toMutableList().apply { set(i, e.copy(title = it.trim())) })
            },
            onDismiss = { renaming = null },
        )
    }
    if (adding) {
        ChoiceDialog(
            title = stringResource(R.string.editor_toc_pick_chapter),
            options = s.chapters.map { it to "${it.index + 1}. ${it.title}" },
            selected = null,
            onSelect = {
                adding = false
                addFor = it
            },
            onDismiss = { adding = false },
        )
    }
    addFor?.let { c ->
        TextInputDialog(
            title = stringResource(R.string.editor_toc_add),
            initial = c.title,
            label = stringResource(R.string.editor_chapter_title),
            onConfirm = { title ->
                addFor = null
                val order = s.chapters.associate { it.path to it.index }
                val at = entries.indexOfLast { (order[it.path] ?: Int.MAX_VALUE) <= c.index } + 1
                update(entries.toMutableList().apply { add(at, TocEntry(title.trim().ifEmpty { c.title }, c.path)) })
            },
            onDismiss = { addFor = null },
        )
    }
}

// ---------------------------------------------------------------- metadata

@Composable
private fun MetadataTab(viewModel: EditorViewModel, s: EpubState) {
    val m = s.meta
    var title by remember(m) { mutableStateOf(m.title) }
    var authors by remember(m) { mutableStateOf(m.authors.joinToString(", ")) }
    var series by remember(m) { mutableStateOf(m.series.orEmpty()) }
    var index by remember(m) { mutableStateOf(m.seriesIndex?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }.orEmpty()) }
    var language by remember(m) { mutableStateOf(m.language.orEmpty()) }
    var publisher by remember(m) { mutableStateOf(m.publisher.orEmpty()) }
    var date by remember(m) { mutableStateOf(m.date.orEmpty()) }
    var subjects by remember(m) { mutableStateOf(m.subjects.joinToString(", ")) }
    var description by remember(m) { mutableStateOf(m.description.orEmpty()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::setCover) }
    fun list(v: String) = v.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    val edited = EpubMeta(
        title = title.trim(),
        authors = list(authors),
        language = language.trim().ifEmpty { null },
        description = description.trim().ifEmpty { null },
        publisher = publisher.trim().ifEmpty { null },
        series = series.trim().ifEmpty { null },
        seriesIndex = index.replace(',', '.').trim().toFloatOrNull(),
        identifier = m.identifier,
        subjects = list(subjects),
        date = date.trim().ifEmpty { null },
    )
    val original = EpubMeta(
        title = m.title.trim(), authors = m.authors, language = m.language?.trim()?.ifEmpty { null }, description = m.description?.trim()?.ifEmpty { null },
        publisher = m.publisher?.trim()?.ifEmpty { null }, series = m.series?.trim()?.ifEmpty { null }, seriesIndex = m.seriesIndex, identifier = m.identifier,
        subjects = m.subjects, date = m.date?.trim()?.ifEmpty { null },
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val cover = s.coverPath?.let { File(s.root, it) }
            Card(Modifier.width(96.dp).aspectRatio(2f / 3f)) {
                if (cover != null && cover.isFile) {
                    AsyncImage(model = fileModel(cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Image, null) }
                }
            }
            Column {
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.editor_change_cover)) }
                if (s.epub3) Text("EPUB 3", style = MaterialTheme.typography.labelSmall) else Text("EPUB 2", style = MaterialTheme.typography.labelSmall)
            }
        }
        OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.editor_meta_title)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(authors, { authors = it }, label = { Text(stringResource(R.string.editor_meta_authors)) }, supportingText = { Text(stringResource(R.string.editor_comma_separated)) }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(series, { series = it }, label = { Text(stringResource(R.string.editor_meta_series)) }, modifier = Modifier.weight(1f))
            OutlinedTextField(index, { index = it }, label = { Text("#") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(88.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(language, { language = it }, label = { Text(stringResource(R.string.editor_meta_language)) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(date, { date = it }, label = { Text(stringResource(R.string.editor_meta_date)) }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(publisher, { publisher = it }, label = { Text(stringResource(R.string.editor_meta_publisher)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(subjects, { subjects = it }, label = { Text(stringResource(R.string.editor_meta_tags)) }, supportingText = { Text(stringResource(R.string.editor_comma_separated)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.editor_meta_description)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.saveMeta(edited) }, enabled = edited != original, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.editor_apply)) }
    }
}

// ---------------------------------------------------------------- search & replace

@Composable
private fun SearchTab(viewModel: EditorViewModel, s: EpubState, onOpen: (String, String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var regex by rememberSaveable { mutableStateOf(false) }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    var inMarkup by rememberSaveable { mutableStateOf(false) }
    var scope by rememberSaveable { mutableStateOf<String?>(null) }
    var pickScope by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    val hits by viewModel.searchHits.collectAsStateWithLifecycle()
    val q = SearchQuery(query, regex, matchCase, wholeWord, inMarkup)
    val scopeList = scope?.let { listOf(it) }
    val titles = s.chapters.associate { it.path to it.title }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it; viewModel.clearSearch() }, label = { Text(stringResource(R.string.editor_find_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(replacement, { replacement = it }, label = { Text(stringResource(R.string.editor_replace_with)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            FilterChipsRow(
                listOf(
                    ChipItem(stringResource(R.string.editor_opt_regex), regex, { regex = !regex }),
                    ChipItem(stringResource(R.string.editor_opt_case), matchCase, { matchCase = !matchCase }),
                    ChipItem(stringResource(R.string.editor_opt_word), wholeWord, { wholeWord = !wholeWord }),
                    ChipItem(stringResource(R.string.editor_opt_markup), inMarkup, { inMarkup = !inMarkup }),
                ),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { pickScope = true }, label = { Text(scope?.let { titles[it] } ?: stringResource(R.string.editor_scope_book), maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(0.01f))
                OutlinedButton(onClick = { viewModel.search(q, scopeList) }, enabled = query.isNotEmpty()) { Text(stringResource(CoreR.string.search)) }
                Button(onClick = { confirmReplace = true }, enabled = query.isNotEmpty()) { Icon(Icons.Default.FindReplace, null); Text(stringResource(R.string.editor_replace_all)) }
            }
            if (inMarkup) Text(stringResource(R.string.editor_markup_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        val list = hits
        if (list != null) {
            item {
                val total = list.sumOf { it.count }
                Text(
                    if (total == 0) stringResource(R.string.editor_no_matches) else pluralStringResource(R.plurals.editor_matches, total, total, list.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(list, key = { it.path }) { hit ->
                ListItem(
                    headlineContent = { Text("${titles[hit.path] ?: hit.path.substringAfterLast('/')} · ${hit.count}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(hit.previews.joinToString("\n") { "…${it.trim()}…" }, style = MaterialTheme.typography.bodySmall, maxLines = 4) },
                    modifier = Modifier.clickable { onOpen(hit.path, if (regex) null else query) },
                )
            }
        }
    }
    if (pickScope) {
        ChoiceDialog(
            title = stringResource(R.string.editor_scope),
            options = listOf<Pair<String?, String>>(null to stringResource(R.string.editor_scope_book)) + s.chapters.map { it.path to "${it.index + 1}. ${it.title}" },
            selected = scope,
            onSelect = {
                pickScope = false
                scope = it
                viewModel.clearSearch()
            },
            onDismiss = { pickScope = false },
        )
    }
    if (confirmReplace) {
        ConfirmDialog(
            title = stringResource(R.string.editor_replace_all),
            text = stringResource(R.string.editor_replace_confirm, query, replacement, scope?.let { titles[it] } ?: stringResource(R.string.editor_scope_book)),
            confirmLabel = stringResource(R.string.editor_replace_all),
            onConfirm = { viewModel.replaceAll(q, replacement, scopeList) },
            onDismiss = { confirmReplace = false },
        )
    }
}

// ---------------------------------------------------------------- images

@Composable
private fun ImagesTab(viewModel: EditorViewModel, s: EpubState) {
    var selected by remember { mutableStateOf<ManifestImage?>(null) }
    var pendingAdd by remember { mutableStateOf<Uri?>(null) }
    var replaceTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<ManifestImage?>(null) }
    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingAdd = uri }
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = replaceTarget
        if (uri != null && target != null) viewModel.replaceImage(target, uri)
        replaceTarget = null
    }
    Box(Modifier.fillMaxSize()) {
        if (s.images.isEmpty()) {
            EmptyState(title = stringResource(R.string.editor_no_images), modifier = Modifier.fillMaxSize(), icon = Icons.Default.Image)
        } else {
            LazyVerticalGrid(GridCells.Adaptive(112.dp), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp)) {
                items(s.images, key = { it.path }) { img ->
                    Card(Modifier.padding(4.dp).clickable { selected = img }) {
                        AsyncImage(
                            model = fileModel(File(s.root, img.path)),
                            contentDescription = img.path,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                        )
                        Text(img.path.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                        Text(
                            Files.humanSize(img.size) + if (img.isCover) " · " + stringResource(R.string.editor_cover) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (img.isCover) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                        )
                    }
                }
            }
        }
        YomuFab(
            text = stringResource(R.string.editor_add_image),
            icon = Icons.Default.Add,
            onClick = { addPicker.launch(arrayOf("image/*")) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    selected?.let { img ->
        YomuSheet(onDismiss = { selected = null }) {
            SheetTitle(img.path.substringAfterLast('/'))
            SheetAction(Icons.Default.Image, stringResource(R.string.editor_replace_image)) {
                selected = null
                replaceTarget = img.path
                replacePicker.launch(arrayOf("image/*"))
            }
            if (!img.isCover) {
                SheetAction(Icons.Default.CheckCircle, stringResource(R.string.editor_set_cover)) {
                    selected = null
                    viewModel.setCover(Uri.fromFile(File(s.root, img.path)))
                }
            }
            SheetAction(Icons.Default.Delete, stringResource(CoreR.string.delete), destructive = true) {
                selected = null
                confirmDelete = img
            }
        }
    }
    pendingAdd?.let { uri ->
        ChoiceDialog(
            title = stringResource(R.string.editor_insert_image_into),
            options = listOf<Pair<String?, String>>(null to stringResource(R.string.editor_dont_insert)) + s.chapters.map { it.path to "${it.index + 1}. ${it.title}" },
            selected = null,
            onSelect = {
                pendingAdd = null
                viewModel.addImage(uri, it)
            },
            onDismiss = { pendingAdd = null },
        )
    }
    confirmDelete?.let { img ->
        ConfirmDialog(
            title = stringResource(R.string.editor_delete_image_title),
            text = stringResource(R.string.editor_delete_image_text),
            confirmLabel = stringResource(CoreR.string.delete),
            onConfirm = { viewModel.deleteImage(img.path) },
            onDismiss = { confirmDelete = null },
        )
    }
}

// ---------------------------------------------------------------- styles

@Composable
private fun StylesTab(viewModel: EditorViewModel, s: EpubState, onEdit: (String) -> Unit) {
    var fonts by rememberSaveable { mutableStateOf(true) }
    var colors by rememberSaveable { mutableStateOf(true) }
    var inline by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PreferenceHeader(stringResource(R.string.editor_stylesheets))
        if (s.styles.isEmpty()) Text(stringResource(R.string.editor_no_styles), modifier = Modifier.padding(16.dp))
        s.styles.forEach { path ->
            ListItem(
                headlineContent = { Text(path.substringAfterLast('/')) },
                supportingContent = { Text(Files.humanSize(File(s.root, path).length())) },
                leadingContent = { Icon(Icons.Default.Code, null) },
                modifier = Modifier.clickable { onEdit(path) },
            )
        }
        PreferenceHeader(stringResource(R.string.editor_style_presets))
        PreferenceSwitch(stringResource(R.string.editor_style_fonts), fonts, { fonts = it }, summary = pluralStringResource(R.plurals.editor_font_files, s.fonts.size, s.fonts.size))
        PreferenceSwitch(stringResource(R.string.editor_style_colors), colors, { colors = it })
        PreferenceSwitch(stringResource(R.string.editor_style_inline), inline, { inline = it })
        Button(onClick = { confirm = true }, enabled = fonts || colors || inline, modifier = Modifier.padding(16.dp).align(Alignment.End)) {
            Icon(Icons.Default.AutoFixHigh, null)
            Text(stringResource(R.string.editor_apply))
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.editor_style_presets),
            text = stringResource(R.string.editor_style_confirm),
            confirmLabel = stringResource(R.string.editor_apply),
            onConfirm = { viewModel.cleanStyles(StyleCleanup(fonts, colors, inline)) },
            onDismiss = { confirm = false },
        )
    }
}

// ---------------------------------------------------------------- tools: clean-up and validation

@Composable
private fun ToolsTab(viewModel: EditorViewModel, s: EpubState, onTranslate: () -> Unit) {
    var empty by rememberSaveable { mutableStateOf(true) }
    var spaces by rememberSaveable { mutableStateOf(true) }
    var dashes by rememberSaveable { mutableStateOf(true) }
    var quotes by rememberSaveable { mutableStateOf(false) }
    var hyphen by rememberSaveable { mutableStateOf(false) }
    val issues by viewModel.issues.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            PreferenceHeader(stringResource(R.string.editor_cleanup))
            PreferenceSwitch(stringResource(R.string.editor_cleanup_empty), empty, { empty = it })
            PreferenceSwitch(stringResource(R.string.editor_cleanup_spaces), spaces, { spaces = it })
            PreferenceSwitch(stringResource(R.string.editor_cleanup_dashes), dashes, { dashes = it })
            PreferenceSwitch(stringResource(R.string.editor_cleanup_quotes), quotes, { quotes = it }, summary = stringResource(R.string.editor_cleanup_quotes_hint, s.meta.language ?: "—"))
            PreferenceSwitch(stringResource(R.string.editor_cleanup_hyphen), hyphen, { hyphen = it })
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = { viewModel.cleanup(CleanupOptions(empty, spaces, dashes, quotes, hyphen), null) }, enabled = empty || spaces || dashes || quotes || hyphen) {
                    Text(stringResource(R.string.editor_cleanup_apply))
                }
            }
            HorizontalDivider()
            PreferenceHeader(stringResource(R.string.editor_validation))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::validate) { Text(stringResource(R.string.editor_validate)) }
                val fixable = issues.orEmpty().filter { it.code.fixable }
                if (fixable.isNotEmpty()) {
                    Button(onClick = { viewModel.fixIssues(fixable) }) {
                        Icon(Icons.Default.AutoFixHigh, null)
                        Text(stringResource(R.string.editor_fix_all, fixable.size))
                    }
                }
            }
        }
        val list = issues
        if (list != null) {
            if (list.isEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.editor_no_issues)) },
                        leadingContent = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            } else {
                item {
                    val errors = list.count { it.code.error }
                    Text(stringResource(R.string.editor_issue_summary, errors, list.size - errors), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
                }
                items(list) { issue ->
                    ListItem(
                        headlineContent = { Text(issueText(issue), style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = if (issue.code.fixable) null else ({ Text(stringResource(R.string.editor_manual_fix), style = MaterialTheme.typography.bodySmall) }),
                        leadingContent = {
                            if (issue.code.error) Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error) else Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.tertiary)
                        },
                    )
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            PreferenceHeader(stringResource(R.string.editor_translate))
            ListItem(
                headlineContent = { Text(stringResource(R.string.editor_translate)) },
                supportingContent = { Text(stringResource(R.string.editor_translate_hint)) },
                leadingContent = { Icon(Icons.Default.Translate, null) },
                modifier = Modifier.clickable(onClick = onTranslate),
            )
        }
    }
}

@Composable
private fun issueText(i: Issue): String {
    val p = i.path?.substringAfterLast('/').orEmpty()
    val d = i.detail.orEmpty()
    return when (i.code) {
        IssueCode.NO_CONTAINER -> stringResource(R.string.issue_no_container)
        IssueCode.NO_TITLE -> stringResource(R.string.issue_no_title)
        IssueCode.NO_LANGUAGE -> stringResource(R.string.issue_no_language)
        IssueCode.NO_IDENTIFIER -> stringResource(R.string.issue_no_identifier)
        IssueCode.DUPLICATE_ID -> stringResource(R.string.issue_duplicate_id, d)
        IssueCode.MISSING_FILE -> stringResource(R.string.issue_missing_file, i.path.orEmpty())
        IssueCode.UNLISTED_FILE -> stringResource(R.string.issue_unlisted_file, i.path.orEmpty())
        IssueCode.JUNK_FILE -> stringResource(R.string.issue_junk_file, i.path.orEmpty())
        IssueCode.NO_NAV -> stringResource(R.string.issue_no_nav)
        IssueCode.TOC_BROKEN -> stringResource(R.string.issue_toc_broken, d)
        IssueCode.NOT_WELL_FORMED -> stringResource(R.string.issue_not_well_formed, p, d)
        IssueCode.BROKEN_LINK -> stringResource(R.string.issue_broken_link, p, d)
        IssueCode.BROKEN_ANCHOR -> stringResource(R.string.issue_broken_anchor, p, d)
        IssueCode.MISSING_RESOURCE -> stringResource(R.string.issue_missing_resource, p, d)
        IssueCode.EMPTY_CHAPTER -> stringResource(R.string.issue_empty_chapter, p)
        IssueCode.NO_COVER -> stringResource(R.string.issue_no_cover)
    }
}

/**
 * Translation: the whole saved book into a new copy (in the background), or the chosen chapters right here in the
 * draft as one undoable step. [preselected] (chapter paths) opens it in the second mode.
 */
@Composable
private fun TranslateDialog(viewModel: EditorViewModel, s: EpubState, preselected: Set<String>?, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf("") }
    var bilingual by remember { mutableStateOf(true) }
    var inDraft by remember { mutableStateOf(preselected != null) }
    var chosen by remember { mutableStateOf(preselected.orEmpty().filterTo(LinkedHashSet()) { p -> s.chapters.any { it.path == p } }) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (target.isEmpty()) target = viewModel.defaultTargetLanguage() }
    if (picking) {
        ChapterPickerDialog(
            title = stringResource(R.string.editor_translate_pick),
            chapters = s.chapters.map { it.index to it.title },
            selected = s.chapters.filter { it.path in chosen }.mapTo(HashSet()) { it.index },
            onConfirm = { set -> chosen = s.chapters.filter { it.index in set }.mapTo(LinkedHashSet()) { it.path }; inDraft = true },
            onDismiss = { picking = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Translate, null) },
        title = { Text(stringResource(R.string.editor_translate)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(target, { target = it }, label = { Text(stringResource(R.string.editor_translate_target)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.toggleable(value = bilingual, role = Role.Switch) { bilingual = it }, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.editor_translate_bilingual), modifier = Modifier.weight(1f))
                    Switch(bilingual, null)
                }
                TranslateModeRow(
                    selected = !inDraft,
                    title = stringResource(R.string.editor_translate_mode_copy),
                    summary = stringResource(R.string.editor_translate_text),
                ) { inDraft = false }
                TranslateModeRow(
                    selected = inDraft,
                    title = stringResource(R.string.editor_translate_mode_draft),
                    summary = if (chosen.isEmpty()) stringResource(R.string.editor_translate_mode_draft_sum)
                    else pluralStringResource(R.plurals.editor_translate_chosen, chosen.size, chosen.size),
                ) { picking = true }
            }
        },
        confirmButton = {
            TextButton(enabled = target.trim().length >= 2 && (!inDraft || chosen.isNotEmpty()), onClick = {
                if (inDraft) viewModel.translateChapters(s.chapters.filter { it.path in chosen }.map { it.path }, target, bilingual)
                else viewModel.translate(target, bilingual)
                onDismiss()
            }) { Text(stringResource(R.string.editor_translate_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

@Composable
private fun TranslateModeRow(selected: Boolean, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
