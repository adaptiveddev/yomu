package app.yomu.sources.mangadex

import app.yomu.source.Filter
import app.yomu.source.FilterList
import app.yomu.source.HttpSource
import app.yomu.source.ItemsPage
import app.yomu.source.Page
import app.yomu.source.SChapter
import app.yomu.source.SItem
import app.yomu.source.Source
import app.yomu.source.SourceContent
import app.yomu.source.SourceFactory
import app.yomu.source.SourceNetwork
import app.yomu.source.Status
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** MangaDex through its official public API (https://api.mangadex.org/docs). */
class MangaDexSource(
    override val lang: String,
    private val apiUrl: String = "https://api.mangadex.org",
    private val coversUrl: String = "https://uploads.mangadex.org",
) : HttpSource() {
    override val name = "MangaDex"
    override val baseUrl = "https://mangadex.org"
    override val content = SourceContent.MANGA
    override val supportsLatest = true

    private var tagCache: Map<String, String>? = null

    // MangaDex redirects browser-like User-Agents on api.* and uploads.* to an anti-bot HTML page, while API clients
    // must identify themselves (https://api.mangadex.org/docs/2-limitations/). So never send the shared browser UA here.
    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", API_USER_AGENT)
        .add("Referer", "$baseUrl/")

    override fun imageHeaders(): Map<String, String> = mapOf("User-Agent" to API_USER_AGENT, "Referer" to "$baseUrl/")

    private fun mangaUrl(page: Int) = "$apiUrl/manga".toHttpUrl().newBuilder()
        .addQueryParameter("limit", LIMIT.toString())
        .addQueryParameter("offset", ((page - 1) * LIMIT).toString())
        .addQueryParameter("includes[]", "cover_art")
        .addQueryParameter("includes[]", "author")
        .addQueryParameter("includes[]", "artist")
        .addQueryParameter("availableTranslatedLanguage[]", lang)

    private suspend fun list(url: String): ItemsPage {
        val res = json.decodeFromString(MdList.serializer(), getJsonText(url))
        return ItemsPage(res.data.map { toItem(it) }, res.offset + res.limit < res.total)
    }

    override suspend fun getPopular(page: Int) = list(
        mangaUrl(page).addQueryParameter("order[followedCount]", "desc").ratings(listOf("safe", "suggestive")).build().toString(),
    )

    override suspend fun getLatest(page: Int) = list(
        mangaUrl(page).addQueryParameter("order[latestUploadedChapter]", "desc").ratings(listOf("safe", "suggestive")).build().toString(),
    )

    override suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage {
        val b = mangaUrl(page)
        if (query.isNotBlank()) b.addQueryParameter("title", query)
        var ratings = listOf("safe", "suggestive")
        for (f in filters.leaves()) when {
            f is Filter.Sort && f.name == SORT -> f.state?.let { s -> b.addQueryParameter("order[${f.keys[s.index]}]", if (s.ascending) "asc" else "desc") }
            f is Filter.CheckBox && f.key.startsWith("status:") && f.state -> b.addQueryParameter("status[]", f.key.removePrefix("status:"))
            f is Filter.CheckBox && f.key.startsWith("demo:") && f.state -> b.addQueryParameter("publicationDemographic[]", f.key.removePrefix("demo:"))
            f is Filter.Select && f.name == RATING -> ratings = when (f.state) {
                1 -> listOf("safe")
                2 -> listOf("safe", "suggestive", "erotica")
                else -> ratings
            }
            f is Filter.Text && f.name == YEAR && f.state.toIntOrNull() != null -> b.addQueryParameter("year", f.state)
            f is Filter.Select && f.name == ORIGINAL && f.state > 0 -> b.addQueryParameter("originalLanguage[]", f.selectedKey)
            f is Filter.Text && f.name == TAGS && f.state.isNotBlank() -> {
                val tags = tags()
                f.state.split(',').map { it.trim().lowercase() }.forEach { t -> tags[t]?.let { b.addQueryParameter("includedTags[]", it) } }
            }
        }
        b.ratings(ratings)
        if (filters.none { it is Filter.Sort }) b.addQueryParameter("order[relevance]", "desc")
        return list(b.build().toString())
    }

    private fun okhttp3.HttpUrl.Builder.ratings(list: List<String>) = apply { list.forEach { addQueryParameter("contentRating[]", it) } }

    private suspend fun tags(): Map<String, String> = tagCache ?: run {
        val res = json.decodeFromString(MdList.serializer(), getJsonText("$apiUrl/manga/tag"))
        res.data.associate { localized(it.attributes?.name).lowercase() to it.id }.also { tagCache = it }
    }

    override fun getFilterList() = FilterList(
        Filter.Sort(SORT, listOf("Relevance", "Popularity", "Latest chapter", "Title", "Year", "Rating"), Filter.Sort.Selection(1, false),
            keys = listOf("relevance", "followedCount", "latestUploadedChapter", "title", "year", "rating")),
        Filter.Group("Status", listOf("ongoing", "completed", "hiatus", "cancelled").map { Filter.CheckBox(it.replaceFirstChar(Char::uppercase), key = "status:$it") }),
        Filter.Group("Demographic", listOf("shounen", "shoujo", "seinen", "josei", "none").map { Filter.CheckBox(it.replaceFirstChar(Char::uppercase), key = "demo:$it") }),
        Filter.Select(RATING, listOf("Safe + suggestive", "Safe only", "Include erotica")),
        Filter.Select(ORIGINAL, listOf("Any", "Japanese", "Korean", "Chinese", "English"), keys = listOf("", "ja", "ko", "zh", "en")),
        Filter.Text(YEAR),
        Filter.Text(TAGS),
    )

    override suspend fun getDetails(item: SItem): SItem {
        val id = item.url.substringAfterLast('/')
        val res = json.decodeFromString(MdSingle.serializer(), getJsonText("$apiUrl/manga/$id?includes[]=cover_art&includes[]=author&includes[]=artist"))
        return toItem(res.data).copy(initialized = true)
    }

    override suspend fun getChapters(item: SItem): List<SChapter> {
        val id = item.url.substringAfterLast('/')
        val out = mutableListOf<SChapter>()
        var offset = 0
        do {
            val url = "$apiUrl/manga/$id/feed".toHttpUrl().newBuilder()
                .addQueryParameter("translatedLanguage[]", lang)
                .addQueryParameter("limit", "500")
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("includes[]", "scanlation_group")
                .addQueryParameter("order[volume]", "desc")
                .addQueryParameter("order[chapter]", "desc")
                .ratings(listOf("safe", "suggestive", "erotica", "pornographic"))
                .build().toString()
            val res = json.decodeFromString(MdList.serializer(), getJsonText(url))
            res.data.filter { it.attributes?.externalUrl == null }.forEach { c ->
                val a = c.attributes!!
                val title = buildString {
                    a.volume?.let { append("Vol.$it ") }
                    a.chapter?.let { append("Ch.$it") }
                    localized(a.title).takeIf { it.isNotBlank() }?.let { append(if (isEmpty()) it else " - $it") }
                    if (isEmpty()) append("Oneshot")
                }
                out += SChapter(
                    url = "/chapter/${c.id}",
                    name = title,
                    number = a.chapter?.toFloatOrNull() ?: -1f,
                    dateUpload = parseDate(a.publishAt ?: a.readableAt),
                    scanlator = c.relationships.firstOrNull { it.type == "scanlation_group" }?.attributes?.name?.let { localized(it) },
                )
            }
            if (res.data.isEmpty() || res.limit <= 0) break
            offset += res.limit
        } while (offset < res.total && offset < 5000)
        return out
    }

    override suspend fun getPages(chapter: SChapter): List<Page> {
        val id = chapter.url.substringAfterLast('/')
        val home = json.decodeFromString(MdAtHome.serializer(), getJsonText("$apiUrl/at-home/server/$id"))
        return home.chapter.data.mapIndexed { i, file -> Page(i, imageUrl = "${home.baseUrl}/data/${home.chapter.hash}/$file") }
    }

    private fun toItem(e: MdEntity): SItem {
        val a = e.attributes
        val cover = e.relationships.firstOrNull { it.type == "cover_art" }?.attributes?.fileName
        val title = title(a?.title, a?.altTitles) ?: e.id
        return SItem(
            url = "/manga/${e.id}",
            title = title,
            author = e.relationships.filter { it.type == "author" }.mapNotNull { r -> r.attributes?.name?.let { localized(it) } }.distinct().joinToString(", ").ifBlank { null },
            artist = e.relationships.filter { it.type == "artist" }.mapNotNull { r -> r.attributes?.name?.let { localized(it) } }.distinct().joinToString(", ").ifBlank { null },
            description = localized(a?.description).trim().ifBlank { null },
            genres = a?.tags.orEmpty().map { localized(it.attributes?.name) }.filter { it.isNotBlank() },
            status = when (a?.status) {
                "ongoing" -> Status.ONGOING
                "completed" -> Status.COMPLETED
                "hiatus" -> Status.HIATUS
                "cancelled" -> Status.CANCELLED
                else -> Status.UNKNOWN
            },
            thumbnailUrl = cover?.let { "$coversUrl/covers/${e.id}/$it.256.jpg" },
            type = SourceContent.MANGA,
            language = lang,
        )
    }

    /** A localized MangaDex value: a plain string or a `{lang: text}` map (anything else is ignored). */
    private fun localized(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.contentOrNull.orEmpty()
        is JsonObject -> listOfNotNull(e[lang], e["en"]).plus(e.values)
            .firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
        else -> ""
    }

    /**
     * Title in the source language, then English, looking at the main title before the alternative
     * ones (`altTitles` is `[{"ja": "..."}, {"en": "..."}]`), then whatever the main title has.
     */
    private fun title(main: JsonElement?, alt: JsonElement?): String? {
        val maps = listOfNotNull(main as? JsonObject) + (alt as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        fun pick(key: String) = maps.firstNotNullOfOrNull { (it[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        return pick(lang) ?: pick("en") ?: localized(main).ifBlank { null }
    }

    private fun parseDate(s: String?): Long = s?.let { runCatching { dateFormat.parse(it.substring(0, 19))?.time }.getOrNull() } ?: 0L

    companion object {
        const val LIMIT = 20

        /** Non-browser agent accepted by api.mangadex.org and uploads.mangadex.org. */
        val API_USER_AGENT: String get() = "Yomu/${SourceNetwork.appVersion} (Android)"
        private const val SORT = "Sort"
        private const val RATING = "Content rating"
        private const val ORIGINAL = "Original language"
        private const val YEAR = "Year"
        private const val TAGS = "Tags (comma separated)"
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val languages = listOf("en", "ru", "uk", "es", "es-la", "fr", "de", "it", "pt-br", "pl", "id", "vi", "tr", "zh", "zh-hk", "ko", "ja")
    }
}

/** One source per translation language, like in Mihon. */
class MangaDexFactory : SourceFactory {
    override fun createSources(): List<Source> = MangaDexSource.languages.map { MangaDexSource(it) }
}
