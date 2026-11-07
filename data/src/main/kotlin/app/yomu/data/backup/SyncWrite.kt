package app.yomu.data.backup

import androidx.room.withTransaction
import app.yomu.core.database.YomuDatabase
import kotlinx.serialization.json.Json

/**
 * The merge changes local rows. Keep those changes in the database transaction until the server accepts the shared
 * state: if a conditional write fails or the connection drops, retrying must not leave a half-applied local merge.
 * A server accepting a write immediately before an interrupted response can still retain it; the next read merges it.
 */
internal suspend fun mergeAndWrite(
    db: YomuDatabase,
    backend: SyncBackend,
    previous: SyncFile?,
    remote: SyncState?,
    base: SyncBase?,
    now: Long,
    options: SyncMerger.Options,
): SyncMerger.Outcome = db.withTransaction {
    val result = SyncMerger(db).merge(remote, base, backend.id, now, options)
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    backend.write(json.encodeToString(SyncState.serializer(), result.state).toByteArray(), previous)
    result
}
