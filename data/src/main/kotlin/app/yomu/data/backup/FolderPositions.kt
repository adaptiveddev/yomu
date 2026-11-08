package app.yomu.data.backup

import androidx.room.withTransaction
import app.yomu.core.database.YomuDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/** Position migration is atomic with changing the local folder URI. */
internal object FolderPositions {
    private val json = Json { ignoreUnknownKeys = true }

    internal fun remap(locator: String, map: FolderPositionMap): Pair<String, Float> =
        remapOrNull(locator, map) ?: throw IOException("A saved page position cannot be remapped")

    /** Null when the reference points at a changed or removed page (lenient maps only). */
    internal fun remapOrNull(locator: String, map: FolderPositionMap): Pair<String, Float>? {
        val obj = try { json.parseToJsonElement(locator).jsonObject } catch (e: Exception) { throw IOException("Invalid saved page position", e) }
        val chapter = try { obj["chapter"]?.jsonPrimitive?.intOrNull ?: if ("chapter" !in obj) 0 else throw IOException("Invalid page number") }
            catch (e: Exception) { throw IOException("Invalid page number", e) }
        val index = map.index(chapter)
        if (index < 0) return null
        val progress = map.progress(index)
        val next = JsonObject(obj + mapOf("chapter" to JsonPrimitive(index), "totalProgression" to JsonPrimitive(progress)))
        return next.toString() to progress
    }

    /** Check every currently saved reference before publishing a changed folder index.
     * Apply repeats these checks transactionally because local state can change afterwards. */
    suspend fun preflight(db: YomuDatabase, bookId: Long, before: FolderEntry, after: FolderEntry) = db.withTransaction {
        if (before.id != after.id) throw IOException("Folder identity changed")
        val book = db.books().get(bookId) ?: throw IOException("Folder book no longer exists")
        if (book.format != "FOLDER" || book.fileHash != before.id) throw IOException("Folder book identity changed")
        val map = FolderPositionMap(before.pages, after.pages)
        val progress = db.progress().get(bookId)
        if (progress == null && book.progress != 0f) throw IOException("Folder has progress without a saved page position")
        progress?.let { remap(it.locatorJson, map) }
        val annotations = db.annotations()
        for (mark in annotations.bookmarks(bookId)) remap(mark.locatorJson, map)
        for (highlight in annotations.highlights(bookId)) {
            remap(highlight.locatorJson, map)
            map.index(highlight.chapter)
        }
        for (note in annotations.notes(bookId)) note.locatorJson?.let { remap(it, map) }
        for (word in db.words().all().filter { it.bookId == bookId }) word.locatorJson?.let { remap(it, map) }
    }

    suspend fun apply(db: YomuDatabase, bookId: Long, before: FolderEntry, after: FolderEntry, newUri: String, isLinked: Boolean = false) = db.withTransaction {
        if (before.id != after.id) throw IOException("Folder identity changed")
        val book = db.books().get(bookId) ?: throw IOException("Folder book no longer exists")
        if (book.format != "FOLDER" || book.fileHash != before.id) throw IOException("Folder book identity changed")
        val map = FolderPositionMap(before.pages, after.pages)
        val progress = db.progress().all().find { it.bookId == bookId }
        if (progress == null && book.progress != 0f) throw IOException("Folder has progress without a saved page position")
        var newProgress = book.progress
        if (progress != null) {
            val (locator, fraction) = remap(progress.locatorJson, map)
            db.progress().upsert(progress.copy(locatorJson = locator, progress = fraction))
            newProgress = fraction
        }
        val annotations = db.annotations()
        for (mark in annotations.allBookmarks().filter { it.bookId == bookId }) {
            val (locator, fraction) = remap(mark.locatorJson, map)
            annotations.updateBookmark(mark.copy(locatorJson = locator, progress = fraction))
        }
        for (highlight in annotations.allHighlights().filter { it.bookId == bookId }) {
            val (locator, _) = remap(highlight.locatorJson, map)
            val newChapter = map.index(highlight.chapter)
            annotations.updateHighlight(highlight.copy(locatorJson = locator, chapter = newChapter))
        }
        for (note in annotations.allNotes().filter { it.bookId == bookId && it.locatorJson != null }) {
            val (locator, _) = remap(note.locatorJson!!, map)
            annotations.updateNote(note.copy(locatorJson = locator))
        }
        val words = db.words()
        for (word in words.all().filter { it.bookId == bookId && it.locatorJson != null }) {
            val (locator, _) = remap(word.locatorJson!!, map)
            words.update(word.copy(locatorJson = locator))
        }
        db.books().update(book.copy(uri = newUri, isLinked = isLinked, progress = newProgress))
    }

