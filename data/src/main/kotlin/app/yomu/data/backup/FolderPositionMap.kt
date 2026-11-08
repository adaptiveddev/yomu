package app.yomu.data.backup

import java.io.IOException

/**
 * Maps only positions that can be identified unambiguously; never guess after a page is removed.
 * In [lenient] mode an unresolvable reference is reported in [unresolved] instead of thrown — the
 * caller decides what to reset, and the report reaches the user. Annotations are never dropped
 * here: an unresolvable one keeps its old locator and is reported as dangling.
 */
internal class FolderPositionMap(
    private val before: List<FolderPage>,
    private val after: List<FolderPage>,
    private val lenient: Boolean = false,
) {
    /** Pages whose saved references cannot be remapped (changed bytes or removed), in scan order. */
    val unresolved: MutableList<String> = mutableListOf()

    private val pathToNew = after.mapIndexed { i, page -> page.path to i }.toMap()
    private val oldPaths = before.map { it.path }.toSet()
    private val digestToOld = before.groupingBy { it.sha256 to it.size }.eachCount()
    private val digestToNew = after.withIndex().groupBy { it.value.sha256 to it.value.size }

    fun index(oldIndex: Int): Int {
        if (oldIndex !in before.indices) {
            if (lenient) { unresolved += "<position outside the folder>"; return -1 }
            throw IOException("Saved page position is outside the original folder")
        }
        val page = before[oldIndex]
        val key = page.sha256 to page.size
        val candidates = digestToNew[key].orEmpty()
        pathToNew[page.path]?.let { samePath ->
            val replacement = after[samePath]
            val replacementKey = replacement.sha256 to replacement.size
            if (replacementKey == key) return samePath
            // The same name and different bytes cannot prove an in-place edit: it may
            // instead be a different page replacing the original. Never move a saved
            // position without a user-resolvable decision in that ambiguous case.
            if (lenient) { unresolved += page.path; return -1 }
            throw FolderSyncProblem(FolderSyncProblem.Kind.CHANGED_PAGE, page.path, "A saved page changed or its filename was reused: ${page.path}")
        }
        if (digestToOld[key] == 1 && candidates.size == 1 && candidates.single().value.path !in oldPaths)
            return candidates.single().index
        if (lenient) { unresolved += page.path; return -1 }
        throw FolderSyncProblem(FolderSyncProblem.Kind.MISSING_PAGE, page.path, "A saved page was removed or cannot be matched unambiguously: ${page.path}")
    }

    fun progress(newIndex: Int): Float = if (after.size <= 1) 1f else newIndex / (after.size - 1).toFloat()
}
