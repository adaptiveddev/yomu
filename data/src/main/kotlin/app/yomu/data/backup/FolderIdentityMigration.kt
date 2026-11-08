package app.yomu.data.backup

import java.io.IOException

/**
 * Pure, fail-closed transformation of legacy path identities into stable folder identities,
 * delegating to [SyncIdentityMigration]. It does not write anything. The caller must coordinate
 * all remote documents, local bases and a durable migration journal before using it in live sync;
 * a partial multi-document migration must never be mistaken for an unrelated new book.
 *
 * Folder positions are page-revision based, so a rewritten record's recomputed content revision
 * cannot be trusted here without the coordinated protocol; revisions therefore abort the
 * migration instead of being recomputed ([SyncIdentityMigration.metadata] with
 * [recomputeRevisions] = false).
 */
internal object FolderIdentityMigration {
    private fun validOld(key: String) = key.startsWith("dir:") && key.length > 4
    private fun validAny(id: String) = validOld(id) || FolderManifestCodec.validId(id)

    private fun folderMapping(mapping: Map<String, String>): Map<String, String> = try {
        require(mapping.keys.all(::validOld))
        require(mapping.values.all(FolderManifestCodec::validId))
        mapping
    } catch (e: Exception) { throw IOException("Invalid folder identity mapping", e) }

    fun metadata(state: SyncState, base: SyncBase?, mapping: Map<String, String>) = try {
        val hashes = (state.books.map { it.hash } + state.highlights.map { it.hash } + state.notes.map { it.hash } + state.bookmarks.map { it.hash }).toSet()
        val m = folderMapping(mapping)
        if (mapping.any { (old, new) -> old in hashes && new in hashes })
            throw IOException("Both legacy and stable folder identities already have metadata")
        SyncIdentityMigration.metadata(state, base, m, ::validAny, recomputeRevisions = false)
    } catch (e: IOException) { throw e }
      catch (e: Exception) { throw IOException("Cannot migrate folder identity; no data was changed", e) }

    fun links(links: List<TrackLink>, mapping: Map<String, String>): List<TrackLink> = try {
        val owners = links.mapNotNull { it.owner.bookHash }.toSet()
        val m = folderMapping(mapping)
        if (mapping.any { (old, new) -> old in owners && new in owners })
            throw IOException("Both legacy and stable folder identities already have tracker bindings")
        SyncIdentityMigration.links(links, m, ::validAny)
    } catch (e: IOException) { throw e }
      catch (e: Exception) { throw IOException("Cannot migrate folder tracker bindings; no data was changed", e) }
}
