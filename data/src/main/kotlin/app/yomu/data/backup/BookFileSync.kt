package app.yomu.data.backup

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import app.yomu.core.common.Files
import app.yomu.core.database.BookEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.domain.model.BookFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** A separate, optimistic-concurrency-protected manifest and immutable content-addressed blobs. */
interface BookFilesTransport {
    suspend fun readBookIndex(): SyncFile?
    suspend fun writeBookIndex(bytes: ByteArray, previous: SyncFile?)
    suspend fun uploadBook(sha256: String, file: File)
    suspend fun <T> readBook(sha256: String, consume: (InputStream) -> T): T
}

/** Content digest of a book file: full SHA-256, size and the "library identity" hash. */
internal data class Digest(val sha256: String, val size: Long, val bookHash: String)

/** A per-book file sync problem: sync continues for the other books; this book's file part is skipped. */
internal class BookFileSyncProblem(val kind: Kind, subject: String, detail: String, val bookId: Long) : IOException(detail), SyncProblem {
    enum class Kind { CHANGED, COLLISION, UPDATE_CONFLICT, ADOPTION_BLOCKED, DELETION_CONFLICT }
    override val kindTag: String = when (kind) {
        Kind.CHANGED -> "book_changed"
        Kind.COLLISION -> "book_collision"
        Kind.UPDATE_CONFLICT -> "book_update_conflict"
        Kind.ADOPTION_BLOCKED -> "book_adoption_blocked"
        Kind.DELETION_CONFLICT -> "book_deletion_conflict"
    }
    override val subject: String = subject.take(100)
    override val target: String = bookId.toString()
}

@Serializable
internal data class BookFileEntry(val bookHash: String, val sha256: String, val size: Long, val name: String)

/** A deletion marker: the fingerprints the deleting device based the removal on, and when. */
@Serializable
internal data class DeletedBook(val expected: BookDeletionGuard.SharedRevision, val at: Long, val device: String = "")

/**
 * v2 adds [replaced] (changed files: old identity → current, chains collapse) and [deleted]
 * (removal markers published when deletion sync is on). The old entry of a replaced or deleted
 * book is removed; blobs stay on the server untouched. Markers older than the tombstone horizon
 * are ignored by receivers.
 */
@Serializable
internal data class BookFileIndex(
    val version: Int = 1,
    val books: List<BookFileEntry> = emptyList(),
    val replaced: Map<String, String> = emptyMap(),
    val deleted: Map<String, DeletedBook> = emptyMap(),
)

/** The per-target baseline of one book: what this device last agreed on, shared and private. */
@Serializable
internal data class BookBaseline(val shared: BookDeletionGuard.SharedRevision, val privateState: String)
@Serializable
internal data class BookFileBase(val version: Int = 1, val syncId: String, val present: Set<String> = emptySet(), val suppressed: Set<String> = emptySet(), val indexKnown: Boolean = false, val baselines: Map<String, BookBaseline> = emptyMap())

/**
 * Opt-in file copy sync. Uploads are snapshotted first; downloads are verified before import. Local removals after a
 * successful sync suppress re-import on that device, but do not delete copies on other devices or server blobs.
 * A changed file / prefix-hash collision is surfaced as a conflict, never overwritten.
 */
