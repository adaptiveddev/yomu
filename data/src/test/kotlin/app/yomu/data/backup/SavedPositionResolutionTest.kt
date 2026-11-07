package app.yomu.data.backup

import app.yomu.core.database.BookEntity
import app.yomu.core.database.BookmarkEntity
import app.yomu.core.database.NoteEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.data.library.CoverStore
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.data.repository.toJson
import app.yomu.domain.model.Locator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedPositionResolutionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = YomuDatabase.build(context, inMemory = true)
    private val repo = BookRepositoryImpl(context, db, CoverStore(context))
    @After fun close() = db.close()
    private suspend fun book(): Long = db.books().insert(BookEntity(title = "Book", sortTitle = "Book", authorsText = "", seriesName = null,
        seriesId = null, seriesIndex = null, language = null, description = null, publisher = null, format = "FOLDER",
        uri = "file:///folder", isLinked = false, fileSize = 1, fileHash = "folder", coverPath = null,
        status = "NEW", isManga = true, addedAt = 1, lastReadAt = null, progress = 0f, readerSettingsJson = null))

    @Test fun resolvesCurrentRemappedLocatorNotOldUiCopy() = runBlocking {
        val bookId = book()
        val id = db.annotations().insertBookmark(BookmarkEntity(bookId = bookId, locatorJson = Locator(chapter = 2).toJson(), title = "mark", progress = .2f, createdAt = 123))
        val original = db.annotations().bookmark(id)!!
        db.annotations().updateBookmark(original.copy(locatorJson = Locator(chapter = 3).toJson(), progress = .3f))
        assertEquals(3, repo.resolveSavedPosition(bookId, "b:$id:123")!!.chapter)
        assertNotNull(runCatching { repo.resolveSavedPosition(bookId, "b:$id:124") }.exceptionOrNull())
        assertNotNull(runCatching { repo.resolveSavedPosition(bookId + 1, "b:$id:123") }.exceptionOrNull())
        db.annotations().deleteBookmark(id)
        assertNotNull(runCatching { repo.resolveSavedPosition(bookId, "b:$id:123") }.exceptionOrNull())
    }
    @Test fun nullLocatorIsValidButMalformedLocatorAndReferenceFail() = runBlocking {
        val bookId = book()
        val id = db.annotations().insertNote(NoteEntity(bookId = bookId, highlightId = null, locatorJson = null, text = "note", tags = "", createdAt = 10, updatedAt = 10))
        assertNull(repo.resolveSavedPosition(bookId, "n:$id:10"))
        db.annotations().updateNote(db.annotations().note(id)!!.copy(locatorJson = "not json"))
        assertNotNull(runCatching { repo.resolveSavedPosition(bookId, "n:$id:10") }.exceptionOrNull())
        assertNotNull(runCatching { repo.resolveSavedPosition(bookId, "n:$id") }.exceptionOrNull())
    }
}
