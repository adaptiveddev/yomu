package app.yomu.data.tracking

import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/** AniList: GraphQL API, implicit OAuth grant (the token lives a year). */
class AniListTracker(
    store: TrackerStore,
    keys: TrackerKeys,
    client: () -> OkHttpClient,
    private val apiUrl: String = "https://graphql.anilist.co",
) : Tracker(TrackService.ANILIST, store, keys, client) {
    override val redirectHost = "anilist-auth"

    override fun authUrl() = "https://anilist.co/api/v2/oauth/authorize?client_id=${URLEncoder.encode(clientId(), "UTF-8")}&response_type=token"

    override suspend fun finishLogin(redirect: String) {
        val p = redirectParams(redirect)
        val token = p["access_token"] ?: throw TrackerException("AniList: ${p["error_description"] ?: p["error"] ?: "no token in the answer"}")
        val expires = p["expires_in"]?.toLongOrNull()?.let { System.currentTimeMillis() + it * 1000 } ?: 0
        store.saveTokens(service, token, null, expires)
        try {
            val viewer = query("query { Viewer { id name } }")["Viewer"]
            store.saveUser(service, viewer["id"].long() ?: 0, viewer["name"].str())
        } catch (e: Exception) {
            store.logout(service)
            throw e
        }
    }

    private suspend fun query(q: String, variables: JsonObject = JsonObject(emptyMap()), authorized: Boolean = true): JsonElement? {
        val body = buildJsonObject {
            put("query", q)
            put("variables", variables)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder().url(apiUrl).post(body)
            .header("Accept", "application/json")
            .apply { if (authorized) header("Authorization", "Bearer ${token()}") }
            .build()
        val text = execute(request, allowNotFound = true) ?: return null
        val root = json.parseToJsonElement(text)
        val errors = root["errors"].list()
        val data = root["data"]
        if (errors.isNotEmpty()) {
            // "Not Found" for a title that is not in the user's list; any other error is real.
            val notFound = errors.all { it["status"].int() == 404 }
            if (!notFound) throw TrackerException("AniList: " + errors.joinToString("; ") { it["message"].str().orEmpty() })
        }
        return data
    }

    override suspend fun search(query: String): List<TrackSearchResult> {
        val q = """
            query Search(${'$'}query: String) {
              Page(perPage: 30) {
                media(search: ${'$'}query, type: MANGA) {
                  id title { userPreferred romaji english native } coverImage { large } format status chapters
                  description(asHtml: false) startDate { year } siteUrl
                }
              }
            }
        """.trimIndent()
        val data = query(q, buildJsonObject { put("query", query) }, authorized = loggedIn())
        return data["Page"]["media"].list().mapNotNull { m ->
            val id = m["id"].long() ?: return@mapNotNull null
            val title = m["title"]
            val main = title["userPreferred"].str() ?: title["romaji"].str() ?: return@mapNotNull null
            TrackSearchResult(
                remoteId = id,
                title = main,
                altTitle = listOfNotNull(title["english"].str(), title["native"].str()).firstOrNull { it != main },
                coverUrl = m["coverImage"]["large"].str(),
                url = m["siteUrl"].str() ?: "https://anilist.co/manga/$id",
                format = m["format"].str(),
                publishing = m["status"].str(),
                totalChapters = m["chapters"].int() ?: 0,
                year = m["startDate"]["year"].int(),
                description = m["description"].str()?.replace(Regex("<[^>]+>"), "")?.trim(),
            )
        }
    }

    override suspend fun find(remoteId: Long): TrackEntry? {
        val q = """
            query Entry(${'$'}mediaId: Int, ${'$'}userId: Int) {
              MediaList(userId: ${'$'}userId, mediaId: ${'$'}mediaId, type: MANGA) { $ENTRY_FIELDS }
            }
        """.trimIndent()
        val data = query(q, buildJsonObject { put("mediaId", remoteId); put("userId", store.userId(service)) })
        return data["MediaList"]?.let { entry(it) }
    }

    override suspend fun save(entry: TrackEntry): TrackEntry {
        val q = """
            mutation Save(${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}progress: Int, ${'$'}score: Int, ${'$'}startedAt: FuzzyDateInput, ${'$'}completedAt: FuzzyDateInput) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress, scoreRaw: ${'$'}score, startedAt: ${'$'}startedAt, completedAt: ${'$'}completedAt) { $ENTRY_FIELDS }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("mediaId", entry.remoteId)
            put("status", statusName(entry.status))
            put("progress", entry.lastChapterRead.toInt())
            put("score", (entry.score * 10).toInt().coerceIn(0, 100))
            putJsonObject("startedAt") { fuzzy(entry.startedAt) }
            putJsonObject("completedAt") { fuzzy(entry.finishedAt) }
        }
        val saved = query(q, vars)["SaveMediaListEntry"] ?: throw TrackerException("AniList: the entry was not saved")
        return entry(saved)
    }

    override suspend fun delete(entry: TrackEntry) {
        val id = entry.libraryId.takeIf { it > 0 } ?: find(entry.remoteId)?.libraryId ?: return
        query("mutation Delete(\$id: Int) { DeleteMediaListEntry(id: \$id) { deleted } }", buildJsonObject { put("id", id) })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.fuzzy(millis: Long) {
        val d = dayOf(millis)
        if (d == null) {
            put("year", JsonNull); put("month", JsonNull); put("day", JsonNull)
        } else {
            put("year", d.year); put("month", d.monthValue); put("day", d.dayOfMonth)
        }
    }

    private fun entry(e: JsonElement): TrackEntry {
        val media = e["media"]
        val mediaId = media["id"].long() ?: 0
        return TrackEntry(
            service = service,
            remoteId = mediaId,
            libraryId = e["id"].long() ?: 0,
            title = media["title"]["userPreferred"].str().orEmpty(),
            url = media["siteUrl"].str() ?: "https://anilist.co/manga/$mediaId",
            status = status(e["status"].str()),
            score = (e["score"].float() ?: 0f) / 10f,
            lastChapterRead = (e["progress"].int() ?: 0).toFloat(),
            totalChapters = media["chapters"].int() ?: 0,
            startedAt = date(e["startedAt"]),
            finishedAt = date(e["completedAt"]),
        )
    }

    private fun date(d: JsonElement?): Long {
        val y = d["year"].int() ?: return 0
        return millisOf(java.time.LocalDate.of(y, d["month"].int() ?: 1, d["day"].int() ?: 1))
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val ENTRY_FIELDS = "id status progress score(format: POINT_100) startedAt { year month day } completedAt { year month day } " +
            "media { id title { userPreferred } chapters siteUrl }"

        fun statusName(s: TrackStatus) = when (s) {
            TrackStatus.READING -> "CURRENT"
            TrackStatus.PLAN_TO_READ -> "PLANNING"
            TrackStatus.COMPLETED -> "COMPLETED"
            TrackStatus.ON_HOLD -> "PAUSED"
            TrackStatus.DROPPED -> "DROPPED"
            TrackStatus.REREADING -> "REPEATING"
        }

        fun status(name: String?) = when (name) {
            "PLANNING" -> TrackStatus.PLAN_TO_READ
            "COMPLETED" -> TrackStatus.COMPLETED
            "PAUSED" -> TrackStatus.ON_HOLD
            "DROPPED" -> TrackStatus.DROPPED
            "REPEATING" -> TrackStatus.REREADING
            else -> TrackStatus.READING
        }
    }
}
