package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.LatencyProbePolicy
import io.nekohasekai.sagernet.bg.runLatencyProbeBatch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class LatencyProbeBatchTest {
    @Test fun workerCountIsBoundedAndNeverExceedsTargets() {
        assertEquals(0, LatencyProbePolicy.workers(999, 0))
        assertEquals(1, LatencyProbePolicy.workers(-20, 100))
        assertEquals(365, LatencyProbePolicy.workers(Int.MAX_VALUE, 365))
        assertEquals(25, LatencyProbePolicy.DEFAULT_CONCURRENCY)
        assertEquals(3, LatencyProbePolicy.workers(12, 3))
    }

    @Test fun simulatedColdTimeoutNeverPublishesBeforeQuietVerification() = runBlocking {
        val calls = ConcurrentHashMap<Int, AtomicInteger>()
        val published = CopyOnWriteArrayList<Pair<Int, Result<Int>>>()
        val firstFinished = AtomicInteger()
        val verificationActive = AtomicInteger()
        val verificationPeak = AtomicInteger()
        val targets = (0 until 40).toList()
        withContext(Dispatchers.Default) {
            runLatencyProbeBatch(targets, 12,
                probe = { node ->
                    val attempt = calls.computeIfAbsent(node) { AtomicInteger() }.incrementAndGet()
                    if (attempt == 1) {
                        delay(2)
                        firstFinished.incrementAndGet()
                        if (node % 3 == 0) throw IOException("context deadline exceeded")
                    } else {
                        assertEquals(targets.size, firstFinished.get())
                        val count = verificationActive.incrementAndGet()
                        verificationPeak.accumulateAndGet(count, ::maxOf)
                        delay(3)
                        verificationActive.decrementAndGet()
                    }
                    50
                },
                publish = { node, result -> published.add(node to result) })
        }
        assertEquals(targets.size, published.size)
        assertEquals(targets.size, published.map { it.first }.toSet().size)
        assertTrue(published.all { it.second.getOrThrow() == 50 })
        assertTrue(verificationPeak.get() in 1..2)
        assertTrue(calls.values.all { it.get() in 1..2 })
    }

    @Test fun allHealthyNodesUseOneProbeAndFullLifecycleConcurrencyIsCapped() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val published = CopyOnWriteArrayList<Int>()
        withContext(Dispatchers.Default) {
            runLatencyProbeBatch((0 until 100).toList(), Int.MAX_VALUE,
                probe = {
                    calls.incrementAndGet()
                    val count = active.incrementAndGet()
                    peak.accumulateAndGet(count, ::maxOf)
                    delay(3)
                    active.decrementAndGet()
                    42
                }, publish = { node, result -> assertEquals(42, result.getOrThrow()); published.add(node) })
        }
        assertEquals(100, calls.get())
        assertEquals(100, published.toSet().size)
        assertTrue(peak.get() in 2..16)
        assertEquals(0, active.get())
    }

    @Test fun permanentErrorsAreNotRetriedAndDeadNodesPublishOnlyOnce() = runBlocking {
        val calls = IntArray(3)
        val results = mutableListOf<Result<Int>>()
        runLatencyProbeBatch(listOf(0, 1, 2), 1,
            probe = { node ->
                calls[node]++
                when (node) {
                    0 -> throw IllegalArgumentException("invalid config")
                    1 -> throw IOException("x509: certificate is not trusted")
                    else -> throw IOException("timeout")
                }
            }, publish = { _, result: Result<Int> -> results.add(result) })
        assertArrayEquals(intArrayOf(1, 1, 2), calls)
        assertEquals(3, results.size)
        assertTrue(results.all { it.isFailure })
    }

    @Test fun cancellationIsNotRetriedOrReportedAsNodeFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val published = AtomicInteger()
        val job = launch {
            runLatencyProbeBatch(listOf(1), 1,
                probe = {
                    calls.incrementAndGet(); started.complete(Unit)
                    awaitCancellation()
                }, publish = { _, _: Result<Int> -> published.incrementAndGet() })
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(1, calls.get())
        assertEquals(0, published.get())
    }

    @Test fun classificationNeverRetriesAuthCertificatesOrCancellation() {
        assertTrue(LatencyProbePolicy.isTransient(IOException("connection reset by peer")))
        assertTrue(LatencyProbePolicy.isTransient(IOException("context deadline exceeded")))
        assertTrue(LatencyProbePolicy.isTransient(IOException("Get \"https://example.org/204\": EOF")))
        assertFalse(LatencyProbePolicy.isTransient(IOException("x509 certificate timeout")))
        assertFalse(LatencyProbePolicy.isTransient(IOException("authentication failed: timeout")))
        assertFalse(LatencyProbePolicy.isTransient(IOException("no such host")))
        assertFalse(LatencyProbePolicy.isTransient(CancellationException("timeout")))
    }
}
