package io.nekohasekai.sagernet.fmt

import java.util.concurrent.atomic.AtomicLong

/** Process-local revision for writes made through this process. */
object SelectorGeneration {
    private val value = AtomicLong(1L)

    fun current(): Long = value.get()
    fun invalidate(): Long = value.incrementAndGet()
}

/**
 * Small synchronized latest-wins gate. It deliberately contains no coroutine or
 * Android code, making the coalescing/serialization contract unit-testable.
 */
internal class SelectorApplyCoalescer<E> {
    private var pending: E? = null
    private var running = false

    /** Returns true exactly for the caller which must start the worker. */
    @Synchronized fun submit(value: E): Boolean {
        pending = value
        if (running) return false
        running = true
        return true
    }

    @Synchronized fun take(): E? {
        val result = pending
        pending = null
        return result
    }

    /** Returns true when a newer request arrived while work was running. */
    @Synchronized fun finish(): Boolean {
        if (pending != null) return true
        running = false
        return false
    }

    @Synchronized fun isRunning(): Boolean = running

    @Synchronized fun hasPending(): Boolean = pending != null
}
