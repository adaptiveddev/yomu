package app.yomu.data.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.yomu.data.R
import app.yomu.data.download.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/** Buttons of the download notifications ("Pause", "Retry", "Resume"): they act on the queue without opening the app. */
class DownloadActionReceiver : BroadcastReceiver(), KoinComponent {
    private val downloads: DownloadRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in ACTIONS } ?: return
        val app = context.applicationContext
        // Null when called directly instead of by the system (tests).
        val pending: PendingResult? = goAsync()
        scope.launch {
            try {
                when (action) {
                    ACTION_PAUSE -> downloads.pauseAll()
                    ACTION_RETRY -> downloads.retryFailed()
                    ACTION_RESUME -> downloads.resumeAll()
                }
                // The "finished"/"paused" notification has done its job once its button was used.
                if (action != ACTION_PAUSE) runCatching { NotificationManagerCompat.from(app).cancel(Notifications.ID_DOWNLOAD_DONE) }
            } catch (e: Throwable) {
                Timber.w(e, "Download notification action failed: $action")
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "app.yomu.action.DOWNLOADS_PAUSE"
        const val ACTION_RETRY = "app.yomu.action.DOWNLOADS_RETRY"
        const val ACTION_RESUME = "app.yomu.action.DOWNLOADS_RESUME"
        private val ACTIONS = setOf(ACTION_PAUSE, ACTION_RETRY, ACTION_RESUME)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Notification button that sends [action] to this receiver (explicit and immutable, so no other app can use it). */
        fun action(context: Context, action: String, @StringRes label: Int): NotificationCompat.Action {
            val intent = Intent(context, DownloadActionReceiver::class.java).setAction(action).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(label), pi).build()
        }
    }
}
