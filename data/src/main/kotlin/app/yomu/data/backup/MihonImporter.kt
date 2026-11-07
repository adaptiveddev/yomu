@file:OptIn(ExperimentalSerializationApi::class)

package app.yomu.data.backup

import androidx.room.withTransaction
import app.yomu.core.database.CatalogCollectionCrossRef
import app.yomu.core.database.CatalogItemEntity
import app.yomu.core.database.ChapterEntity
import app.yomu.core.database.CollectionEntity
import app.yomu.core.database.TrackEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.domain.tracking.MihonTracking
import app.yomu.domain.tracking.TrackService
import app.yomu.source.SourceContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.InputStream
import java.util.zip.GZIPInputStream

/*
 * The parts of a Mihon/Tachiyomi backup (`.tachibk`, `.proto.gz`: gzip of protobuf) that Yomu reads. Field numbers are those of
 * Mihon's backup models; everything else in the file (preferences, forks' own fields) is skipped.
 */

@Serializable
class MihonBackup(
    @ProtoNumber(1) val backupManga: List<MihonManga> = emptyList(),
    @ProtoNumber(2) val backupCategories: List<MihonCategory> = emptyList(),
    @ProtoNumber(101) val backupSources: List<MihonSource> = emptyList(),
    @ProtoNumber(106) val backupExtensionRepo: List<MihonRepo> = emptyList(),
)

@Serializable
class MihonManga(
    @ProtoNumber(1) val source: Long = 0,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val title: String = "",
    @ProtoNumber(4) val artist: String? = null,
    @ProtoNumber(5) val author: String? = null,
    @ProtoNumber(6) val description: String? = null,
    @ProtoNumber(7) val genre: List<String> = emptyList(),
    @ProtoNumber(8) val status: Int = 0,
    @ProtoNumber(9) val thumbnailUrl: String? = null,
    @ProtoNumber(13) val dateAdded: Long = 0,
    @ProtoNumber(16) val chapters: List<MihonChapter> = emptyList(),
    /** Orders ([MihonCategory.order]) of the categories the title is in. */
    @ProtoNumber(17) val categories: List<Long> = emptyList(),
    @ProtoNumber(18) val tracking: List<MihonTrack> = emptyList(),
    @ProtoNumber(100) val favorite: Boolean = true,
    @ProtoNumber(102) val brokenHistory: List<MihonHistory> = emptyList(),
    @ProtoNumber(104) val history: List<MihonHistory> = emptyList(),
)

@Serializable
class MihonChapter(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val scanlator: String? = null,
    @ProtoNumber(4) val read: Boolean = false,
    @ProtoNumber(5) val bookmark: Boolean = false,
    @ProtoNumber(6) val lastPageRead: Long = 0,
    @ProtoNumber(7) val dateFetch: Long = 0,
    @ProtoNumber(8) val dateUpload: Long = 0,
    @ProtoNumber(9) val chapterNumber: Float = 0f,
    @ProtoNumber(10) val sourceOrder: Long = 0,
)

@Serializable
class MihonCategory(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val order: Long = 0,
)

@Serializable
class MihonHistory(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val lastRead: Long = 0,
)

@Serializable
class MihonTrack(
    @ProtoNumber(1) val syncId: Int = 0,
    @ProtoNumber(2) val libraryId: Long = 0,
    @ProtoNumber(3) val mediaIdInt: Int = 0,
    @ProtoNumber(4) val trackingUrl: String = "",
    @ProtoNumber(5) val title: String = "",
    @ProtoNumber(6) val lastChapterRead: Float = 0f,
    @ProtoNumber(7) val totalChapters: Int = 0,
    @ProtoNumber(8) val score: Float = 0f,
    @ProtoNumber(9) val status: Int = 0,
    @ProtoNumber(10) val startedReadingDate: Long = 0,
    @ProtoNumber(11) val finishedReadingDate: Long = 0,
    @ProtoNumber(100) val mediaId: Long = 0,
)

@Serializable
class MihonSource(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val sourceId: Long = 0,
)

@Serializable
class MihonRepo(
    @ProtoNumber(1) val baseUrl: String = "",
    @ProtoNumber(2) val name: String = "",
)

