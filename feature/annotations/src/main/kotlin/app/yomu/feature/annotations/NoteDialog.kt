package app.yomu.feature.annotations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.yomu.core.ui.components.YomuTab
import app.yomu.core.ui.theme.Dimens
import app.yomu.core.ui.theme.YomuTheme
import app.yomu.core.ui.R as UiR

/** Markdown note editor with a live preview tab. [quote] is shown above the editor for context. */
@Composable
fun NoteDialog(
    initial: String,
    quote: String?,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_title)) },
        text = {
            Column {
                if (!quote.isNullOrBlank()) {
                    Text(
                        "“" + quote.take(400) + "”",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        modifier = Modifier.padding(bottom = Dimens.s),
                    )
                }
                PrimaryTabRow(selectedTabIndex = tab) {
                    YomuTab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.note_edit)) })
                    YomuTab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.note_preview)) })
                }
                if (tab == 0) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(stringResource(R.string.note_hint)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(top = Dimens.s),
                    )
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp).verticalScroll(rememberScrollState()).padding(top = Dimens.s)) {
                        if (text.isBlank()) {
                            Text(stringResource(R.string.note_empty_preview), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            MarkdownText(text)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text(stringResource(UiR.string.save)) } },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text(stringResource(UiR.string.delete), color = MaterialTheme.colorScheme.error) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}

/** Lightweight Markdown renderer: headings, lists, quotes, bold, italic, code, links and [[wikilinks]]. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val colors = MaterialTheme.colorScheme
    val annotated = remember(markdown, colors) { Markdown.render(markdown, colors.primary, colors.surfaceVariant, colors.onSurfaceVariant) }
    Text(annotated, modifier = modifier, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines)
}

object Markdown {
    private val inline = Regex("""\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*|_(.+?)_|`(.+?)`|\[\[(.+?)]]|\[(.+?)]\((\S+?)\)|~~(.+?)~~""")

    fun render(md: String, link: Color, codeBg: Color, muted: Color): AnnotatedString = buildAnnotatedString {
        val lines = md.replace("\r\n", "\n").split('\n')
        lines.forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("### ") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 1.1.em)) { inline(line.drop(4), link, codeBg) }
                line.startsWith("## ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.2.em)) { inline(line.drop(3), link, codeBg) }
                line.startsWith("# ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.35.em)) { inline(line.drop(2), link, codeBg) }
                line.startsWith("> ") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = muted)) { append("▍ "); inline(line.drop(2), link, codeBg) }
                line.matches(Regex("""^\s*[-*+] \[[ xX]] .*""")) -> {
                    val checked = line.contains("[x]") || line.contains("[X]")
                    append(if (checked) "☑ " else "☐ ")
                    inline(line.substringAfter("] "), link, codeBg)
                }
                line.matches(Regex("""^\s*[-*+] .*""")) -> {
                    val indent = line.takeWhile { it == ' ' }.length / 2
                    append("  ".repeat(indent) + "• ")
                    inline(line.trimStart().drop(2), link, codeBg)
                }
                line.matches(Regex("""^\s*\d+[.)] .*""")) -> {
                    val num = line.trimStart().takeWhile { it.isDigit() }
                    append("$num. ")
                    inline(line.trimStart().substringAfter(' '), link, codeBg)
                }
                line == "---" || line == "***" -> append("────────")
                else -> inline(line, link, codeBg)
            }
            if (i < lines.lastIndex) append('\n')
        }
    }

    private fun AnnotatedString.Builder.inline(text: String, link: Color, codeBg: Color) {
        var last = 0
        for (m in inline.findAll(text)) {
            append(text.substring(last, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(g[1], link, codeBg) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(g[2], link, codeBg) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(g[3], link, codeBg) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(g[4], link, codeBg) }
                g[5].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(g[5]) }
                g[6].isNotEmpty() -> withStyle(SpanStyle(color = link)) { append(g[6].substringAfter('|')) }
                g[7].isNotEmpty() -> withLink(LinkAnnotation.Url(g[8], TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(g[7]) }
                g[9].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[9]) }
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

@Preview
@Composable
private fun MarkdownPreview() {
    YomuTheme { MarkdownText("# Title\nSome **bold** and *italic* text with `code`.\n- item one\n- [x] done\n> a quote\n[link](https://example.org)") }
}
