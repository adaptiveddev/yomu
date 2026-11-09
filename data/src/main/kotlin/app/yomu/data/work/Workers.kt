package app.yomu.data.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.yomu.core.datastore.AppSettings
import app.yomu.data.R
import app.yomu.data.backup.BackupManager
import app.yomu.data.backup.SyncManager
import app.yomu.data.catalog.CatalogRepository
import app.yomu.data.download.DownloadRepository
import app.yomu.data.library.ImportService
import app.yomu.data.repository.WordRepositoryImpl
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit

object Notifications {
    const val DOWNLOADS = "downloads"
    const val UPDATES = "updates"
    const val BACKUP = "backup"
    const val REMINDERS = "reminders"
    const val TASKS = "tasks"

    const val ID_DOWNLOAD = 1001
    const val ID_UPDATES = 1002
    const val ID_UPDATES_RESULT = 1003
    const val ID_BACKUP = 1004
    const val ID_REMINDER = 1005
    const val ID_TRANSLATION = 1006
    const val ID_SCAN = 1007
    const val ID_DOWNLOAD_DONE = 1008

    /** Intent action of the notifications that open the download queue (handled by the app's main activity). */
    const val ACTION_OPEN_DOWNLOADS = "app.yomu.action.OPEN_DOWNLOADS"

