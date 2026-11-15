package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/** Local plugin readiness, not a second network/URL test or a renewed budget. */
internal suspend fun awaitProbeListeners(
    ports: Collection<Int>,
    deadline: ProbeDeadline,
    checkFailure: () -> Unit = {},
) {
    val pending = ports.toMutableSet()
    while (pending.isNotEmpty()) {
        coroutineContext.ensureActive()
        checkFailure()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            coroutineContext.ensureActive()
            val remaining = deadline.remainingMillis()
            val port = iterator.next()
            val listening = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), minOf(remaining, 100)) }
                true
            } catch (_: IOException) { false }
            if (listening) iterator.remove()
        }
        checkFailure()
        if (pending.isNotEmpty()) delay(minOf(25, deadline.remainingMillis()).toLong())
    }
    deadline.remainingMillis()
}
