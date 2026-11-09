package app.yomu.feature.settings

import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.ui.components.LauncherIconImage
import app.yomu.core.ui.components.LocalLauncherIcons
import app.yomu.core.ui.components.PreferenceHeader
import app.yomu.core.ui.components.errorMessage
import app.yomu.core.ui.theme.Dimens
import kotlin.math.roundToInt
import app.yomu.core.ui.R as CoreR

/** Where the project lives: the author, the repository with its releases and issues. */
object ProjectLinks {
    const val AUTHOR = "Shinoame"
    const val AUTHOR_URL = "https://github.com/adxptived"
    const val REPO_URL = "https://github.com/adxptived/yomu"
    const val RELEASES_URL = "$REPO_URL/releases"
    const val ISSUES_URL = "$REPO_URL/issues/new/choose"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/adxptived/yomu/releases/latest"
}

internal fun appVersion(context: Context): Pair<String, Long> = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    (info.versionName ?: "—") to code
}.getOrDefault("—" to 0L)

@Composable
internal fun ColumnScope.AboutSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val (version, code) = remember { appVersion(context) }
    var showLibraries by remember { mutableStateOf(false) }
    val open: (String) -> Unit = { url -> runCatching { uri.openUri(url) } }

    AboutHeader(version, code)

    PreferenceHeader(stringResource(R.string.set_about_project))
    UpdateItem(vm.updates, version)
    SwitchPref(vm, vm.settings.updateAutoCheck, R.string.set_update_auto, R.string.set_update_auto_sum)
    PreferenceItem(stringResource(R.string.set_about_author), "${ProjectLinks.AUTHOR} · github.com/adxptived", icon = Icons.Outlined.Person, onClick = { open(ProjectLinks.AUTHOR_URL) })
    PreferenceItem(stringResource(R.string.set_about_source), "github.com/adxptived/yomu", icon = Icons.Outlined.Source, onClick = { open(ProjectLinks.REPO_URL) })
    PreferenceItem(stringResource(R.string.set_about_releases), stringResource(R.string.set_about_releases_sum), icon = Icons.Outlined.NewReleases, onClick = { open(ProjectLinks.RELEASES_URL) })
    PreferenceItem(stringResource(R.string.set_about_issues), stringResource(R.string.set_about_issues_sum), icon = Icons.Outlined.BugReport, onClick = { open(ProjectLinks.ISSUES_URL) })

    PreferenceHeader(stringResource(R.string.set_about_legal))
    PreferenceItem(stringResource(R.string.set_license), "Apache License 2.0", icon = settingIcon(R.string.set_license))
    PreferenceItem(stringResource(R.string.set_privacy), stringResource(R.string.set_privacy_text), icon = settingIcon(R.string.set_privacy))
    PreferenceItem(stringResource(R.string.set_libraries), stringResource(R.string.set_libraries_sum), onClick = { showLibraries = true }, icon = settingIcon(R.string.set_libraries))
    Text(stringResource(R.string.set_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.l))
    if (showLibraries) {
        AlertDialog(
            onDismissRequest = { showLibraries = false },
            title = { Text(stringResource(R.string.set_libraries)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    LIBRARIES.forEach { (name, license) ->
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLibraries = false }) { Text(stringResource(CoreR.string.close)) } },
        )
    }
}

/** "Check for updates": every step from the check to the installer, in one row. */
@Composable
private fun UpdateItem(updates: AppUpdates, version: String) {
    val state by updates.state.collectAsStateWithLifecycle()
    val u = state
    PreferenceItem(
        stringResource(R.string.set_check_updates),
        when (u) {
            UpdateState.Idle -> stringResource(R.string.set_update_idle)
            UpdateState.Checking -> stringResource(R.string.set_update_checking)
            is UpdateState.UpToDate -> stringResource(R.string.set_update_latest, u.version)
            is UpdateState.Available -> stringResource(R.string.set_update_available, u.release.version)
            is UpdateState.Downloading -> u.progress?.let { stringResource(R.string.set_update_downloading_percent, u.release.version, (it * 100).roundToInt()) }
                ?: stringResource(R.string.set_update_downloading, u.release.version)
            is UpdateState.ReadyToInstall -> stringResource(R.string.set_update_ready, u.release.version)
            UpdateState.NoReleases -> stringResource(R.string.set_update_none)
            is UpdateState.Failed -> when {
                u.error is UpdateChecksumException -> stringResource(R.string.set_update_bad_checksum)
                u.release != null -> stringResource(R.string.set_update_download_failed, errorMessage(u.error))
                else -> stringResource(R.string.set_update_failed, errorMessage(u.error))
            }
        },
        icon = Icons.Outlined.SystemUpdate,
        onClick = {
            when (u) {
                UpdateState.Checking, is UpdateState.Downloading -> Unit
                is UpdateState.Available, is UpdateState.ReadyToInstall, is UpdateState.Failed -> updates.openDetails()
                else -> updates.check()
            }
        },
        trailing = when (u) {
            UpdateState.Checking -> ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) })
            is UpdateState.Downloading -> ({
                // The ring shows the progress; a tap on it cancels the download.
                IconButton(onClick = updates::cancelDownload) {
                    Box(contentAlignment = Alignment.Center) {
                        val p = u.progress
                        if (p != null) CircularProgressIndicator(progress = { p }, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        else CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        Icon(Icons.Filled.Close, stringResource(R.string.set_update_cancel), Modifier.size(14.dp))
                    }
                }
            })
            is UpdateState.ReadyToInstall -> ({
                FilledTonalButton(onClick = { updates.install(u.file) }) { Text(stringResource(R.string.set_update_install)) }
            })
            else -> null
        },
    )
}

