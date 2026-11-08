package app.yomu.data.backup

import java.io.IOException

/**
 * Transform an explicitly revision-qualified remote metadata snapshot before it is merged.
 * The caller must prove which revision the snapshot represents. This is a pure transform.
 * Metadata and its page revisions are published in the same conditional write.
 *
 * References a changed or removed page cannot be remapped: the reading position restarts the
 * book and annotations keep their old locator (the reader refuses stale jumps) — the owning
 * device resolves them through the conflicts screen. No record is dropped or deleted here.
 */
internal object FolderRemotePositions {
    fun translate(state: SyncState, before: FolderEntry, after: FolderEntry): SyncState {
        if (before.id != after.id) throw IOException("Folder metadata identity changed")
        try {
            FolderManifestCodec.validate(FolderManifest(folders = listOf(before)))
            FolderManifestCodec.validate(FolderManifest(folders = listOf(after)))
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid folder metadata revision", e)
        }
        val map = FolderPositionMap(before.pages, after.pages, lenient = true)
        val reset = "{\"chapter\":0,\"totalProgression\":0}"
        return state.copy(
            books = state.books.map { book ->
                if (book.hash != before.id || book.locator == null) book
                else FolderPositions.remapOrNull(book.locator, map)?.let { (locator, progress) -> book.copy(locator = locator, progress = progress) }
                    ?: book.copy(locator = reset, progress = 0f)
            },
            highlights = state.highlights.map { item ->
                if (item.hash != before.id) item
                else {
                    val remapped = FolderPositions.remapOrNull(item.locator, map)
                    val newChapter = map.index(item.chapter)
                    if (remapped == null || newChapter < 0) item // dangling; the reader refuses the stale jump
                    else item.copy(locator = remapped.first, chapter = newChapter)
                }
            },
            notes = state.notes.map { item ->
                if (item.hash != before.id || item.locator == null) item
                else FolderPositions.remapOrNull(item.locator, map)?.let { (locator, _) -> item.copy(locator = locator) } ?: item
            },
            bookmarks = state.bookmarks.map { item ->
                if (item.hash != before.id) item
                else FolderPositions.remapOrNull(item.locator, map)?.let { (locator, progress) -> item.copy(locator = locator, progress = progress) } ?: item
            },
        )
    }
}
