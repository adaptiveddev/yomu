package app.yomu.data.backup

import java.io.IOException

/**
 * Transform an explicitly revision-qualified remote metadata snapshot before it is merged.
 * The caller must prove which revision the snapshot represents. This is a pure transform: an
 * unresolvable page aborts the whole snapshot, without changing either database or shared file.
 * Metadata and its page revisions are published in the same conditional write.
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
        val map = FolderPositionMap(before.pages, after.pages)
        return state.copy(
            books = state.books.map { book ->
                if (book.hash != before.id || book.locator == null) book
                else FolderPositions.remap(book.locator, map).let { (locator, progress) -> book.copy(locator = locator, progress = progress) }
            },
            highlights = state.highlights.map { item ->
                if (item.hash != before.id) item
                else item.copy(locator = FolderPositions.remap(item.locator, map).first, chapter = map.index(item.chapter))
            },
            notes = state.notes.map { item ->
                if (item.hash != before.id || item.locator == null) item
                else item.copy(locator = FolderPositions.remap(item.locator, map).first)
            },
            bookmarks = state.bookmarks.map { item ->
                if (item.hash != before.id) item
                else FolderPositions.remap(item.locator, map).let { (locator, progress) -> item.copy(locator = locator, progress = progress) }
            },
        )
    }
}
