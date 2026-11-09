package app.yomu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.yomu.data.book.EpubBuilder
import app.yomu.data.book.EpubMeta
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.ByteArrayOutputStream
import java.io.File
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

/** Settings search (phone and the tablet two-pane layout) and the reader's first-run tap-zone diagram. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NavigationUxScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<YomuActivity>()

    @After
    fun tearDown() {
        stopKoin()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun tab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun skipOnboarding() {
        compose.waitUntil(20_000) {
            listOf("Welcome to Yomu", "Library").any { compose.onAllNodes(hasText(it, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
        if (compose.onAllNodes(hasText("Welcome to Yomu")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodesWithText("Next")[0].performClick()
            awaitText("Where are your books?")
            compose.onAllNodesWithText("Skip")[0].performClick()
        }
        awaitText("Library")
    }

    private fun searchSettings(query: String) {
        compose.onAllNodes(tab("Settings"))[0].performClick()
        awaitText("Search settings")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(query)
        awaitText("Settings ›")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneSettingsSearch() {
        skipOnboarding()
        searchSettings("font")
        shot("320_settings_search_results")
        compose.onAllNodes(hasText("Settings ›", substring = true))[0].performClick()
        compose.mainClock.advanceTimeBy(500)
        shot("321_settings_search_opened")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletSettingsTwoPane() {
        skipOnboarding()
        compose.onAllNodes(tab("Settings"))[0].performClick()
        awaitText("Search settings")
        shot("322_tablet_settings_two_pane")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("font")
        awaitText("Settings ›")
        compose.onAllNodes(hasText("Settings ›", substring = true))[0].performClick()
        compose.mainClock.advanceTimeBy(500)
        shot("323_tablet_settings_search_opened")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun readerTapZonesOnFirstOpen() {
        skipOnboarding()
        val bmp = Bitmap.createBitmap(300, 450, Bitmap.Config.ARGB_8888).also { Canvas(it).drawPaint(Paint().apply { color = 0xFF7C3AED.toInt() }) }
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val epub = File(compose.activity.cacheDir, "zones.epub")
        EpubBuilder(EpubMeta(title = "Zones", authors = listOf("A. Reader"), language = "en")).apply {
            coverHref = addResource("cover.png", "image/png", png)
            addChapter("Chapter 1", "<p>First page.</p>")
        }.writeTo(epub)
        val outcome = runBlocking { GlobalContext.get().get<ImportService>().importLocalFile(epub, "zones.epub") }
        assertTrue("import failed: $outcome", outcome is ImportOutcome.Added)
        awaitText("Zones")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithText("Zones")[0].performClick()
        awaitText("Next page")
        shot("324_reader_tap_zones")
        compose.onAllNodesWithText("Got it")[0].performClick()
        awaitText("Long-press a word")
        shot("325_reader_hint_after_zones")
    }
}
