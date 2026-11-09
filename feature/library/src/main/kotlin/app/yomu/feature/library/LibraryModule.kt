package app.yomu.feature.library

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val libraryModule = module {
    viewModel { LibraryViewModel(androidContext(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { HistoryViewModel(get(), get(), get()) }
    viewModel { (bookId: Long) -> BookDetailsViewModel(bookId, androidContext(), get(), get(), get(), get(), get(), get()) }
}
