package app.yomu.core.common

import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber

/**
 * Last line of defence for the coroutines of screens (`viewModelScope.launch(ScreenErrors) { … }`): an error that no
 * code handled (a full disk, a broken database row, a bug) is written to the error log and reported with a short
 * message instead of closing the whole app. Expected failures are still handled and explained where they happen.
 */
val ScreenErrors: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e -> ErrorLog.handled(e) }

/**
 * For small UI work started by gestures (for example looking up a link under a tap in a PDF page while the reader is
 * closing): a failure is only logged, there is nothing for the user to retry.
 */
val QuietErrors: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e -> Timber.w(e, "Ignored error in UI work") }
