package app.yomu.data.tracking

import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import app.yomu.source.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * MangaUpdates v1 API (api.mangaupdates.com). Password sign-in exchanges credentials for a session
 * token once; the token is stored and sent as a Bearer header. Passwords are never saved.
 *
 * The protocol follows the service's own clients (shapes verified against Mihon's implementation):
 * list membership is a "list id" (0 Reading, 1 Wish, 2 Complete, 3 Unfinished, 4 On Hold), reading
 * progress travels as {"status": {"chapter": N}} and the rating lives on the series itself.
 * Live verification with a real account is still pending.
 */
class MangaUpdatesTracker(store: TrackerStore, keys: TrackerKeys, client: () -> OkHttpClient, private val baseUrl: String = "https://api.mangaupdates.com") :
    Tracker(TrackService.MANGAUPDATES, store, keys, client) {
    override val redirectHost = "mangaupdates-password-only"
    override val passwordLogin = true
    override fun authUrl(): String = throw TrackerException("MangaUpdates: password sign-in required")
    override suspend fun finishLogin(redirect: String): Unit = throw TrackerException("MangaUpdates: password sign-in required")

    override suspend fun loginWithPassword(username: String, password: String) {
        require(username.isNotBlank() && password.isNotEmpty()) { "MangaUpdates: username and password are required" }
        val body = buildJsonObject { put("username", username.trim()); put("password", password) }.toString().toRequestBody(JSON)
        // Never expose or log the login response: it echoes credentials.
        val response = client().newCall(Request.Builder().url("$baseUrl/v1/account/login").put(body).build()).await()
        response.use { r ->
            if (!r.isSuccessful) throw TrackerException("MangaUpdates: sign-in failed (HTTP ${r.code})", unauthorized = r.code == 401, code = r.code)
            val value = try { json.parseToJsonElement(r.body.string()) } catch (_: Exception) { throw TrackerException("MangaUpdates: invalid sign-in response") }
            val context = value["context"].obj() ?: throw TrackerException("MangaUpdates: invalid sign-in response")
            val token = context["session_token"].str()?.takeIf { it.isNotBlank() } ?: throw TrackerException("MangaUpdates: missing session token")
            val uid = context["uid"].long()?.takeIf { it > 0 } ?: throw TrackerException("MangaUpdates: invalid account ID")
            val profile = runCatching { profileRequest(token) }.getOrNull()
            store.saveAccount(service, token, null, 0, uid, profile)
        }
    }

    private suspend fun profileRequest(token: String): String? {
        val text = execute(Request.Builder().url("$baseUrl/v1/account/profile").header("Authorization", "Bearer $token").get().build(), allowNotFound = true)
        return text?.let { json.parseToJsonElement(it)["username"].str() }
    }

    private suspend fun send(request: Request): String? {
        val used = token()
        return try { execute(request.newBuilder().header("Authorization", "Bearer $used").build()) }
        catch (e: TrackerException) { if (!e.unauthorized) throw e; throw TrackerException("${service.displayName}: sign in again", unauthorized = true, cause = e, code = 401) }
    }

    private suspend fun postList(path: String, body: String): String? =
        send(Request.Builder().url("$baseUrl/v1/lists/$path").post(body.toRequestBody(JSON)).build())

    override suspend fun search(query: String): List<TrackSearchResult> {
        val body = buildJsonObject {
            put("search", query.take(100))
            put("filter_types", buildJsonArray { add("drama cd"); add("novel") })
        }.toString().toRequestBody(JSON)
        val text = execute(Request.Builder().url("$baseUrl/v1/series/search").post(body).build()) ?: return emptyList()
        val root = json.parseToJsonElement(text)
        val results = root["results"] as? JsonArray ?: throw TrackerException("MangaUpdates: invalid search response")
        return results.mapNotNull { hit ->
            val record = hit["record"].obj() ?: return@mapNotNull null
            val id = record["series_id"].long()?.takeIf { it > 0 } ?: return@mapNotNull null
            val title = record["title"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TrackSearchResult(
                id, title,
                coverUrl = record["image"]["url"]["original"].str(),
                url = record["url"].str() ?: "https://www.mangaupdates.com/series.html?id=$id",
                format = record["type"].str(),
                totalChapters = record["latest_chapter"].int() ?: 0,
                year = record["year"].str()?.take(4)?.toIntOrNull(),
                description = record["description"].str(),
            )
        }
    }

    override suspend fun find(remoteId: Long): TrackEntry? {
        require(remoteId > 0)
        val text = execute(Request.Builder().url("$baseUrl/v1/lists/series/$remoteId").get().build(), allowNotFound = true) ?: return null
        val item = json.parseToJsonElement(text)
        val listId = item["list_id"].long() ?: return null // not in any list yet
        val rating = runCatching {
            execute(Request.Builder().url("$baseUrl/v1/series/$remoteId/rating").get().build(), allowNotFound = true)
                ?.let { json.parseToJsonElement(it)["rating"].float() } ?: 0f
        }.getOrDefault(0f)
        return parseEntry(item, remoteId, listId).copy(score = rating.coerceIn(0f, 10f))
    }

    private fun parseEntry(item: kotlinx.serialization.json.JsonElement, remoteId: Long, listId: Long): TrackEntry {
        val chapter = item["status"]["chapter"].float()?.coerceAtLeast(0f) ?: 0f
        val trackStatus = when (listId) {
            LIST_READING -> TrackStatus.READING
            LIST_WISH -> TrackStatus.PLAN_TO_READ
            LIST_COMPLETE -> TrackStatus.COMPLETED
            LIST_UNFINISHED -> TrackStatus.DROPPED
            LIST_ON_HOLD -> TrackStatus.ON_HOLD
            else -> throw TrackerException("MangaUpdates: unsupported list")
        }
        return TrackEntry(service, remoteId, libraryId = 0, title = "", url = "", trackStatus, 0f, chapter)
    }

    override suspend fun save(entry: TrackEntry): TrackEntry {
        require(entry.remoteId > 0)
        val existing = find(entry.remoteId)
        val listId = when (entry.status) {
            TrackStatus.READING, TrackStatus.REREADING -> LIST_READING
            TrackStatus.PLAN_TO_READ -> LIST_WISH
            TrackStatus.COMPLETED -> LIST_COMPLETE
            TrackStatus.DROPPED -> LIST_UNFINISHED
            TrackStatus.ON_HOLD -> LIST_ON_HOLD
        }
        if (existing == null) {
            postList("series", buildJsonArray {
                addJsonObject { putJsonObject("series") { put("id", entry.remoteId) }; put("list_id", listId) }
            }.toString())
        }
        // Reading progress and the list move together; the rating is a separate series-level value.
        postList("series/update", buildJsonArray {
            addJsonObject {
                putJsonObject("series") { put("id", entry.remoteId) }
                put("list_id", listId)
                putJsonObject("status") { put("chapter", entry.lastChapterRead.toInt().coerceAtLeast(0)) }
            }
        }.toString())
        if (entry.score > 0f) {
            send(Request.Builder().url("$baseUrl/v1/series/${entry.remoteId}/rating")
                .put(buildJsonObject { put("rating", entry.score) }.toString().toRequestBody(JSON)).build())
        } else {
            execute(Request.Builder().url("$baseUrl/v1/series/${entry.remoteId}/rating").delete().build(), allowNotFound = true)
        }
        return entry
    }

    override suspend fun delete(entry: TrackEntry) {
        postList("series/delete", buildJsonArray { add(entry.remoteId) }.toString())
        execute(Request.Builder().url("$baseUrl/v1/series/${entry.remoteId}/rating").delete().build(), allowNotFound = true)
    }

    companion object {
        const val LIST_READING = 0L
        const val LIST_WISH = 1L
        const val LIST_COMPLETE = 2L
        const val LIST_UNFINISHED = 3L
        const val LIST_ON_HOLD = 4L
        private val JSON = "application/json".toMediaType()
    }
}
