package app.yomu.feature.annotations

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.Share
import app.yomu.core.common.UiState
import app.yomu.core.ui.components.ChipItem
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.OneTimeHint
import app.yomu.core.ui.components.FilterChipsRow
import app.yomu.core.ui.components.SheetAction
import app.yomu.core.ui.components.SwipeAction
import app.yomu.core.ui.components.SwipeActionsBox
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.SkeletonList
import app.yomu.core.ui.components.TextInputDialog
import app.yomu.core.ui.components.YomuSheet
import app.yomu.core.ui.components.YomuTab
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.theme.Dimens
import app.yomu.domain.export.AnnotationFormat
import app.yomu.domain.model.HighlightColor
import app.yomu.domain.model.HighlightStyle
import app.yomu.domain.model.Locator
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import app.yomu.core.ui.R as UiR

fun AnnotationFormat.label(): Int = when (this) {
    AnnotationFormat.MARKDOWN -> R.string.export_markdown
    AnnotationFormat.JSON -> R.string.export_json
    AnnotationFormat.HTML -> R.string.export_html
    AnnotationFormat.TXT -> R.string.export_txt
    AnnotationFormat.READWISE_CSV -> R.string.export_readwise
    AnnotationFormat.ANKI_TSV -> R.string.export_anki_tsv
    AnnotationFormat.ANKI_APKG -> R.string.export_anki_apkg
}

fun HighlightStyle.label(): Int = when (this) {
    HighlightStyle.HIGHLIGHT -> R.string.style_highlight
    HighlightStyle.UNDERLINE -> R.string.style_underline
    HighlightStyle.STRIKETHROUGH -> R.string.style_strike
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("yomu", text))
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, context.getString(UiR.string.copied), Toast.LENGTH_SHORT).show()
}

