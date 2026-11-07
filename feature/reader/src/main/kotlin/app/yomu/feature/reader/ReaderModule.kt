package app.yomu.feature.reader

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val readerModule = module {
    viewModel { (args: ReaderArgs) -> ReaderViewModel(args, androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ReadingStatsViewModel(get(), get(), get()) }
    viewModel { (bookId: Long) -> DocxLayoutViewModel(bookId, androidContext(), get(), get()) }
}
