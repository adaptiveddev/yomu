package app.yomu.data.backup

import android.content.Context
import app.yomu.data.R
import java.io.IOException

/** A safe-to-show folder sync problem. Technical details remain available as its cause. */
internal class FolderSyncProblem(
    val kind: Kind,
    override val subject: String,
    detail: String,
    override val target: String? = null,
) : IOException(detail), SyncProblem {
    enum class Kind { LEGACY, SIMILAR, DIVERGED, MISSING_REVISION, CHANGED_PAGE, MISSING_PAGE }

    override val kindTag: String = "folder_" + kind.name.lowercase()

    fun forDisplay(context: Context): IOException {
        val resource = when (kind) {
            Kind.LEGACY -> R.string.sync_folder_legacy
            Kind.SIMILAR -> R.string.sync_folder_similar
            Kind.DIVERGED -> R.string.sync_folder_diverged
            Kind.MISSING_REVISION -> R.string.sync_folder_revision_missing
            Kind.CHANGED_PAGE -> R.string.sync_folder_page_changed
            Kind.MISSING_PAGE -> R.string.sync_folder_page_missing
        }
        return IOException(context.getString(resource, subject.take(100)), this)
    }
}
