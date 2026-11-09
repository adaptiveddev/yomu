package app.yomu.feature.editor

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val editorModule = module {
    viewModel { (bookId: Long) -> EditorViewModel(androidContext(), bookId, get(), get(), get(), get(), get(), get(), get()) }
}
