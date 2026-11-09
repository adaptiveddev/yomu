package app.yomu.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.Share
import app.yomu.core.ui.R as CoreR
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.PreferenceHeader
import app.yomu.core.ui.components.TextInputDialog
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.backup.GitHubSyncBackend
import app.yomu.data.backup.SyncManager
import app.yomu.domain.model.QuickActions
import app.yomu.domain.model.QuickMenuStyle
import app.yomu.domain.tracking.TrackService
import app.yomu.feature.annotations.icon
import app.yomu.feature.annotations.label
import java.text.DateFormat
import java.util.Date

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs))
}

// region Sync

@Composable
internal fun ColumnScope.SyncSection(vm: SettingsViewModel, onOpenProblems: () -> Unit) {
    val s = vm.settings
    val backend by s.syncBackend.state()
    val folder by s.syncFolder.state()
    val url by s.webdavUrl.state()
    val githubToken by s.githubToken.state()
    val githubRepo by s.githubRepo.state()
    val enabled by s.syncEnabled.state()
    val lastSync by s.lastSyncAt.state()
    val lastError by s.lastSyncError.state()
    val webdav = backend == SyncManager.WEBDAV
    val github = backend == SyncManager.GITHUB
    val configured = when {
        webdav -> url.isNotBlank()
        github -> githubToken.isNotBlank() && githubRepo.isNotBlank()
        else -> folder.isNotBlank()
    }
    var createRepo by remember { mutableStateOf(false) }
    val folderPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::setSyncFolder) }

    Hint(stringResource(R.string.set_sync_hint))
    PreferenceSwitch(stringResource(R.string.set_sync), enabled, { v -> vm.set(s.syncEnabled, v) { vm.rescheduleSync() } }, enabled = configured, icon = settingIcon(R.string.set_sync))
    val last = if (lastSync > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastSync)) else null
    PreferenceItem(stringResource(R.string.set_sync_now), last?.let { stringResource(R.string.set_last_time, it) }, enabled = configured, onClick = vm::syncNow, icon = settingIcon(R.string.set_sync_now))
    if (lastError.isNotBlank()) {
        PreferenceItem(
            stringResource(R.string.set_sync_problems),
            stringResource(R.string.set_sync_failed, lastError),
            icon = Icons.Outlined.ErrorOutline,
            onClick = onOpenProblems,
        )
    }

    PreferenceHeader(stringResource(R.string.set_sync_target))
    ChoicePref(
        vm, s.syncBackend, R.string.set_sync_method,
        listOf(
            SyncManager.FOLDER to stringResource(R.string.set_sync_target_folder),
            SyncManager.WEBDAV to stringResource(R.string.set_sync_target_webdav),
            SyncManager.GITHUB to stringResource(R.string.set_sync_target_github),
        ),
        after = { vm.rescheduleSync() },
    )
    if (webdav) {
        TextPref(vm, s.webdavUrl, R.string.set_webdav_url, summaryWhenEmpty = R.string.set_webdav_url_hint, icon = Icons.Outlined.CloudSync)
        TextPref(vm, s.webdavUser, R.string.set_webdav_user, icon = Icons.Outlined.Person)
        TextPref(vm, s.webdavPassword, R.string.set_webdav_password, secret = true, summaryWhenEmpty = R.string.set_webdav_password_sum, icon = Icons.Outlined.Password)
    } else if (github) {
        Hint(stringResource(R.string.set_github_hint))
        TextPref(vm, s.githubToken, R.string.set_github_token, secret = true, summaryWhenEmpty = R.string.set_github_token_sum, icon = Icons.Outlined.Key)
        TextPref(vm, s.githubRepo, R.string.set_github_repo, summaryWhenEmpty = R.string.set_github_repo_hint, icon = Icons.Outlined.CloudSync)
        PreferenceItem(
            stringResource(R.string.set_github_create),
            stringResource(R.string.set_github_create_sum),
            enabled = githubToken.isNotBlank(),
            onClick = { createRepo = true },
            icon = Icons.Outlined.CreateNewFolder,
        )
    } else {
        PreferenceItem(
            stringResource(R.string.set_sync_folder),
            folder.takeIf { it.isNotBlank() }?.let { android.net.Uri.decode(it.substringAfterLast("/tree/")) } ?: stringResource(R.string.set_not_set),
            onClick = { folderPick.launch(null) },
            icon = settingIcon(R.string.set_sync_folder),
        )
    }
    PreferenceItem(stringResource(R.string.set_sync_check), enabled = configured, onClick = vm::checkSync, icon = settingIcon(R.string.set_sync_check))
    if (createRepo) {
        TextInputDialog(
            title = stringResource(R.string.set_github_create),
            initial = GitHubSyncBackend.DEFAULT_REPOSITORY,
            label = stringResource(R.string.set_github_create_name),
            onConfirm = { name -> createRepo = false; vm.createGitHubRepository(name) },
            onDismiss = { createRepo = false },
        )
    }

    PreferenceHeader(stringResource(R.string.set_sync_when))
    SwitchPref(vm, s.syncOnStart, R.string.set_sync_on_start)
    SwitchPref(vm, s.syncOnExit, R.string.set_sync_on_exit)
    ChoicePref(
        vm, s.syncIntervalHours, R.string.set_sync_interval,
        listOf(0, 1, 3, 6, 12, 24).map { h -> h to if (h <= 0) stringResource(R.string.set_off) else pluralStringResource(R.plurals.set_every_hours, h, h) },
        after = { vm.rescheduleSync() },
    )
    if (webdav || github) SwitchPref(vm, s.syncWifiOnly, R.string.set_sync_wifi_only, R.string.set_sync_wifi_only_sum, after = { vm.rescheduleSync() })

    PreferenceHeader(stringResource(R.string.set_sync_what))
    Hint(stringResource(R.string.set_sync_what_hint))
    SwitchPref(vm, s.syncProgress, R.string.set_sync_progress, R.string.set_sync_progress_sum)
    SwitchPref(vm, s.syncAnnotations, R.string.set_sync_annotations, R.string.set_sync_annotations_sum)
    SwitchPref(vm, s.syncBookmarks, R.string.set_sync_bookmarks, R.string.set_sync_bookmarks_sum)
    SwitchPref(vm, s.syncWords, R.string.set_sync_words, R.string.set_sync_words_sum)
    SwitchPref(vm, s.syncCatalog, R.string.set_sync_catalog, R.string.set_sync_catalog_sum)
    SwitchPref(vm, s.syncTrackLinks, R.string.set_sync_track_links, R.string.set_sync_track_links_sum)
    SwitchPref(vm, s.syncBookFiles, R.string.set_sync_book_files, R.string.set_sync_book_files_sum)
    SwitchPref(vm, s.syncDeletions, R.string.set_sync_deletions, R.string.set_sync_deletions_sum)
    if (webdav) {
        Hint(stringResource(R.string.set_sync_track_links_hint))
        Hint(stringResource(R.string.set_sync_webdav_etag_hint))
    } else if (github) {
        Hint(stringResource(R.string.set_sync_track_links_hint))
    } else {
        Hint(stringResource(R.string.set_sync_track_links_hint))
        Hint(stringResource(R.string.set_sync_folder_warning))
    }

    Hint(stringResource(R.string.set_sync_all_devices))
}