internal class BookFileSync(
    private val context: Context,
    private val db: YomuDatabase,
    private val importer: ImportService,
    /** Records per-book problems (changed file, collision) for the conflicts screen; sync continues. */
    private val onProblem: (SyncProblemInfo) -> Unit = {},
) {
    internal data class SyncFilesResult(val imported: Int, val changedBooks: List<Long>, val followBooks: List<Long>)

    private data class LocalSnapshot(val bookId: Long, val hash: String, val uri: Uri, val digest: Digest, val title: String)

    private fun problem(p: BookFileSyncProblem) = onProblem(p.record(System.currentTimeMillis()))

    private suspend fun verifyLocalSnapshots(snapshots: List<LocalSnapshot>) {
        for (snapshot in snapshots) {
            currentCoroutineContext().ensureActive()
            val current = db.books().get(snapshot.bookId)
            if (current == null || current.fileHash != snapshot.hash || current.uri != snapshot.uri.toString())
                throw IOException("Book changed during synchronization: ${snapshot.title}")
            val actual = try { Files.open(context, snapshot.uri).use { digest(it) } }
                catch (e: Exception) { throw IOException("Cannot verify book after transfer: ${snapshot.title}", e) }
            if (actual != snapshot.digest) throw IOException("Book changed during synchronization: ${snapshot.title}")
        }
    }
    suspend fun sync(transport: BookFilesTransport, syncId: String = "test"): Int = syncDetailed(transport, syncId).imported

    suspend fun syncDetailed(transport: BookFilesTransport, syncId: String = "test"): SyncFilesResult {
        var attempt = 0
        while (true) {
            try { return syncOnce(transport, syncId) } catch (e: SyncConflictException) {
                if (++attempt >= 3) throw e
            }
        }
    }

    private suspend fun syncOnce(transport: BookFilesTransport, syncId: String): SyncFilesResult {
        val base = readBase(context, syncId)
        val file = transport.readBookIndex()
        if (file == null && base?.indexKnown == true) throw IOException("Book sync index is missing; restore it before syncing")
        val index = decodeIndex(file?.bytes)
        val replaced = index.replaced
        val entries = index.books.associateByTo(LinkedHashMap()) { it.bookHash }
        val local = db.books().all().filter { it.format != "FOLDER" && it.fileHash != null && !isAudioCollection(it.fileHash) }
        if (local.map { it.fileHash }.toSet().size != local.size)
            throw IOException("Duplicate book identifier prevents safe file sync; resolve the conflict before syncing")
        val localHashes = local.mapNotNull { it.fileHash }.toSet()
        // A book seen in this target's last successful sync and now removed is a local deletion,
        // not a missing file to repair. A manual re-import clears its suppression.
        val suppressed = ((base?.suppressed.orEmpty() + (base?.present.orEmpty() - localHashes)) - localHashes).toMutableSet()
        val present = HashSet<String>()
        val snapshots = mutableListOf<LocalSnapshot>()
        val changedBooks = mutableListOf<Long>()
        val followBooks = mutableListOf<Long>()
        var added = false
        for (book in local) {
            currentCoroutineContext().ensureActive()
            val key = book.fileHash ?: continue
            val uri = Uri.parse(book.uri)
            val available = try { Files.open(context, uri).use { it.read() }; true } catch (_: Exception) { false }
            if (!available) {
                if (entries[key] == null && key !in replaced) throw IOException("Cannot read local book: ${book.title}")
                if (key in replaced) followBooks += book.id // The follow step repairs the missing file from the new version.
                continue // A copy from WebDAV can repair the missing file below.
            }
            val original = Files.open(context, uri).use { digest(it) }
            if (original.size <= 0) throw IOException("Cannot sync an empty book: ${book.title}")
            if (original.bookHash != key) {
                // The file changed after the import (an external edit, or this device's own PDF
                // annotation write). The file part of this book is skipped; metadata keeps syncing.
                // The conflicts screen offers adoption; self-modified PDFs adopt automatically later
                // in the same run.
                problem(BookFileSyncProblem(
                    if (key in replaced) BookFileSyncProblem.Kind.UPDATE_CONFLICT else BookFileSyncProblem.Kind.CHANGED,
                    book.title,
                    "The local file of \"${book.title}\" no longer matches the synchronized identity; no file was replaced",
                    book.id,
                ))
                changedBooks += book.id
                continue
            }
            if (key in replaced) {
                // Another device re-registered this book under a new identity and the local copy is
                // unchanged: adopt the replacement (the follow step downloads the new version).
                followBooks += book.id
                present += key
                snapshots += LocalSnapshot(book.id, key, uri, original, book.title)
                continue
            }
            if (index.deleted.containsKey(key)) {
                // A deletion marker is pending for this book: do not re-publish the copy here,
                // the removal step below decides its fate (apply, or keep on a recorded conflict).
                present += key
                snapshots += LocalSnapshot(book.id, key, uri, original, book.title)
                continue
            }
            val existing = entries[key]
            if (existing != null) {
                if (existing.sha256 != original.sha256 || existing.size != original.size) {
                    problem(BookFileSyncProblem(BookFileSyncProblem.Kind.COLLISION, book.title,
                        "Different files have the same library identifier: ${book.title}", book.id))
                    continue
                }
                present += key
                snapshots += LocalSnapshot(book.id, key, uri, original, book.title)
                continue
            }
            val snapDir = File(context.cacheDir, "book-sync").apply { mkdirs() }
            val snapshot = File.createTempFile("upload-", ".part", snapDir)
            try {
                Files.open(context, uri).use { input -> snapshot.outputStream().use { out -> input.copyTo(out, 64 * 1024) } }
                val copied = snapshot.inputStream().use { digest(it) }
                if (copied != original) throw IOException("Book changed during upload: ${book.title}")
                transport.uploadBook(original.sha256, snapshot)
            } finally { snapshot.delete() }
            val extensions = runCatching { BookFormat.valueOf(book.format).extensions }.getOrDefault(emptyList())
            val fallbackExt = extensions.firstOrNull() ?: "bin"
            val displayName = Files.displayName(context, uri)?.takeIf { it.isNotBlank() } ?: book.title
            entries[key] = BookFileEntry(key, original.sha256, original.size, safeName(displayName, extensions, fallbackExt))
            present += key
            snapshots += LocalSnapshot(book.id, key, uri, original, book.title)
            added = true
        }
        verifyLocalSnapshots(snapshots)
        if (added) transport.writeBookIndex(json.encodeToString(BookFileIndex.serializer(), BookFileIndex(books = entries.values.toList())).toByteArray(), file)

        // Replacement markers: never install a replaced version behind this device's back.
        // Locally known books get it via the follow step (or a conflict record); locally deleted
        // books keep their suppression under the new identity.
        val skipVersions = replaced.filterKeys { it in localHashes || it in suppressed }.values
        var imported = 0
        for (entry in entries.values) {
            currentCoroutineContext().ensureActive()
            if (entry.bookHash in present || entry.bookHash in suppressed || entry.bookHash in skipVersions) continue
            val directory = File(context.filesDir, "synced-books").apply { mkdirs() }
            // Random paths avoid overwriting any other book, including on case-insensitive providers.
            val suffix = "." + entry.name.substringAfterLast('.', "bin").take(12).filter { it.isLetterOrDigit() }.ifBlank { "bin" }
            val temp = File.createTempFile("book-", suffix, directory)
            var keep = false
            var importStarted = false
            try {
                transport.readBook(entry.sha256) { input ->
                    temp.outputStream().use { out -> copyVerified(input, out, entry.sha256, entry.size) }
                }
                val identity = temp.inputStream().use { digest(it) }
                if (identity.bookHash != entry.bookHash) throw IOException("Downloaded book does not match its library identifier")
                importStarted = true
                when (val result = importer.importLocalFile(temp, entry.name)) {
                    is ImportOutcome.Added -> { keep = true; imported++ }
                    is ImportOutcome.Duplicate -> {
                        val path = importer.adoptFileForDuplicate(result.bookId, temp)
                            ?: throw IOException("Could not restore missing book: ${entry.name}")
                        keep = File(path).absolutePath == temp.absolutePath
                        if (keep) imported++
                    }
                    is ImportOutcome.Failed -> throw IOException("Could not import book: ${entry.name}", result.error)
                }
            } finally {
                if (!keep) {
                    // Import can commit the book row and then fail while saving auxiliary data.
                    // Never remove verified bytes if the database already points at them; if the
                    // database cannot be checked, retaining an orphan is safer than data loss.
                    val mayBeReferenced = if (importStarted) withContext(NonCancellable) {
                        try { db.books().byUri(Uri.fromFile(temp).toString()) != null } catch (_: Exception) { true }
                    } else false
                    if (!mayBeReferenced) temp.delete()
                }
            }
        }
        verifyLocalSnapshots(snapshots)
        val after = db.books().all().filter { it.format != "FOLDER" && !isAudioCollection(it.fileHash) }.mapNotNull { it.fileHash }.toSet()
        suppressed.addAll(localHashes - after)
        suppressed.removeAll(after)
        saveBase(context, BookFileBase(syncId = syncId, present = after, suppressed = suppressed, indexKnown = file != null || added, baselines = base?.baselines.orEmpty()))
        return SyncFilesResult(imported, changedBooks.toList(), followBooks.toList())
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val sha256Pattern = Regex("[0-9a-f]{64}")
        private val bookHashPattern = Regex("[0-9a-f]{40}:[0-9]+")
        private const val PREFIX_LIMIT = 1024L * 1024
        private const val MAX_INDEX_BYTES = 4 * 1024 * 1024

        private fun baseFile(context: Context, syncId: String): AtomicFile {
            val digest = MessageDigest.getInstance("SHA-256").digest(syncId.toByteArray()).joinToString("") { "%02x".format(it) }
            return AtomicFile(File(File(context.filesDir, "sync-books").apply { mkdirs() }, "$digest.json"))
        }

        internal fun readBase(context: Context, syncId: String): BookFileBase? {
            val file = baseFile(context, syncId)
            val backup = File(file.baseFile.path + ".bak")
            val pending = File(file.baseFile.path + ".new")
            if (!file.baseFile.exists() && !backup.exists()) {
                if (pending.exists()) throw IOException("Incomplete book sync base")
                return null
            }
            val base = try {
                file.openRead().use { input ->
                    val bytes = readSyncBytes(input, MAX_INDEX_BYTES)
                    if (bytes.size > MAX_INDEX_BYTES) throw IOException("Book sync base is too large")
                    json.decodeFromString(BookFileBase.serializer(), SyncJson.text(bytes))
                }
            } catch (e: Exception) { throw IOException("Invalid book sync base", e) }
            if (base.version != 1 || base.syncId != syncId || (base.present + base.suppressed).any { !it.matches(bookHashPattern) } ||
                base.baselines.any { (id, _) -> !id.matches(bookHashPattern) })
                throw IOException("Invalid book sync base")
            return base
        }

        internal fun saveBase(context: Context, base: BookFileBase) {
            val bytes = json.encodeToString(BookFileBase.serializer(), base).toByteArray()
            if (bytes.size > MAX_INDEX_BYTES) throw IOException("Book sync base is too large")
            AtomicPublish.publish(baseFile(context, base.syncId).baseFile, bytes)
        }

        /** After an adoption the book's present/suppressed identities follow the new content identity. */
        internal fun remapBaseIdentity(context: Context, syncId: String, mapping: Map<String, String>) {
            val base = readBase(context, syncId) ?: return
            val remapped = base.copy(
                present = base.present.map { mapping[it] ?: it }.toSet(),
                suppressed = base.suppressed.map { mapping[it] ?: it }.toSet(),
                baselines = base.baselines.mapKeys { (id, _) -> mapping[id] ?: id },
            )
            if ((remapped.present + remapped.suppressed).any { !it.matches(bookHashPattern) })
                throw IOException("Invalid book identity after adoption")
            saveBase(context, remapped)
        }

        internal fun digest(input: InputStream): Digest {
            val sha = MessageDigest.getInstance("SHA-256")
            val first = MessageDigest.getInstance("SHA-1")
            var size = 0L
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                sha.update(buf, 0, n)
                val prefix = (PREFIX_LIMIT - size).coerceIn(0, n.toLong()).toInt()
                if (prefix > 0) first.update(buf, 0, prefix)
                size += n
            }
            fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
            return Digest(sha.digest().hex(), size, first.digest().hex() + ":" + size)
        }

        internal fun safeName(name: String, extensions: List<String>, fallbackExt: String): String {
            // Pick the extension before sanitize truncates a long name; fb2.zip must not become fb2.
            val trimmed = name.trim()
            val candidates = (if (fallbackExt == "fb2") listOf("fb2.zip") else emptyList()) + extensions
            val ext = candidates.sortedByDescending { it.length }
                .firstOrNull { trimmed.endsWith(".$it", ignoreCase = true) } ?: fallbackExt
            val stem = if (trimmed.endsWith(".$ext", ignoreCase = true)) trimmed.dropLast(ext.length + 1) else trimmed
            return Files.sanitize(stem).take((119 - ext.length).coerceAtLeast(1)) + "." + ext
        }

        internal fun decodeIndex(bytes: ByteArray?): BookFileIndex {
            if (bytes == null) return BookFileIndex()
            try {
                if (bytes.size > MAX_INDEX_BYTES) throw IOException("Book index too large")
                val root = json.parseToJsonElement(SyncJson.text(bytes)).jsonObject
                require("version" in root && "books" in root)
                val index = json.decodeFromJsonElement(BookFileIndex.serializer(), root)
                require(index.version in 1..2)
                val ids = index.books.map { it.bookHash }
                require(index.books.size == ids.toSet().size)
                index.books.forEach { e ->
                    require(e.sha256.matches(sha256Pattern) && e.bookHash.matches(bookHashPattern) && e.size > 0)
                    require(e.bookHash.substringAfter(':').toLongOrNull() == e.size)
                    require(e.name.isNotBlank() && e.name.length <= 200 && e.name == Files.sanitize(e.name))
                }
                if (index.version >= 2) {
                    val ids = ids.toSet()
                    require(index.replaced.keys.all { it.matches(bookHashPattern) && it !in ids })
                    require(index.replaced.values.all { it in ids })
                    require(index.replaced.keys.none { it == index.replaced[it] })
                    require(index.deleted.keys.all { it.matches(bookHashPattern) && it !in ids })
                    require(index.deleted.values.all { m ->
                        listOf(m.expected.file, m.expected.metadata, m.expected.trackingLinks).all { it.matches(sha256Pattern) } && m.at > 0
                    })
                } else {
                    require(index.replaced.isEmpty() && index.deleted.isEmpty())
                }
                return index
            } catch (e: Exception) { throw IOException("Invalid book sync index; it was not overwritten", e) }
        }

        internal fun copyVerified(input: InputStream, output: java.io.OutputStream, expected: String, size: Long) {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (n.toLong() > size - done) throw IOException("Downloaded book is larger than declared")
                output.write(buf, 0, n)
                digest.update(buf, 0, n)
                done += n
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (done != size || actual != expected) throw IOException("Downloaded book failed integrity verification")
        }

        internal fun verifyStream(input: InputStream, expected: String, size: Long) =
            copyVerified(input, object : java.io.OutputStream() {
                override fun write(b: Int) = Unit
                override fun write(b: ByteArray, off: Int, len: Int) = Unit
            }, expected, size)
    }
}

/**
 * Audiobook folders and `.yaudio` manifests have no single file to transfer (and manifests point to online tracks
 * or to files of this device): they stay out of file sync, their progress and metadata still sync.
 */
internal fun isAudioCollection(fileHash: String?): Boolean = fileHash != null && (fileHash.startsWith("audiodir:") || fileHash.startsWith("yaudio:"))
