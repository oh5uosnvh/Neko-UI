package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Own the native worker until it actually stops. Cancellation requests a close
 * on a different executor (the probe executor can be occupied by JNI), while
 * the resource mutex prevents close-before-init from leaking a newly built Box.
 */
internal suspend fun <T> runCancellableProbe(
    context: CoroutineContext,
    prepare: suspend () -> Unit,
    close: suspend () -> Unit,
    probe: suspend () -> T,
): T = coroutineScope {
    val owner = this
    val lifecycle = Mutex()
    val closed = AtomicBoolean(false)
    suspend fun closeResource() = withContext(NonCancellable + Dispatchers.IO) {
        lifecycle.withLock {
            if (closed.compareAndSet(false, true)) close()
        }
    }
    suspendCancellableCoroutine { continuation ->
        val worker = launch(context, start = CoroutineStart.LAZY) {
            try {
                lifecycle.withLock {
                    coroutineContext.ensureActive()
                    prepare()
                    coroutineContext.ensureActive()
                }
                coroutineContext.ensureActive()
                val value = probe()
                coroutineContext.ensureActive()
                runCatching { continuation.resumeWith(Result.success(value)) }
            } catch (cancelled: CancellationException) {
                runCatching { continuation.resumeWith(Result.failure(cancelled)) }
            } catch (failure: Exception) {
                runCatching { continuation.resumeWith(Result.failure(failure)) }
            } finally {
                closeResource()
            }
        }
        continuation.invokeOnCancellation {
            worker.cancel()
            // ATOMIC enters the cleanup body even when the owner was cancelled.
            @Suppress("EXPERIMENTAL_API_USAGE")
            owner.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) { closeResource() }
        }
        worker.invokeOnCompletion { failure ->
            if (failure != null) runCatching { continuation.resumeWith(Result.failure(failure)) }
        }
        worker.start()
    }
}
