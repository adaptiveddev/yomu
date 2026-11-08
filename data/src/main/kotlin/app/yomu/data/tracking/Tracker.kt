package app.yomu.data.tracking

import android.content.Context
import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.source.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** OAuth client ids built into the app (gradle properties YOMU_*_CLIENT_ID, see docs/TRACKING.md); empty = not configured. */
data class TrackerKeys(
    val anilistClientId: String = "",
    val malClientId: String = "",
    val shikimoriClientId: String = "",
    val shikimoriClientSecret: String = "",
)

/** An error of a tracking service; [code] is the HTTP status (0 when there was no answer to read it from). */
class TrackerException(message: String, val unauthorized: Boolean = false, cause: Throwable? = null, val code: Int = 0) : IOException(message, cause) {
    /** The service refused a refresh token (or the app's keys): only signing in again helps. */
    val refused: Boolean get() = unauthorized || code in 400..403
}

/**
 * Accounts of the tracking services: tokens in private preferences of the app (never in backups or sync files) and the
 * client ids the user entered instead of the built-in ones.
 */
class TrackerStore(context: Context) {
    private val prefs = context.getSharedPreferences("trackers", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** Changes on every sign-in, sign-out and client id edit. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun key(s: TrackService, name: String) = "${s.id}.$name"

    fun token(s: TrackService): String? = prefs.getString(key(s, "token"), null)?.takeIf { it.isNotBlank() }
    fun refreshToken(s: TrackService): String? = prefs.getString(key(s, "refresh"), null)?.takeIf { it.isNotBlank() }
    fun expiresAt(s: TrackService): Long = prefs.getLong(key(s, "expires"), 0)
    fun userId(s: TrackService): Long = prefs.getLong(key(s, "user_id"), 0)
    fun userName(s: TrackService): String? = prefs.getString(key(s, "user_name"), null)
    fun extra(s: TrackService, name: String): String? = prefs.getString(key(s, "x.$name"), null)

    fun saveTokens(s: TrackService, token: String, refresh: String?, expiresAt: Long) {
        prefs.edit().putString(key(s, "token"), token).putString(key(s, "refresh"), refresh).putLong(key(s, "expires"), expiresAt).apply()
        _version.value++
    }

    /** Persist a new account and its tokens together; observers never see a mixed account. */
    fun saveAccount(s: TrackService, token: String, refresh: String?, expiresAt: Long, id: Long, name: String?) {
        prefs.edit().putString(key(s, "token"), token).putString(key(s, "refresh"), refresh)
            .putLong(key(s, "expires"), expiresAt).putLong(key(s, "user_id"), id).putString(key(s, "user_name"), name).apply()
        _version.value++
    }

    fun saveUser(s: TrackService, id: Long, name: String?) {
        prefs.edit().putLong(key(s, "user_id"), id).putString(key(s, "user_name"), name).apply()
        _version.value++
    }

    fun setExtra(s: TrackService, name: String, value: String?) {
        prefs.edit().putString(key(s, "x.$name"), value).apply()
    }

    fun logout(s: TrackService) {
        prefs.edit().remove(key(s, "token")).remove(key(s, "refresh")).remove(key(s, "expires")).remove(key(s, "user_id")).remove(key(s, "user_name")).apply()
        _version.value++
    }

    fun clientIdOverride(s: TrackService): String = prefs.getString(key(s, "client_id"), null).orEmpty()
    fun clientSecretOverride(s: TrackService): String = prefs.getString(key(s, "client_secret"), null).orEmpty()

    fun setClient(s: TrackService, id: String, secret: String?) {
        prefs.edit().putString(key(s, "client_id"), id.trim()).apply { if (secret != null) putString(key(s, "client_secret"), secret.trim()) }.apply()
        _version.value++
    }
}

/** One tracking service: OAuth sign-in in the browser, search, and the user's list entries. */
abstract class Tracker(
    val service: TrackService,
    protected val store: TrackerStore,
    private val keys: TrackerKeys,
    protected val client: () -> OkHttpClient,
) {
    protected val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** `yomu://<host>`: must be registered as the redirect URI of the OAuth application. */
    abstract val redirectHost: String
    val redirectUri: String get() = "yomu://$redirectHost"

    /** Shikimori also needs the application's secret. */
    open val needsSecret: Boolean = false
    open val passwordLogin: Boolean = false
    open suspend fun loginWithPassword(username: String, password: String) { throw TrackerException("Password sign-in is unsupported") }

    fun clientId(): String = store.clientIdOverride(service).ifBlank {
        when (service) {
            TrackService.ANILIST -> keys.anilistClientId
            TrackService.MYANIMELIST -> keys.malClientId
            TrackService.SHIKIMORI -> keys.shikimoriClientId
            TrackService.KITSU -> ""
            TrackService.MANGAUPDATES -> ""
        }
    }

    fun clientSecret(): String = store.clientSecretOverride(service).ifBlank { if (service == TrackService.SHIKIMORI) keys.shikimoriClientSecret else "" }

    /** A client id (and secret) is known, so signing in can work. */
    fun configured(): Boolean = passwordLogin || (clientId().isNotBlank() && (!needsSecret || clientSecret().isNotBlank()))

    fun loggedIn(): Boolean = store.token(service) != null

    fun userName(): String? = store.userName(service)

    /** Page to open in the browser; it comes back to [redirectUri]. */
    abstract fun authUrl(): String

    /** Finishes signing in with the redirect the browser opened. */
    abstract suspend fun finishLogin(redirect: String)

    open fun logout() = store.logout(service)

    abstract suspend fun search(query: String): List<TrackSearchResult>

    /** The user's entry for the title, null if it is not in their list. */
    abstract suspend fun find(remoteId: Long): TrackEntry?

    /** Adds or updates the entry; returns it as the service stored it. */
    abstract suspend fun save(entry: TrackEntry): TrackEntry

    abstract suspend fun delete(entry: TrackEntry)

    protected fun token(): String = store.token(service) ?: throw TrackerException("${service.displayName}: not signed in", unauthorized = true)

    private val refreshLock = Mutex()

    /**
     * Runs a token refresh once for requests made at the same time: refresh tokens are single-use (Shikimori), so a second
     * refresh with the same one would fail. Whoever waited for the lock finds a token other than [staleToken] and skips it.
     */
    protected suspend fun refreshOnce(staleToken: String?, refresh: suspend () -> Unit) = refreshLock.withLock {
        if (staleToken != null && store.token(service) != staleToken) return@withLock
        refresh()
    }

    /** Runs the request; HTTP errors become [TrackerException] (401: [TrackerException.unauthorized]). */
    protected suspend fun execute(request: Request, allowNotFound: Boolean = false): String? {
        val response = client().newCall(request).await()
        response.use { r ->
            val body = r.body.string()
            return when {
                r.isSuccessful -> body
                allowNotFound && r.code == 404 -> null
                r.code == 401 -> throw TrackerException("${service.displayName}: sign in again", unauthorized = true, code = 401)
                else -> throw TrackerException("${service.displayName}: HTTP ${r.code} ${body.take(200)}", code = r.code)
            }
        }
    }

    companion object {
        /** Query and fragment parameters of a redirect URI. */
        fun redirectParams(uri: String): Map<String, String> {
            val parts = listOfNotNull(uri.substringAfter('?', "").substringBefore('#').ifEmpty { null }, uri.substringAfter('#', "").ifEmpty { null })
            return parts.flatMap { it.split('&') }.filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
        }

        fun dayOf(millis: Long): LocalDate? = if (millis <= 0) null else Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        fun millisOf(date: LocalDate?): Long = date?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0
        fun millisOf(iso: String?): Long = iso?.takeIf { it.length >= 10 }?.let { runCatching { millisOf(LocalDate.parse(it.take(10))) }.getOrNull() } ?: 0
    }
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
internal fun JsonElement?.float(): Float? = (this as? JsonPrimitive)?.floatOrNull
internal fun JsonElement?.list(): List<JsonElement> = (this as? kotlinx.serialization.json.JsonArray).orEmpty()
