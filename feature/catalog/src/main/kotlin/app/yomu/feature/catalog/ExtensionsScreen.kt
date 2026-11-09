package app.yomu.feature.catalog

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.alpha
import app.yomu.data.source.SourceInfo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.SearchField
import app.yomu.core.ui.components.YomuSheet
import app.yomu.core.ui.components.YomuTab
import app.yomu.core.ui.components.copyText
import app.yomu.core.ui.components.YomuFab
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.source.AvailableExtension
import app.yomu.data.source.BrokenReason
import app.yomu.data.source.ContentWarning
import app.yomu.data.source.ExtensionStatus
import app.yomu.data.source.ExtensionUpdate
import app.yomu.data.source.InstalledExtension
import app.yomu.data.source.StoreState
import app.yomu.data.source.tachiyomi.TachiyomiSource
import coil3.compose.AsyncImage
import app.yomu.core.ui.R as CoreR

/** Mihon-style extension manager: installed (updates, trust, errors), available by language, and repositories. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    viewModel: ExtensionsViewModel,
    onBack: () -> Unit,
    onOpenSourceSettings: (Long) -> Unit = {},
    addStoreUrl: String? = null,
) {
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val available by viewModel.available.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    val installing by viewModel.installing.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val nsfw by viewModel.showNsfw.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val languages by viewModel.languages.collectAsStateWithLifecycle()
    val allLanguages by viewModel.allLanguages.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val hiddenExtensions by viewModel.hiddenExtensions.collectAsStateWithLifecycle()
    val sourceInfos by viewModel.sourceInfos.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(if (addStoreUrl != null) 2 else 0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var trustFor by remember { mutableStateOf<InstalledExtension?>(null) }
    var trustAll by remember { mutableStateOf<List<InstalledExtension>?>(null) }
    var uninstallFor by remember { mutableStateOf<InstalledExtension?>(null) }
    var detailsFor by remember { mutableStateOf<String?>(null) }
    var addStore by rememberSaveable { mutableStateOf(addStoreUrl != null) }
    var addStoreInitial by rememberSaveable { mutableStateOf(addStoreUrl.orEmpty()) }
    var removeStore by remember { mutableStateOf<StoreState?>(null) }
    var pickLanguages by remember { mutableStateOf(false) }
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::installFromFile) }
    CatalogEventsEffect(viewModel.events, snackbar)

    LaunchedEffect(addStoreUrl) { if (addStoreUrl != null) viewModel.previewStore(addStoreUrl) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (searching) {
                            SearchField(query, { viewModel.query.value = it }, stringResource(R.string.ext_search_hint))
                        } else {
                            Text(stringResource(R.string.ext_title))
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) } },
                    actions = {
                        if (searching) {
                            IconButton(onClick = { searching = false; viewModel.query.value = "" }) { Icon(Icons.Filled.Close, stringResource(CoreR.string.close)) }
                        } else {
                            IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, stringResource(R.string.ext_search_hint)) }
                            IconButton(onClick = { viewModel.refresh() }, enabled = !refreshing) { Icon(Icons.Filled.Refresh, stringResource(R.string.ext_refresh)) }
                        }
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, stringResource(CoreR.string.more)) }
                        DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ext_languages)) },
                                leadingIcon = { Icon(Icons.Filled.Translate, null) },
                                onClick = { pickLanguages = true; showMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ext_install_file)) },
                                leadingIcon = { Icon(Icons.Outlined.FileOpen, null) },
                                onClick = {
                                    showMenu = false
                                    pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ext_show_nsfw)) },
                                leadingIcon = { Checkbox(checked = nsfw, onCheckedChange = null) },
                                onClick = { viewModel.setShowNsfw(!nsfw); showMenu = false },
                            )
                        }
                    },
                )
                if (refreshing || installing.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
                PrimaryTabRow(selectedTabIndex = tab) {
                    YomuTab(tab == 0, onClick = { tab = 0 }, text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ext_installed_tab))
                            if (updates.isNotEmpty()) {
                                Badge(
                                    Modifier.padding(start = Dimens.xs),
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ) { Text(updates.size.toString()) }
                            }
                        }
                    })
                    YomuTab(tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.ext_available_tab)) })
                    YomuTab(tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.ext_repos_tab)) })
                }
            }
        },
        floatingActionButton = {
            if (tab == 2) {
                YomuFab(
                    text = stringResource(R.string.ext_add_repo),
                    icon = Icons.Filled.Add,
                    onClick = { addStore = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when (tab) {
            0 -> LazyColumn(modifier, contentPadding = PaddingValues(bottom = Dimens.xl)) {
                installedTab(
                    installed = installed, updates = updates, installing = installing, query = query,
                    onUpdate = viewModel::update, onUpdateAll = viewModel::updateAll,
                    onTrust = { trustFor = it }, onTrustAll = { trustAll = it },
                    onUninstall = { uninstallFor = it }, onOpen = { detailsFor = it.pkg },
                    onBrowse = { tab = 1 },
                    hidden = hiddenExtensions,
                    onHide = { ext, hide -> viewModel.setExtensionHidden(ext.pkg, hide) },
                )
            }
            1 -> LazyColumn(modifier, contentPadding = PaddingValues(bottom = Dimens.xl)) {
                item {
                    LanguageChips(languages, onClick = { pickLanguages = true })
                }
                if (stores.isEmpty()) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.ext_no_repos),
                            message = stringResource(R.string.ext_no_repos_hint),
                            icon = Icons.Outlined.Storage,
                            actionLabel = stringResource(R.string.ext_add_repo),
                            onAction = { tab = 2 },
                        )
                    }
                } else if (available.isEmpty() && !refreshing) {
                    item {
                        EmptyState(
                            title = stringResource(if (query.isBlank()) R.string.ext_nothing_available else R.string.ext_no_results),
                            icon = Icons.Outlined.Language,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                available.forEach { group ->
                    item(key = "h-" + group.lang) { SectionHeader(langLabel(group.lang) + " · " + group.items.size) }
                    items(group.items, key = { "a-" + it.storeUrl + it.pkg }) { a ->
                        AvailableRow(a, installing.containsKey(a.pkg), installing[a.pkg], multiStore = stores.size > 1) { viewModel.install(a) }
                    }
                }
            }
            else -> LazyColumn(modifier, contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth().padding(Dimens.l),
                    ) {
                        Row(Modifier.padding(Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                            Icon(Icons.Outlined.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.ext_repo_warning), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (stores.isEmpty()) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.ext_no_repos),
                            message = stringResource(R.string.ext_no_repos_hint),
                            icon = Icons.Outlined.Storage,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                items(stores, key = { it.url }) { s -> StoreRow(s, onRemove = { removeStore = s }) }
                val suggested = RECOMMENDED_STORES.filter { rec -> stores.none { rec.matches(it.url) } }
                if (suggested.isNotEmpty()) {
                    item(key = "rec-h") { SectionHeader(stringResource(R.string.ext_rec_title)) }
                    items(suggested, key = { "rec-" + it.url }) { rec ->
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.Storage, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(rec.name) },
                            supportingContent = { Text(stringResource(rec.description), style = MaterialTheme.typography.bodySmall) },
                            trailingContent = {
                                FilledTonalButton(onClick = {
                                    addStoreInitial = rec.url
                                    addStore = true
                                    viewModel.previewStore(rec.url)
                                }) { Text(stringResource(CoreR.string.add)) }
                            },
                        )
                    }
                    item(key = "rec-note") {
                        Text(
                            stringResource(R.string.ext_rec_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
                        )
                    }
                }
            }
        }
    }

    detailsFor?.let { pkg ->
        val ext = installed.firstOrNull { it.pkg == pkg }
        if (ext == null) {
            detailsFor = null
        } else {
            ExtensionDetailsSheet(
                ext = ext,
                update = updates.firstOrNull { it.installed.pkg == pkg },
                onDismiss = { detailsFor = null },
                onOpenSettings = { id -> detailsFor = null; onOpenSourceSettings(id) },
                onUpdate = viewModel::update,
                onTrust = { trustFor = ext },
                onUninstall = { detailsFor = null; uninstallFor = ext },
                hidden = ext.pkg in hiddenExtensions,
                onHide = { viewModel.setExtensionHidden(ext.pkg, it) },
                infos = sourceInfos,
                onPinSource = viewModel::setSourcePinned,
                onEnableSource = viewModel::setSourceEnabled,
                onHideSource = viewModel::setSourceHidden,
            )
        }
    }
    trustFor?.let { u ->
        AlertDialog(
            onDismissRequest = { trustFor = null },
            icon = { PackageIcon(u.icon, u.name, size = 48.dp) },
            title = { Text(stringResource(R.string.ext_trust_title, u.name)) },
            text = { Text(stringResource(R.string.ext_trust_text, u.pkg, u.signatureHash.take(16))) },
            confirmButton = { TextButton(onClick = { viewModel.trust(u); trustFor = null }) { Text(stringResource(R.string.ext_trust)) } },
            dismissButton = { TextButton(onClick = { trustFor = null }) { Text(stringResource(CoreR.string.cancel)) } },
        )
    }
    trustAll?.let { list ->
        val names = list.take(8).joinToString("\n") { "• " + it.name } +
            if (list.size > 8) "\n" + stringResource(R.string.ext_trust_all_more, (list.size - 8).toString()) else ""
        AlertDialog(
            onDismissRequest = { trustAll = null },
            icon = { Icon(Icons.Outlined.GppMaybe, null, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text(pluralStringResource(R.plurals.ext_trust_all_title, list.size, list.size)) },
            text = { Text(stringResource(R.string.ext_trust_all_text, names)) },
            confirmButton = { TextButton(onClick = { viewModel.trustAll(list); trustAll = null }) { Text(stringResource(R.string.ext_trust_all)) } },
            dismissButton = { TextButton(onClick = { trustAll = null }) { Text(stringResource(CoreR.string.cancel)) } },
        )
    }
    uninstallFor?.let { ext ->
        ConfirmDialog(
            title = stringResource(R.string.ext_uninstall),
            text = ext.name,
            confirmLabel = stringResource(R.string.ext_uninstall),
            onConfirm = { viewModel.uninstall(ext) },
            onDismiss = { uninstallFor = null },
        )
    }
    if (addStore) {
        AddStoreDialog(
            initial = addStoreInitial,
            preview = preview,
            onCheck = viewModel::previewStore,
            onConfirm = { viewModel.confirmStore(); addStore = false },
            onDismiss = { viewModel.dismissPreview(); addStore = false },
        )
    }
    removeStore?.let { s ->
        ConfirmDialog(
            title = stringResource(R.string.ext_remove_repo),
            text = s.name + "\n\n" + stringResource(R.string.ext_remove_repo_text),
            confirmLabel = stringResource(CoreR.string.delete),
            onConfirm = { viewModel.removeStore(s.url) },
            onDismiss = { removeStore = null },
        )
    }
    if (pickLanguages) {
        LanguagesDialog(
            all = (allLanguages + languages).distinct().sortedWith(ExtensionsViewModel.languageOrder()),
            selected = languages,
            onToggle = viewModel::toggleLanguage,
            onReset = viewModel::resetLanguages,
            onDismiss = { pickLanguages = false },
        )
    }
}

private fun LazyListScope.installedTab(
    installed: List<InstalledExtension>,
    updates: List<ExtensionUpdate>,
    installing: Map<String, Float?>,
    query: String,
    onUpdate: (ExtensionUpdate) -> Unit,
    onUpdateAll: () -> Unit,
    onTrust: (InstalledExtension) -> Unit,
    onTrustAll: (List<InstalledExtension>) -> Unit,
    onUninstall: (InstalledExtension) -> Unit,
    onOpen: (InstalledExtension) -> Unit,
    onBrowse: () -> Unit,
    hidden: Set<String> = emptySet(),
    onHide: (InstalledExtension, Boolean) -> Unit = { _, _ -> },
) {
    val updatePkgs = updates.map { it.installed.pkg }.toSet()
    if (installed.isEmpty()) {
        item {
            EmptyState(
                title = stringResource(if (query.isBlank()) R.string.ext_none_installed else R.string.ext_no_results),
                message = if (query.isBlank()) stringResource(R.string.ext_none_installed_hint) else null,
                icon = Icons.Filled.Extension,
                actionLabel = if (query.isBlank()) stringResource(R.string.ext_available_tab) else null,
                onAction = onBrowse,
            )
        }
        return
    }
    if (updates.isNotEmpty()) {
        item(key = "updates-h") {
            Row(Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.s, top = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ext_updates_header) + " · " + updates.size,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (updates.size > 1) TextButton(onClick = onUpdateAll) { Text(stringResource(R.string.ext_update_all)) }
            }
        }
        items(updates, key = { "u-" + it.installed.pkg }) { u ->
            InstalledRow(u.installed, onClick = { onOpen(u.installed) }) {
                if (installing.containsKey(u.installed.pkg)) InstallProgress(installing[u.installed.pkg])
                else FilledTonalButton(onClick = { onUpdate(u) }) { Text(stringResource(R.string.ext_update)) }
            }
        }
        item(key = "updates-d") { HorizontalDivider(Modifier.padding(vertical = Dimens.s)) }
    }
    val untrusted = installed.filter { it.status == ExtensionStatus.UNTRUSTED }
    if (untrusted.isNotEmpty()) {
        item(key = "untrusted-h") { UntrustedHeader(untrusted.size, onTrustAll = { onTrustAll(untrusted) }) }
        items(untrusted, key = { "t-" + it.pkg }) { u ->
            InstalledRow(u, onClick = { onOpen(u) }) {
                FilledTonalButton(onClick = { onTrust(u) }, contentPadding = PaddingValues(horizontal = Dimens.m)) {
                    Text(stringResource(R.string.ext_trust))
                }
                IconButton(onClick = { onUninstall(u) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.ext_uninstall)) }
            }
        }
        item(key = "untrusted-d") { HorizontalDivider(Modifier.padding(vertical = Dimens.s)) }
    }
    val rest = installed.filter { it.status != ExtensionStatus.UNTRUSTED && it.pkg !in updatePkgs }
    items(rest, key = { "i-" + it.pkg }) { ext ->
        InstalledRow(ext, onClick = { onOpen(ext) }, hidden = ext.pkg in hidden) {
            val isHidden = ext.pkg in hidden
            IconButton(onClick = { onHide(ext, !isHidden) }) {
                Icon(
                    if (isHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    stringResource(if (isHidden) R.string.ext_unhide else R.string.ext_hide),
                    tint = if (isHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ext.sources.any { (it as? TachiyomiSource)?.hasPreferences == true }) {
                IconButton(onClick = { onOpen(ext) }) { Icon(Icons.Filled.Settings, stringResource(R.string.ext_source_settings)) }
            }
            IconButton(onClick = { onUninstall(ext) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.ext_uninstall)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.l, top = Dimens.l, bottom = Dimens.xs),
    )
}

@Composable
private fun LanguageChips(selected: Set<String>, onClick: () -> Unit) {
    val labels = selected.sortedWith(ExtensionsViewModel.languageOrder()).map { langLabel(it) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
    ) {
        Row(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Translate, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Dimens.m))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ext_languages), style = MaterialTheme.typography.labelLarge)
                Text(labels.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ExtIcon(ext: InstalledExtension, size: androidx.compose.ui.unit.Dp = 40.dp) = PackageIcon(ext.icon, ext.name, size = size)

@Composable
private fun WarningBadge(w: ContentWarning) {
    when (w) {
        ContentWarning.NSFW -> Badge(containerColor = MaterialTheme.colorScheme.error) { Text(stringResource(R.string.ext_nsfw)) }
        ContentWarning.MIXED -> Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
            Text(stringResource(R.string.ext_mixed))
        }
        ContentWarning.SAFE -> Unit
    }
}

@Composable
private fun installedSummary(ext: InstalledExtension): String {
    val langs = ext.sources.map { it.lang }.distinct()
    return listOfNotNull(
        ext.versionName.ifBlank { null },
        when {
            langs.size == 1 -> langLabel(langs.first())
            langs.size > 1 -> stringResource(R.string.ext_lang_multi) + " (" + langs.size + ")"
            else -> null
        },
        when {
            ext.isBundled -> stringResource(R.string.ext_bundled)
            !ext.isPrivate -> stringResource(R.string.ext_system)
            else -> null
        },
        ext.storeName,
    ).joinToString(" · ")
}

@Composable
private fun InstalledRow(ext: InstalledExtension, onClick: () -> Unit, hidden: Boolean = false, trailing: @Composable () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).alpha(if (hidden) 0.6f else 1f),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Text(ext.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                WarningBadge(ext.contentWarning)
            }
        },
        supportingContent = {
            when (ext.status) {
                ExtensionStatus.BROKEN -> Text(brokenText(ext, short = true), color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ExtensionStatus.UNTRUSTED -> Text(
                    listOfNotNull(ext.versionName.ifBlank { null }, stringResource(R.string.ext_untrusted_short, ext.signatureHash.take(8).uppercase()))
                        .joinToString(" · "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ExtensionStatus.LOADED -> Text(
                    listOfNotNull(installedSummary(ext), if (hidden) stringResource(R.string.ext_hidden) else null).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = { ExtIcon(ext) },
        trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { trailing() } },
    )
}

/** Why an extension cannot be loaded; [short] is the one-line version for lists (details are in the sheet). */
@Composable
private fun brokenText(ext: InstalledExtension, short: Boolean = false): String = when (ext.brokenReason) {
    BrokenReason.UNSUPPORTED_LIB -> stringResource(R.string.ext_broken_lib, ext.libVersion?.toString() ?: ext.error ?: "?")
    BrokenReason.NEWER_API -> stringResource(R.string.ext_broken_api)
    BrokenReason.UNSIGNED -> stringResource(R.string.ext_broken_unsigned)
    BrokenReason.MALFORMED -> stringResource(R.string.ext_broken_malformed)
    BrokenReason.FAILED -> stringResource(if (short) R.string.ext_broken_failed_short else R.string.ext_broken_failed_explain)
    null -> stringResource(R.string.ext_broken_hint)
}

