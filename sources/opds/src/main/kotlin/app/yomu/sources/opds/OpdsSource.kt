package app.yomu.sources.opds

import app.yomu.source.ConfigurableSource
import app.yomu.source.FilterList
import app.yomu.source.HttpSource
import app.yomu.source.ItemsPage
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import app.yomu.source.SourceIds
import okhttp3.Credentials
import okhttp3.Headers
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic OPDS 1.2 / 2.0 catalog. The user adds any catalog by address (Project Gutenberg, Standard Ebooks,
 * Internet Archive, a personal Calibre server, …). Pagination follows rel="next" links.
 */
class OpdsSource(
    override val name: String,
    feedUrl: String,
    override val lang: String = "all",
    private var username: String? = null,
    private var password: String? = null,
) : HttpSource(), ConfigurableSource {
    override var baseUrl: String = feedUrl
        private set
    override val id: Long = SourceIds.generate("opds:$feedUrl", lang)
    override val content = SourceContent.BOOK
    override val supportsLatest = true
    override val configKeys = listOf("url", "username", "password")

    /** page-number -> URL for each browsed feed, since OPDS paginates with links. */
    private val pages = ConcurrentHashMap<String, MutableMap<Int, String>>()
    private var root: OpdsFeed? = null
    private var searchTemplate: String? = null

    override fun configure(values: Map<String, String>) {
        values["url"]?.let { baseUrl = it; root = null; pages.clear() }
        username = values["username"]?.ifBlank { null }
        password = values["password"]?.ifBlank { null }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        val u = username
        val p = password
        if (u != null && p != null) add("Authorization", Credentials.basic(u, p))
        add("Accept", "application/atom+xml, application/opds+json, application/json;q=0.9, */*;q=0.8")
    }

    private suspend fun load(url: String): OpdsFeed = OpdsParser.parse(getText(url), url)

    private suspend fun rootFeed(): OpdsFeed = root ?: load(baseUrl).also { root = it }

    private suspend fun paged(key: String, firstUrl: String, page: Int): ItemsPage {
        val map = pages.getOrPut(key) { mutableMapOf(1 to firstUrl) }
        val url = map[page] ?: return ItemsPage(emptyList(), false)
        val feed = load(url)
        feed.nextUrl?.let { map[page + 1] = it }
        return feed.toPage()
    }

    override suspend fun getPopular(page: Int): ItemsPage {
        val r = rootFeed()
        return paged("popular", r.popularUrl ?: baseUrl, page)
    }

    override suspend fun getLatest(page: Int): ItemsPage {
        val r = rootFeed()
        return paged("new", r.newUrl ?: baseUrl, page)
    }

    override suspend fun browse(url: String, page: Int): ItemsPage = paged("browse:$url", url, page)

    override suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage {
        val template = searchTemplate ?: run {
            val r = rootFeed()
            val s = r.searchUrl ?: return ItemsPage(emptyList(), false)
            (if (r.searchIsOpenSearchDescription) OpdsParser.parseOpenSearch(getText(s), s) else s).also { searchTemplate = it }
        } ?: return ItemsPage(emptyList(), false)
        return paged("search:$query", OpdsParser.fillTemplate(template, query), page)
    }

    override suspend fun getDetails(item: SItem): SItem = item

    companion object {
        /** Well-known public catalogs offered as presets. */
        val presets = listOf(
            "Project Gutenberg" to "https://www.gutenberg.org/ebooks.opds/",
            "Standard Ebooks" to "https://standardebooks.org/feeds/opds",
            "Feedbooks (public domain)" to "https://catalog.feedbooks.com/catalog/public_domain.atom",
            "ManyBooks" to "https://manybooks.net/opds/index.php",
        )
    }
}
