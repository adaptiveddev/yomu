package app.yomu.data.backup

import android.content.Context
import androidx.room.withTransaction
import app.yomu.core.database.TrackEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import app.yomu.data.tracking.TrackerManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Separate from yomu-sync.json so old versions cannot silently erase tracker bindings. No credentials are included. */
interface TrackLinkTransport {
    suspend fun readTrackLinks(): SyncFile?
    suspend fun writeTrackLinks(bytes: ByteArray, previous: SyncFile?)
}

@Serializable
internal data class LinkOwner(val bookHash: String? = null, val sourceId: Long? = null, val titleUrl: String? = null) {
    fun valid(): Boolean = if (bookHash != null) bookHash.isNotBlank() && bookHash.length <= 512 && sourceId == null && titleUrl == null
        else sourceId != null && titleUrl != null && titleUrl.isNotBlank() && titleUrl.length <= 8192
    fun key(): String = if (bookHash != null) "b:${bookHash.length}:$bookHash" else "c:$sourceId:${titleUrl!!.length}:$titleUrl"
}

@Serializable
internal data class TrackLink(
    val owner: LinkOwner, val service: Int, val remoteId: Long, val title: String, val url: String, val totalChapters: Int = 0,
) {
    fun key(): String = owner.key() + ":s:$service"
    fun valid(): Boolean = owner.valid() && service > 0 && remoteId > 0 && title.length <= 4096 && url.length <= 8192 && totalChapters >= 0
}

@Serializable internal data class TrackLinksFile(val version: Int = 1, val links: List<TrackLink> = emptyList())
@Serializable internal data class TrackLinksBase(val version: Int = 1, val syncId: String, val links: List<TrackLink> = emptyList(), val indexKnown: Boolean = false)

/** Three-way merge on remote IDs. Different simultaneous replacements are never silently overwritten. */
internal object TrackLinkMerge {
    enum class Decision { LOCAL, REMOTE, SAME, KEEP_DIVERGED, CONFLICT }
    fun decide(baseKnown: Boolean, base: Long?, local: Long?, remote: Long?, deletions: Boolean): Decision {
        if (local == remote) return Decision.SAME
        if (baseKnown && !deletions && (local == null || remote == null)) return Decision.KEEP_DIVERGED
        if (!baseKnown) return when {
            local == null -> Decision.REMOTE
            remote == null -> Decision.LOCAL
            else -> Decision.CONFLICT
        }
        return when {
            local == base -> Decision.REMOTE
            remote == base -> Decision.LOCAL
            else -> Decision.CONFLICT
        }
    }
}

internal class TrackLinkSync(private val context: Context, private val db: YomuDatabase) {
    private val json = Json { encodeDefaults = true }
    private val maxBytes = 4 * 1024 * 1024

    private fun decode(bytes: ByteArray): TrackLinksFile {
        if (bytes.size > maxBytes) throw IOException("Tracking sync file is too large")
        val data = try { json.decodeFromString(TrackLinksFile.serializer(), SyncJson.text(bytes)) }
            catch (e: Exception) { throw IOException("Invalid tracking sync file", e) }
        if (data.version != 1 || data.links.any { !it.valid() } || data.links.map { it.key() }.toSet().size != data.links.size)
            throw IOException("Unsupported or invalid tracking sync file")
        return data
    }

