package app.yomu.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

// A damaged server file must not allocate unbounded memory before it is validated.
internal const val MAX_SYNC_FILE_BYTES = 32 * 1024 * 1024
internal fun readSyncBytes(input: InputStream, limit: Int): ByteArray {
    require(limit >= 0)
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit.toLong() - out.size() + 1).toInt())
        if (n < 0) return out.toByteArray()
        if (n == 0) continue
        if (out.size().toLong() + n > limit) throw IOException("Sync file is too large; it was not overwritten")
        out.write(buffer, 0, n)
    }
}

/** The shared sync file as read: its content and a version tag for a safe overwrite (WebDAV ETag). */
class SyncFile(val bytes: ByteArray, val tag: String?)

/** Thrown when the file changed on the server between reading and writing; the sync starts over. */
class SyncConflictException : IOException("The sync file was changed by another device")

/** Where the shared sync file lives. */
interface SyncBackend {
    /** Identifies the target: the sync base of another target is not used. */
    val id: String

    /** The current file, or null when there is none yet. */
    suspend fun read(): SyncFile?

    /** Writes the file; [previous] is what [read] returned (null = there was no file). */
    suspend fun write(bytes: ByteArray, previous: SyncFile?)

    /** Checks the connection and the access rights (creates the folder if it is missing). */
    suspend fun check()
}

/** A folder picked with the system file picker (a cloud drive folder, for example).
 * SAF does not supply an atomic compare-and-swap: this check detects changes seen before writing, but cannot prevent
 * concurrent writes or delayed cloud-provider visibility. Synchronize one device at a time; WebDAV is safer.
 */
class FolderSyncBackend(private val context: Context, private val folder: Uri) : SyncBackend, FolderFilesTransport, TrackLinkTransport {
    override val id: String = "folder:$folder"

