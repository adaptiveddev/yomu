package app.yomu.feature.annotations

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val annotationsModule = module {
    viewModel { (bookId: Long) -> AnnotationsViewModel(bookId, androidContext(), get(), get()) }
}
