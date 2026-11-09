package app.yomu.feature.settings

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val settingsModule = module {
    single { AppUpdates(androidContext(), get(), background = WorkUpdateBackground(androidContext())) }
    viewModel { SettingsViewModel(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
