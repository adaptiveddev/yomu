package app.yomu.data.backup

import app.yomu.core.database.BookEntity
import app.yomu.core.database.BookmarkEntity
import app.yomu.core.database.ReadingProgressEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.database.WordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderPositionsTest {
    private val db = YomuDatabase.build(RuntimeEnvironment.getApplication(), inMemory = true)
    private val folderId = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
    private fun page(path: String, hash: Char) = FolderPage(path, hash.toString().repeat(64), 1)
    private val before = FolderEntry(folderId, "Manga", listOf(page("2.jpg", 'a'), page("3.jpg", 'b')))
    private val after = FolderEntry(folderId, "Manga", listOf(page("1.jpg", 'c'), page("2.jpg", 'a'), page("3.jpg", 'b')))
    private suspend fun book(): Long = db.books().insert(BookEntity(title = "Manga", sortTitle = "Manga", authorsText = "", seriesName = null,
        seriesId = null, seriesIndex = null, language = null, description = null, publisher = null, format = "FOLDER", uri = "file:///old", isLinked = false,
        fileSize = 0, fileHash = folderId, coverPath = null, status = "READING", isManga = true, addedAt = 1, lastReadAt = 1, progress = 1f, readerSettingsJson = null))
    @After fun close() = db.close()
    @Test fun preservesReadingPageAndBookmarkAfterInsertion() = runBlocking {
        val id = book()
        db.progress().upsert(ReadingProgressEntity(id, "{\"chapter\":1,\"unknown\":\"kept\"}", 1f, 10))
        db.annotations().insertBookmark(BookmarkEntity(0, id, "{\"chapter\":0}", "Bookmark", 0f, 5))
        db.words().insert(WordEntity(0, "word", "word", "en", null, null, null, id, "Manga", "{\"chapter\":0}", "", 1, 0, 2.5, 0, 0, 0, 0, null))
        FolderPositions.apply(db, id, before, after, "file:///new")
        val progress = db.progress().all().single()
        assertTrue(progress.locatorJson.contains("\"chapter\":2"))
        assertTrue(progress.locatorJson.contains("\"unknown\":\"kept\""))
        assertEquals(1f, progress.progress, .001f)
        val mark = db.annotations().allBookmarks().single()
        assertTrue(mark.locatorJson.contains("\"chapter\":1"))
        assertEquals(.5f, mark.progress, .001f)
        assertEquals("file:///new", db.books().get(id)!!.uri)
        assertTrue(db.words().all().single().locatorJson!!.contains("\"chapter\":1"))
    }
    @Test fun positionMigrationAndBaseCommitAreAtomicAndCannotReplay() = runBlocking {
        val id = book()
        db.progress().upsert(ReadingProgressEntity(id, "{\"chapter\":0}", .1f, 10))
        FolderRevisionCommit.initialize(db, "target", id, before)
        FolderRevisionCommit.apply(db, "target", id, before, after, "file:///new")
        val migrated = db.progress().all().single().locatorJson
        assertTrue(migrated.contains("\"chapter\":1"))
        assertTrue(runCatching { FolderRevisionCommit.apply(db, "target", id, before, after, "file:///new") }.exceptionOrNull() is IOException)
        assertEquals(migrated, db.progress().all().single().locatorJson)
        assertEquals(FolderManifest(folders = listOf(after)), FolderManifestCodec.decode(db.folderSyncBases().forTarget("target").single().revisionJson.toByteArray()))
    }
    @Test fun anotherTargetCannotRemapFromAnAlreadySupersededLocalRevision() = runBlocking {
        val id = book()
        db.progress().upsert(ReadingProgressEntity(id, "{\"chapter\":0}", .1f, 10))
        FolderRevisionCommit.initialize(db, "first", id, before)
        FolderRevisionCommit.initialize(db, "second", id, before)
        FolderRevisionCommit.apply(db, "first", id, before, after, "file:///new")
        val position = db.progress().all().single().locatorJson
        assertTrue(runCatching { FolderRevisionCommit.apply(db, "second", id, before, after, "file:///new") }.exceptionOrNull() is IOException)
        assertEquals(position, db.progress().all().single().locatorJson)
        // Bring a stale target base up to date without remapping from the old local revision.
        FolderRevisionCommit.apply(db, "second", id, after, after, "file:///new", targetBefore = before)
        assertEquals(position, db.progress().all().single().locatorJson)
    }

    @Test fun failedPositionMigrationDoesNotAdvanceRevisionBase() = runBlocking {
        val id = book()
        db.progress().upsert(ReadingProgressEntity(id, "{\"chapter\":1}", 1f, 10))
        FolderRevisionCommit.initialize(db, "target", id, before)
        val onlyFirst = FolderEntry(folderId, "Manga", listOf(page("2.jpg", 'a')))
        assertTrue(runCatching { FolderRevisionCommit.apply(db, "target", id, before, onlyFirst, "file:///new") }.exceptionOrNull() is IOException)
        assertEquals(FolderManifest(folders = listOf(before)), FolderManifestCodec.decode(db.folderSyncBases().forTarget("target").single().revisionJson.toByteArray()))
        assertEquals("file:///old", db.books().get(id)!!.uri)
    }

    @Test fun removedBookmarkedPageRollsBackAllChanges() = runBlocking {
        val id = book()
        db.progress().upsert(ReadingProgressEntity(id, "{\"chapter\":0}", .1f, 10))
        db.annotations().insertBookmark(BookmarkEntity(0, id, "{\"chapter\":1}", "Bookmark", 1f, 5))
        val onlyFirst = FolderEntry(folderId, "Manga", listOf(page("2.jpg", 'a')))
        assertTrue(runCatching { FolderPositions.apply(db, id, before, onlyFirst, "file:///new") }.exceptionOrNull() is IOException)
        assertEquals("file:///old", db.books().get(id)!!.uri)
        assertEquals("{\"chapter\":0}", db.progress().all().single().locatorJson)
        assertEquals("{\"chapter\":1}", db.annotations().allBookmarks().single().locatorJson)
    }
}
