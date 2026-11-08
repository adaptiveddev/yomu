package app.yomu.data.backup

import android.content.Context
import android.net.Uri
import app.yomu.core.database.FolderSyncBaseEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/** Syncs folders with stable identities; legacy path-based identities still require safe migration. */
internal class FolderSyncCoordinator(
    private val context: Context,
    private val db: YomuDatabase,
    private val importer: ImportService,
    private val recordProblem: (SyncProblemInfo) -> Unit = {},
) {
    private val indexMarker = "__index__"

    /** Legacy folders have no portable ID or trustworthy page-position baseline. */
    suspend fun requirePortableFolderIdentities() {
        val legacy = db.books().all().firstOrNull {
            it.format == "FOLDER" && it.fileHash?.let(FolderManifestCodec::validId) != true
        }
        if (legacy != null) throw FolderSyncProblem(FolderSyncProblem.Kind.LEGACY, legacy.title,
            "Image folder has no portable identity: ${legacy.title}. Migrate it from the sync conflicts screen to enable file sync.",
            legacy.id.toString())
    }

    /** Re-frames a position problem under the folder's name and book id for the conflicts screen. */
    private fun problemOf(kind: FolderSyncProblem.Kind, title: String, bookId: Long, detail: String): Nothing =
        throw FolderSyncProblem(kind, title, detail, bookId.toString())

    suspend fun sync(transport: FolderFilesTransport, target: String, remoteMetadata: FolderMetadataSnapshot? = null): FolderSyncResult {
        requirePortableFolderIdentities()
        val previous = transport.readFolderIndex()
        val baseRows = db.folderSyncBases().forTarget(target)
        if (previous == null && baseRows.any { it.folderId == indexMarker }) throw IOException("Folder index is missing; restore it before syncing")
        val remote = (previous?.let { FolderManifestCodec.decode(it.bytes) } ?: FolderManifest()).folders.associateByTo(LinkedHashMap()) { it.id }
        val bases = baseRows.filter { it.folderId != indexMarker }.associate { row ->
            val item = FolderManifestCodec.decode(row.revisionJson.toByteArray()).folders.singleOrNull() ?: throw IOException("Invalid folder revision base")
            if (item.id != row.folderId) throw IOException("Invalid folder revision identity")
            item.id to item
        }
        val allFolders = db.books().all().filter { it.format == "FOLDER" }
        val books = allFolders.filter { it.fileHash?.let(FolderManifestCodec::validId) == true }
        if (books.map { it.fileHash }.toSet().size != books.size) throw IOException("Duplicate folder identity")
        val local = LinkedHashMap<String, Pair<app.yomu.core.database.BookEntity, FolderEntry>>()
        for (book in books) {
            val id = book.fileHash!!
            local[id] = book to FolderSnapshot.snapshot(context, Uri.parse(book.uri), id, book.title)
        }
        // An independently imported folder may have another UUID.
        // Do not silently import a second copy or merge the identities based on content alone:
        // identical image sequences can be intentional separate library entries.
        val remoteNew = remote.values.filter { it.id !in local }
        if (remoteNew.isNotEmpty()) {
            val localEntries = local.values.map { it.second }
            fun comparableTitle(title: String): String = java.text.Normalizer.normalize(title.trim(), java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
            val localTitles = localEntries.map { comparableTitle(it.title) }.toSet()
            val localPages = localEntries.flatMap { entry -> entry.pages.map { it.sha256 to it.size } }.toHashSet()
            for (entry in remoteNew) {
                val similar = comparableTitle(entry.title) in localTitles || entry.pages.any { (it.sha256 to it.size) in localPages }
                if (similar) throw FolderSyncProblem(FolderSyncProblem.Kind.SIMILAR, entry.title, "A similar image folder already exists with a different identity: ${entry.title}; keep both copies until the conflict can be resolved")
            }
        }
        // Decide every item before any remote index write or local import.
        for ((id, pair) in local) {
            val choice = FolderMerge.decide(bases[id], pair.second, remote[id])
            if (choice == FolderMerge.Decision.CONFLICT) throw FolderSyncProblem(FolderSyncProblem.Kind.DIVERGED, pair.first.title, "Conflicting folder changes: ${pair.first.title}")
        }
        for ((id, base) in bases) {
            if (id !in local && FolderMerge.decide(base, null, remote[id]) == FolderMerge.Decision.CONFLICT)
                throw IOException("A removed local folder also changed remotely: ${base.title}")
        }
        // A local base records what every saved page number meant. Never reconstruct it
        // from today's files: the folder may have changed since those positions were saved.
        // Ambiguous folders do not stop the others: their file part is skipped, the problem is
        // recorded and the conflicts screen offers publishing or accepting a version explicitly.
        val pending = LinkedHashMap<String, FolderSyncProblem>()
        for ((id, pair) in local) {
            val localBase = FolderRevisionCommit.localRevision(db, id)
            if (localBase == null) {
                pending[id] = FolderSyncProblem(FolderSyncProblem.Kind.MISSING_REVISION, pair.first.title,
                    "Local folder position revision is missing; restore a backup: ${pair.first.title}", pair.first.id.toString())
                continue
            }
            val desired = if (FolderMerge.decide(bases[id], pair.second, remote[id]) == FolderMerge.Decision.UPLOAD)
                pair.second else remote[id] ?: pair.second
            try {
                FolderPositions.preflight(db, pair.first.id, localBase, desired)
            } catch (e: FolderSyncProblem) {
                pending[id] = FolderSyncProblem(e.kind, pair.first.title, "${pair.first.title}: ${e.message}", pair.first.id.toString())
            }
        }
        for (problem in pending.values) recordProblem(problem.record(System.currentTimeMillis()))
        // Also protect positions that exist only on another device. The metadata snapshot
        // carries its own page revisions; translate against the planned index before writing.
        // This is a preflight, not an atomic cross-document commit: concurrent writes can
        // still require a retry/conflict in the later conditional metadata write.
        if (remoteMetadata != null) {
            val planned = remote.toMutableMap()
            for ((id, pair) in local) {
                // A pending folder keeps its published revision: its positions are translated
                // against the old pages here, and against the new ones at the explicit commit.
                if (id in pending) continue
                if (FolderMerge.decide(bases[id], pair.second, remote[id]) == FolderMerge.Decision.UPLOAD)
                    planned[id] = pair.second
            }
            val destinations = remoteMetadata.revisions.map { planned[it.id] ?: it }
            FolderMetadataSnapshotCodec.translate(remoteMetadata, destinations)
        }
        var dirty = false
        for ((id, pair) in local) {
            if (id in pending) continue // the publication decision is on the conflicts screen
            if (FolderMerge.decide(bases[id], pair.second, remote[id]) == FolderMerge.Decision.UPLOAD) {
                FolderFileTransfer.upload(context, Uri.parse(pair.first.uri), pair.second, transport)
                remote[id] = pair.second
                dirty = true
            }
        }
        if (dirty) transport.writeFolderIndex(FolderManifestCodec.encode(FolderManifest(folders = remote.values.toList())), previous)
        var changed = 0
        // A downloaded version is installed only after validation. Old copies are deliberately retained.
        for ((id, pair) in local) {
            if (id in pending) continue // the publication decision is on the conflicts screen
            val book = pair.first; val observed = pair.second; val beforeTarget = bases[id]
            val destination = remote[id] ?: throw IOException("Folder disappeared from index")
            val localBase = FolderRevisionCommit.localRevision(db, id)
            if (localBase == null) throw FolderSyncProblem(FolderSyncProblem.Kind.MISSING_REVISION, book.title, "Local folder position revision is missing; restore a backup: ${book.title}")
            if (beforeTarget == null) FolderRevisionCommit.initialize(db, target, book.id, localBase)
            val expectedTarget = beforeTarget ?: localBase
            val expectedLocal = localBase
            if (destination.pages != observed.pages) {
                val stage = FolderFileTransfer.download(context, destination, transport)
                val uri = Uri.fromFile(stage).toString()
                var keep = false
                try {
                    if (FolderSnapshot.snapshot(context, Uri.parse(book.uri), id, book.title).pages != observed.pages)
                        throw IOException("Local folder changed during download: ${book.title}")
                    FolderRevisionCommit.apply(db, target, book.id, expectedLocal, destination, uri, expectedTarget)
                    keep = true; changed++
                } finally {
                    if (!keep) {
                        val referenced = withContext(NonCancellable) { try { db.books().byUri(uri) != null } catch (_: Exception) { true } }
                        if (!referenced) stage.deleteRecursively()
                    }
                }
            } else {
                // Even an equal remote copy can require a local position migration after an edit or retry.
                FolderRevisionCommit.apply(db, target, book.id, expectedLocal, observed, book.uri, expectedTarget, book.isLinked)
            }
        }
        for ((id, entry) in remote) {
            if (id in local || id in bases) continue // a known local removal is not an import
            val stage = FolderFileTransfer.download(context, entry, transport)
            val uri = Uri.fromFile(stage).toString()
            var keep = false
            try {
                when (val result = importer.importSyncedFolder(stage, id, entry.title)) {
                    is ImportOutcome.Added -> { keep = true; FolderRevisionCommit.initialize(db, target, result.bookId, entry); changed++ }
                    is ImportOutcome.Duplicate -> throw IOException("Folder identity already exists: ${entry.title}")
                    is ImportOutcome.Failed -> throw IOException("Cannot import folder: ${entry.title}", result.error)
                }
            } finally {
                if (!keep) {
                    val referenced = withContext(NonCancellable) { try { db.books().byUri(uri) != null } catch (_: Exception) { true } }
                    if (!referenced) stage.deleteRecursively()
                }
            }
        }
        if (previous != null || dirty) db.folderSyncBases().upsert(FolderSyncBaseEntity(target, indexMarker, "known"))
        return FolderSyncResult(changed, pending.keys)
    }

    /** Changed folders whose position references are ambiguous: their file part was skipped. */
    internal data class FolderSyncResult(val changed: Int, val pendingEdits: Set<String>)

    /**
     * Conflict screen's "publish my version": uploads the local pages, publishes the manifest and
     * commits the new revision, remapping positions leniently — unambiguous references follow,
     * the reading position on a changed/removed page restarts the book, annotations keep their
     * locators and are reported. Returns the reported page paths.
     */
    suspend fun publishLocalVersion(bookId: Long, transport: FolderFilesTransport, target: String): List<String> {
        val book = db.books().get(bookId) ?: throw IOException("The folder is no longer in the library")
        val id = book.fileHash ?: throw IOException("The folder has no identity")
        if (!FolderManifestCodec.validId(id)) throw IOException("Migrate the folder to a stable identity first: ${book.title}")
        val base = FolderRevisionCommit.localRevision(db, id)
            ?: throw FolderSyncProblem(FolderSyncProblem.Kind.MISSING_REVISION, book.title, "Local folder position revision is missing; restore a backup: ${book.title}", bookId.toString())
        val previous = transport.readFolderIndex()
        val manifest = previous?.let { FolderManifestCodec.decode(it.bytes) } ?: FolderManifest()
        val observed = FolderSnapshot.snapshot(context, Uri.parse(book.uri), id, book.title)
        if (observed.pages == base.pages) throw IOException("The folder has no local page changes to publish: ${book.title}")
        val targetRow = db.folderSyncBases().forTarget(target).firstOrNull { it.folderId == id }
        val targetBase = targetRow?.let { FolderManifestCodec.decode(it.revisionJson.toByteArray()).folders.singleOrNull() }
        if (targetRow == null) FolderRevisionCommit.initialize(db, target, bookId, base)
        FolderFileTransfer.upload(context, Uri.parse(book.uri), observed, transport)
        val entries = manifest.folders.filter { it.id != id } + observed
        transport.writeFolderIndex(FolderManifestCodec.encode(FolderManifest(folders = entries)), previous)
        return FolderRevisionCommit.applyLenient(db, target, bookId, base, observed, book.uri, targetBase ?: base, book.isLinked)
    }

    /**
     * Conflict screen's "accept the other version": installs the remote revision of one folder,
     * remapping positions leniently. A locally edited folder refuses here — publish your version
     * instead. Returns the reported page paths.
     */
    suspend fun acceptRemoteVersion(bookId: Long, transport: FolderFilesTransport, target: String): List<String> {
        val book = db.books().get(bookId) ?: throw IOException("The folder is no longer in the library")
        val id = book.fileHash ?: throw IOException("The folder has no identity")
        if (!FolderManifestCodec.validId(id)) throw IOException("Migrate the folder to a stable identity first: ${book.title}")
        val base = FolderRevisionCommit.localRevision(db, id)
            ?: throw FolderSyncProblem(FolderSyncProblem.Kind.MISSING_REVISION, book.title, "Local folder position revision is missing; restore a backup: ${book.title}", bookId.toString())
        val manifest = transport.readFolderIndex()?.let { FolderManifestCodec.decode(it.bytes) } ?: FolderManifest()
        val destination = manifest.folders.firstOrNull { it.id == id }
            ?: throw IOException("The folder is missing from the folder index: ${book.title}")
        if (destination.pages == base.pages) throw IOException("The folder has no other version to accept: ${book.title}")
        val observed = FolderSnapshot.snapshot(context, Uri.parse(book.uri), id, book.title)
        if (observed.pages != base.pages) throw IOException("The folder changed locally; publish your version instead: ${book.title}")
        val targetRow = db.folderSyncBases().forTarget(target).firstOrNull { it.folderId == id }
        val targetBase = targetRow?.let { FolderManifestCodec.decode(it.revisionJson.toByteArray()).folders.singleOrNull() }
        if (targetRow == null) FolderRevisionCommit.initialize(db, target, bookId, base)
        val stage = FolderFileTransfer.download(context, destination, transport)
        val uri = Uri.fromFile(stage).toString()
        try {
            val report = FolderRevisionCommit.applyLenient(db, target, bookId, base, destination, uri, targetBase ?: base, book.isLinked)
            return report
        } finally {
            val referenced = withContext(NonCancellable) { try { db.books().byUri(uri) != null } catch (_: Exception) { true } }
            if (!referenced) stage.deleteRecursively()
        }
    }
}
