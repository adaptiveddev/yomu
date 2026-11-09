package app.yomu.data.work

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** What a notification is about; each kind can be switched off in Settings → Notifications. */
enum class NotifyKind { NEW_CHAPTERS, DOWNLOADS, SYNC, BACKUP, TASKS, REMINDERS, APP_UPDATE }

/**
 * The user's notification settings. [quietFrom]..[quietTo] are hours (0–23); the range may cross midnight.
 * During quiet hours notifications still arrive, but without sound or vibration.
 */
data class NotificationPrefs(
    val disabled: Set<NotifyKind> = emptySet(),
    val quietHours: Boolean = false,
    val quietFrom: Int = 22,
    val quietTo: Int = 8,
) {
    fun allows(kind: NotifyKind): Boolean = kind !in disabled

    fun isQuiet(hour: Int): Boolean = quietHours && quietFrom != quietTo && if (quietFrom < quietTo) hour in quietFrom until quietTo else hour >= quietFrom || hour < quietTo
}

/** One entry of the in-app list of recent notifications. */
@Serializable
data class NotificationRecord(val at: Long, val kind: String, val title: String, val text: String = "", val shown: Boolean = true)

/**
 * The recent notifications (newest first, at most [MAX]) kept in the app's folder, also those muted by the settings.
 * The file is read and written on a background thread of its own, in call order: callers (the UI included) never wait.
 */
class NotificationLog(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "notification-log").apply { isDaemon = true } }
    private val _entries = MutableStateFlow<List<NotificationRecord>>(emptyList())
    val entries: StateFlow<List<NotificationRecord>> = _entries.asStateFlow()

    init {
        io.execute { _entries.value = load() }
    }

    private fun load(): List<NotificationRecord> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString(ListSerializer(NotificationRecord.serializer()), AtomicFile(file).readFully().decodeToString())
    }.getOrDefault(emptyList())

    fun add(record: NotificationRecord) = io.execute { save((listOf(record) + _entries.value).take(MAX)) }

    fun clear() = io.execute { save(emptyList()) }

    /** Waits for the pending reads and writes (tests). */
    internal fun flush() = io.submit {}.get()

    private fun save(list: List<NotificationRecord>) {
        _entries.value = list
        runCatching {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(json.encodeToString(ListSerializer(NotificationRecord.serializer()), list).toByteArray())
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                throw e
            }
        }
    }

    companion object {
        const val MAX = 100

        @Volatile private var instance: NotificationLog? = null

        fun get(context: Context): NotificationLog = instance ?: synchronized(this) {
            instance ?: NotificationLog(File(context.applicationContext.filesDir, "notifications/recent.json")).also { instance = it }
        }
    }
}
