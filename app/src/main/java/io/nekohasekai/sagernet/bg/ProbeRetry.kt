package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * A single connection probe can lose its first attempt while the freshly started
 * core/plugin is still wiring its dialer, DNS cache, or transport. Retry only
 * that first attempt; cancellation is never retried.
 */
internal suspend fun <T> retryColdStartProbe(
    attempts: Int = 2,
    backoffMillis: Long = 250L,
    isSuccess: (T) -> Boolean = { true },
    block: suspend () -> T,
): T {
    require(attempts > 0)
    var lastFailure: Exception? = null
    for (attempt in 0 until attempts) {
        try {
            val value = block()
            if (isSuccess(value) || attempt == attempts - 1) return value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            lastFailure = failure
            if (attempt == attempts - 1) throw failure
        }
        if (backoffMillis > 0) delay(backoffMillis)
    }
    throw checkNotNull(lastFailure)
}
