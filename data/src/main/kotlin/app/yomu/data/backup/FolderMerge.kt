package app.yomu.data.backup

/** Three-way decisions do not interpret a missing manifest entry as a deletion. */
internal object FolderMerge {
    enum class Decision { SAME, UPLOAD, DOWNLOAD, LOCALLY_MISSING, CONFLICT }

    fun decide(base: FolderEntry?, local: FolderEntry?, remote: FolderEntry?): Decision {
        val ids = listOfNotNull(base?.id, local?.id, remote?.id).toSet()
        if (ids.size > 1) return Decision.CONFLICT
        if (base != null && remote == null) return Decision.CONFLICT
        if (local == null) return when {
            base == null && remote != null -> Decision.DOWNLOAD
            base != null && remote?.pages != base.pages -> Decision.CONFLICT
            else -> Decision.LOCALLY_MISSING
        }
        if (remote == null) return Decision.UPLOAD
        if (local.pages == remote.pages) return Decision.SAME
        if (base == null) return Decision.CONFLICT
        return when {
            local.pages == base.pages -> Decision.DOWNLOAD
            remote.pages == base.pages -> Decision.UPLOAD
            else -> Decision.CONFLICT
        }
    }
}
