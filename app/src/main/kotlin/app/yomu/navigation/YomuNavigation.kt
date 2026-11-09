package app.yomu.navigation

import android.os.Bundle
import android.widget.Toast
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.Modifier
import app.yomu.core.ui.components.IncognitoBanner
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.yomu.MainEvent
import app.yomu.MainViewModel
import app.yomu.R
import app.yomu.core.common.ErrorLog
import app.yomu.core.ui.components.FloatingNavBarDefaults
import app.yomu.core.ui.components.LocalNavAnimatedScope
import app.yomu.core.ui.components.LocalNavBarHidden
import app.yomu.core.ui.components.LocalNavBarInset
import app.yomu.core.ui.components.NavTab
import app.yomu.core.ui.components.item
import app.yomu.core.ui.components.LocalSharedTransitionScope
import app.yomu.core.ui.components.errorMessage
import app.yomu.data.repository.toJson
import app.yomu.feature.annotations.AnnotationsScreen
import app.yomu.feature.catalog.BrowseArgs
import app.yomu.feature.catalog.CatalogScreen
import app.yomu.feature.catalog.ExtensionsScreen
import app.yomu.feature.catalog.GlobalSearchScreen
import app.yomu.feature.catalog.SearchArgs
import app.yomu.feature.catalog.SourceBrowseScreen
import app.yomu.feature.catalog.SourceBrowseViewModel
import app.yomu.feature.catalog.SourceSettingsScreen
import app.yomu.feature.catalog.TitleScreen
import app.yomu.feature.catalog.WebViewScreen
import app.yomu.feature.dictionary.DictionariesScreen
import app.yomu.feature.dictionary.DictionaryScreen
import app.yomu.feature.dictionary.ReviewScreen
import app.yomu.feature.downloads.DownloadsScreen
import app.yomu.feature.editor.EditorScreen
import app.yomu.feature.library.BookDetailsScreen
import app.yomu.feature.library.LibraryScreen
import app.yomu.feature.reader.DocxLayoutScreen
import app.yomu.feature.reader.ReaderArgs
import app.yomu.feature.reader.ReaderScreen
import app.yomu.feature.reader.ReadingStatsScreen
import app.yomu.feature.settings.SettingsLinks
import app.yomu.feature.settings.SettingsScreen
import app.yomu.feature.settings.SettingsSection
import app.yomu.profile.ProfileScreen
import app.yomu.feature.translator.LanguagePacksScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.feature.catalog.CatalogHomeViewModel
import app.yomu.feature.catalog.OnlineLibraryPane
import app.yomu.feature.library.OnlineLibrary
import app.yomu.domain.repository.BookRepository
import app.yomu.feature.audio.AudioController
import app.yomu.feature.audio.AudioPlayerScreen
import app.yomu.feature.audio.NowPlayingBar
import androidx.compose.runtime.produceState
import org.koin.compose.koinInject

/** The screens of the [NavTab]s, all inside [HomeRoute] (see [TabsHost]); [trailName] names them in crash reports. */
enum class TopLevel(val tab: NavTab, val trailName: String) {
    LIBRARY(NavTab.LIBRARY, "Library tab"),
    HISTORY(NavTab.HISTORY, "Profile tab"),
    CATALOG(NavTab.CATALOG, "Catalog tab"),
    DICTIONARY(NavTab.DICTIONARY, "Dictionary tab"),
    SETTINGS(NavTab.SETTINGS, "Settings tab"),
    ;

    companion object {
        fun of(tab: NavTab): TopLevel = entries.first { it.tab == tab }
    }
}

/**
 * A destination in the crash reports (1.9.0): the name of its route and its numeric arguments (ids), never texts such
 * as search queries, addresses or positions in a book.
 */
internal fun trailName(destination: NavDestination, arguments: Bundle?): String {
    val name = destination.route?.substringBefore('/')?.substringBefore('?')?.substringAfterLast('.') ?: "?"
    val ids = arguments?.keySet().orEmpty().sorted().mapNotNull { key ->
        @Suppress("DEPRECATION")
        when (val value = arguments?.get(key)) {
            is Long -> value.takeIf { it >= 0 }?.let { "$key=$it" }
            is Int -> value.takeIf { it >= 0 }?.let { "$key=$it" }
            else -> null
        }
    }
    return (listOf(name) + ids).joinToString(" ")
}

