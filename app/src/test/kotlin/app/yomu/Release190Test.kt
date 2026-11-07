package app.yomu

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import app.yomu.core.common.AppError
import app.yomu.core.common.CrashReport
import app.yomu.core.common.ErrorLog
import app.yomu.core.datastore.AppSettings
import app.yomu.core.ui.components.ErrorState
import app.yomu.core.ui.theme.ThemeConfig
import app.yomu.core.ui.theme.ThemeMode
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.navigation.TabsHost
import app.yomu.navigation.TabsState
import app.yomu.navigation.TopLevel
import app.yomu.ui.CrashReportDialog
import app.yomu.ui.crashSummary
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.9.0: the crash of a title page that failed to load, the tabs that stay composed, the crash report offered after a
 * restart.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class Release190Test {
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

    /**
     * The crash of the log (1.6.0, Android 16): an error inside the list of a title page was a scrolling column in a
     * lazy list ("Vertically scrollable component was measured with an infinity maximum height constraints").
     */
    @Test
    fun errorStateInsideAListDoesNotCrash() {
        compose.setContent {
            YomuTheme(dark) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Text("Header of the title") }
                    item {
                        ErrorState(
                            error = AppError.from(java.io.IOException("Connection reset")),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            onRetry = {},
                        )
                    }
                    item { Text("After the error") }
                }
            }
        }
        compose.onNodeWithText("Header of the title").assertExists()
        compose.onNodeWithText("After the error").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/190_error_in_list.png")
    }

    private class Probe : ViewModel()

    /** Tabs stay composed, keep their own ViewModels, stop at STARTED off screen and return to the start one on Back. */
    @Test
    fun tabsStayComposedAndBackReturnsToStart() {
        val state = TabsState(TopLevel.LIBRARY)
        val compositions = HashMap<TopLevel, Int>()
        val lifecycles = HashMap<TopLevel, LifecycleOwner>()
        val probes = HashMap<TopLevel, ViewModel>()
        var catalogBack = 0
        compose.setContent {
            YomuTheme(dark) {
                TabsHost(state, reduceMotion = true) { tab ->
                    val count = remember { (compositions[tab] ?: 0) + 1 }
                    SideEffect { compositions[tab] = count }
                    lifecycles[tab] = LocalLifecycleOwner.current
                    probes[tab] = viewModel { Probe() }
                    if (tab == TopLevel.CATALOG) BackHandler { catalogBack++ }
                    Text("${tab.name} composed $count")
                }
            }
        }
        compose.onNodeWithText("LIBRARY composed 1").assertExists()

        compose.runOnUiThread { state.select(TopLevel.CATALOG) }
        compose.waitForIdle()
        compose.onNodeWithText("CATALOG composed 1").assertExists()
        // The tab off screen is still composed, but not shown: no semantics.
        compose.onAllNodesWithText("LIBRARY", substring = true).assertCountEquals(0)
        assertEquals(Lifecycle.State.STARTED, lifecycles.getValue(TopLevel.LIBRARY).lifecycle.currentState)
        assertEquals(Lifecycle.State.RESUMED, lifecycles.getValue(TopLevel.CATALOG).lifecycle.currentState)
        assertNotSame(probes.getValue(TopLevel.LIBRARY), probes.getValue(TopLevel.CATALOG))

        compose.runOnUiThread { state.select(TopLevel.LIBRARY) }
        compose.waitForIdle()
        compose.onNodeWithText("LIBRARY composed 1").assertExists()
        assertEquals(1, compositions[TopLevel.LIBRARY])

        // Back inside a tab goes to the tab's own handler first.
        compose.runOnUiThread { state.select(TopLevel.CATALOG) }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, catalogBack)
        assertEquals(TopLevel.CATALOG, state.selected)

        // From another tab, Back returns to the start tab; the handler of the catalog, off screen, stays silent.
        compose.runOnUiThread { state.select(TopLevel.SETTINGS) }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(TopLevel.LIBRARY, state.selected)
        assertEquals(1, catalogBack)
        compose.onNodeWithText("LIBRARY composed 1").assertExists()
        assertEquals(1, compositions[TopLevel.SETTINGS])
    }

    /** A tab prepared on a press is composed off screen before it is selected. */
    @Test
    fun preparedTabIsComposedBeforeItIsShown() {
        val state = TabsState(TopLevel.LIBRARY)
        val composed = HashSet<TopLevel>()
        compose.setContent {
            YomuTheme(dark) {
                TabsHost(state, reduceMotion = false) { tab ->
                    SideEffect { composed += tab }
                    Text("Tab ${tab.name}")
                }
            }
        }
        compose.runOnUiThread { state.prepare(TopLevel.DICTIONARY) }
        compose.waitForIdle()
        assertTrue(TopLevel.DICTIONARY in composed)
        assertEquals(TopLevel.LIBRARY, state.selected)
        compose.onAllNodesWithText("Tab DICTIONARY").assertCountEquals(0)
        compose.runOnUiThread { state.select(TopLevel.DICTIONARY) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Tab DICTIONARY").assertExists()
    }

    /** Coming back to the tabs (a title was open over them) composes only the tab on screen; the others on a press. */
    @Test
    fun returningToTheTabsComposesOnlyTheOneOnScreen() {
        val state = TabsState(TopLevel.LIBRARY)
        var home by mutableStateOf(true)
        val alive = HashSet<TopLevel>()
        compose.setContent {
            YomuTheme(dark) {
                if (home) {
                    TabsHost(state, reduceMotion = true) { tab ->
                        DisposableEffect(tab) {
                            alive += tab
                            onDispose { alive -= tab }
                        }
                        Text("Tab ${tab.name}")
                    }
                }
            }
        }
        compose.runOnUiThread {
            state.select(TopLevel.CATALOG)
            state.select(TopLevel.SETTINGS)
        }
        compose.waitForIdle()
        assertEquals(setOf(TopLevel.LIBRARY, TopLevel.CATALOG, TopLevel.SETTINGS), alive)

        compose.runOnUiThread { home = false }
        compose.waitForIdle()
        assertTrue(alive.isEmpty())

        compose.runOnUiThread { home = true }
        compose.waitForIdle()
        assertEquals(setOf(TopLevel.SETTINGS), alive)
        compose.onNodeWithText("Tab SETTINGS").assertExists()

        compose.runOnUiThread { state.prepare(TopLevel.LIBRARY) }
        compose.waitForIdle()
        assertEquals(setOf(TopLevel.SETTINGS, TopLevel.LIBRARY), alive)
        assertEquals(TopLevel.SETTINGS, state.selected)
    }

    /** A settings write started on the main thread no longer holds up the other writes while that thread is busy. */
    @Test
    fun aWriteFromTheMainThreadDoesNotHoldUpTheOthers() = runBlocking {
        val settings = GlobalContext.get().get<AppSettings>()
        // The main thread stays busy in this test (its looper is not run). Before 1.9.0 the change of this write waited
        // for it while holding the store's write lock, and every later write of the app waited behind it.
        CoroutineScope(Dispatchers.Main.immediate).launch { settings.crashSeenAt.set(1L) }
        withTimeout(10_000) { settings.updateCheckedAt.set(2L) }
        assertEquals(1L, withTimeout(10_000) { settings.crashSeenAt.flow.first { it == 1L } })
        withTimeout(10_000) {
            settings.crashSeenAt.reset()
            settings.updateCheckedAt.reset()
        }
    }

    private val sample = """
        Yomu ${BuildConfig.VERSION_NAME} / Android 16 (API 36) / OnePlus CPH2645
        Where: main
        Screen: TitleRoute entryId=12
        Recent: Catalog tab → SourceRoute sourceId=7 → TitleRoute entryId=12
        java.lang.IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints, which is disallowed.
        	at androidx.compose.foundation.CheckScrollableContainerConstraintsKt.checkScrollableContainerConstraints-K40F9xA(CheckScrollableContainerConstraints.kt:35)
    """.trimIndent()

    @Test
    fun crashSummaryIsTheErrorLine() {
        assertEquals(
            "IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints, which is disallowed.",
            crashSummary(sample),
        )
        assertEquals("Out of memory", crashSummary("Yomu 1.9.0 / Android 14\nWhere: main\n\nOut of memory"))
        assertNull(crashSummary("Yomu 1.9.0 / Android 14\nWhere: main"))
    }

    /** Only a crash of the running version is offered: one from before the update may well be fixed. */
    @Test
    fun lastCrashIsTheNewestOfThisVersion() {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        ErrorLog.clear()
        val old = File(dir, "crash-2020-01-01_00-00-00-1.txt").apply { writeText(sample.replace(BuildConfig.VERSION_NAME, "1.6.0-debug")) }
        old.setLastModified(2_000_000_000_000)
        assertNull(ErrorLog.lastCrash(BuildConfig.VERSION_NAME))
        val error = File(dir, "error-2020-01-01_00-00-01-2.txt").apply { writeText(sample) }
        error.setLastModified(2_000_000_100_000)
        assertNull(ErrorLog.lastCrash(BuildConfig.VERSION_NAME))
        val crash = File(dir, "crash-2020-01-01_00-00-02-3.txt").apply { writeText(sample) }
        crash.setLastModified(2_000_000_200_000)
        val report = ErrorLog.lastCrash(BuildConfig.VERSION_NAME)
        assertNotNull(report)
        assertEquals(2_000_000_200_000, report!!.time)
        assertTrue(report.text.contains("TitleRoute entryId=12"))
        ErrorLog.clear()
    }

    @Test
    fun crashDialog() {
        var dismissed = 0
        compose.setContent {
            YomuTheme(dark) {
                CrashReportDialog(CrashReport(System.currentTimeMillis(), sample), onDismiss = { dismissed++ })
            }
        }
        awaitText("Yomu closed unexpectedly")
        compose.onNodeWithText("Send report").assertExists()
        compose.onNodeWithText("Copy").assertExists()
        compose.onNodeWithText("IllegalStateException", substring = true).assertExists()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/190_crash_dialog.png")
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }
}
