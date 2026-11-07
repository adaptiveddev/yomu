package app.yomu.data.backup

import android.content.Context
import androidx.room.Room
import app.yomu.core.database.YomuDatabase
import app.yomu.domain.tracking.TrackStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MihonImporterTest {
    private val context = RuntimeEnvironment.getApplication() as Context
    private val db = Room.inMemoryDatabaseBuilder(context, YomuDatabase::class.java).allowMainThreadQueries().build()

    @After fun close() = db.close()

    private val backup = MihonBackup(
        backupManga = listOf(
            MihonManga(
                source = 2499283573021220255, url = "/manga/one", title = "One", author = "Author", genre = listOf("Action", "Drama"),
                status = 1, thumbnailUrl = "https://example.org/one.jpg", dateAdded = 1000, categories = listOf(1),
                chapters = listOf(
                    MihonChapter(url = "/chapter/2", name = "Chapter 2", chapterNumber = 2f, sourceOrder = 0, lastPageRead = 4),
                    MihonChapter(url = "/chapter/1", name = "Chapter 1", chapterNumber = 1f, sourceOrder = 1, read = true, bookmark = true),
                ),
                history = listOf(MihonHistory(url = "/chapter/2", lastRead = 5000)),
                tracking = listOf(
                    MihonTrack(syncId = 2, libraryId = 77, mediaId = 30013, title = "One", trackingUrl = "https://anilist.co/manga/30013", lastChapterRead = 1f, score = 80f, status = 5),
                    MihonTrack(syncId = 3, mediaId = 5, score = 17f), // Kitsu uses a 0–20 score scale
                ),
            ),
            MihonManga(source = 42, url = "/manga/two", title = "Two", favorite = true, categories = listOf(0, 1)),
        ),
        backupCategories = listOf(MihonCategory("Reading", 0), MihonCategory("Later", 1)),
        backupSources = listOf(MihonSource("MangaDex", 2499283573021220255), MihonSource("Lost source", 42)),
        backupExtensionRepo = listOf(MihonRepo("https://raw.githubusercontent.com/keiyoushi/extensions/repo", "Keiyoushi")),
    )

    private fun importer() = MihonImporter(db, installedSource = { if (it == 2499283573021220255) "MangaDex" else null })

    @Test
    fun importsLibraryChaptersTabsAndTrackers() = runBlocking {
        val parsed = MihonImporter.parse(MihonImporter.encode(backup))
        val r = importer().import(parsed)
        assertEquals(2, r.titles)
        assertEquals(2, r.newTitles)
        assertEquals(2, r.chapters)
        assertEquals(2, r.tabs)
        assertEquals(2, r.tracks)
        assertEquals(listOf(MihonImporter.MissingSource(42, "Lost source", 1)), r.missingSources)
        assertEquals(listOf("https://raw.githubusercontent.com/keiyoushi/extensions/repo"), r.repos)

        val one = db.catalog().find(2499283573021220255, "/manga/one")!!
        assertTrue(one.favorite)
        assertEquals("Action,Drama", one.genres)
        assertEquals(1000L, one.addedAt)
        val chapters = db.catalog().chapters(one.id).associateBy { it.url }
        assertTrue(chapters.getValue("/chapter/1").read)
        assertTrue(chapters.getValue("/chapter/1").bookmarked)
        assertEquals(4, chapters.getValue("/chapter/2").lastPageRead)
        assertEquals(5000L, chapters.getValue("/chapter/2").lastReadAt)

        val tabs = db.collections().bookCollections().associate { it.name to it.id }
        assertEquals(setOf("Reading", "Later"), tabs.keys)
        val two = db.catalog().find(42, "/manga/two")!!
        assertEquals(setOf(tabs.getValue("Later")), db.collections().collectionsOfItem(one.id).toSet())
        assertEquals(tabs.values.toSet(), db.collections().collectionsOfItem(two.id).toSet())

        val tracks = db.tracks().forItem(one.id).associateBy { it.service }
        val track = tracks.getValue(2)
        assertEquals(8.5f, tracks.getValue(3).score)
        assertEquals(5L, tracks.getValue(3).remoteId)
        assertEquals(30013L, track.remoteId)
        assertEquals(77L, track.libraryId)
        assertEquals(8f, track.score)
        assertEquals(TrackStatus.PLAN_TO_READ.code, track.status)
    }

    @Test
    fun importingAgainMergesWithoutDuplicates() = runBlocking {
        importer().import(backup)
        val one = db.catalog().find(2499283573021220255, "/manga/one")!!
        // Read here after the first import: the second one keeps it.
        db.catalog().setRead(db.catalog().chapters(one.id).filter { it.url == "/chapter/2" }.map { it.id }, true)
        val r = importer().import(backup)
        assertEquals(0, r.newTitles)
        assertEquals(0, r.tabs)
        assertEquals(2, db.catalog().all().size)
        val chapters = db.catalog().chapters(one.id)
        assertEquals(2, chapters.size)
        assertTrue(chapters.all { it.read })
        assertEquals(2, db.collections().bookCollections().size)
        assertEquals(2, db.tracks().all().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherFiles() {
        MihonImporter.parse("{\"format\":\"yomu-backup\"}".toByteArray())
    }
}
