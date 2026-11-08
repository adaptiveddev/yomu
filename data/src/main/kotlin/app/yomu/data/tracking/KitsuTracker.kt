package app.yomu.data.tracking

import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import app.yomu.source.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.roundToInt

/** Kitsu's JSON:API REST client. Passwords are sent only to the HTTPS token endpoint, never saved. */
class KitsuTracker(store: TrackerStore, keys: TrackerKeys, client: () -> OkHttpClient, private val baseUrl: String = "https://kitsu.io") :
    Tracker(TrackService.KITSU, store, keys, client) {
    override val redirectHost = "kitsu-password-only"
    override val passwordLogin = true
    override fun authUrl(): String = throw TrackerException("Kitsu: password sign-in required")
    override suspend fun finishLogin(redirect: String): Unit = throw TrackerException("Kitsu: password sign-in required")

    private data class Tokens(val access: String, val refresh: String?, val expires: Long)
    private suspend fun tokenRequest(form: FormBody): Tokens {
        val response = client().newCall(Request.Builder().url("$baseUrl/api/oauth/token").post(form).build()).await()
        return response.use { r ->
            // Never expose or log the token endpoint response: it may echo credentials.
            if (!r.isSuccessful) throw TrackerException("Kitsu: sign-in or token refresh failed (HTTP ${r.code})", unauthorized = r.code == 401, code = r.code)
            val value = try { json.parseToJsonElement(r.body.string()) } catch (_: Exception) { throw TrackerException("Kitsu: invalid token response") }
            val access = value["access_token"].str()?.takeIf { it.isNotBlank() } ?: throw TrackerException("Kitsu: missing access token")
            val seconds = value["expires_in"].long()?.takeIf { it in 1..315360000 } ?: 0L
            Tokens(access, value["refresh_token"].str()?.takeIf { it.isNotBlank() }, if (seconds > 0) System.currentTimeMillis() + seconds * 1000 else 0)
        }
    }
    override suspend fun loginWithPassword(username: String, password: String) {
        require(username.isNotBlank() && password.isNotEmpty()) { "Kitsu: email and password are required" }
        val tokens = tokenRequest(FormBody.Builder().add("grant_type", "password").add("username", username.trim()).add("password", password).build())
        val users = array(sendRaw(Request.Builder().url("$baseUrl/api/edge/users?filter%5Bself%5D=true").header("Authorization", "Bearer ${tokens.access}").get().build()))
        if (users.size != 1) throw TrackerException("Kitsu: could not identify account")
        val user = users.single()
        val id = user["id"].str()?.toLongOrNull()?.takeIf { it > 0 } ?: throw TrackerException("Kitsu: invalid account ID")
        store.saveAccount(service, tokens.access, tokens.refresh, tokens.expires, id, user["attributes"]["name"].str())
    }
    private suspend fun refresh(stale: String?) = refreshOnce(stale) {
        val old = store.refreshToken(service) ?: throw TrackerException("Kitsu: sign in again", unauthorized = true)
        val fresh = try { tokenRequest(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", old).build()) }
        catch (e: TrackerException) {
            if (e.code == 400 || e.code == 401) throw TrackerException("Kitsu: sign in again", unauthorized = true, cause = e, code = e.code)
            throw e
        }
        store.saveTokens(service, fresh.access, fresh.refresh ?: old, fresh.expires)
    }
    private suspend fun sendRaw(request: Request): String {
        val req = request.newBuilder().header("Accept", "application/vnd.api+json").build()
        return client().newCall(req).await().use { r ->
            if (!r.isSuccessful) throw TrackerException(if (r.code == 401) "Kitsu: sign in again" else "Kitsu: HTTP ${r.code}", unauthorized = r.code == 401, code = r.code)
            r.body.string()
        }
    }
    private suspend fun send(builder: Request.Builder, auth: Boolean = true): String {
        val expires = store.expiresAt(service)
        if (auth && expires > 0 && expires < System.currentTimeMillis() + 60_000) refresh(store.token(service))
        val used = if (auth) token() else null
        fun request(access: String?) = builder.apply { if (access != null) header("Authorization", "Bearer $access") }.build()
        return try { sendRaw(request(used)) } catch (e: TrackerException) {
            if (!auth || !e.unauthorized || store.refreshToken(service) == null) throw e
            refresh(used)
            sendRaw(request(token()))
        }
    }
    private fun array(text: String): List<JsonElement> {
        val root = json.parseToJsonElement(text)
        return (root["data"] as? JsonArray) ?: throw TrackerException("Kitsu: invalid list response")
    }
    override suspend fun search(query: String): List<TrackSearchResult> {
        val url = "$baseUrl/api/edge/manga".toHttpUrl().newBuilder().addQueryParameter("filter[text]", query.take(100)).addQueryParameter("page[limit]", "20").build()
        return array(send(Request.Builder().url(url).get(), false)).mapNotNull { manga ->
            val id = manga["id"].str()?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val a = manga["attributes"]
            val title = a["canonicalTitle"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TrackSearchResult(id, title, coverUrl = a["posterImage"]["small"].str(), url = "https://kitsu.io/manga/${a["slug"].str() ?: id}", format = a["subtype"].str(), publishing = a["status"].str(), totalChapters = a["chapterCount"].int() ?: 0, year = a["startDate"].str()?.take(4)?.toIntOrNull(), description = a["synopsis"].str())
        }
    }
    override suspend fun find(remoteId: Long): TrackEntry? {
        require(remoteId > 0)
        val user = store.userId(service).takeIf { it > 0 } ?: throw TrackerException("Kitsu: account ID missing; sign in again", unauthorized = true)
        val url = "$baseUrl/api/edge/library-entries".toHttpUrl().newBuilder().addQueryParameter("filter[userId]", user.toString()).addQueryParameter("filter[mangaId]", remoteId.toString()).addQueryParameter("include", "user,manga").build()
        val entries = array(send(Request.Builder().url(url).get()))
        if (entries.size > 1) throw TrackerException("Kitsu: duplicate library entries")
        return entries.singleOrNull()?.let {
            val owner = it["relationships"]["user"]["data"]["id"].str()?.toLongOrNull()
            val manga = it["relationships"]["manga"]["data"]["id"].str()?.toLongOrNull()
            if (owner != user || manga != remoteId) throw TrackerException("Kitsu: library entry identity mismatch")
            parseEntry(it, remoteId)
        }
    }
    private fun parseEntry(value: JsonElement, remoteId: Long): TrackEntry {
        val id = value["id"].str()?.toLongOrNull()?.takeIf { it > 0 } ?: throw TrackerException("Kitsu: invalid library entry ID")
        val a = value["attributes"].obj() ?: throw TrackerException("Kitsu: invalid library entry")
        if (a["private"].str() !in listOf("true", "false")) throw TrackerException("Kitsu: missing privacy flag")
        val privacy = a["private"].str() == "true"
        val progress = a["progress"].int()?.takeIf { it >= 0 } ?: throw TrackerException("Kitsu: invalid progress")
        if (!a.containsKey("ratingTwenty")) throw TrackerException("Kitsu: missing rating")
        val rating = if (a["ratingTwenty"] is JsonNull) 0 else a["ratingTwenty"].int()?.takeIf { it in 2..20 } ?: throw TrackerException("Kitsu: invalid rating")
        if (a["reconsuming"].str() !in listOf("true", "false")) throw TrackerException("Kitsu: invalid rereading state")
        val status = when (a["status"].str()) {
            "current" -> if (a["reconsuming"].str() == "true") TrackStatus.REREADING else TrackStatus.READING
            "planned" -> TrackStatus.PLAN_TO_READ
            "completed" -> TrackStatus.COMPLETED
            "on_hold" -> TrackStatus.ON_HOLD
            "dropped" -> TrackStatus.DROPPED
            else -> throw TrackerException("Kitsu: unsupported library status")
        }
        return TrackEntry(service, remoteId, id, "", "", status, rating / 2f, progress.toFloat(), startedAt = millisOf(a["startedAt"].str()), finishedAt = millisOf(a["finishedAt"].str()), privacy = privacy)
    }
    override suspend fun save(entry: TrackEntry): TrackEntry {
        val existing = find(entry.remoteId)
        val user = store.userId(service).takeIf { it > 0 } ?: throw TrackerException("Kitsu: account ID missing")
        val status = when (entry.status) {
            TrackStatus.READING, TrackStatus.REREADING -> "current"
            TrackStatus.PLAN_TO_READ -> "planned"
            TrackStatus.COMPLETED -> "completed"
            TrackStatus.ON_HOLD -> "on_hold"
            TrackStatus.DROPPED -> "dropped"
        }
        val payload = buildJsonObject { putJsonObject("data") {
            put("type", "libraryEntries")
            if (existing != null) put("id", existing.libraryId.toString())
            putJsonObject("attributes") {
                put("status", status); put("reconsuming", entry.status == TrackStatus.REREADING)
                put("progress", entry.lastChapterRead.toInt().coerceAtLeast(0))
                if (entry.score <= 0f) put("ratingTwenty", JsonNull) else put("ratingTwenty", (entry.score * 2).roundToInt().coerceIn(2, 20))
                // The user's privacy choice must survive every update; new entries start public.
                put("private", existing?.privacy ?: entry.privacy)
                // Dates: null clears them on the service, matching the local "not set" state.
                put("startedAt", dayOf(entry.startedAt)?.toString())
                put("finishedAt", dayOf(entry.finishedAt)?.toString())
            }
            if (existing == null) putJsonObject("relationships") {
                putJsonObject("user") { putJsonObject("data") { put("type", "users"); put("id", user.toString()) } }
                putJsonObject("manga") { putJsonObject("data") { put("type", "manga"); put("id", entry.remoteId.toString()) } }
            }
        } }.toString().toRequestBody(JSON)
        val url = "$baseUrl/api/edge/library-entries" + (existing?.let { "/${it.libraryId}" } ?: "")
        val request = Request.Builder().url(url).let { if (existing == null) it.post(payload) else it.patch(payload) }
        val data = json.parseToJsonElement(send(request))["data"] ?: throw TrackerException("Kitsu: invalid save response")
        return parseEntry(data, entry.remoteId).copy(title = entry.title, url = entry.url, totalChapters = entry.totalChapters)
    }
    override suspend fun delete(entry: TrackEntry) {
        val id = find(entry.remoteId)?.libraryId ?: return
        send(Request.Builder().url("$baseUrl/api/edge/library-entries/$id").delete())
    }
    companion object { private val JSON = "application/vnd.api+json".toMediaType() }
}
