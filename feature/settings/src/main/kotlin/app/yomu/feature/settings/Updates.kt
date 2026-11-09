package app.yomu.feature.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.yomu.core.datastore.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A release published on GitHub: its version (without the "v"), its page and the APK for this device, if any. */
data class ReleaseInfo(
    val version: String,
    val pageUrl: String,
    val apkUrl: String?,
    /** Size of [apkUrl] in bytes, 0 if unknown. */
    val apkSize: Long = 0,
    /** SHA256SUMS.txt of the release, if it has one. */
    val checksumsUrl: String? = null,
    /** Release notes as written on GitHub (Markdown). */
    val notes: String = "",
) {
    /** File name of the APK, the way SHA256SUMS.txt lists it. */
    val apkName: String? get() = apkUrl?.substringBefore('?')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    /** The APK is being downloaded; [progress] from 0 to 1, null while the size is unknown. */
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateState
    /** Downloaded and verified: the system installer takes it from here. */
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    /** The repository has no published release yet. */
    data object NoReleases : UpdateState
    /** The check or the download of [release] failed. */
    data class Failed(val error: Throwable, val release: ReleaseInfo? = null) : UpdateState
}

/** The downloaded APK does not match SHA256SUMS.txt of its release (or is not listed there): it is deleted, not installed. */
class UpdateChecksumException(file: String) : IOException("SHA-256 of $file does not match SHA256SUMS.txt")

/**
 * Asks GitHub for the newest release: one small anonymous request, when the user taps "Check for updates" or, if they
 * turned it on, once a day at start (see [AppUpdates]).
 */
