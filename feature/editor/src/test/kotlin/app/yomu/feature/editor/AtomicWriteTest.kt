package app.yomu.feature.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AtomicWriteTest {
    @Test fun replacesContentWithoutLeavingTempFile(@TempDir dir: File) {
        val f = File(dir, "a/draft.json")
        writeTextAtomic(f, "one")
        writeTextAtomic(f, "two")
        assertEquals("two", f.readText())
        assertFalse(File(f.parentFile, "draft.json.tmp").exists())
    }
}
