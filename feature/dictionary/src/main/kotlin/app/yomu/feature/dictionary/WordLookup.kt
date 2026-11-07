package app.yomu.feature.dictionary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.AppError
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.UiState
import app.yomu.core.ui.components.SkeletonBox
import app.yomu.core.ui.components.rememberHaptic
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.dictionary.DictionaryRepository
import app.yomu.data.repository.WordRepositoryImpl
import app.yomu.data.translation.TranslationService
import app.yomu.domain.model.DictEntry
import app.yomu.domain.model.Locator
import app.yomu.domain.model.LookupResult
import app.yomu.domain.model.Word
import app.yomu.feature.translator.Speaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LookupRequest(
    val word: String,
    val sentence: String? = null,
    val lang: String? = null,
    val bookId: Long? = null,
    val bookTitle: String? = null,
    val locator: Locator? = null,
)

data class WordLookupUi(
    val request: LookupRequest? = null,
    val result: UiState<LookupResult> = UiState.Loading,
    val translation: String? = null,
    val savedId: Long? = null,
)

/** Quick lookup used by the reader: offline dictionaries first, then online, plus a short machine translation. */
class WordLookupViewModel(
    private val dict: DictionaryRepository,
    private val words: WordRepositoryImpl,
    private val translation: TranslationService,
    val speaker: Speaker,
) : ViewModel() {
    private val _state = MutableStateFlow(WordLookupUi())
    val state: StateFlow<WordLookupUi> = _state
    private var job: Job? = null

    fun lookup(request: LookupRequest) {
        job?.cancel()
        _state.value = WordLookupUi(request = request)
        job = viewModelScope.launch(ScreenErrors) {
            val tr = async { runCatching { translation.translate(request.word, request.lang, null).translated }.getOrNull() }
            val saved = words.find(request.word, request.lang)
            _state.update { it.copy(savedId = saved?.id) }
            val r = runCatching { dict.lookup(request.word, request.lang) }
            _state.update { s ->
                s.copy(result = r.fold({ if (it.entries.isEmpty()) UiState.Empty else UiState.Data(it) }, { UiState.Error(AppError.from(it)) }))
            }
            val t = tr.await()
            _state.update { it.copy(translation = t?.takeIf { x -> !x.equals(request.word, ignoreCase = true) }) }
        }
    }

    /** Saves the word with context, or removes it when already saved. Returns true when saved. */
    fun toggleSave() {
        val s = _state.value
        val req = s.request ?: return
        viewModelScope.launch(ScreenErrors) {
            if (s.savedId != null) {
                words.delete(listOf(s.savedId))
                _state.update { it.copy(savedId = null) }
            } else {
                val entry = (s.result as? UiState.Data)?.value?.entries?.firstOrNull()
                val id = words.save(
                    Word(
                        term = (s.result as? UiState.Data)?.value?.matched ?: req.word,
                        lang = req.lang,
                        translation = s.translation,
                        definition = entry?.definitions?.take(3)?.joinToString("; "),
                        context = req.sentence,
                        bookId = req.bookId,
                        bookTitle = req.bookTitle,
                        locator = req.locator,
                    ),
                )
                _state.update { it.copy(savedId = id) }
            }
        }
    }

    fun speak() {
        val req = _state.value.request ?: return
        speaker.speak(req.word, req.lang)
    }
}

private class NearAnchor(private val anchor: Rect?, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        if (anchor == null) return IntOffset((windowSize.width - popupContentSize.width) / 2, windowSize.height - popupContentSize.height - margin * 4)
        val x = (anchor.center.x - popupContentSize.width / 2f).toInt().coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchor.bottom.toInt() + margin
        val y = if (below + popupContentSize.height < windowSize.height - margin) below else (anchor.top.toInt() - popupContentSize.height - margin).coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}

/** Mini dictionary panel shown next to the tapped word. */
@Composable
fun WordPopup(
    viewModel: WordLookupViewModel,
    anchor: Rect?,
    onDismiss: () -> Unit,
    onTranslate: (String) -> Unit,
    onOpenDictionary: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val req = state.request ?: return
    val haptic = rememberHaptic()
    val provider = remember(anchor) { NearAnchor(anchor, 24) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.widthIn(min = 260.dp, max = 380.dp).heightIn(max = 340.dp),
        ) {
            Column(Modifier.padding(start = Dimens.l, end = Dimens.xs, top = Dimens.xs, bottom = Dimens.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val matched = (state.result as? UiState.Data)?.value?.matched
                    Column(Modifier.weight(1f)) {
                        Text(matched ?: req.word, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        val first = (state.result as? UiState.Data)?.value?.entries?.firstOrNull()
                        val sub = listOfNotNull(first?.reading, first?.ipa?.let { "/$it/" }, first?.partOfSpeech).joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = viewModel::speak) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.dict_speak)) }
                    IconButton(onClick = { haptic.perform(); viewModel.toggleSave() }) {
                        if (state.savedId != null) Icon(Icons.Default.Bookmark, stringResource(R.string.dict_unsave), tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.Outlined.BookmarkBorder, stringResource(R.string.dict_save))
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(app.yomu.core.ui.R.string.close)) }
                }
                state.translation?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = Dimens.m, bottom = Dimens.xs))
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(end = Dimens.m)) {
                    when (val r = state.result) {
                        UiState.Loading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SkeletonBox(Modifier.fillMaxWidth(0.9f).height(14.dp))
                            SkeletonBox(Modifier.fillMaxWidth(0.6f).height(14.dp))
                        }
                        UiState.Empty -> Text(stringResource(R.string.dict_not_found), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is UiState.Error -> Text(app.yomu.core.ui.components.errorMessage(r.error), color = MaterialTheme.colorScheme.error)
                        is UiState.Data -> r.value.entries.take(3).forEach { EntryBlock(it, compact = true) }
                    }
                }
                Row {
                    TextButton(onClick = { onTranslate(req.sentence?.takeIf { it.length < 600 } ?: req.word) }) {
                        Icon(Icons.Default.Translate, null)
                        Text(stringResource(if (req.sentence != null) R.string.dict_translate_sentence else R.string.dict_translate), Modifier.padding(start = 6.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onOpenDictionary(req.word) }) {
                        Text(stringResource(R.string.dict_more))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

/** One dictionary entry: part of speech, numbered definitions and examples. */
@Composable
fun EntryBlock(entry: DictEntry, compact: Boolean = false) {
    Column(Modifier.padding(vertical = Dimens.xs)) {
        if (!compact) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(entry.headword, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val sub = listOfNotNull(entry.reading, entry.ipa?.let { "/$it/" }).joinToString(" ")
                if (sub.isNotBlank()) Text("  $sub", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            entry.partOfSpeech?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontStyle = FontStyle.Italic) }
        } else if (entry.partOfSpeech != null && entry.dictionary.isNotBlank()) {
            Text(entry.dictionary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SelectionContainer {
            Column {
                val defs = if (compact) entry.definitions.take(4) else entry.definitions
                defs.forEachIndexed { i, d ->
                    Text(if (defs.size > 1) "${i + 1}. $d" else d, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                }
                val ex = if (compact) entry.examples.take(2) else entry.examples
                ex.forEach { e ->
                    Text("— $e", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, start = 8.dp))
                }
            }
        }
        if (!compact && entry.dictionary.isNotBlank()) {
            Text(entry.dictionary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
