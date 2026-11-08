package app.yomu.data.backup

/**
 * Conservative three-way deletion decisions. Not a synchronization protocol by itself:
 * callers must atomically bind the deletion marker to the checked shared revisions and
 * recheck the local snapshot in the transaction applying it. Until all metadata, file and
 * tracking documents are covered by that protocol, this guard must not delete library rows.
 *
 * Fingerprints are opaque, canonical revisions; they must cover every protected field.
 * Local-only fingerprints must never be published as account credentials or tracker state.
 */
internal object BookDeletionGuard {
    @kotlinx.serialization.Serializable
    data class SharedRevision(val file: String, val metadata: String, val trackingLinks: String)
    data class LocalRevision(val shared: SharedRevision, val privateState: String, val hasPendingTrackerChanges: Boolean)
    data class Baseline(val shared: SharedRevision, val privateState: String)
    enum class Decision { IGNORE, ALREADY_ABSENT, PUBLISH, APPLY, CONFLICT }

    /** A missing local book is a deletion only if this target had a known baseline. */
    fun propose(enabled: Boolean, locallyPresent: Boolean, base: SharedRevision?, remote: SharedRevision?): Decision {
        if (!enabled || locallyPresent || base == null) return Decision.IGNORE
        return if (remote == base) Decision.PUBLISH else Decision.CONFLICT
    }

    /** Only a known, unchanged local copy may be removed in response to a marker. */
    fun receive(
        enabled: Boolean,
        markerExpected: SharedRevision,
        currentRemote: SharedRevision?,
        base: Baseline?,
        local: LocalRevision?,
    ): Decision {
        if (!enabled) return Decision.IGNORE
        // A marker can be stale even if the book is already absent. Do not call it
        // successfully applied while a newer shared version exists.
        if (currentRemote != markerExpected) return Decision.CONFLICT
        if (local == null) return Decision.ALREADY_ABSENT
        if (base == null || base.shared != markerExpected || local.shared != base.shared ||
            local.privateState != base.privateState || local.hasPendingTrackerChanges) return Decision.CONFLICT
        return Decision.APPLY
    }
}
