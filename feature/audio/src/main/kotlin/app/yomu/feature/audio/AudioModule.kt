package app.yomu.feature.audio

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val audioModule = module {
    single { AudioController(androidContext()) }
    viewModel { (bookId: Long) -> AudioPlayerViewModel(bookId, get(), get(), get(), get(), get()) }
}
