package io.nekohasekai.sagernet.fmt

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic identity for the database-backed selector graph.
 *
 * It is deliberately cheap to read on the Binder selector hot path. Database
 * writers invalidate it after their transaction; the next normal reload/build
 * captures the new value in ConfigBuildResult. A click never rebuilds a config
 * merely to discover that it is stale.
 */
object SelectorGeneration {
    private val value = AtomicLong(1L)

    fun current(): Long = value.get()

    fun invalidate(): Long = value.incrementAndGet()
}
