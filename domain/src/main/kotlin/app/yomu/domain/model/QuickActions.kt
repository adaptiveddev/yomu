package app.yomu.domain.model

import kotlinx.serialization.Serializable

/**
 * Look of the long-press menus (2.0.0). BAR: the text selection toolbar of books; OFF: a long press on a manga page runs
 * the first enabled action at once (the behaviour before 2.0.0: translation).
 */
enum class QuickMenuStyle { BAR, LIST, RADIAL, OFF }

/** Actions of the long-press menu on a manga or comic page; order and visibility are configurable. */
enum class PageAction { TRANSLATE, SAVE, SHARE, COPY_TEXT, BOOKMARK, SET_COVER, NOTE }

@Serializable
data class PageActionConfig(val action: PageAction, val enabled: Boolean = true)

object QuickActions {
    val defaultPageActions: List<PageActionConfig> = PageAction.entries.map { PageActionConfig(it) }

    /** Stored list with actions added in later versions appended (enabled) and unknown ones dropped. */
    fun normalize(stored: List<PageActionConfig>): List<PageActionConfig> {
        val known = stored.distinctBy { it.action }
        return known + PageAction.entries.filter { a -> known.none { it.action == a } }.map { PageActionConfig(it) }
    }
}
