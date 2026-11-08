package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ui.ToggleStartRequest
import org.junit.Assert.*
import org.junit.Test

class JumpBehaviorTest {
    @Test fun startRequestIsConsumedAndOldStopCannotReplayIt() {
        var epoch = 0L
        val request = ToggleStartRequest()
        request.request(epoch)
        assertTrue(request.pending(epoch))
        request.acknowledgeStarted()
        assertFalse(request.pending(epoch))
        epoch = -1L
        assertFalse(request.pending(epoch))
    }
}
