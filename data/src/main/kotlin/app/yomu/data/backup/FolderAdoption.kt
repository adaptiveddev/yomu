package app.yomu.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.yomu.core.database.YomuDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Deliberate migration of a legacy (`dir:` path-based) image folder onto a stable identity, and
 * following another device's migration marker. The migration rewrites every place the identity
 * appears — the shared metadata (v3, including the page-revision entry), the folder index (with a
 * replacement marker), the merge bases, the local position baseline rows and the book row — while
 * pages, progress, annotations and tracker bindings stay untouched. A durable journal makes the
 * multi-document run resumable; a device that still holds the legacy identity follows the marker
 * automatically instead of creating a second copy of the same folder.
 */
internal class FolderAdoption(
    private val context: Context,
    private val db: YomuDatabase,
    private val backend: SyncBackend,
    private val syncId: String,
    private val recordProblem: (SyncProblemInfo) -> Unit = {},
) {
    private val json = Json { encodeDefaults = true }

    @Serializable
    internal data class Journal(val old: String, val new: String, val at: Long)

    private val folders: FolderFilesTransport get() = backend as? FolderFilesTransport
        ?: throw IOException("The sync target does not transfer image folders")

    private fun problem(p: FolderSyncProblem): Nothing {
        recordProblem(p.record(System.currentTimeMillis()))
        throw p
    }

    /** The interrupted migration, if any; a pending journal blocks ordinary syncs until resumed. */
    suspend fun loadJournal(): Journal? = try {
        val file = journalFile(context)
        if (!file.exists()) null
        else json.decodeFromString(Journal.serializer(), SyncJson.text(file.readBytes()))
    } catch (_: Exception) { null }

    private suspend fun writeJournal(journal: Journal) {
        val file = journalFile(context)
        val temp = File.createTempFile("folder-migration-", ".new", file.parentFile)
        try {
            temp.writeBytes(json.encodeToString(Journal.serializer(), journal).toByteArray())
            java.nio.file.Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temp.delete()
            throw IOException("Cannot write the folder migration journal", e)
        }
    }

    private suspend fun clearJournal() {
        withContext(NonCancellable) { journalFile(context).delete() }
    }

    /**
     * Migrates one legacy folder to a fresh stable identity. The folder's content must not change
     * during the run; page numbering and every saved position survive as they are.
     */
    suspend fun migrate(bookId: Long) {
        val book = db.books().get(bookId) ?: throw IOException("The folder is no longer in the library")
        if (book.format != "FOLDER") throw IOException("The book is not an image folder")
        val old = book.fileHash ?: throw IOException("The folder has no identity to migrate")
        if (FolderManifestCodec.validId(old)) return // Already stable; nothing to migrate.
        val pending = loadJournal()
        val new = when {
            pending != null && pending.old == old -> pending.new
            pending != null -> throw IOException("Another folder migration is in progress; finish it first")
            else -> "folder:" + UUID.randomUUID()
        }
        val uri = Uri.parse(book.uri)
        writeJournal(Journal(old, new, System.currentTimeMillis()))
        try {
            // The snapshot pins the exact pages the stable identity will stand for.
            val entry = FolderSnapshot.snapshot(context, uri, new, book.title)
            rewriteRemote(old, new, entry, book.title)
            db.withTransaction {
                val current = db.books().get(bookId) ?: throw IOException("The folder is no longer in the library")
                if (current.fileHash != old) throw IOException("The folder identity changed during migration: ${current.title}")
                db.books().update(current.copy(fileHash = new))
                rewriteBaseRows(old, new, entry)
                val localRow = db.folderSyncBases().forFolder(new).firstOrNull { it.targetId == FolderRevisionCommit.LOCAL }
                if (localRow == null) FolderRevisionCommit.initializeLocal(db, bookId, entry)
            }
            clearJournal()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The journal stays; the next attempt resumes the same migration.
            throw e
        }
    }

    /**
     * Applies another device's migration marker to a local legacy folder. The folder's pages must
     * match the migrated revision (an identity migration never changes pages); otherwise the
     * divergence is reported instead of silently re-identifying the folder.
     */
    suspend fun follow(bookId: Long) {
        val book = db.books().get(bookId) ?: return
        val old = book.fileHash ?: return
        if (FolderManifestCodec.validId(old)) return // Already stable.
        val manifest = folders.readFolderIndex()?.let { FolderManifestCodec.decode(it.bytes) } ?: FolderManifest()
        val new = manifest.replaced[old] ?: return // Nothing (or nothing left) to follow.
        val entry = manifest.folders.firstOrNull { it.id == new }
            ?: throw IOException("The migrated folder is missing from the folder index: ${book.title}")
        val uri = Uri.parse(book.uri)
        // The id only labels the entry; the legacy id would fail manifest validation.
        val observed = FolderSnapshot.snapshot(context, uri, new, book.title)
        if (observed.pages != entry.pages) {
            problem(FolderSyncProblem(FolderSyncProblem.Kind.DIVERGED, book.title,
                "The folder changed here while its identity was migrated on another device: ${book.title}", bookId.toString()))
        }
        val pending = loadJournal()
        if (pending != null && pending.old != old) throw IOException("Another folder migration is in progress; finish it first")
        writeJournal(Journal(old, new, System.currentTimeMillis()))
        try {
            rewriteRemote(old, new, entry, book.title)
            db.withTransaction {
                val current = db.books().get(bookId) ?: return@withTransaction
                if (current.fileHash != old) return@withTransaction
                db.books().update(current.copy(fileHash = new))
                rewriteBaseRows(old, new, entry)
                val localRow = db.folderSyncBases().forFolder(new).firstOrNull { it.targetId == FolderRevisionCommit.LOCAL }
                if (localRow == null) FolderRevisionCommit.initializeLocal(db, bookId, entry)
            }
            clearJournal()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e
        }
    }

    /** Rewrites the shared documents: metadata v3 (+base), the folder index with the marker. */
    private suspend fun rewriteRemote(old: String, new: String, entry: FolderEntry, title: String) {
        val mapping = mapOf(old to new)
        val metadataFile = backend.read()
        val document = metadataFile?.let { SyncManager.decodeDocument(it.bytes) }
        val state = document?.state ?: SyncState(version = 2)
        val base = SyncManager.loadBase(context)
        val migrated = FolderIdentityMigration.metadata(state, base, mapping)
        if (migrated.state != state) {
            val revisions = document?.folderSnapshot?.revisions.orEmpty()
                .map { if (it.id == old) it.copy(id = new) else it } + entry
            val encoded = FolderMetadataSnapshotCodec.encode(
                FolderMetadataSnapshot(state = migrated.state, revisions = revisions.distinctBy { it.id }),
            )
            backend.write(encoded, metadataFile)
        }
        migrated.base?.let { if (it != base) SyncManager.saveBase(context, it) }

        val indexFile = folders.readFolderIndex()
        val manifest = indexFile?.let { FolderManifestCodec.decode(it.bytes) } ?: FolderManifest()
        val migratedEntry = manifest.folders.firstOrNull { it.id == new }
        val previousEntry = manifest.folders.firstOrNull { it.id == old }
        if (migratedEntry == null || manifest.replaced[old] != new || previousEntry != null) {
            val markers = LinkedHashMap<String, String>()
            for ((k, v) in manifest.replaced) markers[k] = if (v == old) new else v
            markers[old] = new
            val entries = manifest.folders.filter { it.id != old && it.id != new } + (migratedEntry ?: entry)
            folders.writeFolderIndex(FolderManifestCodec.encode(FolderManifest(version = 2, folders = entries, replaced = markers)), indexFile)
        }
    }

    /** Rewrites the position baseline rows of every target, including the local one. */
    private suspend fun rewriteBaseRows(old: String, new: String, entry: FolderEntry) {
        val dao = db.folderSyncBases()
        for (row in dao.forFolder(old)) {
            val decoded = FolderManifestCodec.decode(row.revisionJson.toByteArray()).folders.singleOrNull()
                ?: throw IOException("Invalid folder revision base during migration")
            val rewritten = if (decoded.id == old) decoded.copy(id = new) else decoded
            dao.upsert(app.yomu.core.database.FolderSyncBaseEntity(row.targetId, new, FolderManifestCodec.encode(FolderManifest(folders = listOf(rewritten))).toString(Charsets.UTF_8)))
            dao.delete(row.targetId, old)
        }
    }

    companion object {
        internal fun journalFile(context: Context) = File(File(context.filesDir, "sync").apply { mkdirs() }, "folder-migration-journal.json")

        /** The interrupted migration, if any; a pending journal blocks ordinary syncs until resumed. */
        internal fun loadJournal(context: Context): Journal? = try {
            val file = journalFile(context)
            if (!file.exists()) null
            else Json { ignoreUnknownKeys = true }.decodeFromString(Journal.serializer(), SyncJson.text(file.readBytes()))
        } catch (_: Exception) { null }
    }
}
