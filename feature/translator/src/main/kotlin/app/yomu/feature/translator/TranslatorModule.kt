package app.yomu.feature.translator

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val translatorModule = module {
    single { Speaker(androidContext()) }
    viewModel { TranslationViewModel(get(), get(), get()) }
    viewModel { LanguagePacksViewModel(get(), get()) }
}
