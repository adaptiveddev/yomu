package app.yomu.feature.catalog

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Parameters of [SourceBrowseViewModel]; [tab] is a [BrowseTab] name to open with. */
data class BrowseArgs(val sourceId: Long, val query: String? = null, val navUrl: String? = null, val navTitle: String? = null, val tab: String? = null)

/** Parameters of [GlobalSearchViewModel]; [migrateFrom] switches it to migration mode. */
data class SearchArgs(val query: String? = null, val migrateFrom: Long? = null)

val catalogModule = module {
    viewModel { CatalogHomeViewModel(androidContext(), get(), get(), get(), get()) }
    viewModel { (args: BrowseArgs) -> SourceBrowseViewModel(args.sourceId, args.query, args.navUrl, args.navTitle, args.tab, androidContext(), get(), get(), get()) }
    viewModel { (args: SearchArgs) -> GlobalSearchViewModel(args.query, args.migrateFrom, get(), get(), get()) }
    viewModel { (entryId: Long) -> TitleViewModel(entryId, get(), get(), get(), get(), get(), get()) }
    viewModel { ExtensionsViewModel(androidContext(), get(), get(), get()) }
}
