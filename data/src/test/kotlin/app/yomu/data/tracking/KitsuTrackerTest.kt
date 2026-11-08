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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KitsuTrackerTest {
    private lateinit var server: MockWebServer
    private lateinit var store: TrackerStore
    private lateinit var tracker: KitsuTracker
    @Before fun setup() {
        server = MockWebServer(); server.start()
        store = TrackerStore(RuntimeEnvironment.getApplication()); store.logout(TrackService.KITSU)
        tracker = KitsuTracker(store, TrackerKeys(), { OkHttpClient() }, server.url("/").toString().removeSuffix("/"))
    }
    @After fun cleanup() { server.shutdown() }
    private fun answer(body: String) { server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/vnd.api+json")) }
    private fun signed() { store.saveTokens(TrackService.KITSU, "access", "refresh", 0); store.saveUser(TrackService.KITSU, 42, "reader") }
    private fun resource(id: Int = 99, status: String = "current") = """{"id":"$id","attributes":{"status":"$status","progress":5,"ratingTwenty":17,"reconsuming":false,"private":true},"relationships":{"user":{"data":{"id":"42"}},"manga":{"data":{"id":"12"}}}}"""
    @Test fun loginDoesNotSavePassword() = runBlocking {
        answer("""{"access_token":"secret-token","refresh_token":"secret-refresh","expires_in":7200}""")
        answer("""{"data":[{"id":"42","attributes":{"name":"reader"}}]}""")
        tracker.loginWithPassword("user@example.com", "a +&=%secret")
        val request = server.takeRequest(); val form = request.body.readUtf8()
        assertEquals("a +&=%secret", java.net.URLDecoder.decode(form.split("&").first { it.startsWith("password=") }.substringAfter("="), "UTF-8"))
        assertEquals("Bearer secret-token", server.takeRequest().getHeader("Authorization"))
        assertEquals(42L, store.userId(TrackService.KITSU)); assertEquals("reader", tracker.userName())
        assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences("trackers", 0).all.values.any { it.toString().contains("a +&=%secret") })
    }
    @Test fun failedIdentityDoesNotReplaceExistingAccount() = runBlocking {
        signed(); answer("""{"access_token":"new"}"""); answer("""{"data":[]}""")
        try { tracker.loginWithPassword("x", "y"); fail() } catch (_: TrackerException) {}
        assertEquals("access", store.token(TrackService.KITSU)); assertEquals(42L, store.userId(TrackService.KITSU))
    }
    @Test fun searchParsesResults() = runBlocking {
        answer("""{"data":[{"id":"2","attributes":{"canonicalTitle":"Berserk","slug":"berserk","chapterCount":400,"posterImage":{"small":"https://example.test/a.jpg"}}}]}""")
        val result = tracker.search("Berserk").single()
        assertEquals(2L, result.remoteId); assertEquals(400, result.totalChapters); assertEquals("Berserk", result.title)
        assertEquals("Berserk", server.takeRequest().requestUrl!!.queryParameter("filter[text]"))
    }
    @Test fun updateFindsOwnEntryAndNeverChangesPrivacy() = runBlocking {
        signed(); answer("""{"data":[${resource()}]}"""); answer("""{"data":${resource()}}""")
        val result = tracker.save(TrackEntry(TrackService.KITSU, 12, 12345, "Title", "url", TrackStatus.REREADING, 8.5f, 5f))
        assertEquals(99L, result.libraryId); assertEquals(8.5f, result.score)
        assertEquals("42", server.takeRequest().requestUrl!!.queryParameter("filter[userId]"))
        val patch = server.takeRequest(); val body = patch.body.readUtf8()
        assertEquals("PATCH", patch.method); assertTrue(patch.path!!.endsWith("/99"))
        assertTrue(body.contains("\"reconsuming\":true")); assertTrue(body.contains("\"ratingTwenty\":17"))
        // The private flag the user set on the service must survive the update.
        assertTrue(body.contains("\"private\":true"))
    }
    @Test fun createAndDeleteUseCurrentAccount() = runBlocking {
        signed(); answer("""{"data":[]}"""); answer("""{"data":${resource()}}"""); answer("""{"data":[${resource()}]}"""); server.enqueue(MockResponse().setResponseCode(204))
        val item = TrackEntry(TrackService.KITSU, 12, title = "Title", url = "url")
        tracker.save(item); server.takeRequest(); val post = server.takeRequest(); val body = post.body.readUtf8()
        assertEquals("POST", post.method); assertTrue(body.contains("\"libraryEntries\"")); assertTrue(body.contains("\"users\"")); assertTrue(body.contains("\"42\"")); assertTrue(body.contains("\"manga\""))
        // New entries start public; dates are explicit nulls (they clear the value on the service).
        assertTrue(body.contains("\"private\":false")); assertTrue(body.contains("\"startedAt\":null")); assertTrue(body.contains("\"finishedAt\":null"))
        tracker.delete(item); server.takeRequest(); assertEquals("DELETE", server.takeRequest().method)
    }
    @Test fun datesAreSentAndCleared() = runBlocking {
        signed()
        val started = 1_520_000_000_000L // 2018-03-02 UTC-ish; only the date part is sent
        answer("""{"data":[${resource()}]}"""); answer("""{"data":${resource()}}""")
        tracker.save(TrackEntry(TrackService.KITSU, 12, 99, "Title", "url", TrackStatus.READING, 8.5f, 5f, startedAt = started))
        server.takeRequest() // find
        val patch = server.takeRequest(); val body = patch.body.readUtf8()
        assertTrue(body.contains("\"startedAt\":\"2018-03-02\""))
        // A zero timestamp clears the date on the service (explicit null).
        answer("""{"data":[${resource()}]}"""); answer("""{"data":${resource()}}""")
        tracker.save(TrackEntry(TrackService.KITSU, 12, 99, "Title", "url", TrackStatus.READING, 8.5f, 5f))
        server.takeRequest(); val cleared = server.takeRequest()
        assertTrue(cleared.body.readUtf8().contains("\"startedAt\":null"))
    }
    @Test fun invalidListFailsClosed() = runBlocking {
        signed(); answer("""{"errors":[{"detail":"oops"}]}""")
        try { tracker.find(12); fail() } catch (_: TrackerException) {}
    }
    @Test fun rejectsMismatchedEntryBeforeMutation() = runBlocking {
        signed(); answer("""{"data":[${resource().replace("\"id\":\"42\"", "\"id\":\"43\"")}]}""")
        try { tracker.save(TrackEntry(TrackService.KITSU, 12, title = "Title", url = "")); fail() } catch (_: TrackerException) {}
        assertEquals(1, server.requestCount)
    }
    @Test fun cloudflareDoesNotInvalidateAccount() = runBlocking {
        signed(); server.enqueue(MockResponse().setResponseCode(403).setBody("blocked"))
        try { tracker.find(12); fail() } catch (e: TrackerException) { assertEquals(403, e.code); assertFalse(e.unauthorized) }
        assertEquals("access", store.token(TrackService.KITSU))
    }
    @Test fun unauthorizedRefreshesAndRetries() = runBlocking {
        signed(); server.enqueue(MockResponse().setResponseCode(401)); answer("""{"access_token":"new","refresh_token":"next","expires_in":3600}"""); answer("""{"data":[]}""")
        assertNull(tracker.find(12)); server.takeRequest(); assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=refresh")); assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        assertEquals("next", store.refreshToken(TrackService.KITSU))
    }
}
