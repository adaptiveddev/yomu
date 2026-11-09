package app.yomu.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CallToAction
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.datastore.Pref
import app.yomu.core.ui.components.topLevelContentInsets
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.components.TopLevelBody
import app.yomu.core.ui.components.SearchField
import app.yomu.core.ui.components.EmptyState
import androidx.compose.material.icons.outlined.SearchOff
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.theme.Dimens
import kotlinx.coroutines.launch
import app.yomu.core.ui.R as CoreR

/** Sections of the settings screen. [items] are the titles used by the settings search. */
enum class SettingsSection(val title: Int, val summary: Int, val icon: ImageVector, val items: List<Int>, val visible: Boolean = true) {
    APPEARANCE(R.string.set_appearance, R.string.set_appearance_sum, Icons.Outlined.Palette, listOf(R.string.set_theme, R.string.set_dynamic_color, R.string.set_amoled, R.string.set_accent, R.string.set_accent_custom, R.string.set_app_icon, R.string.set_language, R.string.set_haptics, R.string.set_reduce_motion)),
    NAVIGATION(R.string.set_navigation, R.string.set_navigation_sum, Icons.Outlined.CallToAction, listOf(R.string.set_nav_labels, R.string.set_nav_tabs, R.string.set_nav_reset)),
    LIBRARY(R.string.set_library, R.string.set_library_sum, Icons.Outlined.LocalLibrary, listOf(R.string.set_grid, R.string.set_columns, R.string.set_show_continue, R.string.set_hide_covers, R.string.set_import_mode, R.string.set_folders, R.string.set_auto_scan, R.string.set_scan_now, R.string.set_ask_delete)),
    READER(R.string.set_reader, R.string.set_reader_sum, Icons.AutoMirrored.Outlined.MenuBook, listOf(R.string.set_fullscreen, R.string.set_cutout, R.string.set_tap_turn, R.string.set_volume_keys, R.string.set_screen_timeout, R.string.set_orientation, R.string.set_font_size, R.string.set_line_height, R.string.set_margins, R.string.set_paged, R.string.set_hyphenation, R.string.set_publisher_styles, R.string.set_reader_theme, R.string.set_word_tap, R.string.set_web_search, R.string.set_tts_speed, R.string.set_reading_stats)),
    MANGA(R.string.set_manga, R.string.set_manga_sum, Icons.Outlined.Collections, listOf(R.string.set_direction, R.string.set_scale, R.string.set_dual_page, R.string.set_crop_borders, R.string.set_tap_zones, R.string.set_preload, R.string.set_page_number, R.string.set_grayscale, R.string.set_pdf_mode, R.string.set_pdf_night, R.string.set_pdf_write_annotations, R.string.set_ocr_language)),
    SELECTION(R.string.set_selection, R.string.set_selection_sum, Icons.Outlined.TextFields, listOf(R.string.set_selection_actions, R.string.set_custom_actions)),
    QUICK_ACTIONS(R.string.set_quick, R.string.set_quick_sum, Icons.Outlined.TouchApp, listOf(R.string.set_page_menu_style, R.string.set_selection_menu_style, R.string.set_page_actions)),
    TRANSLATION(R.string.set_translation, R.string.set_translation_sum, Icons.Outlined.Translate, listOf(R.string.set_translation_mode, R.string.set_engine, R.string.set_target_lang, R.string.set_source_lang, R.string.set_deepl_key, R.string.set_libre_url, R.string.set_llm_url, R.string.set_llm_model, R.string.set_llm_prompt, R.string.set_language_packs, R.string.set_online_dictionary, R.string.set_dictionaries, R.string.set_daily_goal, R.string.set_reminder, R.string.set_review_modes)),
    CATALOG(R.string.set_catalog, R.string.set_catalog_sum, Icons.Outlined.Public, listOf(R.string.set_update_interval, R.string.set_update_wifi, R.string.set_show_nsfw, R.string.set_incognito, R.string.set_extensions, R.string.set_hidden_sources, R.string.set_revoke_trust)),
    TRACKING(R.string.set_tracking, R.string.set_tracking_sum, Icons.Outlined.TrackChanges, listOf(R.string.set_track_auto, R.string.set_track_push, R.string.set_track_keys)),
    DOWNLOADS(R.string.set_downloads, R.string.set_downloads_sum, Icons.Outlined.Download, listOf(R.string.set_download_wifi, R.string.set_parallel, R.string.set_page_threads, R.string.set_auto_download_new, R.string.set_download_ahead, R.string.set_download_retries, R.string.set_auto_delete, R.string.set_pack_cbz, R.string.set_storage, R.string.set_clear_cache)),
    NETWORK(R.string.set_network, R.string.set_network_sum, Icons.Outlined.DataUsage, listOf(R.string.set_download_wifi, R.string.set_net_update_wifi, R.string.set_models_wifi, R.string.set_data_saver, R.string.set_covers_mobile, R.string.set_warn_mobile, R.string.set_traffic, R.string.set_traffic_reset)),
    BACKUP(R.string.set_backup, R.string.set_backup_sum, Icons.Outlined.Backup, listOf(R.string.set_backup_create, R.string.set_backup_restore, R.string.set_mihon_import, R.string.set_auto_backup, R.string.set_backup_folder, R.string.set_backup_keep, R.string.set_reset_all)),
    SYNC(R.string.set_sync_section, R.string.set_sync_section_sum, Icons.Outlined.CloudSync, listOf(R.string.set_sync, R.string.set_sync_now, R.string.set_sync_method, R.string.set_sync_folder, R.string.set_webdav_url, R.string.set_github_token, R.string.set_github_repo, R.string.set_github_create, R.string.set_sync_check, R.string.set_sync_on_start, R.string.set_sync_on_exit, R.string.set_sync_interval, R.string.set_sync_wifi_only, R.string.set_sync_progress, R.string.set_sync_annotations, R.string.set_sync_bookmarks, R.string.set_sync_words, R.string.set_sync_track_links, R.string.set_sync_catalog, R.string.set_sync_book_files, R.string.set_sync_deletions, R.string.set_sync_problems)),
    NOTIFICATIONS(R.string.set_notifications, R.string.set_notifications_sum, Icons.Outlined.Notifications, listOf(R.string.set_notify_permission, R.string.set_notify_system, R.string.set_notify_new_chapters, R.string.set_notify_downloads, R.string.set_notify_sync, R.string.set_notify_backup, R.string.set_notify_tasks, R.string.set_notify_reminders, R.string.set_notify_app_update, R.string.set_notify_quiet_on, R.string.set_notify_recent, R.string.set_notify_test)),
    SYNC_PROBLEMS(R.string.set_sync_problems, R.string.set_sync_problems_sum, Icons.Outlined.ErrorOutline, emptyList(), visible = false),
    SECURITY(R.string.set_security, R.string.set_security_sum, Icons.Outlined.Lock, listOf(R.string.set_app_lock, R.string.set_change_pin, R.string.set_biometric, R.string.set_lock_timeout, R.string.set_secure_screen)),
    FONTS(R.string.set_fonts, R.string.set_fonts_sum, Icons.Outlined.FontDownload, listOf(R.string.set_font_import)),
    ADVANCED(R.string.set_advanced, R.string.set_advanced_sum, Icons.Outlined.Tune, listOf(R.string.set_verbose_logging, R.string.set_error_log, R.string.set_reset_onboarding, R.string.set_reset_hints)),
    LOGS(R.string.set_error_log, R.string.set_error_log_sum, Icons.Outlined.BugReport, emptyList(), visible = false),
    STORAGE(R.string.set_storage, R.string.set_storage_sum, Icons.Outlined.Storage, emptyList(), visible = false),
    ABOUT(R.string.set_about, R.string.set_about_sum, Icons.Outlined.Info, listOf(R.string.set_version, R.string.set_check_updates, R.string.set_update_auto, R.string.set_about_author, R.string.set_about_source, R.string.set_about_releases, R.string.set_about_issues, R.string.set_license, R.string.set_privacy, R.string.set_libraries)),
}

