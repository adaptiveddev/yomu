package app.yomu.feature.settings

import android.Manifest
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.yomu.core.ui.theme.hueSaturation
import app.yomu.core.ui.theme.isDarkScheme
import app.yomu.core.ui.theme.schemeFor
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.draw.clip
import app.yomu.core.network.TrafficMeter
import app.yomu.core.ui.components.animateMotionFloat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.ui.components.LauncherIcon
import app.yomu.core.ui.components.LauncherIconImage
import app.yomu.core.ui.components.LocalLauncherIcons
import app.yomu.core.ui.components.rememberHaptic
import app.yomu.core.common.Files
import app.yomu.core.datastore.Pref
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.PreferenceHeader
import app.yomu.core.ui.components.TextInputDialog
import app.yomu.core.ui.theme.Accent
import app.yomu.core.ui.theme.Dimens
import app.yomu.feature.reader.manga.MangaControlSettings
import app.yomu.feature.reader.manga.MangaPageSettings
import app.yomu.feature.reader.manga.MangaSettingsUpdate
import app.yomu.core.ui.theme.ThemeMode
import app.yomu.domain.model.PdfMode
import app.yomu.domain.model.ReaderOrientation
import app.yomu.domain.model.ReaderTheme
import app.yomu.domain.model.ScreenTimeout
import app.yomu.domain.model.WebSearchEngine
import app.yomu.domain.model.WordTapGesture
import app.yomu.domain.translation.TranslationMode
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import app.yomu.core.ui.R as CoreR

// region Generic bindings

@Composable
internal fun SwitchPref(
    vm: SettingsViewModel,
    pref: Pref<*, Boolean>,
    title: Int,
    summary: Int? = null,
    enabled: Boolean = true,
    icon: ImageVector? = settingIcon(title),
    after: (suspend () -> Unit)? = null,
) {
    val v by pref.state()
    PreferenceSwitch(stringResource(title), v, { vm.set(pref, it, after) }, summary?.let { stringResource(it) }, icon = icon, enabled = enabled)
}

@Composable
internal fun <T> ChoicePref(
    vm: SettingsViewModel,
    pref: Pref<*, T>,
    title: Int,
    options: List<Pair<T, String>>,
    icon: ImageVector? = settingIcon(title),
    after: (suspend () -> Unit)? = null,
) {
    val v by pref.state()
    PreferenceChoice(stringResource(title), options, v, { vm.set(pref, it, after) }, icon = icon)
}

@Composable
internal fun IntSliderPref(
    vm: SettingsViewModel,
    pref: Pref<*, Int>,
    title: Int,
    range: IntRange,
    step: Int = 1,
    label: (Int) -> String = { it.toString() },
    icon: ImageVector? = settingIcon(title),
    after: (suspend () -> Unit)? = null,
) {
    val v by pref.state()
    SliderSetting(stringResource(title), v.toFloat(), range.first.toFloat()..range.last.toFloat(), steps = ((range.last - range.first) / step - 1).coerceAtLeast(0), label = { label(it.toInt()) }, icon = icon) {
        vm.set(pref, it.toInt(), after)
    }
}

@Composable
internal fun TextPref(
    vm: SettingsViewModel,
    pref: Pref<*, String>,
    title: Int,
    secret: Boolean = false,
    multiline: Boolean = false,
    summaryWhenEmpty: Int? = null,
    icon: ImageVector? = settingIcon(title),
) {
    val v by pref.state()
    var edit by remember { mutableStateOf(false) }
    val summary = when {
        v.isBlank() -> summaryWhenEmpty?.let { stringResource(it) } ?: stringResource(R.string.set_not_set)
        secret -> "•".repeat(minOf(v.length, 12))
        else -> v.lines().first().take(80)
    }
    PreferenceItem(stringResource(title), summary, icon, onClick = { edit = true })
    if (edit) {
        if (secret) {
            SecretInputDialog(stringResource(title), v, onConfirm = { vm.set(pref, it.trim()); edit = false }, onDismiss = { edit = false })
        } else {
            TextInputDialog(stringResource(title), v, singleLine = !multiline, onConfirm = { vm.set(pref, if (multiline) it else it.trim()); edit = false }, onDismiss = { edit = false })
        }
    }
}

private fun hoursLabel(context: android.content.Context, h: Int): String =
    if (h <= 0) context.getString(R.string.set_off) else context.resources.getQuantityString(R.plurals.set_every_hours, h, h)

private fun formatTime(ts: Long): String? = if (ts <= 0) null else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))

internal fun languageOptions(context: android.content.Context, codes: List<String>, emptyLabel: Int?): List<Pair<String, String>> = buildList {
    if (emptyLabel != null) add("" to context.getString(emptyLabel))
    codes.forEach { c -> add(c to Locale.forLanguageTag(c).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase() }) }
}

internal val LANGS = listOf("en", "ru", "uk", "be", "ja", "zh", "ko", "de", "fr", "es", "it", "pt", "pl", "cs", "tr", "ar", "hi", "vi", "id", "th", "nl", "sv")

// endregion

