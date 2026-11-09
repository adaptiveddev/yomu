package app.yomu.feature.reader.manga

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CropOriginal
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.South
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.West
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.yomu.core.ui.components.ColorSwatches
import app.yomu.core.ui.components.OptionTiles
import app.yomu.core.ui.components.PreferenceHeader
import app.yomu.core.ui.components.PreferenceSlider
import app.yomu.core.ui.components.PreferenceSwitch
import app.yomu.core.ui.components.TileOption
import app.yomu.core.ui.theme.Dimens
import app.yomu.domain.model.MangaBackground
import app.yomu.domain.model.MangaDirection
import app.yomu.domain.model.MangaSettings
import app.yomu.domain.model.ScaleType
import app.yomu.domain.model.TapZoneLayout
import kotlin.math.roundToInt

/** Changes the settings: receives a transformation of the current value. */
typealias MangaSettingsUpdate = ((MangaSettings) -> MangaSettings) -> Unit

/**
 * How pages look: reading mode, page size, page options and image filters.
 * Shared by the reader sheet (for this title or for all) and the app settings.
 */
@Composable
fun MangaPageSettings(s: MangaSettings, update: MangaSettingsUpdate) {
    PreferenceHeader(stringResource(R.string.mr_mode))
    OptionTiles(
        listOf(
            TileOption(MangaDirection.LTR, stringResource(R.string.mr_dir_ltr), Icons.Filled.East),
            TileOption(MangaDirection.RTL, stringResource(R.string.mr_dir_rtl), Icons.Filled.West),
            TileOption(MangaDirection.VERTICAL, stringResource(R.string.mr_dir_vertical), Icons.Filled.South),
            TileOption(MangaDirection.WEBTOON, stringResource(R.string.mr_dir_webtoon), Icons.Filled.ViewDay),
        ),
        s.direction,
        { d -> update { it.copy(direction = d) } },
    )
    Text(
        stringResource(
            when (s.direction) {
                MangaDirection.LTR -> R.string.mr_dir_ltr_sum
                MangaDirection.RTL -> R.string.mr_dir_rtl_sum
                MangaDirection.VERTICAL -> R.string.mr_dir_vertical_sum
                MangaDirection.WEBTOON -> R.string.mr_dir_webtoon_sum
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
    )

    if (s.direction != MangaDirection.WEBTOON) {
        PreferenceHeader(stringResource(R.string.mr_scale))
        OptionTiles(
            listOf(
                TileOption(ScaleType.FIT_SCREEN, stringResource(R.string.mr_scale_screen), Icons.Filled.FitScreen),
                TileOption(ScaleType.FIT_WIDTH, stringResource(R.string.mr_scale_width), Icons.Filled.Height, iconRotation = 90f),
                TileOption(ScaleType.FIT_HEIGHT, stringResource(R.string.mr_scale_height), Icons.Filled.Height),
                TileOption(ScaleType.ORIGINAL, stringResource(R.string.mr_scale_original), Icons.Filled.CropOriginal),
            ),
            s.scale,
            { v -> update { it.copy(scale = v) } },
        )
    }

    PreferenceHeader(stringResource(R.string.mr_background))
    ColorSwatches(
        listOf(
            Triple(MangaBackground.BLACK.argb, stringResource(R.string.mr_bg_black), MangaBackground.BLACK),
            Triple(MangaBackground.GRAY.argb, stringResource(R.string.mr_bg_gray), MangaBackground.GRAY),
            Triple(MangaBackground.WHITE.argb, stringResource(R.string.mr_bg_white), MangaBackground.WHITE),
        ),
        s.background,
        { v -> update { it.copy(background = v as MangaBackground) } },
    )

    PreferenceHeader(stringResource(R.string.mr_pages))
    if (s.direction == MangaDirection.WEBTOON) {
        val off = stringResource(R.string.mr_off)
        PreferenceSlider(
            stringResource(R.string.mr_webtoon_padding),
            s.webtoonSidePadding.toFloat(),
            0f..30f,
            { v -> update { it.copy(webtoonSidePadding = (v / 5f).roundToInt() * 5) } },
            { if (it < 1f) off else "${it.roundToInt()}%" },
            steps = 5,
        )
        PreferenceSwitch(stringResource(R.string.mr_webtoon_gaps), s.webtoonGaps, { v -> update { it.copy(webtoonGaps = v) } })
        PreferenceSwitch(
            stringResource(R.string.mr_webtoon_double_tap), s.webtoonDoubleTapZoom, { v -> update { it.copy(webtoonDoubleTapZoom = v) } },
            stringResource(R.string.mr_webtoon_double_tap_sum),
        )
    } else {
        PreferenceSwitch(stringResource(R.string.mr_dual), s.dualPageLandscape, { v -> update { it.copy(dualPageLandscape = v) } })
    }
    PreferenceSwitch(
        stringResource(R.string.mr_animation),
        s.pageAnimation,
        { v -> update { it.copy(pageAnimation = v) } },
        summary = stringResource(R.string.mr_animation_sum),
    )
    PreferenceSwitch(stringResource(R.string.mr_crop), s.cropBorders, { v -> update { it.copy(cropBorders = v) } })
    PreferenceSwitch(stringResource(R.string.mr_page_number), s.showPageNumber, { v -> update { it.copy(showPageNumber = v) } })
    PreferenceSlider(
        stringResource(R.string.mr_preload),
        s.preload.toFloat(),
        0f..8f,
        { v -> update { it.copy(preload = v.roundToInt()) } },
        { it.roundToInt().toString() },
        steps = 7,
    )

    PreferenceHeader(stringResource(R.string.mr_image))
    PreferenceSlider(
        stringResource(R.string.mr_brightness),
        s.brightness,
        -0.5f..0.5f,
        { v -> update { it.copy(brightness = (v * 20).roundToInt() / 20f) } },
        { val p = (it * 100).roundToInt(); if (p > 0) "+$p" else "$p" },
        icon = Icons.Filled.BrightnessMedium,
    )
    PreferenceSlider(
        stringResource(R.string.mr_contrast),
        s.contrast,
        0.5f..2f,
        { v -> update { it.copy(contrast = (v * 10).roundToInt() / 10f) } },
        { "%.1f×".format(it) },
        icon = Icons.Filled.Contrast,
    )
    PreferenceSwitch(stringResource(R.string.mr_gray), s.grayscale, { v -> update { it.copy(grayscale = v) } })
    PreferenceSwitch(stringResource(R.string.mr_invert), s.invert, { v -> update { it.copy(invert = v) } })
    if (s.hasImageFilters) {
        TextButton(
            onClick = { update { it.copy(brightness = 0f, contrast = 1f, grayscale = false, invert = false) } },
            modifier = Modifier.padding(horizontal = Dimens.s),
        ) {
            Icon(Icons.Filled.RestartAlt, null)
            Text(stringResource(R.string.mr_reset_filters), Modifier.padding(start = Dimens.s))
        }
    }
}

/** Where taps go and the hardware keys. [onPreviewZones] shows the zones over the open manga. */
@Composable
fun MangaControlSettings(s: MangaSettings, update: MangaSettingsUpdate, onPreviewZones: (() -> Unit)? = null) {
    PreferenceHeader(stringResource(R.string.mr_tap_zones))
    val rtl = s.direction == MangaDirection.RTL
    OptionTiles(
        TapZoneLayout.entries.map { layout ->
            TileOption(layout, zoneLabel(layout), preview = { _ -> TapZonesDiagram(layout, s.invertTapZones, rtl) })
        },
        s.tapZones,
        { v -> update { it.copy(tapZones = v) } },
    )
    val zones = s.tapZones != TapZoneLayout.DISABLED
    PreferenceSwitch(stringResource(R.string.mr_invert_zones), s.invertTapZones, { v -> update { it.copy(invertTapZones = v) } }, enabled = zones)
    PreferenceSwitch(stringResource(R.string.mr_show_zones), s.showTapZonesOnOpen, { v -> update { it.copy(showTapZonesOnOpen = v) } }, enabled = zones)
    if (onPreviewZones != null && zones && s.direction != MangaDirection.WEBTOON) {
        TextButton(onClick = onPreviewZones, modifier = Modifier.padding(horizontal = Dimens.s)) {
            Icon(Icons.Filled.TouchApp, null)
            Text(stringResource(R.string.mr_preview_zones), Modifier.padding(start = Dimens.s))
        }
    }
    PreferenceSwitch(stringResource(R.string.mr_volume_keys), s.volumeKeys, { v -> update { it.copy(volumeKeys = v) } })
    if (s.volumeKeys) {
        PreferenceSwitch(stringResource(R.string.mr_invert_volume), s.invertVolumeKeys, { v -> update { it.copy(invertVolumeKeys = v) } })
    }
}

@Composable
fun zoneLabel(layout: TapZoneLayout): String = stringResource(
    when (layout) {
        TapZoneLayout.L_SHAPE -> R.string.mr_zone_l
        TapZoneLayout.KINDLE -> R.string.mr_zone_kindle
        TapZoneLayout.EDGES -> R.string.mr_zone_edges
        TapZoneLayout.LEFT_RIGHT -> R.string.mr_zone_lr
        TapZoneLayout.DISABLED -> R.string.mr_zone_off
    },
)
