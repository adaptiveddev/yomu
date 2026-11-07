package app.yomu.feature.editor

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.ErrorState
import app.yomu.core.ui.components.errorMessage
import kotlinx.coroutines.launch
import app.yomu.core.ui.R as CoreR

/** Entry point of the editor (route argument: book id). */
@Composable
fun EditorScreen(viewModel: EditorViewModel, onBack: () -> Unit) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    var confirmExit by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is EditorEvent.Message -> launch { snackbar.showSnackbar(resources.getString(e.res, *e.args.toTypedArray())) }
                is EditorEvent.Error -> launch { snackbar.showSnackbar(errorMessage(context, e.error)) }
                EditorEvent.Close -> onBack()
            }
        }
    }
    val requestBack: () -> Unit = { if (status.dirty) confirmExit = true else viewModel.close(keepDraft = false) }
    BackHandler(enabled = phase is EditorPhase.Ready) { requestBack() }

    when (val p = phase) {
        EditorPhase.Loading -> PlainEditorScaffold(onBack) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.editor_preparing), Modifier.padding(top = 16.dp))
                }
            }
        }
        is EditorPhase.DraftFound -> {
            PlainEditorScaffold(onBack) {}
            AlertDialog(
                onDismissRequest = onBack,
                title = { Text(stringResource(R.string.editor_draft_title)) },
                text = {
                    Text(stringResource(R.string.editor_draft_text, DateUtils.getRelativeTimeSpanString(p.modifiedAt).toString()))
                },
                confirmButton = { TextButton(onClick = viewModel::resumeDraft) { Text(stringResource(R.string.editor_draft_resume)) } },
                dismissButton = { TextButton(onClick = viewModel::startOver) { Text(stringResource(R.string.editor_draft_discard)) } },
            )
        }
        is EditorPhase.Failed -> PlainEditorScaffold(onBack) { ErrorState(p.error, Modifier.fillMaxSize()) }
        EditorPhase.Unsupported -> PlainEditorScaffold(onBack) {
            EmptyState(
                title = stringResource(R.string.editor_unsupported_title),
                modifier = Modifier.fillMaxSize(),
                message = stringResource(R.string.editor_unsupported_text),
            )
        }
        is EditorPhase.Ready -> when (p.kind) {
            EditorKind.TEXT -> EpubEditor(viewModel, status, snackbar, requestBack)
            EditorKind.MANGA -> MangaEditor(viewModel, status, snackbar, requestBack)
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.editor_exit_title)) },
            text = { Text(stringResource(R.string.editor_exit_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    viewModel.saveToBook(thenClose = true)
                }) { Text(stringResource(R.string.editor_save_to_book)) }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        confirmExit = false
                        viewModel.close(keepDraft = true)
                    }) { Text(stringResource(R.string.editor_keep_draft)) }
                    TextButton(onClick = {
                        confirmExit = false
                        viewModel.close(keepDraft = false)
                    }) { Text(stringResource(R.string.editor_discard_changes), color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlainEditorScaffold(onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.editor_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) } },
        )
    }) { padding -> Box(Modifier.padding(padding).fillMaxSize()) { content() } }
}

/** Top bar shared by both editors: back, title, undo/redo, save and an overflow menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorTopBar(
    title: String,
    subtitle: String?,
    status: EditorStatus,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) } },
            actions = {
                IconButton(onClick = onUndo, enabled = status.canUndo && !status.busy) {
                    Icon(Icons.AutoMirrored.Filled.Undo, status.undoLabel?.let { stringResource(R.string.editor_undo_label, it) } ?: stringResource(CoreR.string.undo))
                }
                IconButton(onClick = onRedo, enabled = status.canRedo && !status.busy) {
                    Icon(Icons.AutoMirrored.Filled.Redo, status.redoLabel?.let { stringResource(R.string.editor_redo_label, it) } ?: stringResource(R.string.editor_redo))
                }
                IconButton(onClick = onSave, enabled = status.dirty && !status.busy) { Icon(Icons.Default.Save, stringResource(R.string.editor_save_to_book)) }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(CoreR.string.more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
                }
            },
        )
        if (status.busy) {
            val p = status.progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            HorizontalDivider()
        }
    }
}

/** Export launchers must live outside of menus (they would be unregistered when the menu closes). */
@Composable
internal fun rememberExportLauncher(viewModel: EditorViewModel, exports: List<ExportType>): (ExportType) -> Unit {
    val launchers = exports.associateWith { type ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(type.mime)) { uri -> uri?.let { viewModel.export(type, it) } }
    }
    return { type -> launchers[type]?.launch(viewModel.suggestedName(type)) }
}

/** Save/export entries common to both editors. */
@Composable
internal fun ColumnScope.SaveMenuItems(
    viewModel: EditorViewModel,
    exports: List<ExportType>,
    onExport: (ExportType) -> Unit,
    onDiscard: () -> Unit,
    dismiss: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    DropdownMenuItem(text = { Text(stringResource(R.string.editor_save_to_book)) }, onClick = { dismiss(); viewModel.saveToBook() })
    DropdownMenuItem(text = { Text(stringResource(R.string.editor_save_copy)) }, onClick = { dismiss(); viewModel.saveAsNewBook() })
    HorizontalDivider()
    exports.forEach { type ->
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_export_as, type.name)) }, onClick = { dismiss(); onExport(type) })
    }
    extra()
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.editor_discard_changes), color = MaterialTheme.colorScheme.error) },
        onClick = { dismiss(); onDiscard() },
    )
}

@Composable
internal fun DiscardDialog(viewModel: EditorViewModel, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.editor_discard_changes),
        text = stringResource(R.string.editor_discard_text),
        confirmLabel = stringResource(R.string.editor_discard_changes),
        onConfirm = {
            onDismiss()
            viewModel.startOver()
        },
        onDismiss = onDismiss,
    )
}
