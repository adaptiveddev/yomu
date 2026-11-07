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
import java.io.File
import java.io.IOException

class WebDavBookFilesTest {
    private lateinit var server: MockWebServer
    private val objects = mutableMapOf<String, ByteArray>()
    private var version = 0
    private var weak = false
    private var corruptNewBlob = false
    private val conditional = mutableListOf<String?>()
    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@WebDavBookFilesTest) {
                    val path = request.url.encodedPath
                    when (request.method) {
                        "GET" -> objects[path]?.let { bytes ->
                            MockResponse.Builder().code(200).setHeader("ETag", (if (weak) "W/" else "") + "\"$version\"")
                                .body(Buffer().write(bytes)).build()
                        } ?: MockResponse.Builder().code(404).build()
                        "PUT" -> {
                            val none = request.headers["If-None-Match"]
                            val match = request.headers["If-Match"]
                            conditional += none ?: match
                            if ((none == "*" && path in objects) || (match != null && match != "\"$version\"")) {
                                MockResponse.Builder().code(412).build()
                            } else {
                                val body = request.body!!.toByteArray()
                                objects[path] = if (corruptNewBlob && path.contains("yomu-book-")) body.dropLast(1).toByteArray() else body
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
    @After fun close() = server.close()
    private fun backend() = WebDavSyncBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")

    @Test fun indexUsesStrongEtagsAndRejectsConflicts() = runBlocking {
        val d = backend()
        assertNull(d.readBookIndex())
        d.writeBookIndex("one".toByteArray(), null)
        val old = d.readBookIndex()!!
        d.writeBookIndex("two".toByteArray(), old)
        try { d.writeBookIndex("lost".toByteArray(), old); fail("expected 412") } catch (_: SyncConflictException) { }
        assertEquals("two", d.readBookIndex()!!.bytes.decodeToString())
        assertEquals(listOf("*", "\"1\"", "\"1\""), conditional)
        weak = true
        try { d.readBookIndex(); fail("expected strong ETag error") } catch (e: IOException) { assertTrue(e.message!!.contains("ETag")) }
    }

    @Test fun folderIndexHasStrongConditionalUpdates() = runBlocking {
        val d = backend()
        assertNull(d.readFolderIndex())
        d.writeFolderIndex("first".toByteArray(), null)
        val previous = d.readFolderIndex()!!
        d.writeFolderIndex("second".toByteArray(), previous)
        assertTrue(runCatching { d.writeFolderIndex("stale".toByteArray(), previous) }.exceptionOrNull() is SyncConflictException)
        assertEquals("second", d.readFolderIndex()!!.bytes.decodeToString())
        weak = true
        assertTrue(runCatching { d.readFolderIndex() }.exceptionOrNull() is IOException)
    }

    @Test fun immutableBlobIsValidatedWhenAlreadyPresent() = runBlocking {
        val d = backend(); val content = "book bytes".toByteArray()
        val tmp = File.createTempFile("book", ".tmp").apply { writeBytes(content) }
        try {
            val sha = tmp.inputStream().use { BookFileSync.digest(it).sha256 }
            d.uploadBook(sha, tmp)
            d.uploadBook(sha, tmp) // If-None-Match rejects replacement; existing bytes are verified.
            assertEquals(2, conditional.size)
            assertTrue(conditional.all { it == "*" })
            assertArrayEquals(content, d.readBook(sha) { it.readBytes() })
            objects["/dav/yomu-book-$sha.bin"] = "wrong".toByteArray()
            try { d.uploadBook(sha, tmp); fail("expected corrupted-blob error") } catch (_: IOException) { }
            assertArrayEquals("wrong".toByteArray(), objects["/dav/yomu-book-$sha.bin"])
        } finally { tmp.delete() }
    }
    @Test fun successfulPutWithCorruptedServerCopyFailsVerification() = runBlocking {
        val d = backend()
        val tmp = File.createTempFile("book", ".tmp").apply { writeText("book content") }
        try {
            val sha = tmp.inputStream().use { BookFileSync.digest(it).sha256 }
            corruptNewBlob = true
            try { d.uploadBook(sha, tmp); fail("Expected integrity error after successful PUT") }
            catch (e: IOException) { assertTrue(e.message!!.contains("integrity")) }
            assertNull(d.readBookIndex())
            // A retry must not overwrite an existing corrupt blob or silently accept it.
            corruptNewBlob = false
            try { d.uploadBook(sha, tmp); fail("Expected integrity error on retry") }
            catch (e: IOException) { assertTrue(e.message!!.contains("integrity")) }
            assertNull(d.readBookIndex())
        } finally { tmp.delete() }
    }

}