    /**
     * Applies a changed page revision with visible concessions instead of failing: unambiguous
     * references are remapped; the reading position on a changed/removed page restarts the book;
     * annotations on such pages keep their old locator (the reader refuses stale jumps) and are
     * reported for the user to clean up. Returns the reported page paths.
     */
    suspend fun applyLenient(db: YomuDatabase, bookId: Long, before: FolderEntry, after: FolderEntry, newUri: String, isLinked: Boolean = false): List<String> = db.withTransaction {
        if (before.id != after.id) throw IOException("Folder identity changed")
        val book = db.books().get(bookId) ?: throw IOException("Folder book no longer exists")
        if (book.format != "FOLDER" || book.fileHash != before.id) throw IOException("Folder book identity changed")
        val map = FolderPositionMap(before.pages, after.pages, lenient = true)
        var newProgress = book.progress
        val progress = db.progress().all().find { it.bookId == bookId }
        if (progress != null) {
            val remapped = remapOrNull(progress.locatorJson, map)
            if (remapped == null) {
                val reset = JsonObject(mapOf("chapter" to JsonPrimitive(0), "totalProgression" to JsonPrimitive(0f)))
                db.progress().upsert(progress.copy(locatorJson = reset.toString(), progress = 0f))
                newProgress = 0f
            } else {
                val (locator, fraction) = remapped
                db.progress().upsert(progress.copy(locatorJson = locator, progress = fraction))
                newProgress = fraction
            }
        }
        val annotations = db.annotations()
        for (mark in annotations.allBookmarks().filter { it.bookId == bookId }) {
            val remapped = remapOrNull(mark.locatorJson, map) ?: continue // reported, kept dangling
            val (locator, fraction) = remapped
            annotations.updateBookmark(mark.copy(locatorJson = locator, progress = fraction))
        }
        for (highlight in annotations.allHighlights().filter { it.bookId == bookId }) {
            val remapped = remapOrNull(highlight.locatorJson, map)
            val newChapter = if (remapped == null) -1 else map.index(highlight.chapter)
            if (newChapter < 0) continue // reported, kept dangling
            val (locator, _) = remapped!!
            annotations.updateHighlight(highlight.copy(locatorJson = locator, chapter = newChapter))
        }
        for (note in annotations.allNotes().filter { it.bookId == bookId && it.locatorJson != null }) {
            val remapped = remapOrNull(note.locatorJson!!, map) ?: continue // reported, kept dangling
            val (locator, _) = remapped
            annotations.updateNote(note.copy(locatorJson = locator))
        }
        val words = db.words()
        for (word in words.all().filter { it.bookId == bookId && it.locatorJson != null }) {
            val remapped = remapOrNull(word.locatorJson!!, map) ?: continue // reported, kept dangling
            val (locator, _) = remapped
            words.update(word.copy(locatorJson = locator))
        }
        db.books().update(book.copy(uri = newUri, isLinked = isLinked, progress = newProgress))
        map.unresolved.distinct()
    }
}

/** Commits positions and their merge base together, so retrying after a crash cannot remap them twice. */
internal object FolderRevisionCommit {
    // Independent of the remote target: positions must not be remapped twice when a book uses two targets.
    internal const val LOCAL = "__yomu_folder_positions__"
    private fun encoded(entry: FolderEntry): String = FolderManifestCodec.encode(FolderManifest(folders = listOf(entry))).toString(Charsets.UTF_8)
    private suspend fun base(db: YomuDatabase, target: String, id: String) = db.folderSyncBases().forTarget(target).singleOrNull { it.folderId == id }

