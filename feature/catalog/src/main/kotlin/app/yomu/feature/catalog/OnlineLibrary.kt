package app.yomu.feature.catalog

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The online library (titles added with the heart on a title page) inside the main library screen: the same grid,
 * sort and filters as Catalog → Library (the tab row is the library's own). [query] is the library search.
 */
@Composable
fun OnlineLibraryPane(
    viewModel: CatalogHomeViewModel,
    snackbar: SnackbarHostState,
    query: String,
    onOpenTitle: (Long) -> Unit,
    onBrowse: () -> Unit,
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val total by viewModel.libraryTotal.collectAsStateWithLifecycle()
    val display by viewModel.libraryDisplay.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val tabMembers by viewModel.tabMembers.collectAsStateWithLifecycle()
    CatalogEventsEffect(viewModel.events, snackbar, onOpenTitle)
    // The library screen draws the tab row itself, so the pane shows every online title.
    LibraryTab(favorites, total, display, tabs, tabMembers, viewModel, onOpenTitle, onBrowse, query, showTabs = false)
}
