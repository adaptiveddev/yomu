package app.yomu.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Cross-device propagation of book removals, guarded by [BookDeletionGuard]. A device that had a
 * book at the last successful sync and no longer has it publishes a deletion marker (only when
 * the shared state is unchanged since its baseline). A receiving device applies the removal only
 * when the marker is fresh, nothing newer replaced the book remotely, and its own copy is
 * unchanged (shared baseline match plus the private fingerprint re-checked in the applying
 * transaction). Anything else is a recorded conflict that keeps every byte.
 *
 * The runtime receive check adapts the guard: after an applied deletion the remote holds no file
 * entry, so "current remote" is verified as the absence of any newer trace (entry, records,
 * bindings) instead of a full fingerprint. Folders are not covered: their removals stay local.
 */
internal class BookDeletionSync(
    private val context: Context,
    private val db: YomuDatabase,
    private val settings: AppSettings,
    private val backend: SyncBackend,
    private val syncId: String,
    private val recordProblem: (SyncProblemInfo) -> Unit = {},
) {
    private val json = Json { encodeDefaults = true }
    private val files get() = backend as? BookFilesTransport ?: throw IOException("The sync target does not transfer book files")

    private fun tag(kind: BookFileSyncProblem.Kind) = BookFileSyncProblem(kind, "", "", -1).kindTag

    private fun problem(kind: BookFileSyncProblem.Kind, subject: String, detail: String, target: String): Nothing {
        recordProblem(SyncProblemInfo(
            id = tag(kind) + ":" + target, kind = tag(kind), subject = subject.take(100),
            detail = detail, at = System.currentTimeMillis(), target = target,
        ))
        throw IOException(detail)
    }

    /** Applies other devices' deletion markers. Call before the metadata merge. Returns applied count. */
    suspend fun receive(): Int {
        val index = BookFileSync.decodeIndex(files.readBookIndex()?.bytes)
        if (index.deleted.isEmpty()) return 0
        val horizon = System.currentTimeMillis() - SyncMerger.TOMBSTONE_DAYS * 24L * 60 * 60 * 1000
        val links = readLinks()
        val base = BookFileSync.readBase(context, syncId)
        var applied = 0
        for ((id, marker) in index.deleted) {
            try {
                if (marker.at < horizon) continue // the deletion expired; the book must not be removed
                val local = db.books().all().firstOrNull { it.fileHash == id } ?: continue
                // Nothing newer may replace the book remotely after the marker.
                // A re-appeared file entry or an identity replacement means the book was
                // re-published after the marker. Records other devices still contribute are
                // expected while they keep their copies; they are not supersession.
                val superseded = index.books.any { it.bookHash == id } || index.replaced.containsValue(id)
                if (superseded) continue
                val title = local.title
                val baseline = base?.baselines?.get(id)
                if (!settings.syncDeletions.get()) continue // deletions are off here: keep the book silently
                if (baseline == null || baseline.shared != marker.expected) {
                    problem(BookFileSyncProblem.Kind.DELETION_CONFLICT, title,
                        "The deletion of \"$title\" cannot be verified against this device's last sync; the book was kept", local.id.toString())
                }
                applied += db.withTransaction {
                    // Re-check the private fingerprints in the transaction that applies the removal.
                    val row = db.books().get(local.id)
                    if (row == null || row.fileHash != id) return@withTransaction 0
                    val private = LocalBookRevision.capture(db, row.id)
                    val pending = db.tracks().all().any { it.bookId == row.id && it.pending }
                    if (private == null || private != baseline!!.privateState || pending) {
                        problem(BookFileSyncProblem.Kind.DELETION_CONFLICT, title,
                            "\"$title\" changed here since the last sync; the deletion from another device was not applied", row.id.toString())
                    }
                    deleteBookRow(row)
                    1
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                // Recorded: one refused deletion must not block the other markers.
            }
        }
        return applied
    }

    /**
     * Publishes this device's book removals as deletion markers. Call before the metadata merge
     * with the state as it was read from the shared document: the deletion's own tombstones must
     * not count as a competing change. Returns the number of published markers.
     */
    suspend fun publish(agreedState: SyncState?): Int {
        val indexFile = files.readBookIndex()
        val index = BookFileSync.decodeIndex(indexFile?.bytes)
        val base = BookFileSync.readBase(context, syncId)
        val links = readLinks()
        val entries = index.books.associateBy { it.bookHash }
        val deletions = LinkedHashMap<String, DeletedBook>(index.deleted)
        var published = 0
        if (settings.syncDeletions.get()) {
            // Books this device removed locally (suppressed): the entry still exists remotely
            // until the marker is published. `present` only mirrors the current local library.
            for (id in base?.suppressed.orEmpty()) {
                if (deletions.containsKey(id)) continue
                val baseline = base?.baselines?.get(id) ?: continue // never synced closely enough
                val current = agreedState?.let { state ->
                    runCatching { SharedBookRevision.capture(id, state, links, file = entries[id]) }.getOrNull()
                }
                val decision = BookDeletionGuard.propose(enabled = true, locallyPresent = false, base = baseline.shared, remote = current)
                if (decision != BookDeletionGuard.Decision.PUBLISH) println("DIAG decision=$decision book=${agreedState?.books?.firstOrNull { it.hash == id }} hl=${agreedState?.highlights?.filter { it.hash == id }} del=${agreedState?.deleted?.filterKeys { SyncKeys.bookOf(it) == id }}")
                when (decision) {
                    BookDeletionGuard.Decision.PUBLISH -> {
                        deletions[id] = DeletedBook(expected = baseline.shared, at = System.currentTimeMillis())
                        published++
                    }
                    else -> problem(BookFileSyncProblem.Kind.DELETION_CONFLICT, id,
                        "The removal of a book cannot be published safely (the shared copy changed elsewhere); it stays removed only on this device", id)
                }
            }
        }
        if (published > 0) {
            val remaining = index.books.filter { it.bookHash !in deletions }
            val encoded = json.encodeToString(
                BookFileIndex.serializer(),
                BookFileIndex(version = 2, books = remaining, replaced = index.replaced, deleted = deletions),
            ).toByteArray()
            files.writeBookIndex(encoded, indexFile)
        }
        return published
    }

    /** Records what this device agreed on per book: shared fingerprints and the private snapshot. Call after the merge. */
    suspend fun captureBaselines(state: SyncState) {
        val index = BookFileSync.decodeIndex(files.readBookIndex()?.bytes)
        val base = BookFileSync.readBase(context, syncId)
        val links = readLinks()
        val entries = index.books.associateBy { it.bookHash }
        val baselines = LinkedHashMap<String, BookBaseline>(base?.baselines.orEmpty())
        val local = db.books().all().filter { it.format != "FOLDER" && it.fileHash != null && !isAudioCollection(it.fileHash) }
        val known = entries.keys + baselines.keys
        for (book in local) {
            val id = book.fileHash!!
            if (id !in known) continue // not part of this target's file sync
            val shared = runCatching { SharedBookRevision.capture(id, state, links, file = entries[id]) }.getOrNull()
                ?: continue // the book has no shared record yet; no baseline this round
            val private = LocalBookRevision.capture(db, book.id) ?: continue
            baselines[id] = BookBaseline(shared = shared, privateState = private)
        }
        for (id in baselines.keys.toList()) {
            if (id !in entries.keys && local.none { it.fileHash == id }) baselines.remove(id)
        }
        if (base != null && baselines == base.baselines) return
        BookFileSync.saveBase(context, BookFileBase(
            syncId = syncId,
            present = base?.present.orEmpty(),
            suppressed = base?.suppressed.orEmpty(),
            indexKnown = base?.indexKnown ?: false,
            baselines = baselines,
        ))
    }

    private suspend fun readLinks(): List<TrackLink> =
        (backend as? TrackLinkTransport)?.readTrackLinks()?.bytes
            ?.let { runCatching { json.decodeFromString(TrackLinksFile.serializer(), SyncJson.text(it)) }.getOrNull() }?.links
            ?: emptyList()

    /** Deletes the row with its records; the linked file only when it is the library's own copy. */
    private suspend fun deleteBookRow(row: app.yomu.core.database.BookEntity) {
        val uri = Uri.parse(row.uri)
        if (uri.scheme == "file") runCatching { File(uri.path!!).delete() }
        withContext(NonCancellable) {
            val ann = db.annotations()
            for (note in ann.allNotes().filter { it.bookId == row.id }) ann.deleteNote(note.id)
            for (highlight in ann.allHighlights().filter { it.bookId == row.id }) {
                ann.deleteNotesOfHighlight(highlight.id)
                ann.deleteHighlight(highlight.id)
            }
            for (mark in ann.allBookmarks().filter { it.bookId == row.id }) ann.deleteBookmark(mark.id)
            db.words().delete(db.words().all().filter { it.bookId == row.id }.map { it.id })
            db.progress().deleteForBook(row.id)
            db.books().delete(listOf(row.id))
        }
    }
}
