package app.yomu.domain.catalog

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceListFilterTest {
    private fun SourceListFilter.passes(
        content: String = "MANGA",
        kind: String = "EXTENSION",
        pinned: Boolean = false,
        enabled: Boolean = true,
        nsfw: Boolean = false,
        unstable: Boolean = false,
        hidden: Boolean = false,
    ) = matches(content, kind, pinned, enabled, nsfw, unstable, hidden)

    @Test
    fun `hidden sources are listed only on request`() {
        assertFalse(SourceListFilter().passes(hidden = true))
        assertTrue(SourceListFilter().passes(hidden = false))
        val show = SourceListFilter(showHidden = true)
        assertEquals(1, show.activeCount)
        assertTrue(show.passes(hidden = true))
        assertTrue(show.passes(hidden = false))
    }

    @Test
    fun `the default filter shows every source`() {
        val filter = SourceListFilter()
        assertEquals(0, filter.activeCount)
        assertFalse(filter.isActive)
        assertTrue(filter.passes())
        assertTrue(filter.passes(content = "BOOK", kind = "OPDS", enabled = false, nsfw = true, unstable = true))
    }

    @Test
    fun `content and kind keep only the chosen values`() {
        val filter = SourceListFilter(content = setOf("MANGA", "NOVEL"), kinds = setOf("EXTENSION"))
        assertTrue(filter.passes(content = "NOVEL"))
        assertFalse(filter.passes(content = "BOOK"))
        assertFalse(filter.passes(kind = "BUILT_IN"))
        assertEquals(2, filter.activeCount)
    }

    @Test
    fun `18+ can be shown, hidden or shown alone`() {
        assertTrue(SourceListFilter(nsfw = NsfwFilter.ALL).passes(nsfw = true))
        assertFalse(SourceListFilter(nsfw = NsfwFilter.HIDE).passes(nsfw = true))
        assertTrue(SourceListFilter(nsfw = NsfwFilter.HIDE).passes(nsfw = false))
        assertTrue(SourceListFilter(nsfw = NsfwFilter.ONLY).passes(nsfw = true))
        assertFalse(SourceListFilter(nsfw = NsfwFilter.ONLY).passes(nsfw = false))
    }

    @Test
    fun `pinned, disabled and unstable switches`() {
        val filter = SourceListFilter(pinnedOnly = true, hideDisabled = true, hideUnstable = true)
        assertEquals(3, filter.activeCount)
        assertTrue(filter.passes(pinned = true))
        assertFalse(filter.passes(pinned = false))
        assertFalse(filter.passes(pinned = true, enabled = false))
        assertFalse(filter.passes(pinned = true, unstable = true))
    }

    @Test
    fun `survives a round trip through the settings json`() {
        val filter = SourceListFilter(content = setOf("MANGA"), kinds = setOf("OPDS", "LOCAL"), pinnedOnly = true, nsfw = NsfwFilter.HIDE)
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(filter, json.decodeFromString(SourceListFilter.serializer(), json.encodeToString(SourceListFilter.serializer(), filter)))
        // Settings written by an older or newer version: missing fields take their defaults, unknown ones are skipped.
        assertEquals(SourceListFilter(pinnedOnly = true), json.decodeFromString(SourceListFilter.serializer(), """{"pinnedOnly":true,"future":1}"""))
    }
}
