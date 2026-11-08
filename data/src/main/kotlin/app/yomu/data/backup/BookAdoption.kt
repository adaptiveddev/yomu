package app.yomu.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.yomu.core.common.Files
import app.yomu.core.database.BookEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.data.book.pdf.PdfAnnotationStore
import app.yomu.domain.model.BookFormat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.StandardCopyOption

/**
 * Deliberate re-registration of a changed book file ("adopt the new version"): the new content is
 * uploaded as its own blob, the shared metadata and the merge bases are re-keyed to the new
 * identity (progress, highlights, notes, bookmarks and tracker bindings stay on the book), the
 * book index gains a replacement marker (old identity → current identity, chains collapse) and
 * the local row follows. Server blobs are never deleted.
 *
 * [follow] applies another device's marker: the marker is the commit point, so a follower also
 * finishes any interrupted remote part, re-keys its local data and installs the new file version.
 * A durable journal makes every step resumable; a partial migration is never mistaken for an
 * unrelated new book, and concurrent remote changes abort with a conflict instead of overwriting.
 * A file that changed on both devices is reported, never silently overwritten.
 */
internal class BookAdoption(
    private val context: Context,
    private val db: YomuDatabase,
    private val settings: AppSettings,
    private val backend: SyncBackend,
    private val syncId: String,
    private val recordProblem: (SyncProblemInfo) -> Unit = {},
) {
    private val json = Json { encodeDefaults = true }

    @Serializable
    internal data class Journal(val old: String, val new: String, val sha256: String, val size: Long, val name: String, val at: Long)

    private val files: BookFilesTransport get() = backend as? BookFilesTransport
        ?: throw IOException("The sync target does not transfer book files")

    private fun problem(p: BookFileSyncProblem): Nothing {
        recordProblem(p.record(System.currentTimeMillis()))
        throw p
    }

    /** The interrupted adoption, if any; its next run resumes instead of starting over. */
    private fun readJournal(): Journal? = try {
        val file = journalFile(context)
        if (!file.exists()) null
        else json.decodeFromString(Journal.serializer(), SyncJson.text(file.readBytes()))
    } catch (_: Exception) { null }

    private suspend fun writeJournal(journal: Journal) {
        val file = journalFile(context)
        val temp = File.createTempFile("adoption-", ".new", file.parentFile)
        try {
            temp.writeBytes(json.encodeToString(Journal.serializer(), journal).toByteArray())
            java.nio.file.Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temp.delete()
            throw IOException("Cannot write the adoption journal", e)
        }
    }

    private suspend fun clearJournal() {
        withContext(NonCancellable) { journalFile(context).delete() }
    }

    private suspend fun digestOf(uri: Uri, title: String): Digest =
        try { Files.open(context, uri).use { BookFileSync.digest(it) } }
        catch (e: Exception) { throw IOException("Cannot read the book file: $title", e) }

    /**
     * Adopts the current local file as the book's new version. The book keeps its progress,
     * annotations and tracker bindings; the identity (and every record keyed by it) moves to the
     * new content identity.
     */
    suspend fun adopt(bookId: Long) {
        val book = db.books().get(bookId) ?: throw IOException("The book is no longer in the library")
        if (book.format == "FOLDER") throw IOException("Image folders use their own migration")
        val rowId = book.fileHash ?: throw IOException("The book has no file identity to adopt")
        val uri = Uri.parse(book.uri)
        val journal = readJournal()
        if (journal != null && journal.old != rowId && journal.new != rowId)
            throw IOException("Another book adoption is in progress; finish it first")
        if (journal == null && !rowId.matches(bookHashPattern))
            throw IOException("The book identity is not a file identity: ${book.title}")
        val digest = digestOf(uri, book.title)
        val old = if (journal != null && rowId == journal.new) journal.old else rowId
        val new = digest.bookHash
        if (new == old && journal == null) return // Already consistent; nothing to adopt.
        if (new != journal?.new && new != old && journal != null)
            throw IOException("The file changed again during the interrupted adoption; resolve it before syncing")
        val name = if (journal != null) journal.name else entryName(book)
        adoptIdentity(book.id, old, new, digest.sha256, digest.size, book.title, name, uri, install = null)
    }

    /** Self-modified PDFs (this device's own annotation write) adopt without user action. */
    suspend fun autoAdopt(bookId: Long) {
        val book = db.books().get(bookId) ?: return
        val old = book.fileHash ?: return
        val uri = Uri.parse(book.uri)
        val original = runCatching { digestOf(uri, book.title) }.getOrNull() ?: return
        if (original.bookHash == old) return // Already consistent.
        if (book.format != "PDF") return // Stays a recorded problem for the conflicts screen.
        if (!PdfAnnotationStore(context, db, settings).isOwnAppendage(bookId, uri, old)) return
        adopt(bookId)
    }

    /**
     * Applies another device's replacement marker to a local book. With [forced] the local file
     * changes are discarded (the conflicts screen's "take the other version"); otherwise a
     * locally changed file is reported as a conflict and nothing is modified.
     */
    suspend fun follow(bookId: Long, forced: Boolean) {
        val book = db.books().get(bookId) ?: return
        val old = book.fileHash ?: return
        if (!old.matches(bookHashPattern)) return
        val index = BookFileSync.decodeIndex(files.readBookIndex()?.bytes)
        val new = index.replaced[old] ?: return // Nothing (or nothing left) to follow.
        val entry = index.books.firstOrNull { it.bookHash == new }
            ?: throw IOException("The replacement version is missing from the book index: ${book.title}")
        val uri = Uri.parse(book.uri)
        val local = runCatching { digestOf(uri, book.title) }.getOrNull()
        if (local != null && local.bookHash != old && !forced) {
            problem(BookFileSyncProblem(BookFileSyncProblem.Kind.UPDATE_CONFLICT, book.title,
                "The book changed here and on another device: ${book.title}", bookId))
        }
        adoptIdentity(book.id, old, new, entry.sha256, entry.size, book.title, entry.name, uri,
            install = InstallVersion(uri, entry.sha256, entry.size, replace = local?.bookHash != new))
    }

    private data class InstallVersion(val uri: Uri, val sha256: String, val size: Long, val replace: Boolean)

    private suspend fun adoptIdentity(
        bookId: Long,
        old: String,
        new: String,
        sha: String,
        size: Long,
        title: String,
        name: String,
        sourceUri: Uri,
        install: InstallVersion?,
    ) {
        val mapping = mapOf(old to new)
        writeJournal(Journal(old, new, sha, size, name, System.currentTimeMillis()))
        try {
            // The file index: remove the old entry, add the new one, collapse replacement chains.
            val indexFile = files.readBookIndex()
            val index = BookFileSync.decodeIndex(indexFile?.bytes)
            val previousEntry = index.books.firstOrNull { it.bookHash == old }
            val existingNew = index.books.firstOrNull { it.bookHash == new }
            if (existingNew == null || index.replaced[old] != new || previousEntry != null) {
                val markers = LinkedHashMap<String, String>()
                for ((k, v) in index.replaced) markers[k] = if (v == old) new else v
                markers[old] = new
                val entries = index.books.filter { it.bookHash != old && it.bookHash != new } +
                    (existingNew ?: BookFileEntry(new, sha, size, previousEntry?.name ?: name))
                val encoded = json.encodeToString(
                    BookFileIndex.serializer(),
                    BookFileIndex(version = 2, books = entries, replaced = markers),
                ).toByteArray()
                files.writeBookIndex(encoded, indexFile)
            }
            uploadVersion(sha, size, sourceUri)
            rekeyMetadata(mapping, title)
            rewriteBookFileBase(mapping)
            if (install?.replace == true) installVersion(install)
            db.withTransaction {
                val book = db.books().get(bookId) ?: throw IOException("The book is no longer in the library")
                if (book.fileHash !in setOf(old, new)) throw IOException("The book identity changed during adoption: $title")
                val digest = digestOf(Uri.parse(book.uri), book.title)
                if (digest.bookHash != new) throw IOException("The local file does not match the adopted version: $title")
                db.books().update(book.copy(fileHash = new))
            }
            clearJournal()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The journal stays: the next sync or adoption attempt resumes the same migration
            // instead of treating the half-migrated book as a different one.
            throw e
        }
    }

    /** The blob is content-addressed and immutable: reuse a verified copy, otherwise publish one. */
    private suspend fun uploadVersion(sha: String, size: Long, uri: Uri) {
        val verified = runCatching { files.readBook(sha) { input -> BookFileSync.verifyStream(input, sha, size) } }.isSuccess
        if (verified) return
        val snapDir = File(context.cacheDir, "book-sync").apply { mkdirs() }
        val snapshot = File.createTempFile("adopt-", ".part", snapDir)
        try {
            Files.copy(context, uri, snapshot)
            val digest = snapshot.inputStream().use { BookFileSync.digest(it) }
            if (digest.sha256 != sha || digest.size != size) throw IOException("The adopted file changed during upload")
            try {
                files.uploadBook(sha, snapshot)
            } catch (e: SyncConflictException) {
                // Another device published the same content between our check and write: fine.
                files.readBook(sha) { input -> BookFileSync.verifyStream(input, sha, size) }
            }
        } finally { snapshot.delete() }
    }

    /**
     * Re-keys the shared metadata document and the local merge base from the same read. Base
     * revisions of the book's records are recomputed from the rewritten records; a base key with
     * no record (or a deletion proof) aborts the adoption fail-closed.
     */
    private suspend fun rekeyMetadata(mapping: Map<String, String>, title: String) {
        val file = backend.read()
        val document = file?.let { SyncManager.decodeDocument(it.bytes) }
        val state = document?.state ?: SyncState(version = 2)
        val base = SyncManager.loadBase(context)
        val migrated = SyncIdentityMigration.metadata(state, base, mapping, bookHashPattern::matches, recomputeRevisions = true)
        if (migrated.state != state) {
            val encoded = when (val snapshot = document?.folderSnapshot) {
                null -> Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
                    .encodeToString(SyncState.serializer(), migrated.state).toByteArray()
                else -> FolderMetadataSnapshotCodec.encode(FolderMetadataSnapshot(state = migrated.state, revisions = snapshot.revisions))
            }
            backend.write(encoded, file)
        }
        if (base != null && migrated.base != base) SyncManager.saveBase(context, migrated.base!!)
    }

    /** The per-target file base: present and suppressed identities follow the book. */
    private suspend fun rewriteBookFileBase(mapping: Map<String, String>) {
        BookFileSync.remapBaseIdentity(context, syncId, mapping)
    }

    /** Installs the new version's bytes as the book's file (conflict screen's "take the other version"). */
    private suspend fun installVersion(install: InstallVersion) {
        val directory = File(context.filesDir, "synced-books").apply { mkdirs() }
        val temp = File.createTempFile("adopt-install-", ".part", directory)
        var moved = false
        try {
            files.readBook(install.sha256) { input -> temp.outputStream().use { out -> BookFileSync.copyVerified(input, out, install.sha256, install.size) } }
            try {
                when (install.uri.scheme) {
                    "file", null -> java.nio.file.Files.move(temp.toPath(), File(install.uri.path!!).toPath(), StandardCopyOption.REPLACE_EXISTING).also { moved = true }
                    "content" -> context.contentResolver.openOutputStream(install.uri, "wt")?.use { out ->
                        temp.inputStream().use { it.copyTo(out) }
                    } ?: throw IOException("Cannot open the book file for writing")
                    else -> throw IOException("The book file location does not support replacing its content")
                }
            } catch (e: IOException) {
                if (e.message?.contains("book file") == true || e.message?.contains("location") == true) throw e
                throw IOException("Cannot replace the book file: ${e.message}", e)
            }
            val actual = digestOf(install.uri, "the replaced book")
            if (actual.sha256 != install.sha256 || actual.size != install.size)
                throw IOException("The installed version does not match the downloaded copy")
        } finally {
            if (!moved) temp.delete()
        }
    }

    private fun entryName(book: BookEntity): String {
        val extensions = runCatching { BookFormat.valueOf(book.format).extensions }.getOrDefault(emptyList())
        val fallbackExt = extensions.firstOrNull() ?: "bin"
        val displayName = Files.displayName(context, Uri.parse(book.uri))?.takeIf { it.isNotBlank() } ?: book.title
        return BookFileSync.safeName(displayName, extensions, fallbackExt)
    }

    companion object {
        internal val bookHashPattern = Regex("[0-9a-f]{40}:[0-9]+")

        internal fun journalFile(context: Context) = File(File(context.filesDir, "sync").apply { mkdirs() }, "adoption-journal.json")

        /** The interrupted adoption, if any; a pending journal blocks ordinary syncs until resumed. */
        internal fun loadJournal(context: Context): Journal? = try {
            val file = journalFile(context)
            if (!file.exists()) null
            else Json { ignoreUnknownKeys = true }.decodeFromString(Journal.serializer(), SyncJson.text(file.readBytes()))
        } catch (_: Exception) { null }
    }
}
