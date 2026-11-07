package app.yomu.data.source.tachiyomi

import app.yomu.source.CatalogSource
import app.yomu.source.FilterList
import app.yomu.source.HttpStatusException
import app.yomu.source.ItemsPage
import app.yomu.source.Page
import app.yomu.source.SChapter
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import app.yomu.source.SourceException
import app.yomu.source.Status
import app.yomu.source.WebViewRequiredException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Response
import java.io.IOException
import eu.kanade.tachiyomi.source.CatalogueSource as TCatalogueSource
import eu.kanade.tachiyomi.source.model.Page as TPage
import eu.kanade.tachiyomi.source.model.SChapter as TChapter
import eu.kanade.tachiyomi.source.model.SManga as TManga
import eu.kanade.tachiyomi.source.online.HttpSource as THttpSource

/** A source of a Tachiyomi/Mihon extension presented as a Yomu [CatalogSource]. */
class TachiyomiSource(val delegate: TCatalogueSource, val pkg: String) : CatalogSource {
    override val id: Long = delegate.id
    override val name: String = delegate.name
    override val lang: String = delegate.lang
    override val content: SourceContent = SourceContent.MANGA
    override val supportsLatest: Boolean = delegate.supportsLatest

    private val http: THttpSource? = delegate as? THttpSource

    /** Has an androidx.preference settings screen (`ConfigurableSource`). */
    val hasPreferences: Boolean get() = delegate is ConfigurableSource

    override val homeUrl: String? get() = http?.let { runCatching { it.getHomeUrl() }.getOrNull() ?: runCatching { it.baseUrl }.getOrNull() }

    override suspend fun getPopular(page: Int): ItemsPage = call { delegate.getPopularManga(page).toItems() }

    override suspend fun getLatest(page: Int): ItemsPage = call { delegate.getLatestUpdates(page).toItems() }

    override suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage = call {
        val fresh = delegate.getFilterList()
        TachiyomiFilters.applyStates(filters, fresh)
        delegate.getSearchManga(page, query, fresh).toItems()
    }

    override fun getFilterList(): FilterList = runCatching { TachiyomiFilters.toYomu(delegate.getFilterList()) }.getOrDefault(FilterList())

    override suspend fun getDetails(item: SItem): SItem = call {
        delegate.getMangaUpdate(item.toT(), emptyList(), fetchDetails = true, fetchChapters = false).manga.toItem(item).copy(initialized = true)
    }

    override suspend fun getChapters(item: SItem): List<SChapter> = call {
        delegate.getMangaUpdate(item.toT(), emptyList(), fetchDetails = false, fetchChapters = true).chapters.mapNotNull { it.toYomu() }
    }

    override suspend fun getDetailsAndChapters(item: SItem): Pair<SItem, List<SChapter>> = call {
        val update = delegate.getMangaUpdate(item.toT(), emptyList(), fetchDetails = true, fetchChapters = true)
        update.manga.toItem(item).copy(initialized = true) to update.chapters.mapNotNull { it.toYomu() }
    }

    override suspend fun getPages(chapter: SChapter): List<Page> = call {
        delegate.getPageList(chapter.toT()).mapIndexed { i, p -> Page(i, p.url, p.imageUrl?.takeIf { it.isNotBlank() }) }
    }

    override suspend fun getImageUrl(page: Page): String = page.imageUrl ?: call {
        http?.getImageUrl(TPage(page.index, page.url)) ?: page.url
    }

    override fun imageHeaders(): Map<String, String> =
        runCatching { http?.headers?.let { h -> h.names().associateWith { h[it].orEmpty() } } }.getOrNull().orEmpty()

    override suspend fun fetchImage(page: Page, imageUrl: String): Response? {
        val source = http ?: return null
        return call { source.getImage(TPage(page.index, page.url, imageUrl)) }
    }

    override fun webUrl(item: SItem): String? = http?.let { runCatching { it.getMangaUrl(item.toT()) }.getOrNull() }

    override fun toString(): String = "$name ($lang)"

