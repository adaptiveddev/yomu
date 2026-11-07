package app.yomu.sources.opds

import app.yomu.source.ItemsPage
import app.yomu.source.SBookLink
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI

/** Parsed OPDS feed (1.x Atom or 2.0 JSON). */
data class OpdsFeed(
    val title: String,
    val items: List<SItem>,
    val nextUrl: String?,
    val searchUrl: String?,
    val searchIsOpenSearchDescription: Boolean,
    val popularUrl: String?,
    val newUrl: String?,
) {
    fun toPage() = ItemsPage(items, nextUrl != null)
}

object OpdsParser {
    private const val ACQ = "http://opds-spec.org/acquisition"

    fun parse(body: String, baseUrl: String): OpdsFeed =
        if (body.trimStart().startsWith("{")) parseJson(body, baseUrl) else parseAtom(body, baseUrl)

    private fun resolve(base: String, href: String?): String? {
        if (href.isNullOrBlank()) return null
        // URI templates ("search{?query}") are not valid URIs: resolve the part before the template.
        val brace = href.indexOf('{')
        if (brace > 0) return resolve(base, href.substring(0, brace)) + href.substring(brace)
        if (brace == 0) return base.substringBefore('?') + href
        return runCatching { URI(base).resolve(href.replace(" ", "%20")).toString() }.getOrDefault(href)
    }

