package app.yomu.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AppErrorTest {
    @Test
    fun `http status in a message becomes an http error`() {
        val yomu = AppError.from(IOException("HTTP 404 for https://example.org/index.min.json"))
        assertTrue(yomu is AppError.Http)
        assertEquals(404, (yomu as AppError.Http).code)

        // Mihon/Tachiyomi extensions throw HttpException("HTTP error 403") for Cloudflare and blocked pages.
        val ext = AppError.from(IllegalStateException("HTTP error 403"))
        assertEquals(403, (ext as AppError.Http).code)
    }

    @Test
    fun `network failures are classified`() {
        assertTrue(AppError.from(SocketTimeoutException("timeout")) is AppError.Timeout)
        assertTrue(AppError.from(UnknownHostException("example.org")) is AppError.NoNetwork)
        assertTrue(AppError.from(IllegalStateException("HTTP/2 stream reset")) is AppError.Unknown)
    }

    @Test
    fun `technical details name the root cause`() {
        val e = AppError.from(RuntimeException("wrapper", NoSuchFieldException("headers\$delegate")))
        assertEquals("NoSuchFieldException: headers\$delegate", e.technicalDetails)
    }

    @Test
    fun `code built for another app version is an outdated source`() {
        assertTrue(AppError.from(NoSuchMethodError("okhttp3.Headers.get")) is AppError.Incompatible)
        assertTrue(AppError.from(ExceptionInInitializerError("static init")) is AppError.Incompatible)
        // Wrapped by a network interceptor or a source adapter.
        val wrapped = AppError.from(IOException(NoClassDefFoundError("eu/kanade/tachiyomi/Foo")))
        assertTrue(wrapped is AppError.Incompatible)
        assertEquals("NoClassDefFoundError: eu/kanade/tachiyomi/Foo", wrapped.technicalDetails)
        // Ordinary failures stay what they were.
        assertTrue(AppError.from(IOException("connection reset")) is AppError.Unknown)
    }

    @Test
    fun `a full storage is recognised even when wrapped`() {
        assertTrue(AppError.from(IOException("write failed: ENOSPC (No space left on device)")) is AppError.NoSpace)
        assertTrue(AppError.from(IllegalStateException("Page 2 failed", IOException("No space left on device"))) is AppError.NoSpace)
    }
}
