package app.yomu.feature.library

import android.os.Build
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import app.yomu.data.translation.BookTranslator
import app.yomu.core.ui.components.ChapterPickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.Files
import app.yomu.core.common.ScreenErrors
import app.yomu.core.ui.components.CoverImage
import app.yomu.core.ui.components.CoverTheme
import app.yomu.core.ui.components.rememberCoverColor
import androidx.compose.foundation.layout.heightIn
import app.yomu.core.ui.components.SkeletonList
import app.yomu.core.ui.components.TextInputDialog
import app.yomu.core.ui.components.YomuFab
import app.yomu.core.ui.components.coverTint
import app.yomu.core.ui.components.sharedCover
import app.yomu.core.ui.theme.Dimens
import app.yomu.feature.catalog.TrackingButton
import app.yomu.feature.catalog.TrackingSheet
import app.yomu.domain.model.Book
import app.yomu.domain.model.ReadingStatus
import app.yomu.domain.translation.CommonLanguages
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** The book page, tinted with the cover's colours (the app's scheme without a cover). */
@Composable
fun BookDetailsScreen(
    viewModel: BookDetailsViewModel,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    onOpenEditor: (Book) -> Unit,
    onOpenAnnotations: (Book) -> Unit,
    onOpenPageLayout: ((Book) -> Unit)? = null,
    inPane: Boolean = false,
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val hideCovers = app.yomu.core.ui.components.LocalHideCovers.current
    val coverModel = book?.coverPath?.takeIf { !hideCovers }?.let { File(it) }
    CoverTheme(rememberCoverColor(coverModel)) {
        BookDetailsContent(viewModel, onBack, onRead, onOpenEditor, onOpenAnnotations, onOpenPageLayout, inPane)
    }
}

