package app.yomu.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.Base64

/**
 * Full sync through a (private) GitHub repository: the shared files are stored in the default branch with the
 * Contents API. The blob SHA of a file is its version tag: GitHub refuses an update whose SHA is not the current one,
 * which gives the same compare-and-swap as a WebDAV ETag. The sync file and the indexes (`yomu-books.json`,
 * `yomu-folders.json`, `yomu-tracking.json`) live at the root. Book files and image folders are content-addressed,
 * immutable blobs `books/yomu-book-<sha256>.bin`: never overwritten or deleted, verified by reading them back.
 * Uploads stream the file into a base64 JSON body (no whole-file buffer); downloads stream the raw git blob.
 * GitHub rejects files over 100 MiB, so a larger book is refused with a clear error (a per-book problem only).
 */
class GitHubSyncBackend(
    private val client: OkHttpClient,
    private val token: String,
    repository: String,
    private val api: String = API,
    private val maxBookBytes: Long = MAX_BOOK_BYTES,
) : SyncBackend, TrackLinkTransport, FolderFilesTransport {
    private val repo: String = parseRepository(repository) ?: throw IOException("Invalid GitHub repository: use owner/name")
    override val id: String = "github:${repo.lowercase()}"

    private fun request(path: String): Request.Builder = authorized(Request.Builder().url("$api/repos/$repo$path"), token)

    private fun contentsPath(name: String) =
        "/contents/" + name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private class Meta(val sha: String, val size: Long, val inline: ByteArray?)

    private fun meta(name: String): Meta? {
        val req = request(contentsPath(name)).header("Cache-Control", "no-cache").get().build()
        val meta = client.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> return null
                !r.isSuccessful -> fail(r, "reading $name")
                else -> json.parseToJsonElement(r.body.string()).jsonObject
            }
        }
        if (meta["type"]?.jsonPrimitive?.content != "file") throw IOException("$name in the repository is not a file")
        val sha = meta["sha"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: throw IOException("GitHub did not report the version of $name")
        val size = meta["size"]?.jsonPrimitive?.longOrNull ?: 0
        val encoded = meta["content"]?.jsonPrimitive?.content.orEmpty()
        val inline = if (meta["encoding"]?.jsonPrimitive?.content == "base64" && (encoded.isNotEmpty() || size == 0L)) {
            Base64.getMimeDecoder().decode(encoded)
        } else null
        return Meta(sha, size, inline)
    }

    /** Files over 1 MB come without content: their blob is streamed raw, by the same SHA. */
    private fun <T> streamBlob(sha: String, name: String, consume: (InputStream) -> T): T {
        val blob = request("/git/blobs/$sha").header("Accept", "application/vnd.github.raw+json").get().build()
        return client.newCall(blob).execute().use { r ->
            if (!r.isSuccessful) fail(r, "reading $name")
            r.body.byteStream().use(consume)
        }
    }

    private fun readNamed(name: String, limit: Int): SyncFile? {
        val m = meta(name) ?: return null
        if (m.size > limit) throw IOException("Sync file is too large; it was not overwritten")
        val bytes = m.inline ?: streamBlob(m.sha, name) { readSyncBytes(it, limit) }
        if (bytes.size > limit) throw IOException("Sync file is too large; it was not overwritten")
        return SyncFile(bytes, m.sha)
    }

    private fun writeNamed(name: String, bytes: ByteArray, previous: SyncFile?, limit: Int) {
        if (bytes.size > limit) throw IOException("Sync file is too large; it was not overwritten")
        if (previous != null && previous.tag.isNullOrBlank()) throw IOException("Missing version of $name")
        val body = buildJsonObject {
            put("message", "Yomu sync: $name")
            put("content", Base64.getEncoder().encodeToString(bytes))
            previous?.tag?.let { put("sha", it) }
        }
        val req = request(contentsPath(name)).put(body.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { r ->
            when {
                r.isSuccessful -> Unit
                // 409: the SHA is not the current one; 422 without a SHA: someone created the file meanwhile.
                r.code == 409 || (r.code == 422 && previous == null) -> throw SyncConflictException()
                else -> fail(r, "writing $name")
            }
        }
    }

    // Before anything is read or written the repository is checked (private, writable), once per backend instance.
    @Volatile private var checked = false

    private suspend fun ensureChecked() {
        if (!checked) { check(); checked = true }
    }

    override suspend fun read(): SyncFile? = withContext(Dispatchers.IO) {
        ensureChecked()
        readNamed(SyncManager.FILE_NAME, MAX_SYNC_FILE_BYTES)
    }
    override suspend fun write(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) { ensureChecked(); writeNamed(SyncManager.FILE_NAME, bytes, previous, MAX_SYNC_FILE_BYTES) }
    override suspend fun readTrackLinks(): SyncFile? = withContext(Dispatchers.IO) { readNamed(TRACK_INDEX, MAX_INDEX_BYTES) }
    override suspend fun writeTrackLinks(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) { ensureChecked(); writeNamed(TRACK_INDEX, bytes, previous, MAX_INDEX_BYTES) }

    override suspend fun readBookIndex(): SyncFile? = withContext(Dispatchers.IO) { readNamed(BOOK_INDEX, MAX_INDEX_BYTES) }
    override suspend fun writeBookIndex(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) { ensureChecked(); writeNamed(BOOK_INDEX, bytes, previous, MAX_INDEX_BYTES) }
    override suspend fun readFolderIndex(): SyncFile? = withContext(Dispatchers.IO) { readNamed(FOLDER_INDEX, MAX_INDEX_BYTES) }
    override suspend fun writeFolderIndex(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) { ensureChecked(); writeNamed(FOLDER_INDEX, bytes, previous, MAX_INDEX_BYTES) }

    private fun blobName(sha256: String): String {
        require(sha256.matches(SHA256)) { "Invalid book hash" }
        return "$BOOK_DIR/yomu-book-$sha256.bin"
    }

    override suspend fun <T> readBook(sha256: String, consume: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val name = blobName(sha256)
        val m = meta(name) ?: throw IOException("Book file is missing from the GitHub repository")
        m.inline?.let { ByteArrayInputStream(it).use(consume) } ?: streamBlob(m.sha, name, consume)
    }

    /** Immutable, content-addressed blobs are never overwritten or removed; existing ones are only verified. */
    override suspend fun uploadBook(sha256: String, file: File): Unit = withContext(Dispatchers.IO) {
        val name = blobName(sha256)
        val size = file.length()
        if (size > maxBookBytes) throw IOException("The book is larger than the ${maxBookBytes / (1024 * 1024)} MB per-file limit of GitHub")
        ensureChecked()
        if (meta(name) == null) {
            // Every Contents write is a commit on the branch: one racing with another device's commit gets a 409 even
            // though this path is new. Such a race clears on its own, so it is retried a few times.
            var attempt = 0
            while (true) {
                val req = request(contentsPath(name)).put(Base64JsonBody("Yomu sync: book $sha256", file)).build()
                val retry = client.newCall(req).execute().use { r ->
                    when {
                        // 422: the same blob was created meanwhile; it is verified below like any existing one.
                        r.isSuccessful || r.code == 422 -> false
                        r.code == 409 && ++attempt < UPLOAD_ATTEMPTS -> true
                        else -> fail(r, "uploading a book")
                    }
                }
                if (!retry) break
                delay(attempt * 1_000L)
            }
        }
        readBook(sha256) { input -> BookFileSync.verifyStream(input, sha256, size) }
    }

    /** `{"message":..,"content":"<base64 of the file>"}` streamed from disk with a known length. */
    private class Base64JsonBody(message: String, private val file: File) : RequestBody() {
        private val head = buildJsonObject { put("message", message) }.toString().removeSuffix("}") + ",\"content\":\""
        private val tail = "\"}"
        private val length = file.length()
        override fun contentType() = JSON
        override fun contentLength() = head.length + (length + 2) / 3 * 4 + tail.length
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(head)
            val out = object : FilterOutputStream(sink.outputStream()) {
                override fun write(b: ByteArray, off: Int, len: Int) = this.out.write(b, off, len)
                override fun close() = flush() // the sink stays open for the tail
            }
            Base64.getEncoder().wrap(out).use { enc -> file.inputStream().use { it.copyTo(enc) } }
            sink.writeUtf8(tail)
        }
    }

    /** The repository exists, is private, the token can see it and may write to it. */
    override suspend fun check(): Unit = withContext(Dispatchers.IO) {
        val info = client.newCall(request("").get().build()).execute().use { r ->
            when {
                r.code == 404 -> throw IOException("Repository $repo was not found or the token has no access to it")
                !r.isSuccessful -> fail(r, "connecting")
                else -> json.parseToJsonElement(r.body.string()).jsonObject
            }
        }
        // Reading positions, notes, words and book files must never land in a public repository.
        if (info["private"]?.jsonPrimitive?.booleanOrNull != true) throw IOException("Repository $repo is public: make it private on GitHub or choose another one")
        val push = (info["permissions"] as? JsonObject)?.get("push")?.jsonPrimitive?.booleanOrNull
        if (push == false) throw IOException("The token cannot write to $repo")
    }

    companion object {
        const val API = "https://api.github.com"
        const val DEFAULT_REPOSITORY = "yomu-sync"
        private const val TRACK_INDEX = "yomu-tracking.json"
        const val BOOK_DIR = "books"

        /** GitHub refuses files over 100 MiB. */
        const val MAX_BOOK_BYTES = 100L * 1024 * 1024
        private const val UPLOAD_ATTEMPTS = 3
        private const val BOOK_INDEX = "yomu-books.json"
        private const val FOLDER_INDEX = "yomu-folders.json"
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val MAX_INDEX_BYTES = 4 * 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
        private val PART = Regex("[A-Za-z0-9_.-]+")

        private fun authorized(b: Request.Builder, token: String): Request.Builder {
            if (token.isBlank()) throw IOException("The GitHub token is not set")
            return b.header("Authorization", "Bearer ${token.trim()}")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
        }

        /** `owner/name` from `owner/name`, `github.com/owner/name` or a clone address; null when it is not one. */
        fun parseRepository(input: String): String? {
            val path = input.trim()
                .removePrefix("https://").removePrefix("http://").removePrefix("git@")
                .removePrefix("www.").removePrefix("github.com/").removePrefix("github.com:")
                .removeSuffix("/").removeSuffix(".git")
            val parts = path.split('/')
            if (parts.size != 2 || parts.any { !it.matches(PART) || it == "." || it == ".." }) return null
            return parts.joinToString("/")
        }

        private fun fail(r: Response, what: String): Nothing {
            val limited = r.code == 429 || (r.code == 403 && (r.header("X-RateLimit-Remaining") == "0" || r.header("Retry-After") != null))
            throw IOException(
                when {
                    limited -> "GitHub rate limit reached; sync will continue later ($what)"
                    r.code == 401 -> "GitHub rejected the token: it is wrong or expired ($what)"
                    r.code == 403 -> "The GitHub token has no permission for this ($what)"
                    else -> "GitHub error ${r.code} while $what"
                },
            )
        }

        /**
         * Creates a private repository for the sync under the token's account and returns its `owner/name`. When the
         * account already has a repository with that name, that one is used.
         */
        suspend fun createRepository(client: OkHttpClient, token: String, name: String = DEFAULT_REPOSITORY, api: String = API): String =
            withContext(Dispatchers.IO) {
                val repoName = name.trim().ifEmpty { DEFAULT_REPOSITORY }
                if (!repoName.matches(PART)) throw IOException("Invalid repository name: $repoName")
                val body = buildJsonObject {
                    put("name", repoName)
                    put("private", true)
                    put("auto_init", true)
                    put("description", "Yomu sync data")
                }
                val create = authorized(Request.Builder().url("$api/user/repos"), token).post(body.toString().toRequestBody(JSON)).build()
                client.newCall(create).execute().use { r ->
                    when {
                        r.isSuccessful -> return@withContext json.parseToJsonElement(r.body.string()).jsonObject["full_name"]?.jsonPrimitive?.content
                            ?: throw IOException("GitHub did not return the new repository")
                        r.code == 403 || r.code == 404 -> throw IOException(
                            "The GitHub token cannot create repositories: create a private repository on GitHub and enter it, or give the token the Administration permission",
                        )
                        r.code != 422 -> fail(r, "creating the repository")
                    }
                }
                // 422: the name is taken, most likely by an earlier run. Use it if it belongs to this account.
                val user = client.newCall(authorized(Request.Builder().url("$api/user"), token).get().build()).execute().use { r ->
                    if (!r.isSuccessful) fail(r, "reading the account")
                    json.parseToJsonElement(r.body.string()).jsonObject["login"]?.jsonPrimitive?.content
                        ?: throw IOException("GitHub did not return the account name")
                }
                val full = "$user/$repoName"
                GitHubSyncBackend(client, token, full, api).check()
                full
            }
    }
}
