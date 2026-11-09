package app.yomu.data.export

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.yomu.domain.export.AnkiDeck
import app.yomu.domain.export.AnkiNoteType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes an Anki package (.apkg): a zip with `collection.anki2` (an SQLite collection in the schema 11 that every Anki
 * version imports, AnkiDroid and AnkiMobile included) and an empty `media` map. Cards are new; note GUIDs come from
 * [app.yomu.domain.export.AnkiNote.key], so importing a newer export updates the notes instead of duplicating them.
 */
object AnkiPackageWriter {
    fun write(deck: AnkiDeck, out: OutputStream, workDir: File) {
        workDir.mkdirs()
        val file = File.createTempFile("anki-", ".anki2", workDir)
        try {
            file.delete()
            buildCollection(deck, file)
            ZipOutputStream(out).apply {
                putNextEntry(ZipEntry("collection.anki2"))
                file.inputStream().use { it.copyTo(this) }
                closeEntry()
                putNextEntry(ZipEntry("media"))
                write("{}".toByteArray())
                closeEntry()
                finish()
            }
            out.flush()
        } finally {
            file.delete()
            File(file.path + "-journal").delete()
        }
    }

    private fun buildCollection(deck: AnkiDeck, file: File) {
        val nowMs = System.currentTimeMillis()
        val now = nowMs / 1000
        val type = deck.type
        val deckId = deckId(deck.name)
        // Android adds no android_metadata table with NO_LOCALIZED_COLLATORS: the file stays a plain collection.
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            SCHEMA.forEach(db::execSQL)
            db.beginTransaction()
            try {
                db.insertOrThrow("col", null, ContentValues().apply {
                    put("id", 1)
                    put("crt", now - now % 86_400)
                    put("mod", nowMs)
                    put("scm", nowMs)
                    put("ver", 11)
                    put("dty", 0)
                    put("usn", 0)
                    put("ls", 0)
                    put("conf", conf(deckId, type).toString())
                    put("models", buildJsonObject { put(type.id.toString(), model(type, deckId, now)) }.toString())
                    put("decks", buildJsonObject {
                        put("1", deckJson(1, "Default", now))
                        put(deckId.toString(), deckJson(deckId, deck.name, now))
                    }.toString())
                    put("dconf", buildJsonObject { put("1", DCONF) }.toString())
                    put("tags", "{}")
                })
                deck.notes.forEachIndexed { i, n ->
                    require(n.fields.size == type.fields.size) { "Note has ${n.fields.size} fields, ${type.title} has ${type.fields.size}" }
                    val id = nowMs + i
                    val sort = stripHtml(n.fields[0])
                    db.insertOrThrow("notes", null, ContentValues().apply {
                        put("id", id)
                        put("guid", guid(n.key))
                        put("mid", type.id)
                        put("mod", now)
                        put("usn", -1)
                        put("tags", if (n.tags.isEmpty()) "" else n.tags.joinToString(" ", " ", " "))
                        put("flds", n.fields.joinToString("\u001f"))
                        put("sfld", sort)
                        put("csum", checksum(sort))
                        put("flags", 0)
                        put("data", "")
                    })
                    db.insertOrThrow("cards", null, ContentValues().apply {
                        put("id", id)
                        put("nid", id)
                        put("did", deckId)
                        put("ord", 0)
                        put("mod", now)
                        put("usn", -1)
                        put("type", 0)
                        put("queue", 0)
                        put("due", i + 1)
                        put("ivl", 0)
                        put("factor", 0)
                        put("reps", 0)
                        put("lapses", 0)
                        put("left", 0)
                        put("odue", 0)
                        put("odid", 0)
                        put("flags", 0)
                        put("data", "")
                    })
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    /** The same deck name gives the same deck id, so repeated exports land in one deck. */
    internal fun deckId(name: String): Long = 1_000_000_000L + (sha1(name).take(8).toLong(16) and 0x3FFFFFFFL)

    internal fun guid(key: String): String = sha1("yomu:$key").take(16)

    /** Anki's duplicate check: the first 8 hex digits of the SHA-1 of the stripped first field. */
    internal fun checksum(text: String): Long = sha1(text).take(8).toLong(16)

    internal fun stripHtml(s: String): String = s.replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim()

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun conf(deckId: Long, type: AnkiNoteType) = buildJsonObject {
        putJsonArray("activeDecks") { add(JsonPrimitive(deckId)) }
        put("curDeck", deckId)
        put("newSpread", 0)
        put("collapseTime", 1200)
        put("timeLim", 0)
        put("estTimes", true)
        put("dueCounts", true)
        put("curModel", type.id.toString())
        put("nextPos", 1)
        put("sortType", "noteFld")
        put("sortBackwards", false)
        put("addToCur", true)
    }

    private fun model(type: AnkiNoteType, deckId: Long, now: Long) = buildJsonObject {
        put("id", type.id)
        put("name", type.title)
        put("type", 0)
        put("mod", now)
        put("usn", -1)
        put("sortf", 0)
        put("did", deckId)
        putJsonArray("tmpls") {
            add(buildJsonObject {
                put("name", "Card 1")
                put("ord", 0)
                put("qfmt", type.front)
                put("afmt", type.back)
                put("did", null as String?)
                put("bqfmt", "")
                put("bafmt", "")
            })
        }
        put("flds", buildJsonArray {
            type.fields.forEachIndexed { i, name ->
                add(buildJsonObject {
                    put("name", name)
                    put("ord", i)
                    put("sticky", false)
                    put("rtl", false)
                    put("font", "Arial")
                    put("size", 20)
                    put("media", JsonArray(emptyList()))
                })
            }
        })
        put("css", AnkiNoteType.CSS)
        put("latexPre", "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n\\usepackage[utf8]{inputenc}\n\\usepackage{amssymb,amsmath}\n\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n")
        put("latexPost", "\\end{document}")
        // The card exists when the first field is not empty.
        put("req", buildJsonArray { add(buildJsonArray { add(JsonPrimitive(0)); add(JsonPrimitive("any")); add(buildJsonArray { add(JsonPrimitive(0)) }) }) })
        put("tags", JsonArray(emptyList()))
        put("vers", JsonArray(emptyList()))
    }

    private fun deckJson(id: Long, name: String, now: Long): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("mod", now)
        put("usn", -1)
        put("desc", "")
        put("dyn", 0)
        put("conf", 1)
        put("collapsed", false)
        put("browserCollapsed", false)
        put("extendNew", 10)
        put("extendRev", 50)
        for (k in listOf("newToday", "revToday", "lrnToday", "timeToday")) putJsonArray(k) { add(JsonPrimitive(0)); add(JsonPrimitive(0)) }
    }

    private val DCONF = buildJsonObject {
        put("id", 1)
        put("name", "Default")
        put("mod", 0)
        put("usn", 0)
        put("maxTaken", 60)
        put("autoplay", true)
        put("timer", 0)
        put("replayq", true)
        put("dyn", false)
        putJsonObject("new") {
            putJsonArray("delays") { add(JsonPrimitive(1)); add(JsonPrimitive(10)) }
            putJsonArray("ints") { add(JsonPrimitive(1)); add(JsonPrimitive(4)); add(JsonPrimitive(7)) }
            put("initialFactor", 2500)
            put("separate", true)
            put("order", 1)
            put("perDay", 20)
            put("bury", true)
        }
        putJsonObject("lapse") {
            putJsonArray("delays") { add(JsonPrimitive(10)) }
            put("mult", 0)
            put("minInt", 1)
            put("leechFails", 8)
            put("leechAction", 0)
        }
        putJsonObject("rev") {
            put("perDay", 200)
            put("ease4", 1.3)
            put("fuzz", 0.05)
            put("minSpace", 1)
            put("ivlFct", 1)
            put("maxIvl", 36500)
            put("bury", true)
        }
    }

    private val SCHEMA = listOf(
        "CREATE TABLE col (id integer primary key, crt integer not null, mod integer not null, scm integer not null, ver integer not null, dty integer not null, usn integer not null, ls integer not null, conf text not null, models text not null, decks text not null, dconf text not null, tags text not null)",
        "CREATE TABLE notes (id integer primary key, guid text not null, mid integer not null, mod integer not null, usn integer not null, tags text not null, flds text not null, sfld integer not null, csum integer not null, flags integer not null, data text not null)",
        "CREATE TABLE cards (id integer primary key, nid integer not null, did integer not null, ord integer not null, mod integer not null, usn integer not null, type integer not null, queue integer not null, due integer not null, ivl integer not null, factor integer not null, reps integer not null, lapses integer not null, left integer not null, odue integer not null, odid integer not null, flags integer not null, data text not null)",
        "CREATE TABLE revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null, ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null, type integer not null)",
        "CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null)",
        "CREATE INDEX ix_notes_usn on notes (usn)",
        "CREATE INDEX ix_cards_usn on cards (usn)",
        "CREATE INDEX ix_revlog_usn on revlog (usn)",
        "CREATE INDEX ix_cards_nid on cards (nid)",
        "CREATE INDEX ix_cards_sched on cards (did, queue, due)",
        "CREATE INDEX ix_revlog_cid on revlog (cid)",
        "CREATE INDEX ix_notes_csum on notes (csum)",
    )
}
