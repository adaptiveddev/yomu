package app.yomu.sources.opds

import app.yomu.source.FilterList
import app.yomu.source.HttpSource
import app.yomu.source.ItemsPage
import app.yomu.source.MIME_AUDIO_FEED
import app.yomu.source.SBookLink
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import app.yomu.source.Status
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * LibriVox public-domain audiobooks (JSON API). Each book offers "Listen online" (its chapter feed, streamed by the
 * player) and the MP3 ZIP from the Internet Archive (downloaded and unpacked into the library).
 */
class LibriVoxSource(private val api: String = "https://librivox.org") : HttpSource() {
    override val name = "LibriVox"
    override val lang = "all"
    override val baseUrl = api
    override val content = SourceContent.BOOK
    override val supportsLatest = true

    private fun feed(params: String, page: Int) =
        "$api/api/feed/audiobooks/?format=json&coverart=1&limit=$PAGE&offset=${(page - 1).coerceAtLeast(0) * PAGE}&$params"

    private suspend fun list(url: String): ItemsPage {
        val items = parse(getJsonText(url))
        return ItemsPage(items, items.size >= PAGE)
    }

    override suspend fun getPopular(page: Int) = list(feed("", page))
    override suspend fun getLatest(page: Int) = list(feed("sort_field=id&sort_order=desc", page))

    /** The API matches title prefixes ("^") and exact author surnames; both are asked and merged. */
    override suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage {
        val q = query.trim()
        if (q.isEmpty()) return getPopular(page)
        val byTitle = runCatching { parse(getJsonText(feed("title=${enc("^$q")}", page))) }.getOrDefault(emptyList())
        val surname = q.split(' ').last()
        val byAuthor = runCatching { parse(getJsonText(feed("author=${enc(surname)}", page))) }.getOrDefault(emptyList())
        val items = (byTitle + byAuthor).distinctBy { it.url }
        return ItemsPage(items, byTitle.size >= PAGE || byAuthor.size >= PAGE)
    }

    override suspend fun getDetails(item: SItem): SItem {
        if (item.links.isNotEmpty()) return item
        val id = item.memo?.let { Regex(""""id"\s*:\s*"?(\d+)""").find(it)?.groupValues?.get(1) } ?: return item
        return parse(getJsonText("$api/api/feed/audiobooks/?format=json&coverart=1&id=$id")).firstOrNull() ?: item
    }

    companion object {
        private const val PAGE = 30
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private val TAGS = Regex("<[^>]+>")

        /** Books of an API answer; `{"error": …}` (nothing found) is an empty list. */
        internal fun parse(text: String): List<SItem> {
            val root = app.yomu.source.SourceNetwork.json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
            val books = root["books"] as? JsonArray ?: return emptyList()
            return books.mapNotNull { (it as? JsonObject)?.let(::item) }
        }

        private fun item(o: JsonObject): SItem? {
            val id = o.str("id") ?: return null
            val title = o.str("title") ?: return null
            val page = o.str("url_librivox") ?: "https://librivox.org/?p=$id"
            val authors = (o["authors"] as? JsonArray).orEmpty().mapNotNull { a ->
                val ao = a as? JsonObject ?: return@mapNotNull null
                listOfNotNull(ao.str("first_name"), ao.str("last_name")).joinToString(" ").trim().ifBlank { null }
            }
            val links = listOfNotNull(
                o.str("url_rss")?.let { SBookLink(it, MIME_AUDIO_FEED) },
                o.str("url_zip_file")?.let { SBookLink(it.replace(" ", "%20"), "application/zip", "MP3 (ZIP)") },
            )
            val time = o.str("totaltime")
            return SItem(
                url = page,
                title = title,
                author = authors.joinToString("; ").ifBlank { null },
                description = listOfNotNull(
                    o.str("description")?.replace(TAGS, "")?.trim(),
                    time?.let { "⏱ $it" },
                ).joinToString("\n\n").ifBlank { null },
                genres = (o["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") },
                status = Status.COMPLETED,
                thumbnailUrl = o.str("coverart_thumbnail") ?: o.str("coverart_jpg"),
                type = SourceContent.BOOK,
                language = o.str("language"),
                links = links,
                initialized = true,
                memo = """{"id":"$id"}""",
            )
        }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
