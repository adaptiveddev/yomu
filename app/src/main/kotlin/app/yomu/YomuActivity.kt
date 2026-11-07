package app.yomu

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.yomu.core.ui.components.HintStore
import app.yomu.core.ui.components.KeyBus
import app.yomu.core.ui.components.LauncherIcons
import app.yomu.ui.YomuRoot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * The only activity. The launcher starts it through one of the activity-aliases in the manifest (one per launcher
 * icon, see [AppLauncherIcons]); the classic alias keeps the pre-1.7.0 name `app.yomu.MainActivity`.
 */
class YomuActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModel()
    private val lock: AppLockController by inject()
    private val hints: HintStore by inject()
    private val launcherIcons: LauncherIcons by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Reopening from the recent apps list delivers the old intent again: the shared file or notification was handled then.
        val fromHistory = intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !fromHistory) viewModel.handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.state.filterNotNull().map { it.secureScreen }.distinctUntilChanged().collect { secure ->
                    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        setContent { YomuRoot(viewModel, lock, hints, launcherIcons) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.handleIntent(intent)
    }

    /** Volume keys turn pages while the reader asks for it (see [KeyBus]). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (KeyBus.onVolume(up = keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && KeyBus.volumeHandler != null) return true
        return super.onKeyUp(keyCode, event)
    }
}
