package app.yomu.data.backup

import kotlinx.coroutines.Dispatchers
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
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

/**
 * Sync through a (private) GitHub repository: the shared files are stored at the root of the repository's default
 * branch with the Contents API. The blob SHA of a file is its version tag: GitHub refuses an update whose SHA is not
 * the current one, which gives the same compare-and-swap as a WebDAV ETag. Book files are not copied (a repository
 * is no place for large binaries).
 */
class GitHubSyncBackend(
    private val client: OkHttpClient,
    private val token: String,
    repository: String,
    private val api: String = API,
) : SyncBackend, TrackLinkTransport {
    private val repo: String = parseRepository(repository) ?: throw IOException("Invalid GitHub repository: use owner/name")
    override val id: String = "github:${repo.lowercase()}"

    private fun request(path: String): Request.Builder = authorized(Request.Builder().url("$api/repos/$repo$path"), token)

    private fun contentsPath(name: String) = "/contents/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun readNamed(name: String, limit: Int): SyncFile? {
        val req = request(contentsPath(name)).header("Cache-Control", "no-cache").get().build()
        val meta = client.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> return null
                !r.isSuccessful -> fail(r.code, "reading $name")
                else -> json.parseToJsonElement(r.body.string()).jsonObject
            }
        }
        if (meta["type"]?.jsonPrimitive?.content != "file") throw IOException("$name in the repository is not a file")
        val sha = meta["sha"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: throw IOException("GitHub did not report the version of $name")
        val size = meta["size"]?.jsonPrimitive?.longOrNull ?: 0
        if (size > limit) throw IOException("Sync file is too large; it was not overwritten")
        val encoded = meta["content"]?.jsonPrimitive?.content.orEmpty()
        val bytes = if (meta["encoding"]?.jsonPrimitive?.content == "base64" && (encoded.isNotEmpty() || size == 0L)) {
            Base64.getMimeDecoder().decode(encoded)
        } else {
            // Files over 1 MB come without content: their blob is read raw, by the same SHA.
            val blob = request("/git/blobs/$sha").header("Accept", "application/vnd.github.raw+json").get().build()
            client.newCall(blob).execute().use { r ->
                if (!r.isSuccessful) fail(r.code, "reading $name")
                r.body.byteStream().use { readSyncBytes(it, limit) }
            }
        }
        if (bytes.size > limit) throw IOException("Sync file is too large; it was not overwritten")
        return SyncFile(bytes, sha)
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
        val code = client.newCall(req).execute().use { it.code }
        when {
            code in 200..299 -> Unit
            // 409: the SHA is not the current one; 422 without a SHA: someone created the file meanwhile.
            code == 409 || (code == 422 && previous == null) -> throw SyncConflictException()
            else -> fail(code, "writing $name")
        }
    }

    override suspend fun read(): SyncFile? = withContext(Dispatchers.IO) { readNamed(SyncManager.FILE_NAME, MAX_SYNC_FILE_BYTES) }
    override suspend fun write(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) { writeNamed(SyncManager.FILE_NAME, bytes, previous, MAX_SYNC_FILE_BYTES) }
    override suspend fun readTrackLinks(): SyncFile? = withContext(Dispatchers.IO) { readNamed(TRACK_INDEX, MAX_INDEX_BYTES) }
    override suspend fun writeTrackLinks(bytes: ByteArray, previous: SyncFile?) = withContext(Dispatchers.IO) { writeNamed(TRACK_INDEX, bytes, previous, MAX_INDEX_BYTES) }

    /** The repository exists, the token can see it and may write to it. */
    override suspend fun check(): Unit = withContext(Dispatchers.IO) {
        val info = client.newCall(request("").get().build()).execute().use { r ->
            when {
                r.code == 404 -> throw IOException("Repository $repo was not found or the token has no access to it")
                !r.isSuccessful -> fail(r.code, "connecting")
                else -> json.parseToJsonElement(r.body.string()).jsonObject
            }
        }
        val push = (info["permissions"] as? JsonObject)?.get("push")?.jsonPrimitive?.booleanOrNull
        if (push == false) throw IOException("The token cannot write to $repo")
    }

    companion object {
        const val API = "https://api.github.com"
        const val DEFAULT_REPOSITORY = "yomu-sync"
        private const val TRACK_INDEX = "yomu-tracking.json"
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

        private fun fail(code: Int, what: String): Nothing = throw IOException(
            when (code) {
                401 -> "GitHub rejected the token ($what)"
                403 -> "GitHub refused access or the rate limit was reached ($what)"
                else -> "GitHub error $code while $what"
            },
        )

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
                        r.code != 422 -> fail(r.code, "creating the repository")
                    }
                }
                // 422: the name is taken, most likely by an earlier run. Use it if it belongs to this account.
                val user = client.newCall(authorized(Request.Builder().url("$api/user"), token).get().build()).execute().use { r ->
                    if (!r.isSuccessful) fail(r.code, "reading the account")
                    json.parseToJsonElement(r.body.string()).jsonObject["login"]?.jsonPrimitive?.content
                        ?: throw IOException("GitHub did not return the account name")
                }
                val full = "$user/$repoName"
                GitHubSyncBackend(client, token, full, api).check()
                full
            }
    }
}
