package app.yomu.data.dictionary

import app.yomu.domain.model.DictFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class ImportersTest {
    @TempDir
    lateinit var dir: File

    private fun DictImporter.entries(): List<RawEntry> = mutableListOf<RawEntry>().also { list -> read { list += it } }

    /** Writes a StarDict triple (.ifo/.idx/.dict) and returns the .ifo file. */
    private fun starDict(name: String, words: List<Pair<String, ByteArray>>, sameType: String?, gzipDict: Boolean = false): File {
        val dict = ByteArrayOutputStream()
        val idx = ByteArrayOutputStream()
        DataOutputStream(idx).use { out ->
            words.forEach { (word, data) ->
                out.write(word.toByteArray(Charsets.UTF_8))
                out.write(0)
                out.writeInt(dict.size())
                out.writeInt(data.size)
                dict.write(data)
            }
        }
        File(dir, "$name.idx").writeBytes(idx.toByteArray())
        if (gzipDict) {
            File(dir, "$name.dict.dz").outputStream().use { GZIPOutputStream(it).use { gz -> gz.write(dict.toByteArray()) } }
        } else {
            File(dir, "$name.dict").writeBytes(dict.toByteArray())
        }
        val ifo = buildString {
            appendLine("StarDict's dict ifo file")
            appendLine("version=2.4.2")
            appendLine("bookname=Test $name")
            appendLine("wordcount=${words.size}")
            appendLine("idxfilesize=${idx.size()}")
            sameType?.let { appendLine("sametypesequence=$it") }
        }
        return File(dir, "$name.ifo").apply { writeText(ifo) }
    }

    @Test
    fun `stardict with sametypesequence extracts transcription and definitions`() {
        val ifo = starDict(
            "en-ru",
            listOf(
                "apple" to "[ˈæpəl] яблоко\nяблоня".toByteArray(),
                "run" to "бежать".toByteArray(),
            ),
            sameType = "m",
        )
        val importer = StarDictImporter.create(ifo)!!
        assertEquals("Test en-ru", importer.header.name)
        assertEquals(DictFormat.STARDICT, importer.header.format)
        val entries = importer.entries()
        assertEquals(listOf("apple", "run"), entries.map { it.headword })
        assertEquals("ˈæpəl", entries[0].ipa)
        assertEquals(listOf("яблоко", "яблоня"), entries[0].definitions)
        assertNull(entries[1].ipa)
        assertEquals(listOf("бежать"), entries[1].definitions)
    }

    @Test
    fun `stardict without sametypesequence decodes typed fields and dictzip`() {
        fun typed(vararg parts: Pair<Char, String>): ByteArray = ByteArrayOutputStream().apply {
            parts.forEach { (type, text) ->
                write(type.code)
                write(text.toByteArray(Charsets.UTF_8))
                write(0)
            }
        }.toByteArray()
        val ifo = starDict(
            "typed",
            listOf("cat" to typed('m' to "кошка", 'h' to "<b>кот</b><br>котёнок")),
            sameType = null,
            gzipDict = true,
        )
        val entry = StarDictImporter.create(ifo)!!.entries().single()
        assertEquals("cat", entry.headword)
        assertEquals(listOf("кошка", "кот", "котёнок"), entry.definitions)
    }

    @Test
    fun `stardict without dict file is rejected`() {
        val ifo = starDict("broken", listOf("a" to "b".toByteArray()), sameType = "m")
        File(dir, "broken.dict").delete()
        assertNull(StarDictImporter.create(ifo))
    }

    private val dslText = """
        #NAME "English-Russian Test"
        #INDEX_LANGUAGE "English"
        #CONTENTS_LANGUAGE "Russian"

        apple
        apple{s}
        	[m1][t]ˈæpl[/t] [p]n[/p][/m]
        	[m1][trn]яблоко[/trn][/m]
        	[m2][*][ex][lang id=1033]an apple a day[/lang] — яблоко в день[/ex][/*][/m]
        run
        	[m1][p]v[/p] [trn]бежать, [i]разг.[/i] удирать[/trn][/m]
    """.trimIndent()

    private fun checkDsl(importer: DslImporter) {
        val header = importer.header
        assertEquals("English-Russian Test", header.name)
        assertEquals(DictFormat.DSL, header.format)
        assertEquals("en", header.sourceLang)
        assertEquals("ru", header.targetLang)
        val entries = importer.entries()
        assertEquals(listOf("apple", "apple", "run"), entries.map { it.headword })
        val apple = entries.first()
        assertEquals("ˈæpl", apple.ipa)
        assertEquals("n", apple.pos)
        assertEquals(listOf("яблоко"), apple.definitions)
        assertEquals(listOf("an apple a day — яблоко в день"), apple.examples)
        val run = entries.last()
        assertEquals("v", run.pos)
        assertEquals(listOf("v бежать, разг. удирать"), run.definitions)
    }

    @Test
    fun `dsl in utf-16 with bom`() {
        val file = File(dir, "test.dsl")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + dslText.toByteArray(Charsets.UTF_16LE))
        checkDsl(DslImporter(file))
    }

    @Test
    fun `dsl in utf-8 with bom compressed as dsl dz`() {
        val file = File(dir, "test.dsl.dz")
        file.outputStream().use { GZIPOutputStream(it).use { gz -> gz.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + dslText.toByteArray()) } }
        checkDsl(DslImporter(file))
    }

    @Test
    fun `wiktionary kaikki jsonl`() {
        val file = File(dir, "kaikki-en.jsonl")
        file.writeText(
            """
            {"word":"apple","pos":"noun","sounds":[{"ipa":"/ˈæp.əl/"}],"senses":[{"glosses":["A fruit","The common round fruit"],"examples":[{"text":"She ate an apple."}]},{"glosses":["An apple tree"]}]}
            {"word":"empty","pos":"adj","senses":[]}

            {"word":"run","pos":"verb","senses":[{"raw_glosses":["To move quickly"]}]}
            """.trimIndent(),
        )
        val entries = WiktionaryJsonImporter(file).entries()
        assertEquals(listOf("apple", "run"), entries.map { it.headword })
        val apple = entries[0]
        assertEquals("noun", apple.pos)
        assertEquals("/ˈæp.əl/", apple.ipa)
        assertEquals(listOf("The common round fruit", "An apple tree"), apple.definitions)
        assertEquals(listOf("She ate an apple."), apple.examples)
        assertEquals(listOf("To move quickly"), entries[1].definitions)
    }

    @Test
    fun `wiktionary simple json object`() {
        val file = File(dir, "simple.json")
        file.writeText("""{"hund": ["dog", "hound"], "katze": "cat"}""")
        val entries = WiktionaryJsonImporter(file).entries().associate { it.headword to it.definitions }
        assertEquals(mapOf("hund" to listOf("dog", "hound"), "katze" to listOf("cat")), entries)
    }

    @Test
    fun `jmdict gzip with kanji and kana headwords`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <JMdict>
            <entry>
            <ent_seq>1358280</ent_seq>
            <k_ele>
            <keb>食べる</keb>
            </k_ele>
            <r_ele>
            <reb>たべる</reb>
            </r_ele>
            <sense>
            <pos>&v1;</pos>
            <pos>&vt;</pos>
            <gloss>to eat</gloss>
            <gloss>to live on (e.g. a salary)</gloss>
            </sense>
            </entry>
            <entry>
            <ent_seq>1000320</ent_seq>
            <r_ele>
            <reb>あそこ</reb>
            </r_ele>
            <sense>
            <pos>&pn;</pos>
            <gloss>there &amp; over there</gloss>
            </sense>
            </entry>
            </JMdict>
        """.trimIndent()
        val file = File(dir, "JMdict_e.gz")
        file.outputStream().use { GZIPOutputStream(it).use { gz -> gz.write(xml.toByteArray()) } }
        val entries = JmdictImporter(file).entries()
        assertEquals(listOf("食べる", "たべる", "あそこ"), entries.map { it.headword })
        assertEquals("たべる", entries[0].reading)
        assertEquals("v1", entries[0].pos)
        assertEquals(listOf("to eat", "to live on (e.g. a salary)"), entries[0].definitions)
        assertEquals(listOf("there & over there"), entries[2].definitions)
    }

    @Test
    fun `detector finds every supported format in a folder`() {
        starDict("sd", listOf("a" to "b".toByteArray()), sameType = "m")
        File(dir, "lingvo.dsl").writeText(dslText)
        File(dir, "JMdict_e.xml").writeText("<JMdict></JMdict>")
        File(dir, "nested").mkdirs()
        File(dir, "nested/kaikki.jsonl").writeText("""{"word":"x","senses":[{"glosses":["y"]}]}""")
        File(dir, "readme.txt").writeText("not a dictionary")
        val found = DictionaryDetector.find(dir).map { it.header.format }.sorted()
        assertEquals(listOf(DictFormat.STARDICT, DictFormat.DSL, DictFormat.JMDICT, DictFormat.WIKTIONARY).sorted(), found)
    }

    @Test
    fun `raw entry is stored with normalized headword and separated examples`() {
        val e = RawEntry("Apples", listOf("one", "two"), examples = listOf("ex")).toEntity(7)
        assertEquals(7L, e.dictId)
        assertEquals("Apples", e.headword)
        assertTrue(e.normalized == e.normalized.lowercase())
        assertEquals("one${RawEntry.SEP_DEF}two${RawEntry.SEP_EX}ex", e.body)
    }
}
