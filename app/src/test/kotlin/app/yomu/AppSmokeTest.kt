package app.yomu

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.activity.ComponentDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import app.yomu.data.source.BrokenReason
import app.yomu.data.source.ContentWarning
import app.yomu.data.source.ExtensionFormat
import app.yomu.data.source.ExtensionManager
import app.yomu.data.source.ExtensionStatus
import app.yomu.data.source.InstalledExtension
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import app.yomu.data.book.EpubBuilder
import app.yomu.data.book.EpubMeta
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.domain.repository.StatsRepository
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext
import app.yomu.feature.annotations.AnnotationsViewModel
import app.yomu.feature.catalog.BrowseArgs
import app.yomu.feature.catalog.CatalogHomeViewModel
import app.yomu.feature.catalog.ExtensionsViewModel
import app.yomu.feature.catalog.GlobalSearchViewModel
import app.yomu.feature.catalog.SearchArgs
import app.yomu.feature.catalog.SourceBrowseViewModel
import app.yomu.feature.catalog.TitleViewModel
import app.yomu.feature.dictionary.DictionariesViewModel
import app.yomu.feature.dictionary.DictionaryViewModel
import app.yomu.feature.dictionary.ReviewViewModel
import app.yomu.feature.dictionary.WordLookupViewModel
import app.yomu.feature.dictionary.WordsViewModel
import app.yomu.feature.downloads.DownloadsViewModel
import app.yomu.feature.editor.EditorViewModel
import app.yomu.feature.library.BookDetailsViewModel
import app.yomu.feature.library.LibraryViewModel
import app.yomu.feature.reader.DocxLayoutViewModel
import app.yomu.feature.reader.ReaderArgs
import app.yomu.feature.reader.ReaderViewModel
import app.yomu.feature.reader.ReadingStatsViewModel
import app.yomu.feature.settings.SettingsViewModel
import app.yomu.profile.ProfileTrackingViewModel
import app.yomu.profile.ProfileViewModel
import app.yomu.feature.translator.LanguagePacksViewModel
import app.yomu.feature.translator.TranslationViewModel
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.reflect.KClass
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Starts the real application (Koin graph, database, settings) and walks through onboarding and the top level
 * screens. Catches crashes on startup and missing dependency definitions; screenshots go to build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<YomuActivity>()

    @After
    fun tearDown() {
        stopKoin()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitText(text: String, timeout: Long = 20_000) {
        compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeout)
    }

    /** Item of the bottom navigation bar (the screens themselves may contain the same words). */
    private fun clickTab(label: String) {
        compose.onAllNodes(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).let { nodes ->
            val count = nodes.fetchSemanticsNodes().size
            nodes[count - 1].performClick()
        }
    }

    /** Runs the platform animations too (the ripples of the navigation bar are RippleDrawables on the main looper). */
    private fun settle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Every ViewModel of every screen can be created from the real Koin graph (catches missing definitions). */
    @Test
    fun everyViewModelResolves() {
        val missing = -424242L
        val all: List<Pair<KClass<*>, ParametersDefinition?>> = listOf(
            MainViewModel::class to null,
            LibraryViewModel::class to null,
            BookDetailsViewModel::class to { parametersOf(missing) },
            ReaderViewModel::class to { parametersOf(ReaderArgs(bookId = missing)) },
            ReadingStatsViewModel::class to null,
            DocxLayoutViewModel::class to { parametersOf(missing) },
            AnnotationsViewModel::class to { parametersOf(-1L) },
            DictionaryViewModel::class to null,
            WordsViewModel::class to null,
            WordLookupViewModel::class to null,
            ReviewViewModel::class to null,
            DictionariesViewModel::class to null,
            TranslationViewModel::class to null,
            LanguagePacksViewModel::class to null,
            EditorViewModel::class to { parametersOf(missing) },
            CatalogHomeViewModel::class to null,
            SourceBrowseViewModel::class to { parametersOf(BrowseArgs(sourceId = missing)) },
            GlobalSearchViewModel::class to { parametersOf(SearchArgs()) },
            TitleViewModel::class to { parametersOf(missing) },
            ExtensionsViewModel::class to null,
            DownloadsViewModel::class to null,
            SettingsViewModel::class to null,
            ProfileViewModel::class to null,
            ProfileTrackingViewModel::class to null,
        )
        val koin = GlobalContext.get()
        val failures = mutableListOf<String>()
        compose.runOnUiThread {
            all.forEach { (type, params) ->
                runCatching { koin.get<Any>(type, null, params) }.onFailure { failures += "${type.simpleName}: $it" }
            }
        }
        compose.waitForIdle()
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun startsAndShowsMainScreens() {
        awaitText("Welcome to Yomu")
        shot("01_onboarding")
        compose.onAllNodesWithText("Next")[0].performClick()
        awaitText("Where are your books?")
        shot("02_onboarding_folder")
        compose.onAllNodesWithText("Skip")[0].performClick()

        // The empty state proves that the database query of the library finished.
        awaitText("Your library is empty")
        shot("03_library")
        for ((tab, file, marker) in listOf(
            Triple("Catalog", "04_catalog", "MangaDex"),
            Triple("Dictionary", "05_dictionary", "Look up a word"),
            Triple("Settings", "06_settings", "Search settings"),
        )) {
            clickTab(tab)
            awaitText(marker)
            settle()
            shot(file)
        }

        // Import a generated EPUB and open it in the reader.
        val epub = File(compose.activity.cacheDir, "smoke.epub")
        EpubBuilder(EpubMeta(title = "Smoke Test Book", authors = listOf("Robolectric"), language = "en")).apply {
            addChapter("Chapter 1", "<h2>Chapter 1</h2><p>Hello world. This is a test.</p>")
            addChapter("Chapter 2", "<p>Second chapter.</p>")
        }.writeTo(epub)
        val outcome = runBlocking { GlobalContext.get().get<ImportService>().importLocalFile(epub, "smoke.epub") }
        assertTrue("import failed: $outcome", outcome is ImportOutcome.Added)
        clickTab("Library")
        awaitText("Smoke Test Book")
        settle()
        shot("07_library_book")
        compose.onAllNodesWithText("Smoke Test Book")[0].performClick()
        compose.mainClock.advanceTimeBy(2_000)
        shot("08_reader")

        // Reader chrome and settings sheet (only for the screenshots: the chrome may be hidden until a tap).
        runCatching {
            val settings = compose.onAllNodes(hasContentDescription("Reading settings"))
            if (settings.fetchSemanticsNodes().isNotEmpty()) {
                settings[0].performClick()
                compose.mainClock.advanceTimeBy(1_000)
                shot("09_reader_settings")
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
            }
        }

        // History lists the opened book; statistics open from its toolbar. The WebView reader does not run under
        // Robolectric, so a few reading sessions are recorded directly.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        val bookId = (outcome as ImportOutcome.Added).bookId
        runBlocking {
            val stats = GlobalContext.get().get<StatsRepository>()
            val now = System.currentTimeMillis()
            val day = 24 * 60 * 60 * 1000L
            listOf(0 to 25, 1 to 40, 2 to 15, 4 to 55, 6 to 30, 9 to 20).forEach { (daysAgo, minutes) ->
                stats.recordSession(bookId, now - daysAgo * day - minutes * 60_000L, minutes * 60_000L, pages = minutes, chars = minutes * 900L)
            }
        }
        // The profile tab sums everything up; History is its second segment and the statistics open from the overview.
        clickTab("Profile")
        awaitText("Total reading time")
        settle()
        shot("10_profile")
        compose.onNode(hasText("History") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        awaitText("Smoke Test Book")
        shot("11_history")
        compose.onNode(hasText("Overview") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        awaitText("Total reading time")
        compose.onNode(hasText("Total reading time")).performClick()
        awaitText("Time read")
        shot("12_stats")
    }

    /** Generated comic pages: a coloured page with two panels and a big page number. */
    private fun writeCbz(file: File, pages: Int) {
        val colors = listOf(0xFFF3E9D2, 0xFFDCE6F2, 0xFFE8DDF0, 0xFFE2F0DC)
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(pages) { i ->
                val bmp = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(colors[i % colors.size].toInt())
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = 0xFF222222.toInt() }
                canvas.drawRect(30f, 30f, 570f, 420f, stroke)
                canvas.drawRect(30f, 450f, 570f, 870f, stroke)
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt(); textSize = 180f; textAlign = Paint.Align.CENTER }
                canvas.drawText("${i + 1}", 300f, 300f, text)
                zip.putNextEntry(ZipEntry("page%03d.png".format(i + 1)))
                bmp.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        }
    }

    /** Whole screen including dialogs and bottom sheets (they live in their own windows). */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shotScreen(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    /**
     * Dialogs with text fields never become idle under Robolectric, so the clock is advanced by hand while such a
     * dialog is open, and it is closed with Back.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shotTextDialog(name: String, open: () -> Unit) {
        compose.mainClock.autoAdvance = false
        open()
        repeat(120) {
            Snapshot.sendApplyNotifications()
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeByFrame()
        }
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
        dialogBack(idle = false)
        repeat(20) {
            Snapshot.sendApplyNotifications()
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    /** Extension icon: a coloured rounded square with white letters. */
    private fun iconDrawable(color: Int, letters: String): Drawable {
        val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawRoundRect(0f, 0f, 192f, 192f, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt(); textSize = 84f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        canvas.drawText(letters, 96f, 126f, text)
        return BitmapDrawable(compose.activity.resources, bmp)
    }

    /** Back inside the topmost dialog or bottom sheet (they have their own windows and back dispatchers). */
    private fun dialogBack(idle: Boolean = true) {
        compose.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        if (idle) compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Catalog sources with their logos, the extensions screen, the "Add repository" dialog and the manga reader. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun catalogExtensionsAndManga() {
        // Settings live in a process-wide DataStore: when another test ran first, onboarding is already done.
        val tab = hasText("Catalog") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.waitUntilAtLeastOneExists(hasText("Welcome to Yomu", substring = true) or tab, 20_000)
        if (compose.onAllNodes(hasText("Welcome to Yomu", substring = true)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodesWithText("Next")[0].performClick()
            awaitText("Where are your books?")
            compose.onAllNodesWithText("Skip")[0].performClick()
        }
        compose.waitUntilAtLeastOneExists(tab, 20_000)

        clickTab("Catalog")
        awaitText("MangaDex")
        compose.mainClock.advanceTimeBy(1_000)
        shot("20_catalog_sources")

        // The "Add OPDS catalog" dialog starts empty (no prefilled "https://").
        shotTextDialog("25_add_opds") {
            compose.onNode(hasText("Add OPDS catalog") and hasAnyAncestor(hasScrollAction())).performSemanticsAction(SemanticsActions.OnClick)
        }

        // The row sits after every built-in source: scroll the lazy list to it (off-screen rows are not composed),
        // and with the novel sources it can sit below the navigation bar, where a tap would land on the bar.
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Add OPDS catalog"))).performScrollToNode(hasText("Extensions"))
        compose.onNode(hasText("Extensions") and hasAnyAncestor(hasScrollAction())).performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Installed")
        compose.mainClock.advanceTimeBy(2_000)

        // The bundled extensions cannot run under Robolectric (no dex), so the list is replaced with made-up ones:
        // three untrusted (one with an icon, two with monograms), one that failed to start and one working.
        val manager = GlobalContext.get().get<ExtensionManager>()
        runBlocking { manager.reload() } // waits for the scan started by the screen, so it can't overwrite the fakes
        @Suppress("UNCHECKED_CAST")
        val installed = ExtensionManager::class.java.getDeclaredField("_installed").apply { isAccessible = true }
            .get(manager) as MutableStateFlow<List<InstalledExtension>>
        fun fake(name: String, lang: String, status: ExtensionStatus, icon: Drawable? = null, error: String? = null) = InstalledExtension(
            pkg = "eu.kanade.tachiyomi.extension.$lang.${name.lowercase().replace(" ", "")}", name = name,
            versionName = "1.4.${name.length}", versionCode = 1, format = ExtensionFormat.TACHIYOMI, libVersion = 1.4, lang = lang,
            contentWarning = ContentWarning.SAFE, signatures = listOf("9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"),
            isPrivate = true, isBundled = false, status = status,
            brokenReason = if (status == ExtensionStatus.BROKEN) BrokenReason.FAILED else null, error = error, icon = icon,
        )
        compose.runOnUiThread {
            installed.value = listOf(
                fake("AComics", "ru", ExtensionStatus.UNTRUSTED, icon = iconDrawable(0xFF2E7D32.toInt(), "AC")),
                fake("Weeb Central", "en", ExtensionStatus.UNTRUSTED),
                fake("Comick", "all", ExtensionStatus.UNTRUSTED),
                fake("Old Reader", "en", ExtensionStatus.BROKEN, error = "java.lang.NoSuchFieldException: No field headers\$delegate in class " +
                    "Leu/kanade/tachiyomi/source/online/HttpSource;"),
                fake("Manga Park", "en", ExtensionStatus.LOADED, icon = iconDrawable(0xFFC62828.toInt(), "MP")),
            )
        }
        awaitText("Weeb Central")
        compose.mainClock.advanceTimeBy(1_000)
        shot("21_extensions")
        compose.onAllNodesWithText("Trust all")[0].performClick()
        compose.mainClock.advanceTimeBy(500)
        shotScreen("21b_trust_all")
        compose.onAllNodesWithText("Cancel")[0].performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Old Reader")[0].performClick()
        compose.mainClock.advanceTimeBy(1_500)
        shotScreen("21c_broken_details")
        dialogBack()
        compose.mainClock.advanceTimeBy(1_000)

        clickTab("Repositories")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Add repository"), 20_000)
        shotTextDialog("22_add_repository") {
            compose.onNode(hasContentDescription("Add repository")).performSemanticsAction(SemanticsActions.OnClick)
        }
        back()

        // Manga: a generated CBZ opens left to right; since 1.8.0 without the tap zones (they are opt-in again).
        val cbz = File(compose.activity.cacheDir, "Smoke Manga.cbz")
        writeCbz(cbz, 4)
        val outcome = runBlocking { GlobalContext.get().get<ImportService>().importLocalFile(cbz, "Smoke Manga.cbz") }
        assertTrue("import failed: $outcome", outcome is ImportOutcome.Added)
        clickTab("Library")
        awaitText("Smoke Manga")
        compose.onAllNodesWithText("Smoke Manga")[0].performClick()
        // The page number shows once the pages are in; the tap zones ("Back / Menu / Forward") are not shown.
        awaitText("1 / 4")
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("tap zones shown on opening", compose.onAllNodesWithText("Forward").fetchSemanticsNodes().isEmpty())
        shot("23_manga_reader")
        compose.mainClock.advanceTimeBy(4_000)
        runCatching {
            var settings = compose.onAllNodes(hasContentDescription("Reading settings"))
            if (settings.fetchSemanticsNodes().isEmpty()) {
                compose.onAllNodes(isRoot())[0].performTouchInput { click(center) }
                compose.mainClock.advanceTimeBy(1_000)
                settings = compose.onAllNodes(hasContentDescription("Reading settings"))
            }
            settings[0].performClick()
            compose.mainClock.advanceTimeBy(1_000)
            shotScreen("24_manga_settings")
        }.onFailure { println("manga settings screenshot skipped: $it") }
    }
}