@Composable
fun AnnotationsScreen(
    viewModel: AnnotationsViewModel,
    onBack: () -> Unit,
    onOpen: (bookId: Long, locator: Locator?, reference: String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val bookTitle by viewModel.title.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<AnnotationEntry?>(null) }
    var noteFor by remember { mutableStateOf<AnnotationEntry?>(null) }
    var renameFor by remember { mutableStateOf<AnnotationEntry.B?>(null) }
    var colorFor by remember { mutableStateOf<AnnotationEntry.H?>(null) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val exporters = AnnotationFormat.entries.associateWith { f ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { uri -> if (uri != null) viewModel.export(f, uri) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.importReadwise(uri) }

    val deletedMsg = stringResource(R.string.annotations_deleted)
    val undoLabel = stringResource(UiR.string.undo)
    val exportedMsg = stringResource(R.string.annotations_exported)
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is AnnotationsEvent.Deleted -> scope.launch {
                    if (snackbar.showSnackbar(deletedMsg, undoLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) viewModel.undoDelete(e.entry)
                }
                is AnnotationsEvent.Imported -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.annotations_imported, e.added, e.skipped)) }
                AnnotationsEvent.Exported -> scope.launch { snackbar.showSnackbar(exportedMsg) }
                is AnnotationsEvent.Failed -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.annotations_export_failed, errorMessage(context, e.error))) }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(UiR.string.back)) } },
                title = {
                    if (searching) {
                        TextField(
                            value = filter.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text(stringResource(R.string.annotations_search)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Column {
                            Text(stringResource(if (viewModel.allBooks) R.string.annotations_all_title else R.string.annotations_title), maxLines = 1)
                            bookTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { if (searching) viewModel.setQuery(""); searching = !searching }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, stringResource(UiR.string.search))
                    }
                    IconButton(onClick = viewModel::toggleSort) {
                        Icon(Icons.AutoMirrored.Filled.Sort, stringResource(if (filter.sort == AnnotationSort.POSITION) R.string.annotations_sort_date else R.string.annotations_sort_position))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(UiR.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.annotations_export)) },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                onClick = { menu = false; exportMenu = true },
                            )
                            if (viewModel.allBooks) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.annotations_import_readwise)) },
                                    leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                                    onClick = { menu = false; importer.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                                )
                            }
                        }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            AnnotationFormat.entries.forEach { f ->
                                DropdownMenuItem(text = { Text(stringResource(f.label())) }, onClick = {
                                    exportMenu = false
                                    val base = (bookTitle ?: "yomu-annotations").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
                                    exporters.getValue(f).launch("$base.${f.extension}")
                                })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = filter.tab.ordinal, edgePadding = Dimens.s) {
                AnnotationTab.entries.forEach { tab ->
                    YomuTab(
                        selected = filter.tab == tab,
                        onClick = { viewModel.setTab(tab) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(tab.label()))
                                val n = counts[tab] ?: 0
                                if (n > 0) {
                                    Badge(
                                        Modifier.padding(start = 6.dp),
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ) { Text(n.toString()) }
                                }
                            }
                        },
                    )
                }
            }
            if (filter.tab == AnnotationTab.HIGHLIGHTS || filter.tab == AnnotationTab.QUOTES) {
                val chips = buildList {
                    add(ChipItem(stringResource(R.string.annotations_all_colors), filter.color == null, { viewModel.setColor(null) }))
                    HighlightColor.entries.forEach { c ->
                        add(ChipItem(stringResource(c.label()), filter.color == c, { viewModel.setColor(if (filter.color == c) null else c) }, Icons.Default.Palette))
                    }
                    add(ChipItem(stringResource(R.string.annotations_favorites_only), filter.favoritesOnly, viewModel::toggleFavorites, Icons.Default.Star))
                }
                FilterChipsRow(chips, Modifier.padding(vertical = Dimens.xs))
            }
            when (val s = items) {
                UiState.Loading -> SkeletonList()
                UiState.Empty -> {
                    val filtered = filter.query.isNotBlank() || filter.color != null || filter.favoritesOnly
                    EmptyState(
                        title = stringResource(if (filtered) R.string.annotations_no_results else R.string.annotations_empty_title),
                        message = if (filtered) null else stringResource(if (filter.tab == AnnotationTab.BOOKMARKS) R.string.annotations_bookmarks_empty else R.string.annotations_empty_message),
                        icon = if (filter.tab == AnnotationTab.BOOKMARKS) Icons.Outlined.BookmarkBorder else Icons.Default.FormatQuote,
                    )
                }
                is UiState.Error -> EmptyState(errorMessage(s.error))
                is UiState.Data -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = Dimens.l, vertical = Dimens.s),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "swipe-hint") { OneTimeHint("annotations_swipe", stringResource(R.string.hint_annotations_swipe), Modifier.fillMaxWidth()) }
                    var lastBook: Long? = null
                    s.value.forEach { e ->
                        if (viewModel.allBooks && filter.sort == AnnotationSort.POSITION && e.bookId != lastBook) {
                            lastBook = e.bookId
                            item(key = "header-${e.bookId}-${e.key}") {
                                Text(
                                    e.bookTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = Dimens.m, bottom = Dimens.xs),
                                )
                            }
                        }
                        item(key = e.key) {
                            SwipeActionsBox(
                                modifier = Modifier.animateItem(),
                                end = SwipeAction.destructive(stringResource(UiR.string.delete)) { viewModel.delete(e) },
                            ) {
                                AnnotationCard(
                                    entry = e,
                                    showBook = viewModel.allBooks && filter.sort == AnnotationSort.DATE,
                                    onClick = { onOpen(e.bookId, e.locator, "${e.key.first()}:${e.key.drop(1)}:${e.createdAt}") },
                                    onLongClick = { sheetFor = e },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    sheetFor?.let { e ->
        YomuSheet(onDismiss = { sheetFor = null }) {
            SheetTitle(e.text.take(80))
            SheetAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.annotations_open)) { sheetFor = null; onOpen(e.bookId, e.locator, "${e.key.first()}:${e.key.drop(1)}:${e.createdAt}") }
            when (e) {
                is AnnotationEntry.H -> {
                    SheetAction(Icons.Default.Edit, stringResource(if (e.highlight.note.isNullOrBlank()) R.string.annotations_add_note else R.string.annotations_edit_note)) { sheetFor = null; noteFor = e }
                    SheetAction(Icons.Default.Palette, stringResource(R.string.action_highlight)) { sheetFor = null; colorFor = e }
                    SheetAction(if (e.highlight.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder, stringResource(if (e.highlight.isFavorite) R.string.annotations_unfavorite else R.string.annotations_favorite)) { sheetFor = null; viewModel.toggleFavorite(e) }
                    SheetAction(Icons.Default.FormatQuote, stringResource(if (e.highlight.isQuote) R.string.annotations_unmark_quote else R.string.annotations_mark_quote)) { sheetFor = null; viewModel.toggleQuote(e) }
                    SheetAction(Icons.Default.Image, stringResource(R.string.action_share_image)) {
                        sheetFor = null
                        scope.launch(ScreenErrors) { QuoteImage.share(context, e.text, e.bookTitle, viewModel.authorsOf(e.bookId), dark, resources.getString(R.string.quote_share_title)) }
                    }
                }
                is AnnotationEntry.N -> SheetAction(Icons.Default.Edit, stringResource(R.string.annotations_edit_note)) { sheetFor = null; noteFor = e }
                is AnnotationEntry.B -> SheetAction(Icons.Default.Edit, stringResource(R.string.annotations_rename_bookmark)) { sheetFor = null; renameFor = e }
            }
            SheetAction(Icons.Default.ContentCopy, stringResource(UiR.string.copy)) { sheetFor = null; copyToClipboard(context, e.shareText()) }
            SheetAction(Icons.Default.Share, stringResource(UiR.string.share)) { sheetFor = null; Share.shareText(context, e.shareText()) }
            SheetAction(Icons.Default.Delete, stringResource(UiR.string.delete), destructive = true) { sheetFor = null; viewModel.delete(e) }
            Spacer(Modifier.height(Dimens.l))
        }
    }

    noteFor?.let { e ->
        val initial = when (e) {
            is AnnotationEntry.H -> e.highlight.note.orEmpty()
            is AnnotationEntry.N -> e.note.text
            is AnnotationEntry.B -> e.bookmark.title
        }
        NoteDialog(
            initial = initial,
            quote = (e as? AnnotationEntry.H)?.highlight?.text,
            onSave = { viewModel.saveNote(e, it); noteFor = null },
            onDelete = if (initial.isNotBlank()) ({ viewModel.saveNote(e, ""); noteFor = null }) else null,
            onDismiss = { noteFor = null },
        )
    }

    renameFor?.let { b ->
        TextInputDialog(
            title = stringResource(R.string.annotations_rename_bookmark),
            initial = b.bookmark.title,
            onConfirm = { viewModel.saveNote(b, it); renameFor = null },
            onDismiss = { renameFor = null },
        )
    }

    colorFor?.let { h ->
        YomuSheet(onDismiss = { colorFor = null }) {
            SheetTitle(stringResource(R.string.action_highlight))
            HighlightColor.entries.forEach { c ->
                SheetAction(Icons.Default.Palette, stringResource(c.label())) { colorFor = null; viewModel.recolor(h, c) }
            }
            Spacer(Modifier.height(Dimens.l))
        }
    }
}

private fun AnnotationEntry.shareText(): String = when (this) {
    is AnnotationEntry.H -> buildString {
        append("“").append(highlight.text).append("”")
        if (bookTitle.isNotBlank()) append(" — ").append(bookTitle)
        highlight.note?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
    }
    is AnnotationEntry.N -> note.text
    is AnnotationEntry.B -> "$text — $bookTitle"
}

fun AnnotationTab.label(): Int = when (this) {
    AnnotationTab.HIGHLIGHTS -> R.string.annotations_tab_highlights
    AnnotationTab.NOTES -> R.string.annotations_tab_notes
    AnnotationTab.BOOKMARKS -> R.string.annotations_tab_bookmarks
    AnnotationTab.QUOTES -> R.string.annotations_tab_quotes
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnnotationCard(entry: AnnotationEntry, showBook: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val h = (entry as? AnnotationEntry.H)?.highlight
    val stripe = h?.let { Color(it.color.argb) } ?: MaterialTheme.colorScheme.outlineVariant
    val colorName = h?.let { stringResource(it.color.label()) + ", " + stringResource(it.style.label()) }
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = Dimens.xs),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).heightIn(min = Dimens.touch)
                .then(if (colorName != null) Modifier.semantics { contentDescription = colorName + ". " + entry.text } else Modifier),
        ) {
            Box(Modifier.width(6.dp).fillMaxHeight().heightIn(min = 56.dp).background(stripe))
            Column(Modifier.weight(1f).padding(Dimens.m)) {
                when (entry) {
                    is AnnotationEntry.H -> {
                        Text(
                            entry.text,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontStyle = if (entry.highlight.isQuote) FontStyle.Italic else FontStyle.Normal,
                                textDecoration = when (entry.highlight.style) {
                                    HighlightStyle.UNDERLINE -> TextDecoration.Underline
                                    HighlightStyle.STRIKETHROUGH -> TextDecoration.LineThrough
                                    HighlightStyle.HIGHLIGHT -> null
                                },
                            ),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                        entry.highlight.note?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(Dimens.s))
                            Row {
                                Box(Modifier.width(2.dp).height(18.dp).background(MaterialTheme.colorScheme.primary))
                                Spacer(Modifier.width(Dimens.s))
                                MarkdownText(it, maxLines = 6)
                            }
                        }
                    }
                    is AnnotationEntry.N -> MarkdownText(entry.note.text, maxLines = 10)
                    is AnnotationEntry.B -> Text(entry.bookmark.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
                }
                Spacer(Modifier.height(Dimens.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val loc = entry.locator
                    val where = listOfNotNull(
                        if (showBook) entry.bookTitle.takeIf { it.isNotBlank() } else null,
                        loc?.chapterTitle?.takeIf { it.isNotBlank() },
                        loc?.let { "${(it.totalProgression * 100).toInt()}%" },
                        dateFormat.format(Date(entry.createdAt)),
                    ).joinToString(" · ")
                    Text(where, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (h?.isFavorite == true) Icon(Icons.Default.Star, stringResource(R.string.annotations_favorites_only), Modifier.padding(start = 4.dp).height(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}
