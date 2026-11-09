package app.yomu.data.backup

import android.content.Context
import app.yomu.core.database.BookEntity
import app.yomu.core.database.CatalogCollectionCrossRef
import app.yomu.core.database.CatalogItemEntity
import app.yomu.core.database.ChapterEntity
import app.yomu.core.database.CollectionEntity
import app.yomu.core.database.HighlightEntity
import app.yomu.core.database.WordEntity
import app.yomu.core.database.YomuDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Two devices and one shared sync file; every sync goes through JSON like the real file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncMergerTest {
    private val context = RuntimeEnvironment.getApplication() as Context
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private var shared: String? = null
    private var clock = 1_000_000L

    private inner class Device {
        val db: YomuDatabase = YomuDatabase.build(context, inMemory = true)
        var base: SyncBase? = null

        fun sync(deletions: Boolean = true, options: SyncMerger.Options = SyncMerger.Options(deletions = deletions)): Int = runBlocking {
            clock += 1000
            val remote = shared?.let { json.decodeFromString(SyncState.serializer(), it) }
            val out = SyncMerger(db).merge(remote, base, "test", clock, options)
            shared = json.encodeToString(SyncState.serializer(), out.state)
            base = out.base
            out.changed
        }

        fun addBook(hash: String): Long = runBlocking {
            db.books().insert(
                BookEntity(
                    title = "Book $hash", sortTitle = "book $hash", authorsText = "", seriesName = null, seriesId = null, seriesIndex = null,
                    language = "en", description = null, publisher = null, format = "EPUB", uri = "file:///$hash.epub", isLinked = false,
                    fileSize = 1, fileHash = hash, coverPath = null, status = "READING", isManga = false, addedAt = 1, lastReadAt = null,
                    progress = 0f, readerSettingsJson = null,
                ),
            )
        }

        fun highlight(bookId: Long, text: String, at: Long = clock) = runBlocking {
            db.annotations().insertHighlight(HighlightEntity(0, bookId, "{}", 0, text, "yellow", "HIGHLIGHT", false, false, "", null, at, at))
        }

        fun word(term: String) = runBlocking {
            db.words().insert(WordEntity(0, term, term, "en", "t", null, null, null, null, null, "", clock, 0, 2.5, 0, 0, 0, 0, null))
        }

        fun title(url: String, readChapters: Int, tab: String? = null): Long = runBlocking {
            val id = db.catalog().insert(
                CatalogItemEntity(
                    sourceId = 7, url = url, title = "Title $url", author = null, artist = null, description = null, genres = "", status = 0,
                    coverUrl = null, type = "MANGA", favorite = true, categoryId = null, addedAt = clock, lastUpdateAt = 0, initialized = true,
                ),
            )
            db.catalog().insertChapters(
                (1..5).map { n ->
                    ChapterEntity(
                        itemId = id, url = "$url/c$n", name = "Chapter $n", number = n.toFloat(), scanlator = null, uploadDate = 0, read = n <= readChapters,
                        bookmarked = false, lastPageRead = 0, downloaded = false, localPath = null, isNew = false, sourceOrder = 5 - n, fetchedAt = 0,
                        lastReadAt = if (n <= readChapters) clock else null,
                    )
                },
            )
            if (tab != null) {
                val tabId = db.collections().insert(CollectionEntity(name = tab))
                db.collections().addItems(listOf(CatalogCollectionCrossRef(id, tabId)))
            }
            id
        }

        fun highlights() = runBlocking { db.annotations().allHighlights().map { it.text }.sorted() }
        fun words() = runBlocking { db.words().all().map { it.term }.sorted() }
        fun favorite(url: String) = runBlocking { db.catalog().find(7, url)?.favorite == true }
        fun readChapters(url: String) = runBlocking { db.catalog().find(7, url)?.let { i -> db.catalog().chapters(i.id).filter { it.read }.map { it.name }.sorted() } }
        fun tabsOf(url: String) = runBlocking {
            val item = db.catalog().find(7, url) ?: return@runBlocking emptyList<String>()
            val ids = db.collections().collectionsOfItem(item.id)
            db.collections().bookCollections().filter { it.id in ids }.map { it.name }
        }
    }

    private val a = Device()
    private val b = Device()

    @After fun close() {
        a.db.close()
        b.db.close()
    }

    @Test
    fun duplicateBookIdentityStopsMetadataMerge() = runBlocking {
        val id = a.addBook("same")
        val original = a.db.books().get(id)!!
        a.db.books().insert(original.copy(id = 0, uri = "file:///another.epub"))
        a.highlight(id, "keep")
        assertTrue(runCatching { a.sync() }.exceptionOrNull() is java.io.IOException)
        assertNull(shared)
        assertEquals(2, a.db.books().all().size)
        assertEquals(listOf("keep"), a.highlights())
    }

    @Test
    fun switchedOffCategoriesAreNeitherSentNorReceivedNorDeleted() {
        val bookA = a.addBook("granular")
        val bookB = b.addBook("granular")
        a.highlight(bookA, "shared by A")
        a.word("alpha")
        a.sync()
        // B syncs without highlights and words: nothing arrives, its own records stay private.
        val off = SyncMerger.Options(annotations = false, words = false)
        b.highlight(bookB, "private to B")
        b.word("beta")
        b.sync(options = off)
        assertEquals(listOf("private to B"), b.highlights())
        assertEquals(listOf("beta"), b.words())
        // A's records are still in the shared file: they were not taken for deletions.
        a.sync()
        assertEquals(listOf("shared by A"), a.highlights())
        assertEquals(listOf("alpha"), a.words())
        b.sync(options = off)
        // Switched back on, both sides meet.
        b.sync()
        a.sync()
        assertEquals(listOf("private to B", "shared by A"), a.highlights().sorted())
        assertEquals(listOf("alpha", "beta"), b.words().sorted())
    }

    @Test
    fun aCategorySwitchedOffAndOnAgainResumesFromItsBase() {
        val bookA = a.addBook("resume"); b.addBook("resume")
        a.highlight(bookA, "deleted elsewhere")
        a.highlight(bookA, "edited elsewhere")
        a.sync(); b.sync(); a.sync()
        val off = SyncMerger.Options(annotations = false)
        a.sync(options = off)
        // While A does not sync highlights, B deletes one and edits the other.
        runBlocking {
            val hs = b.db.annotations().allHighlights()
            b.db.annotations().deleteHighlight(hs.first { it.text == "deleted elsewhere" }.id)
            val e = hs.first { it.text == "edited elsewhere" }
            b.db.annotations().updateHighlight(e.copy(note = "from B", updatedAt = e.updatedAt + 1))
        }
        b.sync()
        a.sync(options = off)
        assertEquals(listOf("deleted elsewhere", "edited elsewhere"), a.highlights())
        // Switched on again: both changes apply, no false conflict.
        a.sync()
        assertEquals(listOf("edited elsewhere"), a.highlights())
        assertEquals("from B", runBlocking { a.db.annotations().allHighlights().single().note })
    }

    @Test
    fun copiesRecordsBothWays() {
        val bookA = a.addBook("h1")
        val bookB = b.addBook("h1")
        a.highlight(bookA, "from A")
        a.word("alpha")
        a.title("/t1", readChapters = 2, tab = "Reading")
        a.sync()
        b.highlight(bookB, "from B")
        b.sync()
        a.sync()
        assertEquals(listOf("from A", "from B"), a.highlights())
        assertEquals(listOf("from A", "from B"), b.highlights())
        assertEquals(listOf("alpha"), b.words())
        assertTrue(b.favorite("/t1"))
        assertEquals(listOf("Chapter 1", "Chapter 2"), b.readChapters("/t1"))
        assertEquals(listOf("Reading"), b.tabsOf("/t1"))
    }

    @Test
    fun concurrentHighlightEditsConflictRatherThanTrustDeviceClocks() {
        val id = a.addBook("highlight-edit-conflict"); b.addBook("highlight-edit-conflict")
        a.highlight(id, "text"); a.sync(); b.sync()
        runBlocking {
            val ah = a.db.annotations().allHighlights().single()
            val bh = b.db.annotations().allHighlights().single()
            a.db.annotations().updateHighlight(ah.copy(note = "A", updatedAt = 99999999))
            b.db.annotations().updateHighlight(bh.copy(note = "B", updatedAt = 1))
        }
        a.sync(); val before = shared
        assertTrue(runCatching { b.sync() }.exceptionOrNull() is AnnotationSyncProblem)
        assertEquals(before, shared)
        assertEquals("B", runBlocking { b.db.annotations().allHighlights().single().note })
    }

    @Test
    fun remoteNoteEditWinsOverUnchangedLocalEvenWhenTimestampIsOlder() {
        val id = a.addBook("note-clock"); b.addBook("note-clock")
        runBlocking { a.db.annotations().insertNote(app.yomu.core.database.NoteEntity(0, id, null, "{}", "original", "", 100, 500)) }
        a.sync(); b.sync()
        runBlocking { val n = b.db.annotations().allNotes().single(); b.db.annotations().updateNote(n.copy(text = "updated", updatedAt = 1)) }
        b.sync(); a.sync()
        assertEquals("updated", runBlocking { a.db.annotations().allNotes().single().text })
    }

    @Test
    fun remoteBookmarkChangePropagatesAndConcurrentChangesConflict() {
        val id = a.addBook("bookmark-edit"); b.addBook("bookmark-edit")
        runBlocking { a.db.annotations().insertBookmark(app.yomu.core.database.BookmarkEntity(0, id, "{}", "original", 0.1f, 100)) }
        a.sync(); b.sync()
        runBlocking { val m = b.db.annotations().allBookmarks().single(); b.db.annotations().updateBookmark(m.copy(title = "remote")) }
        b.sync(); a.sync()
        assertEquals("remote", runBlocking { a.db.annotations().allBookmarks().single().title })
        b.sync()
        runBlocking {
            val am = a.db.annotations().allBookmarks().single(); val bm = b.db.annotations().allBookmarks().single()
            a.db.annotations().updateBookmark(am.copy(title = "A")); b.db.annotations().updateBookmark(bm.copy(title = "B"))
        }
        a.sync(); val before = shared
        assertTrue(runCatching { b.sync() }.exceptionOrNull() is AnnotationSyncProblem)
        assertEquals(before, shared)
        assertEquals("B", runBlocking { b.db.annotations().allBookmarks().single().title })
    }

    @Test
    fun differingMatchingAnnotationsWithoutBaseFailClosed() {
        val idA = a.addBook("no-base"); val idB = b.addBook("no-base")
        a.highlight(idA, "text", 100); b.highlight(idB, "text", 100)
        runBlocking { val h = b.db.annotations().allHighlights().single(); b.db.annotations().updateHighlight(h.copy(note = "different")) }
        a.sync(); val before = shared
        assertTrue(runCatching { b.sync() }.exceptionOrNull() is AnnotationSyncProblem)
        assertEquals(before, shared)
        assertEquals("different", runBlocking { b.db.annotations().allHighlights().single().note })
    }

    @Test
    fun concurrentRemoteEditAndLocalDeletionCannotDiscardEdit() {
        val book = a.addBook("conflict")
        b.addBook("conflict")
        a.highlight(book, "original")
        a.sync(); b.sync()
        runBlocking {
            val edited = a.db.annotations().allHighlights().single()
            a.db.annotations().updateHighlight(edited.copy(note = "remote edit", updatedAt = clock + 1))
            b.db.annotations().deleteHighlight(b.db.annotations().allHighlights().single().id)
        }
        a.sync()
        val before = shared
        assertNotNull(runCatching { b.sync() }.exceptionOrNull())
        assertEquals(before, shared)
        assertEquals("remote edit", json.decodeFromString(SyncState.serializer(), shared!!).highlights.single().note)
    }

    @Test
    fun incomingDeletionCannotRemoveLocallyEditedHighlightEvenWithOlderClock() {
        val book = a.addBook("conflict")
        b.addBook("conflict")
        a.highlight(book, "original")
        a.sync(); b.sync()
        runBlocking {
            val edited = a.db.annotations().allHighlights().single()
            a.db.annotations().updateHighlight(edited.copy(note = "local edit", updatedAt = edited.updatedAt + 1))
            b.db.annotations().deleteHighlight(b.db.annotations().allHighlights().single().id)
        }
        b.sync()
        val before = shared
        assertNotNull(runCatching { a.sync() }.exceptionOrNull())
        assertEquals(before, shared)
        assertEquals("local edit", runBlocking { a.db.annotations().allHighlights().single().note })
    }

    @Test
    fun concurrentNoteEditAndDeletionIsAConflict() {
        val book = a.addBook("note-conflict"); b.addBook("note-conflict")
        runBlocking { a.db.annotations().insertNote(app.yomu.core.database.NoteEntity(0, book, null, "{}", "original", "", 100, 100)) }
        a.sync(); b.sync()
        runBlocking {
            val note = a.db.annotations().allNotes().single()
            a.db.annotations().updateNote(note.copy(text = "changed", updatedAt = 101))
            b.db.annotations().deleteNote(b.db.annotations().allNotes().single().id)
        }
        a.sync()
        val before = shared
        assertNotNull(runCatching { b.sync() }.exceptionOrNull())
        assertEquals(before, shared)
        assertEquals("changed", json.decodeFromString(SyncState.serializer(), shared!!).notes.single().text)
    }

    @Test
    fun concurrentBookmarkRenameAndDeletionIsAConflict() {
        val book = a.addBook("bookmark-conflict"); b.addBook("bookmark-conflict")
        runBlocking { a.db.annotations().insertBookmark(app.yomu.core.database.BookmarkEntity(0, book, "{}", "original", 0f, 100)) }
        a.sync(); b.sync()
        runBlocking {
            val mark = a.db.annotations().allBookmarks().single()
            a.db.annotations().updateBookmark(mark.copy(title = "changed"))
            b.db.annotations().deleteBookmark(b.db.annotations().allBookmarks().single().id)
        }
        a.sync()
        val before = shared
        assertNotNull(runCatching { b.sync() }.exceptionOrNull())
        assertEquals(before, shared)
        assertEquals("changed", json.decodeFromString(SyncState.serializer(), shared!!).bookmarks.single().title)
    }

    @Test
    fun provenDeletionWorksEvenWhenOriginalDeviceClockWasAhead() {
        val book = a.addBook("clock-skew"); b.addBook("clock-skew")
        a.highlight(book, "original", 9_000_000L)
        a.sync(); b.sync()
        runBlocking { b.db.annotations().deleteHighlight(b.db.annotations().allHighlights().single().id) }
        b.sync(); a.sync()
        assertTrue(a.highlights().isEmpty())
    }

    @Test
    fun localOnlyHighlightDataAndFingerprintsAreNeverPublished() {
        val book = a.addBook("private-data")
        a.highlight(book, "original")
        runBlocking {
            val highlight = a.db.annotations().allHighlights().single()
            a.db.annotations().updateHighlight(highlight.copy(isFavorite = true))
            a.db.annotations().insertNote(app.yomu.core.database.NoteEntity(0, book, highlight.id, "{}", "local private note", "", 100, 100))
        }
        a.sync()
        assertFalse(shared!!.contains("local private note"))
        assertFalse(shared!!.contains("privateAnnotationRevisions"))
        assertFalse(shared!!.contains("isFavorite"))
    }

    @Test
    fun incomingDeletionDoesNotErasePreviouslyExistingLocalOnlyHighlightNote() {
        val book = a.addBook("private-note"); b.addBook("private-note")
        a.highlight(book, "original")
        runBlocking {
            val highlight = a.db.annotations().allHighlights().single()
            a.db.annotations().insertNote(app.yomu.core.database.NoteEntity(0, book, highlight.id, "{}", "private", "", 100, 100))
        }
        a.sync(); b.sync()
        runBlocking { b.db.annotations().deleteHighlight(b.db.annotations().allHighlights().single().id) }
        b.sync()
        assertNotNull(runCatching { a.sync() }.exceptionOrNull())
        assertEquals("private", runBlocking { a.db.annotations().allNotes().single().text })
        assertEquals(listOf("original"), a.highlights())
    }

    @Test
    fun incomingDeletionDoesNotEraseNewLocalFavorite() {
        val book = a.addBook("favorite-conflict"); b.addBook("favorite-conflict")
        a.highlight(book, "original"); a.sync(); b.sync()
        runBlocking {
            val highlight = a.db.annotations().allHighlights().single()
            a.db.annotations().updateHighlight(highlight.copy(isFavorite = true))
            b.db.annotations().deleteHighlight(b.db.annotations().allHighlights().single().id)
        }
        b.sync()
        assertNotNull(runCatching { a.sync() }.exceptionOrNull())
        assertTrue(runBlocking { a.db.annotations().allHighlights().single().isFavorite })
    }

    @Test
    fun legacyRemoteDeletionWithoutProofDoesNotDeleteLocalAnnotation() {
        val book = a.addBook("old-marker"); a.highlight(book, "original"); a.sync()
        val old = json.decodeFromString(SyncState.serializer(), shared!!)
        val mark = old.highlights.single()
        val key = SyncKeys.highlight(mark.hash, mark.createdAt, mark.text)
        shared = json.encodeToString(SyncState.serializer(), old.copy(highlights = emptyList(), deleted = mapOf(key to clock + 100)))
        val before = shared
        assertNotNull(runCatching { a.sync() }.exceptionOrNull())
        assertEquals(before, shared)
        assertEquals(listOf("original"), a.highlights())
    }

    @Test
    fun collidingLocalHighlightKeysAreRejectedBeforeWriting() {
        val book = a.addBook("local-collision")
        a.highlight(book, "Aa", 100); a.highlight(book, "BB", 100)
        assertNotNull(runCatching { a.sync() }.exceptionOrNull())
        assertNull(shared)
        assertEquals(listOf("Aa", "BB"), a.highlights())
    }

    @Test
    fun deletionsReachTheOtherDevice() {
        val bookA = a.addBook("h1")
        b.addBook("h1")
        a.highlight(bookA, "keep")
        a.highlight(bookA, "drop")
        a.word("alpha")
        a.word("beta")
        a.title("/t1", readChapters = 1)
        a.title("/t2", readChapters = 0)
        a.sync()
        b.sync()
        // B deletes a highlight, a word and a title.
        runBlocking {
            val h = b.db.annotations().allHighlights().first { it.text == "drop" }
            b.db.annotations().deleteHighlight(h.id)
            b.db.words().delete(listOf(b.db.words().find("beta", "en")!!.id))
            val t2 = b.db.catalog().find(7, "/t2")!!
            b.db.catalog().setFavorite(t2.id, false, null, clock)
        }
        b.sync()
        a.sync()
        assertEquals(listOf("keep"), a.highlights())
        assertEquals(listOf("alpha"), a.words())
        assertTrue(a.favorite("/t1"))
        assertFalse(a.favorite("/t2"))
        // And the deletions do not come back.
        b.sync()
        assertEquals(listOf("keep"), b.highlights())
        assertFalse(b.favorite("/t2"))
    }

    @Test
    fun unreadAndReadMarksFollowTheLastChange() {
        a.title("/t1", readChapters = 3)
        b.title("/t1", readChapters = 0)
        a.sync()
        b.sync()
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), b.readChapters("/t1"))
        runBlocking {
            val item = b.db.catalog().find(7, "/t1")!!
            val c3 = b.db.catalog().chapters(item.id).first { it.name == "Chapter 3" }
            val c4 = b.db.catalog().chapters(item.id).first { it.name == "Chapter 4" }
            b.db.catalog().setRead(listOf(c3.id), false)
            b.db.catalog().setRead(listOf(c4.id), true)
        }
        b.sync()
        a.sync()
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 4"), a.readChapters("/t1"))
    }

    @Test
    fun aFreshDeviceOrAnOldFileDeletesNothing() {
        val bookA = a.addBook("h1")
        a.highlight(bookA, "one")
        a.word("alpha")
        a.sync()
        // The shared file is replaced by one without these records (written by 1.x, for example): nothing is deleted.
        shared = json.encodeToString(SyncState.serializer(), SyncState(version = 1))
        a.sync()
        assertEquals(listOf("one"), a.highlights())
        assertEquals(listOf("alpha"), a.words())
        // And they are in the file again.
        val state = json.decodeFromString(SyncState.serializer(), shared!!)
        assertEquals(1, state.highlights.size)
        assertEquals(1, state.words.size)
    }

    @Test
    fun annotationsOfBooksMissingHereStayInTheFile() {
        val bookA = a.addBook("h2")
        a.highlight(bookA, "only on A")
        a.sync()
        b.sync() // B does not have the book
        assertTrue(b.highlights().isEmpty())
        val state = json.decodeFromString(SyncState.serializer(), shared!!)
        assertEquals(listOf("only on A"), state.highlights.map { it.text })
        // B gets the book later: the highlight arrives.
        b.addBook("h2")
        b.sync()
        assertEquals(listOf("only on A"), b.highlights())
    }

    @Test
    fun removingABookIsNotADeletionOfItsHighlights() {
        val bookA = a.addBook("h1")
        val bookB = b.addBook("h1")
        a.highlight(bookA, "one")
        a.sync()
        b.sync()
        runBlocking { b.db.books().delete(listOf(bookB)) }
        b.sync()
        a.sync()
        assertEquals(listOf("one"), a.highlights())
        val state = json.decodeFromString(SyncState.serializer(), shared!!)
        assertTrue(state.deleted.isEmpty())
    }

    @Test
    fun withoutDeletionsOldBehaviourStays() {
        val bookA = a.addBook("h1")
        b.addBook("h1")
        a.highlight(bookA, "one")
        a.sync()
        b.sync()
        runBlocking { b.db.annotations().deleteHighlight(b.db.annotations().allHighlights().first().id) }
        b.sync(deletions = false)
        assertEquals(listOf("one"), b.highlights()) // brought back from the file
        a.sync()
        assertEquals(listOf("one"), a.highlights())
    }

    @Test
    fun deletedTabIsRemovedEverywhere() {
        a.title("/t1", readChapters = 0, tab = "Later")
        a.sync()
        b.sync()
        assertEquals(listOf("Later"), b.tabsOf("/t1"))
        runBlocking { b.db.collections().delete(b.db.collections().bookCollections().first { it.name == "Later" }.id) }
        b.sync()
        a.sync()
        assertNull(runBlocking { a.db.collections().bookCollections().firstOrNull { it.name == "Later" } })
        assertTrue(a.favorite("/t1"))
        assertNotNull(runBlocking { a.db.catalog().find(7, "/t1") })
    }

    @Test
    fun keysAndThreeWayFlags() {
        assertEquals("h1", SyncKeys.bookOf(SyncKeys.highlight("h1", 5, "x")))
        assertEquals("h1", SyncKeys.bookOf(SyncKeys.bookmark("h1", 5)))
        assertNull(SyncKeys.bookOf(SyncKeys.word("en", "x")))
        assertNull(SyncKeys.bookOf(SyncKeys.title(1, "/a|b")))
        assertTrue(merge3(local = false, remote = true, base = null))
        assertFalse(merge3(local = false, remote = true, base = true))
        assertTrue(merge3(local = true, remote = false, base = false))
        assertTrue(merge3(local = true, remote = null, base = false))
        assertEquals(setOf("b", "c"), merge3(setOf("a", "b"), setOf("b", "c"), setOf("a", "b")))
    }
    @Test fun folderIdentityContainingDelimiterDoesNotDeleteAnotherBooksAnnotations() = runBlocking {
        val device = Device()
        try {
            val presentHash = "dir:content://provider/folder"
            val absentHash = "$presentHash|other"
            device.addBook(presentHash)
            val highlight = SyncHighlight(absentHash, "{}", 0, "annotation", "yellow", "HIGHLIGHT", false, "", null, 100, 100)
            val key = SyncKeys.highlight(absentHash, 100, "annotation")
            val base = SyncBase("test", keys = setOf(key))
            val state = SyncState(highlights = listOf(highlight))
            val result = SyncMerger(device.db).merge(state, base, "test", 1000, SyncMerger.Options())
            assertEquals(listOf(highlight), result.state.highlights)
            assertFalse(result.state.deleted.containsKey(key))
            assertEquals(absentHash, SyncKeys.bookOf(key))
        } finally { device.db.close() }
    }

    @Test fun annotationDeletionWorksForFolderIdentityContainingDelimiter() = runBlocking {
        val device = Device()
        try {
            val hash = "dir:content://provider/folder|other"
            device.addBook(hash)
            val mark = SyncBookmark(hash, "{}", "mark", 0f, 100)
            val key = SyncKeys.bookmark(hash, 100)
            val result = SyncMerger(device.db).merge(SyncState(bookmarks = listOf(mark)), SyncBase("test", keys = setOf(key), annotationRevisions = mapOf(key to AnnotationRevisions.bookmark(mark))), "test", 1000, SyncMerger.Options())
            assertTrue(result.state.bookmarks.isEmpty())
            assertEquals(1000L, result.state.deleted[key])
            assertEquals(hash, SyncKeys.bookOf(key))
        } finally { device.db.close() }
    }

    @Test fun legacyAnnotationDeletionWithoutRevisionIsRejected() = runBlocking {
        val device = Device()
        try {
            val hash = "hash"; device.addBook(hash)
            val mark = SyncBookmark(hash, "{}", "mark", 0f, 100)
            val key = SyncKeys.bookmark(hash, 100)
            val state = SyncState(bookmarks = listOf(mark))
            val error = runCatching { SyncMerger(device.db).merge(state, SyncBase("test", keys = setOf(key)), "test", 1000, SyncMerger.Options()) }.exceptionOrNull()
            assertTrue(error is java.io.IOException)
            assertEquals(listOf(mark), state.bookmarks)
        } finally { device.db.close() }
    }

    @Test fun collidingHighlightTextHashesCannotOverwriteAnAnnotation() = runBlocking {
        val device = Device()
        try {
            val book = device.addBook("hash")
            device.highlight(book, "Aa", 100)
            assertEquals("Aa".hashCode(), "BB".hashCode())
            val remote = SyncHighlight("hash", "{}", 0, "BB", "yellow", "HIGHLIGHT", false, "", null, 100, 100)
            val failure = runCatching { SyncMerger(device.db).merge(SyncState(highlights = listOf(remote)), null, "test", 1000, SyncMerger.Options()) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(listOf("Aa"), device.highlights())
        } finally { device.db.close() }
    }

}
