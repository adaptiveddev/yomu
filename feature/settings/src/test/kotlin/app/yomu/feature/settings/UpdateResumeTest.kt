package app.yomu.feature.settings

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UpdateResumeTest {
    @TempDir lateinit var dir: File
    private val bytes = "a test apk with several bytes".toByteArray()
    private val name = "yomu.apk"
    private val url = "https://example.org/$name"
    private val sums = "https://example.org/SHA256SUMS.txt"
    private fun release() = ReleaseInfo("1", url, url, bytes.size.toLong(), sums)
    private fun hash(): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Response(url: String, val status: Int, val data: ByteArray, val range: String? = null, val fail: Boolean = false) : HttpURLConnection(URL(url)) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = data.size.toLong()
        override fun getHeaderField(name: String?) = if (name == "Content-Range") range else null
        override fun getInputStream(): java.io.InputStream = if (!fail) data.inputStream() else object : java.io.InputStream() {
            var i = 0
            override fun read(): Int { if (i >= 5) throw IOException("interrupted"); return data[i++].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= 5) throw IOException("interrupted")
                val n = minOf(len, 5 - i); System.arraycopy(data, i, b, off, n); i += n; return n
            }
        }
    }

    @Test fun resumesAfterFailureWithMatchingChecksum() = runBlocking {
        val sum = "${hash()}  $name".toByteArray()
        var apkCalls = 0
        var rangeSeen: String? = null
        val connect: (String) -> HttpURLConnection = { u ->
            if (u == sums) Response(u, 200, sum) else {
                apkCalls++
                if (apkCalls == 1) Response(u, 200, bytes, fail = true) else object : HttpURLConnection(URL(u)) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode(): Int { rangeSeen = getRequestProperty("Range"); return 206 }
                    override fun getHeaderField(name: String?) = if (name == "Content-Range") "bytes 5-${bytes.size-1}/${bytes.size}" else null
                    override fun getInputStream() = bytes.copyOfRange(5, bytes.size).inputStream()
                }
            }
        }
        assertThrows(IOException::class.java) { runBlocking { UpdateDownloader.downloadUsing(release(), dir, {}, connect) } }
        assertEquals(5L, File(dir, "$name.part").length())
        val result = UpdateDownloader.downloadUsing(release(), dir, {}, connect)
        assertEquals("bytes=5-", rangeSeen)
        assertArrayEquals(bytes, result.readBytes())
        assertFalse(File(dir, "$name.part.meta").exists())
    }

    @Test fun serverIgnoringRangeRestartsInsteadOfAppending() = runBlocking {
        File(dir, "$name.part").writeText("stale")
        File(dir, "$name.part.meta").writeText("$url\n${hash()}")
        val result = UpdateDownloader.downloadUsing(release(), dir, {}) { u ->
            if (u == sums) Response(u, 200, "${hash()}  $name".toByteArray()) else Response(u, 200, bytes)
        }
        assertArrayEquals(bytes, result.readBytes())
    }

    @Test fun wrongChecksumDeletesPartial() {
        val wrong = "0".repeat(64)
        assertThrows(UpdateChecksumException::class.java) {
            runBlocking { UpdateDownloader.downloadUsing(release(), dir, {}) { u ->
                if (u == sums) Response(u, 200, "$wrong  $name".toByteArray()) else Response(u, 200, bytes)
            } }
        }
        assertFalse(File(dir, "$name.part").exists())
        assertFalse(File(dir, name).exists())
    }

    @Test fun invalidRangeDiscardsPartial() {
        File(dir, "$name.part").writeText("stale")
        File(dir, "$name.part.meta").writeText("$url\n${hash()}")
        assertThrows(IOException::class.java) {
            runBlocking { UpdateDownloader.downloadUsing(release(), dir, {}) { u ->
                if (u == sums) Response(u, 200, "${hash()}  $name".toByteArray())
                else Response(u, 206, bytes, "bytes 0-${bytes.size-1}/${bytes.size}")
            } }
        }
        assertFalse(File(dir, "$name.part").exists())
    }

    @Test fun badRangesAreRejected() {
        assertEquals(20L, UpdateDownloader.rangeTotal("bytes 5-19/20", 5))
        listOf("bytes 0-19/20", "bytes 5-18/20", "bytes 5-20/20", "bytes 5-19/*", "garbage").forEach {
            assertNull(UpdateDownloader.rangeTotal(it, 5))
        }
    }
}
