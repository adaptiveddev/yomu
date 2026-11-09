package app.yomu.feature.settings

import android.app.PendingIntent
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.yomu.data.work.Notifications
import app.yomu.data.work.NotifyKind
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The app's [UpdateBackground]: a WorkManager job that outlives the screens and the process. */
class WorkUpdateBackground(private val context: Context) : UpdateBackground {
    override fun start(release: ReleaseInfo) {
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UpdateDownloadWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UpdateDownloadWorker.NAME)
        Notifications.cancel(context, Notifications.ID_APP_UPDATE)
    }
}

/**
 * Downloads the pending update in the background with a progress notification. A dropped connection is retried
 * (the partial file resumes), and the finished APK opens the installer when the app is on screen; otherwise a
 * notification does, since Android does not let an app in the background open the installer itself.
 */
class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val updates: AppUpdates by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo = progressInfo(updates.pendingRelease()?.version.orEmpty(), null)

    private fun progressInfo(version: String, progress: Float?): ForegroundInfo {
        val ctx = applicationContext
        return Notifications.foreground(
            ctx, Notifications.ID_APP_UPDATE_PROGRESS, Notifications.TASKS, ctx.getString(R.string.notif_update_downloading, version),
            progress ?: 0f,
        ) {
            addAction(0, ctx.getString(R.string.set_update_cancel), WorkManager.getInstance(ctx).createCancelPendingIntent(id))
        }
    }

    override suspend fun doWork(): Result {
        val release = updates.pendingRelease() ?: return Result.success()
        // From the background the system may refuse a foreground service: the download then runs as plain work.
        runCatching { setForeground(progressInfo(release.version, null)) }
        val progress = MutableStateFlow<Float?>(null)
        val result = coroutineScope {
            // Whole percents only: the notification is not redrawn for every chunk.
            val shown = launch {
                progress.map { p -> p?.let { (it * 100).toInt() } }.distinctUntilChanged().collect { percent ->
                    runCatching { setForeground(progressInfo(release.version, percent?.let { it / 100f })) }
                }
            }
            updates.runDownload(release) { p -> progress.value = p }.also { shown.cancel() }
        }
        return when (result) {
            is UpdateState.ReadyToInstall -> {
                updates.clearPending()
                deliver(result.file, release)
                Result.success()
            }
            is UpdateState.Failed -> if (retryable(result.error) && runAttemptCount < MAX_ATTEMPTS) {
                updates.waitingToRetry(release)
                Result.retry()
            } else {
                updates.clearPending()
                notify(applicationContext.getString(R.string.notif_update_failed, release.version), result.error.message, null)
                Result.failure()
            }
            else -> Result.success()
        }
    }

    /** On screen: the installer right away. In the background: a notification that opens it. */
    private fun deliver(file: File, release: ReleaseInfo) {
        val ctx = applicationContext
        ContextCompat.getMainExecutor(ctx).execute {
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                updates.install(file)
                return@execute
            }
            val checked = runCatching { UpdateDownloader.checkPackage(ctx, file) }
            if (checked.isFailure) {
                updates.install(file) // reports the failure in the settings like a tap on "Install" would
                notify(ctx.getString(R.string.notif_update_failed, release.version), checked.exceptionOrNull()?.message, null)
                return@execute
            }
            val open = PendingIntent.getActivity(
                ctx, Notifications.ID_APP_UPDATE, UpdateDownloader.installIntent(ctx, file),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            notify(ctx.getString(R.string.notif_update_ready, release.version), ctx.getString(R.string.notif_update_ready_text), open)
        }
    }

    private fun notify(title: String, text: String?, open: PendingIntent?) {
        val ctx = applicationContext
        Notifications.notify(
            ctx, Notifications.ID_APP_UPDATE,
            Notifications.builder(ctx, Notifications.APP_UPDATE).setContentTitle(title).setAutoCancel(true)
                .apply { if (!text.isNullOrBlank()) setContentText(text) }
                .apply { if (open != null) setContentIntent(open) },
            NotifyKind.APP_UPDATE,
        )
    }

    companion object {
        const val NAME = "app_update_download"
        private const val MAX_ATTEMPTS = 6

        /** Network trouble is worth another try; a wrong checksum, a missing file or a refused request is not. */
        fun retryable(e: Throwable): Boolean =
            e is IOException && e !is UpdateChecksumException && !(e.message.orEmpty().matches(Regex("HTTP 4\\d\\d.*"))) &&
                e.message?.startsWith("The release has no APK") != true
    }
}
