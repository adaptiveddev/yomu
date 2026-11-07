package app.yomu.data.backup

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class SyncReadSafetyTest {
    @Test fun damagedOrUnknownStatesAreNotTreatedAsEmpty() {
        listOf("broken", "{}", "[]", "{\"version\":99}", "{\"books\":false}").forEach { text ->
            val error = assertThrows(IOException::class.java) { SyncManager.decodeRemote(text.toByteArray()) }
            assertTrue(error.message!!.contains("not overwritten"))
        }
    }
    @Test fun duplicateBookAndAnnotationKeysAreRejected() {
        val book = """{"hash":"h","title":"Book","locator":null,"progress":0,"updatedAt":1,"status":"READING"}"""
        val note = """{"hash":"h","locator":null,"text":"note","tags":"","createdAt":5,"updatedAt":5}"""
        val files = listOf("""{"version":2,"books":[$book,$book]}""", """{"version":2,"notes":[$note,$note]}""")
        files.forEach { text -> assertThrows(IOException::class.java) { SyncManager.decodeRemote(text.toByteArray()) } }
    }
    @Test fun readsOldAndCurrentFormats() {
        assertEquals(1, SyncManager.decodeRemote("{\"updatedAt\":1}".toByteArray()).version)
        assertEquals(2, SyncManager.decodeRemote("{\"version\":2,\"books\":[]}".toByteArray()).version)
    }
    @Test fun invalidUtf8IsNotSilentlyReplaced() {
        val damaged = "{\"version\":2,\"device\":\"".toByteArray() + byteArrayOf(0x80.toByte()) + "\"}".toByteArray()
        assertThrows(IOException::class.java) { SyncManager.decodeRemote(damaged) }
    }

    @Test fun invalidUtf8BookNameIsNotSilentlyReplaced() {
        val prefix = "{\"version\":1,\"books\":[{\"bookHash\":\"" + "a".repeat(40) + ":1\",\"sha256\":\"" + "b".repeat(64) + "\",\"size\":1,\"name\":\""
        val damaged = prefix.toByteArray() + byteArrayOf(0x80.toByte()) + "\"}]}".toByteArray()
        assertThrows(IOException::class.java) { BookFileSync.decodeIndex(damaged) }
    }

    @Test fun duplicateJsonKeysIncludingEscapedAliasesAreRejected() {
        val examples = listOf(
            "{\"version\":2,\"version\":2}",
            "{\"version\":2,\"books\":[],\"books\":[]}",
            "{\"version\":2,\"books\":[{\"hash\":\"a\",\"title\":\"one\",\"ti\\u0074le\":\"two\",\"locator\":null,\"progress\":0,\"updatedAt\":1,\"status\":\"READING\"}]}"
        )
        examples.forEach { assertThrows(IOException::class.java) { SyncManager.decodeRemote(it.toByteArray()) } }
        assertEquals(2, SyncManager.decodeRemote("{\"version\":2,\"device\":\"a:b { } \\\"quote\\\"\"}".toByteArray()).version)
    }
    @Test fun duplicateBookIndexKeysAreRejected() {
        val text = "{\"version\":1,\"version\":1,\"books\":[]}"
        assertThrows(IOException::class.java) { BookFileSync.decodeIndex(text.toByteArray()) }
    }

    @Test fun boundedReadsAcceptTheLimitAndRejectEvenOneExtraByte() {
        assertArrayEquals("1234".toByteArray(), readSyncBytes("1234".byteInputStream(), 4))
        assertThrows(IOException::class.java) { readSyncBytes("12345".byteInputStream(), 4) }
        assertArrayEquals(byteArrayOf(), readSyncBytes(byteArrayOf().inputStream(), 0))
        assertThrows(IOException::class.java) { readSyncBytes("x".byteInputStream(), 0) }
    }

}
