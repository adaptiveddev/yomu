package app.yomu.data.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.data.library.ImportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * Synchronisation through one shared file (`yomu-sync.json`) in a folder picked with the system file picker (a cloud
 * drive folder, for example) or on a WebDAV server. Books are matched by file hash, online titles by source and address.
 * See [SyncMerger] for how the data of the devices is merged, deletions included.
 */
class SyncManager(
    private val context: Context,
    private val db: YomuDatabase,
    private val settings: AppSettings,
    private val client: () -> OkHttpClient,
    private val importer: ImportService,
) {
    private val mutex = Mutex()

    /** Whether a sync target is set up (the folder or the WebDAV address). */
    suspend fun isConfigured(): Boolean = isConfigured(settings)

    suspend fun backend(): SyncBackend = when (settings.syncBackend.get()) {
        WEBDAV -> WebDavSyncBackend(
            client(),
            settings.webdavUrl.get().takeIf { it.isNotBlank() } ?: error("The WebDAV address is not set"),
            settings.webdavUser.get(),
            settings.webdavPassword.get(),
        )
        GITHUB -> GitHubSyncBackend(
            client(),
            settings.githubToken.get().takeIf { it.isNotBlank() } ?: error("The GitHub token is not set"),
            settings.githubRepo.get().takeIf { it.isNotBlank() } ?: error("The GitHub repository is not set"),
        )
        else -> FolderSyncBackend(context, settings.syncFolder.get().takeIf { it.isNotBlank() }?.let(Uri::parse) ?: error("Sync folder is not selected"))
    }

    /** Creates a private repository for the sync with the saved token and selects it. */
    suspend fun createGitHubRepository(name: String): String {
        val full = GitHubSyncBackend.createRepository(client(), settings.githubToken.get(), name)
        settings.githubRepo.set(full)
        return full
    }

    /** Checks that the target can be reached (for "Test connection"). */
    suspend fun check() = withContext(Dispatchers.IO) { backend().check() }

    /** Syncs now; returns the number of records changed on this device. */
    suspend fun syncNow(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val changed = cycle(backend())
                settings.lastSyncAt.set(System.currentTimeMillis())
                settings.lastSyncError.set("")
                SyncProblemStore(context).clear()
                changed
            } catch (e: Exception) {
                throw failed(e)
            }
        }
    }

    /**
     * Adopts the current local file of a changed book as its new version ("keep my version"): the
     * book keeps its progress, annotations and tracker bindings, other devices follow the
     * replacement marker. Runs a full sync afterwards.
     */
    suspend fun adoptChangedBook(bookId: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val backend = backend()
                adoption(backend).adopt(bookId)
                cycle(backend)
            } catch (e: Exception) {
                throw failed(e)
            }
        }
    }

    /** Replaces a locally changed book file with the version another device adopted. */
    suspend fun takeRemoteBookVersion(bookId: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val backend = backend()
                adoption(backend).follow(bookId, forced = true)
                cycle(backend)
            } catch (e: Exception) {
                throw failed(e)
            }
        }
    }

    /** Migrates a legacy (`dir:` path-based) image folder onto a stable identity and syncs. */
    suspend fun migrateLegacyFolder(bookId: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val backend = backend()
                FolderAdoption(context, db, backend, backend.id, ::persistProblemInfo).migrate(bookId)
                cycle(backend)
            } catch (e: Exception) {
                throw failed(e)
            }
        }
    }

    /** "Publish my version" of a folder whose page changes stopped the sync, then syncs. */
    suspend fun publishFolderVersion(bookId: Long): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val backend = backend()
                val folders = backend as? FolderFilesTransport
                    ?: throw IOException("The sync target does not transfer image folders")
                val report = FolderSyncCoordinator(context, db, importer, ::persistProblemInfo)
                    .publishLocalVersion(bookId, folders, backend.id)
                cycle(backend)
                report
            } catch (e: Exception) {
                throw failed(e)
            }
        }
    }

    /** "Accept the other version" of a folder whose page changes stopped the sync, then syncs. */
    suspend fun acceptFolderVersion(bookId: Long): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val backend = backend()
                val folders = backend as? FolderFilesTransport
                    ?: throw IOException("The sync target does not transfer image folders")
                val report = FolderSyncCoordinator(context, db, importer, ::persistProblemInfo)
                    .acceptRemoteVersion(bookId, folders, backend.id)
                cycle(backend)
                report
            } catch (e: Exception) {
                throw failed(e)
            }
        }
    }

    private suspend fun failed(e: Exception): Throwable {
        if (e is kotlinx.coroutines.CancellationException) return e
        val displayed = when (e) {
            is FolderSyncProblem -> e.forDisplay(context)
            is AnnotationSyncProblem -> e.forDisplay(context)
            else -> e
        }
        recordProblem(e)
        settings.lastSyncError.set(displayed.message?.take(300) ?: displayed.javaClass.simpleName)
        return displayed
    }

    private suspend fun cycle(backend: SyncBackend): Int {
        var attempt = 0
        while (true) {
            try { return runOnce(backend) } catch (e: SyncConflictException) {
                // Another device wrote the file between our read and write: merge with its version.
                if (++attempt >= 3) throw e
            }
        }
    }

    private fun adoption(backend: SyncBackend) = BookAdoption(context, db, settings, backend, backend.id, ::persistProblemInfo)

    /** The problems of the last failed sync, for the conflicts screen. */
    suspend fun problems(): List<SyncProblemInfo> = withContext(Dispatchers.IO) { SyncProblemStore(context).load() }

    /** Forgets one recorded problem (the user dismissed it on the conflicts screen). */
    suspend fun dismissProblem(id: String) = withContext(Dispatchers.IO) {
        val store = SyncProblemStore(context)
        store.save(store.load().filterNot { it.id == id })
    }

    /** Forgets all recorded problems without resolving them. */
    suspend fun clearProblems() = withContext(Dispatchers.IO) { SyncProblemStore(context).clear() }

    private fun persistProblemInfo(info: SyncProblemInfo) {
        try {
            val store = SyncProblemStore(context)
            store.save(store.load().filterNot { it.id == info.id } + info)
        } catch (_: Exception) { }
    }

    private fun recordProblem(error: Exception) {
        val info = when (error) {
            is SyncProblem -> error.record(System.currentTimeMillis())
            // Plain failures (network, missing files, ...) are recorded too so the screen explains a stopped sync.
            else -> SyncProblemInfo(id = "error", kind = "error", subject = "", detail = error.message.orEmpty(), at = System.currentTimeMillis())
        }
        persistProblemInfo(info)
    }

    internal suspend fun runOnce(backend: SyncBackend): Int {
        val lease = FolderReadSyncGate.sync(db)
        try {
        // A partially written adoption must resume before anything else touches the documents;
        // syncing over it could mistake the half-migrated book for a different one.
        if (BookAdoption.loadJournal(context) != null || FolderAdoption.loadJournal(context) != null)
            throw IOException("An interrupted migration must be resumed before syncing; open Settings → Sync conflicts")
        val file = backend.read()
        val document = file?.let { decodeDocument(it.bytes) }
        val remote = document?.state
        val folderSnapshot = document?.folderSnapshot
        if (folderSnapshot == null && remote != null && FolderMetadataSnapshotCodec.folderHashes(remote).isNotEmpty())
            throw IOException("Folder metadata has no page revision; resolve the conflict before syncing")
        val base = loadBase(context)
        requireRemoteWhenKnown(file, base, backend.id)
        // File transfer is opt-in on each device. Shared-folder providers cannot guarantee atomic compare-and-swap.
        val fileSync = settings.syncBookFiles.get()
        if (fileSync && backend is FolderFilesTransport) {
            // Follow other devices' migration markers first: a legacy folder whose identity was
            // migrated elsewhere adopts the stable identity instead of blocking the file sync.
            val folderAdoption = FolderAdoption(context, db, backend, backend.id, ::persistProblemInfo)
            for (book in db.books().all().filter { it.format == "FOLDER" && it.fileHash?.let(FolderManifestCodec::validId) != true }) {
                try { folderAdoption.follow(book.id) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { if (e !is SyncProblem) recordProblem(e) }
            }
            FolderSyncCoordinator(context, db, importer).requirePortableFolderIdentities()
        }
        val filesResult = if (fileSync && backend is BookFilesTransport) {
            BookFileSync(context, db, importer, ::persistProblemInfo).syncDetailed(backend, backend.id)
        } else BookFileSync.SyncFilesResult(0, emptyList(), emptyList())
        val imported = filesResult.imported
        if (fileSync && backend is BookFilesTransport) {
            val adoption = BookAdoption(context, db, settings, backend, backend.id, ::persistProblemInfo)
            // Follow other devices' replacement markers (their index entry is the commit point),
            // then adopt self-modified PDF versions. Per-book failures do not stop the other books.
            for (id in filesResult.followBooks) {
                try { adoption.follow(id, forced = false) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { if (e !is SyncProblem) recordProblem(e) }
            }
            for (id in filesResult.changedBooks) {
                try { adoption.autoAdopt(id) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { if (e !is SyncProblem) recordProblem(e) }
            }
        }
        val folderResult = if (fileSync && backend is FolderFilesTransport) {
            FolderSyncCoordinator(context, db, importer, ::persistProblemInfo).sync(backend, backend.id, folderSnapshot)
        } else FolderSyncCoordinator.FolderSyncResult(0, emptySet())
        val importedFolders = folderResult.changed
        val deletion = if (fileSync && backend is BookFilesTransport) {
            BookDeletionSync(context, db, settings, backend, backend.id, ::persistProblemInfo)
        } else null
        if (deletion != null) {
            // Other devices' removals apply before the merge so deleted books stop contributing,
            // and this device's removals publish against the last agreed state (its own
            // tombstones, written by the merge below, must not count as a competing change).
            try { deletion.receive() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: IOException) { /* recorded inside; a refused deletion must not stop the sync */ }
            try { deletion.publish(remote) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: IOException) { /* recorded inside */ }
        }
        val options = SyncMerger.Options(
            catalog = settings.syncCatalog.get(), deletions = settings.syncDeletions.get(), device = deviceName(),
            progress = settings.syncProgress.get(), annotations = settings.syncAnnotations.get(),
            bookmarks = settings.syncBookmarks.get(), words = settings.syncWords.get(),
        )
        val stableFolders = db.books().all().filter { it.format == "FOLDER" && it.fileHash?.let(FolderManifestCodec::validId) == true }
        val outcome = if (folderSnapshot != null || stableFolders.isNotEmpty()) {
            val revisions = folderSnapshot?.revisions.orEmpty().associateByTo(LinkedHashMap()) { it.id }
            for (book in stableFolders) {
                val id = book.fileHash!!
                revisions[id] = FolderRevisionCommit.localRevision(db, id)
                    ?: throw IOException("Folder position revision is missing: ${book.title}")
            }
            mergeFolderMetadataAndWrite(context, db, backend, file, folderSnapshot, base, System.currentTimeMillis(), options, revisions.values.toList(),
                pendingEdits = folderResult.pendingEdits,
                legacyRemote = if (folderSnapshot == null) remote else null)
        } else mergeAndWrite(db, backend, file, remote, base, System.currentTimeMillis(), options)
        // Only after the file was written: the base describes what the shared file and this device agree on.
        saveBase(context, outcome.base)
        val trackChanges = if (backend is TrackLinkTransport && settings.syncTrackLinks.get()) {
            TrackLinkSync(context, db).sync(backend, backend.id, settings.syncCatalog.get(), settings.syncDeletions.get())
        } else 0
        if (deletion != null) {
            // Refresh the per-book deletion baselines from the merged state.
            try { deletion.captureBaselines(outcome.state) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: IOException) { /* the next sync captures them again */ }
        }
        return outcome.changed + imported + importedFolders + trackChanges
        } finally { lease.close() }
    }

    private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() && it != "unknown" }.distinct().joinToString(" ")

    companion object {
        const val FILE_NAME = "yomu-sync.json"
        const val FOLDER = "FOLDER"
        const val WEBDAV = "WEBDAV"
        const val GITHUB = "GITHUB"

        /** Whether the selected target is set up (the folder, the WebDAV address or the GitHub token and repository). */
        suspend fun isConfigured(settings: AppSettings): Boolean = when (settings.syncBackend.get()) {
            WEBDAV -> settings.webdavUrl.get().isNotBlank()
            GITHUB -> settings.githubToken.get().isNotBlank() && settings.githubRepo.get().isNotBlank()
            else -> settings.syncFolder.get().isNotBlank()
        }

        /** Network targets wait for a connection; a shared folder does not. */
        fun needsNetwork(backend: String): Boolean = backend == WEBDAV || backend == GITHUB

        internal data class SyncDocument(val state: SyncState, val folderSnapshot: FolderMetadataSnapshot? = null)

        internal fun decodeDocument(bytes: ByteArray): SyncDocument = try {
            val root = Json.parseToJsonElement(SyncJson.text(bytes)).jsonObject
            if (root["version"]?.jsonPrimitive?.intOrNull == 3) {
                val snapshot = FolderMetadataSnapshotCodec.decode(bytes)
                SyncDocument(snapshot.state, snapshot)
            } else SyncDocument(decodeRemote(bytes))
        } catch (e: Exception) { throw IOException("Cannot read the sync file; it was not overwritten", e) }

        internal fun decodeRemote(bytes: ByteArray): SyncState = try {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
            val root = json.parseToJsonElement(SyncJson.text(bytes)).jsonObject
            require(root.keys.any { it in setOf("version", "updatedAt", "books", "highlights", "notes", "bookmarks", "words", "titles", "tabs", "deleted") })
            json.decodeFromJsonElement(SyncState.serializer(), root).also(::validateState)
        } catch (e: Exception) {
            throw java.io.IOException("Cannot read the sync file; it was not overwritten", e)
        }

        internal fun validateState(state: SyncState) {
            require(state.version in 1..2) { "Unsupported sync file version: ${state.version}" }
            fun <T> unique(keys: List<T>) = require(keys.size == keys.toSet().size) { "Duplicate sync record" }
            unique(state.books.map { it.hash })
            unique(state.highlights.map { SyncKeys.highlight(it.hash, it.createdAt, it.text) })
            unique(state.notes.map { SyncKeys.note(it.hash, it.createdAt) })
            unique(state.bookmarks.map { SyncKeys.bookmark(it.hash, it.createdAt) })
            unique(state.words.map { SyncKeys.word(it.lang, it.normalized) })
            unique(state.titles.map { SyncKeys.title(it.sourceId, it.url) })
            unique(state.tabs.map { SyncKeys.tab(it.name) })
            require(state.annotationDeletionProofs.all { (key, proof) ->
                key in state.deleted && SyncKeys.bookOf(key) != null && proof.matches(Regex("[0-9a-f]{64}"))
            }) { "Invalid annotation deletion proof" }
        }

        internal fun requireRemoteWhenKnown(file: SyncFile?, base: SyncBase?, syncId: String) {
            if (file == null && base?.syncId == syncId)
                throw IOException("The sync file is missing; restore it before syncing to avoid data loss")
        }

        private val baseJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        internal fun loadBase(context: Context): SyncBase? {
            val atomic = AtomicFile(baseFile(context))
            val backup = File(atomic.baseFile.path + ".bak")
            val pending = File(atomic.baseFile.path + ".new")
            val legacyPending = File(atomic.baseFile.path + ".tmp")
            if (!atomic.baseFile.exists() && !backup.exists()) {
                if (pending.exists() || legacyPending.exists()) throw IOException("Incomplete sync base; no data was overwritten")
                return null
            }
            return try {
                baseJson.decodeFromString(SyncBase.serializer(), atomic.openRead().use { SyncJson.text(readSyncBytes(it, MAX_SYNC_FILE_BYTES)) }).also(::validateBase)
            } catch (e: Exception) { throw IOException("Cannot read the sync base; no data was overwritten", e) }
        }

        private fun validateBase(base: SyncBase) {
            val digest = Regex("[0-9a-f]{64}")
            require(base.annotationRevisions.all { (key, value) -> key in base.keys && SyncKeys.bookOf(key) != null && value.matches(digest) })
            require(base.privateAnnotationRevisions.all { (key, value) -> key in base.annotationRevisions && key.startsWith("h|") && value.matches(digest) })
            require(base.annotationDeletionProofs.all { (key, value) -> key in base.tombstones && SyncKeys.bookOf(key) != null && value.matches(digest) })
        }

        internal fun saveBase(context: Context, base: SyncBase) {
            validateBase(base)
            val f = baseFile(context)
            f.parentFile?.mkdirs()
            val bytes = baseJson.encodeToString(SyncBase.serializer(), base).toByteArray()
            if (bytes.size > MAX_SYNC_FILE_BYTES) throw IOException("Sync base is too large; it was not overwritten")
            AtomicPublish.publish(f, bytes)
        }

        private fun baseFile(context: Context) = File(context.filesDir, "sync/base.json")

        /**
         * Forgets what was synced (after a restore from a backup the data is not what the last sync left). The next sync
         * then deletes nothing because of records missing here, and it only adds.
         */
        fun clearBase(context: Context) {
            val f = baseFile(context)
            AtomicFile(f).delete()
            File(f.path + ".tmp").delete()
            // Tracker links have their own per-target merge bases. A restored database may have fewer links;
            // keeping the pre-restore bases would misinterpret those as intentional remote unlinks.
            File(context.filesDir, "sync-tracking").deleteRecursively()
            File(context.filesDir, "sync-books").deleteRecursively()
            // Recorded problems describe the pre-restore state; a restored device starts clean.
            SyncProblemStore(context).clear()
        }
    }
}
