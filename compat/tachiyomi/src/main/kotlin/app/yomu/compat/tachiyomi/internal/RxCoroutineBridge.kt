package app.yomu.compat.tachiyomi.internal

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import rx.Observable
import rx.Subscriber
import rx.Subscription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/* Bridges RxJava 1 observables of legacy extensions to coroutines (adapted from Mihon / TachiyomiEH). */

suspend fun <T> Observable<T>.awaitSingle(): T = single().awaitOne()

@OptIn(InternalCoroutinesApi::class)
private suspend fun <T> Observable<T>.awaitOne(): T = suspendCancellableCoroutine { cont ->
    cont.unsubscribeOnCancellation(
        subscribe(
            object : Subscriber<T>() {
                override fun onStart() {
                    request(1)
                }

                override fun onNext(t: T) {
                    cont.resume(t)
                }

                override fun onCompleted() {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Should have invoked onNext"))
                    }
                }

                override fun onError(e: Throwable) {
                    // Rx1 throws NoSuchElementException if cancelled before emission: resume only if still active.
                    val token = cont.tryResumeWithException(e)
                    if (token != null) cont.completeResume(token)
                }
            },
        ),
    )
}

private fun <T> CancellableContinuation<T>.unsubscribeOnCancellation(sub: Subscription) =
    invokeOnCancellation { sub.unsubscribe() }
