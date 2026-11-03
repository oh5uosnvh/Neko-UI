package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ui.ControlLatencyGate
import org.junit.Assert.*
import org.junit.Test

class ControlLatencyGateTest {
    @Test fun singleInFlightRequestFinishesOnceAndReleasesTheGate() {
        val gate = ControlLatencyGate()
        val request = gate.begin(10, 20)!!
        assertNull(gate.begin(10, 20))
        assertTrue(gate.finish(request, true, 10, 20))
        assertFalse(gate.finish(request, true, 10, 20))
        assertNotNull(gate.begin(10, 20))
    }

    @Test fun profileChangesNeverPublishOldResults() {
        for (ids in listOf(11L to 20L, 10L to 21L, 11L to 21L)) {
            val gate = ControlLatencyGate()
            val request = gate.begin(10, 20)!!
            assertFalse(gate.finish(request, true, ids.first, ids.second))
            assertNotNull(gate.begin(ids.first, ids.second))
        }
    }

    @Test fun disconnectOrInvalidationRejectsOldCompletionAndKeepsNewRequest() {
        val gate = ControlLatencyGate()
        val old = gate.begin(10, 20)!!
        gate.invalidate()
        val fresh = gate.begin(10, 20)!!
        assertFalse(gate.finish(old, true, 10, 20))
        assertNull(gate.begin(10, 20))
        assertTrue(gate.finish(fresh, true, 10, 20))
        val disconnected = gate.begin(10, 20)!!
        assertFalse(gate.finish(disconnected, false, 10, 20))
        assertNotNull(gate.begin(10, 20))
    }

    @Test fun cancellationDestroyAndRepeatedResetAreIdempotent() {
        val gate = ControlLatencyGate()
        val old = gate.begin(10, 20)!!
        repeat(4) { gate.invalidate() }
        assertFalse(gate.finish(old, true, 10, 20))
        val fresh = gate.begin(10, 20)!!
        assertTrue(gate.finish(fresh, true, 10, 20))
    }
}
