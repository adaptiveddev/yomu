package app.yomu

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.yomu.core.database.BookEntity
import app.yomu.core.database.CatalogItemEntity
import app.yomu.core.database.ChapterEntity
import app.yomu.core.database.TrackEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.core.ui.components.QuickActionMenu
import app.yomu.core.ui.components.QuickMenuItem
import app.yomu.core.ui.theme.ThemeConfig
import app.yomu.core.ui.theme.ThemeMode
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import app.yomu.feature.catalog.TitleScreen
import app.yomu.feature.settings.AppUpdates
import app.yomu.feature.settings.SettingsLinks
import app.yomu.feature.settings.SettingsScreen
import app.yomu.feature.settings.SettingsSection
import app.yomu.feature.settings.SettingsViewModel
import app.yomu.widget.ContinueReadingWidget
import app.yomu.widget.ContinueWidgets
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2.0.0: the new settings sections (tracking, sync, long-press menus, the Mihon import), the long-press menus
 * themselves, the tracking sheet of a title and the "Continue reading" widget. Nothing goes to the network: no
 * tracker is signed in and the title's source is not installed. Screenshots: `recordRoborazziDebug`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class Release200Test {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dark = ThemeConfig(mode = ThemeMode.DARK)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val settings: AppSettings get() = GlobalContext.get().get()
    private val db: YomuDatabase get() = GlobalContext.get().get()

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun screen(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    private fun settingsScreen(section: SettingsSection) {
        val koin = GlobalContext.get()
        val vm = SettingsViewModel(
            context, settings, koin.get(), koin.get(), koin.get(), koin.get(), AppUpdates(context, settings, fetch = { null }),
            koin.get(), koin.get(), koin.get(),
        )
        compose.setContent {
            YomuTheme(dark) { SettingsScreen(viewModel = vm, links = SettingsLinks(), initialSection = section) }
        }
    }

    @Test
    fun trackingSettings() {
        settingsScreen(SettingsSection.TRACKING)
        awaitText("Shikimori")
        TrackService.entries.forEach { awaitText(it.displayName) }
        shot("200_settings_tracking")
    }

    @Test
    fun syncSettings() {
        settingsScreen(SettingsSection.SYNC)
        awaitText(context.getString(app.yomu.feature.settings.R.string.set_sync_deletions))
        shot("201_settings_sync")
    }

    @Test
    fun quickActionSettings() {
        settingsScreen(SettingsSection.QUICK_ACTIONS)
        awaitText(context.getString(app.yomu.feature.settings.R.string.set_page_menu_style))
        shot("202_settings_quick_actions")
    }

    @Test
    fun backupHasTheMihonImport() {
        settingsScreen(SettingsSection.BACKUP)
        awaitText(context.getString(app.yomu.feature.settings.R.string.set_mihon_import))
        shot("203_settings_backup")
    }

    @Test
    fun pdfHighlightsSwitch() {
        settingsScreen(SettingsSection.MANGA)
        awaitText(context.getString(app.yomu.feature.settings.R.string.set_pdf_write_annotations))
        shot("204_settings_manga")
    }

    private fun menuItems(): List<QuickMenuItem> = listOf(
        QuickMenuItem("save", Icons.Outlined.Image, "Save page") {},
        QuickMenuItem("translate", Icons.Outlined.Translate, "Translate page") {},
        QuickMenuItem("share", Icons.Outlined.Share, "Share") {},
        QuickMenuItem("bookmark", Icons.Outlined.Bookmark, "Bookmark") {},
        QuickMenuItem("copy", Icons.Outlined.ContentCopy, "Copy text") {},
        QuickMenuItem("search", Icons.Outlined.Search, "Search") {},
    )

    @Test
    fun quickMenus() {
        var radial by mutableStateOf(true)
        var closed = 0
        compose.setContent {
            YomuTheme(dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    QuickActionMenu(radial = radial, anchor = Offset(560f, 1300f), items = menuItems(), onDismiss = { closed++ }, title = "Page 12")
                }
            }
        }
        awaitText("Save page")
        screen("205_quick_menu_radial")
        radial = false
        compose.waitForIdle()
        awaitText("Translate page")
        screen("206_quick_menu_list")
        compose.onAllNodesWithText("Translate page")[0].performClick()
        compose.waitForIdle()
        assertTrue("a tap on an item closes the menu", closed > 0)
    }

    /** A followed title of a source that is not installed (so no refresh can reach the network) with 10 chapters. */
    private fun seedTitle(title: String = "Tracked Manga"): Long = runBlocking {
        val now = System.currentTimeMillis()
        val id = db.catalog().insert(
            CatalogItemEntity(
                sourceId = MISSING_SOURCE, url = "/title/tracked", title = title, author = "Test Author", artist = null,
                description = "A made-up title.", genres = "Action", status = 1, coverUrl = null, type = "MANGA", favorite = true,
                categoryId = null, addedAt = now - 86_400_000L, lastUpdateAt = now, initialized = true,
            ),
        )
        db.catalog().insertChapters(
            (1..10).map { n ->
                ChapterEntity(
                    itemId = id, url = "/c$n", name = "Chapter $n", number = n.toFloat(), scanlator = null, uploadDate = now - (10 - n) * 1000L,
                    read = n <= 3, bookmarked = false, lastPageRead = 0, downloaded = false, localPath = null, isNew = false,
                    sourceOrder = 10 - n, fetchedAt = now, lastReadAt = if (n <= 3) now - (4 - n) * 1000L else null,
                )
            },
        )
        id
    }

    @Test
    fun titleTrackingSheet() {
        val id = seedTitle()
        runBlocking {
            db.tracks().insert(
                TrackEntity(
                    itemId = id, service = TrackService.ANILIST.id, remoteId = 30013, title = "Tracked Manga (AniList)",
                    url = "https://anilist.co/manga/30013", status = TrackStatus.READING.code, score = 8f, lastChapterRead = 3f,
                    totalChapters = 120, pending = true,
                ),
            )
        }
        compose.setContent {
            YomuTheme(dark) {
                TitleScreen(
                    viewModel = koinViewModel { parametersOf(id) },
                    onBack = {}, onReadChapter = {}, onMigrate = { _, _ -> }, onOpenWebView = {}, onOpenDownloads = {},
                )
            }
        }
        awaitText("Chapter 10")
        awaitText("Tracking · 1")
        compose.onAllNodesWithText("Tracking · 1")[0].performClick()
        awaitText("Tracked Manga (AniList)")
        awaitText("MyAnimeList")
        compose.mainClock.advanceTimeBy(1_000)
        screen("207_title_tracking")
    }

    @Test
    fun continueReadingWidget() = runBlocking {
        val now = System.currentTimeMillis()
        db.books().insert(
            BookEntity(
                title = "Widget Book", sortTitle = "widget book", authorsText = "Ann Author", seriesName = null, seriesId = null,
                seriesIndex = null, language = "en", description = null, publisher = null, format = "EPUB", uri = "file:///missing.epub",
                isLinked = false, fileSize = 1, fileHash = null, coverPath = null, status = "READING", isManga = false,
                addedAt = now - 100_000, lastReadAt = now - 60_000, progress = 0.42f, readerSettingsJson = null,
            ),
        )
        seedTitle("Online Title")
        val manager = AppWidgetManager.getInstance(context)
        val widget = shadowOf(manager).createWidget(ContinueReadingWidget::class.java, R.layout.widget_continue)
        ContinueWidgets.refresh(context)
        val view = shadowOf(manager).getViewFor(widget)
        fun text(id: Int) = view.findViewById<TextView>(id).text.toString()
        // The online title was read a minute later than the book.
        assertEquals("Online Title", text(R.id.widget_title_0))
        assertEquals("7 unread chapters", text(R.id.widget_subtitle_0))
        assertEquals("Widget Book", text(R.id.widget_title_1))
        assertEquals("Ann Author · 42%", text(R.id.widget_subtitle_1))
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_row_1).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_row_2).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_empty).visibility)
        ContinueWidgets.stop()
    }

    private companion object {
        const val MISSING_SOURCE = 987_654_321L
    }
}
