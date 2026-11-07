package app.yomu

import app.yomu.core.ui.components.NavTab
import app.yomu.feature.settings.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navigation bar order and visibility, and reading GitHub releases for "Check for updates". */
class Release160LogicTest {
    @Test
    fun defaultOrderWhenNothingIsStored() {
        assertEquals(NavTab.entries, NavTab.ordered(emptyList()))
        assertEquals(NavTab.entries, NavTab.visible(emptyList(), emptySet()))
    }

    @Test
    fun storedOrderDropsUnknownAndKeepsNewTabs() {
        // "READER" is not a tab (an older or newer build); DICTIONARY and SETTINGS were not stored: they go last.
        val order = NavTab.ordered(listOf("CATALOG", "READER", "LIBRARY", "CATALOG", "HISTORY"))
        assertEquals(listOf(NavTab.CATALOG, NavTab.LIBRARY, NavTab.HISTORY, NavTab.DICTIONARY, NavTab.SETTINGS), order)
    }

    @Test
    fun settingsCannotBeHidden() {
        val all = NavTab.entries.map { it.name }.toSet()
        assertEquals(listOf(NavTab.SETTINGS), NavTab.visible(emptyList(), all))
        assertEquals(
            listOf(NavTab.LIBRARY, NavTab.HISTORY, NavTab.CATALOG, NavTab.SETTINGS),
            NavTab.visible(emptyList(), setOf("DICTIONARY")),
        )
    }

    @Test
    fun versionsCompareByNumbers() {
        assertTrue(UpdateChecker.isNewer("1.6.0", "1.5.0"))
        assertTrue(UpdateChecker.isNewer("v1.10.0", "1.9.3"))
        assertTrue(UpdateChecker.isNewer("1.6.1", "1.6"))
        assertFalse(UpdateChecker.isNewer("1.6.0", "1.6.0"))
        assertFalse(UpdateChecker.isNewer("1.6.0", "1.6.0-debug"))
        assertFalse(UpdateChecker.isNewer("1.5.9", "1.6.0-nightly.12"))
        assertFalse(UpdateChecker.isNewer("garbage", "1.0.0"))
    }

    @Test
    fun releasePicksTheApkOfTheDevice() {
        val json = """
            {"tag_name":"v1.6.0","html_url":"https://github.com/adxptived/yomu/releases/tag/v1.6.0","assets":[
              {"browser_download_url":"https://github.com/adxptived/yomu/releases/download/v1.6.0/SHA256SUMS.txt"},
              {"browser_download_url":"https://github.com/adxptived/yomu/releases/download/v1.6.0/yomu-v1.6.0-x86_64.apk"},
              {"browser_download_url":"https://github.com/adxptived/yomu/releases/download/v1.6.0/yomu-v1.6.0-arm64-v8a.apk"}
            ]}
        """.trimIndent()
        val arm = UpdateChecker.parse(json, abis = listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("1.6.0", arm.version)
        assertEquals("https://github.com/adxptived/yomu/releases/tag/v1.6.0", arm.pageUrl)
        assertEquals("https://github.com/adxptived/yomu/releases/download/v1.6.0/yomu-v1.6.0-arm64-v8a.apk", arm.apkUrl)
        assertTrue(UpdateChecker.parse(json, abis = listOf("x86_64")).apkUrl!!.endsWith("x86_64.apk"))
        // No APK for this device among several: the release page instead of a wrong file.
        assertNull(UpdateChecker.parse(json, abis = listOf("armeabi-v7a")).apkUrl)
    }
}
