package app.yomu.source

import java.security.MessageDigest

/** Minimal source description. */
interface Source {
    val id: Long
    val name: String
    val lang: String
    val content: SourceContent
    val supportsLatest: Boolean
    val supportsSearch: Boolean get() = true
}

/** A browsable catalog: popular, latest, search with declarative filters. */
interface CatalogSource : Source {
    suspend fun getPopular(page: Int): ItemsPage
    suspend fun getLatest(page: Int): ItemsPage
    suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage
    fun getFilterList(): FilterList = FilterList()

    suspend fun getDetails(item: SItem): SItem = item
    suspend fun getChapters(item: SItem): List<SChapter> = emptyList()

    /** Details and chapters together; sources that get both from one request override this. */
    suspend fun getDetailsAndChapters(item: SItem): Pair<SItem, List<SChapter>> {
        val details = getDetails(item)
        return details to if (details.type == SourceContent.BOOK) emptyList() else getChapters(details)
    }

    /** Manga: list of page images of a chapter. */
    suspend fun getPages(chapter: SChapter): List<Page> = emptyList()

    /** Web novels: HTML content of a chapter. */
    suspend fun getContent(chapter: SChapter): String = throw UnsupportedOperationException("Not a novel source")

    /** Books: downloadable files (OPDS style). */
    suspend fun getDownloadLinks(book: SItem): List<SBookLink> = book.links

    /** Opens a navigation entry (sub-catalog) when [SItem.isNavigation] is true. */
    suspend fun browse(url: String, page: Int): ItemsPage = ItemsPage(emptyList(), false)

    /** Headers for image requests (for example Referer). */
    fun imageHeaders(): Map<String, String> = emptyMap()

    suspend fun getImageUrl(page: Page): String = page.imageUrl ?: page.url

    /**
     * Downloads the image of [page] whose address is [imageUrl]. Sources with their own HTTP client (cookies,
     * descrambling interceptors, signed requests) return the response; null means "the app fetches [imageUrl]
     * with [imageHeaders]". The caller closes the response.
     */
    suspend fun fetchImage(page: Page, imageUrl: String): okhttp3.Response? = null

    /** Web address of a title (open in WebView / share), when the source knows it. */
    fun webUrl(item: SItem): String? = null

    /** Address to open in WebView when the site asks for a browser check. */
    val homeUrl: String? get() = null
}

/** Extensions may expose several sources (for example one per language). */
interface SourceFactory {
    fun createSources(): List<Source>
}

/** Sources that need user configuration (for example a custom OPDS address). */
interface ConfigurableSource : Source {
    val configKeys: List<String>
    fun configure(values: Map<String, String>)
}

object SourceIds {
    /** Stable 63-bit id from name, language and version, compatible with the approach used by Mihon. */
    fun generate(name: String, lang: String, version: Int = 1): Long {
        val key = "${name.lowercase()}/$lang/$version"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }
}