/**
 * The release found by a check: its notes and "Download and install". Shown by [SettingsScreen] over any section, so the
 * card on the settings root can open it too.
 */
@Composable
internal fun UpdateDialog(release: ReleaseInfo, updates: AppUpdates) {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val notes = remember(release.notes) { ReleaseNotes.blocks(release.notes) }
    val mustAllow = remember {
        Build.VERSION.SDK_INT >= 26 && runCatching { !context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    }
    AlertDialog(
        onDismissRequest = updates::dismissDialog,
        icon = { Icon(Icons.Outlined.SystemUpdate, null) },
        title = { Text(stringResource(R.string.set_update_dialog_title, release.version)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                if (notes.isEmpty()) {
                    Text(stringResource(R.string.set_update_no_notes), style = MaterialTheme.typography.bodyMedium)
                }
                notes.forEach { block ->
                    when (block.kind) {
                        NoteBlock.Kind.HEADING -> Text(block.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Dimens.xs))
                        NoteBlock.Kind.BULLET -> Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                            Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            Text(block.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                        NoteBlock.Kind.TEXT -> Text(block.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        NoteBlock.Kind.NOTE -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Row(Modifier.fillMaxWidth().padding(Dimens.m), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(block.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                val hints = buildList {
                    if (release.apkUrl == null) add(stringResource(R.string.set_update_no_apk))
                    else {
                        if (release.apkSize > 0) add(stringResource(R.string.set_update_size, Formatter.formatShortFileSize(context, release.apkSize)))
                        if (updates.isDebugBuild) add(stringResource(R.string.set_update_debug_note))
                        if (mustAllow) add(stringResource(R.string.set_update_allow_note))
                    }
                }
                hints.forEach { hint ->
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Dimens.xs))
                }
            }
        },
        confirmButton = {
            if (release.apkUrl != null) {
                Button(onClick = { updates.download(release) }) { Text(stringResource(R.string.set_update_download)) }
            } else {
                Button(onClick = { updates.dismissDialog(); runCatching { uri.openUri(release.pageUrl) } }) { Text(stringResource(R.string.set_update_open_page)) }
            }
        },
        dismissButton = {
            Row {
                if (release.apkUrl != null) {
                    TextButton(onClick = { updates.dismissDialog(); runCatching { uri.openUri(release.pageUrl) } }) { Text(stringResource(R.string.set_update_page)) }
                }
                TextButton(onClick = updates::dismissDialog) { Text(stringResource(R.string.set_later)) }
            }
        },
    )
}

/** On top of the settings root while a newer version is out. */
@Composable
internal fun UpdateCard(version: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs + Dimens.xxs),
    ) {
        Row(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.l)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.set_update_card_title, version), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.set_update_card_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** The app icon, name, version and author in a card at the top of "About". */
@Composable
private fun AboutHeader(version: String, code: Long) {
    val context = LocalContext.current
    val launcher = LocalLauncherIcons.current
    val picked = launcher?.let { l ->
        val id by l.current.collectAsStateWithLifecycle()
        l.icons.firstOrNull { it.id == id }
    }
    val icon: ImageBitmap? = remember {
        if (launcher != null) null
        else runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
    ) {
        Row(Modifier.padding(Dimens.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.l)) {
            if (picked != null) {
                LauncherIconImage(picked, Modifier.size(64.dp), RoundedCornerShape(18.dp))
            } else if (icon != null) {
                Image(icon, null, Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)))
            } else {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("読", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.xxs)) {
                Text("Yomu", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.set_about_version_line, version, code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.set_about_tagline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.set_about_by, ProjectLinks.AUTHOR), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
