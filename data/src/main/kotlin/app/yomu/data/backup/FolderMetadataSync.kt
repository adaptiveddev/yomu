package app.yomu.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.yomu.core.database.YomuDatabase
import java.io.IOException

/**
 * Atomic metadata write with page revisions embedded alongside the metadata.
 * Existing legacy path-based folder identities still require safe migration.
 */
internal suspend fun mergeFolderMetadataAndWrite(
    context: Context,
    db: YomuDatabase,
    backend: SyncBackend,
    previous: SyncFile?,
    remote: FolderMetadataSnapshot?,
    base: SyncBase?,
    now: Long,
    options: SyncMerger.Options,
    destinations: List<FolderEntry>,
    pendingEdits: Set<String> = emptySet(),
    legacyRemote: SyncState? = null,
): SyncMerger.Outcome = db.withTransaction {
    if (remote != null && legacyRemote != null) throw IOException("Ambiguous sync metadata source")
    if (legacyRemote != null && FolderMetadataSnapshotCodec.folderHashes(legacyRemote).isNotEmpty())
        throw IOException("Folder metadata has no page revision")
    // An immutable revision in this same write identifies the page numbering in all metadata.
    // The separate folder index may advance before or after this write; future reads translate
    // from the explicitly recorded revision rather than guessing from that index.
    try { FolderManifestCodec.validate(FolderManifest(folders = destinations)) }
    catch (e: Exception) { throw IOException("Invalid destination folder revisions", e) }
    val byId = destinations.associateBy { it.id }
    val local = db.books().all().filter { it.format == "FOLDER" && it.fileHash?.let(FolderManifestCodec::validId) == true }
    for (book in local) {
        val id = book.fileHash!!
        val expected = byId[id] ?: throw IOException("Folder revision missing for ${book.title}")
        val revision = FolderRevisionCommit.localRevision(db, id) ?: throw IOException("Folder position revision missing for ${book.title}")
        if (revision.pages != expected.pages) throw IOException("Folder positions refer to a different revision: ${book.title}")
        if (id in pendingEdits) continue // edited locally; the publication decision is pending
        val observed = FolderSnapshot.snapshot(context, Uri.parse(book.uri), id, book.title)
        if (observed.pages != revision.pages) throw IOException("Folder changed during sync: ${book.title}")
    }
    val translated = remote?.let { FolderMetadataSnapshotCodec.translate(it, destinations).state } ?: legacyRemote
    val outcome = SyncMerger(db).merge(translated, base, backend.id, now, options)
    val ids = FolderMetadataSnapshotCodec.folderHashes(outcome.state)
    val qualified = ids.sorted().map { id -> byId[id] ?: throw IOException("Folder metadata has no known revision: $id") }
    val snapshot = FolderMetadataSnapshot(state = outcome.state, revisions = qualified)
    backend.write(FolderMetadataSnapshotCodec.encode(snapshot), previous)
    outcome
}
