package app.yomu.data.backup

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** A tiny WebDAV server: one folder that must be created first, ETags, If-Match / If-None-Match. */
class WebDavSyncBackendTest {
    private lateinit var server: MockWebServer
    private var folder = false
    private var file: ByteArray? = null
    private var version = 0
    private val methods = mutableListOf<String>()
    private var weakTags = false
    private var noTag = false
    private var customTag: String? = null
    private var acceptEncoding: String? = null

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@WebDavSyncBackendTest) {
                    methods += request.method
                    if (request.headers["Authorization"] != okhttp3.Credentials.basic("me", "secret")) return MockResponse.Builder().code(401).build()
                    val path = request.url.encodedPath
                    when (request.method) {
                        "PROPFIND" -> MockResponse.Builder().code(if (folder) 207 else 404).build()
                        "MKCOL" -> { folder = true; MockResponse.Builder().code(201).build() }
                        "GET" -> {
                            acceptEncoding = request.headers["Accept-Encoding"]
                            val tag = customTag ?: ((if (weakTags) "W/" else "") + "\"v$version\"")
                            file?.let { MockResponse.Builder().code(200).apply { if (!noTag) setHeader("ETag", tag) }.body(Buffer().write(it)).build() }
                                ?: MockResponse.Builder().code(404).build()
                        }
                        "PUT" -> when {
                            !folder -> MockResponse.Builder().code(409).build()
                            path != "/dav/Yomu/yomu-sync.json" -> MockResponse.Builder().code(400).build()
                            request.headers["If-None-Match"] == "*" && file != null -> MockResponse.Builder().code(412).build()
                            request.headers["If-Match"] != null && request.headers["If-Match"] != "\"v$version\"" -> MockResponse.Builder().code(412).build()
                            else -> {
                                file = request.body?.toByteArray()
                                version++
                                MockResponse.Builder().code(201).build()
                            }
                        }
                        else -> MockResponse.Builder().code(405).build()
                    }
                }
            }
            start()
        }
    }

    @After fun tearDown() = server.close()

    private fun backend(password: String = "secret") = WebDavSyncBackend(OkHttpClient(), server.url("/dav/Yomu").toString(), "me", password)

    @Test
    fun createsTheFolderAndWritesSafely() = runBlocking {
        val dav = backend()
        dav.check()
        assertTrue(folder)
        assertNull(dav.read())
        dav.write("one".toByteArray(), null)
        val first = dav.read()!!
        assertEquals("one", first.bytes.decodeToString())
        assertEquals("\"v1\"", first.tag)
        // Another device writes in between: our write is refused and the sync merges again.
        backend().write("other".toByteArray(), dav.read())
        try {
            dav.write("two".toByteArray(), first)
            fail("expected a conflict")
        } catch (_: SyncConflictException) {
        }
        assertEquals("other", dav.read()!!.bytes.decodeToString())
        dav.write("two".toByteArray(), dav.read())
        assertEquals("two", dav.read()!!.bytes.decodeToString())
    }

    @Test
    fun putCreatesAMissingFolder() = runBlocking {
        backend().write("x".toByteArray(), null)
        assertTrue(folder)
        assertEquals(listOf("PUT", "MKCOL", "PUT"), methods)
    }

    @Test
    fun weakOrMissingTagsAreRejectedWithoutOverwriting() = runBlocking {
        backend().write("original".toByteArray(), null)
        weakTags = true
        assertTrue(runCatching { backend().read() }.exceptionOrNull() is java.io.IOException)
        assertEquals("identity", acceptEncoding)
        assertEquals("original", file!!.decodeToString())
        weakTags = false
        noTag = true
        assertTrue(runCatching { backend().read() }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { backend().write("changed".toByteArray(), SyncFile("original".toByteArray(), null)) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { backend().write("changed".toByteArray(), SyncFile("original".toByteArray(), "W/\"v1\"")) }.exceptionOrNull() is java.io.IOException)
        assertEquals("original", file!!.decodeToString())
    }

    @Test
    fun wrongPasswordIsReported() = runBlocking {
        try {
            backend("wrong").read()
            fail("expected an error")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("password"))
        }
    }
    @Test
    fun invalidEntityTagsCannotBypassOptimisticConcurrency() = runBlocking {
        backend().write("original".toByteArray(), null)
        for (tag in listOf("*", "v1", "\"v1\", \"v2\"", "W/\"v1\"")) {
            customTag = tag
            assertTrue("read should reject $tag", runCatching { backend().read() }.exceptionOrNull() is java.io.IOException)
            assertTrue("write should reject $tag", runCatching { backend().write("changed".toByteArray(), SyncFile("original".toByteArray(), tag)) }.exceptionOrNull() is java.io.IOException)
        }
        assertEquals("original", file!!.decodeToString())
    }

    @Test fun oversizedMetadataIsRejectedBeforeNetworkWrite() = runBlocking {
        val before = methods.size
        assertTrue(runCatching { backend().write(ByteArray(MAX_SYNC_FILE_BYTES + 1), null) }.exceptionOrNull() is java.io.IOException)
        assertEquals(before, methods.size)
        assertNull(file)
    }

}
