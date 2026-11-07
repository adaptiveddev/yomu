package app.yomu.data.translation

import app.yomu.domain.translation.TranslationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Online engine against a fake server: request format, response parsing and error mapping. */
class GoogleFreeEngineTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun engine() = GoogleFreeEngine({ client }, server.url("/").toString().trimEnd('/'))

    private fun respond(code: Int, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `joins translated segments and sends the text in the body`() = runBlocking {
        respond(200, """[[["Привет, ","Hello, ",null,null,10],["мир","world",null,null,10]],null,"en"]""")
        val result = engine().translate("Hello, world", null, "ru")
        assertEquals("Привет, мир", result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/translate_a/single", request.url.encodedPath)
        assertEquals("auto", request.url.queryParameter("sl"))
        assertEquals("ru", request.url.queryParameter("tl"))
        assertEquals("q=Hello, world", java.net.URLDecoder.decode(request.body?.utf8().orEmpty(), "UTF-8"))
    }

    @Test
    fun `uses explicit source language and detects language`() = runBlocking {
        respond(200, """[[["Hallo","Hello",null,null,1]],null,"en"]""")
        assertEquals("Hallo", engine().translate("Hello", "en", "de"))
        assertEquals("en", server.takeRequest().url.queryParameter("sl"))
        respond(200, """[[["Hi","Привет",null,null,1]],null,"ru"]""")
        assertEquals("ru", engine().detectLanguage("Привет"))
    }

    @Test
    fun `maps http errors to translation error kinds`() {
        respond(429)
        val rateLimited = assertThrows<TranslationException> { runBlocking { engine().translate("a", null, "ru") } }
        assertEquals(TranslationException.Kind.RATE_LIMITED, rateLimited.kind)

        respond(403)
        val denied = assertThrows<TranslationException> { runBlocking { engine().translate("a", null, "ru") } }
        assertEquals(TranslationException.Kind.NOT_CONFIGURED, denied.kind)

        respond(500, "boom")
        val serverError = assertThrows<TranslationException> { runBlocking { engine().translate("a", null, "ru") } }
        assertEquals(TranslationException.Kind.SERVER, serverError.kind)
        assertTrue(serverError.message!!.contains("500"))
    }

    @Test
    fun `no connection is reported as network error`() {
        val engine = engine()
        server.close()
        val e = assertThrows<TranslationException> { runBlocking { engine.translate("a", null, "ru") } }
        assertEquals(TranslationException.Kind.NO_NETWORK, e.kind)
    }

    @Test
    fun `detect language returns null on failure`() = runBlocking {
        respond(500)
        assertEquals(null, engine().detectLanguage("text"))
    }
}
