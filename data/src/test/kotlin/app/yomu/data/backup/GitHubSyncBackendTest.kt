package app.yomu.data.backup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.io.IOException
import java.util.Base64

/** A tiny GitHub Contents API: files with blob SHAs, 409 on a stale SHA, 422 when a SHA is missing. */
class GitHubSyncBackendTest {
    private lateinit var server: MockWebServer
    private val files = HashMap<String, ByteArray>()
    private var counter = 0
    private val shas = HashMap<String, String>()
    private val repos = mutableSetOf("me/yomu-sync")
    private var push = true

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@GitHubSyncBackendTest) {
                    if (request.headers["Authorization"] != "Bearer tok") return MockResponse.Builder().code(401).build()
                    val path = request.url.encodedPath
                    val contents = Regex("/repos/me/yomu-sync/contents/(.+)").matchEntire(path)?.groupValues?.get(1)
                    val blob = Regex("/repos/me/yomu-sync/git/blobs/(.+)").matchEntire(path)?.groupValues?.get(1)
                    when {
                        request.method == "GET" && path == "/user" -> json("""{"login":"me"}""")
                        request.method == "POST" && path == "/user/repos" -> {
                            val name = body(request)["name"]!!.jsonPrimitive.content
                            if (!repos.add("me/$name")) MockResponse.Builder().code(422).build()
                            else json("""{"full_name":"me/$name"}""", 201)
                        }
                        request.method == "GET" && path.startsWith("/repos/me/") && path.count { it == '/' } == 3 ->
                            if (path.removePrefix("/repos/") in repos) json("""{"permissions":{"push":$push}}""") else MockResponse.Builder().code(404).build()
                        contents != null && request.method == "GET" -> {
                            val bytes = files[contents] ?: return MockResponse.Builder().code(404).build()
                            // Big files come without content, like on GitHub.
                            val content = if (bytes.size > 16) "" else Base64.getMimeEncoder().encodeToString(bytes)
                            val encoding = if (bytes.size > 16) "none" else "base64"
                            json("""{"type":"file","sha":"${shas[contents]}","size":${bytes.size},"encoding":"$encoding","content":"${content.replace("\r\n", "\\n")}"}""")
                        }
                        blob != null -> {
                            val name = shas.entries.firstOrNull { it.value == blob }?.key ?: return MockResponse.Builder().code(404).build()
                            MockResponse.Builder().code(200).body(Buffer().write(files[name]!!)).build()
                        }
                        contents != null && request.method == "PUT" -> {
                            val b = body(request)
                            val sha = b["sha"]?.jsonPrimitive?.content
                            when {
                                sha == null && contents in files -> MockResponse.Builder().code(422).build()
                                sha != null && sha != shas[contents] -> MockResponse.Builder().code(409).build()
                                else -> {
                                    files[contents] = Base64.getDecoder().decode(b["content"]!!.jsonPrimitive.content)
                                    shas[contents] = "sha${++counter}"
                                    json("{}", if (sha == null) 201 else 200)
                                }
                            }
                        }
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
            start()
        }
    }

    @After fun tearDown() = server.close()

    private fun body(r: RecordedRequest) = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
    private fun json(text: String, code: Int = 200) = MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(text).build()
    private val api get() = server.url("/").toString().removeSuffix("/")
    private fun backend(repo: String = "me/yomu-sync", token: String = "tok") = GitHubSyncBackend(OkHttpClient(), token, repo, api)

    @Test
    fun writesAndReadsWithCompareAndSwap() = runBlocking {
        val gh = backend()
        gh.check()
        assertNull(gh.read())
        gh.write("one".toByteArray(), null)
        val first = gh.read()!!
        assertEquals("one", first.bytes.decodeToString())
        assertEquals("sha1", first.tag)
        // Another device writes in between: our write is refused and the sync merges again.
        backend().write("other".toByteArray(), gh.read())
        try { gh.write("two".toByteArray(), first); fail() } catch (_: SyncConflictException) { }
        // Creating a file that someone else created meanwhile is a conflict too.
        try { gh.write("three".toByteArray(), null); fail() } catch (_: SyncConflictException) { }
        assertEquals("other", gh.read()!!.bytes.decodeToString())
    }

    @Test
    fun readsLargeFilesThroughTheBlob() = runBlocking {
        val gh = backend()
        val big = ByteArray(5000) { (it % 251).toByte() }
        gh.write(big, null)
        assertTrue(gh.read()!!.bytes.contentEquals(big))
        gh.writeTrackLinks("{}".toByteArray(), null)
        assertEquals("{}", gh.readTrackLinks()!!.bytes.decodeToString())
    }

    @Test
    fun reportsAMissingRepositoryABadTokenAndNoWriteAccess() = runBlocking {
        try { backend("me/nothing").check(); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("not found")) }
        try { backend(token = "bad").check(); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("token")) }
        push = false
        try { backend().check(); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("cannot write")) }
    }

    @Test
    fun createsAPrivateRepositoryOrReusesTheExistingOne() = runBlocking {
        assertEquals("me/books", GitHubSyncBackend.createRepository(OkHttpClient(), "tok", "books", api))
        assertEquals("me/yomu-sync", GitHubSyncBackend.createRepository(OkHttpClient(), "tok", "yomu-sync", api))
    }

    @Test
    fun parsesRepositoryAddresses() {
        assertEquals("me/yomu-sync", GitHubSyncBackend.parseRepository("me/yomu-sync"))
        assertEquals("me/yomu-sync", GitHubSyncBackend.parseRepository(" https://github.com/me/yomu-sync.git "))
        assertEquals("me/yomu-sync", GitHubSyncBackend.parseRepository("git@github.com:me/yomu-sync.git"))
        assertNull(GitHubSyncBackend.parseRepository("yomu-sync"))
        assertNull(GitHubSyncBackend.parseRepository("me/../x"))
        assertEquals("github:me/yomu-sync", backend("Me/Yomu-Sync").id.lowercase())
    }
}
