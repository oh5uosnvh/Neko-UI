package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficUpdaterTest {
    @Test
    fun disabledStatisticsKeepLiveCountersButDoNotAdvanceProfileCounters() {
        val item = TrafficUpdater.TrafficLooperData(tag = "proxy", tx = 100, rx = 200)

        applyTrafficDelta(item, rx = 30, tx = 40, profileStatisticsEnabled = true)
        applyTrafficDelta(item, rx = 50, tx = 60, profileStatisticsEnabled = false)
        applyTrafficDelta(item, rx = 7, tx = 8, profileStatisticsEnabled = true)

        assertEquals(287, item.rx)
        assertEquals(208, item.tx)
        assertEquals(237, item.profileRx)
        assertEquals(148, item.profileTx)
    }

    @Test
    fun profileCountersStartAtExistingPersistedTraffic() {
        val item = TrafficUpdater.TrafficLooperData(tag = "proxy", tx = 123, rx = 456)
        assertEquals(456, item.profileRx)
        assertEquals(123, item.profileTx)
    }
}
