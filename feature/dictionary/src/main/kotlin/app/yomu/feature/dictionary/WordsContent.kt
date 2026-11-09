package app.yomu.feature.dictionary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.UiState
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.components.ChipItem
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.FilterChipsRow
import app.yomu.core.ui.components.SearchField
import app.yomu.core.ui.components.SkeletonList
import app.yomu.core.ui.components.SwipeAction
import app.yomu.core.ui.components.SwipeActionsBox
import app.yomu.core.ui.components.TextInputDialog
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.theme.Dimens
import app.yomu.domain.export.WordFormat
import app.yomu.domain.model.Locator
import app.yomu.domain.model.Word
import kotlinx.coroutines.launch
import app.yomu.core.ui.R as UiR

fun LevelFilter.label(): Int = when (this) {
    LevelFilter.ALL -> R.string.words_level_all
    LevelFilter.NEW -> R.string.words_level_new
    LevelFilter.LEARNING -> R.string.words_level_learning
    LevelFilter.KNOWN -> R.string.words_level_known
}

fun WordSort.label(): Int = when (this) {
    WordSort.DATE -> R.string.words_sort_date
    WordSort.ALPHA -> R.string.words_sort_alpha
    WordSort.LEVEL -> R.string.words_sort_level
    WordSort.DUE -> R.string.words_sort_due
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WordsContent(viewModel: WordsViewModel, onOpenBook: (Long, Locator?, String) -> Unit, onStartReview: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val tag by viewModel.tag.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Word?>(null) }
    var tagDialog by rememberSaveable { mutableStateOf(false) }
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    val exporters = WordFormat.entries.associateWith { f ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { it?.let { u -> viewModel.export(f, u) } }
    }
    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::importCsv) }
    val deletedMsg = stringResource(R.string.words_deleted)
    val undo = stringResource(UiR.string.undo)
    val exported = stringResource(R.string.words_exported)
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is WordsEvent.Deleted -> scope.launch {
                    if (snackbar.showSnackbar(deletedMsg, undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) viewModel.restore(e.words)
                }
                is WordsEvent.Imported -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.words_imported, e.count)) }
                WordsEvent.Exported -> scope.launch { snackbar.showSnackbar(exported) }
                is WordsEvent.Failed -> scope.launch { snackbar.showSnackbar(errorMessage(context, e.error)) }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selection.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Default.Close, stringResource(UiR.string.cancel)) }
                    Text(stringResource(R.string.words_selected, selection.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = viewModel::selectAll) { Icon(Icons.Default.SelectAll, stringResource(UiR.string.select_all)) }
                    IconButton(onClick = { tagDialog = true }) { Icon(Icons.AutoMirrored.Filled.Label, stringResource(R.string.words_add_tag)) }
                    IconButton(onClick = { exportDialog = true }) { Icon(Icons.Default.FileDownload, stringResource(R.string.words_export)) }
                    IconButton(onClick = { viewModel.delete(selection) }) { Icon(Icons.Default.Delete, stringResource(UiR.string.delete)) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Dimens.xs)) {
                    SearchField(query, { viewModel.query.value = it }, stringResource(R.string.words_search), Modifier.weight(1f).padding(start = Dimens.l, top = Dimens.s, bottom = Dimens.s))
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.words_sort)) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            WordSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(s.label())) },
                                    leadingIcon = { if (s == sort) Icon(Icons.Default.CheckCircle, null) else Icon(Icons.Outlined.Circle, null) },
                                    onClick = { sortMenu = false; viewModel.sort.value = s },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(UiR.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.words_export)) }, leadingIcon = { Icon(Icons.Default.FileDownload, null) }, onClick = { menu = false; exportDialog = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.words_send_ankidroid)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }, onClick = { menu = false; viewModel.sendToAnkiDroid() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.words_import_csv)) }, leadingIcon = { Icon(Icons.Default.FileUpload, null) }, onClick = { menu = false; importCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) })
                        }
                    }
                }
                val chips = buildList {
                    LevelFilter.entries.forEach { l -> add(ChipItem(stringResource(l.label()), level == l, { viewModel.level.value = l })) }
                    tags.forEach { t -> add(ChipItem("#$t", tag == t, { viewModel.tag.value = if (tag == t) null else t })) }
                }
                FilterChipsRow(chips)
            }
            when (val s = items) {
                UiState.Loading -> SkeletonList()
                UiState.Empty -> EmptyState(
                    title = stringResource(if (query.isBlank() && tag == null && level == LevelFilter.ALL) R.string.words_empty_title else R.string.words_no_results),
                    message = if (query.isBlank()) stringResource(R.string.words_empty_text) else null,
                    icon = Icons.Outlined.Spellcheck,
                )
                is UiState.Error -> EmptyState(errorMessage(s.error))
                is UiState.Data -> LazyColumn(contentPadding = PaddingValues(bottom = navBarBottomPadding(88.dp))) {
                    items(s.value, key = { it.id }) { w ->
                        val selected = w.id in selection
                        SwipeActionsBox(
                            modifier = Modifier.animateItem(),
                            end = SwipeAction.destructive(stringResource(UiR.string.delete)) { viewModel.delete(setOf(w.id)) },
                            enabled = selection.isEmpty(),
                        ) {
                            ListItem(
                                colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
                                headlineContent = { Text(w.term, maxLines = 1) },
                                supportingContent = {
                                    Column {
                                        (w.translation ?: w.definition)?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                        w.context?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                },
                                trailingContent = { LevelIndicator(w.level) },
                                modifier = Modifier.combinedClickable(
                                    onClick = { if (selection.isNotEmpty()) viewModel.toggleSelect(w.id) else editing = w },
                                    onLongClick = { viewModel.toggleSelect(w.id) },
                                ),
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = navBarBottomPadding()))
    }
    editing?.let { w ->
        WordEditDialog(
            word = w,
            onSave = { viewModel.update(it); editing = null },
            onReset = { viewModel.resetProgress(w); editing = null },
            onOpenBook = if (w.bookId != null) ({ editing = null; onOpenBook(w.bookId!!, w.locator, "w:${w.id}:${w.createdAt}") }) else null,
            onDelete = { viewModel.delete(setOf(w.id)); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (exportDialog) {
        WordExportDialog(
            onPick = { f ->
                exportDialog = false
                val name = when (f) {
                    WordFormat.ANKI_TSV -> "yomu-words-anki"
                    WordFormat.QUIZLET -> "yomu-words-quizlet"
                    else -> "yomu-words"
                }
                exporters.getValue(f).launch("$name.${f.extension}")
            },
            onDismiss = { exportDialog = false },
        )
    }
    if (tagDialog) {
        TextInputDialog(
            title = stringResource(R.string.words_add_tag),
            onConfirm = { viewModel.addTag(selection, it); tagDialog = false },
            onDismiss = { tagDialog = false },
        )
    }
}

/** Five-step knowledge meter. The level is also written as text, colour is not the only signal. */
@Composable
fun LevelIndicator(level: Int) {
    val label = stringResource(R.string.words_level_value, level)
    Column(horizontalAlignment = Alignment.End) {
        LinearProgressIndicator(progress = { level / 5f }, modifier = Modifier.size(width = 48.dp, height = 6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WordEditDialog(word: Word, onSave: (Word) -> Unit, onReset: () -> Unit, onOpenBook: (() -> Unit)?, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var term by rememberSaveable { mutableStateOf(word.term) }
    var translation by rememberSaveable { mutableStateOf(word.translation.orEmpty()) }
    var definition by rememberSaveable { mutableStateOf(word.definition.orEmpty()) }
    var contextText by rememberSaveable { mutableStateOf(word.context.orEmpty()) }
    var tags by rememberSaveable { mutableStateOf(word.tags.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.words_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(term, { term = it }, label = { Text(stringResource(R.string.words_term)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(translation, { translation = it }, label = { Text(stringResource(R.string.words_translation)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(definition, { definition = it }, label = { Text(stringResource(R.string.words_definition)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(contextText, { contextText = it }, label = { Text(stringResource(R.string.words_context)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tags, { tags = it }, label = { Text(stringResource(R.string.words_tags)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                word.bookTitle?.let { Text(stringResource(R.string.words_from_book, it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Dimens.s)) }
                Row {
                    if (onOpenBook != null) TextButton(onClick = onOpenBook) { Text(stringResource(R.string.words_open_book)) }
                    TextButton(onClick = onReset) { Text(stringResource(R.string.words_reset)) }
                    TextButton(onClick = onDelete) { Text(stringResource(UiR.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = term.isNotBlank(), onClick = {
                onSave(
                    word.copy(
                        term = term.trim(),
                        translation = translation.trim().ifBlank { null },
                        definition = definition.trim().ifBlank { null },
                        context = contextText.trim().ifBlank { null },
                        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    ),
                )
            }) { Text(stringResource(UiR.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}

/** Formats of the word export, the Anki deck first. */
@Composable
private fun WordExportDialog(onPick: (WordFormat) -> Unit, onDismiss: () -> Unit) {
    val order = listOf(WordFormat.ANKI_APKG, WordFormat.ANKI_TSV, WordFormat.CSV, WordFormat.QUIZLET, WordFormat.JSON, WordFormat.MARKDOWN)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.words_export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                order.forEach { f ->
                    val (title, summary) = when (f) {
                        WordFormat.ANKI_APKG -> R.string.words_export_apkg to R.string.words_export_apkg_sum
                        WordFormat.ANKI_TSV -> R.string.words_export_anki to R.string.words_export_anki_sum
                        WordFormat.CSV -> R.string.words_export_csv to R.string.words_export_csv_sum
                        WordFormat.QUIZLET -> R.string.words_export_quizlet to R.string.words_export_quizlet_sum
                        WordFormat.JSON -> R.string.words_export_json to R.string.words_export_json_sum
                        WordFormat.MARKDOWN -> R.string.words_export_md to R.string.words_export_md_sum
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(title)) },
                        supportingContent = { Text(stringResource(summary)) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { onPick(f) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}