/**
 * App shell: adaptive navigation (bottom bar / rail) for the top-level tabs and all other screens on top.
 * [tabs] are the tabs of the bar in the user's order; the first one is the screen the app opens with. Hidden tabs keep
 * their destinations (the library still links to the catalog and the settings).
 */
@Composable
fun YomuNavigation(main: MainViewModel, reduceMotion: Boolean, navLabels: Boolean = false, tabs: List<NavTab> = NavTab.entries) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val shown = tabs.ifEmpty { NavTab.entries }.map(TopLevel::of)
    // The tab the app opens with is fixed for the lifetime of the screen: a new order applies from the next start.
    val tabsState = rememberSaveable(saver = TabsState.Saver) { TabsState(shown.first()) }
    val current = if (entry?.destination?.hasRoute(HomeRoute::class) == true) tabsState.selected else null
    val context = LocalContext.current
    val resources = LocalResources.current

    // The screen on display for the crash reports, set before the new screen is composed (a crash there names it).
    DisposableEffect(nav, tabsState) {
        val listener = NavController.OnDestinationChangedListener { _, destination, arguments ->
            ErrorLog.screen(if (destination.hasRoute(HomeRoute::class)) tabsState.selected.trailName else trailName(destination, arguments))
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }

    LaunchedEffect(main, nav) {
        main.events.collect { e ->
            when (e) {
                is MainEvent.OpenBook -> nav.navigate(ReaderRoute(bookId = e.bookId))
                is MainEvent.Lookup -> nav.navigate(LookupRoute(e.text))
                is MainEvent.AddExtensionStore -> nav.navigate(ExtensionsRoute(addStore = e.url))
                MainEvent.OpenDownloads -> nav.navigate(DownloadsRoute) { launchSingleTop = true }
                is MainEvent.OpenChapter -> nav.navigate(ReaderRoute(chapterId = e.chapterId))
                is MainEvent.OpenTitle -> nav.navigate(TitleRoute(e.entryId))
                is MainEvent.Imported -> {
                    val text = when {
                        e.error != null && e.added == 0 -> errorMessage(context, e.error)
                        else -> resources.getQuantityString(R.plurals.import_result, e.added, e.added) +
                            if (e.failed > 0) " · " + resources.getString(R.string.import_failed_count, e.failed) else ""
                    }
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val adaptive = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    val update by main.updateAvailable.collectAsStateWithLifecycle()
    val items = shown.map { top -> top.tab.item().let { if (top.tab == NavTab.SETTINGS && update != null) it.copy(badge = true) else it } }
    // A top-level screen can hide the bar for a while (the library's own bar for selected books takes its place).
    val barHidden = remember { mutableStateOf(false) }
    // The floating bar is drawn over the top-level screens; they leave this much room at the bottom of their lists.
    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barInset = if (adaptive == NavigationSuiteType.NavigationBar) FloatingNavBarDefaults.height(navLabels) + navBarsBottom else 0.dp
    val incognito by main.incognito.collectAsStateWithLifecycle()
    // Every screen but the reader, which keeps the whole screen for the text.
    val showIncognito = incognito && entry?.destination?.hasRoute(ReaderRoute::class) != true
    // An audiobook playing in the background: a bar on every screen but its player and the reader.
    val audio: AudioController = koinInject()
    val nowPlaying by audio.nowPlaying.collectAsStateWithLifecycle(null)
    val playing = nowPlaying?.takeIf { entry?.destination?.hasRoute(ReaderRoute::class) != true }
    Column(Modifier.fillMaxSize()) {
        if (showIncognito) IncognitoBanner(onTurnOff = { main.setIncognito(false) })
        playing?.let { np ->
            NowPlayingBar(
                np,
                onOpen = { nav.navigate(ReaderRoute(bookId = np.bookId)) },
                onToggle = audio::togglePlay,
                onStop = audio::stop,
                modifier = if (showIncognito) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier,
            )
        }
        val topTaken = showIncognito || playing != null
        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(if (topTaken) WindowInsets.statusBars else WindowInsets(0, 0, 0, 0))) {
            Shell(adaptive, current, barHidden, items, shown, navLabels, tabsState, barInset, nav, reduceMotion)
        }
    }
}

@Composable
private fun Shell(
    adaptive: NavigationSuiteType,
    current: TopLevel?,
    barHidden: androidx.compose.runtime.MutableState<Boolean>,
    items: List<app.yomu.core.ui.components.NavItem>,
    shown: List<TopLevel>,
    navLabels: Boolean,
    tabsState: TabsState,
    barInset: androidx.compose.ui.unit.Dp,
    nav: NavHostController,
    reduceMotion: Boolean,
) {
    NavShell(
        type = adaptive,
        visible = current != null && !barHidden.value,
        items = items,
        selected = shown.indexOf(current),
        showLabels = navLabels,
        onSelect = { index -> shown.getOrNull(index)?.let(tabsState::select) },
        // A press already composes a tab never opened: by the time the finger lifts, it is ready to be shown.
        onPress = { index -> shown.getOrNull(index)?.let(tabsState::prepare) },
    ) {
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalNavBarHidden provides barHidden, LocalTabBarInset provides barInset) {
                YomuNavHost(nav, reduceMotion, tabsState)
            }
        }
    }
}

@Composable
private fun YomuNavHost(nav: NavHostController, reduceMotion: Boolean, tabs: TabsState) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(
        navController = nav,
        startDestination = HomeRoute,
        enterTransition = { if (reduceMotion) EnterTransition.None else fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.97f) },
        exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(90)) },
        // During a predictive back gesture NavHost seeks these two with the finger: the screen shrinks away like a card
        // and the one below comes up from a slight zoom, the Android 14+ "peek" look.
        popEnterTransition = {
            if (reduceMotion) EnterTransition.None else fadeIn(tween(240)) + scaleIn(tween(300), initialScale = 0.94f)
        },
        popExitTransition = {
            if (reduceMotion) ExitTransition.None else fadeOut(tween(220, delayMillis = 60)) + scaleOut(tween(300), targetScale = 0.88f)
        },
    ) {
        // ---------------------------------------------------------------- tabs
        val settingsLinks = SettingsLinks(
            onOpenDictionaries = { nav.navigate(DictionariesRoute) },
            onOpenLanguagePacks = { nav.navigate(LanguagePacksRoute) },
            onOpenExtensions = { nav.navigate(ExtensionsRoute()) },
            onOpenDownloads = { nav.navigate(DownloadsRoute) },
            onOpenStats = { nav.navigate(StatsRoute) },
        )
        screen<HomeRoute> {
            val wide = LocalConfiguration.current.screenWidthDp >= TWO_PANE_MIN_WIDTH_DP
            var settingsPane by rememberSaveable { mutableStateOf(SettingsSection.APPEARANCE.name) }
            TabsHost(tabs, reduceMotion) { tab ->
                CompositionLocalProvider(LocalNavBarInset provides LocalTabBarInset.current) {
                    when (tab) {
                        TopLevel.LIBRARY -> LibraryTab(nav, tabs)
                        TopLevel.HISTORY -> ProfileScreen(
                            viewModel = koinViewModel(),
                            historyViewModel = koinViewModel(),
                            statsViewModel = koinViewModel(),
                            onOpenBook = { nav.navigate(ReaderRoute(bookId = it)) },
                            onBookDetails = { nav.navigate(BookDetailsRoute(it)) },
                            onReadChapter = { nav.navigate(ReaderRoute(chapterId = it)) },
                            onOpenTitle = { nav.navigate(TitleRoute(it)) },
                            onOpenSettings = { section ->
                                if (wide) {
                                    settingsPane = section.name
                                    tabs.select(TopLevel.SETTINGS)
                                } else {
                                    nav.navigate(SettingsSectionRoute(section.name))
                                }
                            },
                            onOpenDownloads = { nav.navigate(DownloadsRoute) },
                        )
                        TopLevel.CATALOG -> CatalogScreen(
                            viewModel = koinViewModel(),
                            onOpenSource = { nav.navigate(SourceRoute(it)) },
                            onOpenTitle = { nav.navigate(TitleRoute(it)) },
                            onReadChapter = { nav.navigate(ReaderRoute(chapterId = it)) },
                            onGlobalSearch = { nav.navigate(GlobalSearchRoute()) },
                            onOpenExtensions = { nav.navigate(ExtensionsRoute()) },
                            onOpenDownloads = { nav.navigate(DownloadsRoute) },
                        )
                        TopLevel.DICTIONARY -> DictionaryScreen(
                            viewModel = koinViewModel(),
                            wordsViewModel = koinViewModel(),
                            onOpenDictionaries = { nav.navigate(DictionariesRoute) },
                            onStartReview = { nav.navigate(ReviewRoute) },
                            onOpenBook = { id, locator, reference -> nav.navigate(ReaderRoute(bookId = id, locator = locator?.toJson(), positionReference = reference)) },
                        )
                        TopLevel.SETTINGS -> if (wide) {
                            SettingsTwoPane(
                                selected = SettingsSection.entries.firstOrNull { it.name == settingsPane } ?: SettingsSection.APPEARANCE,
                                onSelect = { settingsPane = it.name },
                                links = settingsLinks,
                            )
                        } else {
                            SettingsScreen(viewModel = koinViewModel(), links = settingsLinks)
                        }
                    }
                }
            }
        }
        screen<SettingsSectionRoute> { entry ->
            val section = entry.toRoute<SettingsSectionRoute>().section
            SettingsScreen(
                viewModel = koinViewModel(),
                links = settingsLinks,
                onBack = back,
                initialSection = SettingsSection.entries.firstOrNull { it.name == section },
            )
        }

        // ---------------------------------------------------------------- library & reading
        screen<BookDetailsRoute> { entry ->
            val r = entry.toRoute<BookDetailsRoute>()
            BookDetailsScreen(
                viewModel = koinViewModel { parametersOf(r.bookId) },
                onBack = back,
                onRead = { nav.navigate(ReaderRoute(bookId = it.id)) },
                onOpenEditor = { nav.navigate(EditorRoute(it.id)) },
                onOpenAnnotations = { nav.navigate(AnnotationsRoute(it.id)) },
                onOpenPageLayout = { nav.navigate(DocxLayoutRoute(it.id)) },
            )
        }
        screen<ReaderRoute> { entry ->
            val r = entry.toRoute<ReaderRoute>()
            // Audiobooks open in the player; everything else in the reader.
            val books: BookRepository = koinInject()
            val isAudio by produceState<Boolean?>(if (r.bookId < 0) false else null, r.bookId) {
                if (r.bookId >= 0) value = runCatching { books.getBook(r.bookId)?.format?.isAudio == true }.getOrDefault(false)
            }
            when (isAudio) {
                null -> Unit
                true -> AudioPlayerScreen(koinViewModel { parametersOf(r.bookId) }, back)
                false -> ReaderScreen(
                viewModel = koinViewModel { parametersOf(ReaderArgs(bookId = r.bookId, locatorJson = r.locator, chapterId = r.chapterId, positionReference = r.positionReference)) },
                onBack = back,
                onOpenAnnotations = { nav.navigate(AnnotationsRoute(it)) },
                onOpenDictionary = { nav.navigate(LookupRoute(it)) },
                onOpenPacks = { nav.navigate(LanguagePacksRoute) },
                onOpenStats = { nav.navigate(StatsRoute) },
                onOpenEditor = { nav.navigate(EditorRoute(it)) },
                onOpenPageLayout = { nav.navigate(DocxLayoutRoute(it)) },
            )
            }
        }
        screen<DocxLayoutRoute> { entry ->
            val r = entry.toRoute<DocxLayoutRoute>()
            DocxLayoutScreen(koinViewModel { parametersOf(r.bookId) }, back)
        }
        screen<AnnotationsRoute> { entry ->
            val r = entry.toRoute<AnnotationsRoute>()
            AnnotationsScreen(
                viewModel = koinViewModel { parametersOf(r.bookId) },
                onBack = back,
                onOpen = { bookId, locator, reference ->
                    // Coming from the reader of the same book: go back to it at the new position.
                    nav.navigate(ReaderRoute(bookId = bookId, locator = locator?.toJson(), positionReference = reference)) {
                        popUpTo<ReaderRoute> { inclusive = true }
                    }
                },
            )
        }
        screen<StatsRoute> { ReadingStatsScreen(koinViewModel(), back) }
        screen<EditorRoute> { entry ->
            val r = entry.toRoute<EditorRoute>()
            EditorScreen(koinViewModel { parametersOf(r.bookId) }, back)
        }

        // ---------------------------------------------------------------- dictionary & translation
        screen<LookupRoute> { entry ->
            val r = entry.toRoute<LookupRoute>()
            DictionaryScreen(
                viewModel = koinViewModel(),
                wordsViewModel = koinViewModel(),
                onOpenDictionaries = { nav.navigate(DictionariesRoute) },
                onStartReview = { nav.navigate(ReviewRoute) },
                onOpenBook = { id, locator, reference -> nav.navigate(ReaderRoute(bookId = id, locator = locator?.toJson(), positionReference = reference)) },
                initialQuery = r.query,
            )
        }
        screen<DictionariesRoute> { DictionariesScreen(koinViewModel(), back) }
        screen<ReviewRoute> { ReviewScreen(koinViewModel(), back) }
        screen<LanguagePacksRoute> { LanguagePacksScreen(koinViewModel(), back) }

        // ---------------------------------------------------------------- catalog
        screen<SourceRoute> { entry ->
            val r = entry.toRoute<SourceRoute>()
            // Wide windows keep the grid and show the tapped title in a pane beside it.
            val twoPane = LocalConfiguration.current.screenWidthDp >= 840
            var paneTitle by rememberSaveable { mutableStateOf<Long?>(null) }
            val pane = paneTitle
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize()) {
                    SourceBrowseScreen(
                        viewModel = koinViewModel<SourceBrowseViewModel> { parametersOf(BrowseArgs(r.sourceId, r.query, r.navUrl, r.navTitle, r.tab)) },
                        onBack = back,
                        onOpenTitle = { if (twoPane) paneTitle = it else nav.navigate(TitleRoute(it)) },
                        onOpenNavigation = { sourceId, url, title -> nav.navigate(SourceRoute(sourceId, navUrl = url, navTitle = title)) },
                        onOpenWebView = { nav.navigate(WebViewRoute(it)) },
                        // Another language of the source takes the place of this one: Back still leads to the catalog.
                        onSwitchSource = { sourceId, query, tab ->
                            nav.navigate(SourceRoute(sourceId, query = query, tab = tab)) { popUpTo<SourceRoute> { inclusive = true } }
                        },
                    )
                }
                if (twoPane && pane != null) {
                    VerticalDivider()
                    key(pane) {
                        Box(Modifier.width(440.dp).fillMaxSize()) {
                            TitleScreen(
                                viewModel = koinViewModel(key = "pane-$pane") { parametersOf(pane) },
                                onBack = { paneTitle = null },
                                onReadChapter = { nav.navigate(ReaderRoute(chapterId = it)) },
                                onMigrate = { id, title -> nav.navigate(GlobalSearchRoute(query = title, migrateFrom = id)) },
                                onOpenWebView = { nav.navigate(WebViewRoute(it)) },
                                onOpenDownloads = { nav.navigate(DownloadsRoute) },
                                inPane = true,
                                onOpenBook = { nav.navigate(ReaderRoute(bookId = it)) },
                            )
                        }
                    }
                }
            }
        }
        screen<GlobalSearchRoute> { entry ->
            val r = entry.toRoute<GlobalSearchRoute>()
            val migrate = r.migrateFrom.takeIf { it >= 0 }
            GlobalSearchScreen(
                viewModel = koinViewModel { parametersOf(SearchArgs(r.query, migrate)) },
                onBack = back,
                onOpenTitle = { id ->
                    if (migrate != null) {
                        // The old title page is obsolete after a migration.
                        nav.navigate(TitleRoute(id)) { popUpTo<TitleRoute> { inclusive = true } }
                    } else {
                        nav.navigate(TitleRoute(id))
                    }
                },
                onOpenSource = { sourceId, query -> nav.navigate(SourceRoute(sourceId, query = query)) },
            )
        }
        screen<TitleRoute> { entry ->
            val r = entry.toRoute<TitleRoute>()
            TitleScreen(
                viewModel = koinViewModel { parametersOf(r.entryId) },
                onBack = back,
                onReadChapter = { nav.navigate(ReaderRoute(chapterId = it)) },
                onMigrate = { id, title -> nav.navigate(GlobalSearchRoute(query = title, migrateFrom = id)) },
                onOpenWebView = { nav.navigate(WebViewRoute(it)) },
                onOpenDownloads = { nav.navigate(DownloadsRoute) },
                onOpenBook = { nav.navigate(ReaderRoute(bookId = it)) },
            )
        }
        screen<ExtensionsRoute> { entry ->
            val r = entry.toRoute<ExtensionsRoute>()
            ExtensionsScreen(koinViewModel(), back, onOpenSourceSettings = { nav.navigate(SourceSettingsRoute(it)) }, addStoreUrl = r.addStore)
        }
        screen<SourceSettingsRoute> { entry -> SourceSettingsScreen(entry.toRoute<SourceSettingsRoute>().sourceId, back) }
        screen<WebViewRoute> { entry -> WebViewScreen(entry.toRoute<WebViewRoute>().url, back) }
        screen<DownloadsRoute> { DownloadsScreen(koinViewModel(), back) }
    }
}

