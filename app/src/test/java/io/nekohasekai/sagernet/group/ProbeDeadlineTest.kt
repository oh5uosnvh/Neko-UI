package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.ProbeDeadline
import io.nekohasekai.sagernet.bg.runCancellableProbe
import io.nekohasekai.sagernet.bg.runUrlTestBatch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProbeDeadlineTest {
    @Test fun setupAndHttpConsumeOneBudgetWithoutA500msFloor() {
        var nanos = 0L
        val deadline = ProbeDeadline(3000) { nanos }
        nanos = 2_750_000_000L
        assertEquals(250, deadline.remainingMillis())
        nanos += 249_500_000L
        assertEquals(1, deadline.remainingMillis())
        nanos += 500_000L
        try { deadline.remainingMillis(); fail("setup must not renew the HTTP budget") }
        catch (_: IOException) { }
        assertEquals(20, ProbeDeadline(20) { 0L }.remainingMillis())
    }

    @Test fun rejectsInvalidTimeoutWithoutCreatingNativeResources() {
        for (timeout in listOf(0, -1, Int.MIN_VALUE)) {
            try { ProbeDeadline(timeout); fail("invalid budget") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun monotonicClockWrapAndLargeBudgetsStayValid() {
        var now = Long.MAX_VALUE - 1_000_000L
        val deadline = ProbeDeadline(Int.MAX_VALUE) { now }
        now += 10_000_000L
        assertEquals(Int.MAX_VALUE - 10, deadline.remainingMillis())
    }

    @Test fun executorQueueTimeDoesNotSpendNodeBudgetAndCleanupDoesNotChangeRtt() = runBlocking {
        val pool = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = launch(pool) { occupied.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        assertTrue(occupied.await(2, TimeUnit.SECONDS))
        var now = 0L
        var remaining = 0
        val result = async {
            lateinit var deadline: ProbeDeadline
            runCancellableProbe(pool, prepare = {
                deadline = ProbeDeadline(3000) { now }
                now += 100_000_000L
            }, probe = { remaining = deadline.remainingMillis(); 141 }, close = { now += 5_000_000_000L })
        }
        yield()
        now += 10_000_000_000L // queue residence exceeds the entire configured budget
        release.countDown()
        try {
            assertEquals(141, result.await())
            assertEquals(2900, remaining)
            blocker.join()
        } finally { release.countDown(); pool.close() }
    }

    @Test fun batchNeverReusesSlotUntilCleanupCompletes() = runBlocking {
        val pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val live = AtomicInteger()
        val max = AtomicInteger()
        val completed = AtomicInteger()
        try {
            runUrlTestBatch((1..94).toList(), 4) {
                val result = runCancellableProbe(pool, prepare = {
                    val n = live.incrementAndGet(); max.updateAndGet { old -> maxOf(old, n) }
                }, probe = { 31 }, close = { delay(2); live.decrementAndGet(); completed.incrementAndGet() })
                assertEquals(31, result)
            }
            assertEquals(94, completed.get())
            assertEquals(0, live.get())
            assertEquals(4, max.get())
        } finally { pool.close() }
    }

    @Test fun cancelLeavesQueuedNodesUnstartedAndJoinsAllOwnedWorkers() = runBlocking {
        val pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val entered = CountDownLatch(4)
        val released = CountDownLatch(1)
        val starts = AtomicInteger()
        val closed = AtomicInteger()
        try {
            val batch = launch {
                runUrlTestBatch((1..94).toList(), 4) {
                    runCancellableProbe(pool, prepare = { starts.incrementAndGet() },
                        interrupt = { released.countDown() }, close = { closed.incrementAndGet() },
                        probe = { entered.countDown(); assertTrue(released.await(3, TimeUnit.SECONDS)); 50 })
                }
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            withTimeout(2000) { batch.cancelAndJoin() }
            assertEquals(4, starts.get())
            assertEquals(4, closed.get())
        } finally { released.countDown(); pool.close() }
    }

    @Test fun workerFailureCancelsSiblingsAndRunsCleanup() = runBlocking {
        val pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val closed = AtomicInteger()
        try {
            try {
                runUrlTestBatch((1..20).toList(), 4) { node ->
                    runCancellableProbe(pool, prepare = {}, close = { closed.incrementAndGet() },
                        probe = { if (node == 1) throw IOException("synthetic failure") else delay(100); 50 })
                }
                fail("failure must not become batch success")
            } catch (_: IOException) { }
            assertTrue(closed.get() in 1..4)
        } finally { pool.close() }
    }
}
