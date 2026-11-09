package app.yomu.feature.reader

import app.yomu.domain.model.CommonReaderSettings
import app.yomu.domain.model.ScreenTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderQuickPanelTest {
    @Test
    fun `keep awake switches to always and back to the default timeout`() {
        val on = CommonReaderSettings(screenTimeout = ScreenTimeout.MIN_2).withKeepAwake(true)
        assertEquals(ScreenTimeout.ALWAYS, on.screenTimeout)
        assertEquals(DEFAULT_TIMEOUT, on.withKeepAwake(false).screenTimeout)
    }

    @Test
    fun `keep awake off leaves a custom timeout alone`() {
        val s = CommonReaderSettings(screenTimeout = ScreenTimeout.MIN_5)
        assertEquals(ScreenTimeout.MIN_5, s.withKeepAwake(false).screenTimeout)
    }
}
