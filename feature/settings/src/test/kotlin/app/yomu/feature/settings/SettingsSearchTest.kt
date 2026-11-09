package app.yomu.feature.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsSearchTest {
    private fun entry(section: SettingsSection, sectionTitle: String, title: String, summary: String? = null) =
        SettingsSearchEntry(section, sectionTitle, title, summary, anchor = title)

    private val index = listOf(
        entry(SettingsSection.APPEARANCE, "Appearance", "Theme", "Light or dark"),
        entry(SettingsSection.APPEARANCE, "Appearance", "Pure black", "Black background in the dark theme"),
        entry(SettingsSection.READER, "Reader", "Font size"),
        entry(SettingsSection.LIBRARY, "Library", "Dark covers"),
    )

    @Test fun blankQueryGivesNothing() = assertTrue(filterSettings(index, "  ").isEmpty())

    @Test fun matchesCaseInsensitively() = assertEquals(listOf("Font size"), filterSettings(index, "FONT").map { it.title })

    @Test fun titleMatchesRankBeforeSummaryMatches() =
        assertEquals(listOf("Dark covers", "Theme", "Pure black"), filterSettings(index, "dark").map { it.title })

    @Test fun matchesSectionName() = assertEquals(listOf("Font size"), filterSettings(index, "reader").map { it.title })

    @Test fun noMatchIsEmpty() = assertTrue(filterSettings(index, "zzz").isEmpty())
}