@Composable
private fun BookDetailsContent(
    viewModel: BookDetailsViewModel,
    onBack: () -> Unit,
    onRead: (Book) -> Unit,
    onOpenEditor: (Book) -> Unit,
    onOpenAnnotations: (Book) -> Unit,
    onOpenPageLayout: ((Book) -> Unit)?,
    inPane: Boolean,
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val extras by viewModel.extras.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var translateDialog by remember { mutableStateOf(false) }
    var showTracking by remember { mutableStateOf(false) }
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(viewModel::setCover) }

    val current = book
    LaunchedEffect(current) { if (current === BookDetailsViewModel.MISSING) onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        if (inPane) Icon(Icons.Default.Close, stringResource(app.yomu.core.ui.R.string.close))
                        else Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(app.yomu.core.ui.R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    if (current != null && current.id > 0) {
                        IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, stringResource(R.string.details_edit_metadata)) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(app.yomu.core.ui.R.string.more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.details_change_cover)) }, leadingIcon = { Icon(Icons.Default.Image, null) },
                                    onClick = { menu = false; pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(app.yomu.core.ui.R.string.share)) }, leadingIcon = { Icon(Icons.Default.Share, null) },
                                    onClick = {
                                        menu = false
                                        scope.launch(ScreenErrors) {
                                            viewModel.shareIntent()?.let { context.startActivity(android.content.Intent.createChooser(it, current.title)) }
                                        }
                                    },
                                )
                                if (onOpenPageLayout != null && current.format == app.yomu.domain.model.BookFormat.DOCX) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.details_page_layout)) },
                                        leadingIcon = { Icon(Icons.Default.Description, null) },
                                        onClick = { menu = false; onOpenPageLayout(current) },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.details_open_editor)) }, leadingIcon = { Icon(Icons.Default.EditNote, null) },
                                    onClick = { menu = false; onOpenEditor(current) },
                                )
                                if (current.format.isText) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.details_translate_book)) }, leadingIcon = { Icon(Icons.Default.Translate, null) },
                                        onClick = { menu = false; translateDialog = true },
                                    )
                                }
                                if (!current.format.isComic) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.details_read_as_manga)) },
                                        leadingIcon = { Checkbox(checked = current.isManga, onCheckedChange = null) },
                                        onClick = { menu = false; viewModel.setManga(!current.isManga) },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(app.yomu.core.ui.R.string.delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = { menu = false; deleteDialog = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (current != null && current.id > 0) {
                YomuFab(
                    text = stringResource(
                        when {
                            current.format.isAudio -> if (current.progress > 0f) R.string.details_continue_listening else R.string.details_listen
                            current.progress > 0f -> R.string.details_continue
                            else -> R.string.details_read
                        },
                    ),
                    icon = if (current.format.isAudio) Icons.Default.Headphones else Icons.AutoMirrored.Filled.MenuBook,
                    onClick = { onRead(current) },
                )
            }
        },
    ) { padding ->
        if (current == null || current.id <= 0) {
            SkeletonList(Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
                val cover = current.coverPath?.takeIf { !app.yomu.core.ui.components.LocalHideCovers.current }?.let { File(it) }
                val tint = app.yomu.core.ui.components.rememberCoverColor(cover, app.yomu.core.ui.components.coverPlaceholderColor(current.title))
                if (cover != null) {
                    AsyncImage(
                        model = cover, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier.alpha(0.35f)),
                    )
                }
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f), MaterialTheme.colorScheme.surface)),
                    ),
                )
                // Over the scrim, or it would wash the colour out.
                Box(Modifier.matchParentSize().coverTint(tint))
                Row(Modifier.align(Alignment.BottomStart).padding(Dimens.l), verticalAlignment = Alignment.Bottom) {
                    CoverImage(cover, current.title, Modifier.width(120.dp).then(if (inPane) Modifier else Modifier.sharedCover("book-${current.id}")))
                    Column(Modifier.padding(start = Dimens.l).weight(1f)) {
                        Text(current.title, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (current.authors.isNotEmpty()) Text(current.authorsText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        current.series?.let { s ->
                            Text(listOfNotNull(s, current.seriesIndex?.let { "#" + it.toString().removeSuffix(".0") }).joinToString(" "), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            listOfNotNull(current.format.name, Files.humanSize(current.fileSize).takeIf { current.fileSize > 0 }, current.language?.uppercase(), current.publisher)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                if (current.progress > 0f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.weight(1f))
                        Text("  ${(current.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ReadingStatus.entries.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = current.status == s,
                            onClick = { viewModel.setStatus(s) },
                            shape = SegmentedButtonDefaults.itemShape(i, ReadingStatus.entries.size),
                        ) { Text(stringResource(s.label())) }
                    }
                }
                TrackingButton(tracks.size) { showTracking = true }
                if (extras.readingMs > 0) {
                    Text(stringResource(R.string.details_reading_time, formatDuration(extras.readingMs)), style = MaterialTheme.typography.bodyMedium)
                }
                current.description?.takeIf { it.isNotBlank() }?.let { d ->
                    Text(
                        android.text.Html.fromHtml(d, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.animateContentSize().clickable { descriptionExpanded = !descriptionExpanded },
                    )
                }
                Text(stringResource(R.string.library_tags), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    current.tags.forEach { t ->
                        InputChip(selected = false, onClick = { viewModel.setTags(current.tags - t) }, label = { Text("#$t") }, trailingIcon = { Text("×") })
                    }
                    AssistChip(onClick = { tagDialog = true }, label = { Text(stringResource(R.string.library_add_tag)) })
                }
                if (collections.isNotEmpty()) {
                    Text(stringResource(R.string.details_collections), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        collections.forEach { c ->
                            val member = c.id in extras.collections
                            FilterChip(selected = member, onClick = { viewModel.toggleCollection(c.id, member) }, label = { Text(c.name) }, leadingIcon = { Icon(Icons.Outlined.Collections, null) })
                        }
                    }
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.details_annotations)) },
                    supportingContent = { Text(stringResource(R.string.details_annotations_summary, extras.highlights, extras.notes, extras.bookmarks)) },
                    leadingContent = { Icon(Icons.Outlined.Bookmarks, null) },
                    modifier = Modifier.clickable { onOpenAnnotations(current) },
                )
                Spacer(Modifier.height(96.dp))
            }
        }
    }

    if (showTracking) TrackingSheet(viewModel, onDismiss = { showTracking = false })
    if (editing && current != null) {
        MetadataDialog(current, onDismiss = { editing = false }, onSave = { viewModel.save(it); editing = false })
    }
    if (tagDialog && current != null) {
        TextInputDialog(title = stringResource(R.string.library_add_tag), label = stringResource(R.string.library_tag), onConfirm = { viewModel.setTags((current.tags + it.split(',').map(String::trim)).filter(String::isNotBlank).distinct()) }, onDismiss = { tagDialog = false })
    }
    if (deleteDialog && current != null) {
        var withFiles by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text(stringResource(R.string.details_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.details_delete_text))
                    if (current.isLinked) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { withFiles = !withFiles }) {
                            Checkbox(checked = withFiles, onCheckedChange = { withFiles = it })
                            Text(stringResource(R.string.library_delete_files))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.delete(withFiles); deleteDialog = false }) { Text(stringResource(app.yomu.core.ui.R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text(stringResource(app.yomu.core.ui.R.string.cancel)) } },
        )
    }
    if (translateDialog) {
        TranslateBookDialog(viewModel, onDismiss = { translateDialog = false })
    }
}

