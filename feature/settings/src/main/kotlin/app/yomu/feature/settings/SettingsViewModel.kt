package app.yomu.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.AppScope
import app.yomu.core.common.Files
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.runCatchingApp
import app.yomu.core.datastore.AppSettings
import app.yomu.core.datastore.Pref
import app.yomu.data.backup.BackupManager
import app.yomu.data.backup.MihonImporter
import app.yomu.data.backup.SyncManager
import app.yomu.data.backup.SyncProblemInfo
import app.yomu.data.source.ExtensionManager
import app.yomu.data.tracking.TrackerManager
import app.yomu.data.tracking.TrackSyncWorker
import app.yomu.domain.tracking.TrackService
import app.yomu.data.download.DownloadRepository
import app.yomu.data.download.StorageUsage
import app.yomu.data.work.LibraryScanWorker
import app.yomu.data.work.WorkScheduler
import app.yomu.data.work.Notifications
import app.yomu.data.work.NotifyKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface SettingsEvent {
    data class Message(val res: Int, val args: List<Any> = emptyList()) : SettingsEvent
    data class Failure(val error: Throwable) : SettingsEvent
    /** A restore finished; the app should restart to reload everything. */
    data object Restored : SettingsEvent
}

class SettingsViewModel(
    private val context: Context,
    val settings: AppSettings,
    private val backup: BackupManager,
    private val sync: SyncManager,
    private val scheduler: WorkScheduler,
    private val downloads: DownloadRepository,
    /** App updates (About, the card on the settings root); one instance for the app. */
    val updates: AppUpdates,
    private val mihon: MihonImporter,
    /** Accounts of MyAnimeList, AniList and Shikimori (Settings → Tracking). */
    val trackers: TrackerManager,
    private val extensions: ExtensionManager,
    private val appScope: AppScope = AppScope(),
) : ViewModel() {
    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    val busy = MutableStateFlow(false)
    val usage = MutableStateFlow<StorageUsage?>(null)
    val fonts = MutableStateFlow<List<File>>(emptyList())
    val logs = MutableStateFlow<List<File>>(emptyList())
    private val fontsDir get() = File(context.filesDir, "fonts").apply { mkdirs() }

    fun <T> set(pref: Pref<*, T>, value: T, after: (suspend () -> Unit)? = null) = viewModelScope.launch(ScreenErrors) {
        pref.set(value)
        after?.invoke()
    }

    fun <T> update(pref: Pref<*, T>, transform: (T) -> T) = viewModelScope.launch(ScreenErrors) { pref.update(transform) }

    fun rescheduleScan() = viewModelScope.launch(ScreenErrors) { scheduler.scheduleScan() }
    fun rescheduleUpdates() = viewModelScope.launch(ScreenErrors) { scheduler.scheduleUpdates() }
    fun rescheduleBackup() = viewModelScope.launch(ScreenErrors) { scheduler.scheduleBackup() }
    fun rescheduleSync() = viewModelScope.launch(ScreenErrors) { scheduler.rescheduleSyncAfterChange() }
    fun rescheduleReminder() = viewModelScope.launch(ScreenErrors) { scheduler.scheduleReminder() }

    private fun persist(uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    // Library folders

    fun addScanFolder(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        persist(uri, write = false)
        settings.scanFolders.update { it + uri.toString() }
        scheduler.scheduleScan()
        LibraryScanWorker.runNow(context)
        _events.send(SettingsEvent.Message(R.string.set_scan_started))
    }

    fun removeScanFolder(uri: String) = viewModelScope.launch(ScreenErrors) {
        settings.scanFolders.update { it - uri }
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun scanNow() {
        LibraryScanWorker.runNow(context)
        _events.trySend(SettingsEvent.Message(R.string.set_scan_started))
    }

    // Backup & sync

    fun suggestedBackupName(): String = backup.suggestedName()

    fun createBackup(uri: Uri, includeFiles: Boolean) = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp {
            appScope.detached {
                backup.createTo(uri, includeFiles)
                settings.lastBackupAt.set(System.currentTimeMillis())
            }
        }.onSuccess { _events.send(SettingsEvent.Message(R.string.set_backup_done)) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        busy.value = false
    }

    fun restoreBackup(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp {
            appScope.detached { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { backup.restore(it) } ?: error("Cannot read the file") } }
        }.onSuccess { r ->
            _events.send(SettingsEvent.Message(R.string.set_restore_done, listOf(r.rows, r.files)))
            _events.send(SettingsEvent.Restored)
        }.onFailure { _events.send(SettingsEvent.Failure(it)) }
        busy.value = false
    }

    fun setBackupFolder(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        persist(uri, write = true)
        settings.backupFolder.set(uri.toString())
        scheduler.scheduleBackup()
    }

    fun backupNowToFolder() = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp { appScope.detached { backup.autoBackup() } }
            .onSuccess { _events.send(SettingsEvent.Message(R.string.set_backup_done)) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        busy.value = false
    }

    fun setSyncFolder(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        persist(uri, write = true)
        settings.syncFolder.set(uri.toString())
        scheduler.scheduleSync()
    }

    fun syncNow() = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp { appScope.detached { sync.syncNow() } }
            .onSuccess { n -> _events.send(SettingsEvent.Message(R.string.set_sync_done, listOf(n))) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        busy.value = false
    }

    fun createGitHubRepository(name: String) = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp { appScope.detached { sync.createGitHubRepository(name).also { scheduler.scheduleSync() } } }
            .onSuccess { full -> _events.send(SettingsEvent.Message(R.string.set_github_created, listOf(full))) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        busy.value = false
    }

    /** Posts a sample notification through the same rules as the real ones (kinds, quiet hours, the recent list). */
    fun testNotification() {
        Notifications.notify(
            context, Notifications.ID_TEST,
            Notifications.builder(context, Notifications.TASKS).setContentTitle(context.getString(R.string.set_notify_test_title))
                .setContentText(context.getString(R.string.set_notify_test_text)).setAutoCancel(true),
            NotifyKind.TASKS,
        )
    }

    fun checkSync() = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp { appScope.detached { sync.check() } }
            .onSuccess { _events.send(SettingsEvent.Message(R.string.set_sync_check_ok)) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        busy.value = false
    }

    // Sync conflicts (the problems of the last failed sync)

    val syncProblems = MutableStateFlow<List<SyncProblemInfo>>(emptyList())

    fun loadSyncProblems() = viewModelScope.launch(ScreenErrors) { syncProblems.value = sync.problems() }

    fun dismissSyncProblem(id: String) = viewModelScope.launch(ScreenErrors) {
        sync.dismissProblem(id)
        loadSyncProblems()
    }

    fun clearSyncProblems() = viewModelScope.launch(ScreenErrors) {
        sync.clearProblems()
        loadSyncProblems()
    }

    /** "Keep my version": the changed local file becomes the book's new version and syncs. */
    fun adoptChangedBook(bookId: Long) = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp { appScope.detached { sync.adoptChangedBook(bookId) } }
            .onSuccess { _events.send(SettingsEvent.Message(R.string.set_sync_done, listOf(it))) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        loadSyncProblems()
        busy.value = false
    }

    /** "Take the other device's version": the local file is replaced by the adopted remote version. */
    fun takeRemoteBookVersion(bookId: Long) = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp { appScope.detached { sync.takeRemoteBookVersion(bookId) } }
            .onSuccess { _events.send(SettingsEvent.Message(R.string.set_sync_done, listOf(it))) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        loadSyncProblems()
        busy.value = false
    }

    /** Migrates a legacy image folder onto a stable identity so file sync can include it. */
    fun migrateLegacyFolder(folderId: Long) = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp { appScope.detached { sync.migrateLegacyFolder(folderId) } }
            .onSuccess { _events.send(SettingsEvent.Message(R.string.set_sync_done, listOf(it))) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        loadSyncProblems()
        busy.value = false
    }

    /** "Publish my version" of a folder whose page changes stopped the sync. */
    fun publishFolderVersion(folderId: Long) = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp { appScope.detached { sync.publishFolderVersion(folderId) } }
            .onSuccess { _events.send(SettingsEvent.Message(R.string.set_sync_done, listOf(it))) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        loadSyncProblems()
        busy.value = false
    }

    /** "Accept the other version" of a folder whose page changes stopped the sync. */
    fun acceptFolderVersion(folderId: Long) = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp { appScope.detached { sync.acceptFolderVersion(folderId) } }
            .onSuccess { _events.send(SettingsEvent.Message(R.string.set_sync_done, listOf(it))) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        loadSyncProblems()
        busy.value = false
    }

    // Mihon / Tachiyomi

    /** Result of the last import, shown in a dialog until closed. */
    val mihonResult = MutableStateFlow<MihonImporter.Result?>(null)

    fun importMihon(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp {
            appScope.detached { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { mihon.import(it) } ?: error("Cannot read the file") } }
        }.onSuccess { mihonResult.value = it }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        busy.value = false
    }

    fun addStores(urls: List<String>) = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        var added = 0
        var failure: Throwable? = null
        urls.forEach { u -> runCatchingApp { extensions.addStore(u) }.onSuccess { added++ }.onFailure { failure = it } }
        val error = failure
        if (added == 0 && error != null) _events.send(SettingsEvent.Failure(error))
        else _events.send(SettingsEvent.Message(R.string.set_mihon_stores_added, listOf(added)))
        busy.value = false
    }

    fun checkLibraryUpdates() {
        app.yomu.data.work.UpdateWorker.runNow(context)
        _events.trySend(SettingsEvent.Message(R.string.set_mihon_updating))
    }

    // Tracking

    fun trackerPasswordLogin(service: TrackService, username: String, password: String) = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        try {
            runCatchingApp { trackers.tracker(service).loginWithPassword(username, password) }
                .onSuccess { TrackSyncWorker.schedule(context, restart = true); _events.send(SettingsEvent.Message(R.string.set_track_login_done)) }
                .onFailure { _events.send(SettingsEvent.Failure(it)) }
        } finally { busy.value = false }
    }

    fun trackerLogout(service: TrackService) = trackers.tracker(service).logout()

    fun setTrackerClient(service: TrackService, id: String, secret: String?) = trackers.store.setClient(service, id, secret)

    fun pushTracking() = viewModelScope.launch(ScreenErrors) {
        busy.value = true
        runCatchingApp { trackers.pushPending() }
            .onSuccess { left -> _events.send(SettingsEvent.Message(if (left == 0) R.string.set_track_pushed else R.string.set_track_push_left, listOf(left))) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        busy.value = false
    }

    fun resetAll() = viewModelScope.launch(ScreenErrors) {
        settings.resetAll()
        _events.send(SettingsEvent.Message(R.string.set_reset_done))
    }

    // Storage

    fun refreshUsage() = viewModelScope.launch(ScreenErrors) { usage.value = withContext(Dispatchers.IO) { downloads.storageUsage() } }

    fun clearCache() = viewModelScope.launch(ScreenErrors) {
        withContext(Dispatchers.IO) { downloads.clearCache() }
        refreshUsage()
        _events.send(SettingsEvent.Message(R.string.set_cache_cleared))
    }

    // Fonts

    fun loadFonts() { fonts.value = fontsDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty() }

    fun importFont(uri: Uri) = viewModelScope.launch(ScreenErrors) {
        runCatchingApp {
            withContext(Dispatchers.IO) {
                val name = Files.sanitize(Files.displayName(context, uri) ?: "font.ttf")
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("ttf", "otf", "woff", "woff2")) error("Not a font file: $name")
                Files.copy(context, uri, File(fontsDir, name))
            }
        }.onSuccess { _events.send(SettingsEvent.Message(R.string.set_font_added)) }
            .onFailure { _events.send(SettingsEvent.Failure(it)) }
        loadFonts()
    }

    fun deleteFont(file: File) = viewModelScope.launch(ScreenErrors) {
        withContext(Dispatchers.IO) { file.delete() }
        if (settings.textSettings.get().fontFamily == file.name) settings.textSettings.update { it.copy(fontFamily = "serif") }
        loadFonts()
    }

    // Error log

    fun loadLogs() { logs.value = app.yomu.core.common.ErrorLog.entries() }

    fun clearLogs() {
        app.yomu.core.common.ErrorLog.clear()
        loadLogs()
    }

    suspend fun readLog(file: File): String = withContext(Dispatchers.IO) { runCatching { file.readText().take(200_000) }.getOrDefault("") }
}
