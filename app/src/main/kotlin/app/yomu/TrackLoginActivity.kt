package app.yomu

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.yomu.data.tracking.TrackSyncWorker
import app.yomu.data.tracking.TrackerManager
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Receives the OAuth redirects of the tracking services (`yomu://anilist-auth`, `yomu://myanimelist-auth`,
 * `yomu://shikimori-auth`), finishes signing in and returns to the app.
 */
class TrackLoginActivity : ComponentActivity() {
    private val trackers: TrackerManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data?.toString()
        val tracker = uri?.let(trackers::trackerForRedirect)
        if (uri == null || tracker == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val name = tracker.service.displayName
            runCatching { tracker.finishLogin(uri) }
                .onSuccess {
                    val user = tracker.userName()
                    val text = if (user.isNullOrBlank()) getString(R.string.track_login_ok_anon, name) else getString(R.string.track_login_ok, name, user)
                    Toast.makeText(this@TrackLoginActivity, text, Toast.LENGTH_LONG).show()
                    // Changes made while signed out (or before a "sign in again") are sent now.
                    TrackSyncWorker.schedule(applicationContext, restart = true)
                }
                .onFailure { e -> Toast.makeText(this@TrackLoginActivity, getString(R.string.track_login_failed, name, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
            startActivity(Intent(this@TrackLoginActivity, YomuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }
}