    /** Activity opened by notifications; set by the app module. */
    @Volatile var launchIntentFactory: ((Context) -> android.content.Intent)? = null

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        listOf(
            DOWNLOADS to R.string.channel_downloads, UPDATES to R.string.channel_updates, BACKUP to R.string.channel_backup,
            REMINDERS to R.string.channel_reminders, TASKS to R.string.channel_tasks,
        ).forEach { (id, name) ->
            val importance = if (id == UPDATES || id == REMINDERS) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW
            nm.createNotificationChannel(NotificationChannel(id, context.getString(name), importance))
        }
    }

    /** [open] is the intent action of the screen a tap opens (for example [ACTION_OPEN_DOWNLOADS]); the app's start screen by default. */
    fun builder(context: Context, channel: String, open: String? = null): NotificationCompat.Builder = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .apply {
            launchIntentFactory?.invoke(context)?.let {
                if (open != null) it.action = open
                setContentIntent(PendingIntent.getActivity(context, open?.hashCode() ?: 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }

    fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    fun foreground(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        progress: Float?,
        text: String? = null,
        open: String? = null,
        extra: NotificationCompat.Builder.() -> Unit = {},
    ): ForegroundInfo {
        val n = builder(context, channel, open).setContentTitle(title).setOngoing(true).setOnlyAlertOnce(true)
            .apply { if (text != null) setContentText(text) }
            .apply { if (progress != null) setProgress(100, (progress * 100).toInt(), progress <= 0f) }
            .apply(extra)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val downloads: DownloadRepository by inject()
    private val settings: AppSettings by inject()

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // Follow-up runs appended while the previous run was finishing often find the queue already empty.
        if (!downloads.hasWork()) return Result.success()
        val title = ctx.getString(R.string.notif_downloading)
        // A new run replaces the result of the previous one ("finished", "paused: storage full").
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).cancel(Notifications.ID_DOWNLOAD_DONE) }
        runCatching { setForeground(progressInfo(ctx, title, 0f)) }
        var lastShown = 0L
        val outcome = downloads.runQueue { p ->
            val now = System.currentTimeMillis()
            if (now - lastShown < NOTIFY_MS && p.fraction < 1f) return@runQueue
            lastShown = now
            val head = if (p.remaining > 0) ctx.resources.getQuantityString(R.plurals.notif_downloads_left, p.remaining, p.remaining) else title
            runCatching { setForeground(progressInfo(ctx, head, p.fraction, p.title)) }
        }
        if (outcome.outOfSpace) {
            Notifications.notify(
                ctx, Notifications.ID_DOWNLOAD_DONE,
                Notifications.builder(ctx, Notifications.DOWNLOADS, Notifications.ACTION_OPEN_DOWNLOADS)
                    .setContentTitle(ctx.getString(R.string.notif_downloads_no_space))
                    .setContentText(ctx.getString(R.string.notif_downloads_no_space_text)).setAutoCancel(true)
                    .addAction(DownloadActionReceiver.action(ctx, DownloadActionReceiver.ACTION_RESUME, R.string.notif_action_resume)),
            )
        } else if (outcome.waitingForNetwork) {
            // Continue later: the follow-up run waits for the network (and a short delay against fast loops).
            start(ctx, settings.downloadWifiOnly.get(), delayMs = NETWORK_RETRY_MS)
            Notifications.notify(
                ctx, Notifications.ID_DOWNLOAD_DONE,
                Notifications.builder(ctx, Notifications.DOWNLOADS, Notifications.ACTION_OPEN_DOWNLOADS)
                    .setContentTitle(ctx.getString(R.string.notif_downloads_waiting_network)).setAutoCancel(true),
            )
        } else if (outcome.done + outcome.failed > 0) {
            val parts = buildList {
                if (outcome.done > 0) add(ctx.resources.getQuantityString(R.plurals.notif_downloads_done_text, outcome.done, outcome.done))
                if (outcome.failed > 0) add(ctx.resources.getQuantityString(R.plurals.notif_downloads_failed_text, outcome.failed, outcome.failed))
            }
            Notifications.notify(
                ctx, Notifications.ID_DOWNLOAD_DONE,
                Notifications.builder(ctx, Notifications.DOWNLOADS, Notifications.ACTION_OPEN_DOWNLOADS)
                    .setContentTitle(ctx.getString(R.string.notif_downloads_finished))
                    .setContentText(parts.joinToString(" · ")).setAutoCancel(true)
                    .apply {
                        if (outcome.failed > 0) addAction(DownloadActionReceiver.action(ctx, DownloadActionReceiver.ACTION_RETRY, R.string.notif_action_retry))
                    },
            )
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = progressInfo(applicationContext, applicationContext.getString(R.string.notif_downloading), 0f)

    /** Ongoing notification of the queue: a tap opens the queue, "Pause" pauses everything that is waiting or running. */
    private fun progressInfo(ctx: Context, title: String, fraction: Float, text: String? = null): ForegroundInfo = Notifications.foreground(
        ctx, Notifications.ID_DOWNLOAD, Notifications.DOWNLOADS, title, fraction, text, open = Notifications.ACTION_OPEN_DOWNLOADS,
    ) { addAction(DownloadActionReceiver.action(ctx, DownloadActionReceiver.ACTION_PAUSE, R.string.notif_action_pause)) }

    companion object {
        const val NAME = "downloads"
        private const val NOTIFY_MS = 700L
        private const val NETWORK_RETRY_MS = 30_000L

        /**
         * Starts the queue. A run that is already active gets a follow-up run appended (APPEND_OR_REPLACE): tasks added
         * while it is finishing are never left waiting. [delayMs] postpones the run (not expedited then).
         */
        fun start(context: Context, wifiOnly: Boolean, delayMs: Long = 0) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .apply {
                    if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) else setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

class LibraryScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val importer: ImportService by inject()
    private val settings: AppSettings by inject()

    override suspend fun doWork(): Result {
        settings.scanFolders.get().forEach { folder ->
            runCatching { importer.scanTree(Uri.parse(folder)) }.onFailure { Timber.w(it, "Scan of $folder failed") }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notifications.foreground(applicationContext, Notifications.ID_SCAN, Notifications.TASKS, applicationContext.getString(R.string.notif_scanning), null)

    companion object {
        const val NAME = "library_scan"
        fun runNow(context: Context) = WorkManager.getInstance(context)
            .enqueueUniqueWork("${NAME}_now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<LibraryScanWorker>().build())
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val catalog: CatalogRepository by inject()
    private val downloads: DownloadRepository by inject()

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val result = catalog.checkUpdates()
        val queued = runCatching { downloads.autoDownloadNew(result) }.onFailure { Timber.w(it, "Auto download failed") }.getOrDefault(0)
        if (result.updated.isNotEmpty()) {
            val total = result.newChapters
            val lines = result.updated.map { u -> ctx.resources.getQuantityString(R.plurals.notif_new_chapters_text, u.newChapterIds.size, u.newChapterIds.size, u.title) } +
                listOfNotNull(
                    queued.takeIf { it > 0 }?.let { ctx.resources.getQuantityString(R.plurals.notif_auto_download, it, it) },
                    result.failed.size.takeIf { it > 0 }?.let { ctx.resources.getQuantityString(R.plurals.notif_updates_failed, it, it) },
                )
            val text = lines.joinToString("\n")
            Notifications.notify(
                ctx, Notifications.ID_UPDATES_RESULT,
                Notifications.builder(ctx, Notifications.UPDATES).setContentTitle(ctx.getString(R.string.notif_new_chapters) + " ($total)")
                    .setContentText(lines.first()).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true),
            )
        }
        result.failed.forEach { (title, e) -> Timber.i("Update check of $title failed: ${e.message}") }
        return Result.success()
    }

    companion object {
        const val NAME = "catalog_updates"
        fun runNow(context: Context) = WorkManager.getInstance(context)
            .enqueueUniqueWork("${NAME}_now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val backup: BackupManager by inject()

    override suspend fun doWork(): Result = try {
        backup.autoBackup()
        Result.success()
    } catch (e: Exception) {
        Timber.e(e, "Auto backup failed")
        Notifications.notify(applicationContext, Notifications.ID_BACKUP, Notifications.builder(applicationContext, Notifications.BACKUP).setContentTitle(applicationContext.getString(R.string.notif_backup_failed)).setContentText(e.message))
        Result.retry()
    }

    companion object {
        const val NAME = "auto_backup"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val sync: SyncManager by inject()

    override suspend fun doWork(): Result {
        if (!sync.isConfigured()) return Result.success()
        return runCatching { sync.syncNow() }.fold(
            { Result.success() },
            { e ->
                Timber.w(e, "Sync failed")
                // A one-time sync (start, leaving the app) is not retried forever: the periodic one comes anyway.
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        const val NAME = "sync"

        /** A sync right now (the button in the settings): replaces a waiting one. */
        fun runNow(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("${NAME}_now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().build())

        /** An automatic sync (app start, app left): one at a time, with a network when the target is a server. */
        fun runSoon(context: Context, needsNetwork: Boolean, wifiOnly: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(syncNetwork(needsNetwork, wifiOnly)).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("${NAME}_auto", ExistingWorkPolicy.KEEP, request)
        }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val words: WordRepositoryImpl by inject()

    override suspend fun doWork(): Result {
        val due = words.observeDueCount(System.currentTimeMillis()).first()
        if (due > 0) {
            val ctx = applicationContext
            Notifications.notify(
                ctx, Notifications.ID_REMINDER,
                Notifications.builder(ctx, Notifications.REMINDERS).setContentTitle(ctx.getString(R.string.notif_review_title))
                    .setContentText(ctx.resources.getQuantityString(R.plurals.notif_review_text, due, due)).setAutoCancel(true),
            )
        }
        return Result.success()
    }

    companion object {
        const val NAME = "srs_reminder"
    }
}

/** Schedules all periodic background work according to the current settings. */
class WorkScheduler(private val context: Context, private val settings: AppSettings) {
    private val wm get() = WorkManager.getInstance(context)

    suspend fun scheduleAll() {
        scheduleScan()
        scheduleUpdates()
        scheduleBackup()
        scheduleReminder()
        scheduleSync()
    }

    suspend fun scheduleScan() {
        if (!settings.autoScan.get() || settings.scanFolders.get().isEmpty()) {
            wm.cancelUniqueWork(LibraryScanWorker.NAME)
            return
        }
        val hours = settings.autoScanHours.get().coerceAtLeast(1).toLong()
        wm.enqueueUniquePeriodicWork(LibraryScanWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<LibraryScanWorker>(hours, TimeUnit.HOURS).build())
    }

    suspend fun scheduleUpdates() {
        val hours = settings.updateIntervalHours.get()
        if (hours <= 0) {
            wm.cancelUniqueWork(UpdateWorker.NAME)
            return
        }
        val network = if (settings.updateWifiOnly.get()) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(UpdateWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    suspend fun scheduleBackup() {
        val hours = settings.autoBackupHours.get()
        if (hours <= 0 || settings.backupFolder.get().isBlank()) {
            wm.cancelUniqueWork(BackupWorker.NAME)
            return
        }
        wm.enqueueUniquePeriodicWork(BackupWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<BackupWorker>(hours.toLong(), TimeUnit.HOURS).build())
    }

    suspend fun scheduleSync() {
        val network = SyncManager.needsNetwork(settings.syncBackend.get())
        val configured = SyncManager.isConfigured(settings)
        val hours = settings.syncIntervalHours.get()
        if (!settings.syncEnabled.get() || !configured || hours <= 0) {
            wm.cancelUniqueWork(SyncWorker.NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(hours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(syncNetwork(network, settings.syncWifiOnly.get())).build())
            .build()
        wm.enqueueUniquePeriodicWork(SyncWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    suspend fun scheduleReminder() {
        if (!settings.reminderEnabled.get()) {
            wm.cancelUniqueWork(ReminderWorker.NAME)
            return
        }
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, settings.reminderHour.get())
            set(Calendar.MINUTE, settings.reminderMinute.get())
            set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val delay = next.timeInMillis - now.timeInMillis
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        wm.enqueueUniquePeriodicWork(ReminderWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/** Network a background sync waits for: none for a shared folder, Wi-Fi (unmetered) when the user asked for it. */
internal fun syncNetwork(needsNetwork: Boolean, wifiOnly: Boolean): NetworkType = when {
    !needsNetwork -> NetworkType.NOT_REQUIRED
    wifiOnly -> NetworkType.UNMETERED
    else -> NetworkType.CONNECTED
}
