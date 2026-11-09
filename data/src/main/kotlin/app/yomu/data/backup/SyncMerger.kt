package app.yomu.data.backup

import androidx.room.withTransaction
import app.yomu.core.database.BookCollectionCrossRef
import app.yomu.core.database.BookmarkEntity
import app.yomu.core.database.CatalogCollectionCrossRef
import app.yomu.core.database.CatalogItemEntity
import app.yomu.core.database.ChapterEntity
import app.yomu.core.database.CollectionEntity
import app.yomu.core.database.HighlightEntity
import app.yomu.core.database.NoteEntity
import app.yomu.core.database.ReadingProgressEntity
import app.yomu.core.database.WordEntity
import app.yomu.core.database.YomuDatabase

/**
 * Merges this device's data with the shared sync state (three-way, with the [SyncBase] of the last sync):
 * - records new on either side are copied to the other one;
 * - a record deleted here (it was in the base and is gone) becomes a deletion mark, and a deletion mark from another
 *   device deletes a supported annotation only when its version proof matches;
 * - nothing is deleted here without such a mark: a file written by another app version, a missing book or a fresh
 *   install never removes data;
 * - matching annotations use content-based three-way comparison; incompatible edits stop with a conflict.
 * Records of books that are not on this device are kept in the shared state for the other devices.
 */
internal class SyncMerger(private val db: YomuDatabase) {
    /**
     * What takes part in the sync. A category that is off keeps the shared records as they are: nothing is applied here,
     * nothing of this device is published, and its records missing here are never taken for deletions.
     */
    data class Options(
        val catalog: Boolean = true,
        val deletions: Boolean = true,
        val device: String = "",
        val progress: Boolean = true,
        val annotations: Boolean = true,
        val bookmarks: Boolean = true,
        val words: Boolean = true,
    ) {
        /** Whether the record behind [key] (see [SyncKeys]) takes part in the sync. */
        fun includes(key: String): Boolean = when (key.firstOrNull()) {
            'h', 'n' -> annotations
            'b' -> bookmarks
            'w' -> words
            'c' -> catalog
            else -> true
        }
    }
    data class Outcome(val state: SyncState, val base: SyncBase, val changed: Int)

