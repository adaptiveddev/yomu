package app.yomu.sources.opds

import app.yomu.source.FilterList
import app.yomu.source.HttpSource
import app.yomu.source.ItemsPage
import app.yomu.source.SBookLink
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

/** Project Gutenberg through the public Gutendex JSON API (fast search, popular, newest). */
class GutenbergSource(private val api: String = "https://gutendex.com") : HttpSource() {
    override val name = "Project Gutenberg"
    override val lang = "all"
    override val baseUrl = api
    override val content = SourceContent.BOOK
    override val supportsLatest = true

    private suspend fun list(url: String): ItemsPage {
        val root = json.parseToJsonElement(getText(url)).jsonObject
        val items = root["results"]!!.jsonArray.map { r ->
            val o = r.jsonObject
            val formats = o["formats"]?.jsonObject.orEmpty()
            val links = formats.mapNotNull { (type, v) ->
                val href = v.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                if (type.startsWith("application/epub") || type.startsWith("application/x-mobipocket") || type.startsWith("text/plain") || type.startsWith("application/pdf")) {
                    SBookLink(href, type.substringBefore(';'))
                } else {
                    null
                }
            }.sortedBy { if (it.mimeType.contains("epub")) 0 else 1 }
            SItem(
                url = "$api/books/${o["id"]!!.jsonPrimitive.content}",
                title = o.str("title").orEmpty(),
                author = o["authors"]?.jsonArray?.joinToString("; ") { it.jsonObject.str("name").orEmpty() }?.ifBlank { null },
                genres = o["subjects"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.take(8).orEmpty(),
                thumbnailUrl = formats["image/jpeg"]?.jsonPrimitive?.contentOrNull,
                type = SourceContent.BOOK,
                language = o["languages"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
                links = links,
                initialized = true,
            )
        }
        return ItemsPage(items, root.str("next") != null)
    }

    override suspend fun getPopular(page: Int) = list("$api/books/?sort=popular&page=$page")
    override suspend fun getLatest(page: Int) = list("$api/books/?sort=descending&page=$page")
    override suspend fun search(query: String, filters: FilterList, page: Int) = list("$api/books/?search=${enc(query)}&page=$page")
}

/** Internet Archive texts with EPUB/PDF files (advanced search + metadata API). */
class InternetArchiveSource(private val api: String = "https://archive.org") : HttpSource() {
    override val name = "Internet Archive"
    override val lang = "all"
    override val baseUrl = api
    override val content = SourceContent.BOOK
    override val supportsLatest = true

    private suspend fun query(q: String, sort: String, page: Int): ItemsPage {
        val url = "$api/advancedsearch.php?q=${enc("($q) AND mediatype:(texts) AND format:(EPUB)")}" +
            "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=language&fl[]=description&sort[]=${enc(sort)}&rows=30&page=$page&output=json"
        val resp = json.parseToJsonElement(getText(url)).jsonObject["response"]!!.jsonObject
        val docs = resp["docs"]!!.jsonArray.map { it.jsonObject }
        val total = resp["numFound"]?.jsonPrimitive?.intOrNull ?: 0
        val items = docs.map { d ->
            val id = d.str("identifier")!!
            fun first(k: String) = d[k]?.let { runCatching { it.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull }.getOrNull() ?: it.jsonPrimitive.contentOrNull }
            SItem(
                url = "$api/details/$id",
                title = first("title") ?: id,
                author = first("creator"),
                description = first("description"),
                thumbnailUrl = "$api/services/img/$id",
                type = SourceContent.BOOK,
                language = first("language"),
            )
        }
        return ItemsPage(items, page * 30 < total)
    }

    override suspend fun getPopular(page: Int) = query("collection:(gutenberg OR americana OR opensource)", "downloads desc", page)
    override suspend fun getLatest(page: Int) = query("collection:(opensource)", "publicdate desc", page)
    override suspend fun search(query: String, filters: FilterList, page: Int) = query(query, "downloads desc", page)

    override suspend fun getDownloadLinks(book: SItem): List<SBookLink> {
        val id = book.url.substringAfterLast("/details/").substringBefore('/')
        val meta = json.parseToJsonElement(getText("$api/metadata/$id")).jsonObject
        return meta["files"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { f ->
            val format = f.str("format").orEmpty()
            (format == "EPUB" || format == "Text PDF" || format == "DjVuTXT") && f.str("private") != "true"
        }.map { f ->
            val file = f.str("name")!!
            val mime = when {
                file.endsWith(".epub") -> "application/epub+zip"
                file.endsWith(".pdf") -> "application/pdf"
                else -> "text/plain"
            }
            SBookLink("$api/download/$id/${file.split('/').joinToString("/") { enc(it).replace("+", "%20") }}", mime, file, f.str("size")?.toLongOrNull())
        }
    }
}

/** Open Library search; downloadable editions are served by the Internet Archive. */
class OpenLibrarySource(private val api: String = "https://openlibrary.org") : HttpSource() {
    override val name = "Open Library"
    override val lang = "all"
    override val baseUrl = api
    override val content = SourceContent.BOOK
    override val supportsLatest = false
    private val archive = InternetArchiveSource()

    private suspend fun list(q: String, page: Int): ItemsPage {
        val root = json.parseToJsonElement(
            getText("$api/search.json?q=${enc(q)}&has_fulltext=true&ebook_access=public&fields=key,title,author_name,cover_i,ia,language,first_publish_year,subject&limit=20&page=$page"),
        ).jsonObject
        val docs = root["docs"]!!.jsonArray.map { it.jsonObject }
        val total = root["numFound"]?.jsonPrimitive?.intOrNull ?: 0
        val items = docs.mapNotNull { d ->
            val ia = d["ia"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SItem(
                url = "https://archive.org/details/$ia",
                title = d.str("title").orEmpty(),
                author = d["author_name"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content },
                genres = d["subject"]?.jsonArray?.take(6)?.map { it.jsonPrimitive.content }.orEmpty(),
                thumbnailUrl = d["cover_i"]?.jsonPrimitive?.contentOrNull?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
                type = SourceContent.BOOK,
                language = d["language"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
            )
        }
        return ItemsPage(items, page * 20 < total)
    }

    override suspend fun getPopular(page: Int) = list("subject:classics", page)
    override suspend fun getLatest(page: Int) = getPopular(page)
    override suspend fun search(query: String, filters: FilterList, page: Int) = list(query, page)
    override suspend fun getDownloadLinks(book: SItem) = archive.getDownloadLinks(book)
}

/** Wikisource texts exported to EPUB by the official WS Export tool. */
class WikisourceSource(override val lang: String = "en") : HttpSource() {
    override val name = "Wikisource"
    override val baseUrl = "https://$lang.wikisource.org"
    override val content = SourceContent.BOOK
    override val supportsLatest = false

    private fun item(title: String, snippet: String?) = SItem(
        url = "$baseUrl/wiki/${enc(title.replace(' ', '_'))}",
        title = title,
        description = snippet?.replace(Regex("<[^>]+>"), ""),
        type = SourceContent.BOOK,
        language = lang,
        links = listOf(SBookLink("https://ws-export.wmcloud.org/?format=epub&lang=$lang&page=${enc(title)}", "application/epub+zip", "EPUB")),
        initialized = true,
    )

    override suspend fun getPopular(page: Int): ItemsPage {
        val root = json.parseToJsonElement(getText("$baseUrl/w/api.php?action=query&list=random&rnnamespace=0&rnlimit=30&format=json")).jsonObject
        val list = root["query"]!!.jsonObject["random"]!!.jsonArray.map { item(it.jsonObject.str("title")!!, null) }
        return ItemsPage(list, page < 5)
    }

    override suspend fun getLatest(page: Int) = getPopular(page)

    override suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage {
        val offset = (page - 1) * 30
        val root = json.parseToJsonElement(
            getText("$baseUrl/w/api.php?action=query&list=search&srsearch=${enc(query)}&srnamespace=0&srlimit=30&sroffset=$offset&format=json"),
        ).jsonObject
        val q = root["query"]!!.jsonObject
        val list = q["search"]!!.jsonArray.map { it.jsonObject.let { o -> item(o.str("title")!!, o.str("snippet")) } }
        val total = q["searchinfo"]?.jsonObject?.get("totalhits")?.jsonPrimitive?.intOrNull ?: 0
        return ItemsPage(list, offset + 30 < total)
    }
}
