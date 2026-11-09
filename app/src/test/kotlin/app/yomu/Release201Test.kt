package app.yomu

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.yomu.core.database.BookEntity
import app.yomu.core.database.CatalogItemEntity
import app.yomu.core.database.TrackEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.ui.theme.ThemeConfig
import app.yomu.core.ui.theme.ThemeMode
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.data.tracking.Tracker
import app.yomu.data.tracking.TrackerException
import app.yomu.data.tracking.TrackerKeys
import app.yomu.data.tracking.TrackerManager
import app.yomu.data.tracking.TrackerStore
import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import app.yomu.profile.ProfileScreen
import app.yomu.feature.library.BookDetailsScreen
import org.koin.core.parameter.parametersOf
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * 2.0.1: the profile's Tracking and Downloads tabs, and refreshing every tracked entry at once. Nothing goes to the
 * network: no tracker is signed in for the screens and the fake trackers answer from memory.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class Release201Test {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dark = ThemeConfig(mode = ThemeMode.DARK)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val db: YomuDatabase get() = GlobalContext.get().get()

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun screen(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    /** A tab of the profile's scrollable tab row (the last ones may be off screen). */
    private fun clickTab(label: String) {
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** The overview is a lazy list: scrolls to the tracking entry (the one with a scroll action) and opens it. */
    private fun openTracking() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Accounts and tracked titles"))
        compose.onNode(hasText("Accounts and tracked titles")).performClick()
        compose.waitForIdle()
    }

    private fun seedTitle(title: String): Long = runBlocking {
        val now = System.currentTimeMillis()
        db.catalog().insert(
            CatalogItemEntity(
                sourceId = 987_654_321L, url = "/title/$title", title = title, author = "Test Author", artist = null,
                description = null, genres = "Action", status = 1, coverUrl = null, type = "MANGA", favorite = true,
                categoryId = null, addedAt = now - 86_400_000L, lastUpdateAt = now, initialized = true,
            ),
        )
    }

    private fun track(
        itemId: Long,
        service: TrackService,
        remoteId: Long,
        status: TrackStatus,
        read: Float,
        total: Int = 0,
        score: Float = 0f,
        pending: Boolean = false,
        updatedAt: Long = System.currentTimeMillis(),
    ) = TrackEntity(
        itemId = itemId, service = service.id, remoteId = remoteId, title = "Entry $remoteId", url = "${service.site}/manga/$remoteId",
        status = status.code, score = score, lastChapterRead = read, totalChapters = total, updatedAt = updatedAt, pending = pending,
    )

    @Test
    fun profileTrackingPage() {
        val berserk = seedTitle("Berserk")
        val frieren = seedTitle("Frieren: Beyond Journey's End")
        runBlocking {
            db.tracks().insert(track(berserk, TrackService.ANILIST, 30002, TrackStatus.READING, 120f, score = 9f, pending = true))
            db.tracks().insert(track(berserk, TrackService.SHIKIMORI, 2, TrackStatus.READING, 118f, score = 9f))
            db.tracks().insert(track(frieren, TrackService.MYANIMELIST, 126287, TrackStatus.COMPLETED, 140f, 140, 10f, updatedAt = 1_000))
        }
        // Signed in to AniList only (nothing is sent: the screen only reads the accounts).
        val store: TrackerStore = GlobalContext.get().get()
        store.saveTokens(TrackService.ANILIST, "token", null, 0)
        store.saveUser(TrackService.ANILIST, 1, "reader")
        compose.setContent {
            YomuTheme(dark) {
                ProfileScreen(
                    viewModel = koinViewModel(), historyViewModel = koinViewModel(), statsViewModel = koinViewModel(),
                    onOpenBook = {}, onBookDetails = {}, onReadChapter = {}, onOpenTitle = {}, onOpenSettings = {},
                )
            }
        }
        openTracking()
        awaitText("Accounts")
        awaitText("1 change not sent yet")
        awaitText("reader")
        awaitText("Frieren")
        // Status filters: every title, then the ones read and the completed one.
        awaitText("All · 2")
        screen("210_profile_tracking")
    }

    @Test
    fun profileShowsLocalTrackedBookAndOpensItsDetails() {
        var openedBook: Long? = null
        var openedTitle: Long? = null
        val bookId = runBlocking {
            val id = db.books().insert(BookEntity(title = "Local Manga", sortTitle = "Local Manga", authorsText = "", seriesName = null,
                seriesId = null, seriesIndex = null, language = null, description = null, publisher = null, format = "CBZ", uri = "file:///local-manga.cbz",
                isLinked = false, fileSize = 10, fileHash = "local-manga", coverPath = null, status = "READING", isManga = true,
                addedAt = 1, lastReadAt = null, progress = .5f, readerSettingsJson = null))
            db.tracks().insert(TrackEntity(itemId = null, bookId = id, service = TrackService.ANILIST.id, remoteId = 99,
                title = "Series", url = "", status = TrackStatus.READING.code, lastChapterRead = 5f))
            id
        }
        compose.setContent {
            YomuTheme(dark) {
                ProfileScreen(viewModel = koinViewModel(), historyViewModel = koinViewModel(), statsViewModel = koinViewModel(),
                    onOpenBook = {}, onBookDetails = { openedBook = it }, onReadChapter = {}, onOpenTitle = { openedTitle = it }, onOpenSettings = {})
            }
        }
        openTracking()
        awaitText("Local Manga")
        compose.onNode(hasText("Local Manga")).performClick()
        compose.waitForIdle()
        assertEquals(bookId, openedBook); assertEquals(null, openedTitle)
    }

    @Test
    fun localBookHasTrackingSheetWithManualProgressHint() {
        val bookId = runBlocking {
            db.books().insert(BookEntity(title = "Local Example", sortTitle = "Local Example", authorsText = "", seriesName = null,
                seriesId = null, seriesIndex = null, language = null, description = null, publisher = null, format = "CBZ", uri = "file:///local-example.cbz",
                isLinked = false, fileSize = 10, fileHash = "local-example", coverPath = null, status = "READING", isManga = true,
                addedAt = 1, lastReadAt = null, progress = .5f, readerSettingsJson = null))
        }
        compose.setContent {
            YomuTheme(dark) {
                BookDetailsScreen(viewModel = koinViewModel { parametersOf(bookId) }, onBack = {}, onRead = {}, onOpenEditor = {}, onOpenAnnotations = {})
            }
        }
        awaitText("Tracking")
        compose.onNode(hasText("Tracking")).performScrollTo().performClick()
        awaitText("For local files, chapter numbers cannot be determined reliably")
    }

    /** A tracker that answers from memory. */
    private class FakeTracker(service: TrackService, store: TrackerStore, private val answer: (Long) -> TrackEntry?) :
        Tracker(service, store, TrackerKeys(), { OkHttpClient() }) {
        val saved = mutableListOf<TrackEntry>()
        override val redirectHost = "test-${service.id}"
        override fun authUrl() = "https://example.invalid/"
        override suspend fun finishLogin(redirect: String) = Unit
        override suspend fun search(query: String) = emptyList<TrackSearchResult>()
        override suspend fun find(remoteId: Long): TrackEntry? = answer(remoteId)
        override suspend fun save(entry: TrackEntry): TrackEntry = entry.also { saved += it }
        override suspend fun delete(entry: TrackEntry) = Unit
    }

    @Test
    fun refreshAllReadsEveryEntryAndKeepsGoingAfterAnError() = runBlocking {
        val id = seedTitle("Refreshed")
        val store = TrackerStore(context)
        TrackService.entries.forEach { store.saveTokens(it, "token", null, 0) }
        val old = 1_000L
        db.tracks().insert(track(id, TrackService.ANILIST, 1, TrackStatus.READING, 3f, updatedAt = old))
        db.tracks().insert(track(id, TrackService.SHIKIMORI, 2, TrackStatus.READING, 3f, updatedAt = old))
        db.tracks().insert(track(id, TrackService.MYANIMELIST, 3, TrackStatus.READING, 5f, pending = true, updatedAt = old))
        val anilist = FakeTracker(TrackService.ANILIST, store) { TrackEntry(TrackService.ANILIST, it, title = "Refreshed", url = "", status = TrackStatus.READING, lastChapterRead = 7f) }
        val shikimori = FakeTracker(TrackService.SHIKIMORI, store) { throw TrackerException("Shikimori: HTTP 503", code = 503) }
        val mal = FakeTracker(TrackService.MYANIMELIST, store) { error("a pending change is sent, not read") }
        val manager = TrackerManager(null, db, store, TrackerKeys(), { OkHttpClient() }, null, trackers = listOf(anilist, mal, shikimori))

        val (failed, error) = manager.refreshAll()
        assertEquals("only Shikimori failed", 1, failed)
        assertTrue(error is TrackerException)
        val tracks = db.tracks().forItem(id).associateBy { it.service }
        val a = tracks.getValue(TrackService.ANILIST.id)
        assertEquals(7f, a.lastChapterRead)
        assertTrue("a change counts as a change", a.updatedAt > old)
        assertEquals(old, tracks.getValue(TrackService.SHIKIMORI.id).updatedAt)
        assertFalse("the pending change was sent", tracks.getValue(TrackService.MYANIMELIST.id).pending)
        assertEquals(listOf(5f), mal.saved.map { it.lastChapterRead })

        // Nothing changed on the service: the entry keeps its date (the tab lists the latest changes first).
        manager.refreshAll()
        assertEquals(a.updatedAt, db.tracks().forItem(id).first { it.service == TrackService.ANILIST.id }.updatedAt)
    }
}
