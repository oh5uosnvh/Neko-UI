package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.ProbeDeadline
import io.nekohasekai.sagernet.bg.awaitProbeListeners
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

class ProbeListenerReadinessTest {
    @Test fun alreadyListeningPluginDoesNotPayA500msSleep() = runBlocking {
        ServerSocket(0).use { server ->
            val remaining = ProbeDeadline(3000)
            awaitProbeListeners(listOf(server.localPort), remaining)
            assertTrue("ready plugin should retain most of the budget", remaining.remainingMillis() > 2500)
        }
    }

    @Test fun listenerStartingLaterIsWaitedForWithinTheSameBudget() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val server = async(Dispatchers.IO) { delay(40); ServerSocket(port) }
        val deadline = ProbeDeadline(1000)
        try {
            awaitProbeListeners(listOf(port), deadline)
            assertTrue(deadline.remainingMillis() < 1000)
        } finally { server.await().close() }
    }

    @Test fun listenerThatNeverStartsExhaustsOriginalBudget() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        try {
            awaitProbeListeners(listOf(port), ProbeDeadline(50))
            fail("closed listener must not be considered ready")
        } catch (e: IOException) { assertEquals("probe deadline exceeded", e.message) }
    }

    @Test fun cancellationStopsReadinessWithoutWaitingForTimeout() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val job = launch(Dispatchers.IO) { awaitProbeListeners(listOf(port), ProbeDeadline(60_000)) }
        delay(30)
        withTimeout(1000) { job.cancelAndJoin() }
    }

    @Test fun pluginFatalErrorStopsReadinessImmediately() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        try {
            awaitProbeListeners(listOf(port), ProbeDeadline(3000)) { throw IOException("synthetic plugin failure") }
            fail("fatal error should abort")
        } catch (e: IOException) { assertEquals("synthetic plugin failure", e.message) }
    }
}