    private fun find(name: String = SyncManager.FILE_NAME): Uri? {
        val root = DocumentsContract.getTreeDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, root)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val cursor = context.contentResolver.query(children, columns, null, null, null)
            ?: throw IOException("Cannot list the sync folder")
        var found: Uri? = null
        cursor.use { c ->
            val idColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                if (c.getString(nameColumn) != name) continue
                if (found != null) throw IOException("More than one $name exists in the shared folder")
                if (c.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) throw IOException("$name is a folder")
                val id = c.getString(idColumn) ?: throw IOException("Invalid sync file in the shared folder")
                found = DocumentsContract.buildDocumentUriUsingTree(folder, id)
            }
        }
        return found
    }

    private fun readBounded(uri: Uri, limit: Int): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read the sync file")
        return input.use { readSyncBytes(it, limit) }
    }

    /** An interrupted replacement leaves a recovery copy; never treat it as an empty/new state. */
    private fun requireNoRecoveryCopy(name: String) {
        val root = DocumentsContract.getTreeDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, root)
        val column = DocumentsContract.Document.COLUMN_DISPLAY_NAME
        val cursor = context.contentResolver.query(children, arrayOf(column), null, null, null)
            ?: throw IOException("Cannot list the sync folder")
        cursor.use { c ->
            val names = c.getColumnIndexOrThrow(column)
            while (c.moveToNext()) {
                val candidate = c.getString(names) ?: continue
                if (candidate.startsWith("$name.yomu-backup-"))
                    throw IOException("Interrupted sync write: recovery copy $candidate exists; inspect and restore it before syncing")
            }
        }
    }

    private fun readNamed(name: String, limit: Int = Int.MAX_VALUE): SyncFile? {
        requireNoRecoveryCopy(name)
        val uri = find(name) ?: return null
        return SyncFile(readBounded(uri, limit), null)
    }

    private fun writeNamed(name: String, bytes: ByteArray, previous: SyncFile?, limit: Int = Int.MAX_VALUE) {
        if (bytes.size > limit) throw IOException("Sync index is too large")
        requireNoRecoveryCopy(name)
        fun current(): Uri? {
            val existing = find(name)
            val contents = existing?.let { readBounded(it, limit) }
            if ((contents == null) != (previous == null) || (contents != null && !contents.contentEquals(previous!!.bytes)))
                throw SyncConflictException()
            return existing
        }
        current() // Detect conflicts before writing any staging file.
        val stageName = "yomu-index-${java.util.UUID.randomUUID()}.part"
        val stage = DocumentsContract.createDocument(context.contentResolver, rootDocument(), "application/json", stageName)
            ?: throw IOException("Cannot create a staging sync file")
        var ownedStage: Uri? = stage
        try {
            if (find(stageName) != stage) throw IOException("The sync provider did not create the expected staging file")
            (context.contentResolver.openOutputStream(stage, "wt") ?: throw IOException("Cannot write the staging sync file")).use { it.write(bytes) }
            if (!readBounded(stage, limit).contentEquals(bytes)) throw IOException("The sync provider did not save the complete staging file")
            requireNoRecoveryCopy(name)
            val existing = current()
            var backup: Uri? = null
            if (existing != null) {
                // SAF has no atomic compare-and-swap. Keep the old file under a recovery name
                // before replacing it, and fail closed if a process dies between the renames.
                val backupName = "$name.yomu-backup-${java.util.UUID.randomUUID()}"
                backup = DocumentsContract.renameDocument(context.contentResolver, existing, backupName)
                    ?: throw IOException("The sync provider cannot preserve a recovery copy")
                if (find(backupName) != backup || !readBounded(backup, limit).contentEquals(previous!!.bytes))
                    throw IOException("Cannot verify sync recovery copy: $backupName")
            }
            val published = DocumentsContract.renameDocument(context.contentResolver, stage, name)
                ?: throw IOException("The sync provider cannot publish the staged sync file")
            ownedStage = null
            if (find(name) != published || !readBounded(published, limit).contentEquals(bytes))
                throw IOException("The sync provider did not publish the complete sync file")
            if (backup != null && !DocumentsContract.deleteDocument(context.contentResolver, backup))
                throw IOException("Cannot remove the verified sync recovery copy; inspect the shared folder")
        } finally {
            ownedStage?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
        }
    }

    private fun rootDocument(): Uri = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
    private fun blobName(sha256: String): String {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid book hash" }
        return "yomu-book-$sha256.bin"
    }

    override suspend fun read(): SyncFile? = withContext(Dispatchers.IO) { readNamed(SyncManager.FILE_NAME, MAX_SYNC_FILE_BYTES) }
    override suspend fun write(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) { writeNamed(SyncManager.FILE_NAME, bytes, previous, MAX_SYNC_FILE_BYTES) }
    override suspend fun readBookIndex(): SyncFile? = withContext(Dispatchers.IO) { readNamed(BOOK_INDEX, MAX_INDEX_BYTES) }
    override suspend fun writeBookIndex(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) { writeNamed(BOOK_INDEX, bytes, previous, MAX_INDEX_BYTES) }
    override suspend fun readFolderIndex(): SyncFile? = withContext(Dispatchers.IO) { readNamed(FOLDER_INDEX, MAX_INDEX_BYTES) }
    override suspend fun writeFolderIndex(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) { writeNamed(FOLDER_INDEX, bytes, previous, MAX_INDEX_BYTES) }
    override suspend fun readTrackLinks(): SyncFile? = withContext(Dispatchers.IO) { readNamed(TRACK_INDEX, MAX_INDEX_BYTES) }
    override suspend fun writeTrackLinks(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) { writeNamed(TRACK_INDEX, bytes, previous, MAX_INDEX_BYTES) }

    override suspend fun <T> readBook(sha256: String, consume: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val uri = find(blobName(sha256)) ?: throw IOException("Book file is missing from the shared folder")
        (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read the book file")).use(consume)
    }

    override suspend fun uploadBook(sha256: String, file: File): Unit = withContext(Dispatchers.IO) {
        val name = blobName(sha256)
        fun verify(uri: Uri) {
            (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot verify the book file")).use { BookFileSync.verifyStream(it, sha256, file.length()) }
        }
        val existing = find(name)
        if (existing != null) { verify(existing); return@withContext }
        // A random staging document is never referenced by an index. Publish only after full verification.
        val stageName = "yomu-upload-${java.util.UUID.randomUUID()}.part"
        val stage = DocumentsContract.createDocument(context.contentResolver, rootDocument(), "application/octet-stream", stageName)
            ?: throw IOException("Cannot create a staging file in the shared folder")
        var ownedStage: Uri? = stage
        try {
            if (find(stageName) != stage) throw IOException("The sync provider did not create the expected staging file")
            (context.contentResolver.openOutputStream(stage, "wt") ?: throw IOException("Cannot write the staging file")).use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
            verify(stage)
            val now = find(name)
            if (now != null) { verify(now); return@withContext }
            val published = DocumentsContract.renameDocument(context.contentResolver, stage, name)
                ?: throw IOException("The shared-folder provider cannot publish a book safely")
            ownedStage = null
            if (find(name) != published) throw IOException("The sync provider did not publish the expected book file")
            verify(published)
        } finally {
            ownedStage?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
        }
    }

    private companion object {
        const val BOOK_INDEX = "yomu-books.json"
        const val TRACK_INDEX = "yomu-tracking.json"
        const val FOLDER_INDEX = "yomu-folders.json"
        const val MAX_INDEX_BYTES = 4 * 1024 * 1024
    }

    override suspend fun check() { withContext(Dispatchers.IO) { find() } }
}

/**
 * A folder on a WebDAV server (Nextcloud, ownCloud, Yandex Disk, Koofr, a NAS…). The file is replaced only if nobody
 * changed it since it was read (If-Match with the ETag), otherwise the sync merges again.
 */
class WebDavSyncBackend(private val client: OkHttpClient, url: String, user: String, password: String) : SyncBackend, FolderFilesTransport, TrackLinkTransport {
    private val folder = url.trim().trimEnd('/') + "/"
    private val fileUrl = folder + SyncManager.FILE_NAME
    private val auth = if (user.isNotEmpty() || password.isNotEmpty()) Credentials.basic(user, password, Charsets.UTF_8) else null
    override val id: String = "webdav:$folder"

    init {
        require(folder.startsWith("https://") || folder.startsWith("http://")) { "The WebDAV address must start with https:// or http://" }
    }

    private fun request(url: String) = Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }
    // Only a single, quoted RFC entity-tag is safe for If-Match. In particular "*" would
    // turn the intended version check into an existence check and permit lost updates.
    private fun strongTag(tag: String?): String? = tag?.takeIf { value ->
        value.length >= 2 && value.first() == '"' && value.last() == '"' &&
            value.substring(1, value.length - 1).all { c -> c == '\u0021' || c in '\u0023'..'\u007e' || c in '\u0080'..'\u00ff' }
    }

    private fun fail(code: Int, what: String): Nothing = when (code) {
        401, 403 -> throw IOException("WebDAV: wrong user name or password (HTTP $code)")
        404 -> throw IOException("WebDAV: $what not found (HTTP 404)")
        507 -> throw IOException("WebDAV: no space left on the server (HTTP 507)")
        else -> throw IOException("WebDAV: $what failed (HTTP $code)")
    }

    override suspend fun read(): SyncFile? = withContext(Dispatchers.IO) {
        // Uncompressed: servers that gzip on the fly (Apache mod_deflate) change the ETag of the compressed copy, which then
        // never matches on write.
        val get = request(fileUrl).header("Cache-Control", "no-cache").header("Accept-Encoding", "identity").get().build()
        client.newCall(get).execute().use { r ->
            when {
                r.code == 404 -> null
                // Never overwrite an existing file without a strong compare-and-swap token.
                // Weak or absent tags cannot protect against changes by another device.
                r.isSuccessful -> {
                    val tag = strongTag(r.header("ETag"))
                        ?: throw IOException("WebDAV sync requires a strong ETag for its sync file")
                    SyncFile(r.body.byteStream().use { readSyncBytes(it, MAX_SYNC_FILE_BYTES) }, tag)
                }
                else -> fail(r.code, "reading the sync file")
            }
        }
    }

    override suspend fun write(bytes: ByteArray, previous: SyncFile?): Unit = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_SYNC_FILE_BYTES) throw IOException("Sync file is too large; it was not overwritten")
        if (previous != null && strongTag(previous.tag) == null)
            throw IOException("WebDAV sync requires a strong ETag for its sync file")
        fun put(): Int {
            val b = request(fileUrl).put(bytes.toRequestBody(JSON))
            when {
                previous?.tag != null -> b.header("If-Match", previous.tag)
                previous == null -> b.header("If-None-Match", "*")
            }
            return client.newCall(b.build()).execute().use { it.code }
        }
        var code = put()
        if (code == 404 || code == 409) {
            // The folder does not exist yet.
            mkcol()
            code = put()
        }
        when {
            code in 200..299 -> Unit
            code == 412 -> throw SyncConflictException()
            else -> fail(code, "writing the sync file")
        }
    }

    /** Book files use a separate manifest so older app versions cannot strip its entries. */
    override suspend fun readBookIndex(): SyncFile? = withContext(Dispatchers.IO) {
        val req = request(folder + BOOK_INDEX).header("Cache-Control", "no-cache").header("Accept-Encoding", "identity").get().build()
        client.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> fail(r.code, "reading the book index")
                else -> {
                    val tag = strongTag(r.header("ETag"))
                        ?: throw IOException("WebDAV book sync requires a strong ETag for its index")
                    val out = ByteArrayOutputStream()
                    r.body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (out.size().toLong() + n > MAX_INDEX_BYTES) throw IOException("Book sync index is too large")
                            out.write(buffer, 0, n)
                        }
                    }
                    SyncFile(out.toByteArray(), tag)
                }
            }
        }
    }

    override suspend fun writeBookIndex(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_INDEX_BYTES) throw IOException("Book sync index is too large")
        fun put(): Int {
            val b = request(folder + BOOK_INDEX).put(bytes.toRequestBody(JSON))
            if (previous == null) b.header("If-None-Match", "*")
            else b.header("If-Match", strongTag(previous.tag) ?: throw IOException("Missing strong ETag"))
            return client.newCall(b.build()).execute().use { it.code }
        }
        var code = put()
        if (code == 404 || code == 409) { mkcol(); code = put() }
        when {
            code in 200..299 -> Unit
            code == 412 -> throw SyncConflictException()
            else -> fail(code, "writing the book index")
        }
    }

    override suspend fun readFolderIndex(): SyncFile? = withContext(Dispatchers.IO) {
        val req = request(folder + FOLDER_INDEX).header("Cache-Control", "no-cache").header("Accept-Encoding", "identity").get().build()
        client.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> fail(r.code, "reading folder index")
                else -> {
                    val tag = strongTag(r.header("ETag"))
                        ?: throw IOException("WebDAV folder sync requires a strong ETag")
                    val out = ByteArrayOutputStream()
                    r.body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (out.size().toLong() + n > MAX_INDEX_BYTES) throw IOException("Folder sync file is too large")
                            out.write(buffer, 0, n)
                        }
                    }
                    SyncFile(out.toByteArray(), tag)
                }
            }
        }
    }

    override suspend fun writeFolderIndex(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_INDEX_BYTES) throw IOException("Folder sync file is too large")
        fun put(): Int {
            val b = request(folder + FOLDER_INDEX).put(bytes.toRequestBody(JSON))
            if (previous == null) b.header("If-None-Match", "*")
            else b.header("If-Match", strongTag(previous.tag) ?: throw IOException("Missing strong ETag"))
            return client.newCall(b.build()).execute().use { it.code }
        }
        var code = put()
        if (code == 404 || code == 409) { mkcol(); code = put() }
        when { code in 200..299 -> Unit; code == 412 -> throw SyncConflictException(); else -> fail(code, "writing folder index") }
    }

    override suspend fun readTrackLinks(): SyncFile? = withContext(Dispatchers.IO) {
        val req = request(folder + TRACK_INDEX).header("Cache-Control", "no-cache").header("Accept-Encoding", "identity").get().build()
        client.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> fail(r.code, "reading tracking links")
                else -> {
                    val tag = strongTag(r.header("ETag"))
                        ?: throw IOException("WebDAV tracking sync requires a strong ETag")
                    val out = ByteArrayOutputStream()
                    r.body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (out.size().toLong() + n > MAX_INDEX_BYTES) throw IOException("Tracking sync file is too large")
                            out.write(buffer, 0, n)
                        }
                    }
                    SyncFile(out.toByteArray(), tag)
                }
            }
        }
    }

    override suspend fun writeTrackLinks(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_INDEX_BYTES) throw IOException("Tracking sync file is too large")
        fun put(): Int {
            val b = request(folder + TRACK_INDEX).put(bytes.toRequestBody(JSON))
            if (previous == null) b.header("If-None-Match", "*")
            else b.header("If-Match", strongTag(previous.tag) ?: throw IOException("Missing strong ETag"))
            return client.newCall(b.build()).execute().use { it.code }
        }
        var code = put()
        if (code == 404 || code == 409) { mkcol(); code = put() }
        when { code in 200..299 -> Unit; code == 412 -> throw SyncConflictException(); else -> fail(code, "writing tracking links") }
    }

    /** Immutable, content-addressed blobs are never overwritten or removed. */
    override suspend fun uploadBook(sha256: String, file: File): Unit = withContext(Dispatchers.IO) {
        val url = bookUrl(sha256)
        fun put(): Int = client.newCall(request(url).header("If-None-Match", "*")
            .put(file.asRequestBody("application/octet-stream".toMediaType())).build()).execute().use { it.code }
        var code = put()
        if (code == 404 || code == 409) { mkcol(); code = put() }
        // A successful PUT only confirms acceptance, not that the server persisted the exact bytes.
        // Verify both newly uploaded and pre-existing blobs before a manifest can reference them.
        when {
            code in 200..299 || code == 412 ->
                readBook(sha256) { input -> BookFileSync.verifyStream(input, sha256, file.length()) }
            else -> fail(code, "uploading a book")
        }
    }

    override suspend fun <T> readBook(sha256: String, consume: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val req = request(bookUrl(sha256)).header("Accept-Encoding", "identity").get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) fail(r.code, "downloading a book")
            r.body.byteStream().use(consume)
        }
    }

    private fun bookUrl(sha256: String): String {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid book hash" }
        return folder + "yomu-book-$sha256.bin"
    }

    private fun mkcol() {
        val code = client.newCall(request(folder).method("MKCOL", null).build()).execute().use { it.code }
        if (code !in 200..299 && code != 405) fail(code, "creating the folder") // 405: it already exists
    }

    override suspend fun check(): Unit = withContext(Dispatchers.IO) {
        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
        val code = client.newCall(request(folder).header("Depth", "0").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()).execute().use { it.code }
        when {
            code == 207 || code in 200..299 -> Unit
            code == 404 -> mkcol()
            else -> fail(code, "connecting")
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val BOOK_INDEX = "yomu-books.json"
        const val TRACK_INDEX = "yomu-tracking.json"
        const val FOLDER_INDEX = "yomu-folders.json"
        const val MAX_INDEX_BYTES = 4 * 1024 * 1024
    }
}