    suspend fun merge(remote: SyncState?, base: SyncBase?, syncId: String, now: Long, o: Options): Outcome = db.withTransaction {
        val r = remote ?: SyncState()
        try { SyncManager.validateState(r) } catch (e: Exception) { throw java.io.IOException("Invalid shared sync state; no data was applied", e) }
        val b = base?.takeIf { it.syncId == syncId }
        var changed = 0
        val tomb = HashMap<String, Long>()
        fun addTomb(k: String, t: Long) { if (t > (tomb[k] ?: 0L)) tomb[k] = t }
        r.deleted.forEach { (k, t) -> addTomb(k, t) }
        b?.tombstones?.forEach { (k, t) -> addTomb(k, t) }
        /** Keys of the records on this device after the merge. */
        val live = HashSet<String>()

        val ann = db.annotations()
        val words = db.words()
        val catalog = db.catalog()
        val collections = db.collections()
        val books = db.books().all().filter { it.fileHash != null }
        if (books.map { it.fileHash }.toSet().size != books.size)
            throw java.io.IOException("Duplicate book identifier prevents safe metadata sync; resolve the conflict before syncing")
        val idByHash = books.associate { it.fileHash!! to it.id }
        val hashById = books.associate { it.id to it.fileHash!! }
        val localHl = if (o.annotations) ann.allHighlights().filter { it.bookId in hashById } else emptyList()
        val allNotes = ann.allNotes()
        val localNotes = if (o.annotations) allNotes.filter { it.highlightId == null && it.bookId in hashById } else emptyList()
        val linkedNotes = allNotes.filter { it.highlightId != null }.groupBy { it.highlightId }
        val localBm = if (o.bookmarks) ann.allBookmarks().filter { it.bookId in hashById } else emptyList()
        val localWords = if (o.words) words.all() else emptyList()
        var tabs = collections.bookCollections()
        val favorites = if (o.catalog) catalog.favorites() else emptyList()

        // A deletion must be bound to the exact shared revision observed on the deleting
        // device; timestamps alone cannot distinguish concurrent edits or clock skew.
        val localVersions = HashMap<String, String>()
        val privateVersions = HashMap<String, String>()
        val highlightsWithPrivateState = HashSet<String>()
        localHl.forEach { h ->
            val hash = hashById.getValue(h.bookId); val k = SyncKeys.highlight(hash, h.createdAt, h.text)
            if (localVersions.put(k, AnnotationRevisions.highlight(SyncHighlight(hash, h.locatorJson, h.chapter, h.text, h.color, h.style, h.isQuote, h.tags, h.note, h.createdAt, h.updatedAt))) != null)
                throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.DUPLICATE, "Duplicate local annotation identity; no data was applied")
            val attachedNotes = linkedNotes[h.id].orEmpty()
            privateVersions[k] = AnnotationRevisions.privateHighlight(h, attachedNotes)
            // Other devices never see favorites or notes attached to highlights.
            // Even unchanged private state must not be destroyed by their deletion.
            if (h.isFavorite || attachedNotes.isNotEmpty()) highlightsWithPrivateState += k
        }
        localNotes.forEach { n ->
            val hash = hashById.getValue(n.bookId); val k = SyncKeys.note(hash, n.createdAt)
            if (localVersions.put(k, AnnotationRevisions.note(SyncNote(hash, n.locatorJson, n.text, n.tags, n.createdAt, n.updatedAt))) != null)
                throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.DUPLICATE, "Duplicate local annotation identity; no data was applied")
        }
        localBm.forEach { m ->
            val hash = hashById.getValue(m.bookId); val k = SyncKeys.bookmark(hash, m.createdAt)
            if (localVersions.put(k, AnnotationRevisions.bookmark(SyncBookmark(hash, m.locatorJson, m.title, m.progress, m.createdAt))) != null)
                throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.DUPLICATE, "Duplicate local annotation identity; no data was applied")
        }
        val remoteVersions = HashMap<String, String>()
        r.highlights.forEach { remoteVersions[SyncKeys.highlight(it.hash, it.createdAt, it.text)] = AnnotationRevisions.highlight(it) }
        r.notes.forEach { remoteVersions[SyncKeys.note(it.hash, it.createdAt)] = AnnotationRevisions.note(it) }
        r.bookmarks.forEach { remoteVersions[SyncKeys.bookmark(it.hash, it.createdAt)] = AnnotationRevisions.bookmark(it) }
        val proofs = HashMap<String, String>()
        b?.annotationDeletionProofs?.forEach { (key, value) -> proofs[key] = value }
        r.annotationDeletionProofs.forEach { (key, value) ->
            if (proofs[key] != null && proofs[key] != value) throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.CONFLICT, "Conflicting annotation deletion revisions")
            proofs[key] = value
        }
        if (o.deletions) {
            // Never borrow a locally cached proof for a marker that a legacy writer
            // has stripped of its own proof; it may represent a different deletion.
            for (key in r.deleted.keys) {
                if (SyncKeys.bookOf(key) != null && key in localVersions && key !in r.annotationDeletionProofs)
                    throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.NO_PROOF, "Annotation deletion has no revision proof; no data was deleted")
            }
            // A remote deletion may only remove a local record whose shared and private
            // state still match the last acknowledged base and the deletion precondition.
            for ((key, localVersion) in localVersions) {
                if (key !in tomb) continue
                val proof = proofs[key] ?: throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.NO_PROOF, "Annotation deletion has no revision proof; no data was deleted")
                if (b?.annotationRevisions?.get(key) != proof || localVersion != proof || key in highlightsWithPrivateState ||
                    (key.startsWith("h|") && b.privateAnnotationRevisions[key] != privateVersions[key]))
                    throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.CONFLICT, "Annotation was edited and deleted concurrently; no data was deleted")
            }
            // A tombstone must not hide a remote record edited after the deletion baseline.
            for ((key, version) in remoteVersions) {
                if (key in tomb && proofs[key] != version)
                    throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.CONFLICT, "Remote annotation differs from the deletion revision; no data was deleted")
            }
        }

        // Three-way selection uses acknowledged content rather than device clocks.
        // With no common baseline, divergent records cannot safely be combined.
        fun takeRemote(key: String): Boolean {
            val local = localVersions.getValue(key)
            val remoteVersion = remoteVersions.getValue(key)
            if (local == remoteVersion) return false
            val previous = b?.annotationRevisions?.get(key)
            if (previous == local) return true
            if (previous == remoteVersion) return false
            throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.CONFLICT, "Annotation changed on both devices or has no common revision; no changes were applied")
        }

        // 1. Deletions made here since the last sync.
        if (o.deletions && b != null) {
            val present = HashSet<String>()
            localHl.forEach { present += SyncKeys.highlight(hashById.getValue(it.bookId), it.createdAt, it.text) }
            localNotes.forEach { present += SyncKeys.note(hashById.getValue(it.bookId), it.createdAt) }
            localBm.forEach { present += SyncKeys.bookmark(hashById.getValue(it.bookId), it.createdAt) }
            localWords.forEach { present += SyncKeys.word(it.lang, it.normalized) }
            tabs.forEach { present += SyncKeys.tab(it.name) }
            favorites.forEach { present += SyncKeys.title(it.sourceId, it.url) }
            for (k in b.keys) {
                if (k in present) continue
                // A book that left this device takes its annotations along: not a deletion for the other devices.
                val book = SyncKeys.bookOf(k)
                if (book == null && k.length > 1 && k[1] == '|' && k[0] in "hnb")
                    throw java.io.IOException("Invalid annotation key in sync base; no data was deleted")
                if (book != null && book !in idByHash) continue
                if (!o.includes(k)) continue
                if (book != null) {
                    val baseVersion = b.annotationRevisions[k] ?: throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.NO_PROOF, "Annotation deletion has no saved revision; no data was deleted")
                    val remoteVersion = remoteVersions[k]
                    if (remoteVersion != null && remoteVersion != baseVersion)
                        throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.CONFLICT, "Annotation was edited and deleted concurrently; no data was deleted")
                    if (remoteVersion == null && (k !in r.deleted || proofs[k] != baseVersion))
                        throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.NO_PROOF, "Annotation is missing from the shared snapshot; no data was deleted")
                    if (proofs[k] != null && proofs[k] != baseVersion)
                        throw AnnotationSyncProblem(AnnotationSyncProblem.Kind.CONFLICT, "Conflicting annotation deletion revisions")
                    proofs[k] = baseVersion
                }
                addTomb(k, now)
            }
        }
        fun dead(k: String, modifiedAt: Long): Boolean {
            if (!o.deletions) return false
            val time = tomb[k] ?: return false
            // Verified annotation proofs supersede unreliable device clocks.
            return (SyncKeys.bookOf(k) != null && k in proofs) || time >= modifiedAt
        }

        // 2. Books: reading position (newest wins), status and tabs.
        val tabById = tabs.associateBy { it.id }
        val bookTabs = collections.allRefs().groupBy({ it.bookId }, { tabById[it.collectionId]?.name }).mapValues { (_, v) -> v.filterNotNull().toSet() }
        val progress = db.progress().all().associateBy { it.bookId }
        val remoteBooks = r.books.associateBy { it.hash }
        val outBooks = LinkedHashMap<String, SyncBook>()
        val finalBookTabs = HashMap<String, Set<String>>()
        val wantedBookTabs = HashMap<Long, Set<String>>()
        for (bk in books) {
            val hash = bk.fileHash!!
            val rb = remoteBooks[hash]
            val lp = progress[bk.id]
            var locator = lp?.locatorJson
            var value = bk.progress
            var at = lp?.updatedAt ?: 0L
            var status = bk.status
            if (o.progress && rb?.locator != null && rb.updatedAt > at) {
                db.progress().upsert(ReadingProgressEntity(bk.id, rb.locator, rb.progress, rb.updatedAt))
                db.books().updateProgress(bk.id, rb.progress, rb.updatedAt)
                if (rb.status == "FINISHED" && bk.status != "FINISHED") {
                    db.books().setStatus(listOf(bk.id), rb.status)
                    status = rb.status
                }
                locator = rb.locator
                value = rb.progress
                at = rb.updatedAt
                changed++
            }
            val localTabs = bookTabs[bk.id].orEmpty()
            val merged = merge3(localTabs, rb?.tabs?.toSet(), b?.bookTabs?.get(hash)?.toSet())
            if (merged != localTabs) wantedBookTabs[bk.id] = merged
            finalBookTabs[hash] = merged
            outBooks[hash] = if (o.progress) SyncBook(hash, bk.title, locator, value, at, status, merged.sorted())
            // Position sync off: the shared position stays what the other devices wrote; tabs still merge.
            else SyncBook(hash, bk.title, rb?.locator, rb?.progress ?: 0f, rb?.updatedAt ?: 0L, rb?.status ?: bk.status, merged.sorted())
        }
        r.books.forEach { rb -> if (rb.hash !in outBooks) outBooks[rb.hash] = rb }

        // 3. Highlights (notes of a highlight travel with it).
        val remoteHl = r.highlights.associateBy { SyncKeys.highlight(it.hash, it.createdAt, it.text) }
        val outHl = LinkedHashMap<String, SyncHighlight>()
        for (h in localHl) {
            val hash = hashById.getValue(h.bookId)
            val k = SyncKeys.highlight(hash, h.createdAt, h.text)
            val rh = remoteHl[k]
            // The legacy key contains a 32-bit text hash, not the full text. A collision
            // must never make two different highlights look like one editable record.
            if (rh != null && rh.text != h.text) throw java.io.IOException("Conflicting highlight identities; no annotations were overwritten")
            if (dead(k, maxOf(h.updatedAt, rh?.updatedAt ?: 0L))) {
                ann.deleteNotesOfHighlight(h.id)
                ann.deleteHighlight(h.id)
                changed++
                continue
            }
            var cur = h
            if (rh != null && takeRemote(k)) {
                cur = h.copy(locatorJson = rh.locator, chapter = rh.chapter, color = rh.color, style = rh.style, isQuote = rh.isQuote, tags = rh.tags, note = rh.note, updatedAt = rh.updatedAt)
                ann.updateHighlight(cur)
                changed++
            }
            outHl[k] = SyncHighlight(hash, cur.locatorJson, cur.chapter, cur.text, cur.color, cur.style, cur.isQuote, cur.tags, cur.note, cur.createdAt, cur.updatedAt)
            live += k
        }
        for ((k, rh) in remoteHl) {
            if (!o.annotations) { outHl[k] = rh; continue }
            if (k in outHl || dead(k, rh.updatedAt)) continue
            val bookId = idByHash[rh.hash]
            if (bookId != null) {
                ann.insertHighlight(HighlightEntity(0, bookId, rh.locator, rh.chapter, rh.text, rh.color, rh.style, rh.isQuote, false, rh.tags, rh.note, rh.createdAt, rh.updatedAt))
                changed++
                live += k
            }
            outHl[k] = rh
        }

        // 4. Notes without a highlight.
        val remoteNotes = r.notes.associateBy { SyncKeys.note(it.hash, it.createdAt) }
        val outNotes = LinkedHashMap<String, SyncNote>()
        for (n in localNotes) {
            val hash = hashById.getValue(n.bookId)
            val k = SyncKeys.note(hash, n.createdAt)
            val rn = remoteNotes[k]
            if (dead(k, maxOf(n.updatedAt, rn?.updatedAt ?: 0L))) {
                ann.deleteNote(n.id)
                changed++
                continue
            }
            var cur = n
            if (rn != null && takeRemote(k)) {
                cur = n.copy(locatorJson = rn.locator, text = rn.text, tags = rn.tags, updatedAt = rn.updatedAt)
                ann.updateNote(cur)
                changed++
            }
            outNotes[k] = SyncNote(hash, cur.locatorJson, cur.text, cur.tags, cur.createdAt, cur.updatedAt)
            live += k
        }
        for ((k, rn) in remoteNotes) {
            if (!o.annotations) { outNotes[k] = rn; continue }
            if (k in outNotes || dead(k, rn.updatedAt)) continue
            val bookId = idByHash[rn.hash]
            if (bookId != null) {
                ann.insertNote(NoteEntity(0, bookId, null, rn.locator, rn.text, rn.tags, rn.createdAt, rn.updatedAt))
                changed++
                live += k
            }
            outNotes[k] = rn
        }

        // 5. Bookmarks.
        val remoteBm = r.bookmarks.associateBy { SyncKeys.bookmark(it.hash, it.createdAt) }
        val outBm = LinkedHashMap<String, SyncBookmark>()
        for (m in localBm) {
            val hash = hashById.getValue(m.bookId)
            val k = SyncKeys.bookmark(hash, m.createdAt)
            if (dead(k, m.createdAt)) {
                ann.deleteBookmark(m.id)
                changed++
                continue
            }
            val rm = remoteBm[k]
            val cur = if (rm != null && takeRemote(k)) {
                val updated = m.copy(locatorJson = rm.locator, title = rm.title, progress = rm.progress)
                ann.updateBookmark(updated)
                changed++
                updated
            } else m
            outBm[k] = SyncBookmark(hash, cur.locatorJson, cur.title, cur.progress, cur.createdAt)
            live += k
        }
        for ((k, rm) in remoteBm) {
            if (!o.bookmarks) { outBm[k] = rm; continue }
            if (k in outBm || dead(k, rm.createdAt)) continue
            val bookId = idByHash[rm.hash]
            if (bookId != null) {
                ann.insertBookmark(BookmarkEntity(0, bookId, rm.locator, rm.title, rm.progress, rm.createdAt))
                changed++
                live += k
            }
            outBm[k] = rm
        }

        // 6. Words (the review schedule of the newest review wins).
        val remoteWords = r.words.associateBy { SyncKeys.word(it.lang, it.normalized) }
        val outWords = LinkedHashMap<String, SyncWord>()
        for (w in localWords) {
            val k = SyncKeys.word(w.lang, w.normalized)
            val rw = remoteWords[k]
            if (dead(k, maxOf(w.createdAt, w.lastReviewedAt ?: 0L, rw?.lastReviewedAt ?: 0L))) {
                words.delete(listOf(w.id))
                changed++
                continue
            }
            var cur = w
            if (rw != null && (rw.lastReviewedAt ?: 0L) > (w.lastReviewedAt ?: 0L)) {
                cur = w.copy(level = rw.level, ease = rw.ease, intervalDays = rw.intervalDays, repetitions = rw.repetitions, dueAt = rw.dueAt, lapses = rw.lapses, lastReviewedAt = rw.lastReviewedAt)
                words.update(cur)
                changed++
            }
            if (rw != null && cur.translation.isNullOrBlank() && !rw.translation.isNullOrBlank()) {
                cur = cur.copy(translation = rw.translation)
                words.update(cur)
                changed++
            }
            outWords[k] = cur.toSync()
            live += k
        }
        for ((k, rw) in remoteWords) {
            if (!o.words) { outWords[k] = rw; continue }
            if (k in outWords || dead(k, maxOf(rw.createdAt, rw.lastReviewedAt ?: 0L))) continue
            if (words.find(rw.normalized, rw.lang) == null) {
                words.insert(WordEntity(0, rw.term, rw.normalized, rw.lang, rw.translation, rw.definition, rw.context, null, null, null, rw.tags, rw.createdAt, rw.level, rw.ease, rw.intervalDays, rw.repetitions, rw.dueAt, rw.lapses, rw.lastReviewedAt))
                changed++
            }
            live += k
            outWords[k] = rw
        }

        // 7. Tabs. They have no time stamps: a deletion wins over a tab that already existed at the last sync.
        val remoteTabNames = r.tabs.map { it.name.trim() }.filter { it.isNotEmpty() }
        for (t in tabs) {
            val k = SyncKeys.tab(t.name)
            if (o.deletions && k in tomb && (b == null || k in b.keys)) {
                collections.delete(t.id)
                changed++
            } else {
                live += k
                tomb.remove(k)
            }
        }
        tabs = collections.bookCollections()
        for (name in remoteTabNames) {
            val k = SyncKeys.tab(name)
            if (tabs.any { SyncKeys.tab(it.name) == k }) continue
            if (o.deletions && (k in tomb || (b != null && k in b.keys))) continue
            collections.insert(CollectionEntity(name = name, sortOrder = collections.maxSortOrder() + 1))
            changed++
            live += k
            tabs = collections.bookCollections()
        }
        fun tabId(name: String): Long? = tabs.firstOrNull { SyncKeys.tab(it.name) == SyncKeys.tab(name) }?.id
        // A tab deleted on some device is not brought back by a title or a book that still names it.
        val tabAllowed: (String) -> Boolean = { name -> SyncKeys.tab(name).let { key -> key in live || !(o.deletions && key in tomb) } }
        suspend fun ensureTab(name: String): Long = tabId(name) ?: collections.insert(CollectionEntity(name = name, sortOrder = collections.maxSortOrder() + 1)).also {
            tabs = collections.bookCollections()
            live += SyncKeys.tab(name)
            tomb.remove(SyncKeys.tab(name))
        }

        // Book tabs decided above (tabs may have been created or deleted since).
        for ((bookId, names) in wantedBookTabs) {
            val current = collections.collectionsOf(bookId).toSet()
            val wanted = names.filter(tabAllowed).map { ensureTab(it) }.toSet()
            (current - wanted).forEach { collections.remove(listOf(bookId), it) }
            val add = wanted - current
            if (add.isNotEmpty()) collections.add(add.map { BookCollectionCrossRef(bookId, it) })
            changed++
        }

        // 8. Online library: titles, chapter marks, tabs.
        val outTitles = LinkedHashMap<String, SyncTitle>()
        val baseRead = HashMap<String, List<Int>>()
        val baseMarked = HashMap<String, List<Int>>()
        val baseTitleTabs = HashMap<String, List<String>>()
        val remoteTitles = r.titles.associateBy { SyncKeys.title(it.sourceId, it.url) }
        if (o.catalog) {
            val itemTabs = collections.allItemRefs().groupBy({ it.itemId }, { it.collectionId })
            val handled = HashSet<String>()
            for (item in favorites) {
                val k = SyncKeys.title(item.sourceId, item.url)
                handled += k
                if (dead(k, item.addedAt)) {
                    catalog.setFavorite(item.id, false, null, item.addedAt)
                    collections.clearItems(listOf(item.id))
                    changed++
                    continue
                }
                changed += mergeTitle(item, remoteTitles[k], b, k, itemTabs[item.id].orEmpty().mapNotNull { id -> tabs.firstOrNull { it.id == id }?.name }.toSet(), tabAllowed, { n -> ensureTab(n) }, outTitles, baseRead, baseMarked, baseTitleTabs)
                live += k
            }
            for ((k, rt) in remoteTitles) {
                if (k in handled) continue
                if (dead(k, rt.addedAt) || (o.deletions && b != null && k in b.keys)) {
                    if (!dead(k, rt.addedAt)) outTitles[k] = rt // deletions are off on this device
                    continue
                }
                val existing = catalog.find(rt.sourceId, rt.url)
                val item = if (existing == null) {
                    val id = catalog.insert(
                        CatalogItemEntity(
                            sourceId = rt.sourceId, url = rt.url, title = rt.title.ifBlank { rt.url }, author = rt.author, artist = rt.artist,
                            description = rt.description, genres = rt.genres, status = rt.status, coverUrl = rt.cover, type = rt.type,
                            favorite = true, categoryId = null, addedAt = rt.addedAt.takeIf { it > 0 } ?: now, lastUpdateAt = 0, initialized = false,
                        ),
                    )
                    catalog.get(id)!!
                } else {
                    catalog.setFavorite(existing.id, true, null, rt.addedAt.takeIf { it > 0 } ?: now)
                    catalog.get(existing.id)!!
                }
                changed++
                mergeTitle(item, rt, null, k, emptySet(), tabAllowed, { n -> ensureTab(n) }, outTitles, baseRead, baseMarked, baseTitleTabs)
                live += k
            }
        } else {
            outTitles.putAll(remoteTitles)
        }

        // 9. The new shared state and base.
        if (o.deletions) live.forEach { tomb.remove(it) }
        // A switched-off category keeps its last acknowledged base: switched on again, its records merge from there
        // instead of looking new (or concurrently edited) on both sides.
        val carried = b?.keys.orEmpty().filter { it.length > 1 && it[1] == '|' && it[0] in "hnbw" && !o.includes(it) }.toSet()
        val horizon = now - TOMBSTONE_DAYS * 24L * 3600 * 1000
        val keptTombs = tomb.filterValues { it >= horizon }
        val finalTabs = collections.bookCollections()
        val state = SyncState(
            version = 2,
            updatedAt = now,
            device = o.device,
            books = outBooks.values.toList(),
            highlights = outHl.values.toList(),
            notes = outNotes.values.toList(),
            bookmarks = outBm.values.toList(),
            words = outWords.values.toList(),
            titles = outTitles.values.toList(),
            tabs = finalTabs.map { SyncTab(it.name, it.sortOrder) },
            deleted = keptTombs.toSortedMap(),
            annotationDeletionProofs = proofs.filterKeys { it in keptTombs }.toSortedMap(),
        )
        val finalNotesForPrivateRevisions = ann.allNotes().filter { it.highlightId != null }.groupBy { it.highlightId }
        val newBase = SyncBase(
            syncId = syncId,
            keys = live + carried,
            tombstones = keptTombs,
            read = baseRead,
            marked = baseMarked,
            titleTabs = baseTitleTabs,
            bookTabs = finalBookTabs.mapValues { it.value.sorted() },
            annotationRevisions = buildMap {
                outHl.forEach { (key, value) -> if (key in live) put(key, AnnotationRevisions.highlight(value)) }
                outNotes.forEach { (key, value) -> if (key in live) put(key, AnnotationRevisions.note(value)) }
                outBm.forEach { (key, value) -> if (key in live) put(key, AnnotationRevisions.bookmark(value)) }
                b?.annotationRevisions?.forEach { (key, value) -> if (key in carried) put(key, value) }
            },
            privateAnnotationRevisions = if (o.annotations) {
                ann.allHighlights().filter { it.bookId in hashById }.associate { h ->
                    SyncKeys.highlight(hashById.getValue(h.bookId), h.createdAt, h.text) to AnnotationRevisions.privateHighlight(h, finalNotesForPrivateRevisions[h.id].orEmpty())
                }
            } else {
                b?.privateAnnotationRevisions.orEmpty().filterKeys { it in carried && b?.annotationRevisions?.containsKey(it) == true }
            },
            annotationDeletionProofs = proofs.filterKeys { it in keptTombs },
        )
        Outcome(state, newBase, changed)
    }

    /** Merges the chapters and tabs of one title of the library; returns the number of changes made here. */
    private suspend fun mergeTitle(
        item: CatalogItemEntity,
        rt: SyncTitle?,
        b: SyncBase?,
        k: String,
        localTabs: Set<String>,
        tabAllowed: (String) -> Boolean,
        ensureTab: suspend (String) -> Long,
        outTitles: MutableMap<String, SyncTitle>,
        baseRead: MutableMap<String, List<Int>>,
        baseMarked: MutableMap<String, List<Int>>,
        baseTitleTabs: MutableMap<String, List<String>>,
    ): Int {
        val catalog = db.catalog()
        val collections = db.collections()
        var changed = 0
        val chapters = catalog.chapters(item.id)
        val byUrl = chapters.associateBy { it.url }
        val remoteChapters = rt?.chapters?.associateBy { it.url }.orEmpty()
        val readBase = b?.read?.get(k)?.toHashSet()
        val markBase = b?.marked?.get(k)?.toHashSet()
        val updates = ArrayList<ChapterEntity>()
        val inserts = ArrayList<ChapterEntity>()
        var nextOrder = (chapters.maxOfOrNull { it.sourceOrder } ?: -1) + 1
        val now = System.currentTimeMillis()
        for (url in (byUrl.keys + remoteChapters.keys)) {
            val c = byUrl[url]
            val rc = remoteChapters[url]
            val h = url.hashCode()
            val read = merge3(c?.read ?: false, if (rt == null) null else rc?.read ?: false, readBase?.let { h in it })
            val mark = merge3(c?.bookmarked ?: false, if (rt == null) null else rc?.bookmark ?: false, markBase?.let { h in it })
            val newerRemote = rc != null && rc.at > (c?.lastReadAt ?: 0L)
            if (c == null) {
                if (rc == null || !(read || mark || rc.page > 0 || rc.at > 0)) continue
                inserts += ChapterEntity(
                    itemId = item.id, url = url, name = rc.name.ifBlank { url }, number = rc.number, scanlator = null, uploadDate = 0,
                    read = read, bookmarked = mark, lastPageRead = rc.page, downloaded = false, localPath = null, isNew = false,
                    sourceOrder = if (rc.order >= 0 && chapters.isEmpty()) rc.order else nextOrder++, fetchedAt = now, lastReadAt = rc.at.takeIf { it > 0 },
                )
            } else if (read != c.read || mark != c.bookmarked || newerRemote) {
                updates += c.copy(
                    read = read,
                    bookmarked = mark,
                    lastPageRead = if (newerRemote) rc.page else c.lastPageRead,
                    lastReadAt = if (newerRemote) rc.at else c.lastReadAt,
                )
            }
        }
        if (inserts.isNotEmpty()) catalog.insertChapters(inserts)
        if (updates.isNotEmpty()) catalog.updateChapters(updates)
        changed += inserts.size + updates.size

        // Tabs of the title.
        val merged = merge3(localTabs, rt?.tabs?.toSet(), b?.titleTabs?.get(k)?.toSet()).filterTo(LinkedHashSet(), tabAllowed)
        if (merged.map { SyncKeys.tab(it) }.toSet() != localTabs.map { SyncKeys.tab(it) }.toSet()) {
            collections.clearItems(listOf(item.id))
            val ids = merged.map { ensureTab(it) }.distinct()
            if (ids.isNotEmpty()) collections.addItems(ids.map { CatalogCollectionCrossRef(item.id, it) })
            changed++
        }

        val finalChapters = catalog.chapters(item.id).filter { it.read || it.bookmarked || it.lastPageRead > 0 || it.lastReadAt != null }
        outTitles[k] = SyncTitle(
            sourceId = item.sourceId, url = item.url, title = item.title, type = item.type, author = item.author, artist = item.artist,
            cover = item.coverUrl, description = item.description?.take(2000), genres = item.genres, status = item.status, addedAt = item.addedAt,
            tabs = merged.sorted(),
            chapters = finalChapters.map { SyncChapter(it.url, it.name, it.number, it.sourceOrder, it.read, it.bookmarked, it.lastPageRead, it.lastReadAt ?: 0L) },
        )
        baseRead[k] = finalChapters.filter { it.read }.map { it.url.hashCode() }
        baseMarked[k] = finalChapters.filter { it.bookmarked }.map { it.url.hashCode() }
        baseTitleTabs[k] = merged.sorted()
        return changed
    }

    private fun WordEntity.toSync() = SyncWord(term, normalized, lang, translation, definition, context, tags, createdAt, level, ease, intervalDays, repetitions, dueAt, lapses, lastReviewedAt)

    companion object {
        /** Deletion marks are kept this long, then forgotten (a device that did not sync for longer may bring records back). */
        const val TOMBSTONE_DAYS = 180L
    }
}