    /** Turns extension failures into errors the app can explain (and offer WebView for anti-bot pages). */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        val home = homeUrl
        if (home != null && e.code in WEBVIEW_CODES) throw WebViewRequiredException(home)
        throw HttpStatusException(e.code, home.orEmpty())
    } catch (e: IOException) {
        val home = homeUrl
        if (home != null && generateSequence<Throwable>(e) { it.cause }.any { it.javaClass.simpleName == "CloudflareBypassException" }) {
            throw WebViewRequiredException(home)
        }
        throw e
    } catch (e: UnsupportedOperationException) {
        throw SourceException(e.message ?: "Not supported by $name", e)
    } catch (e: Exception) {
        // Extensions report site errors with plain exceptions (e.g. "Login required"): keep their message.
        throw SourceException(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName, e)
    } catch (e: NotImplementedError) {
        // Kotlin's TODO() in an extension: a feature the source does not offer.
        throw SourceException("Not supported by $name", e)
    } catch (e: LinkageError) {
        // Built against another extensions-lib/app API (missing class or method): reported as an outdated source
        // instead of crashing the app.
        throw SourceException("$name is not compatible with this app version: ${e.javaClass.simpleName}", e)
    } catch (e: StackOverflowError) {
        throw SourceException("$name failed to parse the answer of the site", e)
    }

    private fun MangasPage.toItems() = ItemsPage(mangas.mapNotNull { m -> runCatching { m.toItem(null) }.getOrNull() }, hasNextPage)

    companion object {
        // 429 is plain rate limiting: retried with backoff instead of asking for WebView.
        private val WEBVIEW_CODES = setOf(403, 503)
        private val json = Json { ignoreUnknownKeys = true }

        fun TManga.toItem(fallback: SItem?): SItem {
            val url = runCatching { url }.getOrNull() ?: fallback?.url ?: error("Manga without url")
            val title = runCatching { title }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback?.title.orEmpty()
            return SItem(
                url = url,
                title = title,
                author = author ?: fallback?.author,
                artist = artist ?: fallback?.artist,
                description = description ?: fallback?.description,
                genres = genre?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: fallback?.genres.orEmpty(),
                status = statusFromT(status),
                thumbnailUrl = thumbnail_url?.takeIf { it.isNotBlank() } ?: fallback?.thumbnailUrl,
                type = SourceContent.MANGA,
                initialized = initialized,
                memo = runCatching { memo }.getOrNull()?.takeIf { it.isNotEmpty() }?.toString() ?: fallback?.memo,
            )
        }

        fun SItem.toT(): TManga = TManga.create().also { m ->
            m.url = url
            m.title = title
            m.author = author
            m.artist = artist
            m.description = description
            m.genre = genres.joinToString(", ").ifEmpty { null }
            m.status = statusToT(status)
            m.thumbnail_url = thumbnailUrl
            m.initialized = initialized
            memo?.let { runCatching { m.memo = json.parseToJsonElement(it).jsonObject } }
        }

        fun TChapter.toYomu(): SChapter? {
            val url = runCatching { url }.getOrNull() ?: return null
            val name = runCatching { name }.getOrNull().orEmpty()
            val number = chapter_number.takeIf { it >= 0f } ?: ChapterNumbers.parse(name)
            return SChapter(
                url = url,
                name = name.ifBlank { url },
                number = number,
                dateUpload = date_upload,
                scanlator = scanlator?.takeIf { it.isNotBlank() },
                memo = runCatching { memo }.getOrNull()?.takeIf { it.isNotEmpty() }?.toString(),
            )
        }

        fun SChapter.toT(): TChapter = TChapter.create().also { c ->
            c.url = url
            c.name = name
            c.chapter_number = number
            c.date_upload = dateUpload
            c.scanlator = scanlator
            memo?.let { runCatching { c.memo = json.parseToJsonElement(it).jsonObject as JsonObject } }
        }

        fun statusFromT(s: Int): Int = when (s) {
            TManga.ONGOING -> Status.ONGOING
            TManga.COMPLETED, TManga.PUBLISHING_FINISHED -> Status.COMPLETED
            TManga.ON_HIATUS -> Status.HIATUS
            TManga.CANCELLED -> Status.CANCELLED
            else -> Status.UNKNOWN
        }

        fun statusToT(s: Int): Int = when (s) {
            Status.ONGOING -> TManga.ONGOING
            Status.COMPLETED -> TManga.COMPLETED
            Status.HIATUS -> TManga.ON_HIATUS
            Status.CANCELLED -> TManga.CANCELLED
            else -> TManga.UNKNOWN
        }
    }
}

/** Chapter number from a title like "Vol.2 Ch.15.5", "Глава 12" or "#7" when the source gives none. */
object ChapterNumbers {
    private val marked = Regex("""(?i)(?:ch(?:apter)?|глава|гл|ep(?:isode)?|#)\.?\s*(\d+(?:[.,]\d+)?)""")
    private val anyNumber = Regex("""(\d+(?:[.,]\d+)?)""")

    fun parse(name: String): Float {
        val cleaned = name.replace(Regex("""(?i)(?:vol(?:ume)?|том|т)\.?\s*\d+"""), " ")
        val m = marked.find(cleaned) ?: anyNumber.find(cleaned) ?: return -1f
        return m.groupValues[1].replace(',', '.').toFloatOrNull() ?: -1f
    }
}
