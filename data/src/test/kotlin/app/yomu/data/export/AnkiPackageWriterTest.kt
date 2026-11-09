package app.yomu.data.export

import android.database.sqlite.SQLiteDatabase
import app.yomu.domain.export.AnnotationExporter
import app.yomu.domain.export.WordExporter
import app.yomu.domain.model.BookAnnotations
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.Locator
import app.yomu.domain.model.Word
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnkiPackageWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Unpacks the package; returns its entries and the collection file. */
    private fun unpack(bytes: ByteArray): Pair<Map<String, ByteArray>, File> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes()
            }
        }
        val db = tmp.newFile("collection.anki2").apply { writeBytes(entries.getValue("collection.anki2")) }
        return entries to db
    }

    @Test
    fun writesAnImportableWordDeck() {
        val words = listOf(
            Word(term = "cat", translation = "кошка", context = "The Cat sat", bookTitle = "Book <1>", tags = listOf("animals", "two words")),
            Word(term = "dog", definition = "a pet"),
        )
        val out = ByteArrayOutputStream()
        AnkiPackageWriter.write(WordExporter.ankiDeck(words), out, tmp.newFolder("work"))
        val (entries, file) = unpack(out.toByteArray())
        assertEquals(setOf("collection.anki2", "media"), entries.keys)
        assertEquals("{}", entries.getValue("media").decodeToString())

        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT ver, models, decks FROM col", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(11, c.getInt(0))
                val models = Json.parseToJsonElement(c.getString(1)).jsonObject
                assertEquals(1, models.size)
                val decks = Json.parseToJsonElement(c.getString(2)).jsonObject
                assertTrue(decks.values.any { it.jsonObject["name"].toString() == "\"Yomu\"" })
            }
            db.rawQuery("SELECT flds, sfld, tags, guid FROM notes ORDER BY id", null).use { c ->
                assertEquals(2, c.count)
                c.moveToFirst()
                val fields = c.getString(0).split('\u001f')
                assertEquals(listOf("cat", "кошка", "The <b>Cat</b> sat", "Book &lt;1&gt;"), fields)
                assertEquals("cat", c.getString(1))
                assertEquals(" animals two_words yomu ", c.getString(2))
                assertEquals(AnkiPackageWriter.guid("w::cat"), c.getString(3))
            }
            db.rawQuery("SELECT count(*), min(type), max(queue) FROM cards", null).use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
                assertEquals(0, c.getInt(1))
                assertEquals(0, c.getInt(2))
            }
        } finally {
            db.close()
        }
        // No temporary collection is left behind.
        assertTrue(File(tmp.root, "work").listFiles().isNullOrEmpty())
    }

    @Test
    fun writesHighlightsAsADeck() {
        val book = BookAnnotations(
            "Dune", listOf("Frank Herbert"),
            listOf(Highlight(bookId = 1, locator = Locator(chapter = 0, totalProgression = 0.1f), text = "Fear is the mind-killer.", note = "Litany")),
            emptyList(), emptyList(),
        )
        val out = ByteArrayOutputStream()
        AnkiPackageWriter.write(AnnotationExporter.ankiDeck(listOf(book)), out, tmp.newFolder("work"))
        val (_, file) = unpack(out.toByteArray())
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT flds FROM notes", null).use { c ->
                c.moveToFirst()
                assertEquals(listOf("Fear is the mind-killer.", "Litany", "Dune · Frank Herbert"), c.getString(0).split('\u001f'))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun checksumMatchesAnki() {
        // sha1("cat") = 9d989e8d27dc9e0ec3389fc855f142c3d40f0c50
        assertEquals(0x9d989e8dL, AnkiPackageWriter.checksum("cat"))
        assertEquals("cat & dog", AnkiPackageWriter.stripHtml("<b>cat</b> &amp; dog"))
    }
}
