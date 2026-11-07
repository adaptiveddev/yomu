package app.yomu.data.tracking

import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom

/** MyAnimeList: REST API v2, OAuth with PKCE (plain challenge, as MAL requires), refresh tokens. */
class MyAnimeListTracker(
    store: TrackerStore,
    keys: TrackerKeys,
    client: () -> OkHttpClient,
    private val apiUrl: String = "https://api.myanimelist.net/v2",
    private val authBase: String = "https://myanimelist.net/v1/oauth2",
) : Tracker(TrackService.MYANIMELIST, store, keys, client) {
    override val redirectHost = "myanimelist-auth"

    override fun authUrl(): String {
        val verifier = pkceVerifier()
        store.setExtra(service, "verifier", verifier)
        return "$authBase/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId())
            .addQueryParameter("code_challenge", verifier)
            .addQueryParameter("code_challenge_method", "plain")
            .addQueryParameter("redirect_uri", redirectUri)
            .build().toString()
    }

    override suspend fun finishLogin(redirect: String) {
        val p = redirectParams(redirect)
        val code = p["code"] ?: throw TrackerException("MyAnimeList: ${p["error_description"] ?: p["error"] ?: "no code in the answer"}")
        val verifier = store.extra(service, "verifier") ?: throw TrackerException("MyAnimeList: start signing in again")
        val form = FormBody.Builder()
            .add("client_id", clientId())
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", redirectUri)
            .build()
        saveTokens(execute(Request.Builder().url("$authBase/token").post(form).build())!!)
        store.setExtra(service, "verifier", null)
        try {
            val me = json.parseToJsonElement(get("$apiUrl/users/@me")!!)
            store.saveUser(service, me["id"].long() ?: 0, me["name"].str())
        } catch (e: Exception) {
            store.logout(service)
            throw e
        }
    }

    private fun saveTokens(text: String) {
        val t = json.parseToJsonElement(text)
        val access = t["access_token"].str() ?: throw TrackerException("MyAnimeList: no token in the answer")
        val expires = t["expires_in"].long()?.let { System.currentTimeMillis() + it * 1000 } ?: 0
        store.saveTokens(service, access, t["refresh_token"].str(), expires)
    }

    /** Access tokens live about a month; an expired one is renewed with the refresh token. */
    private suspend fun freshToken(): String {
        val expires = store.expiresAt(service)
        if (expires > 0 && expires < System.currentTimeMillis() + 60_000) refresh(store.token(service))
        return token()
    }

    /** Renews the access token, unless a request made at the same time already did (see [refreshOnce]). */
    private suspend fun refresh(staleToken: String?) = refreshOnce(staleToken) {
        val refresh = store.refreshToken(service) ?: throw TrackerException("MyAnimeList: sign in again", unauthorized = true)
        val form = FormBody.Builder().add("client_id", clientId()).add("grant_type", "refresh_token").add("refresh_token", refresh).build()
        try {
            saveTokens(execute(Request.Builder().url("$authBase/token").post(form).build())!!)
        } catch (e: TrackerException) {
            // A server error or a broken answer is tried again later; only a refused refresh token signs out.
            if (!e.refused) throw e
            throw TrackerException("MyAnimeList: sign in again", unauthorized = true, cause = e)
        }
    }

    private suspend fun send(spec: Request.Builder.() -> Request.Builder, allowNotFound: Boolean = false): String? {
        val token = freshToken()
        val first = runCatching { execute(Request.Builder().header("Authorization", "Bearer $token").spec().build(), allowNotFound) }
        val e = first.exceptionOrNull()
        if (e is CancellationException) throw e
        if (e is TrackerException && e.unauthorized && store.refreshToken(service) != null) {
            refresh(token)
            return execute(Request.Builder().header("Authorization", "Bearer ${token()}").spec().build(), allowNotFound)
        }
        return first.getOrThrow()
    }

    private suspend fun get(url: String, allowNotFound: Boolean = false) = send({ url(url).get() }, allowNotFound)

    override suspend fun search(query: String): List<TrackSearchResult> {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(64))
            .addQueryParameter("limit", "25")
            .addQueryParameter("nsfw", "true")
            .addQueryParameter("fields", "id,title,alternative_titles,synopsis,num_chapters,main_picture,status,media_type,start_date")
            .build().toString()
        val text = if (loggedIn()) get(url) else execute(Request.Builder().url(url).header("X-MAL-CLIENT-ID", clientId()).build())
        return json.parseToJsonElement(text!!)["data"].list().mapNotNull { d ->
            val n = d["node"]
            val id = n["id"].long() ?: return@mapNotNull null
            TrackSearchResult(
                remoteId = id,
                title = n["title"].str().orEmpty(),
                altTitle = n["alternative_titles"]["en"].str()?.ifBlank { null } ?: n["alternative_titles"]["ja"].str()?.ifBlank { null },
                coverUrl = n["main_picture"]["large"].str() ?: n["main_picture"]["medium"].str(),
                url = "https://myanimelist.net/manga/$id",
                format = n["media_type"].str(),
                publishing = n["status"].str(),
                totalChapters = n["num_chapters"].int() ?: 0,
                year = n["start_date"].str()?.take(4)?.toIntOrNull(),
                description = n["synopsis"].str(),
            )
        }
    }

    override suspend fun find(remoteId: Long): TrackEntry? {
        val text = get("$apiUrl/manga/$remoteId?fields=id,title,num_chapters,my_list_status{start_date,finish_date}", allowNotFound = true) ?: return null
        val n = json.parseToJsonElement(text)
        val status = n["my_list_status"] ?: return null
        return entry(remoteId, n["title"].str().orEmpty(), n["num_chapters"].int() ?: 0, status)
    }

    override suspend fun save(entry: TrackEntry): TrackEntry {
        val form = FormBody.Builder()
            .add("status", statusName(entry.status))
            .add("is_rereading", (entry.status == TrackStatus.REREADING).toString())
            .add("score", entry.score.toInt().coerceIn(0, 10).toString())
            .add("num_chapters_read", entry.lastChapterRead.toInt().toString())
            .apply {
                dayOf(entry.startedAt)?.let { add("start_date", it.toString()) }
                dayOf(entry.finishedAt)?.let { add("finish_date", it.toString()) }
            }
            .build()
        val text = send({ url("$apiUrl/manga/${entry.remoteId}/my_list_status").put(form) })!!
        val saved = entry(entry.remoteId, entry.title, entry.totalChapters, json.parseToJsonElement(text))
        return saved.copy(startedAt = saved.startedAt.takeIf { it > 0 } ?: entry.startedAt, finishedAt = saved.finishedAt.takeIf { it > 0 } ?: entry.finishedAt)
    }

    override suspend fun delete(entry: TrackEntry) {
        send({ url("$apiUrl/manga/${entry.remoteId}/my_list_status").delete() }, allowNotFound = true)
    }

    private fun entry(id: Long, title: String, total: Int, s: JsonElement): TrackEntry {
        val rereading = s["is_rereading"].str() == "true"
        return TrackEntry(
            service = service,
            remoteId = id,
            title = title,
            url = "https://myanimelist.net/manga/$id",
            status = if (rereading) TrackStatus.REREADING else status(s["status"].str()),
            score = (s["score"].int() ?: 0).toFloat(),
            lastChapterRead = (s["num_chapters_read"].int() ?: 0).toFloat(),
            totalChapters = total,
            startedAt = millisOf(s["start_date"].str()),
            finishedAt = millisOf(s["finish_date"].str()),
        )
    }

    companion object {
        fun statusName(s: TrackStatus) = when (s) {
            TrackStatus.READING, TrackStatus.REREADING -> "reading"
            TrackStatus.PLAN_TO_READ -> "plan_to_read"
            TrackStatus.COMPLETED -> "completed"
            TrackStatus.ON_HOLD -> "on_hold"
            TrackStatus.DROPPED -> "dropped"
        }

        fun status(name: String?) = when (name) {
            "plan_to_read" -> TrackStatus.PLAN_TO_READ
            "completed" -> TrackStatus.COMPLETED
            "on_hold" -> TrackStatus.ON_HOLD
            "dropped" -> TrackStatus.DROPPED
            else -> TrackStatus.READING
        }

        /** 64 characters of the PKCE alphabet. */
        fun pkceVerifier(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            val random = SecureRandom()
            return String(CharArray(64) { chars[random.nextInt(chars.length)] })
        }
    }
}
