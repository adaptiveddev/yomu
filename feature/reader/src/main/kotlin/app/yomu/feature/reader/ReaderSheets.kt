package app.yomu.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.ui.components.ChoiceDialog
import app.yomu.core.ui.components.SearchField
import app.yomu.core.ui.components.SheetTitle
import app.yomu.core.ui.components.YomuSheet
import app.yomu.core.ui.components.YomuTab
import app.yomu.domain.model.Bookmark
import app.yomu.domain.model.CommonReaderSettings
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.Locator
import app.yomu.domain.model.SearchHit
import app.yomu.domain.model.TocItem
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import app.yomu.core.ui.R as CoreR

@Composable
internal fun TocSheet(
    toc: List<TocItem>,
    currentChapter: Int,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    onToc: (TocItem) -> Unit,
    onLocator: (Locator) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onOpenAll: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    YomuSheet(onDismiss = onDismiss) {
        PrimaryTabRow(selectedTabIndex = tab) {
            YomuTab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.reader_toc)) })
            YomuTab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.reader_bookmarks_n, bookmarks.size)) })
            YomuTab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.reader_highlights_n, highlights.size)) })
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 560.dp)) {
            when (tab) {
                0 -> if (toc.isEmpty()) {
                    SheetEmpty(stringResource(R.string.reader_toc_empty))
                } else {
                    val currentIndex = toc.indexOfLast { it.chapter <= currentChapter }.coerceAtLeast(0)
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
                    LazyColumn(state = listState) {
                        items(toc.size) { i ->
                            val item = toc[i]
                            val current = i == currentIndex
                            ListItem(
                                headlineContent = {
                                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                },
                                modifier = Modifier.clickable { onToc(item) }.padding(start = (item.level * 16).dp),
                            )
                        }
                    }
                }
                1 -> if (bookmarks.isEmpty()) {
                    SheetEmpty(stringResource(R.string.reader_bookmarks_empty))
                } else {
                    LazyColumn {
                        items(bookmarks, key = { it.id }) { b ->
                            ListItem(
                                headlineContent = { Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(listOfNotNull(b.locator.chapterTitle, "${(b.locator.totalProgression * 100).toInt()}%").joinToString(" · "))
                                },
                                trailingContent = {
                                    IconButton(onClick = { onDeleteBookmark(b) }) { Icon(Icons.Outlined.Delete, stringResource(CoreR.string.delete)) }
                                },
                                modifier = Modifier.clickable { onLocator(b.locator) },
                            )
                        }
                    }
                }
                else -> if (highlights.isEmpty()) {
                    SheetEmpty(stringResource(R.string.reader_highlights_empty))
                } else {
                    LazyColumn {
                        items(highlights, key = { it.id }) { h ->
                            ListItem(
                                leadingContent = { Box(Modifier.size(14.dp).background(Color(h.color.argb), CircleShape)) },
                                headlineContent = { Text(h.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                                supportingContent = h.note?.takeIf { it.isNotBlank() }?.let { n -> { Text(n, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                                modifier = Modifier.clickable { onLocator(h.locator) },
                            )
                        }
                    }
                }
            }
        }
        if (onOpenAll != null && tab != 0) {
            TextButton(onClick = onOpenAll, modifier = Modifier.align(Alignment.End).padding(8.dp)) {
                Icon(Icons.Outlined.Bookmarks, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reader_all_annotations))
            }
        }
    }
}

@Composable
private fun SheetEmpty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
