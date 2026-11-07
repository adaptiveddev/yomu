package app.yomu.feature.editor

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscript
import androidx.compose.material.icons.filled.Superscript
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.RequireWebView
import app.yomu.core.ui.components.TextInputDialog
import coil3.compose.AsyncImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import app.yomu.core.ui.R as CoreR

/** An image of the book that can be put into the chapter; [href] is relative to the chapter file. */
internal data class InsertableImage(val href: String, val file: File, val name: String)

/** Formatting at the cursor, reported by the page on every selection change. */
private data class FormatState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sup: Boolean = false,
    val sub: Boolean = false,
    val bullets: Boolean = false,
    val numbers: Boolean = false,
    val block: String = "p",
    val align: String = "left",
)

private val BLOCKS = listOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre")

@Composable
private fun blockLabel(tag: String): String = when (tag) {
    "h1", "h2", "h3", "h4", "h5", "h6" -> stringResource(R.string.editor_block_heading, tag.drop(1).toInt())
    "blockquote" -> stringResource(R.string.editor_fmt_quote)
    "pre" -> stringResource(R.string.editor_block_pre)
    "li" -> stringResource(R.string.editor_block_list)
    else -> stringResource(R.string.editor_block_paragraph)
}

/**
 * WYSIWYG editing of a chapter (2.0.0): the chapter with the book's own styles in a WebView with contenteditable.
 * Formatting buttons show the state at the cursor; paste inserts plain text so foreign styles do not leak in; images
 * of the book can be inserted; find highlights matches. The body is saved back as well-formed XHTML.
 * [onSave] gets the new body and whether the source editor should open next.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VisualEditorScreen(
    title: String,
    file: File,
    images: List<InsertableImage>,
    onSave: (html: String, openSource: Boolean) -> Unit,
    onSource: () -> Unit,
    onClose: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var changed by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(FormatState()) }
    var words by remember { mutableIntStateOf(-1) }
    var confirmClose by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var imageDialog by remember { mutableStateOf(false) }
    var blockMenu by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(0 to 0) }

    fun js(code: String) = webView?.evaluateJavascript(code, null)
    fun command(name: String, arg: String? = null) = js("yomuExec(${JsonPrimitive(name)}, ${arg?.let { JsonPrimitive(it).toString() } ?: "null"});")
    fun save(openSource: Boolean) {
        webView?.evaluateJavascript("yomuHtml()") { result ->
            val html = runCatching { Json.parseToJsonElement(result).jsonPrimitive.content }.getOrNull()
            if (html != null) onSave(html, openSource)
        }
    }
    val close = { if (changed) confirmClose = true else onClose() }
    BackHandler { if (finding) { finding = false; webView?.clearMatches() } else close() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (words >= 0) {
                                Text(
                                    pluralStringResource(R.plurals.editor_words, words, words),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) } },
                    actions = {
                        IconButton(onClick = { command("undo") }) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(CoreR.string.undo)) }
                        IconButton(onClick = { command("redo") }) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
                        IconButton(onClick = { finding = !finding; if (!finding) webView?.clearMatches() }) { Icon(Icons.Default.Search, stringResource(CoreR.string.search)) }
                        IconButton(onClick = { if (changed) save(openSource = true) else onSource() }) { Icon(Icons.Default.Code, stringResource(R.string.editor_edit_source)) }
                        IconButton(enabled = changed, onClick = { save(openSource = false) }) { Icon(Icons.Default.Check, stringResource(CoreR.string.save)) }
                    },
                )
                if (finding) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { q ->
                                query = q
                                if (q.isEmpty()) { webView?.clearMatches(); found = 0 to 0 } else webView?.findAllAsync(q)
                            },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.editor_find_in_chapter)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { webView?.findNext(true) }),
                            modifier = Modifier.weight(1f),
                        )
                        Text(if (query.isEmpty()) "" else "${found.first}/${found.second}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                        IconButton(onClick = { webView?.findNext(false) }, enabled = found.second > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.editor_find_prev)) }
                        IconButton(onClick = { webView?.findNext(true) }, enabled = found.second > 0) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.editor_find_next)) }
                        IconButton(onClick = { finding = false; query = ""; webView?.clearMatches() }) { Icon(Icons.Default.Close, stringResource(CoreR.string.close)) }
                    }
                }
                Surface(tonalElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            TextButton(onClick = { blockMenu = true }) {
                                Text(blockLabel(format.block), maxLines = 1)
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = blockMenu, onDismissRequest = { blockMenu = false }) {
                                BLOCKS.forEach { tag ->
                                    DropdownMenuItem(
                                        text = { Text(blockLabel(tag), style = blockStyle(tag)) },
                                        leadingIcon = if (format.block == tag) ({ Icon(Icons.Default.Check, null) }) else null,
                                        onClick = { blockMenu = false; command("formatBlock", "<$tag>") },
                                    )
                                }
                            }
                        }
                        ToolbarDivider()
                        FormatButton(Icons.Default.FormatBold, R.string.editor_fmt_bold, format.bold) { command("bold") }
                        FormatButton(Icons.Default.FormatItalic, R.string.editor_fmt_italic, format.italic) { command("italic") }
                        FormatButton(Icons.Default.FormatUnderlined, R.string.editor_fmt_underline, format.underline) { command("underline") }
                        FormatButton(Icons.Default.FormatStrikethrough, R.string.editor_fmt_strike, format.strike) { command("strikeThrough") }
                        FormatButton(Icons.Default.Superscript, R.string.editor_fmt_sup, format.sup) { command("superscript") }
                        FormatButton(Icons.Default.Subscript, R.string.editor_fmt_sub, format.sub) { command("subscript") }
                        ToolbarDivider()
                        FormatButton(Icons.AutoMirrored.Filled.FormatListBulleted, R.string.editor_fmt_bullets, format.bullets) { command("insertUnorderedList") }
                        FormatButton(Icons.Default.FormatListNumbered, R.string.editor_fmt_numbers, format.numbers) { command("insertOrderedList") }
                        FormatButton(Icons.AutoMirrored.Filled.FormatIndentIncrease, R.string.editor_fmt_indent, false) { command("indent") }
                        FormatButton(Icons.AutoMirrored.Filled.FormatIndentDecrease, R.string.editor_fmt_outdent, false) { command("outdent") }
                        FormatButton(Icons.Default.FormatQuote, R.string.editor_fmt_quote, format.block == "blockquote") {
                            command("formatBlock", if (format.block == "blockquote") "<p>" else "<blockquote>")
                        }
                        ToolbarDivider()
                        FormatButton(Icons.AutoMirrored.Filled.FormatAlignLeft, R.string.editor_fmt_left, format.align == "left") { command("justifyLeft") }
                        FormatButton(Icons.Default.FormatAlignCenter, R.string.editor_fmt_center, format.align == "center") { command("justifyCenter") }
                        FormatButton(Icons.AutoMirrored.Filled.FormatAlignRight, R.string.editor_fmt_right, format.align == "right") { command("justifyRight") }
                        FormatButton(Icons.Default.FormatAlignJustify, R.string.editor_fmt_justify, format.align == "justify") { command("justifyFull") }
                        ToolbarDivider()
                        FormatButton(Icons.Default.Link, R.string.editor_fmt_link, false) { linkDialog = true }
                        FormatButton(Icons.Default.LinkOff, R.string.editor_fmt_unlink, false) { command("unlink") }
                        if (images.isNotEmpty()) FormatButton(Icons.Default.Image, R.string.editor_fmt_image, false) { imageDialog = true }
                        FormatButton(Icons.Default.HorizontalRule, R.string.editor_fmt_rule, false) { command("insertHTML", "<hr/>") }
                        FormatButton(Icons.Default.FormatClear, R.string.editor_fmt_clear, false) { command("removeFormat") }
                    }
                }
            }
        },
    ) { padding ->
        RequireWebView(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().imePadding(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true
                        settings.blockNetworkLoads = true
                        settings.domStorageEnabled = false
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onInput() {
                                post { changed = true }
                            }

                            @JavascriptInterface
                            fun onState(json: String) {
                                val parsed = parseFormat(json)
                                post { parsed?.let { format = it.first; words = it.second } }
                            }
                        }, "YomuEditor")
                        setFindListener { active, count, _ -> found = (if (count == 0) 0 else active + 1) to count }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                            override fun onPageFinished(view: WebView, url: String?) {
                                view.evaluateJavascript(ENABLE_EDITING, null)
                            }
                        }
                        loadUrl(Uri.fromFile(file).toString())
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
    if (linkDialog) {
        TextInputDialog(
            title = stringResource(R.string.editor_fmt_link),
            initial = "https://",
            label = stringResource(R.string.editor_link_url),
            singleLine = true,
            onConfirm = {
                linkDialog = false
                command("createLink", it.trim())
            },
            onDismiss = { linkDialog = false },
        )
    }
    if (imageDialog) {
        AlertDialog(
            onDismissRequest = { imageDialog = false },
            title = { Text(stringResource(R.string.editor_fmt_image)) },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(88.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(images, key = { it.href }) { img ->
                        Column(
                            Modifier.clickable {
                                imageDialog = false
                                command("insertHTML", "<img src=\"${xmlAttr(img.href)}\" alt=\"\"/>")
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AsyncImage(
                                model = fileModel(img.file),
                                contentDescription = img.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                            )
                            Text(img.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { imageDialog = false }) { Text(stringResource(CoreR.string.cancel)) } },
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

@Composable
private fun FormatButton(icon: ImageVector, label: Int, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, stringResource(label), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToolbarDivider() = VerticalDivider(Modifier.heightIn(max = 28.dp).padding(horizontal = 2.dp))

@Composable
private fun blockStyle(tag: String) = when (tag) {
    "h1" -> MaterialTheme.typography.titleLarge
    "h2" -> MaterialTheme.typography.titleMedium
    "h3", "h4", "h5", "h6" -> MaterialTheme.typography.titleSmall
    else -> MaterialTheme.typography.bodyLarge
}

private fun xmlAttr(v: String) = v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

private fun parseFormat(json: String): Pair<FormatState, Int>? = runCatching {
    val o = Json.parseToJsonElement(json).jsonObject
    fun flag(k: String) = o[k]?.jsonPrimitive?.boolean ?: false
    FormatState(
        bold = flag("b"), italic = flag("i"), underline = flag("u"), strike = flag("s"), sup = flag("sup"), sub = flag("sub"),
        bullets = flag("ul"), numbers = flag("ol"),
        block = o["block"]?.jsonPrimitive?.content ?: "p",
        align = o["align"]?.jsonPrimitive?.content ?: "left",
    ) to (o["words"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1)
}.getOrNull()

private const val ENABLE_EDITING = """
(function() {
  var b = document.body;
  if (!b || window.yomuExec) return;
  b.contentEditable = 'true';
  b.spellcheck = true;
  b.style.padding = '12px';
  b.style.minHeight = '90vh';
  b.style.outline = 'none';
  try { document.execCommand('defaultParagraphSeparator', false, 'p'); } catch (e) {}
  try { document.execCommand('styleWithCSS', false, false); } catch (e) {}
  function blockOf(n) {
    while (n && n !== b) {
      if (n.nodeType === 1) {
        var t = n.localName.toLowerCase();
        if (/^(p|h[1-6]|blockquote|pre|li|div)$/.test(t)) return t === 'div' ? 'p' : t;
      }
      n = n.parentNode;
    }
    return 'p';
  }
  function alignOf(n) {
    while (n && n.nodeType !== 1) n = n.parentNode;
    if (!n) return 'left';
    var a = getComputedStyle(n).textAlign;
    if (a === 'start' || a === '-webkit-left' || !a) return 'left';
    if (a === 'end' || a === '-webkit-right') return 'right';
    if (a === '-webkit-center') return 'center';
    return a;
  }
  function q(c) { try { return document.queryCommandState(c); } catch (e) { return false; } }
  function words() { var t = b.innerText || ''; var m = t.trim().match(/\S+/g); return m ? m.length : 0; }
  var timer = null;
  function report() {
    var s = window.getSelection();
    var n = s && s.anchorNode;
    YomuEditor.onState(JSON.stringify({
      b: q('bold'), i: q('italic'), u: q('underline'), s: q('strikeThrough'), sup: q('superscript'), sub: q('subscript'),
      ul: q('insertUnorderedList'), ol: q('insertOrderedList'), block: blockOf(n), align: alignOf(n), words: String(words())
    }));
  }
  function later() { if (timer) clearTimeout(timer); timer = setTimeout(report, 120); }
  document.addEventListener('selectionchange', later);
  b.addEventListener('input', function() { YomuEditor.onInput(); later(); });
  b.addEventListener('paste', function(e) {
    var data = e.clipboardData || window.clipboardData;
    if (!data) return;
    var t = data.getData('text/plain');
    if (t) { e.preventDefault(); document.execCommand('insertText', false, t); }
  });
  b.addEventListener('click', function(e) { var a = e.target.closest ? e.target.closest('a') : null; if (a) e.preventDefault(); });
  window.yomuExec = function(name, arg) { b.focus(); document.execCommand(name, false, arg); YomuEditor.onInput(); later(); };
  window.yomuHtml = function() { return b.innerHTML; };
  report();
})();
"""
