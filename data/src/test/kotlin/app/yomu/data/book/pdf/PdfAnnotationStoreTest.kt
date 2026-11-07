package app.yomu.data.book.pdf

import android.net.Uri
import androidx.room.Room
import app.yomu.core.database.BookEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.data.repository.toDomain
import app.yomu.data.repository.toEntity
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.HighlightColor
import app.yomu.domain.model.Locator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The file side of PDF highlights: Yomu's update is written, replaced while the file still ends with it, removed with
 * the last highlight, kept apart from changes of other programs, and read back on another device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfAnnotationStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, YomuDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettings(context)

    // pdfium does not run on the JVM: every range gets one rectangle.
    private val geometry = PdfTextGeometry { _, _, ranges -> ranges.map { listOf(floatArrayOf(72f, 700f, 200f, 712f)) } }
    private val store = PdfAnnotationStore(context, db, settings, geometry)
    private lateinit var original: ByteArray
    private lateinit var file: File
    private lateinit var uri: Uri
    private var bookId = 0L

    @Before
    fun setUp() = runBlocking {
        File(context.filesDir, "pdf-annotations").deleteRecursively()
        settings.pdfWriteAnnotations.set(true)
        original = javaClass.getResourceAsStream("/pdf/classic.pdf")!!.use { it.readBytes() }
        file = File(context.cacheDir, "book.pdf").apply { writeBytes(original) }
        uri = Uri.fromFile(file)
        bookId = db.books().insert(book())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun book() = BookEntity(
        title = "Sample", sortTitle = "sample", authorsText = "", seriesName = null, seriesId = null, seriesIndex = null, language = null,
        description = null, publisher = null, format = "PDF", uri = uri.toString(), isLinked = false, fileSize = original.size.toLong(),
        fileHash = "hash", coverPath = null, status = "READING", isManga = false, addedAt = 0, lastReadAt = null, progress = 0f,
        readerSettingsJson = null,
    )

    private suspend fun highlight(color: HighlightColor = HighlightColor.YELLOW, created: Long = 1_000L, database: YomuDatabase = db, book: Long = bookId): Long =
        database.annotations().insertHighlight(
            Highlight(bookId = book, locator = Locator(chapter = 0, offset = 3, endOffset = 9), text = "sample", color = color, note = "a note", createdAt = created).toEntity(),
        )

    private suspend fun recolor(color: HighlightColor) {
        val h = db.annotations().highlights(bookId).single().toDomain()
        db.annotations().updateHighlight(h.copy(color = color, updatedAt = h.updatedAt + 1).toEntity())
    }

    private fun found(bytes: ByteArray = file.readBytes()) = PdfAnnotationWriter.read(ByteArrayPdfBytes(bytes))

    private fun eofs(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1).split("%%EOF").size - 1

    @Test
    fun replacesItsOwnUpdateAndRemovesItWithTheLastHighlight() = runBlocking {
        val id = highlight()
        assertTrue(store.write(bookId))
        val first = file.readBytes()
        assertTrue(first.size > original.size)
        assertArrayEquals("the original bytes stay as they were", original, first.copyOf(original.size))
        val markup = found().single()
        assertEquals("yomu-1000-0-3", markup.id)
        assertEquals(MarkupKind.HIGHLIGHT, markup.kind)
        assertEquals("a note", markup.contents)
        assertEquals("the reader opens the file without Yomu's update", original.size.toLong(), store.renderLimit(bookId, uri))

        assertTrue("nothing changed: nothing written", store.write(bookId))
        assertArrayEquals(first, file.readBytes())

        recolor(HighlightColor.GREEN)
        assertTrue(store.write(bookId))
        val second = file.readBytes()
        assertArrayEquals(original, second.copyOf(original.size))
        assertEquals("replaced, not added", eofs(original) + 1, eofs(second))
        assertEquals("GREEN", (found().single().extra!!["Color"] as PdfName).name)
        assertEquals(original.size.toLong(), store.renderLimit(bookId, uri))

        db.annotations().deleteHighlight(id)
        assertTrue(store.write(bookId))
        assertArrayEquals("back to the original file", original, file.readBytes())
        assertNull(store.renderLimit(bookId, uri))
    }

    @Test
    fun aChangeByAnotherProgramIsNeverCutOff() = runBlocking {
        highlight()
        assertTrue(store.write(bookId))
        file.appendBytes("\n% edited in another app\n".toByteArray())
        val edited = file.readBytes()
        assertNull("the file no longer ends with Yomu's update", store.renderLimit(bookId, uri))

        recolor(HighlightColor.PINK)
        assertTrue(store.write(bookId))
        val bytes = file.readBytes()
        assertArrayEquals(edited, bytes.copyOf(edited.size))
        assertEquals("PINK", (found(bytes).single().extra!!["Color"] as PdfName).name)
        assertEquals(edited.size.toLong(), store.renderLimit(bookId, uri))
    }

    @Test
    fun theSettingTurnsWritingOff() = runBlocking {
        highlight()
        settings.pdfWriteAnnotations.set(false)
        assertFalse(store.write(bookId))
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun highlightsWrittenOnAnotherDeviceComeIntoTheLibrary() = runBlocking {
        highlight(color = HighlightColor.BLUE, created = 5_000L)
        assertTrue(store.write(bookId))

        // Another device: the same file, a library without the highlight, no manifest.
        File(context.filesDir, "pdf-annotations").deleteRecursively()
        val other = Room.inMemoryDatabaseBuilder(context, YomuDatabase::class.java).allowMainThreadQueries().build()
        try {
            val otherBook = other.books().insert(book())
            val otherStore = PdfAnnotationStore(context, other, settings, geometry)
            assertEquals(1, otherStore.importFromFile(otherBook))
            val h = other.annotations().highlights(otherBook).single().toDomain()
            assertEquals(HighlightColor.BLUE, h.color)
            assertEquals(Locator(chapter = 0, offset = 3, endOffset = 9, text = "sample"), h.locator)
            assertEquals(5_000L, h.createdAt)
            assertEquals("sample", h.text)
            assertEquals("a note", h.note)
            assertEquals("a file is looked through once", 0, otherStore.importFromFile(otherBook))

            // Writing there replaces the same annotation instead of adding a copy.
            highlight(color = HighlightColor.PURPLE, created = 6_000L, database = other, book = otherBook)
            assertTrue(otherStore.write(otherBook))
            assertEquals(listOf("yomu-5000-0-3", "yomu-6000-0-3"), found().map { it.id }.sorted())
        } finally {
            other.close()
        }
    }
}
