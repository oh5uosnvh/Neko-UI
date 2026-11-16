package io.nekohasekai.sagernet.bg.proto

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A worker slot owns preparation, startup, the blocking probe AND cleanup.
 * Never close a partially prepared instance: cancellation waits for its lifecycle
 * lock, then closes from IO (the probe executor may be entirely blocked in JNI).
 * Both tasks are children, so returning/cancelAndJoin also waits for native exit.
 */
internal suspend fun <T> runUrlTestLifecycle(
    context: CoroutineContext,
    prepare: suspend () -> Unit,
    start: () -> Unit,
    probe: suspend () -> T,
    close: suspend () -> Unit,
): T = coroutineScope {
    val owner = this
    val lifecycle = Mutex()
    var closed = false
    suspend fun closeResource() = withContext(NonCancellable + Dispatchers.IO) {
        lifecycle.withLock {
            if (!closed) {
                closed = true
                close()
            }
        }
    }
    suspendCancellableCoroutine { continuation ->
        // Ignore any Job in the dispatcher context: ownership always stays here.
        val worker = launch(context.minusKey(kotlinx.coroutines.Job), start = CoroutineStart.LAZY) {
            try {
                lifecycle.withLock {
                    coroutineContext.ensureActive()
                    prepare()
                    coroutineContext.ensureActive()
                    start()
                    coroutineContext.ensureActive()
                }
                coroutineContext.ensureActive()
                val result = probe()
                coroutineContext.ensureActive()
                continuation.resumeWith(Result.success(result))
            } catch (failure: Throwable) {
                continuation.resumeWith(Result.failure(failure))
            } finally {
                closeResource()
            }
        }
        continuation.invokeOnCancellation {
            worker.cancel()
            // Enter even if the owner was cancelled; still a child, not GlobalScope.
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            owner.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                closeResource()
            }
        }
        // Cancellation before dispatch must not leave the continuation suspended.
        worker.invokeOnCompletion { failure ->
            if (failure != null && !continuation.isCompleted) {
                continuation.resumeWith(Result.failure(failure))
            }
        }
        worker.start()
    }
}
