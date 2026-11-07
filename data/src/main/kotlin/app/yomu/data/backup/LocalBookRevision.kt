package app.yomu.data.backup

import android.database.Cursor
import androidx.room.withTransaction
import app.yomu.core.database.YomuDatabase
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * A local-only database fingerprint for future deletion conflict detection. Capture and
 * compare in the transaction that makes a deletion; never send it to other devices.
 * This does not cover bytes of a linked file or cover: callers must separately verify those.
 * This helper is deliberately not wired to deletion until the shared protocol is atomic.
 */
internal object LocalBookRevision {
    suspend fun capture(db: YomuDatabase, bookId: Long): String? = db.withTransaction {
        val book = db.books().get(bookId) ?: return@withTransaction null
        val sql = db.openHelper.readableDatabase
        val digest = MessageDigest.getInstance("SHA-256")
        val out = DataOutputStream(DigestOutputStream(object : OutputStream() { override fun write(value: Int) = Unit; override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit }, digest))
        fun bytes(value: ByteArray) { out.writeInt(value.size); out.write(value) }
        fun field(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
        fun query(name: String, statement: String, vararg args: Any) {
            field(name)
            sql.query(statement, args).use { cursor ->
                out.writeInt(cursor.columnCount)
                cursor.columnNames.forEach(::field)
                while (cursor.moveToNext()) {
                    out.writeBoolean(true)
                    for (i in 0 until cursor.columnCount) {
                        val type = cursor.getType(i)
                        out.writeInt(type)
                        when (type) {
                            Cursor.FIELD_TYPE_NULL -> Unit
                            Cursor.FIELD_TYPE_INTEGER -> out.writeLong(cursor.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT -> out.writeDouble(cursor.getDouble(i))
                            Cursor.FIELD_TYPE_STRING -> field(cursor.getString(i))
                            Cursor.FIELD_TYPE_BLOB -> bytes(cursor.getBlob(i))
                            else -> error("Unknown database field type")
                        }
                    }
                }
                out.writeBoolean(false)
            }
        }
        query("book", "SELECT * FROM books WHERE id = ? ORDER BY id", bookId)
        for (table in listOf("reading_progress", "bookmarks", "highlights", "notes", "words", "tracks", "reading_sessions", "book_authors", "book_tags", "book_collections"))
            query(table, "SELECT * FROM `$table` WHERE bookId = ? ORDER BY rowid", bookId)
        query("authors", "SELECT * FROM authors WHERE id IN (SELECT authorId FROM book_authors WHERE bookId = ?) ORDER BY id", bookId)
        query("tags", "SELECT * FROM tags WHERE id IN (SELECT tagId FROM book_tags WHERE bookId = ?) ORDER BY id", bookId)
        query("collections", "SELECT * FROM collections WHERE id IN (SELECT collectionId FROM book_collections WHERE bookId = ?) ORDER BY id", bookId)
        query("series", "SELECT * FROM series WHERE id IN (SELECT seriesId FROM books WHERE id = ?) ORDER BY id", bookId)
        book.fileHash?.let { hash -> query("folder revision", "SELECT * FROM folder_sync_bases WHERE targetId = ? AND folderId = ? ORDER BY rowid", FolderRevisionCommit.LOCAL, hash) }
        out.flush()
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