    private fun baseFile(syncId: String): android.util.AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(syncId.toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "sync-tracking").apply { mkdirs() }
        return android.util.AtomicFile(File(dir, "$digest.json"))
    }

    private fun readBase(syncId: String): TrackLinksBase? {
        val f = baseFile(syncId)
        // AtomicFile may still have a backup after process death. Never silently forget
        // the merge base when a recovery file exists.
        val backup = File(f.baseFile.path + ".bak")
        val pending = File(f.baseFile.path + ".new")
        if (!f.baseFile.exists() && !backup.exists()) {
            if (pending.exists()) throw IOException("Incomplete tracking sync base")
            return null
        }
        val base = try {
            val bytes = f.openRead().use { readSyncBytes(it, maxBytes) }
            if (bytes.size > maxBytes) throw IOException("Tracking sync base is too large")
            json.decodeFromString(TrackLinksBase.serializer(), SyncJson.text(bytes))
        } catch (e: Exception) { throw IOException("Invalid tracking sync base", e) }
        if (base.version != 1 || base.syncId != syncId || base.links.any { !it.valid() } || base.links.map { it.key() }.toSet().size != base.links.size)
            throw IOException("Invalid tracking sync base")
        return base
    }

    private fun saveBase(syncId: String, links: Collection<TrackLink>, indexKnown: Boolean) {
        val bytes = json.encodeToString(TrackLinksBase.serializer(), TrackLinksBase(syncId = syncId, links = links.toList(), indexKnown = indexKnown)).toByteArray()
        if (bytes.size > maxBytes) throw IOException("Tracking sync base is too large")
        AtomicPublish.publish(baseFile(syncId).baseFile, bytes)
    }

    private data class OwnerRef(val owner: LinkOwner, val itemId: Long? = null, val bookId: Long? = null)
    private data class Local(val link: TrackLink, val track: TrackEntity)

    suspend fun sync(transport: TrackLinkTransport, syncId: String, catalog: Boolean, deletions: Boolean): Int {
        val file = transport.readTrackLinks()
        val remote = file?.let { decode(it.bytes).links.associateBy { link -> link.key() } }.orEmpty()
        val savedBase = readBase(syncId)
        val base = savedBase?.links.orEmpty().associateBy { it.key() }
        // A missing sidecar is not evidence that the user intentionally unlinked every
        // tracker. This may be a temporarily unavailable or accidentally deleted file.
        if (file == null && (savedBase?.indexKnown == true || base.isNotEmpty())) throw IOException("Tracking sync file is missing; no links were removed")
        val owners = LinkedHashMap<String, OwnerRef>()
        val ambiguous = mutableSetOf<String>()
        for (book in db.books().all()) book.fileHash?.let { hash ->
            val owner = LinkOwner(bookHash = hash)
            if (owner.valid() && owners.put(owner.key(), OwnerRef(owner, bookId = book.id)) != null) ambiguous += owner.key()
        }
        if (catalog) for (item in db.catalog().all()) {
            val owner = LinkOwner(sourceId = item.sourceId, titleUrl = item.url)
            if (owner.valid()) owners[owner.key()] = OwnerRef(owner, itemId = item.id)
        }
        val allBooks = db.books().all()
        val ambiguousIds = allBooks.filter { it.fileHash != null && LinkOwner(bookHash = it.fileHash).key() in ambiguous }.map { it.id }.toSet()
        val ownersByBook = owners.values.filter { it.bookId != null }.associateBy { it.bookId }
        val ownersByItem = owners.values.filter { it.itemId != null }.associateBy { it.itemId }
        val local = LinkedHashMap<String, Local>()
        for (track in db.tracks().all()) {
            if (track.bookId in ambiguousIds) throw IOException("Duplicate book identifier prevents tracking sync")
            val ref = if (track.bookId != null) ownersByBook[track.bookId] else ownersByItem[track.itemId]
            if (ref == null || TrackService.of(track.service) == null) continue
            val link = TrackLink(ref.owner, track.service, track.remoteId, track.title, track.url, track.totalChapters.coerceAtLeast(0))
            if (!link.valid() || local.put(link.key(), Local(link, track)) != null) throw IOException("Invalid or duplicate local tracking binding")
        }
        val merged = remote.toMutableMap()
        val nextBase = mutableMapOf<String, TrackLink>()
        val apply = mutableListOf<Triple<OwnerRef, Local?, TrackLink?>>()
        val keys = remote.keys + local.keys + base.keys
        for (key in keys) {
            val l = local[key]; val r = remote[key]; val b = base[key]
            val owner = (l?.link ?: r ?: b)?.owner ?: continue
            val ref = owners[owner.key()] ?: continue // a book not present here is not a deletion
            if (owner.key() in ambiguous) throw IOException("Duplicate book identifier prevents tracking sync")
            val service = (l?.link ?: r ?: b)!!.service
            if (TrackService.of(service) == null) continue // preserve bindings from newer versions
            val decision = TrackLinkMerge.decide(b != null, b?.remoteId, l?.link?.remoteId, r?.remoteId, deletions)
            when (decision) {
                TrackLinkMerge.Decision.CONFLICT -> throw IOException("Conflicting tracking bindings for ${l?.link?.title ?: r?.title ?: owner.key()}")
                TrackLinkMerge.Decision.LOCAL -> {
                    if (l == null) merged.remove(key) else merged[key] = l.link
                    l?.link?.let { nextBase[key] = it }
                }
                TrackLinkMerge.Decision.REMOTE -> {
                    if (l?.track?.pending == true) throw IOException("Tracking binding has unsent local changes: ${l.link.title}")
                    apply += Triple(ref, l, r)
                    r?.let { nextBase[key] = it }
                }
                TrackLinkMerge.Decision.SAME -> (l?.link ?: r)?.let { nextBase[key] = it }
                TrackLinkMerge.Decision.KEEP_DIVERGED -> b?.let { nextBase[key] = it }
            }
        }
        val result = TrackLinksFile(links = merged.values.sortedBy { it.key() })
        val bytes = json.encodeToString(TrackLinksFile.serializer(), result).toByteArray()
        if (bytes.size > maxBytes) throw IOException("Tracking sync file is too large")
        if (merged != remote) transport.writeTrackLinks(bytes, file)
        db.withTransaction {
            for ((ref, snapshot, newLink) in apply) {
                val service = (snapshot?.link ?: newLink)!!.service
                val current = if (ref.bookId != null) db.tracks().getBook(ref.bookId, service) else db.tracks().get(ref.itemId!!, service)
                if (current != snapshot?.track) throw SyncConflictException()
                if (current?.pending == true) throw IOException("Tracking binding has unsent local changes: ${current.title}")
                if (current != null) db.tracks().deleteById(current.id)
                if (newLink != null) db.tracks().insert(TrackEntity(
                    itemId = ref.itemId, bookId = ref.bookId, service = newLink.service, remoteId = newLink.remoteId,
                    libraryId = TrackerManager.NEEDS_REMOTE_REFRESH, title = newLink.title, url = newLink.url, status = TrackStatus.PLAN_TO_READ.code,
                    totalChapters = newLink.totalChapters, updatedAt = System.currentTimeMillis(), pending = false,
                ))
            }
        }
        saveBase(syncId, nextBase.values, indexKnown = file != null || merged != remote)
        return apply.size
    }
}
