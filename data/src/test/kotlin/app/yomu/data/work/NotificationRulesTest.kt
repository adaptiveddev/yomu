package app.yomu.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationRulesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun quietHoursAcrossMidnightAndWithinADay() {
        val night = NotificationPrefs(quietHours = true, quietFrom = 22, quietTo = 8)
        assertTrue(night.isQuiet(23))
        assertTrue(night.isQuiet(0))
        assertTrue(night.isQuiet(7))
        assertFalse(night.isQuiet(8))
        assertFalse(night.isQuiet(21))
        val day = NotificationPrefs(quietHours = true, quietFrom = 13, quietTo = 15)
        assertTrue(day.isQuiet(14))
        assertFalse(day.isQuiet(15))
        assertFalse(NotificationPrefs(quietHours = false).isQuiet(23))
        assertFalse(NotificationPrefs(quietHours = true, quietFrom = 5, quietTo = 5).isQuiet(5))
    }

    @Test
    fun switchedOffKindsAreNotAllowed() {
        val p = NotificationPrefs(disabled = setOf(NotifyKind.DOWNLOADS))
        assertFalse(p.allows(NotifyKind.DOWNLOADS))
        assertTrue(p.allows(NotifyKind.NEW_CHAPTERS))
    }

    @Test
    fun logKeepsTheNewestAndSurvivesARestart() {
        val file = File(tmp.root, "recent.json")
        val log = NotificationLog(file)
        repeat(NotificationLog.MAX + 5) { log.add(NotificationRecord(it.toLong(), "TASKS", "n$it")) }
        log.flush()
        assertEquals(NotificationLog.MAX, log.entries.value.size)
        assertEquals("n${NotificationLog.MAX + 4}", log.entries.value.first().title)
        assertEquals(log.entries.value, NotificationLog(file).also { it.flush() }.entries.value)
        log.clear()
        log.flush()
        assertTrue(NotificationLog(file).also { it.flush() }.entries.value.isEmpty())
    }
}
