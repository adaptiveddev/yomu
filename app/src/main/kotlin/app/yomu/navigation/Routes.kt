package app.yomu.navigation

import kotlinx.serialization.Serializable

/**
 * All top-level tabs (library, profile, catalog, dictionary, settings) in one destination since 1.9.0, so that a tab
 * stays composed while another one is on screen (see [TabsHost]).
 */
@Serializable data object HomeRoute

// Library & reading
@Serializable data class BookDetailsRoute(val bookId: Long)
@Serializable data class ReaderRoute(val bookId: Long = -1, val locator: String? = null, val chapterId: Long = -1, val positionReference: String? = null)
/** [bookId] < 0 shows the annotations of all books. */
@Serializable data class AnnotationsRoute(val bookId: Long = -1)
@Serializable data object StatsRoute
@Serializable data class EditorRoute(val bookId: Long)
/** Print layout of a Word document. */
@Serializable data class DocxLayoutRoute(val bookId: Long)

// Dictionary & translation
@Serializable data class LookupRoute(val query: String)
@Serializable data object DictionariesRoute
@Serializable data object ReviewRoute
@Serializable data object LanguagePacksRoute

// Catalog
/** [tab] is the [app.yomu.feature.catalog.BrowseTab] to open with (the language switch keeps the one on display). */
@Serializable data class SourceRoute(val sourceId: Long, val query: String? = null, val navUrl: String? = null, val navTitle: String? = null, val tab: String? = null)
/** [migrateFrom] >= 0 switches the global search to migration of that entry. */
@Serializable data class GlobalSearchRoute(val query: String? = null, val migrateFrom: Long = -1)
@Serializable data class TitleRoute(val entryId: Long)
@Serializable data class ExtensionsRoute(val addStore: String? = null)
@Serializable data class SourceSettingsRoute(val sourceId: Long)
@Serializable data class WebViewRoute(val url: String)
@Serializable data object DownloadsRoute

// Settings
/** One section of the settings opened from elsewhere (the profile opens Backup and sync); [section] is a SettingsSection name. */
@Serializable data class SettingsSectionRoute(val section: String)
