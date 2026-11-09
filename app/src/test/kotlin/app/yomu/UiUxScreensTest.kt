package app.yomu

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.SwipeAction
import app.yomu.core.ui.components.SwipeActionsBox
import app.yomu.core.ui.theme.ThemeConfig
import app.yomu.core.ui.theme.ThemeMode
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.domain.model.CommonReaderSettings
import app.yomu.domain.model.MangaSettings
import app.yomu.domain.model.PdfSettings
import app.yomu.domain.model.TextReaderSettings
import app.yomu.domain.model.TocItem
import app.yomu.domain.model.WordTapGesture
import app.yomu.feature.reader.ReaderArgs
import app.yomu.feature.reader.ReaderContent
import app.yomu.feature.reader.ReaderSettingsSheet
import app.yomu.feature.reader.ReaderViewModel
import app.yomu.feature.reader.epub.EpubController
import app.yomu.feature.reader.epub.TextDocument
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of the UI/UX pass: swipe actions, the empty state and the new reader settings. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class UiUxScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dark = ThemeConfig(mode = ThemeMode.DARK)
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        stopKoin()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shotScreen(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun swipeActionsReveal() {
        compose.setContent {
            YomuTheme(dark) {
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        listOf("Chapter 12 · failed", "Chapter 13 · queued", "Chapter 14 · queued", "Chapter 15 · queued").forEach { title ->
                            SwipeActionsBox(
                                start = SwipeAction.neutral(Icons.Default.Refresh, "Retry") {},
                                end = SwipeAction.destructive("Delete") {},
                            ) {
                                ListItem(headlineContent = { Text(title) }, supportingContent = { Text("Solo Leveling") })
                            }
                        }
                    }
                }
            }
        }
        awaitText("Chapter 13")
        // The finger stays down: the row is caught halfway, past the threshold.
        compose.onNodeWithText("Chapter 13 · queued").performTouchInput {
            down(Offset(width - 30f, centerY))
            repeat(12) { moveBy(Offset(-width * 0.04f, 0f)) }
        }
        compose.mainClock.advanceTimeBy(600)
        shot("300_swipe_actions")
    }

    @Test
    fun emptyState() {
        compose.setContent {
            YomuTheme(dark) {
                Surface(Modifier.fillMaxSize()) {
                    EmptyState(
                        title = "Your library is empty",
                        message = "Import books and comics from the device or find free books in the catalog.",
                        icon = Icons.Outlined.AutoStories,
                        actionLabel = "Import",
                        onAction = {},
                    )
                }
            }
        }
        awaitText("Your library is empty")
        shot("301_empty_state")
    }

    @Test
    fun readerSettingsNewOptions() {
        lateinit var vm: ReaderViewModel
        compose.runOnUiThread { vm = GlobalContext.get().get { parametersOf(ReaderArgs(bookId = -424242L)) } }
        val content = ReaderContent.Text(EpubController(context, FakeDocument, File(context.cacheDir, "fonts").apply { mkdirs() }))
        compose.setContent {
            YomuTheme(dark) {
                ReaderSettingsSheet(
                    viewModel = vm,
                    content = content,
                    text = TextReaderSettings(autoNight = true),
                    manga = MangaSettings(),
                    pdf = PdfSettings(),
                    common = CommonReaderSettings(),
                    perBook = false,
                    wordTap = WordTapGesture.DOUBLE_TAP,
                    onDismiss = {},
                )
            }
        }
        awaitText("Controls")
        compose.onAllNodesWithText("Controls")[0].performClick()
        awaitText("Tap zones")
        shotScreen("302_reader_tap_zones")
        compose.onAllNodesWithText("Edge swipe gestures")[0].performScrollTo()
        shotScreen("303_reader_edge_gestures")
        compose.onAllNodesWithText("Display")[0].performClick()
        awaitText("Night theme")
        shotScreen("304_reader_auto_night")
        compose.onAllNodesWithText("Text")[0].performClick()
        compose.onAllNodesWithText("Page turn animation")[0].performScrollTo()
        shotScreen("305_reader_page_animation")
    }

    private object FakeDocument : TextDocument {
        override val title = "Sample"
        override val language = "en"
        override val rtl = false
        override val chapterCount = 1
        override val toc = listOf(TocItem("Chapter 1", 0))
        override fun chapterPath(index: Int) = "ch1.xhtml"
        override fun chapterIndexOf(path: String) = 0
        override fun chapterTitle(index: Int) = "Chapter 1"
        override fun weight(index: Int) = 1L
        override suspend fun chapterHtml(index: Int) = "<html><body><p>Hello</p></body></html>"
        override fun resource(path: String): ByteArray? = null
        override fun close() = Unit
    }
}
