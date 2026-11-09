package app.yomu.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import app.yomu.compat.tachiyomi.TachiyomiCompat
import app.yomu.core.database.ExtensionRepoEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.data.source.tachiyomi.ExtensionClassLoader
import app.yomu.data.source.tachiyomi.TachiyomiSource
import app.yomu.source.CatalogSource
import app.yomu.source.EXTENSION_API_VERSION
import app.yomu.source.Source
import app.yomu.source.SourceFactory
import app.yomu.source.await
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import eu.kanade.tachiyomi.source.CatalogueSource as TCatalogueSource
import eu.kanade.tachiyomi.source.Source as TSource
import eu.kanade.tachiyomi.source.SourceFactory as TSourceFactory

enum class ExtensionFormat { YOMU, TACHIYOMI }

enum class ExtensionStatus { LOADED, UNTRUSTED, BROKEN }

/** An extension package found on the device (installed system-wide or kept privately by Yomu). */
data class InstalledExtension(
    val pkg: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val format: ExtensionFormat,
    val libVersion: Double?,
    val lang: String,
    val contentWarning: ContentWarning,
    val signatures: List<String>,
    val isPrivate: Boolean,
    val isBundled: Boolean,
    val status: ExtensionStatus,
    val brokenReason: BrokenReason? = null,
    val error: String? = null,
    val sources: List<CatalogSource> = emptyList(),
    val storeName: String? = null,
    val icon: Drawable? = null,
    internal val apkPath: String? = null,
) {
    /** The signature shown to the user and remembered when they trust the extension. */
    val signatureHash: String get() = signatures.lastOrNull() ?: "unsigned"
    val isNsfw: Boolean get() = contentWarning == ContentWarning.NSFW
}

enum class BrokenReason { UNSUPPORTED_LIB, NEWER_API, UNSIGNED, MALFORMED, FAILED }

data class ExtensionUpdate(val installed: InstalledExtension, val available: AvailableExtension)

/** A configured store as the UI shows it. */
data class StoreState(
    val url: String,
    val name: String,
    val badge: String?,
    val signingKey: String?,
    val website: String?,
    val addedAt: Long,
    val extensionCount: Int?,
    val error: String?,
)

/** Why an install or store operation was refused; the UI turns the reason into a message. */
class ExtensionException(val reason: Reason, detail: String? = null, cause: Throwable? = null) :
    Exception(detail ?: reason.name, cause) {
    enum class Reason {
        NOT_AN_APK, NOT_AN_EXTENSION, PACKAGE_MISMATCH, UNSIGNED, SIGNATURE_MISMATCH, DOWNGRADE, UNSUPPORTED_LIB,
        STORE_EXISTS, STORE_SAME_KEY, STORE_INVALID, STORE_KEY_CHANGED, DOWNLOAD_FAILED,
    }
}

/**
 * Finds, installs, trusts and loads catalog extensions.
 *
 * Two formats are understood:
 * - Yomu extensions (meta-data `yomu.extension.class`, Yomu source API),
 * - Tachiyomi/Mihon extensions (extensions-lib 1.4 / 1.6, feature `tachiyomi.extension`): they run on the
 *   compatibility layer of `:compat:tachiyomi` and appear as ordinary Yomu sources through [TachiyomiSource].
 *
 * Extensions are either installed on the system (shared) or downloaded from a store into private storage and loaded
 * from there, without the system installer. Code from an unknown signer is never loaded until the user trusts it;
 * extensions signed with the key of a configured store are trusted automatically, like in Mihon.
 */