    /** Record the exact page revision at import, in the same transaction as the new book row. */
    suspend fun initializeLocal(db: YomuDatabase, bookId: Long, entry: FolderEntry) = db.withTransaction {
        val book = db.books().get(bookId) ?: throw IOException("Folder book no longer exists")
        if (book.format != "FOLDER" || book.fileHash != entry.id) throw IOException("Folder book identity changed")
        if (base(db, LOCAL, entry.id) != null) throw IOException("Folder position revision already exists")
        db.folderSyncBases().upsert(app.yomu.core.database.FolderSyncBaseEntity(LOCAL, entry.id, encoded(entry)))
    }

    suspend fun initialize(db: YomuDatabase, target: String, bookId: Long, entry: FolderEntry) = db.withTransaction {
        require(target != LOCAL)
        val book = db.books().get(bookId) ?: throw IOException("Folder book no longer exists")
        if (book.format != "FOLDER" || book.fileHash != entry.id) throw IOException("Folder book identity changed")
        if (base(db, target, entry.id) != null) throw IOException("Folder revision base already exists")
        val local = base(db, LOCAL, entry.id)
        if (local != null && local.revisionJson != encoded(entry)) throw IOException("Local folder positions refer to a different revision")
        if (local == null) db.folderSyncBases().upsert(app.yomu.core.database.FolderSyncBaseEntity(LOCAL, entry.id, encoded(entry)))
        db.folderSyncBases().upsert(app.yomu.core.database.FolderSyncBaseEntity(target, entry.id, encoded(entry)))
    }

    suspend fun localRevision(db: YomuDatabase, id: String): FolderEntry? {
        val stored = base(db, LOCAL, id) ?: return null
        return FolderManifestCodec.decode(stored.revisionJson.toByteArray()).folders.singleOrNull()
            ?: throw IOException("Invalid local folder revision base")
    }

    suspend fun apply(db: YomuDatabase, target: String, bookId: Long, before: FolderEntry, after: FolderEntry, newUri: String, targetBefore: FolderEntry = before, isLinked: Boolean = false) = db.withTransaction {
        require(target != LOCAL)
        if (before.id != targetBefore.id) throw IOException("Folder identity changed")
        val remoteBase = base(db, target, before.id) ?: throw IOException("Folder revision base is missing")
        val localBase = base(db, LOCAL, before.id) ?: throw IOException("Local folder position base is missing")
        if (remoteBase.revisionJson != encoded(targetBefore) || localBase.revisionJson != encoded(before))
            throw IOException("Folder revision changed; retry the merge")
        FolderPositions.apply(db, bookId, before, after, newUri, isLinked)
        db.folderSyncBases().upsert(remoteBase.copy(revisionJson = encoded(after)))
        db.folderSyncBases().upsert(localBase.copy(revisionJson = encoded(after)))
    }

    /** [apply] with visible concessions; returns the reported page paths. */
    suspend fun applyLenient(db: YomuDatabase, target: String, bookId: Long, before: FolderEntry, after: FolderEntry, newUri: String, targetBefore: FolderEntry = before, isLinked: Boolean = false): List<String> = db.withTransaction {
        require(target != LOCAL)
        if (before.id != targetBefore.id) throw IOException("Folder identity changed")
        val remoteBase = base(db, target, before.id) ?: throw IOException("Folder revision base is missing")
        val localBase = base(db, LOCAL, before.id) ?: throw IOException("Local folder position base is missing")
        if (remoteBase.revisionJson != encoded(targetBefore) || localBase.revisionJson != encoded(before))
            throw IOException("Folder revision changed; retry the merge")
        val report = FolderPositions.applyLenient(db, bookId, before, after, newUri, isLinked)
        db.folderSyncBases().upsert(remoteBase.copy(revisionJson = encoded(after)))
        db.folderSyncBases().upsert(localBase.copy(revisionJson = encoded(after)))
        report
    }
}
