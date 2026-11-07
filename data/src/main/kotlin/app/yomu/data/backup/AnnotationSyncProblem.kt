package app.yomu.data.backup

import android.content.Context
import app.yomu.data.R
import java.io.IOException

/** Human-readable safety stop; no annotation is deleted when this is raised. */
internal class AnnotationSyncProblem(val kind: Kind, detail: String) : IOException(detail), SyncProblem {
    enum class Kind { CONFLICT, NO_PROOF, DUPLICATE }
    override val kindTag: String = "annotation_" + kind.name.lowercase()
    override val subject: String = ""
    fun forDisplay(context: Context): IOException = IOException(context.getString(when (kind) {
        Kind.CONFLICT -> R.string.sync_annotation_conflict
        Kind.NO_PROOF -> R.string.sync_annotation_no_proof
        Kind.DUPLICATE -> R.string.sync_annotation_duplicate
    }), this)
}
