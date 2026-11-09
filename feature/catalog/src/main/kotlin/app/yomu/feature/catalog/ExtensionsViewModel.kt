package app.yomu.feature.catalog

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.runCatchingApp
import app.yomu.core.datastore.AppSettings
import app.yomu.core.datastore.stateIn
import app.yomu.core.ui.components.errorMessage
import app.yomu.data.source.AvailableExtension
import app.yomu.data.source.ExtensionException
import app.yomu.data.source.ExtensionManager
import app.yomu.data.source.ExtensionStatus
import app.yomu.data.source.ExtensionUpdate
import app.yomu.data.source.InstalledExtension
import app.yomu.data.source.SourceInfo
import app.yomu.data.source.SourceManager
import app.yomu.data.source.StoreInfo
import app.yomu.data.source.StoreState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/** A language section of the available extensions list. */
data class ExtensionGroup(val lang: String, val items: List<AvailableExtension>)

/** State of the "add store" dialog. */
sealed interface StorePreview {
    val input: String
    data class Loading(override val input: String) : StorePreview
    data class Ready(override val input: String, val info: StoreInfo, val count: Int, val nsfwCount: Int) : StorePreview
    data class Failed(override val input: String, val message: Int, val detail: String?) : StorePreview
}

/** Extensions screen: installed (with updates, untrusted and broken ones), available by language, and stores. */
class ExtensionsViewModel(
    private val context: Context,
    private val extensions: ExtensionManager,
    private val settings: AppSettings,
    private val sources: SourceManager,
) : ViewModel() {
    /** Extensions whose sources are all hidden from the catalog and the global search. */
    val hiddenExtensions: StateFlow<Set<String>> = settings.hiddenExtensions.stateIn(viewModelScope)

    /** Pin, on/off and hidden state of every source, for the settings of an extension's sources. */
    val sourceInfos: StateFlow<Map<Long, SourceInfo>> = sources.infos.map { l -> l.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setExtensionHidden(pkg: String, hidden: Boolean) = viewModelScope.launch(ScreenErrors) { sources.setExtensionHidden(pkg, hidden) }
    fun setSourceHidden(id: Long, hidden: Boolean) = viewModelScope.launch(ScreenErrors) { sources.setHidden(id, hidden) }
    fun setSourcePinned(id: Long, pinned: Boolean) = viewModelScope.launch(ScreenErrors) { sources.setPinned(id, pinned) }
    fun setSourceEnabled(id: Long, enabled: Boolean) = viewModelScope.launch(ScreenErrors) { sources.setEnabled(id, enabled) }

    private val _events = Channel<CatalogEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val query = MutableStateFlow("")
    val showNsfw: StateFlow<Boolean> = settings.showNsfw.stateIn(viewModelScope)
    val installing: StateFlow<Map<String, Float?>> = extensions.installing
    val refreshing: StateFlow<Boolean> = extensions.refreshing
    val stores: StateFlow<List<StoreState>> = extensions.stores
    val updates: StateFlow<List<ExtensionUpdate>> = extensions.updates

    /** Languages the available list is filtered by (the default set when the user has not chosen any). */
    val languages: StateFlow<Set<String>> = settings.extensionLanguages.flow
        .map { set -> set.ifEmpty { defaultLanguages() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultLanguages())

    val installed: StateFlow<List<InstalledExtension>> = combine(extensions.installed, query) { list, q ->
        list.filter { q.isBlank() || it.name.contains(q.trim(), true) || it.pkg.contains(q.trim(), true) }
            .sortedWith(compareBy<InstalledExtension> { it.status != ExtensionStatus.UNTRUSTED }.thenBy { it.name.lowercase() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every language that any store offers, for the language picker. */
    val allLanguages: StateFlow<List<String>> = extensions.available.map { list ->
        list.map { it.lang }.distinct().sortedWith(languageOrder())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val available: StateFlow<List<ExtensionGroup>> = combine(
        extensions.available, extensions.installed, query, showNsfw, languages,
    ) { all, installed, q, nsfw, langs ->
        val installedPkgs = installed.map { it.pkg }.toSet()
        val term = q.trim()
        all.asSequence()
            .filter { it.pkg !in installedPkgs }
            .filter { nsfw || !it.isNsfw }
            .filter { term.isNotEmpty() || it.lang in langs || it.languages.any { l -> l in langs } }
            .filter { term.isEmpty() || it.name.contains(term, true) || it.sources.any { s -> s.name.contains(term, true) } }
            .distinctBy { it.pkg + "|" + it.storeUrl }
            .groupBy { it.lang }
            .map { (lang, items) -> ExtensionGroup(lang, items.sortedBy { it.name.lowercase() }) }
            .sortedWith(compareBy(languageOrder()) { it.lang })
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _preview = MutableStateFlow<StorePreview?>(null)
    val preview: StateFlow<StorePreview?> = _preview.asStateFlow()
    private var previewJob: Job? = null

    init {
        viewModelScope.launch(ScreenErrors) {
            extensions.reload()
            if (System.currentTimeMillis() - extensions.lastStoreRefreshAt > STORE_REFRESH_MS) refreshStores()
        }
    }

    fun refresh() = viewModelScope.launch(ScreenErrors) {
        runCatchingApp { extensions.reload() }
        refreshStores()
    }

    private suspend fun refreshStores() {
        runCatchingApp { extensions.refreshStores() }.onFailure { _events.send(CatalogEvent.Failure(it)) }
    }

    fun install(ext: AvailableExtension) = viewModelScope.launch(ScreenErrors) {
        run(ext.name) { extensions.install(ext) }?.let { _events.send(CatalogEvent.Message(R.string.ext_installed, ext.name)) }
    }

    fun update(update: ExtensionUpdate) = viewModelScope.launch(ScreenErrors) {
        run(update.installed.name) { extensions.install(update.available) }
            ?.let { _events.send(CatalogEvent.Message(R.string.ext_updated, update.installed.name)) }
    }

    fun updateAll() = viewModelScope.launch(ScreenErrors) {
        val list = updates.value
        var done = 0
        list.forEach { u -> if (run(u.installed.name) { extensions.install(u.available) } != null) done++ }
        if (done > 0) _events.send(CatalogEvent.Message(R.string.ext_updated_count, done.toString()))
    }

    fun installFromFile(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        run(null) { extensions.installFromUri(uri) }?.let { pkg ->
            val ext = extensions.installed.value.firstOrNull { it.pkg == pkg }
            _events.send(CatalogEvent.Message(R.string.ext_installed, ext?.name ?: pkg))
        }
    }

    fun uninstall(ext: InstalledExtension) = viewModelScope.launch(ScreenErrors) {
        if (!extensions.uninstall(ext)) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${ext.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            _events.send(CatalogEvent.Message(R.string.ext_removed, ext.name))
        }
    }

    fun trust(ext: InstalledExtension) = viewModelScope.launch(ScreenErrors) {
        extensions.trust(ext)
        val now = extensions.installed.value.firstOrNull { it.pkg == ext.pkg }
        if (now?.status == ExtensionStatus.BROKEN) _events.send(CatalogEvent.Message(R.string.ext_broken_after_trust, now.error))
    }

    /** Trusts every extension in [list] (the ones the user saw in the "Trust all" dialog) at once. */
    fun trustAll(list: List<InstalledExtension>) = viewModelScope.launch(ScreenErrors) {
        if (list.isEmpty()) return@launch
        extensions.trust(list)
        val pkgs = list.map { it.pkg }.toSet()
        val failed = extensions.installed.value.filter { it.pkg in pkgs && it.status == ExtensionStatus.BROKEN }
        if (failed.isEmpty()) {
            _events.send(CatalogEvent.Message(R.string.ext_trusted_all))
        } else {
            _events.send(CatalogEvent.Message(R.string.ext_broken_after_trust, failed.joinToString { it.name }))
        }
    }

    fun setShowNsfw(value: Boolean) = viewModelScope.launch(ScreenErrors) { settings.showNsfw.set(value) }

    fun toggleLanguage(lang: String) = viewModelScope.launch(ScreenErrors) {
        settings.extensionLanguages.update { current ->
            val base = current.ifEmpty { defaultLanguages() }
            (if (lang in base) base - lang else base + lang).ifEmpty { setOf(lang) }
        }
    }

    fun resetLanguages() = viewModelScope.launch(ScreenErrors) { settings.extensionLanguages.set(emptySet()) }

    // region stores

    /** Starts checking a store address; the dialog shows its name and size before anything is saved. */
    fun previewStore(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        previewJob?.cancel()
        _preview.value = StorePreview.Loading(text)
        previewJob = viewModelScope.launch(ScreenErrors) {
            _preview.value = try {
                val (info, list) = extensions.preview(text)
                StorePreview.Ready(text, info, list.size, list.count { it.isNsfw })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val res = messageFor(e)
                if (res != null) {
                    StorePreview.Failed(text, res, e.message)
                } else {
                    // Network and HTTP problems: say what happened instead of "not a repository".
                    StorePreview.Failed(text, R.string.ext_store_unreachable, errorMessage(context, e))
                }
            }
        }
    }

    fun dismissPreview() {
        previewJob?.cancel()
        _preview.value = null
    }

    fun confirmStore() = viewModelScope.launch(ScreenErrors) {
        val p = _preview.value as? StorePreview.Ready ?: return@launch
        _preview.value = null
        run(null) { extensions.addStore(p.input) }?.let { _events.send(CatalogEvent.Message(R.string.ext_store_added, it.name)) }
    }

    fun removeStore(url: String) = viewModelScope.launch(ScreenErrors) { extensions.removeStore(url) }

    // endregion

    /** Runs an extension operation and reports failures; returns null when it failed. */
    private suspend fun <T> run(name: String?, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val res = messageFor(e)
        if (res != null) _events.send(CatalogEvent.Message(res, name ?: e.message)) else _events.send(CatalogEvent.Failure(e))
        null
    }

    private fun messageFor(e: Throwable): Int? = when ((e as? ExtensionException)?.reason) {
        ExtensionException.Reason.NOT_AN_APK -> R.string.ext_err_not_apk
        ExtensionException.Reason.NOT_AN_EXTENSION -> R.string.ext_err_not_extension
        ExtensionException.Reason.PACKAGE_MISMATCH -> R.string.ext_err_package
        ExtensionException.Reason.UNSIGNED -> R.string.ext_err_unsigned
        ExtensionException.Reason.SIGNATURE_MISMATCH -> R.string.ext_err_signature
        ExtensionException.Reason.DOWNGRADE -> R.string.ext_err_downgrade
        ExtensionException.Reason.UNSUPPORTED_LIB -> R.string.ext_err_lib
        ExtensionException.Reason.STORE_EXISTS -> R.string.ext_store_exists
        ExtensionException.Reason.STORE_SAME_KEY -> R.string.ext_store_same_key
        ExtensionException.Reason.STORE_INVALID -> R.string.ext_store_invalid
        ExtensionException.Reason.STORE_KEY_CHANGED -> R.string.ext_store_key_changed
        ExtensionException.Reason.DOWNLOAD_FAILED -> R.string.ext_err_download
        null -> null
    }

    companion object {
        private const val STORE_REFRESH_MS = 10 * 60 * 1000L

        fun defaultLanguages(): Set<String> = setOf(Locale.getDefault().language, "en", "all")

        /** Device language first, then multilingual, English and the rest alphabetically by name. */
        fun languageOrder(): Comparator<String> {
            val device = Locale.getDefault().language
            return compareBy<String> { lang ->
                when {
                    lang == device || lang.startsWith("$device-") -> 0
                    lang == "all" || lang == "multi" -> 1
                    lang == "en" -> 2
                    else -> 3
                }
            }.thenBy { languageName(it).lowercase() }
        }
    }
}
