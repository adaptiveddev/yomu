package app.yomu.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.IOException

/** A typed preference. [S] is the stored type, [T] the exposed type. */
class Pref<S, T>(
    private val store: DataStore<Preferences>,
    val key: Preferences.Key<S>,
    val default: T,
    private val decode: (S) -> T?,
    private val encode: (T) -> S,
) {
    val flow: Flow<T> = store.data
        // A read failure of the file (I/O error) falls back to the default instead of crashing the collector.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p -> p[key]?.let { runCatching { decode(it) }.getOrNull() } ?: default }
        .distinctUntilChanged()

    suspend fun get(): T = flow.first()

    suspend fun set(value: T) {
        store.editOffMain { it[key] = encode(value) }
    }

    suspend fun update(transform: (T) -> T) {
        store.editOffMain { p ->
            val current = p[key]?.let { runCatching { decode(it) }.getOrNull() } ?: default
            p[key] = encode(transform(current))
        }
    }

    suspend fun reset() {
        store.editOffMain { it.remove(key) }
    }
}

/**
 * [edit] with the change applied off the main thread. DataStore runs the change in the caller's context while it holds
 * its write lock, so a write started on the main thread (a ViewModel) kept every other write of the app waiting for
 * the next free main-thread frame; where the main thread never comes back to it (Robolectric's paused looper between
 * two tests) the writes after it never finished at all.
 */
internal suspend fun DataStore<Preferences>.editOffMain(change: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
    withContext(Dispatchers.IO) { edit(change) }
}

/** Exposes a preference as a hot [kotlinx.coroutines.flow.StateFlow] for ViewModels. */
fun <T> Pref<*, T>.stateIn(scope: kotlinx.coroutines.CoroutineScope): kotlinx.coroutines.flow.StateFlow<T> =
    flow.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, default)
