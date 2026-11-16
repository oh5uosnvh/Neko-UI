package io.nekohasekai.sagernet.bg.proto

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UrlTestLifecycleTest {
    @Test fun successClosesAndWaitsBeforeReturning() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val events = mutableListOf<String>()
        try {
            val value = runUrlTestLifecycle(pool,
                prepare = { events.add("prepare") }, start = { events.add("start") },
                probe = { events.add("probe"); 42 },
                close = { delay(20); events.add("close") })
            assertEquals(42, value)
            assertEquals(listOf("prepare", "start", "probe", "close"), events)
        } finally { pool.close() }
    }

    @Test fun blockingProbeCancellationClosesOffPoolAndJoinsNativeExit() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val entered = CompletableDeferred<Unit>()
        val stop = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val exitCount = AtomicInteger()
        val delivered = AtomicInteger()
        try {
            val job = launch {
                runUrlTestLifecycle(pool, prepare = {}, start = {}, probe = {
                    entered.complete(Unit)
                    try { assertTrue(stop.await(3, TimeUnit.SECONDS)); 1 }
                    finally { exitCount.incrementAndGet() }
                }, close = { closeCount.incrementAndGet(); stop.countDown(); delay(30) })
                delivered.incrementAndGet()
            }
            entered.await()
            withTimeout(3000) { job.cancelAndJoin() }
            assertEquals(1, closeCount.get())
            assertEquals(1, exitCount.get())
            assertEquals(0, delivered.get())
        } finally { stop.countDown(); pool.close() }
    }

    @Test fun cancellationDuringPrepareDoesNotCloseEarlyOrLaunchLate() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val entered = CompletableDeferred<Unit>()
        val allowInit = CountDownLatch(1)
        val prepared = AtomicInteger()
        val closed = AtomicInteger()
        val launched = AtomicInteger()
        try {
            val job = launch {
                runUrlTestLifecycle(pool, prepare = {
                    entered.complete(Unit)
                    assertTrue(allowInit.await(3, TimeUnit.SECONDS))
                    prepared.incrementAndGet()
                }, start = { launched.incrementAndGet() }, probe = { error("must not probe") },
                    close = { assertEquals(1, prepared.get()); closed.incrementAndGet() })
            }
            entered.await()
            job.cancel()
            delay(30)
            assertEquals(0, closed.get())
            allowInit.countDown()
            withTimeout(3000) { job.join() }
            assertEquals(1, closed.get())
            assertEquals(0, launched.get())
        } finally { allowInit.countDown(); pool.close() }
    }

    @Test fun cancellationDuringStartWaitsForSafeCloseBoundary() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val entered = CompletableDeferred<Unit>()
        val releaseStart = CountDownLatch(1)
        val closed = AtomicInteger()
        val probes = AtomicInteger()
        try {
            val job = launch {
                runUrlTestLifecycle(pool, prepare = {}, start = {
                    entered.complete(Unit)
                    assertTrue(releaseStart.await(3, TimeUnit.SECONDS))
                }, probe = { probes.incrementAndGet() }, close = { closed.incrementAndGet() })
            }
            entered.await(); job.cancel(); delay(20)
            assertEquals(0, closed.get())
            releaseStart.countDown()
            withTimeout(3000) { job.join() }
            assertEquals(1, closed.get())
            assertEquals(0, probes.get())
        } finally { releaseStart.countDown(); pool.close() }
    }

    @Test fun everyStageFailureStillClosesExactlyOnce() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        try {
            for (stage in 0..2) {
                val closed = AtomicInteger()
                try {
                    runUrlTestLifecycle<Int>(pool,
                        prepare = { if (stage == 0) throw IOException("prepare") },
                        start = { if (stage == 1) throw IOException("start") },
                        probe = { throw IOException("probe") },
                        close = { closed.incrementAndGet() })
                    fail("expected stage failure")
                } catch (_: IOException) { assertEquals(1, closed.get()) }
            }
        } finally { pool.close() }
    }

    @Test fun cancellationWhileQueuedCannotPrepareOrDetachFromParent() = runBlocking {
        val executor = Executors.newFixedThreadPool(1)
        val pool = executor.asCoroutineDispatcher()
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prepared = AtomicInteger()
        try {
            executor.submit { occupied.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(occupied.await(3, TimeUnit.SECONDS))
            val unrelatedJob = Job()
            val job = launch {
                runUrlTestLifecycle<Int>(pool + unrelatedJob,
                    prepare = { prepared.incrementAndGet() }, start = {}, probe = { 1 }, close = {})
            }
            yield(); job.cancel(); release.countDown()
            withTimeout(3000) { job.join() }
            assertEquals(0, prepared.get())
            assertTrue(unrelatedJob.isActive)
            unrelatedJob.cancel()
        } finally { release.countDown(); pool.close() }
    }

    @Test fun cancellationDuringPluginGracePeriodPreventsProbe() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val waiting = CompletableDeferred<Unit>()
        val probes = AtomicInteger()
        val closed = AtomicInteger()
        try {
            val job = launch {
                runUrlTestLifecycle(pool, prepare = {}, start = {}, probe = {
                    waiting.complete(Unit); delay(500)
                    probes.incrementAndGet()
                }, close = { closed.incrementAndGet() })
            }
            waiting.await()
            withTimeout(3000) { job.cancelAndJoin() }
            assertEquals(0, probes.get())
            assertEquals(1, closed.get())
        } finally { pool.close() }
    }

    @Test fun saturatedExecutorStillCancelsEveryBlockingProbe() = runBlocking {
        val pool = Executors.newFixedThreadPool(16).asCoroutineDispatcher()
        val entered = (0 until 16).map { CompletableDeferred<Unit>() }
        val stops = (0 until 16).map { CountDownLatch(1) }
        val closed = AtomicInteger()
        val exited = AtomicInteger()
        try {
            val jobs = (0 until 16).map { index ->
                launch {
                    runUrlTestLifecycle<Int>(pool, prepare = {}, start = {}, probe = {
                        entered[index].complete(Unit)
                        try { assertTrue(stops[index].await(5, TimeUnit.SECONDS)); 1 }
                        finally { exited.incrementAndGet() }
                    }, close = { stops[index].countDown(); delay(10); closed.incrementAndGet() })
                }
            }
            entered.forEach { it.await() }
            jobs.forEach { it.cancel() }
            withTimeout(5000) { jobs.joinAll() }
            assertEquals(16, closed.get())
            assertEquals(16, exited.get())
        } finally { stops.forEach { it.countDown() }; pool.close() }
    }

    @Test fun cancellationWaitsForAlreadyRunningCleanupWithoutClosingTwice() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupExit = CompletableDeferred<Unit>()
        val closed = AtomicInteger()
        try {
            val job = launch {
                runUrlTestLifecycle<Int>(pool, prepare = {}, start = {}, probe = { 1 }, close = {
                    closed.incrementAndGet(); cleanupEntered.complete(Unit); cleanupExit.await()
                })
            }
            cleanupEntered.await(); job.cancel(); delay(20)
            assertFalse(job.isCompleted)
            cleanupExit.complete(Unit)
            withTimeout(3000) { job.join() }
            assertEquals(1, closed.get())
        } finally { cleanupExit.complete(Unit); pool.close() }
    }

    @Test fun noSlotReuseBeforeSlowCleanupAndNoLateResultsOnRapidRestart() = runBlocking {
        val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val alive = AtomicInteger()
        val maxAlive = AtomicInteger()
        val completed = AtomicInteger()
        try {
            repeat(12) {
                val entered = CompletableDeferred<Unit>()
                val stop = CountDownLatch(1)
                val job = launch {
                    runUrlTestLifecycle(pool, prepare = {
                        val count = alive.incrementAndGet()
                        maxAlive.updateAndGet { maxOf(it, count) }
                    }, start = {}, probe = {
                        entered.complete(Unit); assertTrue(stop.await(3, TimeUnit.SECONDS)); 1
                    }, close = { stop.countDown(); delay(5); alive.decrementAndGet() })
                    completed.incrementAndGet()
                }
                entered.await()
                withTimeout(3000) { job.cancelAndJoin() }
                assertEquals(0, alive.get())
            }
            assertEquals(1, maxAlive.get())
            assertEquals(0, completed.get())
        } finally { pool.close() }
    }
}