/**
 * Room the floating bar takes over the top-level screens. A composition local rather than a value captured by the
 * graph builder: a new value (rotation, labels on or off) must not rebuild the navigation graph.
 */
private val LocalTabBarInset = compositionLocalOf { 0.dp }

/** A destination that exposes its animation scope for shared cover transitions. */
private inline fun <reified T : Any> NavGraphBuilder.screen(crossinline content: @Composable AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit) {
    composable<T> { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}

/** The library with the titles added with the heart in the catalog: the "Online" tab, a shelf in "All" and the user's tabs. */
@Composable
private fun LibraryTab(nav: NavHostController, tabs: TabsState) {
    val catalogVm: CatalogHomeViewModel = koinViewModel()
    val openTitle: (Long) -> Unit = { nav.navigate(TitleRoute(it)) }
    val online = remember(catalogVm) {
        OnlineLibrary { snackbar, query ->
            OnlineLibraryPane(catalogVm, snackbar, query, onOpenTitle = openTitle, onBrowse = { tabs.select(TopLevel.CATALOG) })
        }
    }
    // Wide windows keep the grid and show the details of the tapped book in a pane beside it.
    val twoPane = LocalConfiguration.current.screenWidthDp >= 840
    var paneBook by rememberSaveable { mutableStateOf<Long?>(null) }
    val library: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            LibraryScreen(
                viewModel = koinViewModel(),
                onOpenBook = { nav.navigate(ReaderRoute(bookId = it.id)) },
                onOpenDetails = { if (twoPane) paneBook = it.id else nav.navigate(BookDetailsRoute(it.id)) },
                onFindBooksOnline = { tabs.select(TopLevel.CATALOG) },
                onOpenSettings = { tabs.select(TopLevel.SETTINGS) },
                onOpenTitle = openTitle,
                onReadChapter = { nav.navigate(ReaderRoute(chapterId = it)) },
                onOpenDownloads = { nav.navigate(DownloadsRoute) },
                online = online,
            )
        }
    }
    val pane = paneBook
    if (twoPane && pane != null) {
        Row(Modifier.fillMaxSize()) {
            library(Modifier.weight(1f).fillMaxSize())
            VerticalDivider()
            key(pane) {
                Box(Modifier.width(420.dp).fillMaxSize()) {
                    BookDetailsScreen(
                        viewModel = koinViewModel(key = "pane-$pane") { parametersOf(pane) },
                        onBack = { paneBook = null },
                        onRead = { nav.navigate(ReaderRoute(bookId = it.id)) },
                        onOpenEditor = { nav.navigate(EditorRoute(it.id)) },
                        onOpenAnnotations = { nav.navigate(AnnotationsRoute(it.id)) },
                        onOpenPageLayout = { nav.navigate(DocxLayoutRoute(it.id)) },
                        inPane = true,
                    )
                }
            }
        }
    } else {
        library(Modifier.fillMaxSize())
    }
}
