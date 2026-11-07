package app.yomu

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.yomu.core.datastore.AppSettings
import app.yomu.core.ui.components.FloatingNavBar
import app.yomu.core.ui.components.LauncherIcon
import app.yomu.core.ui.components.LauncherIconImage
import app.yomu.core.ui.components.LocalLauncherIcons
import app.yomu.core.ui.components.NavTab
import app.yomu.core.ui.components.item
import app.yomu.core.ui.theme.ThemeConfig
import app.yomu.core.ui.theme.ThemeMode
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.feature.settings.AppUpdates
import app.yomu.feature.settings.ReleaseInfo
import app.yomu.feature.settings.SettingsLinks
import app.yomu.feature.settings.SettingsScreen
import app.yomu.feature.settings.SettingsSection
import app.yomu.feature.settings.SettingsViewModel
import app.yomu.feature.settings.UpdateState
import app.yomu.ui.WHATS_NEW
import app.yomu.ui.WhatsNewSheet
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screens of 1.7: the launcher icons (as a launcher masks them, and themed), the picker in Appearance, the updater in
 * About (a release dialog, a download in progress), the card on the settings root, the dot on the Settings tab and
 * "What's new". Updates run on fakes: no network.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class Release170ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dark = ThemeConfig(mode = ThemeMode.DARK)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val settings: AppSettings get() = GlobalContext.get().get()

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** With dialogs and sheets, which live in windows of their own. */
    private fun screen(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    // A fake release must stay newer as the installed application version changes.
    private val futureVersion = "${(BuildConfig.VERSION_NAME.substringBefore('.').toIntOrNull() ?: 2) + 1}.0.0"
    private val release = ReleaseInfo(
        version = futureVersion,
        pageUrl = "https://github.com/adxptived/yomu/releases/tag/v$futureVersion",
        apkUrl = "https://github.com/adxptived/yomu/releases/download/v$futureVersion/yomu-v$futureVersion-arm64-v8a.apk",
        apkSize = 68_400_000,
        checksumsUrl = "https://github.com/adxptived/yomu/releases/download/v$futureVersion/SHA256SUMS.txt",
        notes = """
            > **Установка.** Ставится поверх 2.0.0, данные сохраняются.

            ### Читалка

            - **Исправлено:** закладки в PDF снова открываются на своей странице.
            - Плавнее листаются длинные главы манги.

            ### Установка / Install

            - `yomu-v2.0.2-arm64-v8a.apk` — телефоны и планшеты
        """.trimIndent(),
    )

    private fun settingsModel(updates: AppUpdates): SettingsViewModel {
        val koin = GlobalContext.get()
        return SettingsViewModel(context, settings, koin.get(), koin.get(), koin.get(), koin.get(), updates, koin.get(), koin.get(), koin.get())
    }

    private fun settingsScreen(vm: SettingsViewModel, section: SettingsSection?, icons: AppLauncherIcons = AppLauncherIcons(context)) {
        compose.setContent {
            YomuTheme(dark) {
                CompositionLocalProvider(LocalLauncherIcons provides icons) {
                    SettingsScreen(viewModel = vm, links = SettingsLinks(), initialSection = section)
                }
            }
        }
    }

    /** Every icon: as a round and a rounded-square launcher mask cut it, then themed (Android 13+) in light and dark. */
    @Test
    @Config(qualifiers = "w740dp-h520dp-xxhdpi")
    fun appIcons() {
        val icons = AppLauncherIcons(context).icons
        compose.setContent {
            YomuTheme(dark) {
                Row(
                    Modifier.testTag(SHEET).background(Color(0xFF14151B)).padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    icons.forEach { icon ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LauncherIconImage(icon, Modifier.size(ICON), CircleShape)
                            LauncherIconImage(icon, Modifier.size(ICON), RoundedCornerShape(26))
                            ThemedIcon(monochrome(icon), background = Color(0xFFDCE2F9), tint = Color(0xFF2B3866))
                            ThemedIcon(monochrome(icon), background = Color(0xFF2D3040), tint = Color(0xFFC5D0F7))
                            Text(stringResource(icon.label), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SHEET).captureRoboImage("build/outputs/roborazzi/70_app_icons.png")
    }

    @Test
    fun appIconPicker() {
        val icons = AppLauncherIcons(context).apply { select("fox") }
        settingsScreen(settingsModel(AppUpdates(context, settings, fetch = { null })), SettingsSection.APPEARANCE, icons)
        awaitText("App icon")
        // Скроллим к подсказке под плитками: тогда видна вся сетка иконок, а не только заголовок у края.
        compose.onNodeWithText("The icon changes when you leave the app", substring = true).performScrollTo()
        awaitText("Fox")
        compose.mainClock.advanceTimeBy(1_000)
        shot("71_settings_app_icon")
    }

    @Test
    fun updateDialog() {
        val updates = AppUpdates(context, settings, fetch = { release }, installer = {})
        settingsScreen(settingsModel(updates), SettingsSection.ABOUT, AppLauncherIcons(context).apply { select("spark") })
        awaitText("Check for updates")
        updates.check(openDialog = true)
        awaitText("Download and install")
        compose.mainClock.advanceTimeBy(1_000)
        screen("72_update_dialog")
    }

    @Test
    fun updateDownloading() {
        val updates = AppUpdates(
            context,
            settings,
            fetch = { release },
            downloader = { _, _, progress ->
                progress(0.42f)
                awaitCancellation()
            },
            installer = {},
        )
        settingsScreen(settingsModel(updates), SettingsSection.ABOUT, AppLauncherIcons(context).apply { select("spark") })
        awaitText("Check for updates")
        updates.download(release)
        runBlocking { withTimeout(5_000) { updates.state.first { it is UpdateState.Downloading && it.progress == 0.42f } } }
        awaitText("42%")
        compose.mainClock.advanceTimeBy(1_000)
        shot("73_settings_about_downloading")
        updates.cancelDownload()
        runBlocking { withTimeout(5_000) { updates.state.first { it is UpdateState.Available } } }
    }

    @Test
    fun updateCardOnSettingsRoot() {
        runBlocking { settings.updateLatest.set(futureVersion) }
        try {
            val updates = AppUpdates(context, settings, fetch = { release })
            // The stored version is read before the screen starts, so the card is there from the first frame.
            runBlocking { withTimeout(10_000) { updates.available.first { it != null } } }
            settingsScreen(settingsModel(updates), section = null)
            awaitText("Yomu $futureVersion is available")
            compose.mainClock.advanceTimeBy(1_000)
            shot("74_settings_update_card")
        } finally {
            runBlocking { settings.updateLatest.set("") }
        }
    }

    @Test
    fun navBadge() {
        compose.setContent {
            YomuTheme(dark) {
                val items = NavTab.entries.map { tab -> tab.item().let { if (tab == NavTab.SETTINGS) it.copy(badge = true) else it } }
                Column(
                    Modifier.testTag(BAR).fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FloatingNavBar(items, 0, showLabels = false, onSelect = {}, windowInsets = WindowInsets(0))
                    FloatingNavBar(items, 4, showLabels = false, onSelect = {}, windowInsets = WindowInsets(0))
                    FloatingNavBar(items, 0, showLabels = true, onSelect = {}, windowInsets = WindowInsets(0))
                }
            }
        }
        awaitText("Settings, new")
        compose.waitForIdle()
        compose.onNodeWithTag(BAR).captureRoboImage("build/outputs/roborazzi/75_nav_badge.png")
    }

    @Test
    fun whatsNew() {
        compose.setContent {
            YomuTheme(dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    WhatsNewSheet(WHATS_NEW, onDismiss = {})
                }
            }
        }
        awaitText("What's new in Yomu")
        compose.mainClock.advanceTimeBy(2_000)
        screen("76_whats_new")
    }

    @Composable
    private fun ThemedIcon(@DrawableRes mono: Int, background: Color, tint: Color) {
        Box(Modifier.size(ICON).clip(CircleShape).background(background)) {
            Image(painterResource(mono), null, Modifier.matchParentSize().scale(1.5f), colorFilter = ColorFilter.tint(tint))
        }
    }

    @DrawableRes
    private fun monochrome(icon: LauncherIcon): Int = when (icon.id) {
        "spark" -> R.drawable.ic_launcher_spark_monochrome
        "ribbon" -> R.drawable.ic_launcher_ribbon_monochrome
        "fox" -> R.drawable.ic_launcher_fox_monochrome
        "hanko" -> R.drawable.ic_launcher_hanko_monochrome
        "gaze" -> R.drawable.ic_launcher_gaze_monochrome
        "manga" -> R.drawable.ic_launcher_manga_monochrome
        else -> R.drawable.ic_launcher_monochrome
    }

    private companion object {
        const val SHEET = "sheet"
        const val BAR = "bar"
        val ICON = 80.dp
    }
}
