package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** A winning request does not wait for the slowest source to finish. */
internal object FirstReplyRace {
    suspend fun <T : Any> first(
        sources: List<suspend () -> T?>,
        timeoutMs: Long,
    ): T? = coroutineScope {
        val jobs = sources.map { source -> async {
            try { source() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        } }
        val pending = jobs.toMutableList()
        try {
            withTimeoutOrNull(timeoutMs) {
                while (pending.isNotEmpty()) {
                    val (job, value) = select<Pair<Deferred<T?>, T?>> {
                        pending.forEach { job -> job.onAwait { job to it } }
                    }
                    pending.remove(job)
                    if (value != null) return@withTimeoutOrNull value
                }
                null
            }
        } finally { jobs.forEach { it.cancel() } }
    }
}
