package app.yomu

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.yomu.core.datastore.AppSettings
import app.yomu.core.ui.components.IncognitoBanner
import app.yomu.core.ui.theme.ThemeConfig
import app.yomu.core.ui.theme.ThemeMode
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.data.backup.SyncManager
import app.yomu.feature.settings.AppUpdates
import app.yomu.feature.settings.SettingsLinks
import app.yomu.feature.settings.SettingsScreen
import app.yomu.feature.settings.SettingsSection
import app.yomu.feature.settings.SettingsViewModel
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Incognito on the settings root (with the strip shown above every screen) and GitHub sync in Settings → Sync. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class Release210ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dark = ThemeConfig(mode = ThemeMode.DARK)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val settings: AppSettings get() = GlobalContext.get().get()

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun model(): SettingsViewModel {
        val koin = GlobalContext.get()
        return SettingsViewModel(context, settings, koin.get(), koin.get(), koin.get(), koin.get(), AppUpdates(context, settings, fetch = { null }), koin.get(), koin.get(), koin.get())
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun incognitoOnTheSettingsRoot() {
        val vm = model()
        compose.setContent {
            YomuTheme(dark) {
                Column(Modifier.fillMaxSize()) {
                    IncognitoBanner(onTurnOff = {})
                    Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
                        SettingsScreen(viewModel = vm, links = SettingsLinks())
                    }
                }
            }
        }
        awaitText("Incognito mode")
        // The switch on the settings root turns the mode on.
        compose.onNodeWithText("Incognito mode").performClick()
        compose.waitUntil(5_000) { runBlocking { settings.incognito.flow.first() } }
        assertTrue(runBlocking { settings.incognito.get() })
        shot("220_incognito_settings_root")
    }

    @Test
    fun githubSync() {
        runBlocking { settings.syncBackend.set(SyncManager.GITHUB) }
        compose.setContent {
            YomuTheme(dark) { SettingsScreen(viewModel = model(), links = SettingsLinks(), initialSection = SettingsSection.SYNC) }
        }
        awaitText("GitHub token")
        compose.onNodeWithText("Create a private repository").performScrollTo()
        shot("221_settings_sync_github")
    }

    @Test
    fun syncCategories() {
        compose.setContent {
            YomuTheme(dark) { SettingsScreen(viewModel = model(), links = SettingsLinks(), initialSection = SettingsSection.SYNC) }
        }
        awaitText("What to sync")
        compose.onNodeWithText("Tracker links").performScrollTo()
        shot("222_settings_sync_categories")
    }

    @Test
    fun notificationSettings() {
        compose.setContent {
            YomuTheme(dark) { SettingsScreen(viewModel = model(), links = SettingsLinks(), initialSection = SettingsSection.NOTIFICATIONS) }
        }
        awaitText("Notify about")
        compose.onNodeWithText("Review reminders").performScrollTo()
        shot("223_settings_notifications")
    }
}
