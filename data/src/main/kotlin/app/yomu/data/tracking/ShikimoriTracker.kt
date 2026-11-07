package app.yomu.data.tracking

import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Shikimori: REST API, OAuth authorization code with the application secret; tokens live a day and are refreshed.
 * Manga and ranobe (light novels) are both "Manga" in the user's rates.
 */
class ShikimoriTracker(
    store: TrackerStore,
    keys: TrackerKeys,
    client: () -> OkHttpClient,
    private val baseUrl: String = "https://shikimori.io",
) : Tracker(TrackService.SHIKIMORI, store, keys, client) {
    override val redirectHost = "shikimori-auth"
    override val needsSecret = true

    override fun authUrl(): String = "$baseUrl/oauth/authorize".toHttpUrl().newBuilder()
        .addQueryParameter("client_id", clientId())
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("scope", "user_rates")
        .build().toString()

    override suspend fun finishLogin(redirect: String) {
        val p = redirectParams(redirect)
        val code = p["code"] ?: throw TrackerException("Shikimori: ${p["error_description"] ?: p["error"] ?: "no code in the answer"}")
        tokenRequest(FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("redirect_uri", redirectUri))
        try {
            val me = json.parseToJsonElement(get("$baseUrl/api/users/whoami")!!)
            store.saveUser(service, me["id"].long() ?: 0, me["nickname"].str())
        } catch (e: Exception) {
            store.logout(service)
            throw e
        }
    }

    private suspend fun tokenRequest(form: FormBody.Builder) {
        val body = form.add("client_id", clientId()).add("client_secret", clientSecret()).build()
        val text = execute(Request.Builder().url("$baseUrl/oauth/token").header("User-Agent", USER_AGENT).post(body).build())!!
        val t = json.parseToJsonElement(text)
        val access = t["access_token"].str() ?: throw TrackerException("Shikimori: no token in the answer")
        val expires = t["expires_in"].long()?.let { System.currentTimeMillis() + it * 1000 } ?: 0
        store.saveTokens(service, access, t["refresh_token"].str(), expires)
    }

    /** Renews the access token, unless a request made at the same time already did (see [refreshOnce]). */
    private suspend fun refresh(staleToken: String?) = refreshOnce(staleToken) {
        val refresh = store.refreshToken(service) ?: throw TrackerException("Shikimori: sign in again", unauthorized = true)
        try {
            tokenRequest(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh))
        } catch (e: TrackerException) {
            // A server error or a broken answer is tried again later; only a refused refresh token signs out.
            if (!e.refused) throw e
            throw TrackerException("Shikimori: sign in again", unauthorized = true, cause = e)
        }
    }

    private suspend fun send(spec: Request.Builder.() -> Request.Builder, allowNotFound: Boolean = false, authorized: Boolean = true): String? {
        val expires = store.expiresAt(service)
        if (authorized && expires > 0 && expires < System.currentTimeMillis() + 60_000) refresh(store.token(service))
        val used = store.token(service)
        fun build() = Request.Builder().header("User-Agent", USER_AGENT).apply { if (authorized) header("Authorization", "Bearer ${token()}") }.spec().build()
        val first = runCatching { execute(build(), allowNotFound) }
        val e = first.exceptionOrNull()
        if (e is CancellationException) throw e
        if (authorized && e is TrackerException && e.unauthorized && store.refreshToken(service) != null) {
            refresh(used)
            return execute(build(), allowNotFound)
        }
        return first.getOrThrow()
    }

    private suspend fun get(url: String, allowNotFound: Boolean = false, authorized: Boolean = true) = send({ url(url).get() }, allowNotFound, authorized)

    override suspend fun search(query: String): List<TrackSearchResult> = coroutineScope {
        val q = query.take(100)
        val lists = listOf("mangas", "ranobe").map { kind ->
            async {
                val url = "$baseUrl/api/$kind".toHttpUrl().newBuilder().addQueryParameter("search", q).addQueryParameter("limit", "20").build().toString()
                runCatching { json.parseToJsonElement(get(url, authorized = false)!!).list() }.getOrDefault(emptyList())
            }
        }.map { it.await() }
        lists.flatten().mapNotNull { m ->
            val id = m["id"].long() ?: return@mapNotNull null
            val russian = m["russian"].str()?.ifBlank { null }
            val name = m["name"].str().orEmpty()
            TrackSearchResult(
                remoteId = id,
                title = russian ?: name,
                altTitle = name.takeIf { russian != null && it.isNotBlank() },
                coverUrl = m["image"]["original"].str()?.let(::absolute),
                url = absolute(m["url"].str() ?: "/mangas/$id"),
                format = m["kind"].str(),
                publishing = m["status"].str(),
                totalChapters = m["chapters"].int() ?: 0,
                year = m["aired_on"].str()?.take(4)?.toIntOrNull(),
            )
        }.distinctBy { it.remoteId }
    }

    /** Title and chapter count; ranobe ids answer on their own endpoint. */
    private suspend fun details(remoteId: Long): JsonElement? {
        for (kind in listOf("mangas", "ranobe")) {
            val text = runCatching { get("$baseUrl/api/$kind/$remoteId", allowNotFound = true, authorized = false) }.getOrNull() ?: continue
            return json.parseToJsonElement(text)
        }
        return null
    }

    override suspend fun find(remoteId: Long): TrackEntry? {
        val url = "$baseUrl/api/v2/user_rates".toHttpUrl().newBuilder()
            .addQueryParameter("user_id", store.userId(service).toString())
            .addQueryParameter("target_id", remoteId.toString())
            .addQueryParameter("target_type", "Manga")
            .build().toString()
        val rate = json.parseToJsonElement(get(url)!!).list().firstOrNull() ?: return null
        val d = details(remoteId)
        return entry(rate, remoteId, d)
    }

    override suspend fun save(entry: TrackEntry): TrackEntry {
        val body = buildJsonObject {
            putJsonObject("user_rate") {
                if (entry.libraryId == 0L) {
                    put("user_id", store.userId(service))
                    put("target_id", entry.remoteId)
                    put("target_type", "Manga")
                }
                put("status", statusName(entry.status))
                put("chapters", entry.lastChapterRead.toInt())
                put("score", entry.score.toInt().coerceIn(0, 10))
            }
        }.toString().toRequestBody(JSON)
        val text = if (entry.libraryId == 0L) {
            send({ url("$baseUrl/api/v2/user_rates").post(body) })!!
        } else {
            send({ url("$baseUrl/api/v2/user_rates/${entry.libraryId}").patch(body) })!!
        }
        val rate = json.parseToJsonElement(text)
        return entry(rate, entry.remoteId, null).copy(title = entry.title, url = entry.url, totalChapters = entry.totalChapters, startedAt = entry.startedAt, finishedAt = entry.finishedAt)
    }

    override suspend fun delete(entry: TrackEntry) {
        val id = entry.libraryId.takeIf { it > 0 } ?: find(entry.remoteId)?.libraryId ?: return
        send({ url("$baseUrl/api/v2/user_rates/$id").delete() }, allowNotFound = true)
    }

    private fun entry(rate: JsonElement, remoteId: Long, details: JsonElement?): TrackEntry {
        val name = details["russian"].str()?.ifBlank { null } ?: details["name"].str().orEmpty()
        return TrackEntry(
            service = service,
            remoteId = remoteId,
            libraryId = rate["id"].long() ?: 0,
            title = name,
            url = absolute(details["url"].str() ?: "/mangas/$remoteId"),
            status = status(rate["status"].str()),
            score = (rate["score"].int() ?: 0).toFloat(),
            lastChapterRead = (rate["chapters"].int() ?: 0).toFloat(),
            totalChapters = details["chapters"].int() ?: 0,
        )
    }

    private fun absolute(url: String) = if (url.startsWith("http")) url else baseUrl + url

    companion object {
        /** Shikimori asks clients to name themselves. */
        const val USER_AGENT = "Yomu"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun statusName(s: TrackStatus) = when (s) {
            TrackStatus.READING -> "watching"
            TrackStatus.PLAN_TO_READ -> "planned"
            TrackStatus.COMPLETED -> "completed"
            TrackStatus.ON_HOLD -> "on_hold"
            TrackStatus.DROPPED -> "dropped"
            TrackStatus.REREADING -> "rewatching"
        }

        fun status(name: String?) = when (name) {
            "planned" -> TrackStatus.PLAN_TO_READ
            "completed" -> TrackStatus.COMPLETED
            "on_hold" -> TrackStatus.ON_HOLD
            "dropped" -> TrackStatus.DROPPED
            "rewatching" -> TrackStatus.REREADING
            else -> TrackStatus.READING
        }
    }
}
