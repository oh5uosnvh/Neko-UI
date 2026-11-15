package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class BatchProbeResult<T>(val profile: T, val latency: Int?, val error: Exception?)

/**
 * Stream each leaf's outcome before its worker takes another node. There is no
 * input-ordered await/replay: a slow first node cannot hide later completions.
 * Only probe failures become results; cancellation and callback failures escape
 * to the structured owner, which joins every worker before returning.
 */
internal suspend fun <T> runBatchProbe(
    profiles: List<T>,
    concurrency: Int = 10,
    probe: suspend (T) -> Int,
    onResult: suspend (BatchProbeResult<T>) -> Unit,
) = withContext(Dispatchers.IO) {
    runUrlTestBatch(profiles, concurrency) { profile ->
        val result = try {
            BatchProbeResult(profile, probe(profile), null)
        } catch (cancelled: CancellationException) {
            // runUrlTestBatch owns launch workers: a child's CancellationException
            // alone is normally a successful join, so also cancel this batch owner.
            // Otherwise one cancelled leaf could leave a falsely completed batch.
            this@withContext.cancel(cancelled)
            throw cancelled
        } catch (error: Exception) {
            BatchProbeResult(profile, null, error)
        }
        // Blocking JNI may return (or throw) only after native cancellation.
        // Never turn that late outcome into a new UI/database completion.
        ensureActive()
        try {
            onResult(result)
        } catch (cancelled: CancellationException) {
            this@withContext.cancel(cancelled)
            throw cancelled
        }
    }
}
