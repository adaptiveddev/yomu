package app.yomu.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest

/**
 * Content fingerprints for a single book's *shared* data. Unlike LocalBookRevision,
 * these exclude local tracker account state. This does not provide atomicity across the
 * separate sync documents; no deletion may rely on these values until writes are bound
 * to a single conditional commit and local state is checked in its transaction.
 */
internal object SharedBookRevision {
    @Serializable
    private data class Metadata(
        val book: SyncBook,
        val highlights: List<SyncHighlight>,
        val notes: List<SyncNote>,
        val bookmarks: List<SyncBookmark>,
        val deletions: Map<String, Long>,
    )
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun capture(
        id: String,
        state: SyncState,
        links: List<TrackLink>,
        file: BookFileEntry? = null,
        folder: FolderEntry? = null,
    ): BookDeletionGuard.SharedRevision = try {
        SyncManager.validateState(state)
        if ((file == null) == (folder == null)) throw IOException("Exactly one content revision is required")
        if (file != null) {
            if (file.bookHash != id) throw IOException("Book file identity differs")
            // Validate all manifest fields before relying on the content revision.
            BookFileSync.decodeIndex(json.encodeToString(BookFileIndex.serializer(), BookFileIndex(books = listOf(file))).toByteArray())
        }
        if (folder != null) {
            if (folder.id != id) throw IOException("Folder identity differs")
            FolderManifestCodec.validate(FolderManifest(folders = listOf(folder)))
        }
        if (links.any { !it.valid() } || links.map { it.key() }.toSet().size != links.size)
            throw IOException("Invalid tracker bindings")
        val book = state.books.singleOrNull { it.hash == id } ?: throw IOException("Shared book metadata is missing")
        val relevantTombs = state.deleted.filter { (key, _) ->
            val owner = SyncKeys.bookOf(key)
            if (owner == null && key.length > 1 && key[1] == '|' && key[0] in "hnb")
                throw IOException("Invalid annotation deletion key")
            owner == id
        }.toSortedMap()
        val metadata = Metadata(
            // The title is device-local flavor (every merge stamps its own); it is not a
            // protected field and must not destabilize the fingerprint across devices.
            book.copy(title = "", tabs = book.tabs?.sorted()),
            state.highlights.filter { it.hash == id }.sortedBy { SyncKeys.highlight(it.hash, it.createdAt, it.text) },
            state.notes.filter { it.hash == id }.sortedBy { SyncKeys.note(it.hash, it.createdAt) },
            state.bookmarks.filter { it.hash == id }.sortedBy { SyncKeys.bookmark(it.hash, it.createdAt) },
            relevantTombs,
        )
        val relatedLinks = links.filter { it.owner.bookHash == id }.sortedBy { it.key() }
        val fileText = if (file != null) json.encodeToString(BookFileEntry.serializer(), file)
            else json.encodeToString(FolderEntry.serializer(), folder!!)
        val linkText = json.encodeToString(TrackLinksFile.serializer(), TrackLinksFile(links = relatedLinks))
        BookDeletionGuard.SharedRevision(hash(fileText), hash(json.encodeToString(Metadata.serializer(), metadata)), hash(linkText))
    } catch (e: IOException) { throw e }
      catch (e: Exception) { throw IOException("Cannot determine a safe shared book revision", e) }
}
