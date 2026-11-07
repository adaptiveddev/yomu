package app.yomu.data.tracking

import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The three tracker clients against a fake server: sign-in, search, the user's entry, token refresh. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackersTest {
    private lateinit var server: MockWebServer
    private lateinit var store: TrackerStore
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val bodies = CopyOnWriteArrayList<String>()
    private var answer: (RecordedRequest, String) -> MockResponse = { _, _ -> MockResponse().setResponseCode(500) }
    private val client = OkHttpClient()
    private val keys = TrackerKeys(anilistClientId = "11", malClientId = "mal-id", shikimoriClientId = "shiki-id", shikimoriClientSecret = "shiki-secret")

    @Before
    fun setUp() {
        store = TrackerStore(RuntimeEnvironment.getApplication())
        TrackService.entries.forEach { store.logout(it) }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requests += request
                bodies += body
                return answer(request, body)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url(path: String = "") = server.url(path).toString().removeSuffix("/")
    private fun json(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)
    private fun form(body: String): Map<String, String> = body.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test
    fun aniListSignsInSearchesAndSaves() = runBlocking {
        val t = AniListTracker(store, keys, { client }, apiUrl = url("/graphql"))
        assertTrue(t.authUrl().contains("client_id=11") && t.authUrl().contains("response_type=token"))
        answer = { _, body ->
            when {
                "Viewer" in body -> json("""{"data":{"Viewer":{"id":7,"name":"neko"}}}""")
                "Page(" in body -> json(
                    """{"data":{"Page":{"media":[{"id":30002,"title":{"userPreferred":"Berserk","romaji":"Berserk","english":"Berserk","native":"ベルセルク"},
                    "coverImage":{"large":"https://img/b.jpg"},"format":"MANGA","status":"RELEASING","chapters":null,"description":"<b>Guts</b>",
                    "startDate":{"year":1989},"siteUrl":"https://anilist.co/manga/30002"}]}}}""",
                )
                "MediaList(" in body -> MockResponse().setResponseCode(404).setBody("""{"errors":[{"message":"Not Found.","status":404}],"data":{"MediaList":null}}""")
                "SaveMediaListEntry" in body -> json(
                    """{"data":{"SaveMediaListEntry":{"id":555,"status":"CURRENT","progress":12,"score":80,"startedAt":{"year":2024,"month":5,"day":2},
                    "completedAt":{"year":null,"month":null,"day":null},"media":{"id":30002,"title":{"userPreferred":"Berserk"},"chapters":null,"siteUrl":"https://anilist.co/manga/30002"}}}}""",
                )
                else -> MockResponse().setResponseCode(400)
            }
        }
        t.finishLogin("yomu://anilist-auth#access_token=tok-a&token_type=Bearer&expires_in=31536000")
        assertTrue(t.loggedIn())
        assertEquals("neko", t.userName())
        assertEquals(7L, store.userId(TrackService.ANILIST))
        assertEquals("Bearer tok-a", requests.last().getHeader("Authorization"))

        val found = t.search("berserk").single()
        assertEquals(30002L, found.remoteId)
        assertEquals("ベルセルク", found.altTitle)
        assertEquals("Guts", found.description)
        assertEquals(1989, found.year)

        assertNull("a title that is not in the list", t.find(30002))

        val saved = t.save(TrackEntry(TrackService.ANILIST, 30002, title = "Berserk", url = "", status = TrackStatus.READING, score = 8f, lastChapterRead = 12f))
        val vars = Json.parseToJsonElement(bodies.last()).jsonObject["variables"]!!.jsonObject
        assertEquals("CURRENT", vars["status"]!!.jsonPrimitive.content)
        assertEquals(12, vars["progress"]!!.jsonPrimitive.int)
        assertEquals(80, vars["score"]!!.jsonPrimitive.int)
        assertEquals(555L, saved.libraryId)
        assertEquals(8f, saved.score)
        assertEquals(12f, saved.lastChapterRead)
        assertTrue(saved.startedAt > 0)
        assertEquals(0L, saved.finishedAt)
    }

    @Test
    fun aniListReportsADeniedSignIn() = runBlocking {
        val t = AniListTracker(store, keys, { client }, apiUrl = url("/graphql"))
        val e = runCatching { t.finishLogin("yomu://anilist-auth?error=access_denied&error_description=The+user+denied") }.exceptionOrNull()
        assertTrue(e is TrackerException && e.message!!.contains("The user denied"))
        assertFalse(t.loggedIn())
    }

    @Test
    fun myAnimeListUsesPkceAndRefreshesAnExpiredToken() = runBlocking {
        val t = MyAnimeListTracker(store, keys, { client }, apiUrl = url("/v2"), authBase = url("/oauth2"))
        val auth = t.authUrl().toHttpUrl()
        val challenge = auth.queryParameter("code_challenge")!!
        assertEquals("plain", auth.queryParameter("code_challenge_method"))
        assertEquals("yomu://myanimelist-auth", auth.queryParameter("redirect_uri"))
        var tokens = 0
        answer = { r, _ ->
            val path = r.requestUrl!!.encodedPath
            when {
                path == "/oauth2/token" -> {
                    tokens++
                    json("""{"token_type":"Bearer","expires_in":2678400,"access_token":"tok-$tokens","refresh_token":"ref-$tokens"}""")
                }
                path == "/v2/users/@me" -> json("""{"id":42,"name":"reader"}""")
                path == "/v2/manga/2/my_list_status" && r.method == "PUT" -> json(
                    """{"status":"reading","score":7,"num_volumes_read":0,"num_chapters_read":30,"is_rereading":false,"start_date":"2024-01-03","updated_at":"2024-02-01T00:00:00+00:00"}""",
                )
                path == "/v2/manga/2" -> json("""{"id":2,"title":"Berserk","num_chapters":0,"my_list_status":{"status":"on_hold","score":9,"num_chapters_read":100,"is_rereading":false}}""")
                path == "/v2/manga/3" -> json("""{"id":3,"title":"Other","num_chapters":10}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        t.finishLogin("yomu://myanimelist-auth?code=the-code&state=x")
        val exchange = form(bodies[0])
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("the-code", exchange["code"])
        assertEquals(challenge, exchange["code_verifier"])
        assertEquals("mal-id", exchange["client_id"])
        assertEquals("reader", t.userName())

        val entry = t.find(2)!!
        assertEquals(TrackStatus.ON_HOLD, entry.status)
        assertEquals(100f, entry.lastChapterRead)
        assertNull("not in the list", t.find(3))

        val saved = t.save(entry.copy(status = TrackStatus.READING, score = 7f, lastChapterRead = 30f))
        val put = form(bodies.last())
        assertEquals("reading", put["status"])
        assertEquals("30", put["num_chapters_read"])
        assertEquals("7", put["score"])
        assertEquals(30f, saved.lastChapterRead)
        assertTrue(saved.startedAt > 0)

        // A token the server refuses is renewed with the refresh token and the request repeated.
        requests.clear()
        bodies.clear()
        store.saveTokens(TrackService.MYANIMELIST, "tok-1", "ref-1", System.currentTimeMillis() + 3_600_000)
        answer = { r, _ ->
            val path = r.requestUrl!!.encodedPath
            when {
                path == "/oauth2/token" -> json("""{"access_token":"tok-new","refresh_token":"ref-new","expires_in":2678400}""")
                path == "/v2/manga/2" && r.getHeader("Authorization") == "Bearer tok-1" -> MockResponse().setResponseCode(401)
                path == "/v2/manga/2" -> json("""{"id":2,"title":"Berserk","num_chapters":0,"my_list_status":{"status":"reading","num_chapters_read":101}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        assertEquals(101f, t.find(2)!!.lastChapterRead)
        val refresh = form(bodies.first { "grant_type" in it })
        assertEquals("refresh_token", refresh["grant_type"])
        assertEquals("ref-1", refresh["refresh_token"])
        assertEquals("Bearer tok-new", requests.last().getHeader("Authorization"))
        assertEquals("tok-new", store.token(TrackService.MYANIMELIST))
    }

    @Test
    fun shikimoriSendsTheSecretAndKeepsRanobeApart() = runBlocking {
        val t = ShikimoriTracker(store, keys, { client }, baseUrl = url())
        assertTrue(t.configured())
        answer = { r, _ ->
            val path = r.requestUrl!!.encodedPath
            when {
                path == "/oauth/token" -> json("""{"access_token":"s-tok","refresh_token":"s-ref","expires_in":86400,"token_type":"Bearer"}""")
                path == "/api/users/whoami" -> json("""{"id":99,"nickname":"чтец"}""")
                path == "/api/mangas" -> json("""[{"id":1,"name":"Berserk","russian":"Берсерк","image":{"original":"/system/mangas/original/1.jpg"},"url":"/mangas/1-berserk","kind":"manga","status":"ongoing","chapters":0,"aired_on":"1989-08-25"}]""")
                path == "/api/ranobe" -> json("""[{"id":2,"name":"Overlord","russian":"","image":{"original":"https://cdn/2.jpg"},"url":"/ranobe/2-overlord","kind":"light_novel","status":"ongoing","chapters":0}]""")
                path == "/api/v2/user_rates" && r.method == "POST" -> json("""{"id":1000,"user_id":99,"target_id":2,"target_type":"Manga","score":0,"status":"watching","chapters":5}""")
                path == "/api/v2/user_rates" -> json("""[{"id":1000,"user_id":99,"target_id":2,"target_type":"Manga","score":6,"status":"planned","chapters":3}]""")
                path == "/api/mangas/2" -> MockResponse().setResponseCode(404)
                path == "/api/ranobe/2" -> json("""{"id":2,"name":"Overlord","russian":"Повелитель","url":"/ranobe/2-overlord","chapters":120}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        t.finishLogin("yomu://shikimori-auth?code=c0de")
        val token = form(bodies[0])
        assertEquals("authorization_code", token["grant_type"])
        assertEquals("shiki-secret", token["client_secret"])
        assertEquals("yomu://shikimori-auth", token["redirect_uri"])
        assertEquals("чтец", t.userName())
        assertEquals("Yomu", requests[1].getHeader("User-Agent"))
        assertEquals("Bearer s-tok", requests[1].getHeader("Authorization"))

        val results = t.search("over")
        assertEquals(listOf(1L, 2L), results.map { it.remoteId }.sorted())
        val berserk = results.first { it.remoteId == 1L }
        assertEquals("Берсерк", berserk.title)
        assertEquals("Berserk", berserk.altTitle)
        assertEquals(url("/mangas/1-berserk"), berserk.url)
        assertEquals("Overlord", results.first { it.remoteId == 2L }.title)
        assertTrue("search needs no account", requests.filter { it.requestUrl!!.encodedPath.startsWith("/api/mangas") || it.requestUrl!!.encodedPath == "/api/ranobe" }.all { it.getHeader("Authorization") == null })

        val entry = t.find(2)!!
        assertEquals(1000L, entry.libraryId)
        assertEquals("Повелитель", entry.title)
        assertEquals(120, entry.totalChapters)
        assertEquals(TrackStatus.PLAN_TO_READ, entry.status)

        requests.clear()
        bodies.clear()
        val created = t.save(TrackEntry(TrackService.SHIKIMORI, 2, title = "Повелитель", url = "", status = TrackStatus.READING, lastChapterRead = 5f))
        val rate = Json.parseToJsonElement(bodies.single()).jsonObject["user_rate"]!!.jsonObject
        assertEquals(99, rate["user_id"]!!.jsonPrimitive.int)
        assertEquals("watching", rate["status"]!!.jsonPrimitive.content)
        assertEquals(5, rate["chapters"]!!.jsonPrimitive.int)
        assertEquals("POST", requests.single().method)
        assertEquals(1000L, created.libraryId)
    }

    @Test
    fun shikimoriRenewsAnExpiredTokenOnceForRequestsAtTheSameTime() = runBlocking {
        val t = ShikimoriTracker(store, keys, { client }, baseUrl = url())
        store.saveTokens(TrackService.SHIKIMORI, "old", "ref-old", System.currentTimeMillis() - 1_000)
        store.saveUser(TrackService.SHIKIMORI, 99, "reader")
        val refreshes = AtomicInteger()
        answer = { r, body ->
            val path = r.requestUrl!!.encodedPath
            when {
                // Refresh tokens are single-use: a second refresh with the same one is refused.
                path == "/oauth/token" -> if (form(body)["refresh_token"] == "ref-old" && refreshes.incrementAndGet() == 1) {
                    Thread.sleep(300)
                    json("""{"access_token":"new","refresh_token":"ref-new","expires_in":86400}""")
                } else {
                    MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                }
                path == "/api/v2/user_rates" && r.getHeader("Authorization") == "Bearer new" -> json(
                    """[{"id":1000,"user_id":99,"target_id":${r.requestUrl!!.queryParameter("target_id")},"target_type":"Manga","score":0,"status":"watching","chapters":1}]""",
                )
                path == "/api/v2/user_rates" -> MockResponse().setResponseCode(401)
                path.startsWith("/api/mangas/") -> json("""{"id":1,"name":"X","russian":"Икс","url":"/mangas/1","chapters":10}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val found = (1L..3L).map { id -> async(Dispatchers.IO) { t.find(id) } }.awaitAll()
        assertEquals(listOf(1L, 2L, 3L), found.map { it!!.remoteId })
        assertEquals(1, refreshes.get())
        assertEquals("new", store.token(TrackService.SHIKIMORI))
    }

    @Test
    fun aServerErrorWhileRenewingTheTokenDoesNotSignOut() = runBlocking {
        val t = MyAnimeListTracker(store, keys, { client }, apiUrl = url("/v2"), authBase = url("/oauth2"))
        store.saveTokens(TrackService.MYANIMELIST, "old", "ref-old", System.currentTimeMillis() - 1_000)
        answer = { r, _ -> if (r.requestUrl!!.encodedPath == "/oauth2/token") MockResponse().setResponseCode(503) else MockResponse().setResponseCode(404) }
        try {
            t.find(2)
            fail("expected an error")
        } catch (e: TrackerException) {
            assertFalse("a server error is retried later", e.unauthorized)
        }
        assertTrue(t.loggedIn())
        // A refused refresh token is what asks to sign in again.
        answer = { _, _ -> MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""") }
        try {
            t.find(2)
            fail("expected an error")
        } catch (e: TrackerException) {
            assertTrue(e.unauthorized)
        }
    }
}
