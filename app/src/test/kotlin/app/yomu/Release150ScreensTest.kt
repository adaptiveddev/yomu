package app.yomu

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.yomu.core.database.CatalogItemEntity
import app.yomu.core.database.ChapterEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.core.ui.theme.Accent
import app.yomu.core.ui.theme.ThemeConfig
import app.yomu.core.ui.theme.ThemeMode
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.data.library.LibraryTabs
import app.yomu.feature.catalog.CatalogHomeViewModel
import app.yomu.feature.catalog.OnlineLibraryPane
import app.yomu.feature.library.LibraryScreen
import app.yomu.feature.library.OnlineLibrary
import app.yomu.feature.settings.SettingsLinks
import app.yomu.feature.settings.SettingsScreen
import app.yomu.feature.settings.SettingsSection
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screens changed in 1.5: the library with online titles only (no "empty library" any more), user tabs holding online
 * titles, the library filter sheet and the appearance settings. Data goes straight into the database.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class Release150ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dark = ThemeConfig(mode = ThemeMode.DARK)

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shotScreen(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    /** Followed titles of a source that is not installed (no refresh reaches the network); the first one is being read. */
    private fun seed(): List<Long> = runBlocking {
        val db = GlobalContext.get().get<YomuDatabase>()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val titles = listOf("Solo Leveling", "The Beginning After the End", "Omniscient Reader", "Frieren", "Vinland Saga", "A Light Novel")
        val ids = titles.mapIndexed { i, t ->
            db.catalog().insert(
                CatalogItemEntity(
                    sourceId = MISSING_SOURCE, url = "/title/$i", title = t,
                    author = "Author ${i + 1}", artist = null, description = "A made-up title for the screenshots.",
                    genres = "Action,Fantasy", status = if (i == 4) 2 else 1, coverUrl = null, type = if (i == 5) "NOVEL" else "MANGA",
                    favorite = true, categoryId = null, addedAt = now - (30 - i) * day, lastUpdateAt = now - i * day, initialized = true,
                ),
            )
        }
        ids.take(3).forEachIndexed { t, id ->
            val chapters = 24 + t * 6
            val read = 8 + t * 5
            db.catalog().insertChapters(
                (1..chapters).map { n ->
                    ChapterEntity(
                        itemId = id, url = "/t$t/c$n", name = "Chapter $n", number = n.toFloat(), scanlator = "Alpha Group",
                        uploadDate = now - (chapters - n) * day, read = n <= read, bookmarked = false, lastPageRead = 0,
                        downloaded = false, localPath = null, isNew = n > chapters - 2, sourceOrder = chapters - n, fetchedAt = now,
                        lastReadAt = if (n <= read) now - (t + 1) * 3_600_000L - (read - n) * 60_000L else null,
                    )
                },
            )
        }
        ids
    }

    @Composable
    private fun Library() {
        val catalog = koinViewModel<CatalogHomeViewModel>()
        LibraryScreen(
            viewModel = koinViewModel(), onOpenBook = {}, onOpenDetails = {}, onFindBooksOnline = {}, onOpenSettings = {},
            online = OnlineLibrary { snackbar, query -> OnlineLibraryPane(catalog, snackbar, query, onOpenTitle = {}, onBrowse = {}) },
        )
    }

    /** The bug of 1.4: with followed online titles and no books the library said it was empty. */
    @Test
    fun libraryWithOnlineTitlesOnly() {
        seed()
        compose.setContent { YomuTheme(dark) { Library() } }
        awaitText("No books from your device yet")
        awaitText("Solo Leveling")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithText("Your library is empty", substring = true).assertCountEquals(0)
        shot("50_library_online_only")
    }

    @Test
    fun userTabWithOnlineTitles() {
        val ids = seed()
        runBlocking {
            val tabs = GlobalContext.get().get<LibraryTabs>()
            val weekend = tabs.create("Weekend")
            tabs.setOnline(ids.take(3), weekend, member = true)
            tabs.create("Finished")
        }
        compose.setContent { YomuTheme(dark) { Library() } }
        awaitText("Weekend")
        compose.onAllNodesWithText("Weekend", substring = true)[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        awaitText("Omniscient Reader")
        compose.onAllNodesWithText("Frieren").assertCountEquals(0)
        shot("51_library_user_tab")
    }

    @Test
    fun libraryFilterSheet() {
        seed()
        compose.setContent { YomuTheme(dark) { Library() } }
        awaitText("Solo Leveling")
        compose.onAllNodesWithText("Filter")[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        awaitText("Status")
        shotScreen("52_library_filters")
    }

    @Test
    fun appearanceSettings() {
        compose.setContent {
            YomuTheme(dark) { SettingsScreen(viewModel = koinViewModel(), links = SettingsLinks(), initialSection = SettingsSection.APPEARANCE) }
        }
        awaitText("Accent color")
        compose.mainClock.advanceTimeBy(1_000)
        shot("53_settings_appearance")
    }

    /** Another accent in the light theme: the whole palette follows it. */
    @Test
    fun appearanceSettingsLightAccent() {
        val settings = GlobalContext.get().get<AppSettings>()
        runBlocking {
            settings.themeMode.set("LIGHT")
            settings.accent.set("PINK")
        }
        try {
            compose.setContent {
                YomuTheme(ThemeConfig(mode = ThemeMode.LIGHT, accent = Accent.PINK)) {
                    SettingsScreen(viewModel = koinViewModel(), links = SettingsLinks(), initialSection = SettingsSection.APPEARANCE)
                }
            }
            awaitText("Accent color")
            compose.mainClock.advanceTimeBy(1_000)
            shot("54_settings_appearance_light")
        } finally {
            runBlocking {
                settings.themeMode.set("DARK")
                settings.accent.set("YOMU")
            }
        }
    }

    private companion object {
        const val MISSING_SOURCE = 987_654_321L
    }
}