/**
 * Imports the library of a Mihon/Tachiyomi backup (2.0.0): titles with their chapters, read marks, bookmarks, history,
 * categories (as library tabs) and trackers. Source ids are the same as Mihon's, so the titles open once the source's
 * extension is installed; titles of sources that are not installed are kept and listed in the result.
 * Nothing is removed and existing data wins where both have a value (read marks are merged).
 */
class MihonImporter(
    private val db: YomuDatabase,
    /** Name of an installed source, null if it is not installed. */
    private val installedSource: (Long) -> String? = { null },
    private val contentOf: (Long) -> SourceContent? = { null },
) {
    data class MissingSource(val id: Long, val name: String, val titles: Int)

    data class Result(
        val titles: Int,
        val newTitles: Int,
        val chapters: Int,
        val tabs: Int,
        val tracks: Int,
        val missingSources: List<MissingSource>,
        /** Extension stores listed in the backup (to add them). */
        val repos: List<String>,
    )

    suspend fun import(input: InputStream): Result = withContext(Dispatchers.IO) { import(parse(input.readBytes())) }

    suspend fun import(backup: MihonBackup): Result = db.withTransaction {
        val now = System.currentTimeMillis()
        val catalog = db.catalog()
        val collections = db.collections()
        val tracks = db.tracks()

        // Categories → tabs (an existing tab with the same name is reused).
        val existingTabs = collections.bookCollections().toMutableList()
        val tabByOrder = HashMap<Long, Long>()
        var newTabs = 0
        backup.backupCategories.sortedBy { it.order }.forEach { c ->
            val name = c.name.trim()
            if (name.isEmpty()) return@forEach
            val id = existingTabs.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
                ?: collections.insert(CollectionEntity(name = name, sortOrder = collections.maxSortOrder() + 1)).also { id ->
                    newTabs++
                    existingTabs += CollectionEntity(id = id, name = name)
                }
            tabByOrder[c.order] = id
        }

        val sourceNames = backup.backupSources.associate { it.sourceId to it.name }
        val missing = LinkedHashMap<Long, Int>()
        var titles = 0
        var newTitles = 0
        var chapters = 0
        var trackCount = 0
        backup.backupManga.forEach { m ->
            if (m.url.isBlank()) return@forEach
            if (installedSource(m.source) == null) missing[m.source] = (missing[m.source] ?: 0) + 1
            val type = when (contentOf(m.source)) {
                SourceContent.NOVEL -> "NOVEL"
                SourceContent.BOOK -> "BOOK"
                else -> "MANGA"
            }
            val genres = m.genre.map { it.replace(",", " ").trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
            val existing = catalog.find(m.source, m.url)
            val itemId = if (existing == null) {
                newTitles++
                catalog.insert(
                    CatalogItemEntity(
                        sourceId = m.source, url = m.url, title = m.title.ifBlank { m.url }, author = m.author?.ifBlank { null },
                        artist = m.artist?.ifBlank { null }, description = m.description?.ifBlank { null }, genres = genres, status = m.status,
                        coverUrl = m.thumbnailUrl?.ifBlank { null }, type = type, favorite = m.favorite, categoryId = null,
                        addedAt = m.dateAdded.takeIf { it > 0 } ?: now, lastUpdateAt = 0, initialized = false,
                    ),
                )
            } else {
                catalog.update(
                    existing.copy(
                        favorite = existing.favorite || m.favorite,
                        addedAt = if (!existing.favorite && m.favorite) m.dateAdded.takeIf { it > 0 } ?: now else existing.addedAt,
                        author = existing.author ?: m.author?.ifBlank { null },
                        artist = existing.artist ?: m.artist?.ifBlank { null },
                        description = existing.description ?: m.description?.ifBlank { null },
                        genres = existing.genres.ifBlank { genres },
                        coverUrl = existing.coverUrl ?: m.thumbnailUrl?.ifBlank { null },
                    ),
                )
                existing.id
            }
            titles++

            // Chapters with read marks, bookmarks, pages and history.
            val history = HashMap<String, Long>()
            (m.brokenHistory + m.history).forEach { h -> if (h.lastRead > 0) history[h.url] = maxOf(history[h.url] ?: 0, h.lastRead) }
            val local = catalog.chapters(itemId).associateBy { it.url }
            val inserts = mutableListOf<ChapterEntity>()
            val updates = mutableListOf<ChapterEntity>()
            val seen = HashSet<String>()
            m.chapters.forEach { c ->
                if (c.url.isBlank() || !seen.add(c.url)) return@forEach
                val lastRead = history[c.url]
                val page = c.lastPageRead.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                val old = local[c.url]
                if (old == null) {
                    inserts += ChapterEntity(
                        itemId = itemId, url = c.url, name = c.name.ifBlank { c.url }, number = c.chapterNumber, scanlator = c.scanlator?.ifBlank { null },
                        uploadDate = c.dateUpload, read = c.read, bookmarked = c.bookmark, lastPageRead = page, downloaded = false, localPath = null,
                        isNew = false, sourceOrder = c.sourceOrder.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), fetchedAt = c.dateFetch.takeIf { it > 0 } ?: now,
                        lastReadAt = lastRead,
                    )
                } else if ((c.read && !old.read) || (c.bookmark && !old.bookmarked) || page > old.lastPageRead || (lastRead ?: 0) > (old.lastReadAt ?: 0)) {
                    updates += old.copy(
                        read = old.read || c.read,
                        bookmarked = old.bookmarked || c.bookmark,
                        lastPageRead = maxOf(old.lastPageRead, page),
                        lastReadAt = listOfNotNull(old.lastReadAt, lastRead).maxOrNull(),
                    )
                }
            }
            if (inserts.isNotEmpty()) catalog.insertChapters(inserts)
            if (updates.isNotEmpty()) catalog.updateChapters(updates)
            chapters += inserts.size + updates.size

            // Categories.
            if (m.favorite) {
                val tabIds = m.categories.mapNotNull { tabByOrder[it] }.distinct()
                if (tabIds.isNotEmpty()) collections.addItems(tabIds.map { CatalogCollectionCrossRef(itemId, it) })
            }

            // Trackers Yomu supports; a local entry with more progress stays.
            m.tracking.forEach { t ->
                val service = TrackService.of(t.syncId) ?: return@forEach
                val remoteId = t.mediaId.takeIf { it > 0 } ?: t.mediaIdInt.toLong()
                if (remoteId <= 0) return@forEach
                val old = tracks.get(itemId, service.id)
                if (old != null && old.lastChapterRead >= t.lastChapterRead) return@forEach
                val entity = TrackEntity(
                    id = old?.id ?: 0, itemId = itemId, service = service.id, remoteId = remoteId, libraryId = t.libraryId,
                    title = t.title.ifBlank { m.title }, url = t.trackingUrl, status = MihonTracking.status(t.syncId, t.status).code,
                    score = MihonTracking.score(t.syncId, t.score), lastChapterRead = t.lastChapterRead, totalChapters = t.totalChapters,
                    startedAt = t.startedReadingDate, finishedAt = t.finishedReadingDate, updatedAt = now,
                )
                if (old == null) tracks.insert(entity) else tracks.update(entity)
                trackCount++
            }
        }
        Result(
            titles = titles,
            newTitles = newTitles,
            chapters = chapters,
            tabs = newTabs,
            tracks = trackCount,
            missingSources = missing.map { (id, n) -> MissingSource(id, sourceNames[id]?.ifBlank { null } ?: "#$id", n) }.sortedByDescending { it.titles },
            repos = backup.backupExtensionRepo.map { it.baseUrl.trim() }.filter { it.startsWith("http") }.distinct(),
        )
    }

    companion object {
        /** Reads a `.tachibk`/`.proto.gz` file (gzip of protobuf; a bare protobuf works too). */
        fun parse(bytes: ByteArray): MihonBackup {
            val raw = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            } else {
                bytes
            }
            val backup = try {
                ProtoBuf.decodeFromByteArray(MihonBackup.serializer(), raw)
            } catch (e: Exception) {
                // Any other file: the protobuf reader fails on it one way or another.
                throw IllegalArgumentException("Not a Mihon/Tachiyomi backup", e)
            }
            require(backup.backupManga.isNotEmpty() || backup.backupCategories.isNotEmpty()) { "The backup has no library" }
            return backup
        }

        /** Writes a backup in the same format (tests; a Mihon backup of Yomu's online library could build on it). */
        fun encode(backup: MihonBackup): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(out).use { it.write(ProtoBuf.encodeToByteArray(MihonBackup.serializer(), backup)) }
            return out.toByteArray()
        }
    }
}
