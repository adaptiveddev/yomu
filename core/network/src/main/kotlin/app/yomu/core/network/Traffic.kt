package app.yomu.core.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.util.concurrent.atomic.AtomicLong

/** Bytes the app sent and received since [since] (epoch millis), over mobile data and over Wi-Fi or other unmetered networks. */
data class TrafficStats(val mobile: Long = 0, val wifi: Long = 0, val since: Long = 0) {
    val total: Long get() = mobile + wifi
}

/**
 * Counter of the app's own network traffic (catalog, covers, pages, downloads, extensions), split by mobile data and
 * Wi-Fi. Fed by [interceptor] (a network interceptor: answers from the HTTP cache are not counted) and kept across
 * restarts. WebView pages are not included, so the numbers are a close estimate rather than the system's figures.
 */
object TrafficMeter {
    private const val PREFS = "traffic"
    private const val PUBLISH_MS = 1_000L
    private const val PERSIST_MS = 10_000L
    private const val PERSIST_BYTES = 1L shl 20

    private val mobile = AtomicLong()
    private val wifi = AtomicLong()
    private val since = AtomicLong(System.currentTimeMillis())
    private val lastPublish = AtomicLong()
    private val lastPersist = AtomicLong()
    private val unsaved = AtomicLong()

    @Volatile private var prefs: SharedPreferences? = null

    /** Whether the active network is metered (mobile data, a hotspot); set by the app at start. */
    @Volatile
    var isMetered: () -> Boolean = { false }

    private val _stats = MutableStateFlow(TrafficStats(since = since.get()))
    val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    /** Network interceptor that counts request and response sizes (as sent over the wire, before decompression). */
    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        val metered = runCatching { isMetered() }.getOrDefault(false)
        record(request.headers.byteCount() + (request.body?.contentLength() ?: 0L).coerceAtLeast(0L), metered)
        val response = chain.proceed(request)
        record(response.headers.byteCount(), metered)
        response.newBuilder().body(CountingBody(response.body, metered)).build()
    }

    /** Loads the stored counters; call once at start. */
    fun init(context: Context, metered: () -> Boolean) {
        isMetered = metered
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        mobile.addAndGet(p.getLong("mobile", 0))
        wifi.addAndGet(p.getLong("wifi", 0))
        val stored = p.getLong("since", 0)
        if (stored > 0) since.set(stored) else p.edit().putLong("since", since.get()).apply()
        publish()
    }

    fun record(bytes: Long, metered: Boolean) {
        if (bytes <= 0) return
        (if (metered) mobile else wifi).addAndGet(bytes)
        val now = System.currentTimeMillis()
        val last = lastPublish.get()
        if (now - last >= PUBLISH_MS && lastPublish.compareAndSet(last, now)) publish()
        val pending = unsaved.addAndGet(bytes)
        val saved = lastPersist.get()
        if ((pending >= PERSIST_BYTES || now - saved >= PERSIST_MS) && lastPersist.compareAndSet(saved, now)) persist()
    }

    /** Current numbers, including bytes not published to [stats] yet. */
    fun snapshot(): TrafficStats = TrafficStats(mobile.get(), wifi.get(), since.get())

    /** Starts counting from zero. */
    fun reset() {
        mobile.set(0)
        wifi.set(0)
        since.set(System.currentTimeMillis())
        persist()
        publish()
    }

    /** Publishes the bytes counted since the last update of [stats]. */
    fun refresh() = publish()

    /** Writes the counters now (when the app goes to the background). */
    fun flush() {
        persist()
        publish()
    }

    private fun publish() {
        _stats.value = snapshot()
    }

    private fun persist() {
        unsaved.set(0)
        prefs?.edit()?.putLong("mobile", mobile.get())?.putLong("wifi", wifi.get())?.putLong("since", since.get())?.apply()
    }

    private class CountingBody(private val body: ResponseBody, private val metered: Boolean) : ResponseBody() {
        private val counted: BufferedSource by lazy { CountingSource(body.source(), metered).buffer() }
        override fun contentType(): MediaType? = body.contentType()
        override fun contentLength(): Long = body.contentLength()
        override fun source(): BufferedSource = counted
    }

    private class CountingSource(delegate: Source, private val metered: Boolean) : ForwardingSource(delegate) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n > 0) record(n, metered)
            return n
        }
    }
}

/**
 * Traffic limits on metered networks, mirrored from the settings by the app so that the network and image layers can
 * read them without suspending.
 */
object DataSaver {
    /** "Save mobile data": less page preloading, no automatic downloads on mobile data. */
    @Volatile var enabled: Boolean = false

    /** Covers are loaded on mobile data (otherwise only covers already cached are shown). */
    @Volatile var coversOnMobile: Boolean = true

    /** True while the data saver applies (it is on and the network is metered). */
    val active: Boolean get() = enabled && TrafficMeter.isMetered()

    /** True when covers must not be loaded from the network now. */
    val blockCovers: Boolean get() = !coversOnMobile && TrafficMeter.isMetered()

    /** Pages preloaded by the manga reader while the data saver applies. */
    const val PRELOAD_PAGES = 1
}
