package app.yomu.data.backup

import app.yomu.core.database.HighlightEntity
import app.yomu.core.database.NoteEntity
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Content-based deletion preconditions. Private fingerprints remain local, never in the shared file. */
internal object AnnotationRevisions {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun highlight(value: SyncHighlight) = hash(json.encodeToString(SyncHighlight.serializer(), value))
    fun note(value: SyncNote) = hash(json.encodeToString(SyncNote.serializer(), value))
    fun bookmark(value: SyncBookmark) = hash(json.encodeToString(SyncBookmark.serializer(), value))

    @Serializable private data class PrivateNote(val id: Long, val locator: String?, val text: String, val tags: String, val created: Long, val updated: Long)
    @Serializable private data class HighlightPrivate(val favorite: Boolean, val notes: List<PrivateNote>)
    fun privateHighlight(highlight: HighlightEntity, notes: List<NoteEntity>): String = hash(json.encodeToString(
        HighlightPrivate.serializer(), HighlightPrivate(highlight.isFavorite, notes.filter { it.highlightId == highlight.id }.sortedBy { it.id }
            .map { PrivateNote(it.id, it.locatorJson, it.text, it.tags, it.createdAt, it.updatedAt) })
    ))
}