object UpdateChecker {
    suspend fun latest(api: String = ProjectLinks.LATEST_RELEASE_API): ReleaseInfo? = withContext(Dispatchers.IO) {
        val c = (URL(api).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Yomu")
        }
        try {
            when (val code = c.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> null
                in 200..299 -> parse(c.inputStream.bufferedReader().use { it.readText() })
                else -> throw IOException("HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Reads a GitHub "release" object. The APK is the one built for the first ABI of this device that has one
     * ("yomu-v1.7.0-arm64-v8a.apk"), else a universal or the only one; several APKs for other ABIs give none, and the
     * release page opens instead of a file that would not install.
     */
    fun parse(json: String, abis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()): ReleaseInfo {
        val o = Json.parseToJsonElement(json).jsonObject
        val tag = o["tag_name"]?.jsonPrimitive?.content.orEmpty()
        val page = o["html_url"]?.jsonPrimitive?.content ?: ProjectLinks.RELEASES_URL
        val assets = o["assets"]?.jsonArray.orEmpty().mapNotNull { a ->
            val asset = a.jsonObject
            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            Asset(asset["name"]?.jsonPrimitive?.contentOrNull ?: url.substringAfterLast('/'), url, asset["size"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val apk = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.name.substringBeforeLast('.').endsWith("-$abi", ignoreCase = true) } }
            ?: apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apks.singleOrNull()
        return ReleaseInfo(
            version = tag.removePrefix("v"),
            pageUrl = page,
            apkUrl = apk?.url,
            apkSize = apk?.size ?: 0,
            checksumsUrl = assets.firstOrNull { it.name.equals(CHECKSUMS, ignoreCase = true) }?.url,
            notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    /** Whether [remote] is a higher version than [current]; suffixes such as "-debug" or "-nightly.12" are ignored. */
    fun isNewer(remote: String, current: String): Boolean {
        val r = numbers(remote)
        val c = numbers(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** "<sha256>  <file>" lines as written by `sha256sum` (a "*" marks binary mode): file name to lowercase hash. */
    fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { CHECKSUM_LINE.matchEntire(it.trim()) }
        .associate { m -> m.groupValues[2].trim().substringAfterLast('/') to m.groupValues[1].lowercase() }

    private fun numbers(v: String): List<Int> =
        v.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }

    private class Asset(val name: String, val url: String, val size: Long)

    const val CHECKSUMS = "SHA256SUMS.txt"
    private val CHECKSUM_LINE = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")
    private const val TIMEOUT_MS = 10_000
}

/** Runs the APK download outside the screens' lifetime: it goes on when the app is left or its process is restarted. */
interface UpdateBackground {
    fun start(release: ReleaseInfo)
    fun cancel()
}

/**
 * Updates of the app from GitHub Releases. Checking is one anonymous request: when the user taps "Check for updates",
 * or once a day at start if they turned that on (off by default, the app goes online only when asked). The APK built
 * for this device is downloaded into the cache, its SHA-256 is compared with SHA256SUMS.txt of the release, and the
 * system installer takes over; Android itself only accepts an update signed with the same key.
 * One instance for the app. With a [background] runner (the app's, see [UpdateDownloadWorker]) the download survives
 * leaving the app; without one (tests) it runs in this object's scope.
 */
class AppUpdates(
    private val context: Context,
    private val settings: AppSettings,
    private val fetch: suspend () -> ReleaseInfo? = { UpdateChecker.latest() },
    private val downloader: suspend (ReleaseInfo, File, (Float?) -> Unit) -> File = UpdateDownloader::download,
    private val installer: (File) -> Unit = { file ->
        UpdateDownloader.checkPackage(context, file)
        context.startActivity(UpdateDownloader.installIntent(context, file))
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val background: UpdateBackground? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** The installed version name ("1.7.0", "1.7.0-debug"). */
    val current: String = appVersion(context).first

    /** A debug build has its own package: a release installs next to it, not over it. */
    val isDebugBuild: Boolean = context.packageName.endsWith(".debug")

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _dialog = MutableStateFlow<ReleaseInfo?>(null)

    /** The release whose details dialog is open (shown by the settings). */
    val dialog: StateFlow<ReleaseInfo?> = _dialog.asStateFlow()

    /** Version of a newer release found by the last check: the dot on Settings and the card on top of them. */
    val available: Flow<String?> = settings.updateLatest.flow
        .map { v -> v.takeIf { it.isNotBlank() && UpdateChecker.isNewer(it, current) } }
        .distinctUntilChanged()

    /** At start: the daily check, if the user turned it on. Quiet, only a newer release changes anything. */
    fun checkIfDue() {
        scope.launch {
            if (!settings.updateAutoCheck.get()) return@launch
            if (now() - settings.updateCheckedAt.get() < CHECK_EVERY_MS) return@launch
            runCheck(quiet = true, openDialog = false)
        }
    }

    /** "Check for updates"; [openDialog] shows the release right away if it is newer. */
    fun check(openDialog: Boolean = false) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        job = scope.launch { runCheck(quiet = false, openDialog = openDialog) }
    }

    /** A tap on the update card or item: the release, checking first if this run of the app has not yet. */
    fun openDetails() {
        when (val s = _state.value) {
            is UpdateState.Available -> _dialog.value = s.release
            is UpdateState.ReadyToInstall -> install(s.file)
            is UpdateState.Failed -> if (s.release != null) _dialog.value = s.release else check(openDialog = true)
            is UpdateState.Downloading, UpdateState.Checking -> Unit
            else -> check(openDialog = true)
        }
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    /** Downloads and verifies the APK of [release], then opens the installer. */
    fun download(release: ReleaseInfo) {
        _dialog.value = null
        if (release.apkUrl == null || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(release, null)
        val bg = background
        if (bg != null) {
            savePending(release)
            bg.start(release)
            return
        }
        job = scope.launch {
            val result = runDownload(release)
            if (result is UpdateState.ReadyToInstall) ContextCompat.getMainExecutor(context).execute { install(result.file) }
        }
    }

    /** One download attempt of [release]; the state follows it. [onProgress] also gets every progress step. */
    suspend fun runDownload(release: ReleaseInfo, onProgress: (Float?) -> Unit = {}): UpdateState {
        _state.value = UpdateState.Downloading(release, (_state.value as? UpdateState.Downloading)?.progress)
        val result = try {
            val file = downloader(release, UpdateDownloader.dir(context)) { p ->
                _state.value = UpdateState.Downloading(release, p)
                onProgress(p)
            }
            UpdateState.ReadyToInstall(release, file)
        } catch (e: CancellationException) {
            _state.value = UpdateState.Available(release)
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Update download failed")
            UpdateState.Failed(e, release)
        }
        _state.value = result
        return result
    }

    /** Between two attempts of a background download (waiting for the network): still "downloading" for the user. */
    fun waitingToRetry(release: ReleaseInfo) {
        _state.value = UpdateState.Downloading(release, null)
    }

    fun cancelDownload() {
        val s = _state.value as? UpdateState.Downloading ?: return
        val bg = background
        if (bg != null) {
            bg.cancel()
            clearPending()
            _state.value = UpdateState.Available(s.release)
        } else {
            job?.cancel()
        }
    }

    /** The release a background download works on; kept on disk so a restarted process resumes the same one. */
    fun pendingRelease(): ReleaseInfo? = runCatching {
        val o = Json.parseToJsonElement(pendingFile().takeIf { it.isFile }?.readText() ?: return null).jsonObject
        ReleaseInfo(
            version = o["version"]!!.jsonPrimitive.content,
            pageUrl = o["pageUrl"]!!.jsonPrimitive.content,
            apkUrl = o["apkUrl"]?.jsonPrimitive?.contentOrNull,
            apkSize = o["apkSize"]?.jsonPrimitive?.longOrNull ?: 0,
            checksumsUrl = o["checksumsUrl"]?.jsonPrimitive?.contentOrNull,
            notes = o["notes"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }.getOrNull()

    fun clearPending() {
        pendingFile().delete()
    }

    private fun savePending(release: ReleaseInfo) {
        val o = buildJsonObject {
            put("version", release.version)
            put("pageUrl", release.pageUrl)
            put("apkUrl", release.apkUrl)
            put("apkSize", release.apkSize)
            put("checksumsUrl", release.checksumsUrl)
            put("notes", release.notes)
        }
        runCatching { pendingFile().writeText(o.toString()) }.onFailure { Timber.w(it, "Could not save the pending update") }
    }

    private fun pendingFile() = File(context.noBackupFilesDir, "update-pending.json")

    /** Opens the system installer; Android asks once to allow installing apps from Yomu. */
    fun install(file: File) {
        runCatching { installer(file) }.onFailure { e ->
            Timber.w(e, "Could not open the package installer")
            _state.value = UpdateState.Failed(e, (_state.value as? UpdateState.ReadyToInstall)?.release)
        }
    }

    private suspend fun runCheck(quiet: Boolean, openDialog: Boolean) {
        val result = try {
            val latest = fetch()
            settings.updateCheckedAt.set(now())
            settings.updateLatest.set(latest?.version.orEmpty())
            when {
                latest == null -> UpdateState.NoReleases
                UpdateChecker.isNewer(latest.version, current) -> UpdateState.Available(latest)
                else -> UpdateState.UpToDate(current.substringBefore('-'))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.i(e, "Update check failed")
            UpdateState.Failed(e)
        }
        if (quiet) {
            // The check at start speaks up only for a newer version, and never over a download in progress.
            val s = _state.value
            if (result is UpdateState.Available && (s is UpdateState.Idle || s is UpdateState.UpToDate || s is UpdateState.NoReleases || s is UpdateState.Failed)) {
                _state.value = result
            }
            return
        }
        _state.value = result
        if (openDialog && result is UpdateState.Available) _dialog.value = result.release
    }

    companion object {
        /** A little less than a day, so a phone opened at about the same time every morning checks every day. */
        const val CHECK_EVERY_MS = 20L * 60 * 60 * 1000
    }
}

/** Downloads an update APK into the cache and checks it against SHA256SUMS.txt of its release. */
object UpdateDownloader {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val TIMEOUT_MS = 20_000
    private const val MAX_CHECKSUMS_BYTES = 64 * 1024

    fun dir(context: Context) = File(context.cacheDir, "updates")

    suspend fun download(release: ReleaseInfo, dir: File, onProgress: (Float?) -> Unit): File =
        downloadUsing(release, dir, onProgress, ::open)

    internal suspend fun downloadUsing(
        release: ReleaseInfo, dir: File, onProgress: (Float?) -> Unit, connect: (String) -> HttpURLConnection,
    ): File = withContext(Dispatchers.IO) {
        val url = release.apkUrl?.takeIf { it.startsWith("https://") } ?: throw IOException("The release has no APK for this device")
        val name = release.apkName ?: "yomu-${release.version}.apk"
        val expected = release.checksumsUrl?.let { sums -> UpdateChecker.parseChecksums(readText(sums, connect))[name] ?: throw UpdateChecksumException(name) }
        dir.mkdirs()
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        val identity = File(dir, "$name.part.meta")
        // Keep only this release; bind any partial file to both URL and checksum.
        dir.listFiles()?.filter { it != target && it != part && it != identity }?.forEach { it.delete() }
        if (target.isFile && expected != null && sha256(target) == expected) return@withContext target
        val marker = expected?.let { "$url\n$it" }
        if (marker == null || !identity.isFile || identity.readText() != marker) {
            part.delete()
            identity.delete()
        }
        if (marker != null) identity.writeText(marker)
        var offset = part.takeIf { it.isFile }?.length() ?: 0L
        if (release.apkSize > 0 && offset >= release.apkSize) {
            // An entire partial file can be recovered after a crash before rename.
            if (offset == release.apkSize && expected != null && sha256(part) == expected) {
                target.delete()
                if (!part.renameTo(target)) throw IOException("Could not save $name")
                identity.delete()
                return@withContext target
            }
            part.delete()
            offset = 0
        }
        var c = connect(url).apply { if (offset > 0) setRequestProperty("Range", "bytes=$offset-") }
        try {
            if (offset > 0 && c.responseCode == 416) {
                c.disconnect()
                part.delete()
                offset = 0
                c = connect(url)
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total: Long?
            if (offset > 0 && code == HttpURLConnection.HTTP_PARTIAL) {
                total = rangeTotal(c.getHeaderField("Content-Range"), offset)
                    ?: run { part.delete(); identity.delete(); throw IOException("Invalid Content-Range for update") }
                if (release.apkSize > 0 && total != release.apkSize) {
                    part.delete(); identity.delete(); throw IOException("Update size changed")
                }
            } else {
                if (code != HttpURLConnection.HTTP_OK) throw IOException("Unexpected HTTP $code for update")
                offset = 0 // Server ignored Range: replace the partial file, never append a full response.
                total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
            }
            onProgress(total?.let { (offset.toFloat() / it).coerceIn(0f, 1f) })
            var done = offset
            var shown = -1
            c.inputStream.use { input ->
                part.outputStreamForUpdate(append = offset > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (total != null && done != total) throw IOException("Incomplete update download")
        } catch (e: Throwable) {
            // Without a checksum the partial data cannot be bound safely to a release.
            if (expected == null) { part.delete(); identity.delete() }
            throw e
        } finally {
            c.disconnect()
        }
        if (expected != null && sha256(part) != expected) {
            part.delete()
            identity.delete()
            throw UpdateChecksumException(name)
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not save $name")
        identity.delete()
        target
    }

    private fun File.outputStreamForUpdate(append: Boolean) = java.io.FileOutputStream(this, append)

    /** Returns the total only for a complete, well-formed suffix range starting at the local offset. */
    fun rangeTotal(header: String?, offset: Long): Long? {
        val match = RANGE.matchEntire(header?.trim() ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return total.takeIf { start == offset && start <= end && end == total - 1 && total > 0 }
    }

    private val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

    /** Inspect package identity and signing certificates before invoking Android's own verifier/installer. */
    @Suppress("DEPRECATION")
    fun checkPackage(context: Context, file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("Invalid or unsigned update APK")
        val base = context.packageName.removeSuffix(".debug")
        if (archive.packageName != base && archive.packageName != context.packageName) throw IOException("Update APK has a different package name")
        fun certificates(info: PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        } else {
            info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        }
        val candidate = certificates(archive)
        if (candidate.isEmpty()) throw IOException("Update APK has no signing certificate")
        val installed = try { pm.getPackageInfo(archive.packageName, flags) } catch (_: PackageManager.NameNotFoundException) { null }
        if (installed != null && certificates(installed) != candidate) throw IOException("Update APK signing certificate differs from the installed app")
    }

    fun installIntent(context: Context, file: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.files", file), APK_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun readText(url: String, connect: (String) -> HttpURLConnection): String {
        val c = connect(url)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                while (out.size() < MAX_CHECKSUMS_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Yomu")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Release notes reduced from Markdown to plain paragraphs for the update dialog. */
data class NoteBlock(val text: String, val kind: Kind) {
    /** [NOTE] is a `>` quote: in CHANGELOG.md these are the installation notes ("installs over 1.6.0 if…"). */
    enum class Kind { HEADING, BULLET, TEXT, NOTE }
}

object ReleaseNotes {
    /**
     * The part of the notes written for people (the CHANGELOG section): what the release workflow and GitHub add
     * after it (the file list, pull requests) is left out, and so is Markdown markup.
     */
    fun blocks(markdown: String, max: Int = 80): List<NoteBlock> {
        val out = mutableListOf<NoteBlock>()
        var kind: NoteBlock.Kind? = null
        val buf = StringBuilder()
        fun flush() {
            val k = kind
            if (k != null) inline(buf.toString()).takeIf { it.isNotEmpty() }?.let { out += NoteBlock(it, k) }
            kind = null
            buf.setLength(0)
        }
        for (raw in markdown.replace("\r", "").lines()) {
            val line = raw.trim()
            if (STOP.any { line.startsWith(it, ignoreCase = true) }) break
            val bullet = BULLET.find(line)
            when {
                line.isEmpty() || RULE.matches(line) -> flush()
                line.startsWith("#") -> {
                    flush()
                    kind = NoteBlock.Kind.HEADING
                    buf.append(line.trimStart('#'))
                    flush()
                }
                bullet != null -> {
                    flush()
                    kind = NoteBlock.Kind.BULLET
                    buf.append(line.substring(bullet.range.last + 1))
                }
                line.startsWith(">") -> {
                    if (kind != NoteBlock.Kind.NOTE) flush()
                    kind = NoteBlock.Kind.NOTE
                    buf.append(' ').append(line.trimStart('>', ' '))
                }
                else -> {
                    // A line without ">" right after a quote continues it, as in Markdown ("lazy" continuation).
                    if (kind == null) kind = NoteBlock.Kind.TEXT
                    buf.append(' ').append(line)
                }
            }
        }
        flush()
        return out.take(max)
    }

    private fun inline(s: String): String = s
        .replace(IMAGE, "")
        .replace(LINK, "$1")
        .replace(TAG, "")
        .replace(STRONG, "$2")
        .replace(EMPHASIS, "$1")
        .replace("`", "")
        .replace(ESCAPE, "$1")
        .replace(SPACES, " ")
        .trim()

    private val STOP = listOf("### Установка / Install", "## What's Changed", "**Full Changelog**", "## New Contributors")
    private val BULLET = Regex("""^([-*+]|\d+[.)])\s+""")
    private val RULE = Regex("""^([-*_]\s*){3,}$""")
    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val TAG = Regex("""<[^>]+>""")
    private val STRONG = Regex("""(\*\*|__)(.+?)\1""")
    private val EMPHASIS = Regex("""(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])""")
    private val ESCAPE = Regex("""\\([\\`*_{}\[\]()#+\-.!>|])""")
    private val SPACES = Regex("""\s+""")
}
