package io.nekohasekai.sagernet.fmt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorApplyCoalescerTest {
    @Test fun concurrentSubmissionsStartOneWorkerAndKeepNewestRequest() {
        val gate = SelectorApplyCoalescer<Int>()
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val starters = AtomicInteger()
        repeat(8) { index ->
            pool.execute {
                ready.countDown()
                start.await()
                if (gate.submit(index)) starters.incrementAndGet()
            }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(1, starters.get())
        assertTrue(gate.isRunning())
        assertEquals(7, gate.take())
        assertFalse(gate.hasPending())
        assertFalse(gate.finish())
    }

    @Test fun newerRequestKeepsWorkerAliveAfterCurrentCandidateIsDiscarded() {
        val gate = SelectorApplyCoalescer<String>()
        assertTrue(gate.submit("old"))
        assertEquals("old", gate.take())
        assertFalse(gate.submit("new").also { /* worker is already running */ })
        assertTrue(gate.finish())
        assertEquals("new", gate.take())
        assertFalse(gate.finish())
    }
}
