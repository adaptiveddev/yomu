package app.yomu.data.source

import android.content.Context
import android.graphics.drawable.Drawable
import app.yomu.data.audio.YouTubeClient
import app.yomu.data.audio.YouTubeSource
import app.yomu.core.database.SourceEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.data.source.tachiyomi.TachiyomiSource
import app.yomu.source.CatalogSource
import app.yomu.source.ConfigurableSource
import app.yomu.source.Source
import app.yomu.source.SourceContent
import app.yomu.sources.local.LocalSource
import app.yomu.sources.mangadex.MangaDexFactory
import app.yomu.sources.mangadex.MangaDexSource
import app.yomu.sources.novels.NovelBinSource
import app.yomu.sources.novels.RanobeLibSource
import app.yomu.sources.opds.GutenbergSource
import app.yomu.sources.opds.InternetArchiveSource
import app.yomu.sources.opds.OpdsSource
import app.yomu.sources.opds.OpenLibrarySource
import app.yomu.sources.opds.LibriVoxSource
import app.yomu.sources.opds.WikisourceSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Locale

/** Source metadata shown in the catalog tab. */
data class SourceInfo(
    val id: Long,
    val name: String,
    val lang: String,
    val content: SourceContent,
    val kind: Kind,
    val pinned: Boolean,
    val enabled: Boolean,
    val supportsLatest: Boolean,
    val configurable: Boolean,
    val extensionPkg: String? = null,
    val lastUsedAt: Long = 0,
    /** Has its own settings screen (Tachiyomi/Mihon extension preferences). */
    val hasSettings: Boolean = false,
    val extensionName: String? = null,
    val isNsfw: Boolean = false,
    /** Icon of the extension package that provides the source. */
    val icon: Drawable? = null,
    /** Key of the logo bundled for a built-in source ([LOGO_MANGADEX], …), shown when there is no [icon]. */
    val logo: String? = null,
    /** Hidden by the user, alone or with its whole extension: not listed in the catalog nor asked by global search. */
    val hidden: Boolean = false,
) {
    enum class Kind { BUILT_IN, OPDS, LOCAL, EXTENSION }

    companion object {
        const val LOGO_MANGADEX = "mangadex"
        const val LOGO_GUTENBERG = "gutenberg"
        const val LOGO_ARCHIVE = "archive"
        const val LOGO_OPEN_LIBRARY = "openlibrary"
        const val LOGO_WIKISOURCE = "wikisource"
    }
}

