package app.yomu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.AppLockController
import app.yomu.MainViewModel
import app.yomu.core.ui.components.HintStore
import app.yomu.core.ui.components.LocalHapticsEnabled
import app.yomu.core.ui.components.LocalHideCovers
import app.yomu.core.ui.components.LauncherIcons
import app.yomu.core.ui.components.LocalHintStore
import app.yomu.core.ui.components.LocalLauncherIcons
import app.yomu.core.ui.components.LocalReduceMotion
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.feature.settings.AppLockScreen
import app.yomu.navigation.YomuNavigation

/** Theme, global locals, onboarding, the navigation shell and the app lock overlay. */
@Composable
fun YomuRoot(viewModel: MainViewModel, lock: AppLockController, hints: HintStore, launcherIcons: LauncherIcons? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locked by lock.locked.collectAsStateWithLifecycle()
    val whatsNew by viewModel.whatsNew.collectAsStateWithLifecycle()
    val crash by viewModel.crash.collectAsStateWithLifecycle()
    val hideCovers by viewModel.hideCovers.collectAsStateWithLifecycle()
    val s = state ?: return
    YomuTheme(s.theme) {
        CompositionLocalProvider(
            LocalHapticsEnabled provides s.haptics,
            LocalHideCovers provides hideCovers,
            LocalHintStore provides hints,
            LocalLauncherIcons provides launcherIcons,
            LocalReduceMotion provides s.reduceMotion,
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    if (s.onboardingDone) {
                        YomuNavigation(viewModel, s.reduceMotion, s.navLabels, s.navTabs)
                    } else {
                        OnboardingScreen(viewModel.settings, onDone = viewModel::finishOnboarding)
                    }
                    when (locked) {
                        true -> Box(
                            Modifier.fillMaxSize().pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            },
                        ) {
                            AppLockScreen(viewModel.settings, onUnlocked = lock::unlock)
                        }
                        // Settings not read yet: cover the content instead of flashing it.
                        null -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                        false -> Unit
                    }
                }
                val report = crash
                when {
                    !s.onboardingDone || locked != false -> Unit
                    // The crash first: the list of news waits until the next start or until the dialog is closed.
                    report != null -> CrashReportDialog(report, onDismiss = viewModel::dismissCrash)
                    whatsNew.isNotEmpty() -> WhatsNewSheet(whatsNew, onDismiss = viewModel::dismissWhatsNew)
                }
            }
        }
    }
}
