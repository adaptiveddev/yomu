package app.yomu.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FolderPositionMapTest {
    private fun page(path: String, digest: Char) = FolderPage(path, digest.toString().repeat(64), 10)
    @Test fun additionsAndRenamesPreservePagePosition() {
        val before = listOf(page("2.jpg", 'a'), page("3.jpg", 'b'))
        val after = listOf(page("1.jpg", 'c'), page("2.jpg", 'a'), page("3-renamed.jpg", 'b'))
        val map = FolderPositionMap(before, after)
        assertEquals(1, map.index(0)) // same path and same bytes
        assertEquals(2, map.index(1)) // unique image digest, path changed
        assertEquals(.5f, map.progress(1), .0001f)
    }
    @Test fun changedBytesAtSameNameCannotProveSamePage() {
        val map = FolderPositionMap(listOf(page("1.jpg", 'a')), listOf(page("1.jpg", 'c')))
        assertTrue(runCatching { map.index(0) }.exceptionOrNull() is IOException)
    }
    @Test fun unreferencedPageCanChangeWithoutMovingOtherPositions() {
        val map = FolderPositionMap(listOf(page("1.jpg", 'a'), page("2.jpg", 'b')), listOf(page("1.jpg", 'c'), page("2.jpg", 'b')))
        assertEquals(1, map.index(1))
    }

    @Test fun renamedPageCannotStealAnotherOldPagesPath() {
        val before = listOf(page("a.jpg", 'a'), page("b.jpg", 'b'))
        val after = listOf(page("b.jpg", 'a'))
        val map = FolderPositionMap(before, after)
        assertTrue(runCatching { map.index(0) }.exceptionOrNull() is IOException)
    }
    @Test fun removedAndAmbiguousPagesAreNotGuessed() {
        val before = listOf(page("a.jpg", 'a'), page("b.jpg", 'b'))
        val after = listOf(page("other.jpg", 'b'), page("duplicate.jpg", 'b'))
        val map = FolderPositionMap(before, after)
        assertTrue(runCatching { map.index(0) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { map.index(1) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { map.index(9) }.exceptionOrNull() is IOException)
    }
    @Test fun renumberingThatReusesExistingNamesIsNeverTreatedAsEditing() {
        val before = listOf(page("1.jpg", 'a'), page("2.jpg", 'b'))
        val after = listOf(page("1.jpg", 'c'), page("2.jpg", 'a'), page("3.jpg", 'b'))
        val map = FolderPositionMap(before, after)
        assertTrue(runCatching { map.index(0) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { map.index(1) }.exceptionOrNull() is IOException)
    }
    @Test fun replacingOneNameWithAnotherOldImageIsAConflict() {
        val before = listOf(page("1.jpg", 'a'), page("2.jpg", 'b'))
        val after = listOf(page("1.jpg", 'b'))
        assertTrue(runCatching { FolderPositionMap(before, after).index(0) }.exceptionOrNull() is IOException)
    }

}
