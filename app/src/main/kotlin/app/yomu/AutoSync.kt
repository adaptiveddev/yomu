package app.yomu

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.yomu.core.datastore.AppSettings
import app.yomu.data.backup.SyncManager
import app.yomu.data.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Syncs when the app comes to the screen and when it is left (Settings → Sync), in the background through WorkManager. */
class AutoSync(private val context: Context, private val settings: AppSettings) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) = trigger(onStart = true)

    override fun onStop(owner: LifecycleOwner) = trigger(onStart = false)

    private fun trigger(onStart: Boolean) {
        scope.launch {
            runCatching {
                if (!settings.syncEnabled.get()) return@launch
                if (onStart && !settings.syncOnStart.get()) return@launch
                if (!onStart && !settings.syncOnExit.get()) return@launch
                if (!SyncManager.isConfigured(settings)) return@launch
                val network = SyncManager.needsNetwork(settings.syncBackend.get())
                // Coming back after a minute is not a reason for another sync.
                if (onStart && System.currentTimeMillis() - settings.lastSyncAt.get() < MIN_GAP_MS) return@launch
                SyncWorker.runSoon(context, needsNetwork = network, wifiOnly = settings.syncWifiOnly.get())
            }
        }
    }

    private companion object {
        const val MIN_GAP_MS = 3 * 60_000L
    }
}
