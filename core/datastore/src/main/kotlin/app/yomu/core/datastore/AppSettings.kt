package app.yomu.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.yomu.domain.catalog.ChapterDisplay
import app.yomu.domain.catalog.LibraryDisplay
import app.yomu.domain.catalog.SourceListFilter
import app.yomu.domain.catalog.SourceSortMode
import app.yomu.domain.model.CommonReaderSettings
import app.yomu.domain.model.CustomAction
import app.yomu.domain.model.LibrarySort
import app.yomu.domain.model.MangaDirection
import app.yomu.domain.model.MangaSettings
import app.yomu.domain.model.PdfSettings
import app.yomu.domain.model.SelectionAction
import app.yomu.domain.model.SelectionActionConfig
import app.yomu.domain.model.TextReaderSettings
import app.yomu.domain.model.WebSearchEngine
import app.yomu.domain.model.WordTapGesture
import app.yomu.domain.translation.TranslationMode
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * A settings file that cannot be read (cut short by a crash or a full disk) is replaced with defaults instead of
 * making every start of the app fail.
 */
private val Context.yomuStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

val SettingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

/** All user settings of the app, backed by Preferences DataStore. Every value is exposed as a [Pref] with a Flow. */
class AppSettings(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.yomuStore

    private fun bool(name: String, default: Boolean) = Pref(store, booleanPreferencesKey(name), default, { it }, { it })
    private fun int(name: String, default: Int) = Pref(store, intPreferencesKey(name), default, { it }, { it })
    private fun long(name: String, default: Long) = Pref(store, longPreferencesKey(name), default, { it }, { it })
    private fun float(name: String, default: Float) = Pref(store, floatPreferencesKey(name), default, { it }, { it })
    private fun string(name: String, default: String) = Pref(store, stringPreferencesKey(name), default, { it }, { it })
    private fun stringSet(name: String, default: Set<String> = emptySet()) = Pref(store, stringSetPreferencesKey(name), default, { it }, { it })
    private inline fun <reified E : Enum<E>> enum(name: String, default: E) =
        Pref(store, stringPreferencesKey(name), default, { v -> enumValues<E>().firstOrNull { it.name == v } }, { it.name })
    private fun <T> json(name: String, default: T, serializer: KSerializer<T>) =
        Pref(store, stringPreferencesKey(name), default, { SettingsJson.decodeFromString(serializer, it) }, { SettingsJson.encodeToString(serializer, it) })

    // Appearance
    /** SYSTEM, LIGHT or DARK (names of core.ui ThemeMode). */
    /** Dark by default since 1.5.0 (graphite and lavender); "SYSTEM" follows the phone. */
    val themeMode = string("theme_mode", "DARK")
    /** Material You colours. Off by default since 1.2.0: the app uses its own palette ([accent] YOMU). */
    val dynamicColor = bool("dynamic_color", false)
    val amoled = bool("amoled", false)
    val accent = string("accent", "YOMU")
    /** ARGB colour of the "custom" accent (1.5.0). */
    val accentCustom = long("accent_custom", 0xFF8F7FD6)
    /** Labels under every icon of the navigation bar; off: only the selected tab shows its name (1.5.0). */
    val navLabels = bool("nav_labels", false)
    /** Tabs of the navigation bar in the user's order (names of core.ui NavTab); empty: the default order (1.6.0). */
    val navTabOrder = json("nav_tab_order", emptyList(), ListSerializer(String.serializer()))
    /** Tabs hidden from the navigation bar (names of core.ui NavTab; Settings can't be hidden) (1.6.0). */
    val navTabsHidden = stringSet("nav_tabs_hidden")
    val haptics = bool("haptics", true)
    val reduceMotion = bool("reduce_motion", false)
    val onboardingDone = bool("onboarding_done", false)
    val shownHints = stringSet("shown_hints")

    // Library
    val libraryGrid = bool("library_grid", true)
    val gridColumns = int("grid_columns", 0)
    val librarySort = enum("library_sort", LibrarySort.LAST_READ)
    val libraryAscending = bool("library_ascending", false)
    val showContinueReading = bool("show_continue", true)
    /** Covers are replaced by their generated placeholder everywhere in the app (and the home-screen widget). */
    val hideCovers = bool("hide_covers", false)
    /** COPY or LINK. */
    val importMode = string("import_mode", "COPY")
    val scanFolders = stringSet("scan_folders")
    val autoScan = bool("auto_scan", true)
    val autoScanHours = int("auto_scan_hours", 12)
    val askDeleteFiles = bool("ask_delete_files", true)

    // Reader defaults
    val textSettings = json("text_settings", TextReaderSettings(), TextReaderSettings.serializer())
    val mangaSettings = json("manga_settings", MangaSettings(), MangaSettings.serializer())
    val pdfSettings = json("pdf_settings", PdfSettings(), PdfSettings.serializer())
    val commonReader = json("common_reader", CommonReaderSettings(), CommonReaderSettings.serializer())
    val selectionActions = json("selection_actions", defaultSelectionActions(), ListSerializer(SelectionActionConfig.serializer()))
    val customActions = json("custom_actions", emptyList(), ListSerializer(CustomAction.serializer()))
    val wordTap = enum("word_tap", WordTapGesture.DOUBLE_TAP)
    val webSearch = enum("web_search", WebSearchEngine.GOOGLE)
    val customSearchUrl = string("custom_search_url", "")
    val showReadingStats = bool("reading_stats", true)
    val readerHintsShown = bool("reader_hints", false)

    // Translation
    val translationMode = enum("translation_mode", TranslationMode.OFFLINE_FIRST)
    /** google, deepl, libre, llm */
    val onlineEngine = string("online_engine", "google")
    /** Empty = device language. */
    val targetLang = string("target_lang", "")
    /** Empty = auto detect. */
    val sourceLang = string("source_lang", "")
    val deeplKey = string("deepl_key", "")
    val libreUrl = string("libre_url", "https://libretranslate.com")
    val libreKey = string("libre_key", "")
    val llmUrl = string("llm_url", "https://api.openai.com/v1")
    val llmKey = string("llm_key", "")
    val llmModel = string("llm_model", "gpt-4o-mini")
    val llmPrompt = string("llm_prompt", DEFAULT_LLM_PROMPT)
    val translationCache = bool("translation_cache", true)
    val modelsWifiOnly = bool("models_wifi_only", true)
    val ocrLanguage = string("ocr_language", "ja")

    // Dictionary
    val onlineDictionary = bool("online_dictionary", true)
    val dictionaryLang = string("dictionary_lang", "")

    // Vocabulary and SRS
    val dailyGoal = int("daily_goal", 20)
    val reminderEnabled = bool("reminder_enabled", false)
    val reminderHour = int("reminder_hour", 19)
    val reminderMinute = int("reminder_minute", 0)

    // Notifications
    /** Kinds of notifications switched off (names of data.work.NotifyKind); they still appear in the in-app list. */
    val notifyDisabled = stringSet("notify_disabled")
    /** Quiet hours: notifications arrive without sound or vibration from [quietFrom] to [quietTo] (hours). */
    val quietHours = bool("quiet_hours", false)
    val quietFrom = int("quiet_from", 22)
    val quietTo = int("quiet_to", 8)
    val reviewModes = stringSet("review_modes", setOf("WORD_TO_TRANSLATION", "TRANSLATION_TO_WORD", "CLOZE"))

    // Catalog
    val updateIntervalHours = int("update_interval_hours", 6)
    val updateWifiOnly = bool("update_wifi_only", true)
    val showNsfw = bool("show_nsfw", false)
    val catalogLanguages = stringSet("catalog_languages")
    val incognito = bool("incognito", false)
    val trustedExtensions = stringSet("trusted_extensions")
    /** Bundled extensions the user uninstalled: they are not copied back on the next start. */
    val removedBundledExtensions = stringSet("removed_bundled_extensions")
    /** Sources (ids) the user hid from the catalog and the global search. */
    val hiddenSources = stringSet("hidden_sources")
    /** Extensions (package names) whose sources are all hidden from the catalog and the global search. */
    val hiddenExtensions = stringSet("hidden_extensions")
    /** Languages shown in the list of available extensions; empty = device language, English and multilingual. */
    val extensionLanguages = stringSet("extension_languages")
    /** Chapter list sort and filters of titles without their own (see "Use for all titles" on the title page). */
    val chapterDisplayDefault = json("chapter_display_default", ChapterDisplay(), ChapterDisplay.serializer())
    /** Sort and filters of the online library tab. */
    val libraryDisplay = json("catalog_library_display", LibraryDisplay(), LibraryDisplay.serializer())
    val sourceSort = enum("source_sort", SourceSortMode.RECENT)
    /** Filters of the source list besides the languages (1.8.0). */
    val catalogSourceFilter = json("catalog_source_filter", SourceListFilter(), SourceListFilter.serializer())
    /** Global search hides sources without results. */
    val globalSearchOnlyResults = bool("global_search_only_results", false)
    /** Recent searches in a source (newest first, at most 10). */
    val sourceSearchHistory = json("source_search_history", emptyList(), ListSerializer(String.serializer()))

    // Downloads
    val downloadWifiOnly = bool("download_wifi_only", false)
    val maxParallelDownloads = int("max_parallel_downloads", 2)
    val downloadFolder = string("download_folder", "")
    val autoDeleteRead = bool("auto_delete_read", false)
    val packCbz = bool("pack_cbz", true)
    /** Queue new chapters of library titles found by the update check. */
    val autoDownloadNew = bool("auto_download_new", false)
    /** While reading a library title, keep this many next chapters downloaded (0 = off). */
    val downloadAhead = int("download_ahead", 0)
    /** Automatic retries of a download after network errors, timeouts and server errors. */
    val downloadRetries = int("download_retries", 3)
    /** Pages of one chapter downloaded at the same time (1..6). */
    val pageDownloadThreads = int("page_download_threads", 3)

    // Network and traffic
    /** On mobile data: fewer pages preloaded, no automatic downloads (new chapters, chapters ahead). */
    val dataSaver = bool("data_saver", false)
    /** Covers are loaded over mobile data (otherwise only covers already cached are shown there). */
    val coversOnMobile = bool("covers_on_mobile", true)
    /** Ask before opening a chapter that is not downloaded while on mobile data. */
    val warnMobileReading = bool("warn_mobile_reading", false)

    // Backup and sync
    val autoBackupHours = int("auto_backup_hours", 0)
    val backupFolder = string("backup_folder", "")
    val backupKeep = int("backup_keep", 5)
    val lastBackupAt = long("last_backup_at", 0)
    val syncFolder = string("sync_folder", "")
    val syncEnabled = bool("sync_enabled", false)
    val lastSyncAt = long("last_sync_at", 0)
    /** FOLDER (a folder picked with the system file picker) or WEBDAV (2.0.0). */
    val syncBackend = string("sync_backend", "FOLDER")
    /** Folder address on a WebDAV server, for example https://webdav.yandex.ru/Yomu. */
    val webdavUrl = string("webdav_url", "")
    val webdavUser = string("webdav_user", "")
    val webdavPassword = string("webdav_password", "")
    /** GitHub personal access token with access to [githubRepo] (contents: read and write). */
    val githubToken = string("github_token", "")
    /** owner/name of the (private) repository that holds the sync files. */
    val githubRepo = string("github_repo", "")
    /** Sync when the app starts and when it goes to the background (2.0.0). */
    val syncOnStart = bool("sync_on_start", true)
    val syncOnExit = bool("sync_on_exit", true)
    val syncIntervalHours = int("sync_interval_hours", 6)
    /** Online library, chapter marks and tabs (2.0.0). */
    val syncCatalog = bool("sync_catalog", true)
    /** Opt-in copy of book files through WebDAV (not shared folders). */
    val syncBookFiles = bool("sync_book_files", false)
    /** Deleted highlights, notes, bookmarks, words and online titles are deleted on the other devices too (2.0.0). */
    val syncDeletions = bool("sync_deletions", true)
    /** Granular sync (what takes part): reading positions and statuses, highlights with notes, bookmarks, words, tracker links. */
    val syncProgress = bool("sync_progress", true)
    val syncAnnotations = bool("sync_annotations", true)
    val syncBookmarks = bool("sync_bookmarks", true)
    val syncWords = bool("sync_words", true)
    val syncTrackLinks = bool("sync_track_links", true)
    /** Automatic syncs over a network target wait for Wi-Fi (or another unmetered network). */
    val syncWifiOnly = bool("sync_wifi_only", false)
    /** Result of the last sync attempt: empty = success, otherwise the error text. */
    val lastSyncError = string("last_sync_error", "")

    // Tracking (2.0.0)
    /** Move the entries on MyAnimeList, AniList and Shikimori forward when chapters are read. */
    val trackAutoUpdate = bool("track_auto_update", true)

    // Long-press menus (2.0.0)
    val pageMenuStyle = enum("page_menu_style", app.yomu.domain.model.QuickMenuStyle.RADIAL)
    val selectionMenuStyle = enum("selection_menu_style", app.yomu.domain.model.QuickMenuStyle.BAR)
    val pageActions = json("page_actions", app.yomu.domain.model.QuickActions.defaultPageActions, ListSerializer(app.yomu.domain.model.PageActionConfig.serializer()))

    // PDF annotations (2.0.0)
    /** Highlights and notes of PDF books are also written into the PDF file (as standard annotations). */
    val pdfWriteAnnotations = bool("pdf_write_annotations", true)

    // Security
    val appLock = bool("app_lock", false)
    val lockBiometric = bool("lock_biometric", true)
    val pinHash = string("pin_hash", "")
    val lockTimeoutSeconds = int("lock_timeout", 0)
    val secureScreen = bool("secure_screen", false)

    // Advanced
    val verboseLogging = bool("verbose_logging", false)
    val readingSpeedCpm = float("reading_speed_cpm", 0f)
    /** User text presets of the reader (JSON list of TextPreset). */
    val readerPresetsJson = string("reader_presets_json", "[]")
    /** Daily reading goal shown in the history and statistics. */
    val dailyGoalMinutes = int("daily_goal_minutes", 20)

    // Profile (1.8.0): the name and avatar of the profile tab and of the shared statistics card. Exported with the
    // other settings, so a backup restores the profile on a new phone.
    /** Name shown in the profile; "" = not set (the profile says "Reader"). */
    val profileName = string("profile_name", "")
    /** Emoji used as the avatar; "" = the first letter of [profileName]. */
    val profileAvatar = string("profile_avatar", "")

    // App updates (GitHub Releases) and "What's new"
    /** Look for a new version once a day at start. Off by default: the app only goes online when asked to. */
    val updateAutoCheck = bool("update_auto_check", false)
    /** When the last update check finished, for the daily check. */
    val updateCheckedAt = long("update_checked_at", 0)
    /** Version of the newest release found by the last check ("" = none): the dot on Settings while it is newer. */
    val updateLatest = string("update_latest", "")
    /** versionCode whose "What's new" the user has seen; 0 = none yet. */
    val whatsNewSeen = long("whats_new_seen", 0)
    /** Time of the newest crash report the user was told about at start (1.9.0); 0 = none. */
    val crashSeenAt = long("crash_seen_at", 0)

    /** Keys that are never exported to a backup or sync file. */
    private val privateKeys = setOf("pin_hash", "deepl_key", "libre_key", "llm_key", "webdav_password", "github_token")

    /** State of this installation, not a preference: never exported, and a backup never overwrites it. */
    private val deviceKeys = setOf("update_checked_at", "update_latest", "whats_new_seen", "crash_seen_at", "last_sync_error")

    /** Exports all stored preferences as typed JSON (used by backup). Secrets are skipped unless [includeSecrets]. */
    suspend fun exportJson(includeSecrets: Boolean = false): JsonObject {
        val prefs = store.data.first().asMap()
        val map = LinkedHashMap<String, JsonElement>()
        for ((k, v) in prefs) {
            if (!includeSecrets && k.name in privateKeys) continue
            if (k.name in deviceKeys) continue
            map[k.name] = when (v) {
                is Boolean -> JsonObject(mapOf("t" to JsonPrimitive("b"), "v" to JsonPrimitive(v)))
                is Int -> JsonObject(mapOf("t" to JsonPrimitive("i"), "v" to JsonPrimitive(v)))
                is Long -> JsonObject(mapOf("t" to JsonPrimitive("l"), "v" to JsonPrimitive(v)))
                is Float -> JsonObject(mapOf("t" to JsonPrimitive("f"), "v" to JsonPrimitive(v)))
                is Double -> JsonObject(mapOf("t" to JsonPrimitive("d"), "v" to JsonPrimitive(v)))
                is String -> JsonObject(mapOf("t" to JsonPrimitive("s"), "v" to JsonPrimitive(v)))
                is Set<*> -> JsonObject(mapOf("t" to JsonPrimitive("ss"), "v" to JsonArray(v.map { JsonPrimitive(it.toString()) })))
                else -> continue
            }
        }
        return JsonObject(map)
    }

    /** Level of the one-time upgrades of stored values that already ran, see [migrate]. */
    private val migrationLevel = int(MIGRATION_KEY, 0)

    /**
     * One-time upgrades of stored values, run at start and after a settings import.
     * 1: manga reads left to right by default (1.2.0). Older versions stored their right-to-left default
     * together with any other manga setting, so the stored direction is switched once; per-book choices stay.
     */
    suspend fun migrate() {
        val level = migrationLevel.get()
        if (level < 1) {
            if (store.data.first()[mangaSettings.key] != null) {
                mangaSettings.update { if (it.direction == MangaDirection.RTL) it.copy(direction = MangaDirection.LTR) else it }
            }
        }
        if (level < MIGRATION_LEVEL) migrationLevel.set(MIGRATION_LEVEL)
    }

    suspend fun importJson(obj: JsonObject) {
        store.editOffMain { p ->
            // A backup of an older version has not seen the newer upgrades: run them again for its values.
            if (MIGRATION_KEY !in obj) p.remove(intPreferencesKey(MIGRATION_KEY))
            for ((name, el) in obj) {
                if (name in privateKeys || name in deviceKeys) continue
                val o = runCatching { el.jsonObject }.getOrNull() ?: continue
                val type = o["t"]?.jsonPrimitive?.content ?: continue
                val v = o["v"] ?: continue
                runCatching {
                    when (type) {
                        "b" -> p[booleanPreferencesKey(name)] = v.jsonPrimitive.boolean
                        "i" -> p[intPreferencesKey(name)] = v.jsonPrimitive.int
                        "l" -> p[longPreferencesKey(name)] = v.jsonPrimitive.long
                        "f" -> p[floatPreferencesKey(name)] = v.jsonPrimitive.float
                        "d" -> p[doublePreferencesKey(name)] = v.jsonPrimitive.double
                        "s" -> p[stringPreferencesKey(name)] = v.jsonPrimitive.content
                        "ss" -> p[stringSetPreferencesKey(name)] = v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    }
                }
            }
        }
        migrate()
    }

    suspend fun resetAll() {
        store.editOffMain { it.clear() }
    }

    companion object {
        private const val MIGRATION_KEY = "settings_migration"
        private const val MIGRATION_LEVEL = 1

        const val DEFAULT_LLM_PROMPT =
            "Translate the following text from {from} to {to}. Keep the meaning, tone and formatting. Reply with the translation only.\n\n{text}"

        fun defaultSelectionActions(): List<SelectionActionConfig> = listOf(
            SelectionAction.HIGHLIGHT, SelectionAction.NOTE, SelectionAction.MEANING, SelectionAction.TRANSLATE,
            SelectionAction.SAVE_WORD, SelectionAction.COPY, SelectionAction.QUOTE, SelectionAction.SHARE,
            SelectionAction.UNDERLINE, SelectionAction.STRIKE, SelectionAction.SEARCH_BOOK, SelectionAction.SEARCH_WEB,
            SelectionAction.SPEAK, SelectionAction.SELECT_SENTENCE, SelectionAction.SELECT_PARAGRAPH,
            SelectionAction.SHARE_IMAGE, SelectionAction.BOOKMARK, SelectionAction.CUSTOM,
        ).map { SelectionActionConfig(it, enabled = it != SelectionAction.CUSTOM) }
    }
}
