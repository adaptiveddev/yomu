package app.yomu.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TrafficMeterTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().addNetworkInterceptor(TrafficMeter.interceptor).build()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        TrafficMeter.reset()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.close() }
        TrafficMeter.isMetered = { false }
        DataSaver.enabled = false
        DataSaver.coversOnMobile = true
    }

    private fun get(path: String): String {
        val request = Request.Builder().url(server.url(path)).build()
        return client.newCall(request).execute().use { it.body.string() }
    }

    @Test
    fun `response bodies are counted by the kind of network`() {
        val body = "x".repeat(20_000)
        server.enqueue(MockResponse.Builder().body(body).build())
        server.enqueue(MockResponse.Builder().body(body).build())

        TrafficMeter.isMetered = { true }
        assertEquals(body, get("/mobile"))
        val mobile = TrafficMeter.snapshot()
        assertTrue(mobile.mobile >= 20_000, "mobile: ${mobile.mobile}")
        assertEquals(0L, mobile.wifi)

        TrafficMeter.isMetered = { false }
        assertEquals(body, get("/wifi"))
        val both = TrafficMeter.snapshot()
        assertEquals(mobile.mobile, both.mobile)
        assertTrue(both.wifi >= 20_000, "wifi: ${both.wifi}")
        assertEquals(both.mobile + both.wifi, both.total)
    }

    @Test
    fun `reset starts from zero`() {
        TrafficMeter.record(5_000, metered = true)
        TrafficMeter.record(7_000, metered = false)
        assertEquals(12_000L, TrafficMeter.snapshot().total)
        TrafficMeter.reset()
        assertEquals(0L, TrafficMeter.snapshot().total)
        TrafficMeter.refresh()
        assertEquals(0L, TrafficMeter.stats.value.total)
    }

    @Test
    fun `data saver applies only on metered networks`() {
        DataSaver.enabled = true
        TrafficMeter.isMetered = { false }
        assertFalse(DataSaver.active)
        TrafficMeter.isMetered = { true }
        assertTrue(DataSaver.active)
        assertFalse(DataSaver.blockCovers)
        DataSaver.coversOnMobile = false
        assertTrue(DataSaver.blockCovers)
        TrafficMeter.isMetered = { false }
        assertFalse(DataSaver.blockCovers)
    }
}
