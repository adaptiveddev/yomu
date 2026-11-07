package app.yomu.feature.reader.epub

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.yomu.core.ui.components.RequireWebView

/** Hosts the WebView of [controller]; the first chapter is opened by the caller after [onAttached]. */
@Composable
fun EpubReaderView(controller: EpubController, modifier: Modifier = Modifier, onAttached: () -> Unit) {
    RequireWebView(modifier) {
        AndroidView(
            factory = { ctx -> EpubWebView(ctx).also { controller.attach(it); onAttached() } },
            modifier = modifier.fillMaxSize(),
        )
    }
    DisposableEffect(controller) { onDispose { controller.detach() } }
}