@Composable
private fun TranslateBookDialog(viewModel: BookDetailsViewModel, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf("") }
    var bilingual by remember { mutableStateOf(true) }
    var chapters by remember { mutableStateOf<List<BookTranslator.Chapter>?>(null) }
    /** null = the whole book. */
    var chosen by remember { mutableStateOf<Set<Int>?>(null) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        target = viewModel.defaultTarget()
        chapters = viewModel.translationChapters()
    }
    if (picking) {
        ChapterPickerDialog(
            title = stringResource(R.string.details_translate_pick),
            chapters = chapters.orEmpty().map { it.index to it.title },
            selected = chosen.orEmpty(),
            onConfirm = { chosen = it },
            onDismiss = { picking = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details_translate_book)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Text(stringResource(R.string.details_translate_hint), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    CommonLanguages.forEach { l ->
                        FilterChip(selected = target == l, onClick = { target = l }, label = { Text(Locale.forLanguageTag(l).getDisplayLanguage().ifBlank { l }) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.details_bilingual), modifier = Modifier.weight(1f))
                    Switch(checked = bilingual, onCheckedChange = { bilingual = it })
                }
                val list = chapters
                if (list != null && list.size > 1) {
                    Text(stringResource(R.string.details_translate_what), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = chosen == null, role = Role.RadioButton) { chosen = null },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == null, onClick = null)
                        Text(pluralStringResource(R.plurals.details_translate_all, list.size, list.size), Modifier.padding(start = Dimens.s))
                    }
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = chosen != null, role = Role.RadioButton) { picking = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen != null, onClick = null)
                        Column(Modifier.padding(start = Dimens.s)) {
                            Text(stringResource(R.string.details_translate_some))
                            chosen?.let { set ->
                                Text(
                                    pluralStringResource(R.plurals.details_translate_chosen, set.size, set.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = target.isNotBlank() && chosen?.isEmpty() != true, onClick = { viewModel.translateBook(target, bilingual, chosen); onDismiss() }) {
                Text(stringResource(R.string.details_start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(app.yomu.core.ui.R.string.cancel)) } },
    )
}

@Composable
private fun MetadataDialog(book: Book, onDismiss: () -> Unit, onSave: (Book) -> Unit) {
    var title by rememberSaveable { mutableStateOf(book.title) }
    var authors by rememberSaveable { mutableStateOf(book.authors.joinToString(", ")) }
    var series by rememberSaveable { mutableStateOf(book.series.orEmpty()) }
    var index by rememberSaveable { mutableStateOf(book.seriesIndex?.toString()?.removeSuffix(".0").orEmpty()) }
    var language by rememberSaveable { mutableStateOf(book.language.orEmpty()) }
    var publisher by rememberSaveable { mutableStateOf(book.publisher.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(book.description.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details_edit_metadata)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.meta_title)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(authors, { authors = it }, label = { Text(stringResource(R.string.meta_authors)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    OutlinedTextField(series, { series = it }, label = { Text(stringResource(R.string.meta_series)) }, modifier = Modifier.weight(2f))
                    OutlinedTextField(index, { index = it }, label = { Text("#") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    OutlinedTextField(language, { language = it }, label = { Text(stringResource(R.string.meta_language)) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(publisher, { publisher = it }, label = { Text(stringResource(R.string.meta_publisher)) }, modifier = Modifier.weight(2f))
                }
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.meta_description)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(
                    book.copy(
                        title = title.trim(),
                        authors = authors.split(',', ';', '&').map { it.trim() }.filter { it.isNotEmpty() },
                        series = series.trim().ifBlank { null },
                        seriesIndex = index.replace(',', '.').toFloatOrNull(),
                        language = language.trim().lowercase().ifBlank { null },
                        publisher = publisher.trim().ifBlank { null },
                        description = description.trim().ifBlank { null },
                    ),
                )
            }) { Text(stringResource(app.yomu.core.ui.R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(app.yomu.core.ui.R.string.cancel)) } },
    )
}

internal fun ReadingStatus.label(): Int = when (this) {
    ReadingStatus.NEW -> R.string.status_new
    ReadingStatus.READING -> R.string.status_reading
    ReadingStatus.FINISHED -> R.string.status_finished
}

@Composable
internal fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return if (minutes >= 60) stringResource(R.string.duration_hm, minutes / 60, minutes % 60) else stringResource(R.string.duration_m, minutes)
}
