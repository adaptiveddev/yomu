package app.yomu.sources.novels

import app.yomu.source.FilterList
import app.yomu.source.HttpSource
import app.yomu.source.ItemsPage
import app.yomu.source.SChapter
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import app.yomu.source.SourceException
import app.yomu.source.Status
import app.yomu.sources.novels.ProseMirror.arrayOrEmpty
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.OffsetDateTime

/**
 * RanobeLib (ranobelib.me): Russian translations of light and web novels, through the JSON API the site itself uses.
 * A chapter translated by several teams appears once per team (the team is its "scanlator").
 */
class RanobeLibSource(
    private val apiUrl: String = "https://api.lib.social",
    override val baseUrl: String = "https://ranobelib.me",
) : HttpSource() {
    override val name = "RanobeLib"
    override val lang = "ru"
    override val content = SourceContent.NOVEL
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Site-Id", SITE_ID)
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json")

    override fun imageHeaders(): Map<String, String> = mapOf("Referer" to "$baseUrl/")

    override suspend fun getPopular(page: Int): ItemsPage = list(page) { addQueryParameter("sort_by", "views") }

    override suspend fun getLatest(page: Int): ItemsPage = list(page) { addQueryParameter("sort_by", "last_chapter_at") }

    override suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage = list(page) {
        if (query.isNotBlank()) addQueryParameter("q", query.trim())
    }

    private suspend fun list(page: Int, query: okhttp3.HttpUrl.Builder.() -> Unit): ItemsPage {
        val url = "$apiUrl/api/manga".toHttpUrl().newBuilder()
            .addQueryParameter("site_id[]", SITE_ID)
            .addQueryParameter("page", page.toString())
            .apply(query)
            .build().toString()
        val root = json.parseToJsonElement(getJsonText(url)).jsonObject
        val items = root["data"].arrayOrEmpty().mapNotNull { (it as? JsonObject)?.let(::item) }
        val hasNext = (root["meta"] as? JsonObject)?.get("has_next_page")?.jsonPrimitive?.booleanOrNull ?: items.isNotEmpty()
        return ItemsPage(items, hasNext)
    }

    private fun item(o: JsonObject): SItem? {
        val slug = o.str("slug_url") ?: return null
        val title = o.str("rus_name")?.takeIf { it.isNotBlank() } ?: o.str("eng_name")?.takeIf { it.isNotBlank() } ?: o.str("name") ?: slug
        return SItem(
            url = slug,
            title = title,
            thumbnailUrl = (o["cover"] as? JsonObject)?.let { it.str("default") ?: it.str("md") ?: it.str("thumbnail") },
            status = status((o["status"] as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull),
            type = SourceContent.NOVEL,
            language = lang,
        )
    }

    override suspend fun getDetails(item: SItem): SItem {
        val url = "$apiUrl/api/manga/${item.url}".toHttpUrl().newBuilder().apply {
            DETAIL_FIELDS.forEach { addQueryParameter("fields[]", it) }
        }.build().toString()
        val root = json.parseToJsonElement(getJsonText(url)).jsonObject
        val o = root["data"] as? JsonObject ?: throw SourceException("RanobeLib: no such title")
        val base = item(o) ?: item
        val summary = o["summary"]
        val description = when {
            ProseMirror.isDocument(summary) -> ProseMirror.toText(summary!!)
            summary is JsonPrimitive -> summary.contentOrNull
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        val people = { key: String -> o[key].arrayOrEmpty().mapNotNull { p -> (p as? JsonObject)?.let { it.str("rus_name")?.takeIf(String::isNotBlank) ?: it.str("name") } } }
        val genres = (o["genres"].arrayOrEmpty() + o["tags"].arrayOrEmpty()).mapNotNull { (it as? JsonObject)?.str("name") }.distinct()
        val other = listOfNotNull(o.str("eng_name"), o.str("name")).filter { it.isNotBlank() && it != base.title }.distinct()
        return base.copy(
            url = item.url,
            author = people("authors").joinToString(", ").ifBlank { null },
            artist = people("artists").joinToString(", ").ifBlank { null },
            description = listOfNotNull(description, other.takeIf { it.isNotEmpty() }?.joinToString(" / ")).joinToString("\n\n").ifBlank { null },
            genres = genres,
            initialized = true,
        )
    }

    override suspend fun getChapters(item: SItem): List<SChapter> {
        val root = json.parseToJsonElement(getJsonText("$apiUrl/api/manga/${item.url}/chapters")).jsonObject
        val chapters = root["data"].arrayOrEmpty().mapNotNull { it as? JsonObject }
        val out = ArrayList<SChapter>(chapters.size)
        for (c in chapters) {
            val volume = c.str("volume").orEmpty()
            val number = c.str("number").orEmpty()
            val title = c.str("name")?.trim().orEmpty()
            val name = buildString {
                if (volume.isNotEmpty()) append("Том ").append(volume).append(' ')
                append("Глава ").append(number)
                if (title.isNotEmpty()) append(" — ").append(title)
            }
            val branches = c["branches"].arrayOrEmpty().mapNotNull { it as? JsonObject }.ifEmpty { listOf(JsonObject(emptyMap())) }
            for (b in branches) {
                val branchId = b["branch_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull.orEmpty()
                val teams = b["teams"].arrayOrEmpty().mapNotNull { (it as? JsonObject)?.str("name") }
                val user = (b["user"] as? JsonObject)?.str("username")
                out += SChapter(
                    url = listOf(item.url, volume, number, branchId).joinToString(SEPARATOR),
                    name = name,
                    number = number.toFloatOrNull() ?: -1f,
                    dateUpload = b.str("created_at")?.let(::parseDate) ?: 0L,
                    scanlator = teams.joinToString(", ").ifBlank { user }?.takeIf { branches.size > 1 || it.isNotBlank() },
                )
            }
        }
        return out.asReversed()
    }

    override suspend fun getContent(chapter: SChapter): String {
        val parts = chapter.url.split(SEPARATOR)
        if (parts.size < 3) throw SourceException("RanobeLib: bad chapter address ${chapter.url}")
        val (slug, volume, number) = parts
        val url = "$apiUrl/api/manga/$slug/chapter".toHttpUrl().newBuilder()
            .addQueryParameter("number", number)
            .addQueryParameter("volume", volume)
            .apply { parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("branch_id", it) } }
            .build().toString()
        val o = json.parseToJsonElement(getJsonText(url)).jsonObject["data"] as? JsonObject ?: throw SourceException("RanobeLib: empty chapter")
        val attachments = o["attachments"].arrayOrEmpty().mapNotNull { it as? JsonObject }
        val byName = attachments.associate { (it.str("name") ?: it.str("filename").orEmpty().substringBeforeLast('.')) to it.str("url") }
        val content = o["content"]
        val html = when {
            ProseMirror.isDocument(content) -> ProseMirror.toHtml(content!!) { name -> byName[name]?.let(::absolute) }
            content is JsonPrimitive -> content.contentOrNull.orEmpty()
            else -> ""
        }
        if (html.isBlank()) throw SourceException("RanobeLib: the chapter has no text (it may be paid or removed)")
        return html
    }

    override fun webUrl(item: SItem): String = "$baseUrl/ru/book/${item.url}"

    private fun absolute(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> baseUrl + (if (path.startsWith('/')) path else "/$path")
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun status(id: Int?): Int = when (id) {
        1 -> Status.ONGOING
        2 -> Status.COMPLETED
        3 -> Status.HIATUS
        4, 5 -> Status.CANCELLED
        else -> Status.UNKNOWN
    }

    private fun parseDate(s: String): Long = runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrDefault(0L)

    companion object {
        const val SITE_ID = "3"
        private const val SEPARATOR = "|"
        private val DETAIL_FIELDS = listOf("summary", "genres", "tags", "authors", "artists", "status_id", "chap_count", "eng_name")
    }
}
