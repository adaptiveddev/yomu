package app.yomu.feature.dictionary

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val dictionaryModule = module {
    viewModel { WordLookupViewModel(get(), get(), get(), get()) }
    viewModel { DictionaryViewModel(get(), get(), get()) }
    viewModel { WordsViewModel(androidContext(), get(), get()) }
    viewModel { ReviewViewModel(get(), get()) }
    viewModel { DictionariesViewModel(get(), get()) }
}
