package app.yomu.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yomu.core.common.AppError
import app.yomu.core.common.UiState
import app.yomu.core.ui.R
import app.yomu.core.ui.theme.Dimens

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(Dimens.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val primary = MaterialTheme.colorScheme.primary
        val tertiary = MaterialTheme.colorScheme.tertiary
        Box(
            Modifier.size(124.dp).clip(CircleShape).background(primary.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(92.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(primary.copy(alpha = 0.26f), tertiary.copy(alpha = 0.16f)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp), tint = primary)
            }
        }
        Spacer(Modifier.height(Dimens.l))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(Dimens.s))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Dimens.l))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun errorMessage(error: Throwable): String = errorMessage(androidx.compose.ui.platform.LocalContext.current, error)

/** Human readable message for [error]; usable outside of composition. Raw technical messages are shortened. */
fun errorMessage(context: android.content.Context, error: Throwable): String = when (val e = AppError.from(error)) {
    is AppError.NoNetwork -> context.getString(R.string.error_no_network)
    is AppError.Timeout -> context.getString(R.string.error_timeout)
    is AppError.Http -> when (e.code) {
        401, 403 -> context.getString(R.string.error_http_403, e.code)
        404, 410 -> context.getString(R.string.error_http_404, e.code)
        429 -> context.getString(R.string.error_http_429)
        in 500..599 -> context.getString(R.string.error_http_5xx, e.code)
        else -> context.getString(R.string.error_http, e.code)
    }
    is AppError.NeedsWebView -> context.getString(R.string.error_webview)
    is AppError.BadData -> context.getString(R.string.error_bad_data)
    is AppError.CorruptedFile -> context.getString(R.string.error_corrupted)
    is AppError.UnsupportedFormat -> context.getString(R.string.error_unsupported, e.format)
    is AppError.NoPermission -> context.getString(R.string.error_permission)
    is AppError.FileMissing -> context.getString(R.string.error_file_missing)
    is AppError.NoSpace -> context.getString(R.string.error_no_space)
    is AppError.NotConfigured -> context.getString(R.string.error_not_configured, e.what)
    is AppError.Incompatible -> context.getString(R.string.error_incompatible)
    is AppError.AudioLink -> context.getString(
        when (e.reason) {
            AppError.AudioLink.Reason.NO_LINK -> R.string.error_audio_no_link
            AppError.AudioLink.Reason.NOTEBOOKLM -> R.string.error_audio_notebooklm
            AppError.AudioLink.Reason.YOUTUBE_BLOCKED -> R.string.error_audio_youtube
            AppError.AudioLink.Reason.UNSUPPORTED -> R.string.error_audio_unsupported
        },
    )
    is AppError.Unknown -> error.message?.takeIf { it.isNotBlank() }?.let(::shorten) ?: context.getString(R.string.error_generic)
}

/** Messages of unknown errors can be whole JSON or HTML fragments: keep the first line, at most [max] characters. */
private fun shorten(message: String, max: Int = 160): String {
    val line = message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: message.trim()
    return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
}

@Composable
private fun errorTitle(e: AppError): String = stringResource(
    when (e) {
        is AppError.NoNetwork -> R.string.error_title_network
        is AppError.Timeout -> R.string.error_title_timeout
        is AppError.Http -> R.string.error_title_site
        is AppError.NeedsWebView -> R.string.error_title_check
        is AppError.BadData -> R.string.error_title_bad_data
        is AppError.CorruptedFile, is AppError.UnsupportedFormat, is AppError.NoPermission,
        is AppError.FileMissing, is AppError.NoSpace -> R.string.error_title_file
        is AppError.NotConfigured -> R.string.error_title_setup
        is AppError.Incompatible -> R.string.error_title_incompatible
        is AppError.AudioLink -> R.string.error_title_audio_link
        is AppError.Unknown -> R.string.error_generic
    },
)

private fun errorIcon(e: AppError): ImageVector = when (e) {
    is AppError.NoNetwork -> Icons.Outlined.CloudOff
    is AppError.Timeout -> Icons.Outlined.HourglassEmpty
    is AppError.Http -> Icons.Outlined.Dns
    is AppError.NeedsWebView -> Icons.Outlined.GppMaybe
    is AppError.BadData -> Icons.Outlined.SyncProblem
    is AppError.CorruptedFile, is AppError.UnsupportedFormat, is AppError.FileMissing -> Icons.AutoMirrored.Outlined.InsertDriveFile
    is AppError.NoPermission -> Icons.Outlined.Lock
    is AppError.NoSpace -> Icons.Outlined.SdCard
    is AppError.NotConfigured -> Icons.Outlined.Settings
    is AppError.Incompatible -> Icons.Outlined.Extension
    is AppError.AudioLink -> Icons.Outlined.Link
    is AppError.Unknown -> Icons.Outlined.ErrorOutline
}

