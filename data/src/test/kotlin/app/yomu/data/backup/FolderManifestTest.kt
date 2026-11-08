package app.yomu.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FolderManifestTest {
    private val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
    private val sha = "a".repeat(64)
    private fun manifest(vararg paths: String) = FolderManifest(folders = listOf(FolderEntry(id, "Manga", paths.map { FolderPage(it, sha, 1) })))

    @Test fun preservesNestedPagePathsAndOrder() {
        val source = manifest("chapter 1/001.jpg", "chapter 1/002.png", "chapter 2/001.webp")
        assertEquals(source, FolderManifestCodec.decode(FolderManifestCodec.encode(source)))
    }
    @Test fun rejectsUnsafeAndAmbiguousPaths() {
        val invalid = listOf("../page.jpg", "/page.jpg", "a/../../page.jpg", "a\\page.jpg", "a//page.jpg", "C:/page.jpg", "page.jpg.", "a/./page.jpg", "page.txt")
        for (path in invalid) assertFalse(path, FolderManifestCodec.validPath(path))
        for (paths in listOf(arrayOf("a.jpg", "A.JPG"), arrayOf("a.jpg", "a.jpg/page.png"), arrayOf("e\u0301.jpg", "é.jpg"), arrayOf("Chapter/1.jpg", "chapter/2.jpg"), arrayOf("01.jpg", "1.jpg"))) {
            assertTrue(runCatching { FolderManifestCodec.encode(manifest(*paths)) }.isFailure)
        }
    }
    @Test fun failsClosedOnMalformedOrFutureIndex() {
        for (value in listOf("{}", "{not-json", "{\"version\":3,\"folders\":[]}", "{\"version\":1,\"folders\":[],\"unknown\":true}")) {
            assertTrue(runCatching { FolderManifestCodec.decode(value.toByteArray()) }.exceptionOrNull() is IOException)
        }
    }
}
