package app.yomu.data.backup

import kotlinx.serialization.Serializable

// The shared sync file (yomu-sync.json). Version 1 (1.x) had books, annotations and words; version 2 (2.0.0) adds the
// online library, tabs and deletions. Every field added in version 2 has a default, so 1.x reads the file (and simply
// ignores the rest) and 2.0 reads files written by 1.x.

@Serializable
data class SyncBook(
    val hash: String,
    val title: String,
    val locator: String?,
    val progress: Float,
    val updatedAt: Long,
    val status: String,
    /** Names of the tabs of the book; null = not known (written by 1.x). */
    val tabs: List<String>? = null,
)

@Serializable
data class SyncHighlight(val hash: String, val locator: String, val chapter: Int, val text: String, val color: String, val style: String, val isQuote: Boolean, val tags: String, val note: String?, val createdAt: Long, val updatedAt: Long)

@Serializable
data class SyncNote(val hash: String, val locator: String?, val text: String, val tags: String, val createdAt: Long, val updatedAt: Long)

@Serializable
data class SyncBookmark(val hash: String, val locator: String, val title: String, val progress: Float, val createdAt: Long)

@Serializable
data class SyncWord(
    val term: String, val normalized: String, val lang: String, val translation: String?, val definition: String?, val context: String?,
    val tags: String, val createdAt: Long, val level: Int, val ease: Double, val intervalDays: Int, val repetitions: Int, val dueAt: Long,
    val lapses: Int, val lastReviewedAt: Long?,
)

/** A chapter of an online title with a mark: read, bookmarked or opened. */
@Serializable
data class SyncChapter(
    val url: String,
    val name: String = "",
    val number: Float = -1f,
    val order: Int = 0,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val page: Int = 0,
    /** When the chapter was last opened (0 = never). */
    val at: Long = 0,
)

/** A title of the online library, matched by source and address. */
@Serializable
data class SyncTitle(
    val sourceId: Long,
    val url: String,
    val title: String,
    val type: String = "MANGA",
    val author: String? = null,
    val artist: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val genres: String = "",
    val status: Int = 0,
    val addedAt: Long = 0,
    val tabs: List<String> = emptyList(),
    val chapters: List<SyncChapter> = emptyList(),
)

@Serializable
data class SyncTab(val name: String, val order: Int = 0)

@Serializable
data class SyncState(
    val version: Int = 1,
    val updatedAt: Long = 0,
    /** Device that wrote the file last (shown in the settings). */
    val device: String = "",
    val books: List<SyncBook> = emptyList(),
    val highlights: List<SyncHighlight> = emptyList(),
    val notes: List<SyncNote> = emptyList(),
    val bookmarks: List<SyncBookmark> = emptyList(),
    val words: List<SyncWord> = emptyList(),
    val titles: List<SyncTitle> = emptyList(),
    val tabs: List<SyncTab> = emptyList(),
    /** Deleted records: key (see [SyncKeys]) → time of the deletion. */
    val deleted: Map<String, Long> = emptyMap(),
    /** Expected shared annotation revision for a deletion. Older clients must not share this sync target. */
    val annotationDeletionProofs: Map<String, String> = emptyMap(),
)

/**
 * What this device knew after its last sync with one sync target: it tells a record deleted here (in [keys], gone now)
 * from a record that is new on another device, and a chapter marked unread here from one marked read elsewhere.
 */
@Serializable
data class SyncBase(
    /** The sync target this base belongs to (a folder or a WebDAV address). */
    val syncId: String,
    /** Keys of all records that were on this device after the last sync. */
    val keys: Set<String> = emptySet(),
    /** Deletions known to this device (kept here too, so a file rewritten by 1.x does not bring deleted records back). */
    val tombstones: Map<String, Long> = emptyMap(),
    /** Title key → hashes of the read chapters' addresses. */
    val read: Map<String, List<Int>> = emptyMap(),
    /** Title key → hashes of the bookmarked chapters' addresses. */
    val marked: Map<String, List<Int>> = emptyMap(),
    /** Title key → names of its tabs. */
    val titleTabs: Map<String, List<String>> = emptyMap(),
    /** Book hash → names of its tabs. */
    val bookTabs: Map<String, List<String>> = emptyMap(),
    /** Last acknowledged shared revisions of locally present annotations. */
    val annotationRevisions: Map<String, String> = emptyMap(),
    /** Local-only state such as favorites and linked private notes; never published. */
    val privateAnnotationRevisions: Map<String, String> = emptyMap(),
    val annotationDeletionProofs: Map<String, String> = emptyMap(),
)

/** Keys of the synced records; a deletion is stored under the key of the deleted record. */
object SyncKeys {
    fun highlight(hash: String, createdAt: Long, text: String) = "h|$hash|$createdAt|${Integer.toHexString(text.hashCode())}"
    fun note(hash: String, createdAt: Long) = "n|$hash|$createdAt"
    fun bookmark(hash: String, createdAt: Long) = "b|$hash|$createdAt"
    fun word(lang: String, normalized: String) = "w|$lang|$normalized"
    fun title(sourceId: Long, url: String) = "c|$sourceId|$url"
    fun tab(name: String) = "t|${name.trim().lowercase()}"

    /** The book of an annotation key, null for other keys. */
    fun bookOf(key: String): String? {
        if (key.length < 4 || key[1] != '|' || key[0] !in "hnb") return null
        // Hashes for legacy image folders contain URIs, which can themselves contain '|'.
        // Parse the fixed timestamp (and highlight text hash) from the right instead.
        val last = key.lastIndexOf('|')
        val timeSeparator = if (key[0] == 'h') key.lastIndexOf('|', last - 1) else last
        if (timeSeparator <= 2 || last < timeSeparator || key.substring(timeSeparator + 1, if (key[0] == 'h') last else key.length).toLongOrNull() == null)
            return null
        if (key[0] == 'h' && last == key.lastIndex) return null
        return key.substring(2, timeSeparator)
    }
}

/** Three-way merge of one flag: the side that changed since [base] wins; without a base either side setting it wins. */
internal fun merge3(local: Boolean, remote: Boolean?, base: Boolean?): Boolean = when {
    remote == null || local == remote -> local
    base == null -> local || remote
    local != base -> local
    else -> remote
}

/** Three-way merge of a set (tabs of a title, for example), element by element. */
internal fun <T> merge3(local: Set<T>, remote: Set<T>?, base: Set<T>?): Set<T> {
    if (remote == null) return local
    return (local + remote).filterTo(LinkedHashSet()) { e -> merge3(e in local, e in remote, base?.let { e in it }) }
}
