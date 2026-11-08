package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ui.ToggleStartRequest
import io.nekohasekai.sagernet.bg.ServiceRestartFence
import org.junit.Assert.*
import org.junit.Test

class NotificationStopUiRegressionTest {
    @Test fun connectedOnClickIsNotReplayedWhenNotificationReportsStopped() {
        var epoch = 0L
        val fence = ServiceRestartFence({ epoch }, { epoch = it })
        val ui = ToggleStartRequest()
        ui.request(fence.snapshot())
        assertTrue(ui.pending(fence.snapshot()))
        ui.acknowledgeStarted()
        fence.userStop()
        repeat(20) { assertFalse(ui.pending(fence.snapshot())) }
    }
    @Test fun queuedOnDuringStoppingLosesToANewerNotificationStop() {
        var epoch = 0L
        val fence = ServiceRestartFence({ epoch }, { epoch = it })
        val ui = ToggleStartRequest()
        ui.request(fence.snapshot())
        assertTrue(ui.pending(fence.snapshot()))
        val permissionClick = fence.snapshot()
        fence.userStop()
        assertFalse(ui.pending(fence.snapshot()))
        assertFalse(fence.permitsUserStart(permissionClick))
        ui.request(fence.snapshot())
        assertTrue(ui.pending(fence.snapshot()))
        assertTrue(fence.admitUserStart(fence.snapshot()))
        ui.acknowledgeStarted()
        assertFalse(ui.pending(fence.snapshot()))
    }
    @Test fun rapidOffOnDuringCleanupStillAllowsOneFreshConnect() {
        val ui = ToggleStartRequest()
        ui.request(1)
        ui.cancel()
        assertFalse(ui.pending(1))
        ui.request(-2)
        assertTrue(ui.pending(-2))
        ui.acknowledgeStarted()
        assertFalse(ui.pending(2))
    }
}
