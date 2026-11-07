package app.yomu.feature.editor

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.yomu.core.common.ScreenErrors
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.RequireWebView
import app.yomu.core.ui.components.TextInputDialog
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import app.yomu.core.ui.R as CoreR

enum class CodeLang { MARKUP, CSS }

private data class CodeColors(val tag: Color, val attr: Color, val value: Color, val comment: Color, val entity: Color)

@Composable
private fun codeColors(): CodeColors {
    val dark = MaterialTheme.colorScheme.surface.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    return if (dark) {
        CodeColors(Color(0xFF82AAFF), Color(0xFFFFCB6B), Color(0xFFC3E88D), Color(0xFF7F8C98), Color(0xFFF78C6C))
    } else {
        CodeColors(Color(0xFF1A4FBF), Color(0xFF9A5B00), Color(0xFF2E7D32), Color(0xFF8A8A8A), Color(0xFFB0381E))
    }
}

/** Lightweight regex based syntax highlighting (disabled for very large files). */
private class Highlighter(private val lang: CodeLang, private val c: CodeColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > MAX_LENGTH) return TransformedText(text, OffsetMapping.Identity)
        val b = AnnotatedString.Builder(text)
        val s = text.text
        fun style(color: Color, start: Int, end: Int) = b.addStyle(SpanStyle(color = color), start, end)
        when (lang) {
            CodeLang.MARKUP -> {
                TAG.findAll(s).forEach { m ->
                    style(c.tag, m.range.first, m.range.last + 1)
                    ATTR.findAll(m.value).forEach { a ->
                        val name = a.groups[1]!!.range
                        val value = a.groups[2]!!.range
                        style(c.attr, m.range.first + name.first, m.range.first + name.last + 1)
                        style(c.value, m.range.first + value.first, m.range.first + value.last + 1)
                    }
                }
                ENTITY.findAll(s).forEach { style(c.entity, it.range.first, it.range.last + 1) }
                COMMENT.findAll(s).forEach { style(c.comment, it.range.first, it.range.last + 1) }
            }
            CodeLang.CSS -> {
                SELECTOR.findAll(s).forEach { style(c.tag, it.range.first, it.range.last + 1) }
                PROPERTY.findAll(s).forEach { style(c.attr, it.range.first, it.range.last + 1) }
                CSS_VALUE.findAll(s).forEach { style(c.value, it.range.first, it.range.last + 1) }
                CSS_COMMENT.findAll(s).forEach { style(c.comment, it.range.first, it.range.last + 1) }
            }
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }

    companion object {
        const val MAX_LENGTH = 150_000
        private val TAG = Regex("</?[A-Za-z!?][^<>]*>")
        private val ATTR = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(\"[^\"]*\"|'[^']*')")
        private val ENTITY = Regex("&[#A-Za-z0-9]+;")
        private val COMMENT = Regex("<!--[\\s\\S]*?-->")
        private val SELECTOR = Regex("(?m)[^{}\\n;][^{}\\n]*(?=\\{)")
        private val PROPERTY = Regex("(?<=[{;\\s])[-a-zA-Z]+(?=\\s*:[^{}]*[;}])")
        private val CSS_VALUE = Regex("(?<=:)[^;{}\\n]+(?=;|})")
        private val CSS_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
    }
}

@Composable
fun CodeEditor(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, lang: CodeLang, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    val colors = codeColors()
    val transformation = remember(lang, colors) { Highlighter(lang, colors) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = transformation,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp).let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
    )
}

/** Full screen source editor for XHTML or CSS with find, syntax highlighting and an XML check on save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceEditorScreen(
    title: String,
    initialText: String,
    lang: CodeLang,
    initialFind: String?,
    checkXml: suspend (String) -> String?,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initialText)) }
    var findOpen by remember { mutableStateOf(!initialFind.isNullOrEmpty()) }
    var query by remember { mutableStateOf(initialFind.orEmpty()) }
    var xmlError by remember { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val dirty = value.text != initialText

    fun find(forward: Boolean) {
        if (query.isEmpty()) return
        val text = value.text
        val idx = if (forward) {
            text.indexOf(query, value.selection.max, ignoreCase = true).takeIf { it >= 0 } ?: text.indexOf(query, 0, ignoreCase = true)
        } else {
            text.lastIndexOf(query, (value.selection.min - 1).coerceAtLeast(0), ignoreCase = true).takeIf { it >= 0 && it < value.selection.min }
                ?: text.lastIndexOf(query, ignoreCase = true)
        }
        if (idx >= 0) {
            value = value.copy(selection = TextRange(idx, idx + query.length))
            runCatching { focus.requestFocus() }
        }
    }

    val close = { if (dirty) confirmClose = true else onClose() }
    BackHandler { close() }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) } },
                    actions = {
                        IconButton(onClick = { findOpen = !findOpen }) { Icon(Icons.Default.Search, stringResource(CoreR.string.search)) }
                        TextButton(enabled = dirty, onClick = {
                            scope.launch(ScreenErrors) {
                                val error = if (lang == CodeLang.MARKUP) checkXml(value.text) else null
                                if (error != null) xmlError = error else onSave(value.text)
                            }
                        }) { Text(stringResource(CoreR.string.save)) }
                    },
                )
                if (findOpen) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.editor_find_hint)) },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { find(false) }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.editor_find_prev)) }
                        IconButton(onClick = { find(true) }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.editor_find_next)) }
                    }
                }
                HorizontalDivider()
            }
        },
    ) { padding ->
        CodeEditor(value, { value = it }, lang, Modifier.padding(padding).fillMaxSize().imePadding(), focus)
    }
    xmlError?.let { err ->
        ConfirmDialog(
            title = stringResource(R.string.editor_xml_error_title),
            text = stringResource(R.string.editor_xml_error_text, err),
            confirmLabel = stringResource(R.string.editor_save_anyway),
            onConfirm = {
                xmlError = null
                onSave(value.text)
            },
            onDismiss = { xmlError = null },
        )
    }
    if (confirmClose) {
        ConfirmDialog(
            title = stringResource(R.string.editor_unsaved_title),
            text = stringResource(R.string.editor_unsaved_text),
            confirmLabel = stringResource(R.string.editor_discard_edits),
            onConfirm = {
                confirmClose = false
                onClose()
            },
            onDismiss = { confirmClose = false },
        )
    }
}

