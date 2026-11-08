package app.yomu.data.backup

import java.io.IOException

/**
 * Pure, fail-closed rewrite of record keys when a book or image folder changes its sync identity
 * (a changed file re-registered under its new content identity, a legacy path folder moved to a
 * stable id). It does not write anything and must never run without a durable journal: a partial
 * multi-document migration must not be mistaken for an unrelated new book.
 *
 * Shared revision values embed the identity, so after rewriting the records their content
 * revisions are recomputed; records needed for that must exist. Deletion proofs cannot be
 * recomputed (the deleted record's content is gone) and block the migration.
 */
internal object SyncIdentityMigration {
    private fun validate(mapping: Map<String, String>, idValid: (String) -> Boolean) {
        require(mapping.keys.all { it.length in 4..512 && idValid(it) && it != "h" && it != "n" && it != "b" })
        require(mapping.values.all(idValid))
        require(mapping.keys.none { it in mapping.values })
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
            if (result.containsKey(migrated)) throw IOException("Identity migration has conflicting records")
            result[migrated] = value
        }
        return result
    }

    /**
     * Rewrites identities and drops stale aliases: a record still stored under the old identity
     * next to its rewritten counterpart is a pre-resolution contribution of another device (its
     * local database stays the source of truth and re-contributes under the new identity).
     */
    private fun <T> deduped(records: List<T>, key: (T) -> String, mapped: (T) -> T): List<T> {
        val current = LinkedHashMap<String, T>()
        for (record in records) current[key(record)] = record
        for (record in records) {
            val rewritten = mapped(record)
            if (rewritten == record) continue
            val oldKey = key(record)
            if (current[oldKey] === record) current.remove(oldKey)
            // An authoritative record under the new identity already exists: the old-identity
            // alias is dropped and the record's owner device re-contributes from its database.
            if (!current.containsKey(key(rewritten))) current[key(rewritten)] = rewritten
        }
        return current.values.toList()
    }

    private fun rewrite(state: SyncState, mapping: Map<String, String>): SyncState = state.copy(
        books = deduped(state.books, { it.hash }, { it.copy(hash = mapping[it.hash] ?: it.hash) }),
        highlights = deduped(state.highlights, { SyncKeys.highlight(it.hash, it.createdAt, it.text) }, { it.copy(hash = mapping[it.hash] ?: it.hash) }),
        notes = deduped(state.notes, { SyncKeys.note(it.hash, it.createdAt) }, { it.copy(hash = mapping[it.hash] ?: it.hash) }),
        bookmarks = deduped(state.bookmarks, { SyncKeys.bookmark(it.hash, it.createdAt) }, { it.copy(hash = mapping[it.hash] ?: it.hash) }),
        deleted = rewriteMap(state.deleted, mapping),
    )

    data class Metadata(val state: SyncState, val base: SyncBase?)

    /**
     * Rewrites the sync state and the per-target merge base. With [recomputeRevisions] the base's
     * annotation revisions are recalculated from the rewritten records (the shared state must
     * contain a record for every rewritten base key); otherwise any changing revision key aborts.
     */
    fun metadata(
        state: SyncState,
        base: SyncBase?,
        mapping: Map<String, String>,
        idValid: (String) -> Boolean,
        recomputeRevisions: Boolean,
    ): Metadata = try {
        validate(mapping, idValid)
        SyncManager.validateState(state)
        // Deleted records' content is gone: their proofs cannot follow the identity.
        if (state.annotationDeletionProofs.keys.any { migrateKey(it, mapping) != it })
            throw IOException("The book has verified annotation deletions; its identity cannot be migrated")
        if (base?.annotationDeletionProofs.orEmpty().keys.any { migrateKey(it, mapping) != it })
            throw IOException("The merge base has verified annotation deletions; the identity cannot be migrated")
        if (!recomputeRevisions) {
            // Without a coordinated recompute, rewriting a revision key would fabricate a proof
            // for a different record; folder migrations must not do that.
            val revisionKeys = base?.annotationRevisions.orEmpty().keys + base?.privateAnnotationRevisions.orEmpty().keys
            if (revisionKeys.any { migrateKey(it, mapping) != it })
                throw IOException("The identity has annotation revisions that require coordinated migration")
        }
        val next = rewrite(state, mapping)
        SyncManager.validateState(next)
        val nextBase = base?.let { b ->
            val keys = b.keys.map { migrateKey(it, mapping) }
            if (keys.size != keys.toSet().size) throw IOException("Identity migration has conflicting base keys")
            val bookTabs = LinkedHashMap<String, List<String>>()
            for ((old, tabs) in b.bookTabs) {
                val key = mapping[old] ?: old
                if (bookTabs.put(key, tabs) != null) throw IOException("Identity migration has conflicting book tabs")
            }
            val migrated = b.copy(keys = keys.toSet(), tombstones = rewriteMap(b.tombstones, mapping), bookTabs = bookTabs)
            if (recomputeRevisions) recomputeRevisions(migrated, next, mapping) else migrated
        }
        Metadata(next, nextBase)
    } catch (e: IOException) { throw e }
      catch (e: Exception) { throw IOException("Cannot migrate the identity; no data was changed", e) }

    /** Shared revisions embed the identity; private fingerprints do not (values survive, keys follow). */
    private fun recomputeRevisions(base: SyncBase, state: SyncState, mapping: Map<String, String>): SyncBase {
        val highlights = state.highlights.associateBy { SyncKeys.highlight(it.hash, it.createdAt, it.text) }
        val notes = state.notes.associateBy { SyncKeys.note(it.hash, it.createdAt) }
        val bookmarks = state.bookmarks.associateBy { SyncKeys.bookmark(it.hash, it.createdAt) }
        val revisions = LinkedHashMap<String, String>()
        for ((key, value) in base.annotationRevisions) {
            val migrated = migrateKey(key, mapping)
            val source = when {
                migrated == key -> value
                migrated in highlights -> AnnotationRevisions.highlight(highlights.getValue(migrated))
                migrated in notes -> AnnotationRevisions.note(notes.getValue(migrated))
                migrated in bookmarks -> AnnotationRevisions.bookmark(bookmarks.getValue(migrated))
                else -> throw IOException("A merge base revision has no record to recompute: ${key.take(64)}")
            }
            if (revisions.put(migrated, source) != null) throw IOException("Identity migration has conflicting base revisions")
        }
        val private = LinkedHashMap<String, String>()
        for ((key, value) in base.privateAnnotationRevisions) {
            if (private.put(migrateKey(key, mapping), value) != null) throw IOException("Identity migration has conflicting private revisions")
        }
        return base.copy(annotationRevisions = revisions, privateAnnotationRevisions = private)
    }

    fun links(links: List<TrackLink>, mapping: Map<String, String>, idValid: (String) -> Boolean): List<TrackLink> = try {
        validate(mapping, idValid)
        if (links.any { !it.valid() } || links.map { it.key() }.toSet().size != links.size)
            throw IOException("Invalid tracker links before identity migration")
        val migrated = links.map { link ->
            val old = link.owner.bookHash
            if (old == null || old !in mapping) link else link.copy(owner = link.owner.copy(bookHash = mapping.getValue(old)))
        }
        // Identical rewritten aliases are dropped; diverged bindings must be resolved by hand.
        val byKey = migrated.groupBy { it.key() }
        if (byKey.any { (_, group) -> group.size > 1 && group.any { it != group.first() } })
            throw IOException("Conflicting tracker bindings")
        byKey.map { it.value.first() }
    } catch (e: IOException) { throw e }
      catch (e: Exception) { throw IOException("Cannot migrate tracker bindings; no data was changed", e) }
}