@Composable
private fun InstallProgress(progress: Float?) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (progress != null && progress > 0f) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        else CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** Calm (not alarming) explanation above the extensions that wait for the user's trust. */
@Composable
private fun UntrustedHeader(count: Int, onTrustAll: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
    ) {
        Column(Modifier.padding(start = Dimens.l, end = Dimens.l, top = Dimens.l, bottom = if (count > 1) Dimens.s else Dimens.l)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Icon(Icons.Outlined.GppMaybe, null)
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    Text(pluralStringResource(R.plurals.ext_untrusted_title, count, count), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.ext_untrusted_section_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (count > 1) {
                TextButton(onClick = onTrustAll, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.ext_trust_all)) }
            }
        }
    }
}

@Composable
private fun AvailableRow(a: AvailableExtension, busy: Boolean, progress: Float?, multiStore: Boolean, onInstall: () -> Unit) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                WarningBadge(a.contentWarning)
            }
        },
        supportingContent = {
            val langs = a.languages
            Text(
                listOfNotNull(
                    a.versionName.ifBlank { null },
                    if (langs.size > 1) stringResource(R.string.ext_lang_multi) + " (" + langs.size + ")" else null,
                    if (multiStore) a.storeName else null,
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                if (a.iconUrl != null) AsyncImage(a.iconUrl, null, Modifier.fillMaxSize())
                else Icon(Icons.Filled.Extension, null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            when {
                busy -> InstallProgress(progress)
                !a.isSupported -> Text(stringResource(R.string.ext_unsupported), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                else -> FilledTonalButton(onClick = onInstall) { Text(stringResource(R.string.ext_install)) }
            }
        },
    )
}

@Composable
private fun StoreRow(s: StoreState, onRemove: () -> Unit) {
    val uri = LocalUriHandler.current
    ListItem(
        headlineContent = { Text(s.name.ifBlank { s.url }) },
        supportingContent = {
            Column {
                Text(s.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val info = listOfNotNull(
                    s.extensionCount?.let { stringResource(R.string.ext_repo_count, it.toString()) },
                    s.signingKey?.let { stringResource(R.string.ext_repo_key, it.take(12)) },
                ).joinToString(" · ")
                if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.bodySmall)
                s.error?.let { err ->
                    Text(
                        if (err == "KEY_CHANGED") stringResource(R.string.ext_store_key_changed) else stringResource(R.string.ext_repo_error, err),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                s.website?.takeIf { it.startsWith("http") }?.let { site ->
                    TextButton(onClick = { runCatching { uri.openUri(site) } }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.ext_repo_website))
                    }
                }
            }
        },
        leadingContent = {
            Icon(if (s.error != null) Icons.Outlined.ErrorOutline else Icons.Outlined.Storage, null,
                tint = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        },
        trailingContent = { IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, stringResource(CoreR.string.delete)) } },
    )
}

@Composable
private fun AddStoreDialog(
    initial: String,
    preview: StorePreview?,
    onCheck: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // No prefilled "https://": people paste full links, and a prefix only produced "https://https://…".
    var text by rememberSaveable { mutableStateOf(initial) }
    val ready = (preview as? StorePreview.Ready)?.takeIf { it.input == text.trim() }
    val canCheck = text.trim().length >= 4 && preview !is StorePreview.Loading
    val submit = {
        when {
            ready != null -> onConfirm()
            canCheck -> onCheck(text)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Storage, null) },
        title = { Text(stringResource(R.string.ext_add_repo)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.ext_repo_url)) },
                    placeholder = { Text(stringResource(R.string.ext_repo_url_hint)) },
                    supportingText = { Text(stringResource(R.string.ext_repo_url_help)) },
                    singleLine = true,
                    isError = preview is StorePreview.Failed && preview.input == text.trim(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    trailingIcon = {
                        if (text.isEmpty()) {
                            IconButton(onClick = {
                                clipboardText(context)?.trim()?.takeIf { it.isNotEmpty() }?.let { pasted ->
                                    text = pasted
                                    onCheck(pasted)
                                }
                            }) {
                                Icon(Icons.Filled.ContentPaste, stringResource(R.string.ext_repo_paste))
                            }
                        } else {
                            IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Close, stringResource(CoreR.string.clear)) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    preview is StorePreview.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.ext_repo_checking), style = MaterialTheme.typography.bodyMedium)
                    }
                    ready != null -> Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(Dimens.m), horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                            Icon(Icons.Outlined.VerifiedUser, null)
                            Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                                Text(stringResource(R.string.ext_repo_found, ready.info.name, ready.count.toString()), style = MaterialTheme.typography.titleSmall)
                                if (ready.nsfwCount > 0) Text(stringResource(R.string.ext_repo_found_nsfw, ready.nsfwCount.toString()), style = MaterialTheme.typography.bodySmall)
                                val key = ready.info.signingKey
                                if (key != null) {
                                    Text(stringResource(R.string.ext_repo_key, key.take(16)), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                } else {
                                    Text(stringResource(R.string.ext_repo_no_key), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    preview is StorePreview.Failed && preview.input == text.trim() -> Text(
                        stringResource(preview.message, preview.detail.orEmpty()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Text(stringResource(R.string.ext_repo_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (ready != null) {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.ext_add_repo)) }
            } else {
                TextButton(onClick = { onCheck(text) }, enabled = canCheck) { Text(stringResource(R.string.ext_repo_check)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

private fun clipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

@Composable
private fun LanguagesDialog(all: List<String>, selected: Set<String>, onToggle: (String) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_languages)) },
        text = {
            LazyColumn {
                items(all, key = { it }) { lang ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(lang) }.padding(vertical = Dimens.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = lang in selected, onCheckedChange = { onToggle(lang) })
                        Text(langLabel(lang))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.done)) } },
        dismissButton = { TextButton(onClick = onReset) { Text(stringResource(R.string.ext_languages_reset)) } },
    )
}

@Composable
private fun ExtensionDetailsSheet(
    ext: InstalledExtension,
    update: ExtensionUpdate?,
    onDismiss: () -> Unit,
    onOpenSettings: (Long) -> Unit,
    onUpdate: (ExtensionUpdate) -> Unit,
    onTrust: () -> Unit,
    onUninstall: () -> Unit,
    hidden: Boolean,
    onHide: (Boolean) -> Unit,
    infos: Map<Long, SourceInfo>,
    onPinSource: (Long, Boolean) -> Unit,
    onEnableSource: (Long, Boolean) -> Unit,
    onHideSource: (Long, Boolean) -> Unit,
) {
    YomuSheet(onDismiss) {
        Row(Modifier.padding(horizontal = Dimens.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
            ExtIcon(ext)
            Column(Modifier.weight(1f)) {
                Text(ext.name, style = MaterialTheme.typography.titleLarge)
                Text(installedSummary(ext), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WarningBadge(ext.contentWarning)
        }
        Spacer(Modifier.height(Dimens.m))
        when (ext.status) {
            ExtensionStatus.BROKEN -> BrokenDetails(ext)
            ExtensionStatus.UNTRUSTED -> Text(
                stringResource(R.string.ext_untrusted_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.l).padding(bottom = Dimens.m),
            )
            ExtensionStatus.LOADED -> Unit
        }
        Row(Modifier.padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            if (update != null) FilledTonalButton(onClick = { onUpdate(update) }) { Text(stringResource(R.string.ext_update) + " → " + update.available.versionName) }
            if (ext.status == ExtensionStatus.UNTRUSTED) FilledTonalButton(onClick = onTrust) { Text(stringResource(R.string.ext_trust)) }
            OutlinedButton(onClick = onUninstall) { Text(stringResource(R.string.ext_uninstall)) }
        }
        ListItem(
            modifier = Modifier.clickable { onHide(!hidden) },
            leadingContent = { Icon(Icons.Outlined.VisibilityOff, null) },
            headlineContent = { Text(stringResource(R.string.ext_hide_switch)) },
            supportingContent = { Text(stringResource(R.string.ext_hide_switch_sum)) },
            trailingContent = { Switch(checked = hidden, onCheckedChange = onHide) },
        )
        if (ext.sources.isNotEmpty()) {
            Spacer(Modifier.height(Dimens.s))
            SectionHeader(stringResource(R.string.ext_sources) + " · " + ext.sources.size)
            ext.sources.sortedWith(compareBy(ExtensionsViewModel.languageOrder()) { it.lang }).forEach { src ->
                val configurable = (src as? TachiyomiSource)?.hasPreferences == true
                val info = infos[src.id]
                val pinned = info?.pinned == true
                val enabled = info?.enabled != false
                val sourceHidden = info?.hidden == true
                ListItem(
                    modifier = (if (configurable) Modifier.clickable { onOpenSettings(src.id) } else Modifier).alpha(if (enabled && !sourceHidden) 1f else 0.6f),
                    headlineContent = { Text(src.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                langLabel(src.lang),
                                if (!enabled) stringResource(R.string.source_disabled) else null,
                                if (sourceHidden) stringResource(R.string.source_hidden) else null,
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onPinSource(src.id, !pinned) }) {
                                Icon(
                                    if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                    stringResource(if (pinned) R.string.source_unpin else R.string.source_pin),
                                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onEnableSource(src.id, !enabled) }) {
                                Icon(
                                    if (enabled) Icons.Outlined.CheckCircle else Icons.Outlined.Block,
                                    stringResource(if (enabled) R.string.source_disable else R.string.source_enable),
                                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                )
                            }
                            // Hiding a source alone means nothing while the whole extension is hidden.
                            IconButton(onClick = { onHideSource(src.id, !sourceHidden) }, enabled = !hidden) {
                                Icon(
                                    if (sourceHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    stringResource(if (sourceHidden) R.string.source_unhide else R.string.source_hide),
                                )
                            }
                            if (configurable) Icon(Icons.Filled.Settings, stringResource(R.string.ext_source_settings))
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(Dimens.s))
        HorizontalDivider()
        DetailLine(stringResource(R.string.ext_details_version), "${ext.versionName} (${ext.versionCode})")
        ext.libVersion?.let { DetailLine(stringResource(R.string.ext_details_lib), it.toString()) }
        DetailLine(stringResource(R.string.ext_details_package), ext.pkg)
        ext.storeName?.let { DetailLine(stringResource(R.string.ext_details_store), it) }
        DetailLine(stringResource(R.string.ext_details_signature), ext.signatureHash.chunked(4).take(8).joinToString(" ") + "…")
        DetailLine(
            stringResource(R.string.ext_details_location),
            when {
                ext.isBundled -> stringResource(R.string.ext_bundled)
                ext.isPrivate -> stringResource(R.string.ext_private)
                else -> stringResource(R.string.ext_system)
            },
        )
        Spacer(Modifier.height(Dimens.xl))
    }
}

/** Explanation of a broken extension and, for start failures, the technical error to copy for its authors. */
@Composable
private fun BrokenDetails(ext: InstalledExtension) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = Dimens.l).padding(bottom = Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Text(brokenText(ext), style = MaterialTheme.typography.bodyMedium)
        }
        val error = ext.error?.takeIf { ext.brokenReason == BrokenReason.FAILED && it.isNotBlank() }
        if (error != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = Dimens.m, top = Dimens.s, end = Dimens.xs, bottom = Dimens.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.ext_error_details),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { copyText(context, "${ext.name} ${ext.versionName} (${ext.pkg})\n$error") }) {
                            Icon(Icons.Outlined.ContentCopy, stringResource(CoreR.string.copy))
                        }
                    }
                    Text(
                        error.take(1200),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = Dimens.s, bottom = Dimens.s),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
