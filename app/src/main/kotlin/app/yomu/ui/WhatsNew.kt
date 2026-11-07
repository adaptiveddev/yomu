package app.yomu.ui

import androidx.annotation.ArrayRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.yomu.R
import app.yomu.core.ui.theme.Dimens
import app.yomu.feature.settings.ProjectLinks

/** Highlights of one release for the "What's new" sheet. */
@Immutable
data class WhatsNew(val versionCode: Long, val version: String, @ArrayRes val items: Int)

/**
 * Newest first. Every release adds its line here and its string array `whats_new_<version>` (see docs/RELEASING.md);
 * the sheet shows the releases newer than the one the user saw last, once, after the update.
 */
val WHATS_NEW = listOf(
    WhatsNew(16, "2.0.4", R.array.whats_new_2_0_4),
    WhatsNew(15, "2.0.3", R.array.whats_new_2_0_3),
    WhatsNew(14, "2.0.2", R.array.whats_new_2_0_2),
    WhatsNew(13, "2.0.1", R.array.whats_new_2_0_1),
    WhatsNew(12, "2.0.0", R.array.whats_new_2_0_0),
    WhatsNew(11, "1.9.0", R.array.whats_new_1_9_0),
    WhatsNew(10, "1.8.0", R.array.whats_new_1_8_0),
    WhatsNew(9, "1.7.0", R.array.whats_new_1_7_0),
)

/**
 * What the sheet shows after updating from [seen] to [current]: the releases in between, newest first.
 * [seen] 0 means an update from 1.6.0 or older, which did not remember it: the previous version is unknown, so only
 * [current] is shown rather than releases the user may have had for months.
 */
fun whatsNewSince(seen: Long, current: Long, all: List<WhatsNew> = WHATS_NEW): List<WhatsNew> = when {
    seen >= current -> emptyList()
    seen <= 0 -> all.filter { it.versionCode == current }
    else -> all.filter { it.versionCode in (seen + 1)..current }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(releases: List<WhatsNew>, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.xl).padding(bottom = Dimens.l)) {
            Text(stringResource(R.string.whats_new_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(top = Dimens.m),
                verticalArrangement = Arrangement.spacedBy(Dimens.m),
            ) {
                releases.forEach { release ->
                    Text(release.version, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    stringArrayResource(release.items).forEach { line -> WhatsNewLine(line) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = Dimens.l), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { runCatching { uri.openUri(ProjectLinks.RELEASES_URL) } }) { Text(stringResource(R.string.whats_new_all)) }
                Button(onClick = onDismiss, modifier = Modifier.padding(start = Dimens.s)) { Text(stringResource(R.string.whats_new_ok)) }
            }
        }
    }
}

@Composable
private fun WhatsNewLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
