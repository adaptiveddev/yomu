package app.yomu.data.backup

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class WebDavTrackLinksTest {
    private lateinit var server: MockWebServer
    private var file: ByteArray? = null
    private var revision = 0
    private var weak = false
    private var lastEncoding: String? = null
    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@WebDavTrackLinksTest) {
                    if (request.url.encodedPath != "/dav/yomu-tracking.json") return MockResponse.Builder().code(404).build()
                    when (request.method) {
                        "GET" -> {
                            lastEncoding = request.headers["Accept-Encoding"]
                            file?.let { MockResponse.Builder().code(200).setHeader("ETag", (if (weak) "W/" else "") + "\"$revision\"").body(Buffer().write(it)).build() }
                                ?: MockResponse.Builder().code(404).build()
                        }
                        "PUT" -> when {
                            request.headers["If-None-Match"] == "*" && file != null -> MockResponse.Builder().code(412).build()
                            request.headers["If-Match"] != null && request.headers["If-Match"] != "\"$revision\"" -> MockResponse.Builder().code(412).build()
                            else -> { file = request.body!!.toByteArray(); revision++; MockResponse.Builder().code(204).build() }
                        }
                        else -> MockResponse.Builder().code(405).build()
                    }
                }
            }
            start()
        }
    }
    @After fun close() { server.close() }
    private fun backend() = WebDavSyncBackend(OkHttpClient(), server.url("/dav").toString(), "", "")
    @Test fun conditionalWritesDetectConcurrentChanges() = runBlocking {
        val a = backend(); assertNull(a.readTrackLinks()); a.writeTrackLinks("first".toByteArray(), null)
        val snapshot = a.readTrackLinks()!!; assertEquals("identity", lastEncoding); assertEquals("\"1\"", snapshot.tag)
        backend().writeTrackLinks("second".toByteArray(), snapshot)
        assertTrue(runCatching { a.writeTrackLinks("stale".toByteArray(), snapshot) }.exceptionOrNull() is SyncConflictException)
        assertEquals("second", a.readTrackLinks()!!.bytes.decodeToString())
    }
    @Test fun weakTagIsRejected() = runBlocking {
        val b = backend(); b.writeTrackLinks("{}".toByteArray(), null); weak = true
        assertTrue(runCatching { b.readTrackLinks() }.exceptionOrNull() is IOException)
    }
    @Test fun oversizedIndexIsRejectedWithoutOverwrite() = runBlocking {
        file = ByteArray(4 * 1024 * 1024 + 1)
        assertTrue(runCatching { backend().readTrackLinks() }.exceptionOrNull() is IOException)
        assertEquals(4 * 1024 * 1024 + 1, file!!.size)
    }
}
