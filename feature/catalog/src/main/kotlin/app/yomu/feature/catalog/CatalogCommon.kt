package app.yomu.feature.catalog

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.yomu.core.common.AppError
import app.yomu.core.ui.components.errorMessage
import app.yomu.domain.model.ContentType
import app.yomu.source.SourceContent
import app.yomu.source.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Locale
import app.yomu.core.ui.R as CoreR

/** Collects one-shot [CatalogEvent]s: shows snackbars and performs navigation. */
@Composable
internal fun CatalogEventsEffect(
    events: Flow<CatalogEvent>,
    snackbar: SnackbarHostState,
    onOpenTitle: (Long) -> Unit = {},
    onOpenWebView: ((String) -> Unit)? = null,
    onOpenBook: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val openBook by rememberUpdatedState(onOpenBook)
    val resources = LocalResources.current
    val openTitle by rememberUpdatedState(onOpenTitle)
    val openWeb by rememberUpdatedState(onOpenWebView)
    LaunchedEffect(events) {
        events.collect { e ->
            when (e) {
                is CatalogEvent.OpenTitle -> openTitle(e.entryId)
                is CatalogEvent.OpenBook -> openBook(e.bookId)
                is CatalogEvent.Message -> launch {
                    snackbar.showSnackbar(if (e.arg != null) resources.getString(e.res, e.arg) else resources.getString(e.res))
                }
                is CatalogEvent.Count -> launch { snackbar.showSnackbar(resources.getQuantityString(e.res, e.count, e.count)) }
                is CatalogEvent.Failure -> launch {
                    val err = AppError.from(e.error)
                    val web = openWeb
                    if (err is AppError.NeedsWebView && web != null) {
                        val r = snackbar.showSnackbar(
                            errorMessage(context, err),
                            actionLabel = resources.getString(CoreR.string.open_webview),
                            duration = SnackbarDuration.Long,
                        )
                        if (r == SnackbarResult.ActionPerformed) web(err.url)
                    } else {
                        snackbar.showSnackbar(errorMessage(context, err))
                    }
                }
            }
        }
    }
}

/**
 * Display name of a language code for lists and filters. Multi-language sources get a word instead of a code;
 * [single] picks the form used for one source ("Multilingual" rather than the group header "Multilingual" in Russian).
 */
@Composable
internal fun langLabel(code: String, single: Boolean = false): String = when (code) {
    "all", "multi", "" -> stringResource(if (single) R.string.source_lang_multi else R.string.ext_lang_multi)
    "other" -> stringResource(R.string.ext_lang_other)
    else -> languageName(code)
}

/** Names already looked up, per device language: the list sorts thousands of sources by them. */
private val languageNames = java.util.concurrent.ConcurrentHashMap<String, String>()

/**
 * Name of a language code in the device language (used for sorting too; see [langLabel] for display). Cached:
 * building a Locale and its display name for every comparison made sorting by language and the language chips slow.
 */
internal fun languageName(code: String): String {
    val device = Locale.getDefault()
    return languageNames.getOrPut(device.toLanguageTag() + "|" + code) {
        when {
            code.isBlank() -> ""
            code == "all" || code == "multi" -> "*"
            else -> {
                // Sources use "es-la" for Latin American Spanish ("LA" would be Laos in BCP 47).
                val tag = code.replace('_', '-').let { if (it.equals("es-la", true)) "es-419" else it }
                val locale = Locale.forLanguageTag(tag)
                val name = if (locale.country.isNotEmpty()) locale.getDisplayName(device) else locale.getDisplayLanguage(device)
                name.replaceFirstChar { it.titlecase() }.ifBlank { code }
            }
        }
    }
}

internal fun contentLabel(context: Context, c: SourceContent): String = context.getString(
    when (c) {
        SourceContent.MANGA -> R.string.content_manga
        SourceContent.NOVEL -> R.string.content_novel
        SourceContent.BOOK -> R.string.content_book
    },
)

internal fun contentLabel(context: Context, c: ContentType): String = context.getString(
    when (c) {
        ContentType.MANGA -> R.string.content_manga
        ContentType.NOVEL -> R.string.content_novel
        ContentType.BOOK -> R.string.content_book
    },
)

internal fun statusLabel(context: Context, status: Int): String? = when (status) {
    Status.ONGOING -> context.getString(R.string.status_ongoing)
    Status.COMPLETED -> context.getString(R.string.status_completed)
    Status.HIATUS -> context.getString(R.string.status_hiatus)
    Status.CANCELLED -> context.getString(R.string.status_cancelled)
    else -> null
}

internal fun relativeTime(ts: Long): String =
    if (ts <= 0) "" else DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

internal fun chapterNumberLabel(number: Float): String =
    if (number < 0f) "" else if (number == number.toInt().toFloat()) number.toInt().toString() else number.toString()

private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*)://""")
private val DOUBLED_SCHEME = Regex("""^(?:https?://)+(https?://)""", RegexOption.IGNORE_CASE)
private val IPV4 = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

/**
 * Turns a typed address into an http(s) URL: drops spaces and a scheme pasted over another one ("https://https://…")
 * and adds a scheme to a bare host — http:// for home servers (IP addresses, localhost, *.local), https:// otherwise.
 * Returns null when the text does not look like a web address.
 */
internal fun webAddress(input: String): String? {
    var u = input.trim().replace(Regex("""\s+"""), "")
    if (u.isEmpty()) return null
    DOUBLED_SCHEME.find(u)?.let { m -> u = m.groupValues[1] + u.substring(m.range.last + 1) }
    val scheme = SCHEME.find(u)?.groupValues?.get(1)
    u = when {
        scheme == null -> u.removePrefix("//").let { rest -> (if (isHomeServer(hostOf(rest))) "http://" else "https://") + rest }
        scheme.equals("http", true) || scheme.equals("https", true) -> scheme.lowercase() + u.substring(scheme.length)
        else -> return null
    }
    val host = hostOf(u.substringAfter("://"))
    return u.takeIf { host.contains('.') || host.startsWith("[") || host.equals("localhost", true) }
}

private fun hostOf(rest: String): String {
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    return if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
}

private fun isHomeServer(host: String): Boolean =
    host.equals("localhost", true) || IPV4.matches(host) || host.endsWith(".local", true) || host.endsWith(".lan", true)

