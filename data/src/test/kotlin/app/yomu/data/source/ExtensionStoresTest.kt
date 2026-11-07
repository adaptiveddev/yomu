package app.yomu.data.source

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionStoresTest {
    private val stores = ExtensionStores { OkHttpClient() }
    private val index = "https://raw.githubusercontent.com/owner/extensions/repo/index.min.json"

    @Test
    fun `full index address is kept`() {
        assertEquals(index, stores.normalize(index))
        assertEquals(index, stores.normalize("  $index/ "))
    }

    @Test
    fun `scheme typed over a prefilled one is collapsed`() {
        assertEquals(index, stores.normalize("https://$index"))
        assertEquals(index, stores.normalize("https://https://raw.githubusercontent.com/owner/extensions/repo/index.min.json"))
        assertEquals(index, stores.normalize("HTTPS://raw.githubusercontent.com/owner/extensions/repo/index.min.json"))
    }

    @Test
    fun `bare hosts and folders get https and the index file`() {
        assertEquals("https://example.org/index.min.json", stores.normalize("example.org"))
        assertEquals("https://example.org/repo/index.min.json", stores.normalize("example.org/repo/"))
        assertEquals(index, stores.normalize("raw.githubusercontent.com/owner/extensions/repo/index.min.json"))
    }

    @Test
    fun `github pages point to raw files`() {
        assertEquals(index, stores.normalize("https://github.com/owner/extensions/tree/repo"))
        assertEquals(index, stores.normalize("https://github.com/owner/extensions/blob/repo/index.min.json"))
    }

    @Test
    fun `add to mihon links carry the address`() {
        assertEquals(index, stores.normalize("tachiyomi://add-repo?url=$index"))
        assertEquals(index, stores.normalize("mihon://extension-store?url=https%3A%2F%2Fraw.githubusercontent.com%2Fowner%2Fextensions%2Frepo%2Findex.min.json"))
    }
}
