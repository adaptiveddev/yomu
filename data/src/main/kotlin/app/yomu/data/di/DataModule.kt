package app.yomu.data.di

import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.core.network.NetworkClients
import app.yomu.core.network.NetworkMonitor
import app.yomu.data.audio.AudiobookDownloader
import app.yomu.data.audio.AudiobookStore
import app.yomu.data.audio.YouTubeClient
import app.yomu.data.backup.BackupManager
import app.yomu.data.backup.MihonImporter
import app.yomu.data.tracking.TrackerManager
import app.yomu.data.tracking.TrackerStore
import app.yomu.data.backup.SyncManager
import app.yomu.data.book.pdf.PdfAnnotationStore
import app.yomu.data.catalog.CatalogRepository
import app.yomu.data.dictionary.DictionaryRepository
import app.yomu.data.download.DownloadOverview
import app.yomu.data.download.DownloadRepository
import app.yomu.data.library.CoverStore
import app.yomu.data.library.ImportService
import app.yomu.data.library.LibraryTabs
import app.yomu.data.ocr.OcrService
import app.yomu.data.repository.AnnotationRepositoryImpl
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.data.repository.StatsRepositoryImpl
import app.yomu.data.repository.ReadingHistoryRepository
import app.yomu.data.repository.WordRepositoryImpl
import app.yomu.data.source.ExtensionManager
import app.yomu.data.source.SourceManager
import app.yomu.data.translation.BookTranslator
import app.yomu.data.translation.MlKitEngine
import app.yomu.data.translation.TranslationService
import app.yomu.data.work.WorkScheduler
import app.yomu.domain.repository.AnnotationRepository
import app.yomu.domain.repository.BookRepository
import app.yomu.domain.repository.StatsRepository
import app.yomu.domain.repository.WordRepository
import app.yomu.data.source.SourceHealthTracker
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin graph of the data layer. */
val dataModule = module {
    single { YomuDatabase.build(androidContext()) }
    single { AppSettings(androidContext()) }
    single { NetworkClients(androidContext()) }
    single { NetworkMonitor(androidContext()) }
    single<() -> OkHttpClient> { val clients = get<NetworkClients>(); { clients.client } }
    single { CoverStore(androidContext()) }
    single { BookRepositoryImpl(androidContext(), get(), get()) } bind BookRepository::class
    single { AnnotationRepositoryImpl(get()) } bind AnnotationRepository::class
    single { WordRepositoryImpl(get()) } bind WordRepository::class
    single { StatsRepositoryImpl(get()) } bind StatsRepository::class
    single { ReadingHistoryRepository(get(), get()) }
    single { YouTubeClient { get<NetworkClients>().client } }
    single { AudiobookStore(androidContext(), get(), { get<NetworkClients>().client }, get()) }
    single { AudiobookDownloader(androidContext(), get()) { get<NetworkClients>().downloadClient } }
    single { ImportService(androidContext(), get(), get(), get(), get()) }
    single { MlKitEngine(androidContext()) }
    single { TranslationService(get(), get(), get(), get(), get()) }
    single { BookTranslator(androidContext(), get(), get(), get()) }
    single { DictionaryRepository(androidContext(), get(), get(), get(), get()) }
    single { ExtensionManager(androidContext(), get(), get(), get()) }
    single { SourceManager(androidContext(), get(), get(), get(), get()) }
    single { SourceHealthTracker() }
    single { LibraryTabs(get()) }
    single { CatalogRepository(androidContext(), get(), get(), get(), get(), get(), get()) }
    single { DownloadRepository(androidContext(), get(), get(), get(), get(), get(), get()) }
    single { DownloadOverview(get(), get(), get(), get(), get()) }
    single { BackupManager(androidContext(), get(), get()) }
    single { SyncManager(androidContext(), get(), get(), get(), get()) }
    single {
        val sources = get<SourceManager>()
        MihonImporter(get(), installedSource = { id -> sources.get(id)?.name }, contentOf = { id -> sources.get(id)?.content })
    }
    single { TrackerStore(androidContext()) }
    // TrackerKeys come from the app module (its BuildConfig). Created at start: reading a chapter moves the trackers.
    single(createdAtStart = true) {
        TrackerManager(androidContext(), get(), get(), get(), get(), get()).also { m ->
            get<CatalogRepository>().chaptersReadListener = m::onChaptersRead
        }
    }
    single { OcrService(get()) }
    single { PdfAnnotationStore(androidContext(), get(), get()) }
    single { WorkScheduler(androidContext(), get()) }
}
