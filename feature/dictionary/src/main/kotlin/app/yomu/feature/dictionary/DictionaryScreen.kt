package app.yomu.feature.dictionary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.UiState
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.SearchField
import app.yomu.core.ui.components.TopLevelBody
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.components.topLevelContentInsets
import app.yomu.core.ui.components.ErrorState
import app.yomu.core.ui.components.SkeletonList
import app.yomu.core.ui.components.YomuTab
import app.yomu.core.ui.theme.Dimens
import app.yomu.feature.translator.Speaker
import app.yomu.feature.translator.TranslationSheet
import app.yomu.feature.translator.TranslationViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Top-level "Dictionary" destination: lookup with history, and the saved words list. */
@Composable
fun DictionaryScreen(
    viewModel: DictionaryViewModel,
    wordsViewModel: WordsViewModel,
    onOpenDictionaries: () -> Unit,
    onStartReview: () -> Unit,
    onOpenBook: (Long, app.yomu.domain.model.Locator?, String) -> Unit,
    initialQuery: String? = null,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(initialQuery) { if (!initialQuery.isNullOrBlank()) { tab = 0; viewModel.search(initialQuery) } }
    Scaffold(
        contentWindowInsets = topLevelContentInsets(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dict_title)) },
                actions = {
                    IconButton(onClick = onStartReview) { Icon(Icons.Default.School, stringResource(R.string.review_title)) }
                    IconButton(onClick = onOpenDictionaries) { Icon(Icons.AutoMirrored.Filled.LibraryBooks, stringResource(R.string.dicts_title)) }
                },
            )
        },
    ) { padding ->
        TopLevelBody(padding, Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                YomuTab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.dict_tab_lookup)) })
                YomuTab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.dict_tab_words)) })
            }
            if (tab == 0) LookupTab(viewModel, onStartReview, onOpenDictionaries) else WordsContent(wordsViewModel, onOpenBook, onStartReview)
        } }
    }
}

@Composable
private fun LookupTab(viewModel: DictionaryViewModel, onStartReview: () -> Unit, onOpenDictionaries: () -> Unit) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val due by viewModel.dueCount.collectAsStateWithLifecycle()
    val dicts by viewModel.dictionaries.collectAsStateWithLifecycle()
    val saved by viewModel.savedId.collectAsStateWithLifecycle()
    val speaker: Speaker = koinInject()
    var translate by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        // The same filled pill as the other search fields (settings, catalog).
        SearchField(
            query = query,
            onQueryChange = viewModel::setQuery,
            placeholder = stringResource(R.string.dict_search_hint),
            onSearch = { viewModel.search() },
            modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
        )
        val r = result
        when {
            r == null && query.isNotBlank() && suggestions.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(bottom = navBarBottomPadding())) {
                items(suggestions) { s ->
                    ListItem(headlineContent = { Text(s) }, leadingContent = { Icon(Icons.Default.Search, null) }, modifier = Modifier.clickable { viewModel.search(s) })
                }
            }
            r == null -> LazyColumn(contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(Modifier.padding(Dimens.l), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.School, null)
                            Column(Modifier.weight(1f).padding(horizontal = Dimens.m)) {
                                Text(stringResource(R.string.review_title), style = MaterialTheme.typography.titleMedium)
                                Text(pluralStringResource(R.plurals.review_due, due, due), style = MaterialTheme.typography.bodyMedium)
                            }
                            FilledTonalButton(onClick = onStartReview) { Text(stringResource(R.string.review_start)) }
                        }
                    }
                }
                if (dicts?.isEmpty() == true) {
                    item {
                        Card(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
                            Column(Modifier.padding(Dimens.l)) {
                                Text(stringResource(R.string.dict_no_dicts_title), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.dict_no_dicts_text), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = onOpenDictionaries) { Text(stringResource(R.string.dicts_import)) }
                            }
                        }
                    }
                }
                if (history.isNotEmpty()) {
                    item {
                        Row(Modifier.padding(start = Dimens.l, top = Dimens.m), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.dict_history), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::clearHistory) { Text(stringResource(app.yomu.core.ui.R.string.clear)) }
                        }
                    }
                    items(history.take(50)) { h ->
                        ListItem(headlineContent = { Text(h) }, leadingContent = { Icon(Icons.Default.History, null) }, modifier = Modifier.clickable { viewModel.search(h) })
                    }
                }
            }
            r is UiState.Loading -> SkeletonList()
            r is UiState.Empty -> EmptyState(
                title = stringResource(R.string.dict_not_found),
                message = stringResource(R.string.dict_not_found_hint),
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                actionLabel = stringResource(R.string.dict_translate),
                onAction = { translate = query },
            )
            r is UiState.Error -> ErrorState(r.error, onRetry = { viewModel.search() })
            r is UiState.Data -> LazyColumn(contentPadding = PaddingValues(start = Dimens.l, end = Dimens.l, top = Dimens.s, bottom = navBarBottomPadding(Dimens.s))) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.value.matched ?: r.value.query, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { speaker.speak(r.value.matched ?: r.value.query, null) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.dict_speak)) }
                        IconButton(onClick = { translate = r.value.matched ?: r.value.query }) { Icon(Icons.Default.Translate, stringResource(R.string.dict_translate)) }
                        IconButton(onClick = viewModel::toggleSave) {
                            if (saved != null) Icon(Icons.Default.Bookmark, stringResource(R.string.dict_unsave), tint = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Outlined.BookmarkBorder, stringResource(R.string.dict_save))
                        }
                    }
                    if (r.value.online) Text(stringResource(R.string.dict_online_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Dimens.s))
                }
                items(r.value.entries) { e ->
                    Card(Modifier.fillMaxWidth().padding(vertical = Dimens.xs), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(2.dp)) { EntryBlock(e) }
                    }
                }
            }
        }
    }
    translate?.let { t ->
        val trVm: TranslationViewModel = koinViewModel()
        TranslationSheet(trVm, t, onDismiss = { translate = null })
    }
}