@Composable
internal fun ColumnScope.AppearanceSection(vm: SettingsViewModel) {
    val s = vm.settings
    val mode by s.themeMode.state()
    val dynamic by s.dynamicColor.state()
    val amoled by s.amoled.state()
    val accent by s.accent.state()
    val custom by s.accentCustom.state()
    val dynamicOn = dynamic && Build.VERSION.SDK_INT >= 31

    ThemePreview(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s))

    PreferenceHeader(stringResource(R.string.set_theme))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs)) {
        val modes = listOf(ThemeMode.SYSTEM to R.string.set_theme_system, ThemeMode.LIGHT to R.string.set_theme_light, ThemeMode.DARK to R.string.set_theme_dark)
        modes.forEachIndexed { i, (m, label) ->
            SegmentedButton(
                selected = mode == m.name,
                onClick = { vm.set(s.themeMode, m.name) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
            ) { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    SwitchPref(vm, s.amoled, R.string.set_amoled, R.string.set_amoled_sum)
    if (Build.VERSION.SDK_INT >= 31) SwitchPref(vm, s.dynamicColor, R.string.set_dynamic_color, R.string.set_dynamic_color_sum)

    PreferenceHeader(stringResource(R.string.set_accent))
    AccentPicker(
        selected = runCatching { Accent.valueOf(accent) }.getOrDefault(Accent.YOMU),
        custom = custom,
        amoled = amoled,
        dimmed = dynamicOn,
        onSelect = { vm.set(s.accent, it.name) },
        onCustom = { vm.set(s.accentCustom, it) },
    )
    val accentHint = when {
        dynamicOn -> R.string.set_accent_dynamic_hint
        accent == Accent.YOMU.name -> R.string.set_accent_yomu_hint
        accent == Accent.CLASSIC.name -> R.string.set_accent_classic_hint
        else -> null
    }
    if (accentHint != null) {
        Text(stringResource(accentHint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs))
    }

    LocalLauncherIcons.current?.let { launcher ->
        val current by launcher.current.collectAsStateWithLifecycle()
        PreferenceHeader(stringResource(R.string.set_app_icon))
        AppIconPicker(launcher.icons, current, onSelect = launcher::select)
        Text(stringResource(R.string.set_app_icon_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs))
    }

    PreferenceHeader(stringResource(R.string.set_interface))
    var locale by remember { mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags()) }
    PreferenceChoice(
        stringResource(R.string.set_language),
        listOf("" to stringResource(R.string.set_language_system), "en" to "English", "ru" to "Русский"),
        locale.substringBefore(',').substringBefore('-'),
        { tag ->
            locale = tag
            AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
        },
        icon = settingIcon(R.string.set_language),
    )
    SwitchPref(vm, s.haptics, R.string.set_haptics)
    SwitchPref(vm, s.reduceMotion, R.string.set_reduce_motion, R.string.set_reduce_motion_sum)
}

/** The launcher icons as the launcher draws them, in the same tiles as the accents above. */
@Composable
private fun AppIconPicker(icons: List<LauncherIcon>, selected: String, onSelect: (String) -> Unit) {
    val haptic = rememberHaptic()
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.s),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(Dimens.xs),
    ) {
        icons.forEach { icon ->
            val isSelected = icon.id == selected
            Column(
                Modifier.width(76.dp).clip(MaterialTheme.shapes.medium)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = {
                        if (!isSelected) haptic.perform()
                        onSelect(icon.id)
                    })
                    .padding(vertical = Dimens.s),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(58.dp)
                        .border(if (isSelected) 2.5.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                        .padding(if (isSelected) 5.dp else 2.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    LauncherIconImage(icon, Modifier.fillMaxSize())
                    if (isSelected) {
                        Box(
                            Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                }
                Text(
                    stringResource(icon.label),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A small mock of the library in the current colours: it changes as soon as a theme or an accent is picked. */
@Composable
private fun ThemePreview(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = c.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = 52.dp, height = 76.dp).clip(MaterialTheme.shapes.small)
                        .background(Brush.linearGradient(listOf(c.primary, c.tertiary))),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.xxs)) {
                    Text(stringResource(R.string.set_preview_continue), style = MaterialTheme.typography.labelMedium, color = c.primary)
                    Text(stringResource(R.string.set_preview_book), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.set_preview_meta), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Box(Modifier.padding(top = Dimens.xs).fillMaxWidth().height(6.dp).clip(CircleShape).background(c.surfaceContainerHighest)) {
                        Box(Modifier.fillMaxWidth(0.42f).height(6.dp).clip(CircleShape).background(c.primary))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                PreviewPill(stringResource(R.string.set_preview_all), selected = true)
                PreviewPill(stringResource(R.string.set_preview_manga), selected = false)
                PreviewPill(stringResource(R.string.set_preview_online), selected = false)
            }
            Surface(shape = CircleShape, color = c.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(
                        Modifier.clip(CircleShape).background(c.primary).padding(horizontal = Dimens.m, vertical = Dimens.s),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.CollectionsBookmark, null, Modifier.size(18.dp), tint = c.onPrimary)
                        Text(stringResource(R.string.set_preview_nav), style = MaterialTheme.typography.labelLarge, color = c.onPrimary)
                    }
                    listOf(Icons.Outlined.History, Icons.Outlined.Public, Icons.Outlined.Translate, Icons.Outlined.Settings).forEach {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Icon(it, null, Modifier.size(20.dp), tint = c.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewPill(text: String, selected: Boolean) {
    val c = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) c.onPrimary else c.onSurfaceVariant,
        modifier = Modifier.clip(CircleShape).background(if (selected) c.primary else c.surfaceContainerHigh).padding(horizontal = Dimens.m, vertical = 6.dp),
    )
}

/**
 * Accent swatches drawn in the colour each accent really gets in the current theme, with their names; "Custom" adds
 * hue and saturation sliders (saved when a slider is released).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(selected: Accent, custom: Long, amoled: Boolean, dimmed: Boolean, onSelect: (Accent) -> Unit, onCustom: (Long) -> Unit) {
    val dark = isDarkScheme()
    val (h0, s0) = remember(custom) { hueSaturation(custom) }
    var hue by remember(custom) { mutableFloatStateOf(h0) }
    var sat by remember(custom) { mutableFloatStateOf(s0.coerceIn(SAT_MIN, SAT_MAX)) }
    val customArgb = Color.hsl(hue, sat, 0.6f).toArgb().toLong() and 0xFFFFFFFFL
    val swatches = remember(dark, amoled, customArgb) {
        Accent.entries.associateWith { schemeFor(it, dark, amoled, customArgb).primary }
    }
    FlowRow(
        Modifier.fillMaxWidth().alpha(if (dimmed) 0.45f else 1f).padding(horizontal = Dimens.s),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(Dimens.xs),
    ) {
        Accent.entries.forEach { a ->
            val isSelected = a == selected
            val name = stringResource(a.label())
            Column(
                Modifier.width(76.dp).clip(MaterialTheme.shapes.medium)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(a) })
                    .padding(vertical = Dimens.s),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val color = swatches.getValue(a)
                Box(
                    Modifier.size(46.dp)
                        .border(if (isSelected) 2.5.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                        .padding(if (isSelected) 5.dp else 0.dp)
                        .clip(CircleShape)
                        .background(
                            if (a == Accent.CUSTOM) {
                                Brush.sweepGradient(listOf(Color(0xFFE57373), Color(0xFFFFD54F), Color(0xFF81C784), Color(0xFF4FC3F7), Color(0xFF9575CD), Color(0xFFE57373)))
                            } else {
                                SolidColor(color)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (a == Accent.CUSTOM) Box(Modifier.size(24.dp).clip(CircleShape).background(color))
                    when {
                        isSelected && a != Accent.CUSTOM -> Icon(Icons.Filled.Check, null, tint = markOn(color))
                        // Yomu's own palette carries its mark: 読 ("read").
                        a == Accent.YOMU -> Text("読", color = markOn(color), fontFamily = FontFamily.Serif, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (selected == Accent.CUSTOM && !dimmed) {
        val save = { onCustom(Color.hsl(hue, sat, 0.6f).toArgb().toLong() and 0xFFFFFFFFL) }
        GradientSlider(
            title = stringResource(R.string.set_accent_hue),
            value = hue,
            range = 0f..359f,
            brush = Brush.horizontalGradient((0..6).map { Color.hsl(it * 60f % 360f, sat, 0.6f) }),
            label = "${hue.toInt()}°",
            onChange = { hue = it },
            onDone = save,
        )
        GradientSlider(
            title = stringResource(R.string.set_accent_saturation),
            value = sat,
            range = SAT_MIN..SAT_MAX,
            brush = Brush.horizontalGradient(listOf(Color.hsl(hue, SAT_MIN, 0.6f), Color.hsl(hue, SAT_MAX, 0.6f))),
            label = "${(sat * 100).toInt()}%",
            onChange = { sat = it },
            onDone = save,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradientSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    brush: Brush,
    label: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            track = { Box(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(brush)) },
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

private fun Accent.label(): Int = when (this) {
    Accent.YOMU -> R.string.set_accent_yomu
    Accent.PURPLE -> R.string.set_accent_purple
    Accent.INDIGO -> R.string.set_accent_indigo
    Accent.BLUE -> R.string.set_accent_blue
    Accent.TEAL -> R.string.set_accent_teal
    Accent.GREEN -> R.string.set_accent_green
    Accent.ORANGE -> R.string.set_accent_orange
    Accent.RED -> R.string.set_accent_red
    Accent.PINK -> R.string.set_accent_pink
    Accent.CLASSIC -> R.string.set_accent_classic
    Accent.CUSTOM -> R.string.set_accent_custom
}

/** Dark ink on light swatches, white on dark ones. */
private fun markOn(color: Color): Color = if (color.luminance() > 0.4f) Color(0xFF1A1530) else Color.White

private const val SAT_MIN = 0.18f
private const val SAT_MAX = 0.72f

@Composable
internal fun ColumnScope.LibrarySection(vm: SettingsViewModel) {
    val s = vm.settings
    val folders by s.scanFolders.state()
    var removeFolder by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::addScanFolder) }
    SwitchPref(vm, s.libraryGrid, R.string.set_grid)
    val auto = stringResource(R.string.set_auto)
    IntSliderPref(vm, s.gridColumns, R.string.set_columns, 0..6, label = { if (it == 0) auto else it.toString() })
    SwitchPref(vm, s.showContinueReading, R.string.set_show_continue)
    SwitchPref(vm, s.hideCovers, R.string.set_hide_covers, R.string.set_hide_covers_sum)
    ChoicePref(vm, s.importMode, R.string.set_import_mode, listOf(
        "COPY" to stringResource(R.string.set_import_copy),
        "LINK" to stringResource(R.string.set_import_link),
    ))
    SwitchPref(vm, s.askDeleteFiles, R.string.set_ask_delete)
    PreferenceHeader(stringResource(R.string.set_folders))
    folders.sorted().forEach { f ->
        PreferenceItem(
            title = android.net.Uri.decode(f.substringAfterLast("/tree/").substringAfterLast(':').ifBlank { f }),
            summary = android.net.Uri.decode(f.substringAfterLast("/tree/")),
            icon = Icons.Filled.Folder,
            trailing = { IconButton(onClick = { removeFolder = f }) { Icon(Icons.Filled.Delete, stringResource(CoreR.string.delete)) } },
        )
    }
    PreferenceItem(stringResource(R.string.set_add_folder), stringResource(R.string.set_add_folder_sum), settingIcon(R.string.set_add_folder), onClick = { pick.launch(null) })
    SwitchPref(vm, s.autoScan, R.string.set_auto_scan, R.string.set_auto_scan_sum, after = { vm.rescheduleScan() })
    val context = LocalContext.current
    ChoicePref(vm, s.autoScanHours, R.string.set_scan_interval, listOf(1, 6, 12, 24, 48).map { it to hoursLabel(context, it) }, after = { vm.rescheduleScan() })
    PreferenceItem(stringResource(R.string.set_scan_now), onClick = vm::scanNow, icon = settingIcon(R.string.set_scan_now))
    removeFolder?.let { f ->
        ConfirmDialog(stringResource(R.string.set_remove_folder), android.net.Uri.decode(f), stringResource(CoreR.string.delete), onConfirm = { vm.removeScanFolder(f) }, onDismiss = { removeFolder = null })
    }
}

@Composable
internal fun ColumnScope.ReaderSection(vm: SettingsViewModel, links: SettingsLinks) {
    val s = vm.settings
    val c by s.commonReader.state()
    val t by s.textSettings.state()
    PreferenceHeader(stringResource(R.string.set_reader_general))
    PreferenceSwitch(stringResource(R.string.set_fullscreen), c.fullscreen, { v -> vm.update(s.commonReader) { it.copy(fullscreen = v) } }, icon = settingIcon(R.string.set_fullscreen))
    PreferenceSwitch(stringResource(R.string.set_cutout), c.drawUnderCutout, { v -> vm.update(s.commonReader) { it.copy(drawUnderCutout = v) } }, icon = settingIcon(R.string.set_cutout))
    PreferenceSwitch(stringResource(R.string.set_tap_turn), c.tapToTurn, { v -> vm.update(s.commonReader) { it.copy(tapToTurn = v) } }, icon = settingIcon(R.string.set_tap_turn))
    PreferenceSwitch(stringResource(R.string.set_volume_keys), c.volumeKeys, { v -> vm.update(s.commonReader) { it.copy(volumeKeys = v) } }, stringResource(R.string.set_volume_keys_sum), icon = settingIcon(R.string.set_volume_keys))
    PreferenceChoice(
        stringResource(R.string.set_screen_timeout),
        ScreenTimeout.entries.map { it to timeoutLabel(it) },
        c.screenTimeout,
        { v -> vm.update(s.commonReader) { it.copy(screenTimeout = v) } },
        icon = settingIcon(R.string.set_screen_timeout),
    )
    PreferenceChoice(
        stringResource(R.string.set_orientation),
        listOf(
            ReaderOrientation.AUTO to stringResource(R.string.set_orientation_auto),
            ReaderOrientation.PORTRAIT to stringResource(R.string.set_orientation_portrait),
            ReaderOrientation.LANDSCAPE to stringResource(R.string.set_orientation_landscape),
            ReaderOrientation.LOCKED to stringResource(R.string.set_orientation_locked),
        ),
        c.orientation,
        { v -> vm.update(s.commonReader) { it.copy(orientation = v) } },
        icon = settingIcon(R.string.set_orientation),
    )
    val never = stringResource(R.string.set_never)
    SliderSetting(stringResource(R.string.set_autohide), c.autoHideMenuSeconds.toFloat(), 0f..10f, steps = 9, label = { if (it < 1f) never else "${it.toInt()} s" }, icon = settingIcon(R.string.set_autohide)) { v ->
        vm.update(s.commonReader) { it.copy(autoHideMenuSeconds = v.toInt()) }
    }
    SwitchPref(vm, s.showReadingStats, R.string.set_reading_stats, R.string.set_reading_stats_sum)
    PreferenceItem(stringResource(R.string.set_open_stats), onClick = links.onOpenStats, icon = settingIcon(R.string.set_open_stats))

    PreferenceHeader(stringResource(R.string.set_reader_text))
    SliderSetting(stringResource(R.string.set_font_size), t.fontSizePercent.toFloat(), 50f..250f, steps = 39, label = { "${it.toInt()}%" }, icon = settingIcon(R.string.set_font_size)) { v ->
        vm.update(s.textSettings) { it.copy(fontSizePercent = v.toInt()) }
    }
    SliderSetting(stringResource(R.string.set_line_height), t.lineHeight, 1f..2.5f, steps = 14, label = { String.format(Locale.US, "%.1f", it) }, icon = settingIcon(R.string.set_line_height)) { v ->
        vm.update(s.textSettings) { it.copy(lineHeight = v) }
    }
    SliderSetting(stringResource(R.string.set_margins), t.marginHorizontal.toFloat(), 0f..64f, steps = 15, label = { "${it.toInt()} dp" }, icon = settingIcon(R.string.set_margins)) { v ->
        vm.update(s.textSettings) { it.copy(marginHorizontal = v.toInt()) }
    }
    PreferenceChoice(
        stringResource(R.string.set_reader_theme),
        ReaderTheme.entries.filter { it != ReaderTheme.CUSTOM }.map { it to readerThemeLabel(it) },
        t.theme,
        { v -> vm.update(s.textSettings) { it.copy(theme = v) } },
        icon = settingIcon(R.string.set_reader_theme),
    )
    PreferenceSwitch(stringResource(R.string.set_paged), t.paged, { v -> vm.update(s.textSettings) { it.copy(paged = v) } }, stringResource(R.string.set_paged_sum), icon = settingIcon(R.string.set_paged))
    PreferenceSwitch(stringResource(R.string.set_hyphenation), t.hyphenation, { v -> vm.update(s.textSettings) { it.copy(hyphenation = v) } }, icon = settingIcon(R.string.set_hyphenation))
    PreferenceSwitch(stringResource(R.string.set_publisher_styles), t.publisherStyles, { v -> vm.update(s.textSettings) { it.copy(publisherStyles = v) } }, stringResource(R.string.set_publisher_styles_sum), icon = settingIcon(R.string.set_publisher_styles))
    PreferenceSwitch(stringResource(R.string.set_highlight_words), t.highlightSavedWords, { v -> vm.update(s.textSettings) { it.copy(highlightSavedWords = v) } }, icon = settingIcon(R.string.set_highlight_words))

    PreferenceHeader(stringResource(R.string.set_reader_gestures))
    ChoicePref(vm, s.wordTap, R.string.set_word_tap, listOf(
        WordTapGesture.OFF to stringResource(R.string.set_off),
        WordTapGesture.SINGLE_TAP to stringResource(R.string.set_single_tap),
        WordTapGesture.DOUBLE_TAP to stringResource(R.string.set_double_tap),
    ))
    val engine by s.webSearch.state()
    ChoicePref(vm, s.webSearch, R.string.set_web_search, listOf(
        WebSearchEngine.GOOGLE to "Google",
        WebSearchEngine.DUCKDUCKGO to "DuckDuckGo",
        WebSearchEngine.YANDEX to "Yandex",
        WebSearchEngine.WIKIPEDIA to "Wikipedia",
        WebSearchEngine.CUSTOM to stringResource(R.string.set_custom),
    ))
    if (engine == WebSearchEngine.CUSTOM) TextPref(vm, s.customSearchUrl, R.string.set_custom_search_url, summaryWhenEmpty = R.string.set_custom_search_url_sum)

    PreferenceHeader(stringResource(R.string.set_tts))
    SliderSetting(stringResource(R.string.set_tts_speed), c.ttsSpeed, 0.5f..2.5f, steps = 19, label = { String.format(Locale.US, "%.1f×", it) }, icon = settingIcon(R.string.set_tts_speed)) { v ->
        vm.update(s.commonReader) { it.copy(ttsSpeed = v) }
    }
    SliderSetting(stringResource(R.string.set_tts_pitch), c.ttsPitch, 0.5f..2f, steps = 14, label = { String.format(Locale.US, "%.1f", it) }, icon = settingIcon(R.string.set_tts_pitch)) { v ->
        vm.update(s.commonReader) { it.copy(ttsPitch = v) }
    }
}

@Composable
private fun timeoutLabel(t: ScreenTimeout): String = when (t) {
    ScreenTimeout.SYSTEM -> stringResource(R.string.set_timeout_system)
    ScreenTimeout.ALWAYS -> stringResource(R.string.set_timeout_always)
    else -> stringResource(R.string.set_minutes, t.minutes)
}

@Composable
private fun readerThemeLabel(t: ReaderTheme): String = stringResource(
    when (t) {
        ReaderTheme.LIGHT -> R.string.set_rtheme_light
        ReaderTheme.SEPIA -> R.string.set_rtheme_sepia
        ReaderTheme.GRAY -> R.string.set_rtheme_gray
        ReaderTheme.DARK -> R.string.set_rtheme_dark
        ReaderTheme.BLACK -> R.string.set_rtheme_black
        ReaderTheme.CUSTOM -> R.string.set_custom
    },
)

@Composable
internal fun ColumnScope.MangaSection(vm: SettingsViewModel) {
    val s = vm.settings
    val m by s.mangaSettings.state()
    val p by s.pdfSettings.state()
    val updateManga: MangaSettingsUpdate = { transform -> vm.update(s.mangaSettings, transform) }
    MangaPageSettings(m, updateManga)
    MangaControlSettings(m, updateManga)

    PreferenceHeader("PDF")
    PreferenceChoice(
        stringResource(R.string.set_pdf_mode),
        listOf(
            PdfMode.SCROLL to stringResource(R.string.set_pdf_scroll),
            PdfMode.PAGED to stringResource(R.string.set_pdf_paged),
            PdfMode.DUAL to stringResource(R.string.set_pdf_dual),
        ),
        p.mode,
        { v -> vm.update(s.pdfSettings) { it.copy(mode = v) } },
        icon = settingIcon(R.string.set_pdf_mode),
    )
    PreferenceSwitch(stringResource(R.string.set_pdf_night), p.night, { v -> vm.update(s.pdfSettings) { it.copy(night = v) } }, icon = settingIcon(R.string.set_pdf_night))
    PreferenceSwitch(stringResource(R.string.set_pdf_autocrop), p.autoCrop, { v -> vm.update(s.pdfSettings) { it.copy(autoCrop = v) } }, icon = settingIcon(R.string.set_pdf_autocrop))
    SwitchPref(vm, s.pdfWriteAnnotations, R.string.set_pdf_write_annotations, R.string.set_pdf_write_annotations_sum)

    PreferenceHeader(stringResource(R.string.set_ocr))
    ChoicePref(vm, s.ocrLanguage, R.string.set_ocr_language, listOf(
        "ja" to "日本語", "zh" to "中文", "ko" to "한국어", "latin" to stringResource(R.string.set_ocr_latin),
    ))
}

@Composable
internal fun ColumnScope.TranslationSection(vm: SettingsViewModel, links: SettingsLinks) {
    val s = vm.settings
    val context = LocalContext.current
    val engine by s.onlineEngine.state()
    ChoicePref(vm, s.translationMode, R.string.set_translation_mode, listOf(
        TranslationMode.OFFLINE_FIRST to stringResource(R.string.set_mode_offline_first),
        TranslationMode.OFFLINE_ONLY to stringResource(R.string.set_mode_offline),
        TranslationMode.ONLINE_ONLY to stringResource(R.string.set_mode_online),
    ))
    ChoicePref(vm, s.onlineEngine, R.string.set_engine, listOf(
        "google" to "Google Translate",
        "deepl" to "DeepL",
        "libre" to "LibreTranslate",
        "llm" to stringResource(R.string.set_engine_llm),
    ))
    ChoicePref(vm, s.targetLang, R.string.set_target_lang, languageOptions(context, LANGS, R.string.set_system_language))
    ChoicePref(vm, s.sourceLang, R.string.set_source_lang, languageOptions(context, LANGS, R.string.set_auto_detect))
    when (engine) {
        "deepl" -> TextPref(vm, s.deeplKey, R.string.set_deepl_key, secret = true)
        "libre" -> {
            TextPref(vm, s.libreUrl, R.string.set_libre_url)
            TextPref(vm, s.libreKey, R.string.set_libre_key, secret = true)
        }
        "llm" -> {
            TextPref(vm, s.llmUrl, R.string.set_llm_url)
            TextPref(vm, s.llmKey, R.string.set_llm_key, secret = true)
            TextPref(vm, s.llmModel, R.string.set_llm_model)
            TextPref(vm, s.llmPrompt, R.string.set_llm_prompt, multiline = true)
        }
    }
    Text(stringResource(R.string.set_online_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs))
    SwitchPref(vm, s.translationCache, R.string.set_translation_cache)
    SwitchPref(vm, s.modelsWifiOnly, R.string.set_models_wifi)
    PreferenceItem(stringResource(R.string.set_language_packs), stringResource(R.string.set_language_packs_sum), onClick = links.onOpenLanguagePacks, icon = settingIcon(R.string.set_language_packs))

    PreferenceHeader(stringResource(R.string.set_dictionary))
    SwitchPref(vm, s.onlineDictionary, R.string.set_online_dictionary, R.string.set_online_dictionary_sum)
    ChoicePref(vm, s.dictionaryLang, R.string.set_dictionary_lang, languageOptions(context, LANGS, R.string.set_system_language))
    PreferenceItem(stringResource(R.string.set_dictionaries), stringResource(R.string.set_dictionaries_sum), onClick = links.onOpenDictionaries, icon = settingIcon(R.string.set_dictionaries))

    PreferenceHeader(stringResource(R.string.set_words))
    IntSliderPref(vm, s.dailyGoal, R.string.set_daily_goal, 5..100, step = 5)
    val reminder by s.reminderEnabled.state()
    val hour by s.reminderHour.state()
    val minute by s.reminderMinute.state()
    var pickTime by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.set(s.reminderEnabled, true) { vm.rescheduleReminder() }
    }
    PreferenceSwitch(stringResource(R.string.set_reminder), reminder, { on ->
        if (on && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else vm.set(s.reminderEnabled, on) { vm.rescheduleReminder() }
    }, stringResource(R.string.set_reminder_sum), icon = settingIcon(R.string.set_reminder))
    PreferenceItem(stringResource(R.string.set_reminder_time), String.format(Locale.getDefault(), "%02d:%02d", hour, minute), enabled = reminder, onClick = { pickTime = true }, icon = settingIcon(R.string.set_reminder_time))
    if (pickTime) {
        TimeDialog(hour, minute, onDismiss = { pickTime = false }) { h, m ->
            vm.set(s.reminderHour, h)
            vm.set(s.reminderMinute, m) { vm.rescheduleReminder() }
            pickTime = false
        }
    }
    val modes by s.reviewModes.state()
    PreferenceHeader(stringResource(R.string.set_review_modes))
    listOf(
        "WORD_TO_TRANSLATION" to R.string.set_review_word,
        "TRANSLATION_TO_WORD" to R.string.set_review_translation,
        "CLOZE" to R.string.set_review_cloze,
    ).forEach { (key, label) ->
        Row(
            Modifier.fillMaxWidth().clickable { vm.update(s.reviewModes) { if (key in it && it.size > 1) it - key else it + key } }.padding(horizontal = Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = key in modes, onCheckedChange = null, modifier = Modifier.padding(Dimens.s))
            Text(stringResource(label))
        }
    }
}

@Composable
internal fun ColumnScope.CatalogSection(vm: SettingsViewModel, links: SettingsLinks) {
    val s = vm.settings
    val context = LocalContext.current
    val trusted by s.trustedExtensions.state()
    var revoke by remember { mutableStateOf(false) }
    ChoicePref(vm, s.updateIntervalHours, R.string.set_update_interval, listOf(0, 3, 6, 12, 24, 48).map { it to hoursLabel(context, it) }, after = { vm.rescheduleUpdates() })
    SwitchPref(vm, s.updateWifiOnly, R.string.set_update_wifi, after = { vm.rescheduleUpdates() })
    SwitchPref(vm, s.showNsfw, R.string.set_show_nsfw)
    SwitchPref(vm, s.incognito, R.string.set_incognito, R.string.set_incognito_sum)
    PreferenceItem(stringResource(R.string.set_extensions), stringResource(R.string.set_extensions_sum), onClick = links.onOpenExtensions, icon = settingIcon(R.string.set_extensions))
    val hiddenSources by s.hiddenSources.state()
    val hiddenExtensions by s.hiddenExtensions.state()
    var unhide by remember { mutableStateOf(false) }
    PreferenceItem(
        stringResource(R.string.set_hidden_sources),
        stringResource(R.string.set_hidden_sources_sum, hiddenExtensions.size, hiddenSources.size),
        enabled = hiddenSources.isNotEmpty() || hiddenExtensions.isNotEmpty(),
        onClick = { unhide = true },
        icon = settingIcon(R.string.set_hidden_sources),
    )
    if (unhide) {
        ConfirmDialog(
            stringResource(R.string.set_hidden_sources),
            stringResource(R.string.set_hidden_sources_text),
            stringResource(R.string.set_hidden_sources_show),
            onConfirm = { vm.set(s.hiddenSources, emptySet()); vm.set(s.hiddenExtensions, emptySet()) },
            onDismiss = { unhide = false },
        )
    }
    PreferenceItem(stringResource(R.string.set_revoke_trust), stringResource(R.string.set_trusted_count, trusted.size), enabled = trusted.isNotEmpty(), onClick = { revoke = true }, icon = settingIcon(R.string.set_revoke_trust))
    if (revoke) {
        ConfirmDialog(stringResource(R.string.set_revoke_trust), stringResource(R.string.set_revoke_trust_text), stringResource(R.string.set_revoke), onConfirm = { vm.set(s.trustedExtensions, emptySet()) }, onDismiss = { revoke = false })
    }
}

@Composable
internal fun ColumnScope.DownloadsSection(vm: SettingsViewModel, links: SettingsLinks, onOpenStorage: () -> Unit) {
    val s = vm.settings
    SwitchPref(vm, s.downloadWifiOnly, R.string.set_download_wifi)
    IntSliderPref(vm, s.maxParallelDownloads, R.string.set_parallel, 1..5)
    IntSliderPref(vm, s.pageDownloadThreads, R.string.set_page_threads, 1..6)
    SwitchPref(vm, s.autoDownloadNew, R.string.set_auto_download_new, R.string.set_auto_download_new_sum)
    ChoicePref(
        vm, s.downloadAhead, R.string.set_download_ahead,
        listOf(0 to stringResource(R.string.set_download_ahead_off)) + listOf(2, 3, 5, 10).map { it to pluralStringResource(R.plurals.set_download_ahead_n, it, it) },
    )
    val noRetries = stringResource(R.string.set_download_retries_off)
    IntSliderPref(vm, s.downloadRetries, R.string.set_download_retries, 0..5, label = { if (it == 0) noRetries else it.toString() })
    SwitchPref(vm, s.autoDeleteRead, R.string.set_auto_delete, R.string.set_auto_delete_sum)
    SwitchPref(vm, s.packCbz, R.string.set_pack_cbz, R.string.set_pack_cbz_sum)
    PreferenceItem(stringResource(R.string.set_open_downloads), onClick = links.onOpenDownloads, icon = settingIcon(R.string.set_open_downloads))
    PreferenceItem(stringResource(R.string.set_storage), stringResource(R.string.set_storage_sum), onClick = onOpenStorage, icon = settingIcon(R.string.set_storage))
}

@Composable
internal fun ColumnScope.NetworkSection(vm: SettingsViewModel) {
    val s = vm.settings
    PreferenceHeader(stringResource(R.string.set_wifi_only_header))
    SwitchPref(vm, s.downloadWifiOnly, R.string.set_download_wifi)
    SwitchPref(vm, s.updateWifiOnly, R.string.set_net_update_wifi, after = { vm.rescheduleUpdates() })
    SwitchPref(vm, s.modelsWifiOnly, R.string.set_models_wifi)
    PreferenceHeader(stringResource(R.string.set_mobile_header))
    SwitchPref(vm, s.dataSaver, R.string.set_data_saver, R.string.set_data_saver_sum)
    SwitchPref(vm, s.coversOnMobile, R.string.set_covers_mobile, R.string.set_covers_mobile_sum)
    SwitchPref(vm, s.warnMobileReading, R.string.set_warn_mobile, R.string.set_warn_mobile_sum)
    TrafficCounter()
}

/** Traffic used by the app since the last reset: total, a mobile/Wi-Fi bar and a reset action. */
@Composable
private fun TrafficCounter() {
    val stats by TrafficMeter.stats.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    // Publishes the bytes counted since the last update (the counter publishes at most once a second).
    LaunchedEffect(Unit) { TrafficMeter.refresh() }
    val metered = remember(stats) { runCatching { TrafficMeter.isMetered() }.getOrDefault(false) }
    val since = remember(stats.since) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(stats.since)) }
    val mobileColor = MaterialTheme.colorScheme.tertiary
    val wifiColor = MaterialTheme.colorScheme.primary
    PreferenceHeader(stringResource(R.string.set_traffic))
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs)) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Icon(Icons.Outlined.DataUsage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(Files.humanSize(stats.total), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.set_traffic_since, since),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val share by animateMotionFloat(if (stats.total > 0) stats.mobile.toFloat() / stats.total else 0f, label = "traffic-share")
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                    .background(if (stats.total > 0) wifiColor else MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (share > 0f) Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(mobileColor))
            }
            Row(Modifier.fillMaxWidth()) {
                TrafficLegend(Icons.Outlined.SignalCellularAlt, stringResource(R.string.set_traffic_mobile), stats.mobile, mobileColor, Modifier.weight(1f))
                TrafficLegend(Icons.Outlined.Wifi, stringResource(R.string.set_traffic_wifi), stats.wifi, wifiColor, Modifier.weight(1f))
            }
            Text(
                stringResource(if (metered) R.string.set_traffic_now_mobile else R.string.set_traffic_now_wifi),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        stringResource(R.string.set_traffic_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
    )
    PreferenceItem(stringResource(R.string.set_traffic_reset), icon = settingIcon(R.string.set_traffic_reset), enabled = stats.total > 0, onClick = { confirm = true })
    if (confirm) {
        ConfirmDialog(
            stringResource(R.string.set_traffic_reset),
            stringResource(R.string.set_traffic_reset_text),
            stringResource(R.string.set_reset),
            onConfirm = { TrafficMeter.reset() },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun TrafficLegend(icon: ImageVector, label: String, bytes: Long, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Files.humanSize(bytes), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
internal fun ColumnScope.StorageSection(vm: SettingsViewModel) {
    val usage by vm.usage.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshUsage() }
    val u = usage
    if (u == null) {
        PreferenceItem(stringResource(R.string.set_calculating), icon = settingIcon(R.string.set_calculating))
    } else {
        PreferenceItem(stringResource(R.string.set_storage_total), Files.humanSize(u.total), icon = settingIcon(R.string.set_storage_total))
        PreferenceItem(stringResource(R.string.set_storage_books), Files.humanSize(u.books), icon = settingIcon(R.string.set_storage_books))
        PreferenceItem(stringResource(R.string.set_storage_chapters), Files.humanSize(u.chapters), icon = settingIcon(R.string.set_storage_chapters))
        PreferenceItem(stringResource(R.string.set_storage_converted), Files.humanSize(u.converted), icon = settingIcon(R.string.set_storage_converted))
        PreferenceItem(stringResource(R.string.set_storage_covers), Files.humanSize(u.covers), icon = settingIcon(R.string.set_storage_covers))
        PreferenceItem(stringResource(R.string.set_clear_cache), Files.humanSize(u.cache), onClick = { confirm = true }, icon = settingIcon(R.string.set_clear_cache))
    }
    if (confirm) {
        ConfirmDialog(stringResource(R.string.set_clear_cache), stringResource(R.string.set_clear_cache_text), stringResource(CoreR.string.clear), onConfirm = vm::clearCache, onDismiss = { confirm = false })
    }
}

@Composable
internal fun ColumnScope.BackupSection(vm: SettingsViewModel, onOpenSync: () -> Unit) {
    val s = vm.settings
    val context = LocalContext.current
    var includeFiles by rememberSaveable { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.createBackup(it, includeFiles) } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    val backupFolderPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::setBackupFolder) }
    val lastBackup by s.lastBackupAt.state()
    val backupFolder by s.backupFolder.state()
    val syncEnabled by s.syncEnabled.state()

    PreferenceItem(stringResource(R.string.set_backup_create), stringResource(R.string.set_backup_create_sum), onClick = { create.launch(vm.suggestedBackupName()) }, icon = settingIcon(R.string.set_backup_create))
    PreferenceSwitch(stringResource(R.string.set_backup_files), includeFiles, { includeFiles = it }, stringResource(R.string.set_backup_files_sum), icon = settingIcon(R.string.set_backup_files))
    PreferenceItem(stringResource(R.string.set_backup_restore), stringResource(R.string.set_backup_restore_sum), onClick = { open.launch(arrayOf("*/*")) }, icon = settingIcon(R.string.set_backup_restore))
    MihonImportItem(vm)

    PreferenceHeader(stringResource(R.string.set_auto_backup))
    ChoicePref(vm, s.autoBackupHours, R.string.set_auto_backup, listOf(0, 12, 24, 72, 168).map { it to hoursLabel(context, it) }, after = { vm.rescheduleBackup() })
    PreferenceItem(stringResource(R.string.set_backup_folder), backupFolder.takeIf { it.isNotBlank() }?.let { android.net.Uri.decode(it.substringAfterLast("/tree/")) } ?: stringResource(R.string.set_not_set), onClick = { backupFolderPick.launch(null) }, icon = settingIcon(R.string.set_backup_folder))
    IntSliderPref(vm, s.backupKeep, R.string.set_backup_keep, 1..20)
    PreferenceItem(stringResource(R.string.set_backup_now), formatTime(lastBackup)?.let { stringResource(R.string.set_last_time, it) }, enabled = backupFolder.isNotBlank(), onClick = vm::backupNowToFolder, icon = settingIcon(R.string.set_backup_now))

    PreferenceHeader(stringResource(R.string.set_sync))
    PreferenceItem(
        stringResource(R.string.set_sync_section),
        stringResource(if (syncEnabled) R.string.set_sync_on else R.string.set_sync_section_sum),
        onClick = onOpenSync,
        icon = settingIcon(R.string.set_sync),
    )

    PreferenceHeader(stringResource(R.string.set_reset))
    PreferenceItem(stringResource(R.string.set_reset_all), stringResource(R.string.set_reset_all_sum), onClick = { confirmReset = true }, icon = settingIcon(R.string.set_reset_all))

    restoreUri?.let { uri ->
        ConfirmDialog(stringResource(R.string.set_backup_restore), stringResource(R.string.set_restore_confirm), stringResource(R.string.set_restore), onConfirm = { vm.restoreBackup(uri) }, onDismiss = { restoreUri = null })
    }
    if (confirmReset) {
        ConfirmDialog(stringResource(R.string.set_reset_all), stringResource(R.string.set_reset_all_confirm), stringResource(R.string.set_reset), onConfirm = vm::resetAll, onDismiss = { confirmReset = false })
    }
}

@Composable
internal fun ColumnScope.AdvancedSection(vm: SettingsViewModel, onOpenLogs: () -> Unit) {
    val s = vm.settings
    SwitchPref(vm, s.verboseLogging, R.string.set_verbose_logging, R.string.set_verbose_logging_sum)
    PreferenceItem(stringResource(R.string.set_error_log), stringResource(R.string.set_error_log_sum), onClick = onOpenLogs, icon = settingIcon(R.string.set_error_log))
    PreferenceItem(stringResource(R.string.set_reset_onboarding), stringResource(R.string.set_reset_onboarding_sum), onClick = { vm.set(s.onboardingDone, false) }, icon = settingIcon(R.string.set_reset_onboarding))
    PreferenceItem(stringResource(R.string.set_reset_hints), onClick = {
        vm.set(s.shownHints, emptySet())
        vm.set(s.readerHintsShown, false)
    }, icon = settingIcon(R.string.set_reset_hints))
}
