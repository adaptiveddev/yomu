package app.yomu.data.tracking

import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * MangaUpdates shapes are written against the service's own API as implemented by Mihon
 * (session-token login, list ids, series/update + rating endpoints). Live verification with a
 * real account is a separate, manual step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MangaUpdatesTrackerTest {
    private lateinit var server: MockWebServer
    private lateinit var store: TrackerStore
    private lateinit var tracker: MangaUpdatesTracker
    @Before fun setup() {
        server = MockWebServer(); server.start()
        store = TrackerStore(RuntimeEnvironment.getApplication()); store.logout(TrackService.MANGAUPDATES)
        tracker = MangaUpdatesTracker(store, TrackerKeys(), { OkHttpClient() }, server.url("/").toString().removeSuffix("/"))
    }
    @After fun cleanup() { server.shutdown() }
    private fun answer(body: String, code: Int = 200) { server.enqueue(MockResponse().setBody(body).setResponseCode(code)) }
    private fun signed() { store.saveAccount(TrackService.MANGAUPDATES, "session-token", null, 0, 42, "reader") }

    @Test fun loginStoresSessionTokenAndNeverThePassword() = runBlocking {
        answer("""{"context":{"session_token":"secret-session","uid":42}}""")
        answer("""{"username":"reader"}""")
        tracker.loginWithPassword("reader", "a +&=%secret")
        val login = server.takeRequest()
        assertEquals("PUT", login.method)
        assertEquals("/v1/account/login", login.path)
        val body = login.body.readUtf8()
        assertTrue(body.contains("\"username\":\"reader\""))
        assertTrue(body.contains("a +&=%secret"))
        assertEquals("Bearer secret-session", server.takeRequest().getHeader("Authorization"))
        assertEquals(42L, store.userId(TrackService.MANGAUPDATES)); assertEquals("reader", tracker.userName())
        assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences("trackers", 0).all.values.any { it.toString().contains("a +&=%secret") })
        assertNull(store.refreshToken(TrackService.MANGAUPDATES))
    }

    @Test fun failedLoginKeepsTheExistingAccount() = runBlocking {
        signed(); answer("""{"status":"exception","reason":"Wrong username/password"}""", code = 401)
        try { tracker.loginWithPassword("x", "y"); fail() } catch (_: TrackerException) {}
        assertEquals("session-token", store.token(TrackService.MANGAUPDATES)); assertEquals(42L, store.userId(TrackService.MANGAUPDATES))
    }

    @Test fun searchParsesRecordsAndFiltersNovels() = runBlocking {
        answer("""{"total_hits":1,"results":[{"record":{"series_id":40490245618,"title":"Berserk of Gluttony","url":"https://www.mangaupdates.com/series.html?id=40490245618","description":"A story","image":{"url":{"original":"https://example.test/a.jpg"}},"type":"Manga","year":"2018","latest_chapter":60,"bayesian_rating":6.72}}]}""")
        val result = tracker.search("gluttony").single()
        assertEquals(40490245618L, result.remoteId); assertEquals("Berserk of Gluttony", result.title)
        assertEquals("Manga", result.format); assertEquals(60, result.totalChapters)
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/v1/series/search", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"search\":\"gluttony\""))
        assertTrue(body.contains("\"filter_types\":[\"drama cd\",\"novel\"]"))
    }

    private fun listItem(listId: Long = 0, chapter: Int = 4) =
        """{"series":{"series_id":12,"title":"T"},"list_id":$listId,"status":{"volume":1,"chapter":$chapter}}"""

    @Test fun findMapsListIdToStatusAndReadsRating() = runBlocking {
        signed(); answer(listItem(listId = 2, chapter = 7)); answer("""{"rating":8.5}""")
        val entry = tracker.find(12L)!!
        assertEquals(TrackStatus.COMPLETED, entry.status); assertEquals(7f, entry.lastChapterRead)
        assertEquals(8.5f, entry.score)
        assertEquals("/v1/lists/series/12", server.takeRequest().path)
        assertEquals("/v1/series/12/rating", server.takeRequest().path)
    }

    @Test fun findReturnsNullWhenTheSeriesIsNotListed() = runBlocking {
        signed(); answer("""{"status":"exception","reason":"Series not on your list"}""", code = 404)
        assertNull(tracker.find(12L))
    }

    @Test fun saveAddsThenUpdatesProgressAndRating() = runBlocking {
        signed()
        // find: not on the list (404) -> add -> update -> rating put
        answer("""{"status":"exception","reason":"Series not on your list"}""", code = 404)
        answer("{}"); answer("{}"); answer("{}")
        val result = tracker.save(TrackEntry(TrackService.MANGAUPDATES, 12, 0, "Title", "url", TrackStatus.READING, 8f, 5f))
        assertEquals(TrackStatus.READING, result.status); assertEquals(5f, result.lastChapterRead)
        assertEquals("/v1/lists/series/12", server.takeRequest().path) // find
        val add = server.takeRequest(); assertEquals("/v1/lists/series", add.path)
        assertTrue(add.body.readUtf8().contains("\"list_id\":0"))
        val update = server.takeRequest(); assertEquals("/v1/lists/series/update", update.path)
        val updateBody = update.body.readUtf8()
        assertTrue(updateBody.contains("\"chapter\":5")); assertTrue(updateBody.contains("\"list_id\":0"))
        val rating = server.takeRequest(); assertEquals("/v1/series/12/rating", rating.path)
        assertEquals("PUT", rating.method); assertTrue(rating.body.readUtf8().contains("\"rating\":8.0"))
    }

    @Test fun saveOfExistingEntrySkipsTheAdd() = runBlocking {
        signed()
        answer(listItem()); answer("{}"); answer("{}"); answer("{}") // list, rating, update, rating removal
        tracker.save(TrackEntry(TrackService.MANGAUPDATES, 12, 0, "Title", "url", TrackStatus.REREADING, 0f, 2f))
        assertEquals("/v1/lists/series/12", server.takeRequest().path) // find: the list item...
        assertEquals("/v1/series/12/rating", server.takeRequest().path) // ...and its rating
        val update = server.takeRequest(); assertEquals("/v1/lists/series/update", update.path)
        assertTrue(update.body.readUtf8().contains("\"chapter\":2"))
        val rating = server.takeRequest(); assertEquals("DELETE", rating.method) // score 0 removes the rating
        assertEquals("/v1/series/12/rating", rating.path)
    }

    @Test fun deleteRemovesTheListEntryAndTheRating() = runBlocking {
        signed()
        answer("[]"); answer("{}", code = 200)
        tracker.delete(TrackEntry(TrackService.MANGAUPDATES, 12, 0, "Title", "url"))
        val removal = server.takeRequest(); assertEquals("/v1/lists/series/delete", removal.path)
        assertTrue(removal.body.readUtf8().contains("12"))
        assertEquals("/v1/series/12/rating", server.takeRequest().path)
    }

    @Test fun unauthorizedMarkedForSignOut() = runBlocking {
        signed(); answer("""{"status":"exception"}""", code = 401)
        try { tracker.find(12L); fail() } catch (e: TrackerException) { assertTrue(e.unauthorized) }
    }
}