/** Registry of all catalog sources: built-in ones, user OPDS catalogs, the local source and loaded extensions. */
class SourceManager(
    private val context: Context,
    private val db: YomuDatabase,
    private val settings: AppSettings,
    private val extensions: ExtensionManager,
    youtube: YouTubeClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Timber.e(e, "Source list update failed") })
    private val json = Json { ignoreUnknownKeys = true }
    private val configSerializer = MapSerializer(String.serializer(), String.serializer())

    val local = LocalSource(context)
    private val builtIn: List<CatalogSource> = buildList {
        add(local)
        MangaDexFactory().createSources().filterIsInstance<CatalogSource>().forEach { add(it) }
        add(GutenbergSource())
        add(InternetArchiveSource())
        add(OpenLibrarySource())
        add(LibriVoxSource())
        add(YouTubeSource(youtube))
        listOf("en", "ru", "de", "fr").forEach { add(WikisourceSource(it)) }
        // Online novels (2.0.0): Mihon extensions are manga only.
        add(RanobeLibSource())
        add(NovelBinSource())
    }
    private val opds = MutableStateFlow<List<OpdsSource>>(emptyList())
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * All sources. An extension source with the same id as a built-in one (for example the MangaDex extension and the
     * built-in MangaDex use the same id scheme and URLs) replaces it, so saved titles keep working with either.
     */
    val sources: StateFlow<List<CatalogSource>> = combine(opds, extensions.loaded) { o, e ->
        val ext = e.flatMap { it.sources }.distinctBy { it.id }
        val extIds = ext.map { it.id }.toSet()
        builtIn.filter { it is LocalSource || it.id !in extIds } + o + ext.filter { it.id != LocalSource.ID }
    }.stateIn(scope, SharingStarted.Eagerly, builtIn)

    val infos: StateFlow<List<SourceInfo>> = combine(sources, db.sources().observe(), settings.hiddenSources.flow, settings.hiddenExtensions.flow) { list, rows, hiddenSources, hiddenExtensions ->
        val byId = rows.associateBy { it.id }
        // The extension of every source in one pass: looking it up per source went over all sources of all
        // extensions for each one (millions of comparisons with the big repositories, on every list change).
        val extOf = HashMap<Long, InstalledExtension>()
        extensions.loaded.value.forEach { e -> e.sources.forEach { src -> extOf.putIfAbsent(src.id, e) } }
        list.map { s ->
            val row = byId[s.id]
            val ext = extOf[s.id]
            SourceInfo(
                id = s.id, name = s.name, lang = s.lang, content = s.content,
                kind = when {
                    s is LocalSource -> SourceInfo.Kind.LOCAL
                    s is OpdsSource -> SourceInfo.Kind.OPDS
                    ext != null -> SourceInfo.Kind.EXTENSION
                    else -> SourceInfo.Kind.BUILT_IN
                },
                pinned = row?.pinned ?: false,
                enabled = row?.enabled ?: true,
                supportsLatest = s.supportsLatest,
                configurable = s is ConfigurableSource,
                extensionPkg = ext?.pkg,
                lastUsedAt = row?.lastUsedAt ?: 0,
                hasSettings = (s as? TachiyomiSource)?.hasPreferences == true,
                extensionName = ext?.name,
                isNsfw = ext?.isNsfw == true,
                icon = ext?.icon,
                logo = if (ext == null) logoOf(s) else null,
                hidden = s.id.toString() in hiddenSources || (ext != null && ext.pkg in hiddenExtensions),
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Languages the catalog lists and the global search queries: the user's choice; until they choose, the device
     * language and English (the bundled extensions alone bring more than a hundred language variants).
     * [ALL_LANGUAGES] means every language.
     */
    val languages: StateFlow<Set<String>> = combine(settings.catalogLanguages.flow, infos) { stored, list ->
        effectiveLanguages(stored, list.map { it.lang })
    }.stateIn(scope, SharingStarted.Eagerly, setOf(ALL_LANGUAGES))

    /** Whether [info] passes the language filter; local folders, own OPDS catalogs and multi-language sources always do. */
    fun isShown(info: SourceInfo, langs: Set<String> = languages.value): Boolean =
        ALL_LANGUAGES in langs || info.kind == SourceInfo.Kind.LOCAL || info.kind == SourceInfo.Kind.OPDS ||
            info.lang.isBlank() || info.lang == "all" || info.lang in langs

    init {
        scope.launch {
            // Each step on its own: one broken OPDS row or extension must not leave the catalog "not ready" forever.
            try {
                runCatching { reloadOpds() }.onFailure { Timber.e(it, "Loading OPDS catalogs failed") }
                runCatching { db.sources().get(LocalSource.ID)?.configJson?.let { local.configure(decode(it)) } }
                    .onFailure { Timber.e(it, "Configuring the local source failed") }
                runCatching { extensions.reload() }.onFailure { Timber.e(it, "Loading extensions failed") }
            } finally {
                _ready.value = true
            }
        }
    }

    private fun logoOf(s: CatalogSource): String? = when (s) {
        is MangaDexSource -> SourceInfo.LOGO_MANGADEX
        is GutenbergSource -> SourceInfo.LOGO_GUTENBERG
        is InternetArchiveSource -> SourceInfo.LOGO_ARCHIVE
        is OpenLibrarySource -> SourceInfo.LOGO_OPEN_LIBRARY
        is WikisourceSource -> SourceInfo.LOGO_WIKISOURCE
        else -> null
    }

    private fun decode(s: String): Map<String, String> = runCatching { json.decodeFromString(configSerializer, s) }.getOrDefault(emptyMap())

    private suspend fun reloadOpds() {
        val rows = db.sources().all().filter { it.kind == KIND_OPDS }
        opds.value = rows.mapNotNull { row ->
            val cfg = row.configJson?.let(::decode) ?: return@mapNotNull null
            val url = cfg["url"] ?: return@mapNotNull null
            OpdsSource(row.name, url, row.lang, cfg["username"], cfg["password"])
        }
    }

    fun get(id: Long): CatalogSource? = sources.value.firstOrNull { it.id == id }

    fun getOrStub(id: Long, name: String = "Unknown"): Source = get(id) ?: object : Source {
        override val id = id
        override val name = name
        override val lang = ""
        override val content = SourceContent.MANGA
        override val supportsLatest = false
    }

    suspend fun addOpds(name: String, url: String, username: String?, password: String?): Long {
        val src = OpdsSource(name, url.trim(), "all", username, password)
        val cfg = buildMap {
            put("url", url.trim())
            username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
            password?.takeIf { it.isNotBlank() }?.let { put("password", it) }
        }
        db.sources().upsert(SourceEntity(src.id, name, "all", KIND_OPDS, pinned = true, enabled = true, configJson = json.encodeToString(configSerializer, cfg), lastUsedAt = 0))
        reloadOpds()
        return src.id
    }

    suspend fun removeOpds(id: Long) {
        db.sources().delete(id)
        reloadOpds()
    }

    suspend fun configureLocal(folderUri: String) {
        val cfg = mapOf(LocalSource.KEY_FOLDER to folderUri)
        local.configure(cfg)
        db.sources().upsert(SourceEntity(LocalSource.ID, local.name, local.lang, "local", pinned = true, enabled = true, configJson = json.encodeToString(configSerializer, cfg), lastUsedAt = 0))
    }

    private suspend fun ensureRow(id: Long): SourceEntity {
        db.sources().get(id)?.let { return it }
        val s = get(id)
        val row = SourceEntity(id, s?.name ?: "?", s?.lang ?: "", "builtin", pinned = false, enabled = true, configJson = null, lastUsedAt = 0)
        db.sources().upsert(row)
        return row
    }

    suspend fun setPinned(id: Long, pinned: Boolean) {
        ensureRow(id)
        db.sources().setPinned(id, pinned)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        ensureRow(id)
        db.sources().setEnabled(id, enabled)
    }

    suspend fun setHidden(id: Long, hidden: Boolean) {
        settings.hiddenSources.update { if (hidden) it + id.toString() else it - id.toString() }
    }

    suspend fun setExtensionHidden(pkg: String, hidden: Boolean) {
        settings.hiddenExtensions.update { if (hidden) it + pkg else it - pkg }
    }

    /** Shows every hidden source and extension again. */
    suspend fun unhideAll() {
        settings.hiddenSources.set(emptySet())
        settings.hiddenExtensions.set(emptySet())
    }

    /** When each source was last marked as used in this run (see [touch]). */
    private val touchedAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /**
     * Marks the source as used (the "Recent" order of the catalog). Called for every page that is loaded, so it writes
     * at most once a minute per source: each write made the whole source list be built and sorted again.
     */
    suspend fun touch(id: Long) {
        val now = System.currentTimeMillis()
        val last = touchedAt[id]
        if (last != null && now - last < TOUCH_INTERVAL_MS) return
        touchedAt[id] = now
        ensureRow(id)
        db.sources().touch(id, now)
    }

    companion object {
        const val KIND_OPDS = "opds"
        private const val TOUCH_INTERVAL_MS = 60_000L

        /** Stored in the language filter when the user picked "All languages". */
        const val ALL_LANGUAGES = "*"

        fun effectiveLanguages(stored: Set<String>, available: Collection<String>, device: String = Locale.getDefault().language): Set<String> =
            stored.ifEmpty {
                available.filter { it == "en" || it == device || it.startsWith("$device-") }.toSet().ifEmpty { setOf(ALL_LANGUAGES) }
            }
    }
}
