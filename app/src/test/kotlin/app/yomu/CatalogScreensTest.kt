package app.yomu

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.yomu.core.database.CatalogItemEntity
import app.yomu.core.database.ChapterEntity
import app.yomu.core.database.DownloadTaskEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.domain.model.DownloadStatus
import app.yomu.feature.catalog.CatalogHomeViewModel
import app.yomu.feature.catalog.CatalogScreen
import app.yomu.feature.catalog.TitleScreen
import app.yomu.feature.downloads.DownloadsScreen
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
import org.koin.core.parameter.parametersOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screens of the catalog added in 1.3 (chapter sort and filters, download menu and selection, the download queue and
 * the catalog library filters), rendered with data written straight into the database: nothing goes to the network.
 * Screenshots go to build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CatalogScreensTest {
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

    /** Whole window, including bottom sheets and menus (they live in their own windows). */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shotScreen(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    /** A followed title of a source that is not installed (so no refresh can reach the network) with 12 chapters. */
    private fun seed(): Long = runBlocking {
        val db = GlobalContext.get().get<YomuDatabase>()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val id = db.catalog().insert(
            CatalogItemEntity(
                sourceId = MISSING_SOURCE, url = "/title/smoke", title = "Smoke Manga: a Title Long Enough to Wrap",
                author = "Test Author", artist = null, description = "A made-up title for screenshots of the catalog screens.",
                genres = "Action,Comedy,Slice of Life", status = 1, coverUrl = null, type = "MANGA", favorite = true, categoryId = null,
                addedAt = now - 30 * day, lastUpdateAt = now - day, initialized = true,
            ),
        )
        val chapters = (1..12).map { n ->
            ChapterEntity(
                itemId = id, url = "/c$n", name = "Chapter $n", number = n.toFloat(), scanlator = if (n % 4 == 0) "Beta Scans" else "Alpha Group",
                uploadDate = now - (12 - n) * day, read = n <= 4, bookmarked = n == 6, lastPageRead = if (n == 5) 7 else 0,
                downloaded = n == 5 || n == 6, localPath = null, isNew = false, sourceOrder = 12 - n, fetchedAt = now,
                lastReadAt = if (n <= 4) now - n * 3_600_000L else null,
            )
        }
        db.catalog().insertChapters(chapters)
        val byNumber = db.catalog().chapters(id).associateBy { it.number.toInt() }
        fun task(n: Int, status: DownloadStatus, done: Int = 0, error: String? = null, attempts: Int = 0) = DownloadTaskEntity(
            kind = "CHAPTER", title = "Smoke Manga: a Title Long Enough to Wrap — Chapter $n", url = "/c$n", headersJson = null,
            status = status.name, bytesDownloaded = 0, bytesTotal = 0, pagesDone = done, pagesTotal = if (done > 0) 24 else 0,
            error = error, chapterId = byNumber.getValue(n).id, itemId = id, sourceId = MISSING_SOURCE, destPath = null, mimeType = null,
            createdAt = now + n, updatedAt = now + n, attempts = attempts,
        )
        listOf(
            task(7, DownloadStatus.RUNNING, done = 9),
            task(8, DownloadStatus.QUEUED),
            task(9, DownloadStatus.PAUSED, error = "Not enough storage space"),
            task(10, DownloadStatus.FAILED, error = "The site answered with an error (HTTP 503)", attempts = 3),
            task(5, DownloadStatus.DONE),
        ).forEach { db.downloads().insert(it) }
        id
    }

    private fun showTitle(id: Long) = compose.setContent {
        YomuTheme {
            TitleScreen(
                viewModel = koinViewModel { parametersOf(id) },
                onBack = {}, onReadChapter = {}, onMigrate = { _, _ -> }, onOpenWebView = {}, onOpenDownloads = {},
            )
        }
    }

    /** The node of [description] nearest to the top of the screen (the app bar's button rather than a list row's). */
    private fun topmost(description: String): SemanticsNodeInteraction {
        val id = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().minBy { it.boundsInRoot.top }.id
        return compose.onNode(SemanticsMatcher("node $id") { it.id == id })
    }

    @Test
    fun titleChaptersAndSortSheet() {
        showTitle(seed())
        awaitText("Chapter 12")
        compose.mainClock.advanceTimeBy(1_000)
        // The source of the seeded title is not installed: the notice offers a migration.
        compose.onNodeWithText("Migrate").assertExists()
        shot("30_title_chapters")

        compose.onAllNodes(hasContentDescription("Sort and filter"))[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        shotScreen("31_title_sort_filter")
    }

    @Test
    fun titleDownloadMenu() {
        showTitle(seed())
        awaitText("Chapter 12")
        topmost("Download").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        shotScreen("32_title_download_menu")
    }

    @Test
    fun titleSelection() {
        showTitle(seed())
        awaitText("Chapter 12")
        compose.onAllNodesWithText("Chapter 11")[0].performTouchInput { longClick() }
        compose.onAllNodesWithText("Chapter 12")[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        shot("33_title_selection")
    }

    @Test
    fun downloadQueue() {
        seed()
        compose.setContent { YomuTheme { DownloadsScreen(koinViewModel(), onBack = {}) } }
        awaitText("Chapter 8")
        compose.mainClock.advanceTimeBy(1_000)
        shot("34_downloads")
    }

    @Test
    fun catalogLibraryAndFilters() {
        seed()
        compose.setContent {
            YomuTheme {
                CatalogScreen(
                    viewModel = koinViewModel<CatalogHomeViewModel>(), onOpenSource = {}, onOpenTitle = {}, onReadChapter = {},
                    onGlobalSearch = {}, onOpenExtensions = {}, onOpenDownloads = {},
                )
            }
        }
        compose.onAllNodesWithText("Library")[0].performClick()
        awaitText("Smoke Manga")
        compose.mainClock.advanceTimeBy(1_000)
        shot("35_catalog_library")
        compose.onAllNodesWithText("Filter")[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        shotScreen("36_catalog_library_filters")
    }

    private companion object {
        const val MISSING_SOURCE = 987_654_321L
    }
}
