package io.nekohasekai.sagernet.bg

import java.io.IOException

/** A node's monotonic budget, created only after its native worker is acquired. */
internal class ProbeDeadline(
    timeoutMillis: Int,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val start = clockNanos()
    private val budgetNanos = timeoutMillis.toLong() * 1_000_000L

    init { require(timeoutMillis > 0) { "invalid probe timeout" } }

    /** Round up sub-millisecond remainder, but never renew the original budget. */
    fun remainingMillis(): Int {
        val remaining = budgetNanos - (clockNanos() - start)
        if (remaining <= 0) throw IOException("probe deadline exceeded")
        return ((remaining + 999_999L) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
