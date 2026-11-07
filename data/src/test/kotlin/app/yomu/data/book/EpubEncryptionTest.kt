package app.yomu.data.book

import app.yomu.core.common.AppError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class EpubEncryptionTest {
    private fun container(algorithm: String) = object : ResourceContainer {
        private val contents = mapOf(
            "META-INF/encryption.xml" to """<encryption xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/></enc:EncryptedData></encryption>""",
            "book.opf" to """<package><metadata><title>Test</title></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""",
            "a.xhtml" to "<html><body>Hello</body></html>",
        )
        override val entries get() = contents.keys.toList()
        override val entrySet get() = contents.keys
        override fun open(path: String): InputStream? = contents[path]?.let { ByteArrayInputStream(it.toByteArray()) }
        override fun close() {}
    }
    @Test fun rejectsEncryptedContent() {
        assertThrows(AppError.UnsupportedFormat::class.java) { EpubParser.parse(container("http://www.w3.org/2001/04/xmlenc#aes256-cbc")) }
    }
    @Test fun permitsObfuscatedFonts() {
        assertEquals("Test", EpubParser.parse(container("http://www.idpf.org/2008/embedding")).meta.title)
    }
}
