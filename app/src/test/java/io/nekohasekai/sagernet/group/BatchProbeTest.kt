package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.runBatchProbe
import io.nekohasekai.sagernet.bg.runCancellableProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BatchProbeTest {
    @Test(timeout = 5000) fun callbackStreamsCompletionOrderAndMonotonicProgress() = runBlocking {
        val started = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        val order = CopyOnWriteArrayList<Int>()
        val errors = CopyOnWriteArrayList<String>()
        val progress = CopyOnWriteArrayList<Int>()
        val callbackLock = Mutex()

        runBatchProbe(
            profiles = listOf(1, 2, 3, 4),
            concurrency = 2,
            probe = { profile ->
                when (profile) {
                    1 -> {
                        if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                        bothStarted.await()
                        releaseSlow.await()
                        10
                    }
                    2 -> {
                        if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                        20
                    }
                    3 -> 30
                    4 -> {
                        releaseFailure.await()
                        throw IOException("synthetic timeout")
                    }
                    else -> error("unexpected profile")
                }
            },
            onResult = { result ->
                callbackLock.withLock {
                    order += result.profile
                    progress += order.size
                    result.error?.message?.let(errors::add)
                    when (result.profile) {
                        3 -> releaseSlow.complete(Unit)
                        1 -> releaseFailure.complete(Unit)
                    }
                }
            },
        )

        assertEquals(listOf(2, 3, 1, 4), order.toList())
        assertEquals(listOf(1, 2, 3, 4), progress.toList())
        assertEquals(4, order.size)
        assertTrue(order.contains(4))
        assertEquals(listOf("synthetic timeout"), errors.toList())
    }

    @Test(timeout = 5000) fun failureIsPublishedBeforeSlowSuccessAndWorkerContinues() = runBlocking {
        val releaseSlow = CompletableDeferred<Unit>()
        val order = CopyOnWriteArrayList<Int>()
        val progress = CopyOnWriteArrayList<Int>()
        val lock = Any()
        runBatchProbe(listOf(1, 2, 3), concurrency = 2, probe = { id ->
            when (id) {
                1 -> { releaseSlow.await(); 42 }
                2 -> throw IOException("timeout")
                else -> 99
            }
        }) { result ->
            synchronized(lock) {
                order += result.profile
                progress += order.size
                if (result.profile == 2) {
                    assertEquals("timeout", result.error?.message)
                    assertEquals(null, result.latency)
                }
                if (result.profile == 3) releaseSlow.complete(Unit)
            }
        }
        assertEquals(listOf(2, 3, 1), order.toList())
        assertEquals(listOf(1, 2, 3), progress.toList())
    }

    @Test(timeout = 5000) fun cancellationIsNeverDeliveredAsFailure() = runBlocking {
        var callbacks = 0
        try {
            runBatchProbe(listOf(1), probe = { throw CancellationException("cancel") }) { callbacks++ }
            fail("cancellation must escape")
        } catch (_: CancellationException) { }
        assertEquals(0, callbacks)
    }

    @Test(timeout = 5000) fun callbackCancellationCannotBecomeSuccessfulBatchReturn() = runBlocking {
        var callbacks = 0
        try {
            runBatchProbe(listOf(1, 2), concurrency = 1, probe = { 20 }) {
                callbacks++
                throw CancellationException("callback cancelled")
            }
            fail("callback cancellation must escape")
        } catch (_: CancellationException) { }
        assertEquals(1, callbacks)
    }

    @Test(timeout = 5000) fun callbackFailureIsNotReplayedAsASecondProbeFailure() = runBlocking {
        var callbacks = 0
        try {
            runBatchProbe(listOf(1, 2), concurrency = 1, probe = { 20 }) {
                callbacks++
                throw IOException("callback failure")
            }
            fail("callback failure must escape to the structured owner")
        } catch (failure: IOException) {
            assertEquals("callback failure", failure.message)
        }
        assertEquals(1, callbacks)
    }

    @Test(timeout = 5000) fun cancellationDropsLateNativeResultsAndJoinsEveryWorker() = runBlocking {
        val pool = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val exits = AtomicInteger()
        val callbacks = AtomicInteger()
        val starts = AtomicInteger()
        val closed = AtomicInteger()
        try {
            val batch = launch {
                runCancellableProbe(pool, prepare = {}, interrupt = { release.countDown() }, close = {
                    closed.incrementAndGet()
                }, probe = {
                    runBatchProbe((1..94).toList(), concurrency = 2, probe = { id ->
                        starts.incrementAndGet()
                        entered.countDown()
                        try {
                            check(release.await(2, TimeUnit.SECONDS))
                            if (id == 2) throw IOException("late native failure")
                            20
                        } finally { exits.incrementAndGet() }
                    }) { callbacks.incrementAndGet() }
                })
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            withTimeout(2000) { batch.cancelAndJoin() }
            assertEquals(2, starts.get())
            assertEquals(2, exits.get())
            assertEquals(0, callbacks.get())
            assertEquals(1, closed.get())
        } finally {
            release.countDown()
            pool.close()
        }
    }

    @Test(timeout = 5000) fun ninetyFourResultsArriveBeforeFinalBatchReturnWithoutReplay() = runBlocking {
        val progress = CopyOnWriteArrayList<Int>()
        val lock = Any()
        var returned = false
        var successful = 0
        var failed = 0
        runBatchProbe((1..94).toList(), probe = { id ->
            if (id > 90) throw IOException("timeout")
            id
        }) { result ->
            synchronized(lock) {
                assertFalse("callbacks cannot be replayed after doTest returns", returned)
                progress += progress.size + 1
                if (result.error == null) successful++ else failed++
            }
        }
        returned = true
        assertTrue(returned)
        assertEquals((1..94).toList(), progress.toList())
        assertEquals(90, successful)
        assertEquals(4, failed)
    }
}
