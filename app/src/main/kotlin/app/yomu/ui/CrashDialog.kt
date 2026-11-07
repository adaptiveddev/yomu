package app.yomu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import app.yomu.R
import app.yomu.core.common.CrashReport
import app.yomu.core.common.Share
import app.yomu.core.ui.components.copyText
import app.yomu.core.ui.theme.Dimens
import java.io.File
import app.yomu.core.ui.R as CoreR

/**
 * Shown once after a crash of the previous run (1.9.0): the report (the error, the screen and the last steps before it,
 * the phone model) can be sent or copied right away instead of digging for it in Settings → Advanced → Error log.
 */
@Composable
fun CrashReportDialog(report: CrashReport, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val summary = remember(report) { crashSummary(report.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.crash_text))
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Dimens.m),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { shareCrash(context, report); onDismiss() }) { Text(stringResource(R.string.crash_send)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { copyText(context, report.text, "Yomu crash"); onDismiss() }) { Text(stringResource(CoreR.string.copy)) }
                TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.close)) }
            }
        },
    )
}

/** The error line of a report (the exception and its message), past the header lines. */
internal fun crashSummary(text: String): String? = text.lineSequence()
    .drop(1)
    .map { it.trim() }
    .firstOrNull { it.isNotEmpty() && HEADERS.none(it::startsWith) }
    ?.let { line ->
        // "java.lang.IllegalStateException: message" → "IllegalStateException: message"
        val cls = line.substringBefore(": ")
        if (cls.contains('.') && !cls.contains(' ')) cls.substringAfterLast('.') + line.removePrefix(cls) else line
    }

private val HEADERS = listOf("Where:", "Screen:", "Recent:")

/** As a file like "Share all" of the error log: a long stack trace does not fit into a chat message. */
private fun shareCrash(context: android.content.Context, report: CrashReport) {
    val shared = runCatching {
        val file = File(Share.shareDir(context), "yomu-crash.txt").apply { writeText(report.text) }
        Share.shareFile(context, file, "text/plain", "Yomu crash report")
    }.getOrDefault(false)
    if (!shared) Share.shareText(context, report.text, "Yomu crash report")
}