// endregion

// region Tracking

@Composable
internal fun ColumnScope.TrackingSection(vm: SettingsViewModel) {
    val s = vm.settings
    val context = LocalContext.current
    // Recomposes after a sign-in (the browser comes back to TrackLoginActivity) or a sign-out.
    val version by vm.trackers.store.version.collectAsStateWithLifecycle()
    var signOut by remember { mutableStateOf<TrackService?>(null) }
    var noKey by remember { mutableStateOf<TrackService?>(null) }
    var editKey by remember { mutableStateOf<Pair<TrackService, Boolean>?>(null) }
    var passwordService by remember { mutableStateOf<TrackService?>(null) }

    Hint(stringResource(R.string.set_tracking_hint))
    TrackService.entries.forEach { service ->
        val tracker = remember(version, service) { vm.trackers.tracker(service) }
        val loggedIn = remember(version, service) { tracker.loggedIn() }
        val user = remember(version, service) { tracker.userName() }
        PreferenceItem(
            service.displayName,
            when {
                loggedIn && !user.isNullOrBlank() -> stringResource(R.string.set_track_signed_in, user)
                loggedIn -> stringResource(R.string.set_track_signed_in_anon)
                else -> stringResource(R.string.set_track_signed_out)
            },
            icon = if (loggedIn) Icons.AutoMirrored.Outlined.Logout else Icons.AutoMirrored.Outlined.Login,
            onClick = {
                when {
                    loggedIn -> signOut = service
                    tracker.passwordLogin -> passwordService = service
                    !tracker.configured() -> noKey = service
                    else -> Share.openUrl(context, tracker.authUrl())
                }
            },
        )
    }
    SwitchPref(vm, s.trackAutoUpdate, R.string.set_track_auto, R.string.set_track_auto_sum)
    PreferenceItem(stringResource(R.string.set_track_push), stringResource(R.string.set_track_push_sum), onClick = vm::pushTracking, icon = settingIcon(R.string.set_track_push))

    PreferenceHeader(stringResource(R.string.set_track_keys))
    Hint(stringResource(R.string.set_track_keys_hint))
    TrackService.entries.filterNot { vm.trackers.tracker(it).passwordLogin }.forEach { service ->
        val tracker = remember(version, service) { vm.trackers.tracker(service) }
        val own = remember(version, service) { vm.trackers.store.clientIdOverride(service) }
        PreferenceItem(
            stringResource(R.string.set_track_client_id, service.displayName),
            own.ifBlank { stringResource(if (tracker.configured()) R.string.set_track_key_builtin else R.string.set_not_set) },
            icon = Icons.Outlined.Key,
            onClick = { editKey = service to false },
        )
        if (tracker.needsSecret) {
            val ownSecret = remember(version, service) { vm.trackers.store.clientSecretOverride(service) }
            PreferenceItem(
                stringResource(R.string.set_track_client_secret, service.displayName),
                if (ownSecret.isNotBlank()) "•".repeat(12) else stringResource(if (tracker.configured()) R.string.set_track_key_builtin else R.string.set_not_set),
                icon = Icons.Outlined.Password,
                onClick = { editKey = service to true },
            )
        }
        Hint(stringResource(R.string.set_track_redirect, tracker.redirectUri))
    }

    passwordService?.let { service ->
        var username by remember(service) { mutableStateOf("") }
        var password by remember(service) { mutableStateOf("") }
        fun dismiss() { username = ""; password = ""; passwordService = null }
        AlertDialog(
            onDismissRequest = { dismiss() },
            title = { Text(service.displayName) },
            text = { Column {
                Text(stringResource(R.string.set_track_password_notice))
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.set_track_email)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.set_track_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { TextButton(enabled = username.isNotBlank() && password.isNotEmpty(), onClick = { vm.trackerPasswordLogin(service, username, password); dismiss() }) { Text(stringResource(R.string.set_track_sign_in)) } },
            dismissButton = { TextButton(onClick = { dismiss() }) { Text(stringResource(CoreR.string.cancel)) } },
        )
    }
    signOut?.let { service ->
        ConfirmDialog(
            stringResource(R.string.set_track_sign_out),
            stringResource(R.string.set_track_sign_out_confirm, service.displayName),
            stringResource(R.string.set_track_sign_out),
            onConfirm = { vm.trackerLogout(service) },
            onDismiss = { signOut = null },
        )
    }
    noKey?.let { service ->
        AlertDialog(
            onDismissRequest = { noKey = null },
            title = { Text(service.displayName) },
            text = { Text(stringResource(R.string.set_track_no_key, service.displayName, vm.trackers.tracker(service).redirectUri)) },
            confirmButton = { TextButton(onClick = { noKey = null; editKey = service to false }) { Text(stringResource(R.string.set_track_enter_key)) } },
            dismissButton = { TextButton(onClick = { noKey = null }) { Text(stringResource(CoreR.string.cancel)) } },
        )
    }
    editKey?.let { (service, secret) ->
        val store = vm.trackers.store
        if (secret) {
            SecretInputDialog(
                stringResource(R.string.set_track_client_secret, service.displayName),
                store.clientSecretOverride(service),
                onConfirm = { v -> vm.setTrackerClient(service, store.clientIdOverride(service), v); editKey = null },
                onDismiss = { editKey = null },
            )
        } else {
            TextInputDialog(
                stringResource(R.string.set_track_client_id, service.displayName),
                store.clientIdOverride(service),
                onConfirm = { v -> vm.setTrackerClient(service, v, null); editKey = null },
                onDismiss = { editKey = null },
            )
        }
    }
}

// endregion

// region Long-press menus

@Composable
internal fun QuickActionsSection(vm: SettingsViewModel, onOpenSelection: () -> Unit) {
    val s = vm.settings
    val stored by s.pageActions.state()
    val actions = remember(stored) { QuickActions.normalize(stored) }
    fun move(i: Int, d: Int) = vm.update(s.pageActions) { list ->
        val l = QuickActions.normalize(list).toMutableList()
        val j = i + d
        if (j in l.indices) l.add(j, l.removeAt(i))
        l
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
        item(key = "page-style") {
            ChoicePref(
                vm, s.pageMenuStyle, R.string.set_page_menu_style,
                listOf(
                    QuickMenuStyle.RADIAL to stringResource(R.string.set_style_radial),
                    QuickMenuStyle.LIST to stringResource(R.string.set_style_list),
                    QuickMenuStyle.OFF to stringResource(R.string.set_style_off),
                ),
            )
        }
        item(key = "text-style") {
            ChoicePref(
                vm, s.selectionMenuStyle, R.string.set_selection_menu_style,
                listOf(
                    QuickMenuStyle.BAR to stringResource(R.string.set_style_bar),
                    QuickMenuStyle.RADIAL to stringResource(R.string.set_style_radial),
                    QuickMenuStyle.LIST to stringResource(R.string.set_style_list),
                ),
            )
        }
        item(key = "text-actions") {
            PreferenceItem(stringResource(R.string.set_selection_actions), stringResource(R.string.set_text_actions_sum), onClick = onOpenSelection, icon = settingIcon(R.string.set_selection_actions))
        }
        item(key = "page-h") { PreferenceHeader(stringResource(R.string.set_page_actions)) }
        item(key = "page-hint") { Hint(stringResource(R.string.set_page_actions_hint)) }
        itemsIndexed(actions, key = { _, a -> a.action.name }) { i, a ->
            ListItem(
                headlineContent = { Text(stringResource(a.action.label())) },
                leadingContent = { Icon(a.action.icon(), null) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.set_move_up)) }
                        IconButton(onClick = { move(i, 1) }, enabled = i < actions.lastIndex) { Icon(Icons.Filled.ArrowDownward, stringResource(R.string.set_move_down)) }
                        Switch(checked = a.enabled, onCheckedChange = { on -> vm.update(s.pageActions) { l -> QuickActions.normalize(l).map { if (it.action == a.action) it.copy(enabled = on) else it } } })
                    }
                },
            )
        }
        item(key = "page-reset") {
            PreferenceItem(stringResource(R.string.set_page_actions_reset), onClick = { vm.set(s.pageActions, QuickActions.defaultPageActions) }, icon = Icons.Outlined.RestartAlt)
        }
    }
}

