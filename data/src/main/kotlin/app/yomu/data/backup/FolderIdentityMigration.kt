package app.yomu.data.backup

import java.io.IOException

/**
 * Pure, fail-closed transformation of legacy path identities into stable folder identities.
 * It does not write anything. The caller must coordinate all remote documents, local bases and
 * a durable migration journal before using it in live sync; a partial multi-document migration
 * must never be mistaken for an unrelated new book.
 */
internal object FolderIdentityMigration {
    private fun validate(mapping: Map<String, String>) {
        require(mapping.keys.all { it.startsWith("dir:") && it.length > 4 })
        require(mapping.values.all(FolderManifestCodec::validId))
        require(mapping.values.toSet().size == mapping.size)
    }

    private fun migrateKey(key: String, mapping: Map<String, String>): String {
        if (key.length < 3 || key[1] != '|' || key[0] !in "hnb") return key
        // Match the entire known legacy identity. A URI can itself contain the '|' character.
        val old = mapping.keys.sortedByDescending { it.length }.firstOrNull { key.startsWith("${key[0]}|$it|") } ?: return key
        return "${key[0]}|${mapping.getValue(old)}|" + key.substring(3 + old.length)
    }

    private fun <T> rewriteMap(map: Map<String, T>, mapping: Map<String, String>): Map<String, T> {
        val result = LinkedHashMap<String, T>()
        for ((key, value) in map) {
            val migrated = migrateKey(key, mapping)
            if (result.containsKey(migrated)) throw IOException("Folder migration has conflicting records")
            result[migrated] = value
        }
        return result
    }

    data class Metadata(val state: SyncState, val base: SyncBase?)

    fun metadata(state: SyncState, base: SyncBase?, mapping: Map<String, String>): Metadata = try {
        validate(mapping)
        SyncManager.validateState(state)
        // Fingerprints include the old identity. Rewriting only their keys would
        // fabricate a proof for a different record; migration needs a journal that
        // can safely reconstruct them instead.
        val revisionKeys = state.annotationDeletionProofs.keys + base?.annotationRevisions.orEmpty().keys +
            base?.privateAnnotationRevisions.orEmpty().keys + base?.annotationDeletionProofs.orEmpty().keys
        if (revisionKeys.any { migrateKey(it, mapping) != it })
            throw IOException("Folder identity has annotation revision proofs that require coordinated migration")
        val hashes = (state.books.map { it.hash } + state.highlights.map { it.hash } + state.notes.map { it.hash } + state.bookmarks.map { it.hash }).toSet()
        if (mapping.any { (old, new) -> old in hashes && new in hashes })
            throw IOException("Both legacy and stable folder identities already have metadata")
        val next = state.copy(
            books = state.books.map { it.copy(hash = mapping[it.hash] ?: it.hash) },
            highlights = state.highlights.map { it.copy(hash = mapping[it.hash] ?: it.hash) },
            notes = state.notes.map { it.copy(hash = mapping[it.hash] ?: it.hash) },
            bookmarks = state.bookmarks.map { it.copy(hash = mapping[it.hash] ?: it.hash) },
            deleted = rewriteMap(state.deleted, mapping),
        )
        SyncManager.validateState(next)
        val nextBase = base?.let { b ->
            val keys = b.keys.map { migrateKey(it, mapping) }
            if (keys.size != keys.toSet().size) throw IOException("Folder migration has conflicting base keys")
            val bookTabs = LinkedHashMap<String, List<String>>()
            for ((old, tabs) in b.bookTabs) {
                val key = mapping[old] ?: old
                if (bookTabs.put(key, tabs) != null) throw IOException("Folder migration has conflicting book tabs")
            }
            b.copy(keys = keys.toSet(), tombstones = rewriteMap(b.tombstones, mapping), bookTabs = bookTabs)
        }
        Metadata(next, nextBase)
    } catch (e: IOException) { throw e }
      catch (e: Exception) { throw IOException("Cannot migrate folder identity; no data was changed", e) }

    fun links(links: List<TrackLink>, mapping: Map<String, String>): List<TrackLink> = try {
        validate(mapping)
        if (links.any { !it.valid() } || links.map { it.key() }.toSet().size != links.size)
            throw IOException("Invalid tracker links before folder migration")
        val owners = links.mapNotNull { it.owner.bookHash }.toSet()
        if (mapping.any { (old, new) -> old in owners && new in owners })
            throw IOException("Both legacy and stable folder identities already have tracker bindings")
        links.map { link ->
            val old = link.owner.bookHash
            if (old == null || old !in mapping) link else link.copy(owner = link.owner.copy(bookHash = mapping.getValue(old)))
        }.also { migrated ->
            if (migrated.map { it.key() }.toSet().size != migrated.size) throw IOException("Conflicting tracker bindings")
        }
    } catch (e: IOException) { throw e }
      catch (e: Exception) { throw IOException("Cannot migrate folder tracker bindings; no data was changed", e) }
}
