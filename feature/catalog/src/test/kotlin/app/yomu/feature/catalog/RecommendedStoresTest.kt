package app.yomu.feature.catalog

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommendedStoresTest {
    private val keiyoushi = RECOMMENDED_STORES.first { it.name == "Keiyoushi" }

    @Test
    fun `an added repository is recognised in any address form`() {
        assertTrue(keiyoushi.matches("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"))
        assertTrue(keiyoushi.matches("https://raw.githubusercontent.com/keiyoushi/extensions/repo"))
        assertTrue(keiyoushi.matches("https://github.com/Keiyoushi/Extensions"))
        assertTrue(keiyoushi.matches("https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.min.json"))
    }

    @Test
    fun `other repositories do not match`() {
        assertFalse(keiyoushi.matches("https://raw.githubusercontent.com/keiyoushi/extensions-fork/repo"))
        assertFalse(keiyoushi.matches("https://raw.githubusercontent.com/notkeiyoushi/extensions/repo"))
    }
}
