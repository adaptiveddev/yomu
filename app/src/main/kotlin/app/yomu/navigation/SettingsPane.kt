package app.yomu.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yomu.feature.settings.SettingsLinks
import app.yomu.feature.settings.SettingsScreen
import app.yomu.feature.settings.SettingsSection
import org.koin.androidx.compose.koinViewModel

/** Window width from which Settings shows the section list beside the selected section. */
internal const val TWO_PANE_MIN_WIDTH_DP = 840

/**
 * The settings root (with its search) on the left, the selected section on the right. A search result opens its section
 * scrolled to the option.
 */
@Composable
internal fun SettingsTwoPane(selected: SettingsSection, onSelect: (SettingsSection) -> Unit, links: SettingsLinks) {
    var anchor by remember { mutableStateOf<String?>(null) }
    // Picking the same result again must scroll and flash again, so every pick gets a fresh right pane.
    var pick by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(360.dp).fillMaxSize()) {
            SettingsScreen(
                viewModel = koinViewModel(),
                links = links,
                onSelectSection = { section, a -> anchor = a; pick++; onSelect(section) },
            )
        }
        VerticalDivider()
        // A section is a screen of its own, so each selection gets a fresh one (its inner navigation starts over).
        key(selected, pick) {
            Box(Modifier.weight(1f).fillMaxSize()) {
                SettingsScreen(viewModel = koinViewModel(), links = links, initialSection = selected, initialAnchor = anchor)
            }
        }
    }
}
