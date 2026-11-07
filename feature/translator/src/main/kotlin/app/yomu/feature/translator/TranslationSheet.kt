package app.yomu.feature.translator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.Share
import app.yomu.core.common.UiState
import app.yomu.core.ui.components.ChipItem
import app.yomu.core.ui.components.FilterChipsRow
import app.yomu.core.ui.components.SkeletonBox
import app.yomu.core.ui.components.YomuSheet
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.theme.Dimens
import app.yomu.domain.translation.CommonLanguages
import app.yomu.core.ui.R as UiR

/**
 * Bottom sheet with the translation of [text]: languages, engine switch, copy, speak and save word.
 * [onSaveWord] receives (term, translation, sourceLanguage).
 */
@Composable
fun TranslationSheet(
    viewModel: TranslationViewModel,
    text: String,
    sourceLang: String? = null,
    onDismiss: () -> Unit,
    onSaveWord: ((String, String, String?) -> Unit)? = null,
    onOpenPacks: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(text) { if (text != state.source) viewModel.start(text, sourceLang) }
    YomuSheet(onDismiss = { viewModel.speaker.stop(); onDismiss() }) {
        Column(Modifier.padding(horizontal = Dimens.l).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val detected = (state.result as? UiState.Data)?.value?.from
                LanguageButton(
                    label = state.from?.let { Languages.name(it) } ?: (stringResource(R.string.tr_auto) + (detected?.let { " (" + Languages.name(it) + ")" } ?: "")),
                    options = listOf<String?>(null) + CommonLanguages,
                    onSelect = viewModel::setFrom,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::swap) { Icon(Icons.Default.SwapHoriz, stringResource(R.string.tr_swap)) }
                LanguageButton(
                    label = Languages.name(state.to),
                    options = CommonLanguages,
                    onSelect = { it?.let(viewModel::setTo) },
                    modifier = Modifier.weight(1f),
                )
            }
            SelectionContainer {
                Text(state.source, style = MaterialTheme.typography.bodyLarge, maxLines = 8, modifier = Modifier.padding(vertical = Dimens.s))
            }
            Row {
                IconButton(onClick = { viewModel.speaker.speak(state.source, state.from ?: (state.result as? UiState.Data)?.value?.from) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.tr_speak_source))
                }
            }
            HorizontalDivider()
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(vertical = Dimens.m)) {
                when (val r = state.result) {
                    UiState.Loading, UiState.Empty -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBox(Modifier.fillMaxWidth(0.9f).height(16.dp))
                        SkeletonBox(Modifier.fillMaxWidth(0.7f).height(16.dp))
                        SkeletonBox(Modifier.fillMaxWidth(0.5f).height(16.dp))
                    }
                    is UiState.Error -> Column {
                        Text(
                            if (state.missingModel) stringResource(R.string.tr_model_missing) else errorMessage(r.error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(Dimens.s))
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                            if (state.missingModel) {
                                FilledTonalButton(onClick = viewModel::downloadModels, enabled = !state.downloading) {
                                    Icon(Icons.Default.Download, null)
                                    Text(stringResource(R.string.tr_download_models), Modifier.padding(start = 6.dp))
                                }
                                if (onOpenPacks != null) TextButton(onClick = onOpenPacks) { Text(stringResource(R.string.packs_title)) }
                            }
                            OutlinedButton(onClick = viewModel::retry) { Text(stringResource(UiR.string.retry)) }
                        }
                        if (state.downloading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Dimens.s))
                    }
                    is UiState.Data -> Column {
                        SelectionContainer {
                            Text(r.value.translated, style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.primary))
                        }
                        val engineName = state.engines.firstOrNull { it.id == r.value.engineId }?.name ?: r.value.engineId
                        Text(
                            engineName + if (r.value.fromCache) " · " + stringResource(R.string.tr_cached) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Dimens.xs),
                        )
                    }
                }
            }
            val translated = (state.result as? UiState.Data)?.value
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                IconButton(enabled = translated != null, onClick = { translated?.let { viewModel.speaker.speak(it.translated, it.to) } }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.tr_speak_translation))
                }
                IconButton(enabled = translated != null, onClick = { translated?.let { copy(context, it.translated) } }) {
                    Icon(Icons.Default.ContentCopy, stringResource(UiR.string.copy))
                }
                IconButton(enabled = translated != null, onClick = { translated?.let { Share.shareText(context, it.source + "\n\n" + it.translated) } }) {
                    Icon(Icons.Default.Share, stringResource(UiR.string.share))
                }
                if (onSaveWord != null) {
                    IconButton(enabled = translated != null, onClick = { translated?.let { onSaveWord(it.source, it.translated, it.from) } }) {
                        Icon(Icons.Default.BookmarkAdd, stringResource(R.string.tr_save_word))
                    }
                }
            }
            val chips = buildList {
                add(ChipItem(stringResource(R.string.tr_engine_auto), state.engineId == null, { viewModel.setEngine(null) }))
                state.engines.forEach { e ->
                    add(ChipItem(e.name + if (!e.configured) " ⚠" else "", state.engineId == e.id, { viewModel.setEngine(e.id) }))
                }
            }
            FilterChipsRow(chips, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp))
            Spacer(Modifier.height(Dimens.xl))
        }
    }
}

@Composable
private fun LanguageButton(label: String, options: List<String?>, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        AssistChip(onClick = { open = true }, label = { Text(label, maxLines = 1) }, modifier = Modifier.fillMaxWidth())
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            val auto = stringResource(R.string.tr_auto)
            options.forEach { code ->
                DropdownMenuItem(text = { Text(code?.let { Languages.name(it) } ?: auto) }, onClick = { open = false; onSelect(code) })
            }
        }
    }
}

private fun copy(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(android.content.ClipData.newPlainText("yomu", text))
}
