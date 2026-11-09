package app.yomu.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.backup.SyncProblemInfo
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Sync → conflicts: what stopped the last sync, item by item. Nothing was changed by
 * the failed run; resolutions appear here as the corresponding sync features land.
 */
@Composable
internal fun SyncProblemsSection(vm: SettingsViewModel) {
    LaunchedEffect(Unit) { vm.loadSyncProblems() }
    val problems by vm.syncProblems.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
        item(key = "hint") {
            Text(
                stringResource(R.string.set_sync_problems_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
            )
        }
        if (problems.isEmpty()) {
            item(key = "none") {
                Text(
                    stringResource(R.string.set_sync_problems_none),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Dimens.l),
                )
            }
        } else {
            items(problems, key = { "${it.id}#${it.at}" }) { problem ->
                val target = problem.target?.toLongOrNull()
                SyncProblemRow(
                    problem,
                    onDismiss = { vm.dismissSyncProblem(problem.id) },
                    onAdopt = if (problem.kind == "book_changed" || problem.kind == "book_update_conflict") {
                        target?.let { id -> { vm.adoptChangedBook(id) } }
                    } else null,
                    onTakeRemote = if (problem.kind == "book_update_conflict") {
                        target?.let { id -> { vm.takeRemoteBookVersion(id) } }
                    } else null,
                    onMigrate = if (problem.kind == "folder_legacy") {
                        target?.let { id -> { vm.migrateLegacyFolder(id) } }
                    } else null,
                    onPublish = if (problem.kind == "folder_changed_page" || problem.kind == "folder_missing_page") {
                        target?.let { id -> { vm.publishFolderVersion(id) } }
                    } else null,
                    onAcceptRemote = if (problem.kind == "folder_changed_page" || problem.kind == "folder_missing_page") {
                        target?.let { id -> { vm.acceptFolderVersion(id) } }
                    } else null,
                )
            }
            item(key = "clear") {
                PreferenceItem(stringResource(R.string.set_sync_problems_clear), icon = Icons.Outlined.ClearAll, onClick = vm::clearSyncProblems)
            }
        }
    }
}

@Composable
private fun SyncProblemRow(
    problem: SyncProblemInfo,
    onDismiss: () -> Unit,
    onAdopt: (() -> Unit)?,
    onTakeRemote: (() -> Unit)?,
    onMigrate: (() -> Unit)? = null,
    onPublish: (() -> Unit)? = null,
    onAcceptRemote: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = Dimens.m),
        )
        Column(
            Modifier.weight(1f).clickable(onClick = onDismiss),
            verticalArrangement = Arrangement.spacedBy(Dimens.xxs),
        ) {
            Text(
                when (problem.subject) {
                    "" -> stringResource(problemLabel(problem.kind))
                    else -> stringResource(problemLabel(problem.kind)) + " — " + problem.subject
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (problem.detail.isNotBlank()) {
                Text(
                    problem.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(problem.at)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onAdopt != null || onTakeRemote != null || onMigrate != null || onPublish != null || onAcceptRemote != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s), modifier = Modifier.padding(top = Dimens.xxs)) {
                    if (onAdopt != null) TextButton(onAdopt) { Text(stringResource(R.string.set_sync_problem_adopt)) }
                    if (onTakeRemote != null) TextButton(onTakeRemote) { Text(stringResource(R.string.set_sync_problem_take_remote)) }
                    if (onMigrate != null) TextButton(onMigrate) { Text(stringResource(R.string.set_sync_problem_migrate_folder)) }
                    if (onPublish != null) TextButton(onPublish) { Text(stringResource(R.string.set_sync_problem_publish_folder)) }
                    if (onAcceptRemote != null) TextButton(onAcceptRemote) { Text(stringResource(R.string.set_sync_problem_accept_folder)) }
                }
            }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.set_sync_problems_dismiss)) }
    }
}

/** A localized one-line name for each machine kind; unknown kinds fall back to the generic label. */
private fun problemLabel(kind: String): Int = when (kind) {
    "error" -> R.string.set_sync_problem_error
    "annotation_conflict" -> R.string.set_sync_problem_annotation_conflict
    "annotation_no_proof" -> R.string.set_sync_problem_annotation_no_proof
    "annotation_duplicate" -> R.string.set_sync_problem_annotation_duplicate
    "folder_legacy" -> R.string.set_sync_problem_folder_legacy
    "folder_similar" -> R.string.set_sync_problem_folder_similar
    "folder_diverged" -> R.string.set_sync_problem_folder_diverged
    "folder_missing_revision" -> R.string.set_sync_problem_folder_missing_revision
    "folder_changed_page" -> R.string.set_sync_problem_folder_changed_page
    "folder_missing_page" -> R.string.set_sync_problem_folder_missing_page
    else -> R.string.set_sync_problem_error
}
