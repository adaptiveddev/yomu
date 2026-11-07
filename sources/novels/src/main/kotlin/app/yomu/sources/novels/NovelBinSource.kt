package app.yomu.sources.novels

import app.yomu.source.FilterList
import app.yomu.source.HttpSource
import app.yomu.source.ItemsPage
import app.yomu.source.SChapter
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import app.yomu.source.SourceException
import app.yomu.source.Status
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** NovelBin (novelbin.com): English web and light novels, read from the site's pages. */
class NovelBinSource(override val baseUrl: String = "https://novelbin.com") : HttpSource() {
    override val name = "NovelBin"
    override val lang = "en"
    override val content = SourceContent.NOVEL
    override val supportsLatest = true

    override fun imageHeaders(): Map<String, String> = mapOf("Referer" to "$baseUrl/")

    override suspend fun getPopular(page: Int): ItemsPage = list("$baseUrl/sort/top-view-novel".toHttpUrl().newBuilder().page(page).build().toString())

    override suspend fun getLatest(page: Int): ItemsPage =
        list("$baseUrl/sort/top-view-novel".toHttpUrl().newBuilder().addQueryParameter("sort", "LASTEST").page(page).build().toString())

    override suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage {
        if (query.isBlank()) return getPopular(page)
        return list("$baseUrl/search".toHttpUrl().newBuilder().addQueryParameter("keyword", query.trim()).page(page).build().toString())
    }

    private fun okhttp3.HttpUrl.Builder.page(page: Int) = apply { if (page > 1) addQueryParameter("page", page.toString()) }

    private suspend fun list(url: String): ItemsPage {
        val doc = Jsoup.parse(getText(url), url)
        val items = doc.select("div.list-novel div.row, div.list.list-novel div.row").mapNotNull { row ->
            val a = row.selectFirst("h3.novel-title a[href]") ?: return@mapNotNull null
            SItem(
                url = relative(a.absUrl("href")),
                title = a.attr("title").ifBlank { a.text() },
                author = row.selectFirst("span.author")?.text()?.trim()?.ifBlank { null },
                thumbnailUrl = cover(row.selectFirst("img[src], img[data-src]")),
                type = SourceContent.NOVEL,
                language = lang,
            )
        }.distinctBy { it.url }
        val pagination = doc.selectFirst("ul.pagination")
        val hasNext = pagination?.selectFirst("li.next:not(.disabled) a, li.last:not(.disabled) a") != null
        return ItemsPage(items, hasNext)
    }

    override suspend fun getDetails(item: SItem): SItem {
        val url = absolute(item.url)
        val doc = Jsoup.parse(getText(url), url)
        val info = doc.select("ul.info-meta li, ul.info li")
        fun field(label: String): Element? = info.firstOrNull { it.selectFirst("h3")?.text()?.trim()?.startsWith(label, ignoreCase = true) == true }
        val genres = (field("Genre")?.select("a").orEmpty() + field("Tag")?.select("a").orEmpty()).map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val statusText = field("Status")?.selectFirst("a")?.text()?.trim()?.lowercase().orEmpty()
        val descriptionEl: Element? = doc.selectFirst("#novel-description-content, div.desc-text")
        val description = descriptionEl?.let { d: Element -> d.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(d.text()) }.joinToString("\n\n") }
        return item.copy(
            title = doc.selectFirst("h3.title")?.text()?.trim()?.ifBlank { null } ?: item.title,
            author = field("Author")?.select("a")?.joinToString(", ") { it.text().trim() }?.ifBlank { null } ?: item.author,
            description = description?.ifBlank { null },
            genres = genres,
            status = when {
                "ongoing" in statusText -> Status.ONGOING
                "completed" in statusText -> Status.COMPLETED
                else -> Status.UNKNOWN
            },
            thumbnailUrl = cover(doc.selectFirst("div.book img[src]")) ?: doc.selectFirst("meta[property=og:image]")?.attr("content") ?: item.thumbnailUrl,
            initialized = true,
        )
    }

    override suspend fun getChapters(item: SItem): List<SChapter> {
        val slug = item.url.trimEnd('/').substringAfterLast('/')
        val url = "$baseUrl/ajax/chapter-archive".toHttpUrl().newBuilder().addQueryParameter("novelId", slug).build().toString()
        val doc = Jsoup.parse(getText(url), url)
        val links = doc.select("li[data-chapter-item] a[href], ul.list-chapter li a[href]")
        val chapters = links.map { a ->
            val title = a.attr("title").ifBlank { a.text() }.trim()
            SChapter(url = relative(a.absUrl("href")), name = title, number = chapterNumber(title))
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw SourceException("NovelBin: no chapters found for ${item.title}")
        return chapters.asReversed()
    }

    override suspend fun getContent(chapter: SChapter): String {
        val url = absolute(chapter.url)
        val doc = Jsoup.parse(getText(url), url)
        return chapterHtml(doc) ?: throw SourceException("NovelBin: the chapter text was not found")
    }

    override fun webUrl(item: SItem): String = absolute(item.url)

    private fun absolute(url: String): String = if (url.startsWith("http")) url else baseUrl + (if (url.startsWith('/')) url else "/$url")

    private fun relative(url: String): String = url.removePrefix(baseUrl).ifBlank { url }

    private fun cover(img: Element?): String? {
        if (img == null) return null
        val src = img.absUrl("data-src").ifBlank { img.absUrl("src") }.ifBlank { return null }
        // Lists use 80×113 thumbnails; the same path without the size is the full cover.
        return src.replace(Regex("/novel_\\d+_\\d+/"), "/novel/")
    }

    companion object {
        private val NUMBER = Regex("""(?i)chapter\s*(\d+(?:\.\d+)?)""")

        fun chapterNumber(title: String): Float = NUMBER.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f

        /** The text of a chapter page without ads, scripts and the site's hidden notices. */
        fun chapterHtml(doc: Document): String? {
            val body = doc.selectFirst("#chr-content, #chapter-content, div.chr-c") ?: return null
            body.select("script, style, iframe, ins, noscript, .ads, [class*=ad-slot], [id*=pf-], div[align=center], .chr-nav, button").remove()
            body.select("[style*=display:none], [style*=display: none]").remove()
            val paragraphs = body.select("p").filter { p -> p.text().isNotBlank() || p.selectFirst("img") != null }
            val html = if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n") { it.outerHtml() } else body.html()
            return html.takeIf { Jsoup.parse(it).text().isNotBlank() }
        }
    }
}
