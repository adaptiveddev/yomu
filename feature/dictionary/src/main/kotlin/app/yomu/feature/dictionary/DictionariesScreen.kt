package app.yomu.feature.dictionary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.PreferenceSwitch
import app.yomu.core.ui.components.SkeletonList
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.components.YomuFab
import app.yomu.core.ui.theme.Dimens
import app.yomu.domain.model.DictFormat
import app.yomu.domain.model.DictionaryInfo
import app.yomu.core.ui.R as UiR

@Composable
fun DictionariesScreen(viewModel: DictionariesViewModel, onBack: () -> Unit) {
    val list by viewModel.dictionaries.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<DictionaryInfo?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.import(it) }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { r ->
            r.fold(
                { snackbar.showSnackbar(resources.getString(R.string.dicts_imported, it)) },
                { snackbar.showSnackbar(errorMessage(context, it)) },
            )
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dicts_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(UiR.string.back)) } },
            )
        },
        floatingActionButton = {
            YomuFab(
                text = stringResource(R.string.dicts_import),
                icon = Icons.Default.Add,
                onClick = { if (!importing) picker.launch(arrayOf("*/*")) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (importing) {
                Column(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s)) {
                    Text(progress?.let { stringResource(R.string.dicts_importing, it.name, it.entries) } ?: stringResource(R.string.dicts_preparing), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Dimens.xs))
                }
            }
            PreferenceSwitch(stringResource(R.string.dicts_online), online, viewModel::setOnline, summary = stringResource(R.string.dicts_online_summary))
            val l = list
            when {
                l == null -> SkeletonList()
                l.isEmpty() -> EmptyState(stringResource(R.string.dicts_empty_title), message = stringResource(R.string.dicts_formats), icon = Icons.AutoMirrored.Outlined.MenuBook)
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    itemsIndexed(l, key = { _, d -> d.id }) { i, d ->
                        ListItem(
                            modifier = Modifier.animateItem(),
                            headlineContent = { Text(d.name) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        d.format.label(),
                                        listOfNotNull(d.sourceLang, d.targetLang).joinToString("→").takeIf { it.isNotBlank() },
                                        stringResource(R.string.dicts_entries, d.entryCount),
                                    ).joinToString(" · "),
                                )
                            },
                            leadingContent = { Switch(checked = d.enabled, onCheckedChange = { viewModel.setEnabled(d.id, it) }, modifier = Modifier.semantics { contentDescription = d.name }) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(enabled = i > 0, onClick = { viewModel.move(d.id, -1) }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.dicts_move_up)) }
                                    IconButton(enabled = i < l.lastIndex, onClick = { viewModel.move(d.id, 1) }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.dicts_move_down)) }
                                    IconButton(onClick = { confirm = d }) { Icon(Icons.Default.Delete, stringResource(UiR.string.delete)) }
                                }
                            },
                        )
                    }
                    item {
                        Card(Modifier.fillMaxWidth().padding(Dimens.l), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Text(stringResource(R.string.dicts_formats), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(Dimens.m))
                        }
                    }
                }
            }
        }
    }
    confirm?.let { d ->
        ConfirmDialog(
            title = stringResource(R.string.dicts_delete_title, d.name),
            text = stringResource(R.string.dicts_delete_text),
            confirmLabel = stringResource(UiR.string.delete),
            onConfirm = { viewModel.delete(d.id); confirm = null },
            onDismiss = { confirm = null },
        )
    }
}

fun DictFormat.label(): String = when (this) {
    DictFormat.STARDICT -> "StarDict"
    DictFormat.DSL -> "DSL"
    DictFormat.WIKTIONARY -> "Wiktionary"
    DictFormat.JMDICT -> "JMdict"
    DictFormat.ONLINE -> "Online"
}
