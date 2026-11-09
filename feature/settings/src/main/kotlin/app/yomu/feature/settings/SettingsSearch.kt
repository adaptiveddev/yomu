package app.yomu.feature.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.delay

/**
 * One searchable option. [title] doubles as the anchor id: option rows register under
 * their displayed title (see [SettingsAnchor]), so the index needs no separate key table.
 * A null [anchor] means the entry is the section itself.
 */
internal data class SettingsSearchEntry(
    val section: SettingsSection,
    val sectionTitle: String,
    val title: String,
    val summary: String?,
    val anchor: String?,
)

/** Title matches come first, then summary and section-name matches; order inside a group follows the index. */
internal fun filterSettings(entries: List<SettingsSearchEntry>, query: String): List<SettingsSearchEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val (byTitle, rest) = entries.partition { it.title.lowercase().contains(q) }
    val bySummary = rest.filter { it.summary?.lowercase()?.contains(q) == true || it.sectionTitle.lowercase().contains(q) }
    return (byTitle + bySummary).distinctBy { it.section to it.title }
}

/** Index built from the same string resources the sections render; `<title>_sum` is used as the summary when present. */
@Composable
internal fun rememberSettingsIndex(): List<SettingsSearchEntry> {
    val resources = LocalResources.current
    val pkg = LocalContext.current.packageName
    return remember(resources) {
        SettingsSection.entries.filter { it.visible }.flatMap { s ->
            val sectionTitle = resources.getString(s.title)
            val own = SettingsSearchEntry(s, sectionTitle, sectionTitle, resources.getString(s.summary), anchor = null)
            val items = s.items.map { res ->
                val sumId = resources.getIdentifier(resources.getResourceEntryName(res) + "_sum", "string", pkg)
                SettingsSearchEntry(s, sectionTitle, resources.getString(res), sumId.takeIf { it != 0 }?.let(resources::getString), anchor = resources.getString(res))
            }
            listOf(own) + items
        }
    }
}

/** Option title a section should scroll to and flash once it is shown. */
@Stable
internal class SettingsFocus {
    var target by mutableStateOf<String?>(null)
    var nonce by mutableStateOf(0)
        private set

    fun request(title: String?) {
        target = title
        nonce++
    }
}

internal val LocalSettingsFocus = compositionLocalOf { SettingsFocus() }

/** Registers an option row as a scroll/highlight target for [title]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsAnchor(title: String, content: @Composable () -> Unit) {
    val focus = LocalSettingsFocus.current
    val requester = remember { BringIntoViewRequester() }
    val flash = remember { Animatable(0f) }
    val color = MaterialTheme.colorScheme.primary
    val hit = focus.target == title
    LaunchedEffect(hit, focus.nonce) {
        if (!hit) return@LaunchedEffect
        delay(300) // let the section transition finish and the scroll range settle
        requester.bringIntoView()
        repeat(2) {
            flash.animateTo(1f, tween(260))
            flash.animateTo(0f, tween(260))
        }
        focus.request(null)
    }
    Box(Modifier.fillMaxWidth().bringIntoViewRequester(requester).drawBehind { drawRect(color.copy(alpha = 0.22f * flash.value)) }) { content() }
}

@Composable
internal fun PreferenceItem(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) = SettingsAnchor(title) { app.yomu.core.ui.components.PreferenceItem(title, summary, icon, enabled, onClick, trailing) }

@Composable
internal fun PreferenceSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) = SettingsAnchor(title) { app.yomu.core.ui.components.PreferenceSwitch(title, checked, onCheckedChange, summary, icon, enabled) }

@Composable
internal fun <T> PreferenceChoice(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
    summary: String? = null,
) = SettingsAnchor(title) { app.yomu.core.ui.components.PreferenceChoice(title, options, selected, onSelect, icon, summary) }

@Composable
internal fun SliderSetting(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    label: (Float) -> String,
    icon: ImageVector? = null,
    onCommit: (Float) -> Unit,
) = SettingsAnchor(title) { SliderSettingRow(title, value, range, steps, label, icon, onCommit) }
