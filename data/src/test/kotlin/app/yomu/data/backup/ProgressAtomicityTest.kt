package app.yomu.data.backup

import app.yomu.core.database.BookEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.data.library.CoverStore
import app.yomu.data.repository.BookRepositoryImpl
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
class ProgressAtomicityTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = YomuDatabase.build(context, inMemory = true)
    private val repo = BookRepositoryImpl(context, db, CoverStore(context))
    @After fun close() = db.close()
    @Test fun failedBookUpdateRollsBackPositionWrite() = runBlocking {
        val id = db.books().insert(BookEntity(title = "Book", sortTitle = "Book", authorsText = "", seriesName = null,
            seriesId = null, seriesIndex = null, language = null, description = null, publisher = null, format = "TXT",
            uri = "file:///book.txt", isLinked = false, fileSize = 1, fileHash = "h", coverPath = null,
            status = "NEW", isManga = false, addedAt = 1, lastReadAt = null, progress = 0f, readerSettingsJson = null))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_book_progress BEFORE UPDATE OF progress ON books BEGIN SELECT RAISE(ABORT, 'simulated failure'); END")
        assertNotNull(runCatching { repo.saveProgress(id, Locator(chapter = 3, totalProgression = .5f)) }.exceptionOrNull())
        assertNull(db.progress().get(id))
        assertEquals(0f, db.books().get(id)!!.progress, .001f)
        assertEquals("NEW", db.books().get(id)!!.status)
    }
}
