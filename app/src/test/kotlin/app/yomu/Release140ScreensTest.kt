package app.yomu

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.yomu.core.database.CatalogItemEntity
import app.yomu.core.database.ChapterEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.feature.catalog.ActiveFiltersRow
import app.yomu.feature.catalog.CatalogHomeViewModel
import app.yomu.feature.catalog.OnlineLibraryPane
import app.yomu.feature.catalog.SourceFilterState
import app.yomu.feature.catalog.SourceFiltersContent
import app.yomu.feature.catalog.TitleScreen
import app.yomu.feature.library.LibraryScreen
import app.yomu.feature.library.OnlineLibrary
import app.yomu.feature.settings.SettingsLinks
import app.yomu.feature.settings.SettingsScreen
import app.yomu.feature.settings.SettingsSection
import app.yomu.source.Filter
import app.yomu.source.FilterList
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screens changed in 1.4: chapter numbers on the title page, the online library inside the library, the source
 * filters and the network settings. Data goes straight into the database; nothing goes to the network.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class Release140ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    /** Followed titles of a source that is not installed (no refresh can reach the network); the first has [chapters] chapters. */
    private fun seed(chapters: Int = 30): Long = runBlocking {
        val db = GlobalContext.get().get<YomuDatabase>()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val titles = listOf("Smoke Manga: the Long Road", "Second Title", "Third One", "A Light Novel")
        val ids = titles.mapIndexed { i, t ->
            db.catalog().insert(
                CatalogItemEntity(
                    sourceId = MISSING_SOURCE, url = "/title/$i", title = t,
                    author = "Author ${i + 1}", artist = null, description = "A made-up title for the screenshots.",
                    genres = "Action,Comedy", status = 1, coverUrl = null, type = if (i == 3) "NOVEL" else "MANGA", favorite = true,
                    categoryId = null, addedAt = now - (30 - i) * day, lastUpdateAt = now - day, initialized = true,
                ),
            )
        }
        val id = ids.first()
        db.catalog().insertChapters(
            (1..chapters).map { n ->
                ChapterEntity(
                    itemId = id, url = "/c$n", name = "Chapter $n", number = n.toFloat(), scanlator = "Alpha Group",
                    uploadDate = now - (chapters - n) * day, read = n <= 8, bookmarked = false, lastPageRead = 0,
                    downloaded = n in 9..12, localPath = null, isNew = n > chapters - 2, sourceOrder = chapters - n, fetchedAt = now,
                    lastReadAt = if (n <= 8) now - n * 3_600_000L else null,
                )
            },
        )
        id
    }

    @Test
    fun titleChapterNumbers() {
        val id = seed()
        compose.setContent {
            YomuTheme {
                TitleScreen(
                    viewModel = koinViewModel { parametersOf(id) },
                    onBack = {}, onReadChapter = {}, onMigrate = { _, _ -> }, onOpenWebView = {}, onOpenDownloads = {},
                )
            }
        }
        awaitText("Chapter 30")
        compose.mainClock.advanceTimeBy(1_000)
        shot("40_title_chapter_numbers")
    }

    @Test
    fun libraryOnlineShelfAndTab() {
        seed(chapters = 3)
        compose.setContent {
            YomuTheme {
                val catalog = koinViewModel<CatalogHomeViewModel>()
                LibraryScreen(
                    viewModel = koinViewModel(), onOpenBook = {}, onOpenDetails = {}, onFindBooksOnline = {}, onOpenSettings = {},
                    online = OnlineLibrary { snackbar, query -> OnlineLibraryPane(catalog, snackbar, query, onOpenTitle = {}, onBrowse = {}) },
                )
            }
        }
        awaitText("Second Title")
        compose.mainClock.advanceTimeBy(1_000)
        shot("41_library_online_shelf")
        compose.onAllNodesWithText("Online (4)")[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        awaitText("A Light Novel")
        shot("42_library_online_tab")
    }

    /** Filters shaped like the MangaDex extension's. */
    private fun mangaDexLike() = FilterList(
        Filter.CheckBox("Has available chapters", true),
        Filter.Group("Original language", listOf("Japanese", "Chinese", "Korean", "English").map { Filter.CheckBox(it) }),
        Filter.Group("Content rating", listOf("Safe", "Suggestive", "Erotica", "Pornographic").mapIndexed { i, n -> Filter.CheckBox(n, i < 2) }),
        Filter.Group("Status", listOf("Ongoing", "Completed", "Hiatus", "Cancelled").map { Filter.CheckBox(it) }),
        Filter.Sort("Sort", listOf("Alphabetic", "Chapter uploaded at", "Number of follows", "Content created at", "Year", "Rating"), Filter.Sort.Selection(2, false)),
        Filter.Group("Tags mode", listOf(Filter.Select("Included tags mode", listOf("And", "Or")), Filter.Select("Excluded tags mode", listOf("And", "Or"), 1))),
        Filter.Group(
            "Genre",
            listOf(
                "Action", "Adventure", "Comedy", "Crime", "Drama", "Fantasy", "Historical", "Horror", "Isekai", "Mecha", "Medical",
                "Mystery", "Philosophical", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Thriller", "Tragedy",
            ).map { Filter.TriState(it) },
        ),
        Filter.Select("Created within last", listOf("Any time", "1 day", "1 week", "1 month", "3 months", "6 months", "1 year")),
        Filter.Text("Author or artist"),
    )

    @Test
    fun sourceFilters() {
        val filters = mangaDexLike()
        val defaults = mangaDexLike()
        ((filters[3] as Filter.Group).filters[0] as Filter.CheckBox).state = true
        (filters[4] as Filter.Sort).state = Filter.Sort.Selection(5, false)
        ((filters[6] as Filter.Group).filters[0] as Filter.TriState).state = Filter.TriState.INCLUDE
        ((filters[6] as Filter.Group).filters[14] as Filter.TriState).state = Filter.TriState.EXCLUDE
        compose.setContent {
            YomuTheme {
                Surface(Modifier.fillMaxSize()) {
                    var version by remember { mutableIntStateOf(0) }
                    val active = remember(version) { SourceFilterState.active(filters, defaults) }
                    Column {
                        ActiveFiltersRow(active, onEdit = {}, onRemove = {}, onClear = {})
                        SourceFiltersContent(
                            filters = filters,
                            version = version,
                            onChanged = { version++ },
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        awaitText("Genre")
        compose.mainClock.advanceTimeBy(1_000)
        shot("43_source_filters")
        // Folded groups show what is chosen in them.
        compose.onAllNodesWithText("Original language")[0].performClick()
        compose.onAllNodesWithText("Content rating")[0].performClick()
        compose.onAllNodesWithText("Status")[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        shot("44_source_filters_folded")
        // A long group: a search field and the tri-state chips.
        compose.onAllNodesWithText("Sort")[0].performClick()
        compose.onAllNodesWithText("Tags mode")[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithText("Genre")[0].performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        shot("47_source_filters_genres")
    }

    @Test
    fun networkSettings() {
        compose.setContent {
            YomuTheme { SettingsScreen(viewModel = koinViewModel(), links = SettingsLinks(), initialSection = SettingsSection.NETWORK) }
        }
        awaitText("Save mobile data")
        compose.mainClock.advanceTimeBy(1_000)
        shot("45_settings_network")
    }

    @Test
    fun settingsWithIcons() {
        compose.setContent {
            YomuTheme { SettingsScreen(viewModel = koinViewModel(), links = SettingsLinks(), initialSection = SettingsSection.READER) }
        }
        compose.mainClock.advanceTimeBy(1_000)
        shot("46_settings_reader_icons")
    }

    private companion object {
        const val MISSING_SOURCE = 987_654_321L
    }
}
