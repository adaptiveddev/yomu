package app.yomu.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import app.yomu.core.ui.theme.Dimens
import app.yomu.domain.model.BatchMetadata
import app.yomu.domain.model.BatchOrder
import app.yomu.domain.model.Book
import app.yomu.domain.model.ReadingStatus
import app.yomu.core.ui.R as CoreR

/**
 * Metadata of several books at once: a field the user leaves as it is keeps every book's own value; a value typed
 * in goes to all of them; the clear button empties the field of all of them.
 */
@Composable
internal fun BatchMetadataDialog(books: List<Book>, onDismiss: () -> Unit, onApply: (BatchMetadata) -> Unit) {
    val authorValues = remember(books) { books.map { b -> b.authors.joinToString(", ").ifEmpty { null } } }
    val seriesValues = remember(books) { books.map { it.series } }
    val languageValues = remember(books) { books.map { it.language } }
    val publisherValues = remember(books) { books.map { it.publisher } }
    val tagCounts = remember(books) { books.flatMap { it.tags }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second } }

    var authors by rememberSaveable { mutableStateOf(BatchMetadata.common(authorValues).orEmpty()) }
    var authorsClear by rememberSaveable { mutableStateOf(false) }
    var series by rememberSaveable { mutableStateOf(BatchMetadata.common(seriesValues).orEmpty()) }
    var seriesClear by rememberSaveable { mutableStateOf(false) }
    var language by rememberSaveable { mutableStateOf(BatchMetadata.common(languageValues).orEmpty()) }
    var languageClear by rememberSaveable { mutableStateOf(false) }
    var publisher by rememberSaveable { mutableStateOf(BatchMetadata.common(publisherValues).orEmpty()) }
    var publisherClear by rememberSaveable { mutableStateOf(false) }
    var number by rememberSaveable { mutableStateOf(false) }
    var numberFrom by rememberSaveable { mutableStateOf("1") }
    var bySelection by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<ReadingStatus?>(null) }
    var addTags by rememberSaveable { mutableStateOf("") }
    var removeTags by rememberSaveable { mutableStateOf(emptySet<String>()) }

    val seriesEdit = BatchMetadata.fieldEdit(series, BatchMetadata.common(seriesValues), seriesClear)
    val edit = BatchMetadata(
        authors = BatchMetadata.fieldEdit(authors, BatchMetadata.common(authorValues), authorsClear),
        series = seriesEdit,
        numberFrom = if (number) numberFrom.replace(',', '.').toFloatOrNull() else null,
        order = if (bySelection) BatchOrder.SELECTION else BatchOrder.TITLE,
        language = BatchMetadata.fieldEdit(language, BatchMetadata.common(languageValues), languageClear),
        publisher = BatchMetadata.fieldEdit(publisher, BatchMetadata.common(publisherValues), publisherClear),
        status = status,
        addTags = addTags.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() },
        removeTags = removeTags,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.library_batch_title, books.size, books.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Text(stringResource(R.string.library_batch_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BatchTextField(stringResource(R.string.meta_authors), authors, { authors = it }, authorValues, authorsClear, { authorsClear = it })
                BatchTextField(stringResource(R.string.meta_series), series, { series = it }, seriesValues, seriesClear, { seriesClear = it })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    Text(stringResource(R.string.library_batch_number), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = number, onCheckedChange = { number = it }, enabled = seriesEdit !is app.yomu.domain.model.FieldEdit.Clear)
                }
                if (number && seriesEdit !is app.yomu.domain.model.FieldEdit.Clear) {
                    OutlinedTextField(
                        numberFrom,
                        { numberFrom = it },
                        label = { Text(stringResource(R.string.library_batch_number_from)) },
                        singleLine = true,
                        isError = numberFrom.replace(',', '.').toFloatOrNull() == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        FilterChip(selected = !bySelection, onClick = { bySelection = false }, label = { Text(stringResource(R.string.library_batch_order_title)) })
                        FilterChip(selected = bySelection, onClick = { bySelection = true }, label = { Text(stringResource(R.string.library_batch_order_selection)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    BatchTextField(
                        stringResource(R.string.meta_language), language, { language = it }, languageValues, languageClear, { languageClear = it },
                        modifier = Modifier.weight(1f),
                    )
                    BatchTextField(
                        stringResource(R.string.meta_publisher), publisher, { publisher = it }, publisherValues, publisherClear, { publisherClear = it },
                        modifier = Modifier.weight(1.6f),
                    )
                }
                Text(stringResource(R.string.library_batch_status), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    FilterChip(selected = status == null, onClick = { status = null }, label = { Text(stringResource(R.string.library_batch_keep)) })
                    ReadingStatus.entries.forEach { st ->
                        FilterChip(selected = status == st, onClick = { status = st }, label = { Text(stringResource(st.label())) })
                    }
                }
                Text(stringResource(R.string.library_batch_tags), style = MaterialTheme.typography.titleSmall)
                if (tagCounts.isNotEmpty()) {
                    Text(stringResource(R.string.library_batch_tags_remove), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                        tagCounts.forEach { (tag, count) ->
                            val removed = tag in removeTags
                            FilterChip(
                                selected = removed,
                                onClick = { removeTags = if (removed) removeTags - tag else removeTags + tag },
                                label = {
                                    Text(
                                        if (count < books.size) "$tag · $count" else tag,
                                        textDecoration = if (removed) TextDecoration.LineThrough else null,
                                    )
                                },
                                leadingIcon = if (removed) {
                                    { Icon(Icons.Default.Close, null) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    addTags,
                    { addTags = it },
                    label = { Text(stringResource(R.string.library_batch_tags_add)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            val numberOk = !number || numberFrom.replace(',', '.').toFloatOrNull() != null
            TextButton(enabled = !edit.isEmpty && numberOk, onClick = { onApply(edit) }) { Text(stringResource(R.string.library_batch_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

/**
 * A text field of the batch editor. It starts with the value all the books share ([values]); when they differ it
 * starts empty and says so. The trailing button clears the value of all books (or undoes that).
 */
@Composable
private fun BatchTextField(
    label: String,
    text: String,
    onText: (String) -> Unit,
    values: List<String?>,
    cleared: Boolean,
    onCleared: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val common = BatchMetadata.common(values)
    val mixed = values.distinct().size > 1
    OutlinedTextField(
        value = if (cleared) "" else text,
        onValueChange = {
            onCleared(false)
            onText(it)
        },
        label = { Text(label) },
        placeholder = if (mixed && !cleared) {
            { Text(stringResource(R.string.library_batch_mixed)) }
        } else {
            null
        },
        supportingText = when {
            cleared || (common != null && text.isBlank()) -> {
                { Text(stringResource(R.string.library_batch_cleared), color = MaterialTheme.colorScheme.error) }
            }
            else -> null
        },
        trailingIcon = when {
            cleared || (common != null && text.isBlank()) -> {
                {
                    IconButton(onClick = {
                        onCleared(false)
                        onText(common.orEmpty())
                    }) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.library_batch_keep)) }
                }
            }
            mixed || text.isNotEmpty() -> {
                {
                    IconButton(onClick = {
                        onText("")
                        onCleared(true)
                    }) { Icon(Icons.Default.Clear, stringResource(R.string.library_batch_clear)) }
                }
            }
            else -> null
        },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}
