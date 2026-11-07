package app.yomu.data.backup

import app.yomu.core.database.BookEntity
import app.yomu.core.database.BookmarkEntity
import app.yomu.core.database.ReadingProgressEntity
import app.yomu.core.database.TrackEntity
import app.yomu.core.database.YomuDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalBookRevisionTest {
    @Test fun fingerprintsChangeForProgressBookmarksAndPendingTrackerState() = runBlocking {
        val db = YomuDatabase.build(RuntimeEnvironment.getApplication(), inMemory = true)
        try {
            val id = db.books().insert(BookEntity(title = "Book", sortTitle = "Book", authorsText = "", seriesName = null, seriesId = null,
                seriesIndex = null, language = null, description = null, publisher = null, format = "TXT", uri = "file:///book.txt",
                isLinked = true, fileSize = 1, fileHash = "hash", coverPath = null, status = "NEW", isManga = false,
                addedAt = 1, lastReadAt = null, progress = 0f, readerSettingsJson = null))
            val initial = LocalBookRevision.capture(db, id)!!
            assertEquals(initial, LocalBookRevision.capture(db, id))
            db.progress().upsert(ReadingProgressEntity(id, "{}", .2f, 1))
            val progress = LocalBookRevision.capture(db, id)!!
            assertNotEquals(initial, progress)
            db.annotations().insertBookmark(BookmarkEntity(0, id, "{}", "mark", .2f, 1))
            val bookmark = LocalBookRevision.capture(db, id)!!
            assertNotEquals(progress, bookmark)
            db.tracks().insert(TrackEntity(itemId = null, bookId = id, service = 2, remoteId = 123, title = "Book", url = "", status = 1, pending = true))
            assertNotEquals(bookmark, LocalBookRevision.capture(db, id))
            db.books().delete(listOf(id))
            assertNull(LocalBookRevision.capture(db, id))
        } finally { db.close() }
    }
}
