package app.yomu.feature.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinHasherTest {
    @Test
    fun `hash verifies only the same pin`() {
        val stored = PinHasher.hash("1234")
        assertTrue(PinHasher.verify("1234", stored))
        assertFalse(PinHasher.verify("1235", stored))
        assertFalse(PinHasher.verify("1234", "garbage"))
        assertFalse(PinHasher.verify("1234", "a:b:c"))
    }

    @Test
    fun `hash is salted`() {
        assertNotEquals(PinHasher.hash("0000"), PinHasher.hash("0000"))
    }
}
