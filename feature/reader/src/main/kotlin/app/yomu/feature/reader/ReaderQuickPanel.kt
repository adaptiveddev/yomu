package app.yomu.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.yomu.core.ui.theme.Dimens
import app.yomu.domain.model.CommonReaderSettings
import app.yomu.domain.model.ScreenTimeout
import app.yomu.domain.model.TextReaderSettings
import app.yomu.core.ui.R as CoreR

/** Screen timeout after switching "keep the screen on" off again. */
internal val DEFAULT_TIMEOUT = ScreenTimeout.MIN_10

/** Flips "keep the screen on": [ScreenTimeout.ALWAYS] versus the usual timeout. */
internal fun CommonReaderSettings.withKeepAwake(on: Boolean): CommonReaderSettings =
    copy(screenTimeout = if (on) ScreenTimeout.ALWAYS else if (screenTimeout == ScreenTimeout.ALWAYS) DEFAULT_TIMEOUT else screenTimeout)

/**
 * Brightness, warm filter, keep-awake (and font size and theme for text) above the bottom bar: changed without opening
 * the full settings, so the page stays visible and the effect can be judged at once.
 */
@Composable
internal fun ReaderQuickPanel(
    common: CommonReaderSettings,
    text: TextReaderSettings?,
    vm: ReaderViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = Dimens.l, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reader_quick_display), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(CoreR.string.close)) }
            }
            if (text != null) QuickTextBar(text, vm)
            val brightnessLabel = stringResource(R.string.rs_brightness)
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BrightnessMedium, null, Modifier.size(20.dp))
                Slider(
                    value = if (common.brightness < 0f) 0.5f else common.brightness,
                    onValueChange = { v -> vm.updateCommon { it.copy(brightness = v.coerceAtLeast(0.01f)) } },
                    enabled = common.brightness >= 0f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = brightnessLabel },
                )
                FilterChip(
                    selected = common.brightness < 0f,
                    onClick = { vm.updateCommon { it.copy(brightness = if (it.brightness < 0f) 0.5f else -1f) } },
                    label = { Text(stringResource(R.string.reader_brightness_auto)) },
                )
            }
            val warmLabel = stringResource(R.string.rs_warm)
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WbTwilight, null, Modifier.size(20.dp))
                Slider(
                    value = common.warmFilter,
                    onValueChange = { v -> vm.updateCommon { it.copy(warmFilter = v) } },
                    valueRange = 0f..0.8f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = warmLabel },
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.Start) {
                FilterChip(
                    selected = common.screenTimeout == ScreenTimeout.ALWAYS,
                    onClick = { vm.updateCommon { it.withKeepAwake(it.screenTimeout != ScreenTimeout.ALWAYS) } },
                    label = { Text(stringResource(R.string.reader_keep_awake)) },
                )
            }
        }
    }
}
