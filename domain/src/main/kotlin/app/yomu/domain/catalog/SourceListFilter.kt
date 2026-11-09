package app.yomu.domain.catalog

import kotlinx.serialization.Serializable

/** Which sources marked 18+ the catalog lists. */
@Serializable
enum class NsfwFilter { ALL, HIDE, ONLY }

/**
 * Filters of the source list of the catalog (1.8.0), stored in the settings. The languages are a setting of their
 * own (shared with the global search), so they are not part of this. Empty sets mean "any".
 */
@Serializable
data class SourceListFilter(
    /** Names of `SourceContent` (MANGA, NOVEL, BOOK) to show. */
    val content: Set<String> = emptySet(),
    /** Names of `SourceInfo.Kind` (EXTENSION, BUILT_IN, OPDS, LOCAL) to show. */
    val kinds: Set<String> = emptySet(),
    val pinnedOnly: Boolean = false,
    val hideDisabled: Boolean = false,
    val nsfw: NsfwFilter = NsfwFilter.ALL,
    /** Hides the sources shown as "not responding". */
    val hideUnstable: Boolean = false,
    /** Lists the sources the user hid (one by one or with their whole extension). */
    val showHidden: Boolean = false,
) {
    /** Number of filters that differ from "show everything" (for the badge of the filter button). */
    val activeCount: Int
        get() = listOf(content.isNotEmpty(), kinds.isNotEmpty(), pinnedOnly, hideDisabled, nsfw != NsfwFilter.ALL, hideUnstable, showHidden).count { it }

    val isActive: Boolean get() = activeCount > 0

    /**
     * Whether a source passes. A pure function of the source's properties so the list can be filtered off the main
     * thread and tested without Android.
     */
    fun matches(content: String, kind: String, pinned: Boolean, enabled: Boolean, nsfw: Boolean, unstable: Boolean, hidden: Boolean = false): Boolean =
        (this.content.isEmpty() || content in this.content) &&
            (kinds.isEmpty() || kind in kinds) &&
            (!pinnedOnly || pinned) &&
            (!hideDisabled || enabled) &&
            when (this.nsfw) {
                NsfwFilter.ALL -> true
                NsfwFilter.HIDE -> !nsfw
                NsfwFilter.ONLY -> nsfw
            } &&
            (!hideUnstable || !unstable) &&
            (showHidden || !hidden)
}
