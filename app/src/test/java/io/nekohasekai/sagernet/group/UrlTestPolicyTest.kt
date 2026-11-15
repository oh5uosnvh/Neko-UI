package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.urlTestWorkerCount
import io.nekohasekai.sagernet.bg.runCancellableProbe
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlTestPolicyTest {
    @Test fun all36NativeWorkersCanEnterBeforeAnyFinishes() = runBlocking {
        val workers = urlTestWorkerCount(36, 94)
        val pool = Executors.newFixedThreadPool(workers).asCoroutineDispatcher()
        val entered = CountDownLatch(workers)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        try {
            val jobs = List(workers) {
                async(Dispatchers.IO) {
                    runCancellableProbe(pool, prepare = {}, close = { closed.incrementAndGet() },
                        probe = {
                            entered.countDown()
                            assertTrue(release.await(3, TimeUnit.SECONDS))
                            50
                        })
                }
            }
            val together = withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) }
            release.countDown()
            assertTrue("configured36 concurrency must not become16", together)
            assertEquals(List(workers) { 50 }, jobs.awaitAll())
            assertEquals(workers, closed.get())
        } finally { release.countDown(); pool.close() }
    }
    @Test fun configured36WorkersFor94NodesAreNotSilentlyReducedTo16() {
        assertEquals(36, urlTestWorkerCount(36, 94))
    }
    @Test fun concurrencyCannotExceedTheActualNodeCount() {
        assertEquals(4, urlTestWorkerCount(36, 4))
        assertEquals(94, urlTestWorkerCount(Int.MAX_VALUE, 94))
    }
    @Test fun emptyBatchDoesNotCreateWorkers() {
        assertEquals(0, urlTestWorkerCount(36, 0))
    }
    @Test fun invalidLegacyValueStillHasOneWorkerForNonemptyBatch() {
        assertEquals(1, urlTestWorkerCount(0, 94))
        assertEquals(1, urlTestWorkerCount(-1, 94))
    }
}
