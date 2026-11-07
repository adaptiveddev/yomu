package app.yomu.core.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yomu.core.ui.R
import app.yomu.core.ui.theme.Dimens

/** Multiple choice of chapters (index → title), for example the ones to translate. */
@Composable
fun ChapterPickerDialog(
    title: String,
    chapters: List<Pair<Int, String>>,
    selected: Set<Int>,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember(chapters) { mutableStateOf(selected.filterTo(LinkedHashSet()) { i -> chapters.any { it.first == i } }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                item(key = "all") {
                    val all = chosen.size == chapters.size && chapters.isNotEmpty()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
                            .toggleable(value = all, role = Role.Checkbox) { v -> chosen = if (v) chapters.mapTo(LinkedHashSet()) { it.first } else LinkedHashSet() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = all, onCheckedChange = null)
                        Text(stringResource(R.string.select_all), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = Dimens.m))
                    }
                }
                items(chapters, key = { it.first }) { (index, name) ->
                    val on = index in chosen
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
                            .toggleable(value = on, role = Role.Checkbox) { v -> chosen = LinkedHashSet(if (v) chosen + index else chosen - index) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(
                            "${index + 1}. $name",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = Dimens.m),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onConfirm(chosen); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