// endregion

// region Mihon / Tachiyomi import

@Composable
internal fun MihonImportItem(vm: SettingsViewModel) {
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importMihon) }
    PreferenceItem(stringResource(R.string.set_mihon_import), stringResource(R.string.set_mihon_import_sum), onClick = { open.launch(arrayOf("*/*")) }, icon = settingIcon(R.string.set_mihon_import))
    val result by vm.mihonResult.collectAsStateWithLifecycle()
    result?.let { r ->
        AlertDialog(
            onDismissRequest = { vm.mihonResult.value = null },
            title = { Text(stringResource(R.string.set_mihon_done_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    Text(stringResource(R.string.set_mihon_done_text, r.titles, r.newTitles, r.chapters, r.tabs, r.tracks))
                    if (r.missingSources.isNotEmpty()) {
                        Text(stringResource(R.string.set_mihon_missing, r.missingSources.size), style = MaterialTheme.typography.titleSmall)
                        Text(r.missingSources.take(30).joinToString("\n") { "• ${it.name} (${it.titles})" }, style = MaterialTheme.typography.bodySmall)
                        if (r.missingSources.size > 30) Text("…", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.set_mihon_next), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.repos.isNotEmpty()) {
                        TextButton(onClick = { vm.addStores(r.repos) }) { Text(stringResource(R.string.set_mihon_add_stores, r.repos.size)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.mihonResult.value = null; vm.checkLibraryUpdates() }) { Text(stringResource(R.string.set_mihon_check_updates)) } },
            dismissButton = { TextButton(onClick = { vm.mihonResult.value = null }) { Text(stringResource(CoreR.string.close)) } },
        )
    }
}

// endregion
