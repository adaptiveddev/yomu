package app.yomu.data.backup

import app.yomu.data.format.FormatDetector
import app.yomu.data.format.NaturalOrder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.Locale

/** Image-folder protocol primitives. Existing legacy identities still require migration. */
@Serializable internal data class FolderPage(val path: String, val sha256: String, val size: Long)
@Serializable internal data class FolderEntry(val id: String, val title: String, val pages: List<FolderPage>)

/**
 * v2 adds [replaced]: legacy (`dir:`) identities that were deliberately migrated to stable ids,
 * old → current. A device that still holds the legacy identity follows the marker instead of
 * creating a second copy of the same folder.
 */
@Serializable internal data class FolderManifest(val version: Int = 1, val folders: List<FolderEntry> = emptyList(), val replaced: Map<String, String> = emptyMap())

internal object FolderManifestCodec {
    private val json = Json { encodeDefaults = true }
    private val idPattern = Regex("folder:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val shaPattern = Regex("[0-9a-f]{64}")
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_FOLDERS = 10000
    private const val MAX_PAGES = 20000

    internal fun validId(id: String): Boolean = idPattern.matches(id)

    /** Paths must remain relative across providers and cannot alias a file or a directory. */
    internal fun validPath(path: String): Boolean {
        if (path.isEmpty() || path.length > 1024 || path.startsWith('/') || '\\' in path || ':' in path) return false
        val parts = path.split('/')
        if (parts.size > 9 || parts.any { part ->
                part.isBlank() || part == "." || part == ".." || part.length > 200 ||
                    part.endsWith(' ') || part.endsWith('.') || part.any { it.code < 32 || it.code == 127 }
            }) return false
        return FormatDetector.isImage(parts.last())
    }

    fun validate(manifest: FolderManifest) {
        require(manifest.version in 1..2 && manifest.folders.size <= MAX_FOLDERS)
        require(manifest.folders.map { it.id }.toSet().size == manifest.folders.size)
        if (manifest.version >= 2) {
            val ids = manifest.folders.map { it.id }.toSet()
            require(manifest.replaced.keys.all { !validId(it) && it.length in 5..512 })
            require(manifest.replaced.values.all { it in ids })
            require(manifest.replaced.keys.none { manifest.replaced[it] == it })
        } else require(manifest.replaced.isEmpty())
        for (folder in manifest.folders) {
            require(validId(folder.id) && folder.title.isNotBlank() && folder.title.length <= 512)
            require(folder.pages.isNotEmpty() && folder.pages.size <= MAX_PAGES)
            require(folder.pages.map { it.path } == folder.pages.map { it.path }.sortedWith(NaturalOrder))
            require(folder.pages.zipWithNext().none { (a, b) -> NaturalOrder.compare(a.path, b.path) == 0 })
            val names = HashSet<String>()
            val canonicalPrefixes = HashMap<String, String>()
            for (page in folder.pages) {
                require(validPath(page.path) && shaPattern.matches(page.sha256) && page.size > 0)
                val components = page.path.split('/')
                for (n in 1..components.size) {
                    val original = components.take(n).joinToString("/")
                    val normalized = java.text.Normalizer.normalize(original, java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT)
                    val prior = canonicalPrefixes.putIfAbsent(normalized, original)
                    require(prior == null || prior == original)
                }
                val normalized = java.text.Normalizer.normalize(page.path, java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT)
                require(names.add(normalized))
            }
            for (path in names) {
                var slash = path.indexOf('/')
                while (slash >= 0) {
                    require(path.substring(0, slash) !in names)
                    slash = path.indexOf('/', slash + 1)
                }
            }
        }
    }

    fun encode(manifest: FolderManifest): ByteArray {
        validate(manifest)
        return json.encodeToString(FolderManifest.serializer(), manifest).toByteArray(Charsets.UTF_8).also {
            if (it.size > MAX_BYTES) throw IOException("Folder sync index is too large")
        }
    }

    fun decode(bytes: ByteArray): FolderManifest = try {
        require(bytes.size <= MAX_BYTES)
        val text = SyncJson.text(bytes)
        val root = json.parseToJsonElement(text).jsonObject
        require("version" in root && "folders" in root)
        json.decodeFromJsonElement(FolderManifest.serializer(), root).also(::validate)
    } catch (e: Exception) {
        throw IOException("Invalid folder sync index; it was not applied or overwritten", e)
    }
}

/** Separate sidecar: older versions must not strip folder entries from the ordinary book index. */
interface FolderFilesTransport : BookFilesTransport {
    suspend fun readFolderIndex(): SyncFile?
    suspend fun writeFolderIndex(bytes: ByteArray, previous: SyncFile?)
}
