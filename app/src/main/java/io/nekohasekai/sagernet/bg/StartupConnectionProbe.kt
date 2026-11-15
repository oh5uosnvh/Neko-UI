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
        // URLTest/native probe already owns this same end-to-end deadline.
        // Never multiply it with a hidden cold-start retry: a 3000 ms setting
        // must not leave the control bar spinning for 6250 ms (or longer while
        // a blocking native cleanup is being joined).
        val budget = UrlTestTimeout.normalizeMillis(timeoutMillis).toLong()
        return try {
            val elapsed = withTimeout(budget) {
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
