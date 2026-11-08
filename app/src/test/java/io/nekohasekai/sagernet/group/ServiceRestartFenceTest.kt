package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.ServiceRestartFence
import org.junit.Assert.*
import org.junit.Test

class ServiceRestartFenceTest {
    @Test fun notificationStopBeatsAlreadyQueuedReloadAndNullStickyRestart() {
        var persisted = 0L
        val f = ServiceRestartFence({ persisted }, { persisted = it })
        val queued = f.snapshot()
        assertTrue(f.permitsAutomatic(queued))
        f.userStop()
        assertFalse(f.permitsAutomatic(queued))
        assertFalse(f.permitsAutomatic())
        assertFalse(f.permitsUserStart(queued))
        val freshConnect = f.snapshot()
        assertTrue(f.admitUserStart(freshConnect))
        assertTrue(f.permitsAutomatic())
        assertFalse(f.permitsAutomatic(queued))
    }
    @Test fun persistentFenceSurvivesServiceAndProcessRecreation() {
        var persisted = 8L
        ServiceRestartFence({ persisted }, { persisted = it }).userStop()
        val fresh = ServiceRestartFence({ persisted }, { persisted = it })
        assertFalse(fresh.permitsAutomatic())
        val queuedUser = fresh.snapshot()
        fresh.userStop()
        assertFalse(fresh.admitUserStart(queuedUser))
        assertTrue(fresh.admitUserStart(fresh.snapshot()))
    }
    @Test fun duplicateStopDuringCleanupStillInvalidatesNewerQueuedRequests() {
        var persisted = 2L
        val f = ServiceRestartFence({ persisted }, { persisted = it })
        f.userStop()
        val pending = f.snapshot()
        f.userStop()
        assertFalse(f.admitUserStart(pending))
        assertFalse(f.permitsAutomatic(pending))
    }
}
