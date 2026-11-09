package app.yomu.data.audio

import app.yomu.source.CatalogSource
import app.yomu.source.Filter
import app.yomu.source.FilterList
import app.yomu.source.ItemsPage
import app.yomu.source.MIME_AUDIO_STREAM
import app.yomu.source.SBookLink
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import app.yomu.source.SourceIds
import app.yomu.source.Status
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube in the catalog: search for long videos (audiobooks, lectures, podcasts) and playlists; "Listen online" adds
 * one as an audiobook. YouTube pages with continuation tokens, so the token of each next page is remembered per search.
 */
class YouTubeSource(private val youtube: YouTubeClient) : CatalogSource {
    override val id: Long = SourceIds.generate("YouTube", "all")
    override val name = "YouTube"
    override val lang = "all"
    override val content = SourceContent.BOOK
    override val supportsLatest = false

    private val russian get() = Locale.getDefault().language == "ru"
    private val tokens = ConcurrentHashMap<String, String>()

    override fun getFilterList() = FilterList(
        Filter.Select(
            if (russian) "Искать" else "Search for",
            if (russian) listOf("Длинные видео (20+ мин)", "Все видео", "Плейлисты") else listOf("Long videos (20+ min)", "All videos", "Playlists"),
            keys = YouTubeSearchFilter.entries.map { it.name },
        ),
    )

    override suspend fun getPopular(page: Int) = search(if (russian) "аудиокнига" else "audiobook", getFilterList(), page)

    override suspend fun getLatest(page: Int) = ItemsPage(emptyList(), false)

    override suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage {
        val q = query.trim()
        if (q.isEmpty()) return getPopular(page)
        val filter = filters.leaves().filterIsInstance<Filter.Select>().firstOrNull()
            ?.selectedKey?.let { k -> YouTubeSearchFilter.entries.firstOrNull { it.name == k } } ?: YouTubeSearchFilter.LONG_VIDEOS
        val key = "$filter|$q"
        val token = if (page <= 1) null else tokens["$key|$page"] ?: return ItemsPage(emptyList(), false)
        val result = youtube.search(q, filter, token)
        result.continuation?.let { tokens["$key|${page + 1}"] = it }
        return ItemsPage(result.items.map(::item), result.continuation != null && result.items.isNotEmpty())
    }

    override fun webUrl(item: SItem): String = item.url

    /**
     * The library stores a title without its links, so the title page asks again: every YouTube video or playlist is
     * one "Listen online" stream (the address itself; the player resolves it).
     */
    override suspend fun getDownloadLinks(book: SItem): List<SBookLink> =
        book.links.ifEmpty { listOfNotNull(book.url.takeIf { YouTubeClient.parseUrl(it) != null }?.let { SBookLink(it, MIME_AUDIO_STREAM) }) }

    override val homeUrl: String get() = "https://www.youtube.com"

    private fun item(r: YouTubeResult) = SItem(
        url = r.url,
        title = r.title,
        author = r.author,
        description = when {
            r.isPlaylist -> r.videoCount?.let { if (russian) "▶ Плейлист · видео: $it" else "▶ Playlist · $it videos" } ?: if (russian) "▶ Плейлист" else "▶ Playlist"
            r.durationMs > 0 -> "⏱ " + duration(r.durationMs)
            else -> null
        },
        status = Status.COMPLETED,
        thumbnailUrl = r.thumbnailUrl,
        type = SourceContent.BOOK,
        links = listOf(SBookLink(r.url, MIME_AUDIO_STREAM)),
        initialized = true,
    )

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
