package app.yomu.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.ui.components.PreferenceHeader
import app.yomu.core.ui.theme.Dimens
import app.yomu.data.work.NotificationLog
import app.yomu.data.work.NotifyKind
import java.text.DateFormat
import java.util.Date

private fun NotifyKind.title(): Int = when (this) {
    NotifyKind.NEW_CHAPTERS -> R.string.set_notify_new_chapters
    NotifyKind.DOWNLOADS -> R.string.set_notify_downloads
    NotifyKind.SYNC -> R.string.set_notify_sync
    NotifyKind.BACKUP -> R.string.set_notify_backup
    NotifyKind.TASKS -> R.string.set_notify_tasks
    NotifyKind.REMINDERS -> R.string.set_notify_reminders
    NotifyKind.APP_UPDATE -> R.string.set_notify_app_update
}

private fun NotifyKind.summary(): Int = when (this) {
    NotifyKind.NEW_CHAPTERS -> R.string.set_notify_new_chapters_sum
    NotifyKind.DOWNLOADS -> R.string.set_notify_downloads_sum
    NotifyKind.SYNC -> R.string.set_notify_sync_sum
    NotifyKind.BACKUP -> R.string.set_notify_backup_sum
    NotifyKind.TASKS -> R.string.set_notify_tasks_sum
    NotifyKind.REMINDERS -> R.string.set_notify_reminders_sum
    NotifyKind.APP_UPDATE -> R.string.set_notify_app_update_sum
}

private fun NotifyKind.icon(): ImageVector = when (this) {
    NotifyKind.NEW_CHAPTERS -> Icons.Outlined.NewReleases
    NotifyKind.DOWNLOADS -> Icons.Outlined.Download
    NotifyKind.SYNC -> Icons.Outlined.CloudSync
    NotifyKind.BACKUP -> Icons.Outlined.Backup
    NotifyKind.TASKS -> Icons.Outlined.Task
    NotifyKind.REMINDERS -> Icons.Outlined.School
    NotifyKind.APP_UPDATE -> Icons.Outlined.SystemUpdate
}

/** Settings → Notifications: permission, kinds, quiet hours and the recent notifications. */
@Composable
internal fun ColumnScope.NotificationsSection(vm: SettingsViewModel) {
    val s = vm.settings
    val context = LocalContext.current
    val disabled by s.notifyDisabled.state()
    val quiet by s.quietHours.state()
    val log = remember { NotificationLog.get(context) }
    val recent by log.entries.collectAsStateWithLifecycle()

    // The permission can change in the system settings while the app is in the background.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { checks++; onPauseOrDispose { } }
    val granted = remember(checks) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    val openSystem = {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent) }
    }

    if (!granted) {
        PreferenceItem(
            stringResource(R.string.set_notify_permission),
            stringResource(R.string.set_notify_permission_sum),
            icon = Icons.Outlined.NotificationsOff,
            onClick = { if (Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS) else openSystem() },
        )
    }
    PreferenceItem(stringResource(R.string.set_notify_system), stringResource(R.string.set_notify_system_sum), icon = Icons.Outlined.Settings, onClick = { openSystem() })

    PreferenceHeader(stringResource(R.string.set_notify_kinds))
    NotifyKind.entries.forEach { k ->
        PreferenceSwitch(
            stringResource(k.title()),
            k.name !in disabled,
            { on -> vm.set(s.notifyDisabled, if (on) disabled - k.name else disabled + k.name) },
            stringResource(k.summary()),
            icon = k.icon(),
        )
    }

    PreferenceHeader(stringResource(R.string.set_notify_quiet))
    SwitchPref(vm, s.quietHours, R.string.set_notify_quiet_on, R.string.set_notify_quiet_sum, icon = Icons.Outlined.Bedtime)
    val hours = (0..23).map { it to "%02d:00".format(it) }
    if (quiet) {
        ChoicePref(vm, s.quietFrom, R.string.set_notify_quiet_from, hours)
        ChoicePref(vm, s.quietTo, R.string.set_notify_quiet_to, hours)
    }

    PreferenceHeader(stringResource(R.string.set_notify_recent))
    if (recent.isEmpty()) {
        Text(
            stringResource(R.string.set_notify_recent_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
        )
    } else {
        val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
        recent.take(30).forEach { r ->
            val kind = NotifyKind.entries.firstOrNull { it.name == r.kind } ?: NotifyKind.TASKS
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(if (r.shown) kind.icon() else Icons.Outlined.NotificationsOff, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    val meta = format.format(Date(r.at)) + if (r.shown) "" else " · " + stringResource(R.string.set_notify_muted)
                    Text(listOf(r.text, meta).filter { it.isNotBlank() }.joinToString("\n"), maxLines = 3, overflow = TextOverflow.Ellipsis)
                },
            )
        }
        PreferenceItem(stringResource(R.string.set_notify_clear), icon = Icons.Outlined.DeleteSweep, onClick = { log.clear() })
    }
    PreferenceItem(stringResource(R.string.set_notify_test), stringResource(R.string.set_notify_test_sum), icon = Icons.Outlined.NotificationsActive, onClick = vm::testNotification)
}
