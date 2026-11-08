package io.nekohasekai.sagernet.bg

/** Pure, persisted stop fence. Only service-side admission/stop mutates it.
 * Requests carry the snapshot taken when queued, not when eventually delivered.
 * A negative epoch means user-stopped; process death alone never changes it.
 */
class ServiceRestartFence(
    private val readEpoch: () -> Long,
    private val writeEpoch: (Long) -> Unit,
) {
    @Synchronized fun snapshot(): Long = readEpoch()

    @Synchronized fun userStop(): Long {
        val old = readEpoch()
        val magnitude = if (old == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(old)
        val next = if (magnitude == Long.MAX_VALUE) 1L else magnitude + 1L
        return (-next).also(writeEpoch)
    }

    @Synchronized fun permitsAutomatic(epoch: Long? = null): Boolean {
        val current = readEpoch()
        return current >= 0L && (epoch == null || epoch == current)
    }

    @Synchronized fun permitsUserStart(epoch: Long): Boolean = epoch == readEpoch()

    @Synchronized fun admitUserStart(epoch: Long): Boolean {
        if (epoch != readEpoch()) return false
        if (epoch < 0L) writeEpoch(-epoch)
        return true
    }
}
