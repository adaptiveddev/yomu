package app.yomu.feature.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HistoryTest {
    @TempDir
    lateinit var tmp: File
    private lateinit var root: File
    private lateinit var dir: File

    @BeforeEach
    fun setUp() {
        root = File(tmp, "work").apply { mkdirs() }
        dir = File(tmp, "history")
        File(root, "a.txt").writeText("one")
    }

    private fun read(name: String) = File(root, name).takeIf { it.exists() }?.readText()

    @Test
    fun `undo restores and redo reapplies a change`() {
        val h = History(root, dir)
        h.change("edit", listOf("a.txt")) { File(root, "a.txt").writeText("two") }
        assertEquals("two", read("a.txt"))
        assertTrue(h.canUndo)
        assertEquals("edit", h.undoLabel)

        assertEquals("edit", h.undo()?.label)
        assertEquals("one", read("a.txt"))
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)

        assertEquals("edit", h.redo()?.label)
        assertEquals("two", read("a.txt"))
        assertFalse(h.canRedo)
    }

    @Test
    fun `files created by a change are removed on undo`() {
        val h = History(root, dir)
        h.change("add", emptyList()) { File(root, "sub/b.txt").apply { parentFile.mkdirs() }.writeText("new") }
        assertEquals("new", read("sub/b.txt"))
        h.undo()
        assertNull(read("sub/b.txt"))
        h.redo()
        assertEquals("new", read("sub/b.txt"))
    }

    @Test
    fun `declared files that are deleted come back on undo`() {
        val h = History(root, dir)
        h.change("delete", listOf("a.txt")) { File(root, "a.txt").delete() }
        assertNull(read("a.txt"))
        h.undo()
        assertEquals("one", read("a.txt"))
        h.redo()
        assertNull(read("a.txt"))
    }

    @Test
    fun `failed change is rolled back and not recorded`() {
        val h = History(root, dir)
        assertThrows<IllegalStateException> {
            h.change("broken", listOf("a.txt")) {
                File(root, "a.txt").writeText("half")
                File(root, "c.txt").writeText("garbage")
                error("boom")
            }
        }
        assertEquals("one", read("a.txt"))
        assertNull(read("c.txt"))
        assertFalse(h.canUndo)
        assertEquals(0, h.size)
    }

    @Test
    fun `new change drops the redo tail`() {
        val h = History(root, dir)
        h.change("first", listOf("a.txt")) { File(root, "a.txt").writeText("two") }
        h.change("second", listOf("a.txt")) { File(root, "a.txt").writeText("three") }
        h.undo()
        assertTrue(h.canRedo)
        h.change("third", listOf("a.txt")) { File(root, "a.txt").writeText("four") }
        assertFalse(h.canRedo)
        assertEquals(2, h.size)
        h.undo()
        assertEquals("two", read("a.txt"))
        h.undo()
        assertEquals("one", read("a.txt"))
    }

    @Test
    fun `history survives a restart`() {
        History(root, dir).change("edit", listOf("a.txt")) { File(root, "a.txt").writeText("two") }
        val reopened = History(root, dir)
        assertTrue(reopened.canUndo)
        assertEquals("edit", reopened.undoLabel)
        reopened.undo()
        assertEquals("one", read("a.txt"))
        assertTrue(History(root, dir).canRedo)
    }

    @Test
    fun `old steps are trimmed over the limit`() {
        val h = History(root, dir, limit = 2)
        repeat(3) { i -> h.change("step $i", listOf("a.txt")) { File(root, "a.txt").writeText("v$i") } }
        assertEquals(2, h.size)
        h.undo()
        h.undo()
        assertFalse(h.canUndo)
        assertEquals("v0", read("a.txt"))
    }

    @Test
    fun `clear forgets everything`() {
        val h = History(root, dir)
        h.change("edit", listOf("a.txt")) { File(root, "a.txt").writeText("two") }
        h.clear()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertEquals("two", read("a.txt"))
    }
}
