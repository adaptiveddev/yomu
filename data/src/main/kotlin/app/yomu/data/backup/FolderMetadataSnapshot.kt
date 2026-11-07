package app.yomu.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException

/**
 * Revision-qualified metadata envelope. Metadata and the revisions that interpret
 * its page positions must be committed as one conditional write. A standalone folder index is
 * not sufficient: it may advance independently of a metadata write or an old device.
 * Version 3 makes earlier version-aware clients refuse the document rather than stripping revisions.
 */
@Serializable
internal data class FolderMetadataSnapshot(
    val version: Int = 3,
    val state: SyncState,
    val revisions: List<FolderEntry>,
)

internal object FolderMetadataSnapshotCodec {
    private val json = Json { encodeDefaults = true }
    private const val MAX_BYTES = 32 * 1024 * 1024

    internal fun folderHashes(state: SyncState): Set<String> = (
        state.books.map { it.hash } + state.highlights.map { it.hash } +
            state.notes.map { it.hash } + state.bookmarks.map { it.hash }
        ).filter { it.startsWith("folder:") }.toSet()

    private fun validate(snapshot: FolderMetadataSnapshot) {
        require(snapshot.version == 3)
        SyncManager.validateState(snapshot.state)
        FolderManifestCodec.validate(FolderManifest(folders = snapshot.revisions))
        require(folderHashes(snapshot.state) == snapshot.revisions.map { it.id }.toSet())
    }

    // Keep ordinary metadata at the top level. A legacy reader that ignores version
    // numbers can then see the records rather than mistaking this document for an
    // empty library. Such a reader may still strip folderRevisions: a later modern
    // sync must reject the resulting unqualified folder positions, not guess.
    fun encode(snapshot: FolderMetadataSnapshot): ByteArray = try {
        validate(snapshot)
        val state = json.encodeToJsonElement(SyncState.serializer(), snapshot.state).jsonObject
        val root = JsonObject(state + mapOf(
            "version" to JsonPrimitive(3),
            "stateVersion" to JsonPrimitive(snapshot.state.version),
            "folderRevisions" to json.encodeToJsonElement(ListSerializer(FolderEntry.serializer()), snapshot.revisions),
        ))
        root.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    } catch (e: Exception) { throw IOException("Invalid folder metadata snapshot; no data was published", e) }

    fun decode(bytes: ByteArray): FolderMetadataSnapshot = try {
        require(bytes.size <= MAX_BYTES)
        val text = SyncJson.text(bytes)
        val root = json.parseToJsonElement(text).jsonObject
        require(root["version"]?.jsonPrimitive?.intOrNull == 3)
        val snapshot = if ("state" in root) {
            // Read the earlier unreleased nested envelope as well.
            json.decodeFromString(FolderMetadataSnapshot.serializer(), text)
        } else {
            val stateVersion = root["stateVersion"]?.jsonPrimitive?.intOrNull ?: error("Missing state version")
            val revisions = root["folderRevisions"] ?: error("Missing folder revisions")
            val stateRoot = JsonObject((root - "stateVersion" - "folderRevisions") + ("version" to JsonPrimitive(stateVersion)))
            FolderMetadataSnapshot(
                state = json.decodeFromJsonElement(SyncState.serializer(), stateRoot),
                revisions = json.decodeFromJsonElement(ListSerializer(FolderEntry.serializer()), revisions),
            )
        }
        snapshot.also(::validate)
    } catch (e: Exception) { throw IOException("Invalid folder metadata snapshot; no data was applied", e) }

    /** Translate every folder's metadata together, or return no result if any page is ambiguous. */
    fun translate(snapshot: FolderMetadataSnapshot, destinations: List<FolderEntry>): FolderMetadataSnapshot = try {
        validate(snapshot)
        FolderManifestCodec.validate(FolderManifest(folders = destinations))
        val byId = destinations.associateBy { it.id }
        var next = snapshot.state
        val revised = snapshot.revisions.map { old ->
            val new = byId[old.id] ?: throw IOException("Folder revision is missing: ${old.title}")
            next = FolderRemotePositions.translate(next, old, new)
            new
        }
        FolderMetadataSnapshot(state = next, revisions = revised).also(::validate)
    } catch (e: IOException) { throw e }
      catch (e: Exception) { throw IOException("Cannot translate folder metadata; no data was applied", e) }
}