/** Links from settings to screens owned by other features. */
data class SettingsLinks(
    val onOpenDictionaries: () -> Unit = {},
    val onOpenLanguagePacks: () -> Unit = {},
    val onOpenExtensions: () -> Unit = {},
    val onOpenDownloads: () -> Unit = {},
    val onOpenStats: () -> Unit = {},
)

@Composable
internal fun <T> Pref<*, T>.state(): State<T> = flow.collectAsStateWithLifecycle(initialValue = default)

/**
 * Settings root with search; sections open inside the same destination. With [onSelectSection] the root only lists
 * (a two-pane list): sections and search results go to the caller with the option's anchor, which another instance
 * opens via [initialSection] and [initialAnchor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    links: SettingsLinks,
    onBack: (() -> Unit)? = null,
    initialSection: SettingsSection? = null,
    initialAnchor: String? = null,
    onSelectSection: ((SettingsSection, String?) -> Unit)? = null,
) {
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { SettingsFocus() }
    LaunchedEffect(initialAnchor) { if (initialAnchor != null) focus.request(initialAnchor) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var askRestart by remember { mutableStateOf(false) }
    val updateAvailable by viewModel.updates.available.collectAsStateWithLifecycle(null)
    val updateDialog by viewModel.updates.dialog.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is SettingsEvent.Message -> launch { snackbar.showSnackbar(resources.getString(e.res, *e.args.toTypedArray())) }
                is SettingsEvent.Failure -> launch { snackbar.showSnackbar(errorMessage(context, e.error)) }
                SettingsEvent.Restored -> askRestart = true
            }
        }
    }
    BackHandler(enabled = section == null && query.isNotEmpty()) { query = "" }
    BackHandler(enabled = section != null && section != initialSection) {
        section = if (section == SettingsSection.LOGS) SettingsSection.ADVANCED
        else if (section == SettingsSection.STORAGE) SettingsSection.DOWNLOADS
        else if (section == SettingsSection.SYNC_PROBLEMS) SettingsSection.SYNC
        else null
    }

    val current = section
    Scaffold(
        contentWindowInsets = topLevelContentInsets(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(current?.title ?: CoreR.string.settings)) },
                    navigationIcon = {
                        val back: (() -> Unit)? = when {
                            current != null && current != initialSection -> ({
                                section = when (current) {
                                    SettingsSection.LOGS -> SettingsSection.ADVANCED
                                    SettingsSection.STORAGE -> SettingsSection.DOWNLOADS
                                    SettingsSection.SYNC_PROBLEMS -> SettingsSection.SYNC
                                    else -> null
                                }
                            })
                            else -> onBack
                        }
                        if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) }
                    },
                )
                if (busy) LinearProgressIndicator(Modifier.padding(horizontal = Dimens.l))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        TopLevelBody(padding) { CompositionLocalProvider(LocalSettingsFocus provides focus) { AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "settings") { s ->
            if (s == null) {
                SettingsRoot(
                    query = query,
                    onQuery = { query = it },
                    update = updateAvailable,
                    onUpdate = {
                        if (onSelectSection != null) onSelectSection(SettingsSection.ABOUT, null) else section = SettingsSection.ABOUT
                        viewModel.updates.openDetails()
                    },
                    onOpen = { if (onSelectSection != null) onSelectSection(it, null) else section = it },
                    onOpenResult = { e ->
                        if (onSelectSection != null) onSelectSection(e.section, e.anchor)
                        else { focus.request(e.anchor); section = e.section }
                    },
                    incognito = viewModel.settings.incognito.state().value,
                    onIncognito = { on -> viewModel.set(viewModel.settings.incognito, on) },
                )
            } else {
                SectionContent(s, viewModel, links, onOpen = { section = it })
            }
        } } }
    }

    updateDialog?.let { release -> UpdateDialog(release, viewModel.updates) }

    if (askRestart) {
        AlertDialog(
            onDismissRequest = { askRestart = false },
            title = { Text(stringResource(R.string.set_restore_restart_title)) },
            text = { Text(stringResource(R.string.set_restore_restart_text)) },
            confirmButton = { TextButton(onClick = { askRestart = false; restartApp(context) }) { Text(stringResource(R.string.set_restart)) } },
            dismissButton = { TextButton(onClick = { askRestart = false }) { Text(stringResource(R.string.set_later)) } },
        )
    }
}

@Composable
private fun SettingsRoot(
    query: String,
    onQuery: (String) -> Unit,
    update: String?,
    onUpdate: () -> Unit,
    onOpen: (SettingsSection) -> Unit,
    onOpenResult: (SettingsSearchEntry) -> Unit,
    incognito: Boolean,
    onIncognito: (Boolean) -> Unit,
) {
    val index = rememberSettingsIndex()
    val q = query.trim()
    val results = remember(q, index) { filterSettings(index, q) }
    val root = stringResource(CoreR.string.settings)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
        item(key = "search") {
            SearchField(
                query = query,
                onQueryChange = onQuery,
                placeholder = stringResource(R.string.set_search),
                modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
            )
        }
        if (q.isNotEmpty()) {
            if (results.isEmpty()) item(key = "none") { EmptyState(stringResource(R.string.set_search_none), Modifier.fillParentMaxHeight(0.6f), icon = Icons.Outlined.SearchOff) }
            if (results.isNotEmpty()) item(key = "results") {
                SettingsGroup {
                    results.forEach { e -> SettingsRow(e.section.icon, e.title, if (e.anchor == null) root else "$root › ${e.sectionTitle}") { onOpenResult(e) } }
                }
            }
        } else {
            if (update != null) item(key = "update") { UpdateCard(update, onUpdate) }
            item(key = "quick") {
                SettingsGroup {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = incognito, role = Role.Switch, onValueChange = onIncognito).padding(horizontal = Dimens.l, vertical = Dimens.s),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.l),
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.tertiary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(CoreR.string.incognito_mode), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(CoreR.string.incognito_mode_sum), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Switch(checked = incognito, onCheckedChange = null)
                    }
                }
            }
            val visible = SettingsSection.entries.filter { it.visible }
            val groups = ROOT_GROUPS.map { g -> g.filter { it in visible } } + listOf(visible.filter { s -> ROOT_GROUPS.none { s in it } })
            groups.filter { it.isNotEmpty() }.forEachIndexed { i, group ->
                item(key = "group$i") {
                    SettingsGroup {
                        group.forEach { s -> SettingsRow(s.icon, stringResource(s.title), stringResource(s.summary)) { onOpen(s) } }
                    }
                }
            }
        }
    }
}

/** Sections of the settings root, one rounded card per group. */
private val ROOT_GROUPS = listOf(
    listOf(SettingsSection.APPEARANCE, SettingsSection.NAVIGATION, SettingsSection.LIBRARY, SettingsSection.READER, SettingsSection.MANGA, SettingsSection.SELECTION, SettingsSection.QUICK_ACTIONS, SettingsSection.FONTS),
    listOf(SettingsSection.TRANSLATION, SettingsSection.CATALOG, SettingsSection.TRACKING, SettingsSection.DOWNLOADS, SettingsSection.NETWORK),
    listOf(SettingsSection.BACKUP, SettingsSection.SYNC, SettingsSection.NOTIFICATIONS, SettingsSection.SECURITY, SettingsSection.ADVANCED, SettingsSection.ABOUT),
)

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs + Dimens.xxs),
    ) {
        Column(Modifier.padding(vertical = Dimens.xs), content = content)
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Dimens.l, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.l),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionContent(s: SettingsSection, vm: SettingsViewModel, links: SettingsLinks, onOpen: (SettingsSection) -> Unit) {
    when (s) {
        SettingsSection.LOGS -> ErrorLogSection(vm)
        SettingsSection.FONTS -> FontsSection(vm)
        SettingsSection.SELECTION -> SelectionSection(vm)
        SettingsSection.NAVIGATION -> NavigationSection(vm)
        SettingsSection.QUICK_ACTIONS -> QuickActionsSection(vm, onOpenSelection = { onOpen(SettingsSection.SELECTION) })
        SettingsSection.SYNC_PROBLEMS -> SyncProblemsSection(vm)
        else -> ScrollSection {
            when (s) {
                SettingsSection.APPEARANCE -> AppearanceSection(vm)
                SettingsSection.LIBRARY -> LibrarySection(vm)
                SettingsSection.READER -> ReaderSection(vm, links)
                SettingsSection.MANGA -> MangaSection(vm)
                SettingsSection.TRANSLATION -> TranslationSection(vm, links)
                SettingsSection.CATALOG -> CatalogSection(vm, links)
                SettingsSection.DOWNLOADS -> DownloadsSection(vm, links, onOpenStorage = { onOpen(SettingsSection.STORAGE) })
                SettingsSection.STORAGE -> StorageSection(vm)
                SettingsSection.NETWORK -> NetworkSection(vm)
                SettingsSection.BACKUP -> BackupSection(vm, onOpenSync = { onOpen(SettingsSection.SYNC) })
                SettingsSection.SYNC -> SyncSection(vm, onOpenProblems = { onOpen(SettingsSection.SYNC_PROBLEMS) })
                SettingsSection.NOTIFICATIONS -> NotificationsSection(vm)
                SettingsSection.TRACKING -> TrackingSection(vm)
                SettingsSection.SECURITY -> SecuritySection(vm)
                SettingsSection.ADVANCED -> AdvancedSection(vm, onOpenLogs = { onOpen(SettingsSection.LOGS) })
                SettingsSection.ABOUT -> AboutSection(vm)
            }
        }
    }
}

@Composable
internal fun ScrollSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = navBarBottomPadding(Dimens.xl)), content = content)
}

/** Restarts the app process (used after restoring a backup). */
fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    if (intent != null) context.startActivity(intent)
    (context as? Activity)?.finishAffinity()
    Runtime.getRuntime().exit(0)
}
