package app.yomu

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import app.yomu.core.common.AppError
import app.yomu.core.datastore.AppSettings
import app.yomu.core.ui.components.FloatingNavBar
import app.yomu.core.ui.components.NavTab
import app.yomu.core.ui.components.item
import app.yomu.core.ui.theme.ThemeConfig
import app.yomu.core.ui.theme.ThemeMode
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.feature.catalog.ActiveFilter
import app.yomu.feature.catalog.BrowseModel
import app.yomu.feature.catalog.BrowseTab
import app.yomu.feature.catalog.CatalogEvent
import app.yomu.feature.catalog.SearchResults
import app.yomu.feature.catalog.SourceBrowseScreen
import app.yomu.feature.settings.SettingsLinks
import app.yomu.feature.settings.SettingsScreen
import app.yomu.feature.settings.SettingsSection
import app.yomu.source.Filter
import app.yomu.source.FilterList
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
 * Screens changed in 1.6: the navigation bar animation (frame by frame), the navigation bar settings, About, and the
 * source screen with its own Popular / Latest / Search pages. The source screen runs on a fake model: no network.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class Release160ScreensTest {
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

    @OptIn(ExperimentalTestApi::class)
    private fun awaitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    private fun tab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /**
     * Both bar styles (icons only, and labels everywhere) switching Library → Catalog → Settings, one frame every
     * [FRAME_MS]: the frames go to build/outputs/roborazzi/nav/ (joined into a GIF and a strip outside the test).
     */
    @Test
    fun navBarAnimationFrames() {
        var selected by mutableIntStateOf(0)
        compose.setContent {
            YomuTheme(dark) {
                val items = NavTab.entries.map { it.item() }
                Column(
                    Modifier.testTag(BAR).fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FloatingNavBar(items, selected, showLabels = false, onSelect = { selected = it }, windowInsets = WindowInsets(0))
                    FloatingNavBar(items, selected, showLabels = true, onSelect = { selected = it }, windowInsets = WindowInsets(0))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        var frame = 0
        fun capture() = compose.onNodeWithTag(BAR).captureRoboImage("build/outputs/roborazzi/nav/frame_%03d.png".format(frame++))
        capture()
        for (target in listOf("Catalog", "Settings", "Library")) {
            compose.onAllNodes(tab(target))[0].performClick()
            repeat(FRAMES) {
                compose.mainClock.advanceTimeBy(FRAME_MS)
                capture()
            }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        check(selected == 0) { "the bar should be back on Library, is on $selected" }
    }

    /** A custom order (Catalog first) with the dictionary switched off. */
    @Test
    fun navigationSettings() {
        val settings = GlobalContext.get().get<AppSettings>()
        runBlocking {
            settings.navTabOrder.set(listOf("CATALOG", "LIBRARY", "HISTORY", "DICTIONARY", "SETTINGS"))
            settings.navTabsHidden.set(setOf("DICTIONARY"))
        }
        try {
            compose.setContent {
                YomuTheme(dark) { SettingsScreen(viewModel = koinViewModel(), links = SettingsLinks(), initialSection = SettingsSection.NAVIGATION) }
            }
            awaitText("Opens at start")
            awaitText("Hidden")
            compose.mainClock.advanceTimeBy(1_000)
            shot("60_settings_navigation")
        } finally {
            runBlocking {
                settings.navTabOrder.set(emptyList())
                settings.navTabsHidden.set(emptySet())
            }
        }
    }

    @Test
    fun about() {
        compose.setContent {
            YomuTheme(dark) { SettingsScreen(viewModel = koinViewModel(), links = SettingsLinks(), initialSection = SettingsSection.ABOUT) }
        }
        awaitText("Made by Shinoame")
        awaitText("Check for updates")
        compose.mainClock.advanceTimeBy(1_000)
        shot("61_settings_about")
    }

    private fun browse(model: FakeBrowse) {
        compose.setContent {
            YomuTheme(dark) { SourceBrowseScreen(model, onBack = {}, onOpenTitle = {}, onOpenNavigation = { _, _, _ -> }, onOpenWebView = {}) }
        }
    }

    @Test
    fun browsePopular() {
        browse(FakeBrowse())
        awaitText(POPULAR[0])
        compose.mainClock.advanceTimeBy(1_000)
        shot("62_browse_popular")
    }

    /** The bug of 1.5: "Latest" kept showing the popular titles. Now it is a page of its own with its own list. */
    @Test
    fun browseLatestHasItsOwnList() {
        browse(FakeBrowse())
        awaitText(POPULAR[0])
        compose.onAllNodes(tab("Latest"))[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        awaitText(LATEST[0])
        compose.onAllNodesWithText(POPULAR[0]).assertCountEquals(0)
        shot("63_browse_latest")
    }

    /** A failing "Latest" says why (with WebView for a verification page) instead of quietly showing other titles. */
    @Test
    fun browseLatestError() {
        browse(FakeBrowse(initialTab = BrowseTab.LATEST, latestError = AppError.NeedsWebView("https://weebcentral.com")))
        awaitText("Retry")
        compose.mainClock.advanceTimeBy(1_000)
        shot("64_browse_latest_error")
    }

    @Test
    fun browseSearchPanel() {
        browse(FakeBrowse(initialTab = BrowseTab.SEARCH))
        awaitText("Show results")
        awaitText("Recent searches")
        compose.mainClock.advanceTimeBy(1_000)
        shot("65_browse_search_filters")
    }

    @Test
    fun browseSearchResults() {
        val model = FakeBrowse(initialTab = BrowseTab.SEARCH)
        browse(model)
        awaitText("Show results")
        model.query.value = "isekai"
        compose.onAllNodesWithText("Show results")[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        awaitText(RESULTS[0])
        compose.onAllNodesWithText("Show results").assertCountEquals(0)
        shot("66_browse_search_results")
    }

    /** What a source screen needs, without a source: three fixed lists and filters like Weeb Central's. */
    private class FakeBrowse(
        override val initialTab: BrowseTab = BrowseTab.POPULAR,
        latestError: Throwable? = null,
    ) : BrowseModel {
        override val sourceId = 1L
        override val exists = true
        override val title = "Weeb Central"
        override val isNavigation = false
        override val supportsSearch = true
        override val content = SourceContent.MANGA
        override val webUrl = "https://weebcentral.com"
        override val tabs = listOf(BrowseTab.POPULAR, BrowseTab.LATEST, BrowseTab.SEARCH)
        override val popular: Flow<PagingData<SItem>> = flowOf(loaded(POPULAR.items("p")))
        override val latest: Flow<PagingData<SItem>> = flowOf(
            if (latestError == null) {
                loaded(LATEST.items("l"))
            } else {
                PagingData.from(emptyList(), LoadStates(LoadState.Error(latestError), LoadState.NotLoading(true), LoadState.NotLoading(true)))
            },
        )
        override val results = MutableStateFlow<SearchResults?>(null)
        override val query = MutableStateFlow("")
        override val filters = MutableStateFlow(
            FilterList(
                Filter.Sort("Sort", listOf("Best match", "Alphabet", "Popularity", "Subscribers", "Recently added", "Latest updates"), Filter.Sort.Selection(2, false)),
                Filter.Select("Status", listOf("Any", "Ongoing", "Complete", "Hiatus", "Canceled")),
                Filter.Select("Type", listOf("Any", "Manga", "Manhwa", "Manhua", "OEL"), state = 2),
                Filter.CheckBox("Official translation"),
                Filter.Group(
                    "Tags",
                    listOf(
                        Filter.TriState("Action", Filter.TriState.INCLUDE),
                        Filter.TriState("Adventure"),
                        Filter.TriState("Comedy"),
                        Filter.TriState("Drama", Filter.TriState.EXCLUDE),
                        Filter.TriState("Fantasy", Filter.TriState.INCLUDE),
                        Filter.TriState("Isekai"),
                        Filter.TriState("Romance"),
                    ),
                ),
            ),
        )
        override val filterVersion = MutableStateFlow(0)
        override val activeFilters = MutableStateFlow(
            listOf(
                ActiveFilter(listOf(2), "Manhwa", ActiveFilter.Kind.VALUE),
                ActiveFilter(listOf(4, 0), "Action", ActiveFilter.Kind.INCLUDE),
                ActiveFilter(listOf(4, 3), "Drama", ActiveFilter.Kind.EXCLUDE),
                ActiveFilter(listOf(4, 4), "Fantasy", ActiveFilter.Kind.INCLUDE),
            ),
        )
        override val searchHistory = MutableStateFlow(listOf("solo leveling", "frieren", "omniscient reader"))
        override val inLibrary = MutableStateFlow(setOf("/p/1"))
        override val events: Flow<CatalogEvent> = emptyFlow()
        private var searches = 0

        override fun submit() {
            results.value = SearchResults(++searches, query.value, flowOf(loaded(RESULTS.items("s"))))
        }

        override fun searchFromHistory(q: String) {
            query.value = q
            submit()
        }

        override fun clearSearchHistory() {
            searchHistory.value = emptyList()
        }

        override fun onFilterChanged() {
            filterVersion.value++
        }

        override fun resetFilters() = Unit
        override fun resetFilter(path: List<Int>) = Unit
        override fun clearFilters() = Unit
        override fun open(item: SItem) = Unit
        override fun toggleLibrary(item: SItem) = Unit
    }

    private companion object {
        const val BAR = "bars"
        const val FRAMES = 14
        const val FRAME_MS = 32L
        val POPULAR = listOf("Solo Leveling", "Omniscient Reader", "The Beginning After the End", "Frieren", "Vinland Saga", "Blue Lock", "Chainsaw Man", "Dandadan", "Kagurabachi")
        val LATEST = listOf("Sakamoto Days", "Kaiju No. 8", "Witch Hat Atelier", "Akane-banashi", "Undead Unluck", "Ichi the Witch", "Nue's Exorcist", "Gachiakuta", "Ruri Rocks")
        val RESULTS = listOf("Mushoku Tensei", "Re:Monster", "Overgeared", "The Greatest Estate Developer", "Trash of the Count's Family", "Leveling with the Gods")

        fun List<String>.items(prefix: String) = mapIndexed { i, t -> SItem(url = "/$prefix/$i", title = t, author = "Author ${i + 1}") }

        /** One loaded page, the last one: what a pager reports after the first load of a short list. */
        fun loaded(items: List<SItem>) =
            PagingData.from(items, LoadStates(LoadState.NotLoading(false), LoadState.NotLoading(true), LoadState.NotLoading(true)))
    }
}