class ExtensionManager(
    private val context: Context,
    private val db: YomuDatabase,
    private val settings: AppSettings,
    private val client: () -> OkHttpClient,
) {
    private val storesApi = ExtensionStores(client)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _installed = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val installed: StateFlow<List<InstalledExtension>> = _installed.asStateFlow()

    private val _loaded = MutableStateFlow<List<InstalledExtension>>(emptyList())
    /** Extensions whose sources are registered in the app. */
    val loaded: StateFlow<List<InstalledExtension>> = _loaded.asStateFlow()

    private val _available = MutableStateFlow<List<AvailableExtension>>(emptyList())
    val available: StateFlow<List<AvailableExtension>> = _available.asStateFlow()

    private val _stores = MutableStateFlow<List<StoreState>>(emptyList())
    val stores: StateFlow<List<StoreState>> = _stores.asStateFlow()

    private val _updates = MutableStateFlow<List<ExtensionUpdate>>(emptyList())
    val updates: StateFlow<List<ExtensionUpdate>> = _updates.asStateFlow()

    /** Package → download progress 0..1 (null while the size is unknown). */
    private val _installing = MutableStateFlow<Map<String, Float?>>(emptyMap())
    val installing: StateFlow<Map<String, Float?>> = _installing.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    @Volatile
    var lastStoreRefreshAt: Long = 0
        private set

    private val storeCache = mutableMapOf<String, Pair<StoreInfo, List<AvailableExtension>>>()
    private val storeErrors = mutableMapOf<String, String>()
    private var bundledChecked = false

    private val privateDir: File get() = File(context.filesDir, "extensions").also { it.mkdirs() }

    // region loading

    /** Scans system and private extensions and (re)loads the trusted ones. */
    suspend fun reload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!bundledChecked) {
                bundledChecked = true
                runCatching { installBundled() }.onFailure { Timber.w(it, "Bundled extensions") }
            }
            val pm = context.packageManager
            val trusted = settings.trustedExtensions.get()
            val storeKeys = db.sources().repos().mapNotNull { it.signingKey?.lowercase() }.toSet()
            val bundledPkgs = bundledIndex().map { it.pkg }.toSet()
            val previous = _installed.value.associateBy { it.pkg }

            val shared = runCatching { pm.getInstalledPackages(PACKAGE_FLAGS) }.getOrDefault(emptyList())
                .filter { formatOf(it) != null }
                .associateBy { it.packageName }
            val private = privateFiles().mapNotNull { f ->
                archiveInfo(pm, f)?.takeIf { formatOf(it) != null }?.let { it to f }
            }.associateBy { it.first.packageName }

            val out = (shared.keys + private.keys).mapNotNull { pkg ->
                val s = shared[pkg]
                val p = private[pkg]
                // Like Mihon: when both exist, the newer one wins.
                val (info, file) = when {
                    s != null && p != null -> if (versionCodeOf(s) >= versionCodeOf(p.first)) s to null else p
                    s != null -> s to null
                    else -> p!!
                }
                runCatching { inspect(pm, info, file, trusted, storeKeys, pkg in bundledPkgs, previous[pkg]) }
                    .onFailure { Timber.e(it, "Extension $pkg") }
                    .getOrNull()
            }.sortedBy { it.name.lowercase() }
            _installed.value = out
            _loaded.value = out.filter { it.status == ExtensionStatus.LOADED }
            computeUpdates()
        }
    }

    private fun inspect(
        pm: PackageManager,
        info: PackageInfo,
        file: File?,
        trusted: Set<String>,
        storeKeys: Set<String>,
        bundled: Boolean,
        previous: InstalledExtension?,
    ): InstalledExtension {
        val app = info.applicationInfo
        val meta = app?.metaData
        val format = formatOf(info)!!
        val label = app?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: info.packageName
        val name = when (format) {
            ExtensionFormat.TACHIYOMI -> meta?.getString(T_META_NAME) ?: label.substringAfter("Tachiyomi: ")
            ExtensionFormat.YOMU -> label.removePrefix("Yomu: ")
        }
        val versionCode = versionCodeOf(info)
        val signatures = signaturesOf(info)
        val warning = contentWarningOf(format, meta)
        val libVersion = if (format == ExtensionFormat.TACHIYOMI) libVersionOf(info) else null
        val store = storeCache.values.map { it.first }.firstOrNull { st -> st.signingKey != null && st.signingKey in signatures }
        val icon = app?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
        val base = InstalledExtension(
            pkg = info.packageName, name = name, versionName = info.versionName.orEmpty(), versionCode = versionCode,
            format = format, libVersion = libVersion, lang = "", contentWarning = warning, signatures = signatures,
            isPrivate = file != null, isBundled = bundled, status = ExtensionStatus.BROKEN, storeName = store?.name,
            icon = icon, apkPath = file?.path ?: app?.sourceDir,
        )
        fun broken(reason: BrokenReason, error: String? = null) = base.copy(status = ExtensionStatus.BROKEN, brokenReason = reason, error = error)

        if (app == null || meta == null || info.versionName.isNullOrEmpty()) return broken(BrokenReason.MALFORMED)
        if (format == ExtensionFormat.TACHIYOMI && (libVersion == null || !TachiyomiCompat.isLibVersionSupported(libVersion))) {
            return broken(BrokenReason.UNSUPPORTED_LIB, libVersion?.toString())
        }
        if (format == ExtensionFormat.YOMU && meta.getInt(Y_META_API, 1) > EXTENSION_API_VERSION) return broken(BrokenReason.NEWER_API)
        if (signatures.isEmpty()) return broken(BrokenReason.UNSIGNED)
        val isTrusted = signatures.any { it in storeKeys } || signatures.any { "${info.packageName}:$it" in trusted }
        if (!isTrusted) return base.copy(status = ExtensionStatus.UNTRUSTED)

        // Nothing changed since the last scan: keep the already created sources (and their HTTP clients).
        if (previous != null && previous.status == ExtensionStatus.LOADED && previous.versionCode == versionCode &&
            previous.isPrivate == base.isPrivate && previous.apkPath == base.apkPath
        ) {
            return previous.copy(storeName = store?.name, isBundled = bundled)
        }

        val path = file?.path ?: app.sourceDir
        val sources = try {
            when (format) {
                ExtensionFormat.TACHIYOMI -> loadTachiyomi(info, meta.getString(T_META_CLASS), path)
                ExtensionFormat.YOMU -> loadYomu(info, meta.getString(Y_META_CLASS), path, app)
            }
        } catch (t: Throwable) {
            Timber.e(t, "Extension ${info.packageName} failed to load")
            return broken(BrokenReason.FAILED, generateSequence(t) { it.cause }.last().let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" })
        }
        val langs = sources.map { it.lang }.toSet()
        return base.copy(
            status = ExtensionStatus.LOADED,
            sources = sources,
            lang = when (langs.size) { 0 -> ""; 1 -> langs.first(); else -> "all" },
        )
    }

    private fun classNames(info: PackageInfo, value: String?): List<String> =
        value.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) info.packageName + it else it }
            .ifEmpty { throw IllegalStateException("Missing source class") }

    private fun loadTachiyomi(info: PackageInfo, classes: String?, path: String): List<CatalogSource> {
        check(TachiyomiCompat.isInitialized) { "Tachiyomi compatibility layer is not initialized" }
        val loader = ExtensionClassLoader(path, context.classLoader)
        return classNames(info, classes).flatMap { cls ->
            when (val obj = Class.forName(cls, false, loader).getDeclaredConstructor().newInstance()) {
                is TSourceFactory -> obj.createSources()
                is TSource -> listOf(obj)
                else -> throw IllegalStateException("Unknown source class type: ${obj.javaClass.name}")
            }
        }.filterIsInstance<TCatalogueSource>().map { TachiyomiSource(it, info.packageName) }
    }

    private fun loadYomu(info: PackageInfo, classes: String?, path: String, app: ApplicationInfo): List<CatalogSource> {
        val loader = PathClassLoader(path, app.nativeLibraryDir, context.classLoader)
        return classNames(info, classes).flatMap { cls ->
            when (val obj = Class.forName(cls, false, loader).getDeclaredConstructor().newInstance()) {
                is SourceFactory -> obj.createSources()
                is Source -> listOf(obj)
                else -> emptyList()
            }
        }.filterIsInstance<CatalogSource>()
    }

    private fun privateFiles(): List<File> =
        privateDir.listFiles { f -> f.isFile && (f.extension == PRIVATE_EXT || f.extension == "apk") }.orEmpty().toList()

    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? =
        runCatching { pm.getPackageArchiveInfo(file.path, PACKAGE_FLAGS) }.getOrNull()?.also {
            it.applicationInfo?.sourceDir = file.path
            it.applicationInfo?.publicSourceDir = file.path
        }

    // endregion

    // region trust

    /** Trusts the signing key of [ext] for this package and loads it. */
    suspend fun trust(ext: InstalledExtension) = trust(listOf(ext))

    /** Trusts the signing keys of [exts] for their packages and loads them with a single rescan. */
    suspend fun trust(exts: List<InstalledExtension>) {
        if (exts.isEmpty()) return
        val pkgs = exts.map { it.pkg }.toSet()
        settings.trustedExtensions.update { set ->
            set.filterNot { it.substringBeforeLast(':') in pkgs }.toSet() + exts.map { "${it.pkg}:${it.signatureHash}" }
        }
        reload()
    }

    // endregion

    // region stores

    /** Downloads a store index without adding it, for the confirmation dialog. */
    suspend fun preview(input: String): Pair<StoreInfo, List<AvailableExtension>> = withContext(Dispatchers.IO) {
        val url = storesApi.normalize(input)
        try {
            storesApi.fetch(url)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw ExtensionException(ExtensionException.Reason.STORE_INVALID, e.message, e)
        }
    }

    suspend fun addStore(input: String): StoreInfo = withContext(Dispatchers.IO) {
        val (info, list) = preview(input)
        val existing = db.sources().repos()
        if (existing.any { it.url == info.url || it.url == info.indexUrl }) throw ExtensionException(ExtensionException.Reason.STORE_EXISTS)
        if (info.signingKey != null && existing.any { it.signingKey.equals(info.signingKey, ignoreCase = true) }) {
            throw ExtensionException(ExtensionException.Reason.STORE_SAME_KEY, existing.first { it.signingKey.equals(info.signingKey, true) }.name)
        }
        db.sources().upsertRepo(ExtensionRepoEntity(info.url, info.name, System.currentTimeMillis(), info.signingKey, info.website, info.badge))
        storeCache[info.url] = info to list
        storeErrors.remove(info.url)
        publishStores()
        reload()
        info
    }

    suspend fun removeStore(url: String) {
        db.sources().deleteRepo(url)
        storeCache.remove(url)
        storeErrors.remove(url)
        publishStores()
        reload()
    }

    /** Fetches every store index (in parallel would be nicer; stores are few and small). */
    suspend fun refreshStores() = withContext(Dispatchers.IO) {
        _refreshing.value = true
        try {
            val repos = db.sources().repos()
            storeCache.keys.retainAll(repos.map { it.url }.toSet())
            repos.forEach { repo ->
                runCatching { storesApi.fetch(repo.url) }
                    .onSuccess { (info, list) ->
                        val pinned = repo.signingKey?.lowercase()
                        if (pinned != null && info.signingKey != null && info.signingKey != pinned) {
                            storeErrors[repo.url] = "KEY_CHANGED"
                            storeCache.remove(repo.url)
                        } else {
                            storeErrors.remove(repo.url)
                            storeCache[repo.url] = info to list
                            if (pinned == null && info.signingKey != null || repo.name != info.name) {
                                db.sources().upsertRepo(repo.copy(name = info.name, signingKey = pinned ?: info.signingKey, website = info.website, badge = info.badge))
                            }
                        }
                    }
                    .onFailure { e ->
                        Timber.w(e, "Store ${repo.url}")
                        storeErrors[repo.url] = e.message ?: e.javaClass.simpleName
                    }
            }
            lastStoreRefreshAt = System.currentTimeMillis()
            publishStores()
            // Store keys may have changed what is trusted.
            reload()
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun publishStores() {
        val repos = db.sources().repos().sortedBy { it.addedAt }
        _stores.value = repos.map { r ->
            val cached = storeCache[r.url]
            StoreState(
                url = r.url, name = cached?.first?.name ?: r.name, badge = cached?.first?.badge ?: r.badge,
                signingKey = r.signingKey, website = cached?.first?.website ?: r.website, addedAt = r.addedAt,
                extensionCount = cached?.second?.size, error = storeErrors[r.url],
            )
        }
        _available.value = repos.flatMap { storeCache[it.url]?.second.orEmpty() }
        computeUpdates()
    }

    private fun computeUpdates() {
        val byPkg = _available.value.groupBy { it.pkg }
        val keys = storeCache.values.associate { it.first.url to it.first.signingKey }
        _updates.value = _installed.value.mapNotNull { inst ->
            val candidates = byPkg[inst.pkg].orEmpty().filter { it.versionCode > inst.versionCode && it.isSupported }
            // Only offer versions from a store whose key signed the installed one (or any store when it has no key).
            val best = candidates.filter { a -> keys[a.storeUrl]?.let { it in inst.signatures } ?: true }.maxByOrNull { it.versionCode }
            best?.let { ExtensionUpdate(inst, it) }
        }
    }

    // endregion

    // region install

    /** Downloads [ext] from its store and keeps it privately (no system installer needed). */
    suspend fun install(ext: AvailableExtension) = withContext(Dispatchers.IO) {
        if (!ext.isSupported) throw ExtensionException(ExtensionException.Reason.UNSUPPORTED_LIB, ext.libVersion?.toString())
        _installing.update { it + (ext.pkg to null) }
        val tmp = File(context.cacheDir, "ext-${ext.pkg}.tmp")
        try {
            download(ext.apkUrl, tmp) { p -> _installing.update { it + (ext.pkg to p) } }
            installFile(tmp, ext.pkg)
        } finally {
            tmp.delete()
            _installing.update { it - ext.pkg }
        }
    }

    /** Installs an extension APK chosen by the user; it stays untrusted until the user trusts its signer. */
    suspend fun installFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "ext-import-${System.nanoTime()}.tmp")
        try {
            (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            installFile(tmp, null)
        } finally {
            tmp.delete()
        }
    }

    private suspend fun installFile(tmp: File, expectedPkg: String?): String {
        val pm = context.packageManager
        val info = archiveInfo(pm, tmp) ?: throw ExtensionException(ExtensionException.Reason.NOT_AN_APK)
        if (formatOf(info) == null) throw ExtensionException(ExtensionException.Reason.NOT_AN_EXTENSION, info.packageName)
        if (expectedPkg != null && info.packageName != expectedPkg) throw ExtensionException(ExtensionException.Reason.PACKAGE_MISMATCH, info.packageName)
        val sigs = signaturesOf(info)
        if (sigs.isEmpty()) throw ExtensionException(ExtensionException.Reason.UNSIGNED)
        val dest = File(privateDir, "${info.packageName}.$PRIVATE_EXT")
        val current = listOf(dest, File(privateDir, "${info.packageName}.apk")).firstOrNull { it.exists() }?.let { archiveInfo(pm, it) }
        if (current != null) {
            if (signaturesOf(current).none { it in sigs }) throw ExtensionException(ExtensionException.Reason.SIGNATURE_MISMATCH, info.packageName)
            if (versionCodeOf(info) < versionCodeOf(current)) throw ExtensionException(ExtensionException.Reason.DOWNGRADE, info.versionName)
        }
        writeReadOnly(tmp, dest)
        File(privateDir, "${info.packageName}.apk").takeIf { it.exists() }?.let { it.setWritable(true); it.delete() }
        settings.removedBundledExtensions.update { it - info.packageName }
        reload()
        return info.packageName
    }

    /** Android 14+ refuses to load writable dex files, so private extensions are made read-only. */
    private fun writeReadOnly(src: File, dest: File) {
        val part = File(dest.parentFile, dest.name + ".part")
        part.delete()
        src.copyTo(part, overwrite = true)
        if (dest.exists()) dest.setWritable(true)
        // An atomic replace: a kill in between leaves the old extension, never none.
        try {
            java.nio.file.Files.move(part.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
        }
        dest.setReadOnly()
    }

    /** Removes a privately kept extension. Returns false for system-installed ones (the caller opens the system uninstaller). */
    suspend fun uninstall(ext: InstalledExtension): Boolean {
        if (!ext.isPrivate) return false
        withContext(Dispatchers.IO) {
            listOf(File(privateDir, "${ext.pkg}.$PRIVATE_EXT"), File(privateDir, "${ext.pkg}.apk")).forEach { f ->
                if (f.exists()) { f.setWritable(true); f.delete() }
            }
        }
        if (ext.isBundled) settings.removedBundledExtensions.update { it + ext.pkg }
        reload()
        return true
    }

    private suspend fun download(url: String, dest: File, progress: (Float?) -> Unit) {
        val response = try {
            client().newCall(Request.Builder().url(url).build()).await()
        } catch (e: IOException) {
            throw ExtensionException(ExtensionException.Reason.DOWNLOAD_FAILED, e.message, e)
        }
        response.use { r ->
            if (!r.isSuccessful) throw ExtensionException(ExtensionException.Reason.DOWNLOAD_FAILED, "HTTP ${r.code}")
            val total = r.body.contentLength().takeIf { it > 0 }
            var done = 0L
            var lastReport = 0L
            dest.outputStream().use { out ->
                r.body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null && done - lastReport > total / 50) {
                            lastReport = done
                            progress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
    }

    // endregion

    // region bundled extensions

    @Serializable
    private data class BundledExtension(val pkg: String, val file: String, val versionCode: Long, val signingKey: String)

    private val bundledIndexCache by lazy {
        runCatching {
            context.assets.open("$BUNDLED_DIR/index.json").use { json.decodeFromString<List<BundledExtension>>(it.readBytes().decodeToString()) }
        }.getOrDefault(emptyList())
    }

    private fun bundledIndex(): List<BundledExtension> = bundledIndexCache

    /**
     * Copies the extensions shipped inside the app to private storage the first time (and when the app brings a newer
     * build). They are trusted for their package and signer only; a store with the same key gives updates later.
     */
    private suspend fun installBundled() {
        val pm = context.packageManager
        val removed = settings.removedBundledExtensions.get()
        bundledIndex().forEach { b ->
            if (b.pkg in removed) return@forEach
            val dest = File(privateDir, "${b.pkg}.$PRIVATE_EXT")
            val current = dest.takeIf { it.exists() }?.let { archiveInfo(pm, it) }
            if (current != null && versionCodeOf(current) >= b.versionCode) return@forEach
            val tmp = File(context.cacheDir, "bundled-${b.pkg}.tmp")
            try {
                context.assets.open("$BUNDLED_DIR/${b.file}").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val info = archiveInfo(pm, tmp) ?: return@forEach
                val sigs = signaturesOf(info)
                if (info.packageName != b.pkg || b.signingKey.lowercase() !in sigs) {
                    Timber.w("Bundled extension ${b.file} does not match its index entry")
                    return@forEach
                }
                if (current != null && signaturesOf(current).none { it in sigs }) return@forEach
                writeReadOnly(tmp, dest)
                settings.trustedExtensions.update { it + "${b.pkg}:${b.signingKey.lowercase()}" }
            } finally {
                tmp.delete()
            }
        }
    }

    // endregion

    companion object {
        const val Y_META_CLASS = "yomu.extension.class"
        const val Y_META_API = "yomu.extension.api"
        const val Y_META_NSFW = "yomu.extension.nsfw"

        const val T_FEATURE = "tachiyomi.extension"
        const val T_META_CLASS = "tachiyomi.extension.class"
        const val T_META_NSFW = "tachiyomi.extension.nsfw"
        const val T_META_NAME = "tachiyomix.name"
        const val T_META_LIB = "tachiyomix.extensionLib"
        const val T_META_CONTENT_WARNING = "tachiyomix.contentWarning"

        private const val PRIVATE_EXT = "ext"
        private const val BUNDLED_DIR = "extensions"

        @Suppress("DEPRECATION")
        private val PACKAGE_FLAGS = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS or
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

        internal fun formatOf(info: PackageInfo): ExtensionFormat? {
            val meta = info.applicationInfo?.metaData
            return when {
                meta?.containsKey(Y_META_CLASS) == true -> ExtensionFormat.YOMU
                info.reqFeatures.orEmpty().any { it.name == T_FEATURE } || meta?.containsKey(T_META_CLASS) == true -> ExtensionFormat.TACHIYOMI
                else -> null
            }
        }

        private fun contentWarningOf(format: ExtensionFormat, meta: android.os.Bundle?): ContentWarning = when {
            meta == null -> ContentWarning.SAFE
            format == ExtensionFormat.YOMU -> if (meta.getInt(Y_META_NSFW, 0) == 1) ContentWarning.NSFW else ContentWarning.SAFE
            meta.containsKey(T_META_CONTENT_WARNING) -> when ((meta.get(T_META_CONTENT_WARNING) as? Number)?.toInt()) {
                1 -> ContentWarning.MIXED
                2 -> ContentWarning.NSFW
                else -> ContentWarning.SAFE
            }
            (meta.get(T_META_NSFW) as? Number)?.toInt() == 1 -> ContentWarning.NSFW
            else -> ContentWarning.SAFE
        }

        /** extensions-lib version: the explicit meta-data, else the first two parts of versionName ("1.4.12" → 1.4). */
        private fun libVersionOf(info: PackageInfo): Double? {
            val raw = info.applicationInfo?.metaData?.get(T_META_LIB)
            val explicit = when (raw) {
                is Number -> raw.toString().toDoubleOrNull()?.takeIf { it != 0.0 }
                is String -> raw.toDoubleOrNull()
                else -> null
            }
            return explicit ?: info.versionName?.substringBeforeLast('.')?.toDoubleOrNull()
        }

        @Suppress("DEPRECATION")
        internal fun versionCodeOf(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

        /** SHA-256 fingerprints of the signing certificates (lowercase hex), as stores publish them. */
        @SuppressLint("PackageManagerGetSignatures")
        @Suppress("DEPRECATION")
        internal fun signaturesOf(info: PackageInfo): List<String> {
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                val si = info.signingInfo ?: return emptyList()
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } else {
                info.signatures
            }
            return sigs.orEmpty().map { sha256(it.toByteArray()) }
        }

        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
