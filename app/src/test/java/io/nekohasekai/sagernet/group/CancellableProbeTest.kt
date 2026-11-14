package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.runCancellableProbe
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CancellableProbeTest {
    @Test fun successClosesResourceBeforeReturningToTheBatch() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val closed = AtomicInteger()
        try {
            val result = runCancellableProbe(pool, prepare = {}, close = {
                delay(10); closed.incrementAndGet()
            }, probe = { 50 })
            assertEquals(50, result)
            assertEquals(1, closed.get())
        } finally { pool.close() }
    }

    @Test fun cancellationDuringBlockingNativeProbeClosesAndJoinsBeforeSlotReuse() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val closed = AtomicInteger()
        val exited = AtomicInteger()
        val delivered = AtomicInteger()
        try {
            val job = launch {
                runCancellableProbe(pool, prepare = {}, close = {
                    closed.incrementAndGet(); released.countDown()
                }, probe = {
                    entered.complete(Unit)
                    try { assertTrue(released.await(2, TimeUnit.SECONDS)); 50 }
                    finally { exited.incrementAndGet() }
                })
                delivered.incrementAndGet()
            }
            entered.await()
            withTimeout(2000) { job.cancelAndJoin() }
            assertEquals(1, closed.get())
            assertEquals(1, exited.get())
            assertEquals(0, delivered.get())
        } finally { released.countDown(); pool.close() }
    }

    @Test fun cancellationDuringInitCannotCloseBeforeThenLeakANewResource() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val preparing = CompletableDeferred<Unit>()
        val finishInit = CountDownLatch(1)
        val prepared = AtomicInteger()
        val closed = AtomicInteger()
        val calls = AtomicInteger()
        try {
            val job = launch {
                runCancellableProbe(pool, prepare = {
                    preparing.complete(Unit)
                    assertTrue(finishInit.await(2, TimeUnit.SECONDS))
                    prepared.incrementAndGet()
                }, close = {
                    assertEquals(1, prepared.get())
                    closed.incrementAndGet()
                }, probe = { calls.incrementAndGet(); 50 })
            }
            preparing.await()
            job.cancel()
            delay(20)
            assertEquals(0, closed.get())
            finishInit.countDown()
            withTimeout(2000) { job.join() }
            assertEquals(1, prepared.get())
            assertEquals(1, closed.get())
            assertEquals(0, calls.get())
        } finally { finishInit.countDown(); pool.close() }
    }

    @Test fun blockingStartupExitsOnlyFromIndependentCancelSignal() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val entered = CompletableDeferred<Unit>()
        val interrupted = CountDownLatch(1)
        val released = AtomicInteger()
        val probes = AtomicInteger()
        try {
            val job = launch {
                runCancellableProbe(pool,
                    prepare = {
                        entered.complete(Unit)
                        assertTrue("cancel cannot wait for the startup lock",
                            interrupted.await(2, TimeUnit.SECONDS))
                    },
                    interrupt = { interrupted.countDown() },
                    close = { released.incrementAndGet() },
                    probe = { probes.incrementAndGet(); 50 })
            }
            entered.await()
            withTimeout(2000) { job.cancelAndJoin() }
            assertEquals(1, released.get())
            assertEquals(0, probes.get())
        } finally { interrupted.countDown(); pool.close() }
    }

    @Test fun thrownFailureStillClosesExactlyOnce() = runBlocking {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val closed = AtomicInteger()
        try {
            try {
                runCancellableProbe<Int>(pool, prepare = {}, close = { closed.incrementAndGet() },
                    probe = { throw IOException("context deadline exceeded") })
                fail("wanted failure")
            } catch (_: IOException) { assertEquals(1, closed.get()) }
        } finally { pool.close() }
    }
}