    fun parseAtom(xml: String, baseUrl: String): OpdsFeed {
        val doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser())
        val feed = doc.selectFirst("feed") ?: doc
        val feedLinks = feed.children().filter { it.tagName() == "link" }
        fun feedLink(predicate: (Element) -> Boolean) = feedLinks.firstOrNull(predicate)?.attr("href")?.let { resolve(baseUrl, it) }
        val search = feedLinks.firstOrNull { it.attr("rel") == "search" }
        val items = feed.children().filter { it.tagName() == "entry" }.mapNotNull { parseEntry(it, baseUrl) }
        return OpdsFeed(
            title = feed.children().firstOrNull { it.tagName() == "title" }?.text().orEmpty(),
            items = items,
            nextUrl = feedLink { it.attr("rel") == "next" },
            searchUrl = search?.attr("href")?.let { resolve(baseUrl, it) },
            searchIsOpenSearchDescription = search?.attr("type")?.contains("opensearchdescription") == true,
            popularUrl = feedLink { it.attr("rel").endsWith("/sort/popular") },
            newUrl = feedLink { it.attr("rel").endsWith("/sort/new") },
        )
    }

    private fun parseEntry(e: Element, base: String): SItem? {
        val title = e.children().firstOrNull { it.tagName() == "title" }?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null
        val links = e.children().filter { it.tagName() == "link" }
        val acquisitions = links.filter { it.attr("rel").startsWith(ACQ) && !it.attr("rel").contains("borrow") && !it.attr("rel").contains("buy") }
            .mapNotNull { l ->
                resolve(base, l.attr("href"))?.let {
                    SBookLink(it, l.attr("type").ifBlank { "application/octet-stream" }, l.attr("title").ifBlank { null }, l.attr("length").toLongOrNull())
                }
            }
        val image = links.firstOrNull { it.attr("rel") == "http://opds-spec.org/image/thumbnail" || it.attr("rel") == "x-stanza-cover-image-thumbnail" }
            ?: links.firstOrNull { it.attr("rel").startsWith("http://opds-spec.org/image") || it.attr("rel") == "x-stanza-cover-image" }
        val nav = links.firstOrNull { it.attr("type").contains("profile=opds-catalog") || (it.attr("type").contains("atom+xml") && it.attr("rel") != "alternate") }
        val authors = e.children().filter { it.tagName() == "author" }.mapNotNull { a -> a.children().firstOrNull { it.tagName() == "name" }?.text() }
        val summary = e.children().firstOrNull { it.tagName() == "summary" || it.tagName() == "content" }?.let { c ->
            if (c.attr("type") == "html") Jsoup.parse(Parser.unescapeEntities(c.text(), false)).text() else c.text()
        }
        val lang = e.children().firstOrNull { it.tagName() == "dc:language" || it.tagName() == "dcterms:language" }?.text()
        val genres = e.children().filter { it.tagName() == "category" }.map { it.attr("label").ifBlank { it.attr("term") } }.filter { it.isNotBlank() }
        val isNav = acquisitions.isEmpty() && nav != null
        val url = if (isNav) resolve(base, nav.attr("href")) else (links.firstOrNull { it.attr("rel") == "alternate" }?.attr("href")?.let { resolve(base, it) }
            ?: e.children().firstOrNull { it.tagName() == "id" }?.text() ?: title)
        return SItem(
            url = url ?: return null,
            title = title,
            author = authors.joinToString(", ").ifBlank { null },
            description = summary,
            genres = genres,
            thumbnailUrl = image?.attr("href")?.let { resolve(base, it) },
            type = SourceContent.BOOK,
            language = lang,
            links = acquisitions,
            isNavigation = isNav,
            initialized = true,
        )
    }

    /** OpenSearch description -> template URL with {searchTerms}. */
    fun parseOpenSearch(xml: String, base: String): String? {
        val doc = Jsoup.parse(xml, base, Parser.xmlParser())
        val urls = doc.select("Url")
        val u = urls.firstOrNull { it.attr("type").contains("atom") } ?: urls.firstOrNull()
        return u?.attr("template")?.let { resolve(base, it) }
    }

    fun fillTemplate(template: String, query: String): String {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return template.replace("{searchTerms}", q).replace("{?query}", "?query=$q").replace("{query}", q)
            .replace(Regex("\\{[^}]*\\?}"), "").replace(Regex("\\{\\?[^}]*}"), "")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseJson(body: String, base: String): OpdsFeed {
        val root = json.parseToJsonElement(body).jsonObject
        val links = root["links"]?.jsonArray.orEmpty().map { it.jsonObject }
        fun link(rel: String) = links.firstOrNull { l -> relOf(l).contains(rel) }?.get("href")?.jsonPrimitive?.contentOrNull?.let { resolve(base, it) }
        val items = mutableListOf<SItem>()
        fun collect(obj: JsonObject) {
            obj["navigation"]?.jsonArray?.forEach { n ->
                val o = n.jsonObject
                val href = o["href"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                items += SItem(url = resolve(base, href)!!, title = o["title"]?.jsonPrimitive?.contentOrNull ?: href, type = SourceContent.BOOK, isNavigation = true, initialized = true)
            }
            obj["publications"]?.jsonArray?.forEach { p -> parsePublication(p.jsonObject, base)?.let { items += it } }
        }
        collect(root)
        root["groups"]?.jsonArray?.forEach { collect(it.jsonObject) }
        return OpdsFeed(
            title = root["metadata"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty(),
            items = items,
            nextUrl = link("next"),
            searchUrl = link("search"),
            searchIsOpenSearchDescription = false,
            popularUrl = link("http://opds-spec.org/sort/popular"),
            newUrl = link("http://opds-spec.org/sort/new"),
        )
    }

    private fun relOf(o: JsonObject): List<String> = when (val r = o["rel"]) {
        is JsonPrimitive -> listOfNotNull(r.contentOrNull)
        is JsonArray -> r.mapNotNull { it.jsonPrimitive.contentOrNull }
        else -> emptyList()
    }

    private fun names(e: JsonElement?): List<String> = when (e) {
        null -> emptyList()
        is JsonPrimitive -> listOfNotNull(e.contentOrNull)
        is JsonArray -> e.flatMap { names(it) }
        is JsonObject -> listOfNotNull(e["name"]?.let { n -> if (n is JsonObject) n.values.firstOrNull()?.jsonPrimitive?.contentOrNull else n.jsonPrimitive.contentOrNull })
    }

    private fun parsePublication(p: JsonObject, base: String): SItem? {
        val meta = p["metadata"]?.jsonObject ?: return null
        val title = when (val t = meta["title"]) {
            is JsonPrimitive -> t.contentOrNull
            is JsonObject -> t.values.firstOrNull()?.jsonPrimitive?.contentOrNull
            else -> null
        } ?: return null
        val links = p["links"]?.jsonArray.orEmpty().map { it.jsonObject }
        val acq = links.filter { l -> relOf(l).any { it.startsWith(ACQ) } }.mapNotNull { l ->
            val href = l["href"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SBookLink(resolve(base, href)!!, l["type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream")
        }
        val image = p["images"]?.jsonArray?.firstOrNull()?.jsonObject?.get("href")?.jsonPrimitive?.contentOrNull
        val self = links.firstOrNull { l -> relOf(l).contains("self") }?.get("href")?.jsonPrimitive?.contentOrNull
        return SItem(
            url = resolve(base, self ?: meta["identifier"]?.jsonPrimitive?.contentOrNull ?: title)!!,
            title = title,
            author = names(meta["author"]).joinToString(", ").ifBlank { null },
            description = meta["description"]?.jsonPrimitive?.contentOrNull?.let { Jsoup.parse(it).text() },
            genres = names(meta["subject"]),
            thumbnailUrl = image?.let { resolve(base, it) },
            type = SourceContent.BOOK,
            language = names(meta["language"]).firstOrNull(),
            links = acq,
            initialized = true,
        )
    }
}
