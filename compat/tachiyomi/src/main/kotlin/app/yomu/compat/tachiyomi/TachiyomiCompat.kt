package app.yomu.compat.tachiyomi

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host side of the Tachiyomi/Mihon extension API.
 *
 * Extensions obtain host objects through Injekt (`Injekt.get<NetworkHelper>()`, `injectLazy<Json>()`, …),
 * so [init] must run in `Application.onCreate` before any extension class is loaded.
 */
object TachiyomiCompat {

    /** The same browser-like default as Mihon: many sites refuse obviously non-browser agents. */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"

    /** Extension library versions the host implements: the same set Mihon accepts (1.5 was an incompatible fork). */
    val SUPPORTED_LIB_VERSIONS: List<Double> = listOf(1.4, 1.6)

    @Volatile
    var versionCode: Int = 1
        internal set

    @Volatile
    var versionName: String = "1.0"
        internal set

    @Volatile
    internal var userAgent: () -> String = { DEFAULT_USER_AGENT }

    /** Lets the host add e.g. DNS-over-HTTPS or logging to the extensions' client. */
    @Volatile
    internal var clientCustomizer: ((OkHttpClient.Builder) -> Unit)? = null

    private val initialized = AtomicBoolean(false)

    val isInitialized: Boolean get() = initialized.get()

    @OptIn(ExperimentalSerializationApi::class)
    fun init(
        app: Application,
        versionCode: Int,
        versionName: String,
        userAgent: (() -> String)? = null,
        clientCustomizer: ((OkHttpClient.Builder) -> Unit)? = null,
    ) {
        if (!initialized.compareAndSet(false, true)) return
        this.versionCode = versionCode
        this.versionName = versionName
        if (userAgent != null) this.userAgent = userAgent
        this.clientCustomizer = clientCustomizer

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        Injekt.addSingleton(fullType<Application>(), app)
        Injekt.addSingleton(fullType<Context>(), app)
        Injekt.addSingleton(fullType<Json>(), json)
        Injekt.addSingleton(fullType<ProtoBuf>(), ProtoBuf)
        Injekt.addSingletonFactory(fullType<NetworkHelper>()) { NetworkHelper(app) }
        Injekt.addSingletonFactory(fullType<JavaScriptEngine>()) { JavaScriptEngine(app) }
    }

    val network: NetworkHelper get() = Injekt.get()

    /** True when the library version declared by an extension can be loaded by this host. */
    fun isLibVersionSupported(libVersion: Double): Boolean = libVersion in SUPPORTED_LIB_VERSIONS
}
