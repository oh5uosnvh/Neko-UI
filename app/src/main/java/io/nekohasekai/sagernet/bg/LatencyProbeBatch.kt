package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.coroutineContext

/** Per-run limits: live probes, initialization, and verification never disagree. */
internal object LatencyProbePolicy {
    const val DEFAULT_CONCURRENCY = 12
    const val MAX_CONCURRENCY = 16
    const val INITIALIZATION_CONCURRENCY = 2
    const val VERIFICATION_CONCURRENCY = 2

    fun workers(configured: Int, targets: Int): Int =
        if (targets <= 0) 0 else configured.coerceIn(1, MAX_CONCURRENCY).coerceAtMost(targets)

    /** Configuration/auth/certificate errors cannot become healthy by waiting. */
    fun isTransient(failure: Exception): Boolean {
        if (failure is CancellationException || failure is IllegalArgumentException) return false
        val text = generateSequence<Throwable>(failure) { it.cause }
            .take(8).mapNotNull { it.message }.joinToString(" ").lowercase(Locale.ROOT)
        if (listOf("certificate", "x509", "authentication", "unauthorized", "invalid config",
                "decode config", "unknown outbound", "plugin not found", "no such host",
                "nxdomain", "missing proxy", "empty proxy chain", "cyclic proxy chain")
                .any(text::contains)) return false
        return listOf("timeout", "timed out", "deadline exceeded", "connection reset",
            "connection refused", "econnreset", "econnrefused", "broken pipe", "unexpected eof",
            "temporary failure", "try again", "network is unreachable", "no route to host")
            .any(text::contains) || text.trim() == "eof" || text.trim().endsWith(": eof")
    }
}

/**
 * Keep successful nodes moving. Transient failures are verified AFTER the first
 * pass with lower concurrency; no provisional timeout or previous cached RTT is
 * published. Exactly one terminal result is delivered for each target.
 */
internal suspend fun <T, R> runLatencyProbeBatch(
    targets: List<T>,
    concurrency: Int,
    verificationConcurrency: Int = LatencyProbePolicy.VERIFICATION_CONCURRENCY,
    shouldRetry: (Exception) -> Boolean = LatencyProbePolicy::isTransient,
    probe: suspend (T) -> R,
    publish: suspend (T, Result<R>) -> Unit,
) = coroutineScope {
    val pending = ConcurrentLinkedQueue(targets)
    val verification = ConcurrentLinkedQueue<T>()
    suspend fun pass(queue: ConcurrentLinkedQueue<T>, workers: Int, retry: Boolean) = coroutineScope {
        repeat(LatencyProbePolicy.workers(workers, queue.size)) {
            launch {
                while (true) {
                    coroutineContext.ensureActive()
                    val target = queue.poll() ?: break
                    val result = try {
                        Result.success(probe(target))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (!retry && shouldRetry(failure)) {
                            verification.add(target)
                            continue
                        }
                        Result.failure(failure)
                    }
                    coroutineContext.ensureActive()
                    publish(target, result)
                }
            }
        }
    }
    pass(pending, concurrency, false)
    pass(verification, verificationConcurrency.coerceAtMost(concurrency.coerceAtLeast(1)), true)
}
