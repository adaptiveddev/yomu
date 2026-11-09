package app.yomu

import android.app.Application
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import app.yomu.compat.tachiyomi.TachiyomiCompat
import app.yomu.core.common.AppError
import app.yomu.core.common.ErrorLog
import app.yomu.core.datastore.AppSettings
import app.yomu.core.network.DataSaver
import app.yomu.core.network.NetworkClients
import app.yomu.core.network.NetworkMonitor
import app.yomu.core.network.TrafficMeter
import app.yomu.data.di.dataModule
import app.yomu.data.work.Notifications
import app.yomu.data.work.WorkScheduler
import app.yomu.feature.annotations.annotationsModule
import app.yomu.feature.catalog.catalogModule
import app.yomu.feature.dictionary.dictionaryModule
import app.yomu.feature.downloads.downloadsModule
import app.yomu.feature.editor.editorModule
import app.yomu.feature.library.libraryModule
import app.yomu.feature.reader.readerModule
import app.yomu.feature.audio.audioModule
import app.yomu.feature.settings.settingsModule
import app.yomu.feature.translator.translatorModule
import app.yomu.source.HttpStatusException
import app.yomu.source.SourceException
import app.yomu.source.SourceNetwork
import app.yomu.source.WebViewRequiredException
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber
import app.yomu.widget.ContinueWidgets
import app.yomu.data.book.pdf.PdfAnnotationStore

/** Koin modules of the whole app (also used by the DI verification test). */
val yomuModules = listOf(
    dataModule, libraryModule, annotationsModule, dictionaryModule, translatorModule, readerModule,
    catalogModule, downloadsModule, settingsModule, editorModule, audioModule, appModule,
)

class YomuApp : Application(), SingletonImageLoader.Factory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ErrorLog.install(this, BuildConfig.VERSION_NAME)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        // Host objects for Mihon/Tachiyomi extensions (Injekt) must exist before any extension class is loaded.
        TachiyomiCompat.init(
            this,
            BuildConfig.VERSION_CODE,
            BuildConfig.VERSION_NAME.substringBefore('-'),
            clientCustomizer = { it.addNetworkInterceptor(TrafficMeter.interceptor) },
        )

        startKoin {
            androidContext(this@YomuApp)
            modules(yomuModules)
        }

        val monitor = get<NetworkMonitor>()
        TrafficMeter.init(this) { monitor.isMetered }
        val clients = get<NetworkClients>()
        SourceNetwork.client = clients.client
        SourceNetwork.appVersion = BuildConfig.VERSION_NAME.substringBefore('-')
        AppError.mapper = ::mapError
        Notifications.launchIntentFactory = { ctx ->
            Intent(ctx, YomuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        Notifications.createChannels(this)
        Notifications.watch(appScope, get())
        get<AppLockController>().start()
        get<AppLauncherIcons>().start()
        get<AutoSync>().start()
        // The "Continue reading" widget follows the reading progress while the app runs (when it is on the home screen).
        ContinueWidgets.start(this)
        get<PdfAnnotationStore>().start(appScope)

        val settings = get<AppSettings>()
        appScope.launch { runCatching { settings.migrate() }.onFailure { Timber.w(it, "Settings migration failed") } }
        // A process killed during a dictionary import leaves it half-written: nothing can be importing at start.
        appScope.launch { runCatching { get<app.yomu.data.dictionary.DictionaryRepository>().removeUnfinished() } }
        // The network and image layers read the traffic limits without suspending.
        appScope.launch {
            combine(settings.dataSaver.flow, settings.coversOnMobile.flow) { saver, covers -> saver to covers }
                .distinctUntilChanged()
                .collect { (saver, covers) ->
                    DataSaver.enabled = saver
                    DataSaver.coversOnMobile = covers
                }
        }
        // Views outside Compose (WebView, extension settings screens) follow the app theme instead of the system one.
        appScope.launch(Dispatchers.Main) {
            settings.themeMode.flow.distinctUntilChanged().collect { mode ->
                AppCompatDelegate.setDefaultNightMode(
                    when (mode) {
                        "LIGHT" -> AppCompatDelegate.MODE_NIGHT_NO
                        "DARK" -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    },
                )
            }
        }
        appScope.launch {
            runCatching { get<WorkScheduler>().scheduleAll() }.onFailure { Timber.w(it, "Scheduling background work failed") }
            if (!BuildConfig.DEBUG && settings.verboseLogging.flow.first()) Timber.plant(Timber.DebugTree())
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val clients = get<NetworkClients>()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { clients.client }))
                add(CoverTrafficInterceptor)
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(256L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The app went to the background (or memory is low): store the traffic counters.
        TrafficMeter.flush()
    }

    private fun mapError(t: Throwable): AppError? = when (t) {
        is WebViewRequiredException -> AppError.NeedsWebView(t.url)
        is HttpStatusException -> AppError.Http(t.code, t.message)
        is SourceException -> t.cause?.let(AppError::from)
        else -> null
    }
}

/** With "Covers on mobile data" off, images from the internet come only from the cache while the network is metered. */
private object CoverTrafficInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data
        val remote = when (data) {
            is String -> data.startsWith("http://") || data.startsWith("https://")
            is coil3.Uri -> data.scheme == "http" || data.scheme == "https"
            else -> data.toString().startsWith("http")
        }
        if (!remote || !DataSaver.blockCovers) return chain.proceed()
        return chain.withRequest(request.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()).proceed()
    }
}
