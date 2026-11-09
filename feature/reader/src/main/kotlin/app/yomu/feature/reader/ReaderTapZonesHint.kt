package app.yomu.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.yomu.core.ui.theme.Dimens
import app.yomu.core.ui.components.LocalHintStore
import app.yomu.domain.model.TapZoneLayout
import app.yomu.feature.reader.manga.TapAction
import app.yomu.feature.reader.manga.TapZonesOverlay
import kotlinx.coroutines.launch
import app.yomu.core.ui.R as CoreR

private const val TAP_ZONES_HINT_KEY = "reader_tap_zones"

/**
 * First-run diagram of the real tap zones of the text and PDF readers, shown once.
 * Later hints wait for [onDone] so they never stack on top of it.
 */
@Composable
internal fun ReaderTapZonesHint(
    layout: TapZoneLayout,
    rtl: Boolean,
    edgeGestures: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val store = LocalHintStore.current
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) {
        if (store == null || store.isShown(TAP_ZONES_HINT_KEY)) onDone() else visible = true
    }
    if (!visible || store == null) return
    fun dismiss() {
        visible = false
        scope.launch { store.markShown(TAP_ZONES_HINT_KEY); onDone() }
    }
    val title = stringResource(R.string.tapzones_title)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .semantics { paneTitle = title }
            .clickable(onClickLabel = stringResource(CoreR.string.got_it), onClick = ::dismiss),
    ) {
        TapZonesOverlay(
            layout, inverted = false, rtl = rtl, sideFraction = 0.25f,
            label = {
                stringResource(
                    when (it) {
                        TapAction.PREV -> R.string.tapzones_prev
                        TapAction.NEXT -> R.string.tapzones_next
                        TapAction.MENU -> R.string.tapzones_menu
                    },
                )
            },
        )
        if (edgeGestures) {
            EdgeStrip(stringResource(R.string.tapzones_edge_brightness), start = true)
            EdgeStrip(stringResource(R.string.tapzones_edge_warm), start = false)
        }
        Button(onClick = ::dismiss, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Dimens.xl)) {
            Text(stringResource(CoreR.string.got_it))
        }
    }
}

@Composable
private fun BoxScope.EdgeStrip(label: String, start: Boolean) {
    Box(
        Modifier.align(if (start) Alignment.CenterStart else Alignment.CenterEnd)
            .fillMaxHeight().fillMaxWidth(0.12f).background(Color.White.copy(alpha = 0.22f)),
    )
    // The strip is too narrow for its label: the pill reaches into the page, above the zone labels so they never overlap.
    Text(
        "↕ $label",
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = Modifier
            .align(BiasAlignment(if (start) -1f else 1f, -0.3f))
            .padding(horizontal = Dimens.s)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
