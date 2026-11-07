package app.yomu.feature.translator

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.ScreenErrors
import app.yomu.core.datastore.AppSettings
import app.yomu.core.datastore.stateIn
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.PreferenceHeader
import app.yomu.core.ui.components.PreferenceSwitch
import app.yomu.core.ui.components.SkeletonList
import app.yomu.core.ui.components.errorMessage
import app.yomu.data.translation.MlKitEngine
import app.yomu.data.translation.TranslationService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.yomu.core.ui.R as UiR

data class LanguagePack(val code: String, val name: String, val downloaded: Boolean, val busy: Boolean = false)

/** Manages ML Kit offline translation models. */
class LanguagePacksViewModel(private val service: TranslationService, private val settings: AppSettings) : ViewModel() {
    private val _packs = MutableStateFlow<List<LanguagePack>?>(null)
    val packs: StateFlow<List<LanguagePack>?> = _packs
    val wifiOnly = settings.modelsWifiOnly.stateIn(viewModelScope)
    private val errors = Channel<Throwable>(Channel.BUFFERED)
    val errorFlow = errors.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch(ScreenErrors) {
        val downloaded = service.mlKit.downloadedModels()
        val all = service.mlKit.supportedLanguages()
        val busy = _packs.value.orEmpty().filter { it.busy }.map { it.code }.toSet()
        _packs.value = all.map { LanguagePack(it, Languages.name(it), it in downloaded || it == "en", it in busy) }
            .sortedWith(compareByDescending<LanguagePack> { it.downloaded }.thenBy { it.name })
    }

    private fun setBusy(code: String, busy: Boolean) = _packs.update { l -> l?.map { if (it.code == code) it.copy(busy = busy) else it } }

    fun download(code: String) = viewModelScope.launch(ScreenErrors) {
        setBusy(code, true)
        runCatching { service.mlKit.download(code, settings.modelsWifiOnly.get()) }.onFailure { errors.send(it) }
        setBusy(code, false)
        refresh()
    }

    fun delete(code: String) = viewModelScope.launch(ScreenErrors) {
        setBusy(code, true)
        runCatching { service.mlKit.delete(code) }.onFailure { errors.send(it) }
        setBusy(code, false)
        refresh()
    }

    fun setWifiOnly(v: Boolean) = viewModelScope.launch(ScreenErrors) { settings.modelsWifiOnly.set(v) }
}

@Composable
fun LanguagePacksScreen(viewModel: LanguagePacksViewModel, onBack: () -> Unit) {
    val packs by viewModel.packs.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<LanguagePack?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.errorFlow.collect { snackbar.showSnackbar(errorMessage(context, it)) } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.packs_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(UiR.string.back)) } },
            )
        },
    ) { padding ->
        val list = packs
        if (list == null) {
            SkeletonList(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column {
                    Text(
                        stringResource(R.string.packs_summary, MlKitEngine.MODEL_SIZE_MB),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    PreferenceSwitch(stringResource(R.string.packs_wifi_only), wifiOnly, viewModel::setWifiOnly)
                }
            }
            val (down, avail) = list.partition { it.downloaded }
            item { PreferenceHeader(stringResource(R.string.packs_downloaded, down.size)) }
            items(down, key = { "d" + it.code }) { p -> PackRow(p, onDownload = {}, onDelete = { confirmDelete = p }) }
            item { PreferenceHeader(stringResource(R.string.packs_available)) }
            items(avail, key = { "a" + it.code }) { p -> PackRow(p, onDownload = { viewModel.download(p.code) }, onDelete = {}) }
        }
    }
    confirmDelete?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.packs_delete_title, p.name),
            text = stringResource(R.string.packs_delete_text),
            confirmLabel = stringResource(UiR.string.delete),
            onConfirm = { viewModel.delete(p.code); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun PackRow(p: LanguagePack, onDownload: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        leadingContent = {
            Icon(if (p.downloaded) Icons.Default.CheckCircle else Icons.Outlined.Translate, null, tint = if (p.downloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = { Text(p.name) },
        supportingContent = {
            Text(
                if (p.downloaded) stringResource(if (p.code == "en") R.string.packs_builtin else R.string.packs_ready) else stringResource(R.string.packs_size, MlKitEngine.MODEL_SIZE_MB),
            )
        },
        trailingContent = {
            when {
                p.busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                p.code == "en" -> {}
                p.downloaded -> IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(UiR.string.delete)) }
                else -> IconButton(onClick = onDownload) { Icon(Icons.Default.Download, stringResource(R.string.packs_download)) }
            }
        },
    )
}
