package app.yomu.data.repository

import app.yomu.core.database.YomuDatabase
import app.yomu.data.source.SourceManager
import app.yomu.domain.catalog.ChapterOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class TitleInfo(val title: String, val cover: String?)

/** What was read on one day: a local book or an online title (latest chapter of that day). */
data class ReadingHistoryItem(
    val key: String,
    val day: LocalDate,
    val readAt: Long,
    val title: String,
    /** Authors of a book, or the chapter name of an online title. */
    val subtitle: String?,
    /** A file path (books) or an URL (online titles). */
    val cover: String?,
    val durationMs: Long,
    val pages: Int,
    val bookId: Long? = null,
    val progress: Float? = null,
    val finished: Boolean = false,
    val catalogItemId: Long? = null,
    val chapterId: Long? = null,
    val chaptersRead: Int = 0,
    val sourceName: String? = null,
    val isManga: Boolean = false,
)

/**
 * The reading timeline shared by local books and online titles: reading sessions (time, pages) plus the chapters
 * marked as read. Removing something from the history keeps its time in the statistics.
 */
class ReadingHistoryRepository(private val db: YomuDatabase, private val sources: SourceManager) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(zone: ZoneId = ZoneId.systemDefault()): Flow<List<ReadingHistoryItem>> =
        combine(db.sessions().observeVisible(LIMIT), db.catalog().observeHistory()) { s, c -> s to c }
            .mapLatest { (sessions, chapters) ->
                fun day(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
                val books = db.books().getAll(sessions.mapNotNull { it.bookId }.toSet()).associateBy { it.id }
                val itemIds = sessions.mapNotNull { it.catalogItemId }.toSet() + chapters.map { it.chapter.itemId }
                val items = if (itemIds.isEmpty()) emptyMap() else db.catalog().items(itemIds).associateBy { it.id }
                val out = ArrayList<ReadingHistoryItem>()

                sessions.filter { it.bookId != null }.groupBy { it.bookId!! to day(it.startedAt) }.forEach { (k, v) ->
                    val b = books[k.first] ?: return@forEach
                    out += ReadingHistoryItem(
                        key = "b:${b.id}:${k.second}",
                        day = k.second,
                        readAt = v.maxOf { it.startedAt + it.durationMs },
                        title = b.title,
                        subtitle = b.authorsText.ifBlank { null },
                        cover = b.coverPath,
                        durationMs = v.sumOf { it.durationMs },
                        pages = v.sumOf { it.pages },
                        bookId = b.id,
                        progress = b.progress,
                        finished = b.status == "FINISHED",
                        isManga = b.isManga,
                    )
                }

                val onlineTime = sessions.filter { it.catalogItemId != null }.groupBy { it.catalogItemId!! to day(it.startedAt) }
                val seen = HashSet<Pair<Long, LocalDate>>()
                chapters.groupBy { it.chapter.itemId to day(it.chapter.lastReadAt ?: 0) }.forEach { (k, rows) ->
                    val latest = rows.maxBy { it.chapter.lastReadAt ?: 0 }
                    val time = onlineTime[k].orEmpty()
                    seen += k
                    out += ReadingHistoryItem(
                        key = "c:${k.first}:${k.second}",
                        day = k.second,
                        readAt = maxOf(latest.chapter.lastReadAt ?: 0, time.maxOfOrNull { it.startedAt + it.durationMs } ?: 0),
                        title = latest.itemTitle,
                        subtitle = latest.chapter.name,
                        cover = latest.coverUrl,
                        durationMs = time.sumOf { it.durationMs },
                        pages = time.sumOf { it.pages },
                        catalogItemId = k.first,
                        chapterId = latest.chapter.id,
                        chaptersRead = rows.size,
                        sourceName = sources.get(latest.sourceId)?.name,
                        isManga = items[k.first]?.type?.let { it != "NOVEL" && it != "BOOK" } ?: true,
                    )
                }
                // Online reading whose chapters dropped out of the chapter list (older than its window).
                onlineTime.filterKeys { it !in seen }.forEach { (k, v) ->
                    val item = items[k.first] ?: return@forEach
                    out += ReadingHistoryItem(
                        key = "c:${k.first}:${k.second}",
                        day = k.second,
                        readAt = v.maxOf { it.startedAt + it.durationMs },
                        title = item.title,
                        subtitle = null,
                        cover = item.coverUrl,
                        durationMs = v.sumOf { it.durationMs },
                        pages = v.sumOf { it.pages },
                        catalogItemId = item.id,
                        sourceName = sources.get(item.sourceId)?.name,
                        isManga = item.type != "NOVEL" && item.type != "BOOK",
                    )
                }
                out.sortedByDescending { it.readAt }
            }
            .flowOn(Dispatchers.IO)

    /**
     * The chapter to open to continue [itemId]: its last chapter, or the next unread one when that was finished
     * (in the reading order of the title page; other groups' releases of a read chapter count as read).
     */
    suspend fun resumeChapter(itemId: Long, chapterId: Long?): Long? {
        val entities = db.catalog().chapters(itemId)
        if (entities.isEmpty()) return null
        val lastReadAt = entities.associate { it.id to (it.lastReadAt ?: 0L) }
        val chapters = ChapterOrder.reading(entities.map { it.toDomain() })
        val ch = chapters.firstOrNull { it.id == chapterId } ?: chapters.maxByOrNull { lastReadAt[it.id] ?: 0L } ?: chapters.first()
        if (!ch.read) return ch.id
        return ChapterOrder.nextUnread(chapters, ch.id)?.id ?: ch.id
    }

    /** Titles and covers for statistics keys ("b:<bookId>", "c:<catalogItemId>"). */
    suspend fun describe(keys: Collection<String>): Map<String, TitleInfo> {
        val bookIds = keys.filter { it.startsWith("b:") }.mapNotNull { it.substring(2).toLongOrNull() }
        val itemIds = keys.filter { it.startsWith("c:") }.mapNotNull { it.substring(2).toLongOrNull() }
        val out = HashMap<String, TitleInfo>()
        if (bookIds.isNotEmpty()) db.books().getAll(bookIds).forEach { out["b:${it.id}"] = TitleInfo(it.title, it.coverPath) }
        if (itemIds.isNotEmpty()) db.catalog().items(itemIds).forEach { out["c:${it.id}"] = TitleInfo(it.title, it.coverUrl) }
        return out
    }

    suspend fun finishedBooksSince(from: Long): Int = db.books().finishedSince(from)

    suspend fun remove(item: ReadingHistoryItem) {
        item.bookId?.let { db.sessions().hideBook(it) }
        item.catalogItemId?.let {
            db.sessions().hideCatalogItem(it)
            db.catalog().removeHistoryOfItem(it)
        }
    }

    suspend fun clear() {
        db.sessions().hideAll()
        db.catalog().clearHistory()
    }

    private companion object {
        const val LIMIT = 4000
    }
}
