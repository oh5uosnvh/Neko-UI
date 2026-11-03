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
        return try {
            val elapsed = withTimeout(timeoutMillis.coerceIn(500, 120_000).toLong() + 250L) {
                withContext(Dispatchers.IO) { test() }
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
