package app.yomu.data.tracking

import app.yomu.core.database.BookEntity
import app.yomu.core.database.ChapterEntity
import app.yomu.core.database.CatalogItemEntity
import app.yomu.core.database.TrackEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.domain.tracking.TrackEntry
import app.yomu.domain.tracking.TrackSearchResult
import app.yomu.domain.tracking.TrackService
import app.yomu.domain.tracking.TrackStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
class LocalTrackingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = YomuDatabase.build(context, inMemory = true)
    private val store = TrackerStore(context)
    private val service = TrackService.ANILIST
    private val fake = FakeTracker(store)
    private val manager = TrackerManager(null, db, store, TrackerKeys(), { OkHttpClient() }, null, listOf(fake))
    private val result = TrackSearchResult(22, "Series", url = "https://anilist.co/manga/22", totalChapters = 100)

    private class FakeTracker(store: TrackerStore) : Tracker(TrackService.ANILIST, store, TrackerKeys(), { OkHttpClient() }) {
        var remote: TrackEntry? = null
        var failSave = false
        var failFind = false
        var deleted = false
        val saved = mutableListOf<TrackEntry>()
        override val redirectHost = "local-test"
        override fun authUrl() = "https://example.invalid"
        override suspend fun finishLogin(redirect: String) = Unit
        override suspend fun search(query: String) = emptyList<TrackSearchResult>()
        override suspend fun find(remoteId: Long): TrackEntry? {
            if (failFind) throw IOException("offline")
            return remote
        }
        override suspend fun save(entry: TrackEntry): TrackEntry {
            if (failSave) throw IOException("offline")
            saved += entry
            return entry.also { remote = it }
        }
        override suspend fun delete(entry: TrackEntry) { deleted = true }
    }
    private suspend fun addBook(title: String = "Local") = db.books().insert(BookEntity(
        title = title, sortTitle = title, authorsText = "", seriesName = null, seriesId = null, seriesIndex = null,
        language = null, description = null, publisher = null, format = "CBZ", uri = "file:///$title.cbz", isLinked = false,
        fileSize = 123, fileHash = "hash-$title", coverPath = null, status = "READING", isManga = true,
        addedAt = 1, lastReadAt = 2, progress = .75f, readerSettingsJson = null,
    ))
    @After fun tearDown() { store.logout(service); db.close() }

    @Test fun bindPreservesRemoteAndDoesNotConvertPageProgressIntoChapters() = runBlocking {
        store.saveTokens(service, "token", null, 0)
        val bookId = addBook()
        fake.remote = TrackEntry(service, 22, title = "Series", url = result.url, status = TrackStatus.READING, lastChapterRead = 7f, score = 8f)
        val track = manager.bindBook(bookId, service, result)
        assertEquals(bookId, track.bookId); assertNull(track.itemId)
        assertEquals(7f, track.lastChapterRead, .001f); assertEquals(8f, track.score, .001f)
        assertTrue(fake.saved.isEmpty())
        assertEquals(bookId, db.tracks().observeWithItems().first().single().track.bookId)
    }

    @Test fun localEditsQueueOfflineThenSendAndUnlinkWithoutDeletingRemote() = runBlocking {
        store.saveTokens(service, "token", null, 0)
        val id = addBook(); val track = manager.bindBook(id, service, result)
        store.logout(service)
        assertFalse(manager.update(track, with(TrackerManager) { track.toEntry() }.copy(lastChapterRead = 12f)))
        assertTrue(db.tracks().getBook(id, service.id)!!.pending)
        assertEquals(0, manager.pushPending())
        store.saveTokens(service, "token", null, 0)
        assertEquals(0, manager.pushPending())
        val sent = db.tracks().getBook(id, service.id)!!
        assertEquals(12f, sent.lastChapterRead, .001f); assertFalse(sent.pending); assertEquals(id, sent.bookId)
        manager.unbind(sent, false)
        assertNull(db.tracks().getBook(id, service.id)); assertFalse(fake.deleted)
    }

    @Test fun staleUiEditsAreAppliedToLatestEntry() = runBlocking {
        store.saveTokens(service, "token", null, 0)
        val bookId = addBook(); val stale = manager.bindBook(bookId, service, result)
        assertTrue(manager.edit(stale) { it.copy(score = 9f) })
        assertTrue(manager.edit(stale) { it.copy(lastChapterRead = 14f) })
        val current = db.tracks().getBook(bookId, service.id)!!
        assertEquals(9f, current.score, .001f); assertEquals(14f, current.lastChapterRead, .001f)
        assertEquals(9f, fake.saved.last().score, .001f)
    }

    @Test fun failedSaveStaysPendingAndRetries() = runBlocking {
        store.saveTokens(service, "token", null, 0)
        val bookId = addBook(); val row = manager.bindBook(bookId, service, result)
        fake.failSave = true
        assertFalse(manager.edit(row) { it.copy(lastChapterRead = 8f) })
        assertEquals(1, manager.pushPending())
        assertTrue(db.tracks().getBook(bookId, service.id)!!.pending)
        fake.failSave = false
        assertEquals(0, manager.pushPending())
        val sent = db.tracks().getBook(bookId, service.id)!!
        assertFalse(sent.pending); assertEquals(8f, sent.lastChapterRead, .001f)
    }

    @Test fun syncedBindingRefreshesServiceStateBeforeEditing() = runBlocking {
        val book = addBook()
        val id = db.tracks().insert(TrackEntity(itemId = null, bookId = book, service = service.id, remoteId = 22,
            libraryId = TrackerManager.NEEDS_REMOTE_REFRESH, title = "Series", url = result.url, status = 2))
        val row = db.tracks().byId(id)!!
        val failure = runCatching { manager.edit(row) { it.copy(lastChapterRead = 13f) } }.exceptionOrNull()
        assertTrue(failure is TrackerException)
        assertEquals(TrackerManager.NEEDS_REMOTE_REFRESH, db.tracks().byId(id)!!.libraryId)
        assertTrue(fake.saved.isEmpty())
        store.saveTokens(service, "token", null, 0)
        fake.remote = TrackEntry(service, 22, libraryId = 77, title = "Series", url = result.url,
            status = TrackStatus.READING, score = 9f, lastChapterRead = 12f)
        assertTrue(manager.edit(row) { it.copy(lastChapterRead = 13f) })
        assertEquals(9f, fake.saved.single().score, .001f)
        assertEquals(13f, fake.saved.single().lastChapterRead, .001f)
        assertEquals(77L, fake.saved.single().libraryId)
    }

    @Test fun syncedBindingWithoutRemoteEntryCanBeEditedAfterChecking() = runBlocking {
        val book = addBook()
        val id = db.tracks().insert(TrackEntity(itemId = null, bookId = book, service = service.id, remoteId = 22,
            libraryId = TrackerManager.NEEDS_REMOTE_REFRESH, title = "Series", url = result.url, status = 2))
        store.saveTokens(service, "token", null, 0)
        assertTrue(manager.edit(db.tracks().byId(id)!!) { it.copy(score = 8f) })
        assertEquals(0L, fake.saved.single().libraryId)
        assertEquals(8f, fake.saved.single().score, .001f)
    }

    @Test fun networkErrorNeverClearsUnrefreshedBindingOrSendsDefaults() = runBlocking {
        val book = addBook()
        val id = db.tracks().insert(TrackEntity(itemId = null, bookId = book, service = service.id, remoteId = 22,
            libraryId = TrackerManager.NEEDS_REMOTE_REFRESH, title = "Series", url = result.url, status = 2))
        store.saveTokens(service, "token", null, 0)
        fake.failFind = true
        val row = db.tracks().byId(id)!!
        assertTrue(runCatching { manager.edit(row) { it.copy(score = 7f) } }.exceptionOrNull() is IOException)
        assertTrue(runCatching { manager.update(row, with(TrackerManager) { row.toEntry() }) }.exceptionOrNull() is TrackerException)
        assertEquals(TrackerManager.NEEDS_REMOTE_REFRESH, db.tracks().byId(id)!!.libraryId)
        assertTrue(fake.saved.isEmpty())
    }

    @Test fun automaticOnlineProgressFetchesSyncedBindingBeforeSending() = runBlocking {
        val title = db.catalog().insert(CatalogItemEntity(sourceId = 1, url = "/22", title = "Series", author = null, artist = null,
            description = null, genres = "", status = 0, coverUrl = null, type = "MANGA", favorite = true, categoryId = null,
            addedAt = 1, lastUpdateAt = 1, initialized = true))
        db.catalog().insertChapters(listOf(ChapterEntity(itemId = title, url = "/22/13", name = "13", number = 13f,
            scanlator = null, uploadDate = 0, read = true, bookmarked = false, lastPageRead = 0, downloaded = false,
            localPath = null, isNew = false, sourceOrder = 0, fetchedAt = 0, lastReadAt = 1)))
        val id = db.tracks().insert(TrackEntity(itemId = title, service = service.id, remoteId = 22,
            libraryId = TrackerManager.NEEDS_REMOTE_REFRESH, title = "Series", url = result.url, status = 2))
        assertEquals(0, manager.advance(title))
        assertTrue(fake.saved.isEmpty())
        store.saveTokens(service, "token", null, 0)
        fake.remote = TrackEntry(service, 22, libraryId = 77, title = "Series", url = result.url,
            status = TrackStatus.READING, score = 9f, lastChapterRead = 12f)
        assertEquals(1, manager.advance(title))
        assertEquals(9f, fake.saved.single().score, .001f)
        assertEquals(13f, fake.saved.single().lastChapterRead, .001f)
        assertEquals(77L, db.tracks().byId(id)!!.libraryId)
    }

    @Test fun localAndOnlineIdsDoNotCollideAndDeletionCascadesOnlyOwnTrack() = runBlocking {
        store.saveTokens(service, "token", null, 0)
        val id = addBook()
        val online = db.catalog().insert(CatalogItemEntity(id = id, sourceId = 1, url = "/1", title = "Online", author = null, artist = null,
            description = null, genres = "", status = 0, coverUrl = null, type = "MANGA", favorite = true, categoryId = null,
            addedAt = 1, lastUpdateAt = 1, initialized = true))
        db.tracks().insert(TrackEntity(itemId = online, service = service.id, remoteId = 99, title = "Online", url = "", status = 1))
        manager.bindBook(id, service, result)
        assertEquals(2, db.tracks().observeWithItems().first().size)
        db.books().delete(listOf(id))
        assertEquals(1, db.tracks().all().size); assertEquals(online, db.tracks().all().single().itemId)
    }
}
