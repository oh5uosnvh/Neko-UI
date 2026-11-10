package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A measured URL round trip belongs to startup, before publishing Connected. Never invent a ping. */
internal object StartupConnectionProbe {
    data class Result(val elapsed: Int = -1, val error: String? = null)

    suspend fun measure(timeoutMillis: Int, test: () -> Int): Result {
        val perAttempt = timeoutMillis.coerceIn(500, 120_000).toLong()
        // The first URL round trip can race core/plugin, DNS, or TLS lazy setup.
        // Give it one fresh attempt, while keeping one overall finite budget.
        val overall = perAttempt * 2L + 250L
        return try {
            val elapsed = withTimeout(overall) {
                retryColdStartProbe(isSuccess = { it >= 0 }) {
                    // Libcore.urlTest already enforces perAttempt. Do not nest a
                    // coroutine timeout here: an outer cancellation must remain
                    // CancellationException and must never trigger a retry.
                    withContext(Dispatchers.IO) { test() }
                }
            }
            if (elapsed < 0) Result(error = "Connection unavailable") else Result(elapsed = elapsed)
        } catch (_: TimeoutCancellationException) {
            Result(error = "Connection timed out")
        } catch (cancelled: CancellationException) {
            // The native test receives the same finite timeout. Stop/reload must not publish success.
            throw cancelled
        } catch (error: Exception) {
            Result(error = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
        }
    }
}