/** Technical text worth showing under "Details" (not for errors whose message already says everything). */
private fun errorDetails(e: AppError, message: String): String? = when (e) {
    is AppError.NoNetwork, is AppError.NoSpace, is AppError.NotConfigured, is AppError.FileMissing -> null
    else -> e.technicalDetails?.takeIf { it != message && it.length > 3 }
}

/**
 * Full screen error: an icon, a short title, a plain explanation, actions and, for unexpected errors,
 * collapsible technical details that can be copied for a bug report.
 */
@Composable
fun ErrorState(
    error: Throwable,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    retryLabel: String? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val e = remember(error) { AppError.from(error) }
    val message = errorMessage(context, e)
    val genericMessage = stringResource(R.string.error_generic)
    val details = remember(e, message) { errorDetails(e, message) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxSize()) {
        // On a screen of its own the error fills the height and scrolls when it does not fit. As a row of a list (the
        // error of a title page) the height is unbounded and the list scrolls: a scrolling column in there crashed the
        // app ("Vertically scrollable component was measured with an infinity maximum height constraints").
        val fill = if (constraints.hasBoundedHeight) Modifier.heightIn(min = maxHeight).verticalScroll(scroll) else Modifier
        Column(
            Modifier.fillMaxWidth().then(fill).padding(Dimens.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(88.dp).background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(errorIcon(e), contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(Dimens.l))
            Text(errorTitle(e), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (e !is AppError.Unknown || message != genericMessage) {
                Spacer(Modifier.height(Dimens.s))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
            Row(Modifier.padding(top = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                if (onRetry != null) Button(onClick = onRetry) { Text(retryLabel ?: stringResource(R.string.retry)) }
                if (secondaryLabel != null && onSecondary != null) FilledTonalButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
            if (details != null) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = Dimens.s)) {
                    Text(stringResource(if (expanded) R.string.error_hide_details else R.string.error_details))
                }
                AnimatedVisibility(expanded) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(start = Dimens.m, end = Dimens.xs, top = Dimens.m)) {
                            SelectionContainer {
                                Text(
                                    details,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 14,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(end = Dimens.s),
                                )
                            }
                            TextButton(onClick = { copyText(context, details) }, modifier = Modifier.align(Alignment.End)) {
                                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                                Text(stringResource(R.string.copy), Modifier.padding(start = Dimens.xs))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Copies [text] to the clipboard and confirms it on Android versions without the system clipboard toast. */
fun copyText(context: android.content.Context, text: String, label: String = "Yomu") {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    if (android.os.Build.VERSION.SDK_INT < 33) {
        android.widget.Toast.makeText(context, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun shimmerBrush(base: Color = MaterialTheme.colorScheme.surfaceVariant): Brush {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(0f, 1000f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return Brush.linearGradient(
        listOf(base, base.copy(alpha = base.alpha * 0.4f), base),
        start = androidx.compose.ui.geometry.Offset(x - 300f, 0f),
        end = androidx.compose.ui.geometry.Offset(x, 300f),
    )
}

@Composable
fun SkeletonBox(modifier: Modifier, base: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Box(modifier.clip(MaterialTheme.shapes.small).background(shimmerBrush(base)))
}

@Composable
fun SkeletonGrid(modifier: Modifier = Modifier, count: Int = 12) {
    LazyVerticalGrid(GridCells.Adaptive(Dimens.gridMin), modifier.fillMaxSize(), contentPadding = PaddingValues(Dimens.s), userScrollEnabled = false) {
        items(count) {
            Column(Modifier.padding(Dimens.xs)) {
                SkeletonBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                SkeletonBox(Modifier.padding(top = 6.dp).fillMaxWidth(0.9f).height(12.dp))
                SkeletonBox(Modifier.padding(top = 4.dp).fillMaxWidth(0.6f).height(10.dp))
            }
        }
    }
}

@Composable
fun SkeletonList(modifier: Modifier = Modifier, count: Int = 8) {
    Column(modifier.fillMaxWidth().padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        repeat(count) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBox(Modifier.width(44.dp).height(60.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBox(Modifier.fillMaxWidth(0.8f).height(14.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.5f).height(10.dp))
                }
            }
        }
    }
}

/** Renders the four canonical states of a screen. */
@Composable
fun <T> StateContent(
    state: UiState<T>,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    loading: @Composable () -> Unit = { SkeletonList(modifier) },
    empty: @Composable () -> Unit = { EmptyState(stringResource(R.string.empty_generic), modifier) },
    content: @Composable (T) -> Unit,
) {
    when (state) {
        UiState.Loading -> loading()
        UiState.Empty -> empty()
        is UiState.Error -> ErrorState(state.error, modifier, onRetry)
        is UiState.Data -> content(state.value)
    }
}

val Transparent = Color.Transparent
