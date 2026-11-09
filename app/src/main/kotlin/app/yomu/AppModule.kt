package app.yomu

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.IntentCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.CrashReport
import app.yomu.core.common.ErrorLog
import app.yomu.core.common.ScreenErrors
import app.yomu.core.datastore.AppSettings
import app.yomu.core.ui.components.HintStore
import app.yomu.core.ui.components.LauncherIcons
import app.yomu.core.ui.components.NavTab
import app.yomu.core.ui.theme.Accent
import app.yomu.core.ui.theme.ThemeConfig
import app.yomu.core.ui.theme.ThemeMode
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.data.work.Notifications
import app.yomu.feature.settings.AppUpdates
import app.yomu.profile.ProfileTrackingViewModel
import app.yomu.profile.ProfileViewModel
import app.yomu.ui.WhatsNew
import app.yomu.ui.whatsNewSince
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module
import app.yomu.widget.ContinueWidgets
import app.yomu.data.audio.AudiobookStore
import app.yomu.feature.audio.AudioSessionActivity
import app.yomu.data.repository.ReadingHistoryRepository

val appModule = module {
    single { app.yomu.core.common.AppScope() }
    single { AppLockController(get()) }
    single { AutoSync(androidContext(), get()) }
    // OAuth client ids of the tracking services, set at build time (docs/TRACKING.md); the user can enter their own.
    single {
        app.yomu.data.tracking.TrackerKeys(
            anilistClientId = BuildConfig.ANILIST_CLIENT_ID,
            malClientId = BuildConfig.MAL_CLIENT_ID,
            shikimoriClientId = BuildConfig.SHIKIMORI_CLIENT_ID,
            shikimoriClientSecret = BuildConfig.SHIKIMORI_CLIENT_SECRET,
        )
    }
    single<HintStore> { SettingsHintStore(get()) }
    single { AppLauncherIcons(androidContext()) } bind LauncherIcons::class
    viewModel { MainViewModel(get(), get(), get(), get(), get()) }
    // The audiobook notification opens the player of its book (through the widget action that opens a book).
    single<AudioSessionActivity> {
        AudioSessionActivity { context, bookId ->
            val intent = android.content.Intent(context, YomuActivity::class.java)
                .setAction(ContinueWidgets.ACTION_OPEN_BOOK)
                .putExtra(ContinueWidgets.EXTRA_ID, bookId)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
    viewModel { ProfileViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { ProfileTrackingViewModel(get(), get()) }
}

/** One-time hints ("tap a word", "tap zones") remembered in the settings. */
class SettingsHintStore(private val settings: AppSettings) : HintStore {
    override suspend fun isShown(key: String): Boolean = key in settings.shownHints.get()
    override suspend fun markShown(key: String) = settings.shownHints.update { it + key }
}

/**
 * App lock state. The app is locked on a cold start and after it was in the background for longer than the
 * configured timeout (0 = any time it leaves the screen).
 */
class AppLockController(private val settings: AppSettings) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _locked = MutableStateFlow<Boolean?>(null)

    /** `null` until the settings are read. */
    val locked: StateFlow<Boolean?> = _locked.asStateFlow()
    @Volatile private var enabled = false
    @Volatile private var timeoutMs = 0L
    private var backgroundAt = 0L

    fun start() {
        scope.launch {
            combine(settings.appLock.flow, settings.pinHash.flow) { on, pin -> on && pin.isNotBlank() }.collect { on ->
                if (_locked.value == null) _locked.value = on
                if (!on) _locked.value = false
                enabled = on
            }
        }
        scope.launch { settings.lockTimeoutSeconds.flow.collect { timeoutMs = it * 1000L } }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        val away = SystemClock.elapsedRealtime() - backgroundAt
        if (enabled && backgroundAt > 0 && away >= maxOf(timeoutMs, MIN_AWAY_MS)) _locked.value = true
    }

    fun unlock() {
        _locked.value = false
    }

    private companion object {
        /** Ignore very short trips (permission dialogs, the biometric prompt itself). */
        const val MIN_AWAY_MS = 1_500L
    }
}

data class AppUiState(
    val theme: ThemeConfig,
    val haptics: Boolean,
    val reduceMotion: Boolean,
    val onboardingDone: Boolean,
    val secureScreen: Boolean,
    /** Names under every navigation icon (otherwise only under the selected one). */
    val navLabels: Boolean = false,
    /** Tabs of the navigation bar in the user's order; the first one opens at start. */
    val navTabs: List<NavTab> = NavTab.entries,
)

sealed interface MainEvent {
    data class OpenBook(val bookId: Long) : MainEvent
    data class Lookup(val text: String) : MainEvent
    data class Imported(val added: Int, val failed: Int, val error: Throwable?) : MainEvent
    /** "Add to Mihon/Tachiyomi" links of extension repositories (tachiyomi://add-repo?url=…, mihon://extension-store?url=…). */
    data class AddExtensionStore(val url: String) : MainEvent
    /** A tap on a download notification. */
    data object OpenDownloads : MainEvent
    /** An online chapter to read (the "Continue reading" widget). */
    data class OpenChapter(val chapterId: Long) : MainEvent
    data class OpenTitle(val entryId: Long) : MainEvent
}

/**
 * Global UI settings, handling of incoming intents ("Open with", "Share", "Look up"), "What's new", the report of a
 * crash of the previous run and the update check.
 */
class MainViewModel(
    val settings: AppSettings,
    private val importer: ImportService,
    private val updates: AppUpdates,
    private val history: ReadingHistoryRepository,
    private val audiobooks: AudiobookStore,
) : ViewModel() {
    val state: StateFlow<AppUiState?> = combine(
        combine(settings.themeMode.flow, settings.dynamicColor.flow, settings.amoled.flow, settings.accent.flow, settings.accentCustom.flow) { mode, dynamic, amoled, accent, custom ->
            ThemeConfig(
                mode = runCatching { ThemeMode.valueOf(mode) }.getOrDefault(ThemeMode.DARK),
                dynamicColor = dynamic,
                amoled = amoled,
                accent = runCatching { Accent.valueOf(accent) }.getOrDefault(Accent.YOMU),
                customColor = custom,
            )
        },
        combine(settings.haptics.flow, settings.reduceMotion.flow) { haptics, reduce -> haptics to reduce },
        combine(settings.navLabels.flow, settings.navTabOrder.flow, settings.navTabsHidden.flow) { labels, order, hidden ->
            labels to NavTab.visible(order, hidden)
        },
        settings.onboardingDone.flow, settings.secureScreen.flow,
    ) { theme, (haptics, reduce), (labels, tabs), onboarding, secure -> AppUiState(theme, haptics, reduce, onboarding, secure, labels, tabs) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _events = Channel<MainEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _whatsNew = MutableStateFlow<List<WhatsNew>>(emptyList())

    /** Releases for the "What's new" sheet, once after an update; empty the rest of the time. */
    val whatsNew: StateFlow<List<WhatsNew>> = _whatsNew.asStateFlow()

    private val _crash = MutableStateFlow<CrashReport?>(null)

    /**
     * The report of a crash of this version that the user has not seen yet (1.9.0): the app offers to send it once,
     * after the restart. Reports stay on the device unless the user shares one.
     */
    val crash: StateFlow<CrashReport?> = _crash.asStateFlow()

    /** Version of a newer release found by a check: a dot on the Settings tab. */
    val updateAvailable: StateFlow<String?> = updates.available.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Incognito mode: a strip above every screen says it is on. */
    val incognito: StateFlow<Boolean> = settings.incognito.flow.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setIncognito(on: Boolean) = viewModelScope.launch(ScreenErrors) { settings.incognito.set(on) }

    val hideCovers: StateFlow<Boolean> = settings.hideCovers.flow.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch(ScreenErrors) {
            val code = BuildConfig.VERSION_CODE.toLong()
            val seen = settings.whatsNewSeen.get()
            if (seen >= code) return@launch
            // A new installation: everything is new, onboarding tells about it.
            val releases = if (settings.onboardingDone.get()) whatsNewSince(seen, code) else emptyList()
            if (releases.isEmpty()) settings.whatsNewSeen.set(code) else _whatsNew.value = releases
        }
        viewModelScope.launch(ScreenErrors) {
            val report = withContext(Dispatchers.IO) { ErrorLog.lastCrash(BuildConfig.VERSION_NAME) } ?: return@launch
            if (report.time > settings.crashSeenAt.get()) _crash.value = report
        }
        viewModelScope.launch(ScreenErrors) {
            // Off by default; when on, it waits until the first screen has settled.
            delay(UPDATE_CHECK_DELAY_MS)
            updates.checkIfDue()
        }
    }

    fun dismissWhatsNew() {
        _whatsNew.value = emptyList()
        viewModelScope.launch(ScreenErrors) { settings.whatsNewSeen.set(BuildConfig.VERSION_CODE.toLong()) }
    }

    /** The crash report was sent, copied or closed: it is not offered again. */
    fun dismissCrash() {
        val report = _crash.value ?: return
        _crash.value = null
        viewModelScope.launch(ScreenErrors) { settings.crashSeenAt.set(report.time) }
    }

    fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { data ->
                val store = storeLink(data)
                when {
                    store != null -> _events.trySend(MainEvent.AddExtensionStore(store))
                    data.scheme == "tachiyomi" || data.scheme == "mihon" -> Unit
                    else -> importAndOpen(listOf(data))
                }
            }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                when {
                    stream != null -> importAndOpen(listOf(stream))
                    // A shared link (YouTube, a podcast, an audio file) becomes an audiobook; other text is looked up.
                    text.contains("http://") || text.contains("https://") -> addAudioLink(text)
                    text.isNotEmpty() -> _events.trySend(MainEvent.Lookup(text.take(MAX_LOOKUP)))
                }
            }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.takeIf { it.isNotEmpty() }?.let { importAndOpen(it) }
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { _events.trySend(MainEvent.Lookup(it.take(MAX_LOOKUP))) }
            Notifications.ACTION_OPEN_DOWNLOADS -> _events.trySend(MainEvent.OpenDownloads)
            // Rows of the "Continue reading" widget.
            ContinueWidgets.ACTION_OPEN_BOOK -> intent.getLongExtra(ContinueWidgets.EXTRA_ID, -1).takeIf { it > 0 }
                ?.let { _events.trySend(MainEvent.OpenBook(it)) }
            ContinueWidgets.ACTION_OPEN_ONLINE -> intent.getLongExtra(ContinueWidgets.EXTRA_ID, -1).takeIf { it > 0 }?.let { openOnline(it) }
        }
    }

    /** An online title of the widget: the chapter to continue with, or the title page when it has no chapters yet. */
    private fun openOnline(itemId: Long) = viewModelScope.launch(ScreenErrors) {
        val chapter = withContext(Dispatchers.IO) { history.resumeChapter(itemId, null) }
        _events.send(if (chapter != null) MainEvent.OpenChapter(chapter) else MainEvent.OpenTitle(itemId))
    }

    private fun addAudioLink(text: String) = viewModelScope.launch(ScreenErrors) {
        _importing.value = true
        val outcome = runCatching { audiobooks.addLink(text) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) { _importing.value = false; throw it } }
            .getOrElse { ImportOutcome.Failed(text, it) }
        _importing.value = false
        when (outcome) {
            is ImportOutcome.Added -> _events.send(MainEvent.OpenBook(outcome.bookId))
            is ImportOutcome.Duplicate -> _events.send(MainEvent.OpenBook(outcome.bookId))
            is ImportOutcome.Failed -> _events.send(MainEvent.Imported(0, 1, outcome.error))
        }
    }

    private fun importAndOpen(uris: List<Uri>) = viewModelScope.launch(ScreenErrors) {
        _importing.value = true
        // Access to shared files is temporary, so they are always copied into the library.
        val results = runCatching { importer.import(uris, link = false) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) { _importing.value = false; throw it } }
            .getOrElse { e -> listOf(ImportOutcome.Failed("", e)) }
        _importing.value = false
        val opened = results.mapNotNull {
            when (it) {
                is ImportOutcome.Added -> it.bookId
                is ImportOutcome.Duplicate -> it.bookId
                is ImportOutcome.Failed -> null
            }
        }
        val failed = results.filterIsInstance<ImportOutcome.Failed>()
        if (uris.size == 1 && opened.size == 1) {
            _events.send(MainEvent.OpenBook(opened.first()))
        } else {
            _events.send(MainEvent.Imported(results.count { it is ImportOutcome.Added }, failed.size, failed.firstOrNull()?.error))
        }
        if (uris.size == 1 && failed.isNotEmpty()) _events.send(MainEvent.Imported(0, 1, failed.first().error))
    }

    fun finishOnboarding() = viewModelScope.launch(ScreenErrors) { settings.onboardingDone.set(true) }

    companion object {
        private const val MAX_LOOKUP = 200
        private const val UPDATE_CHECK_DELAY_MS = 5_000L

        /** The repository address of an extension-store deep link, or null. */
        fun storeLink(data: Uri): String? {
            val isStore = (data.scheme == "tachiyomi" && data.host == "add-repo") || (data.scheme == "mihon" && data.host == "extension-store")
            if (!isStore) return null
            return data.getQueryParameter("url")?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
    }
}
