package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ui.LatencyFailureLabel
import io.nekohasekai.sagernet.widget.TwoColumnProtocolLabel
import org.junit.Assert.*
import org.junit.Test

class Dns09DisplayPolicyTest {
    @Test fun oneAndTwoColumnCardsUseTheSameProtocolText() {
        for (full in listOf("WireGuard", "ShadowTLS", "Hysteria1", "Hysteria2", "Shadowsocks", "ShadowsocksR", "AnyTLS")) {
            assertEquals(full, TwoColumnProtocolLabel.display(full, true))
            assertEquals(full, TwoColumnProtocolLabel.display(full, false))
        }
    }
    @Test fun latencySlotClassifiesButNeverReturnsLongRawLogOrUrl() {
        assertEquals(LatencyFailureLabel.Kind.TIMEOUT,LatencyFailureLabel.kind("Get https://example.test: context deadline exceeded"))
        assertEquals(LatencyFailureLabel.Kind.RESET,LatencyFailureLabel.kind("read tcp: connection reset by peer"))
        assertEquals(LatencyFailureLabel.Kind.UNAVAILABLE,LatencyFailureLabel.kind("reality verification failed; upstream password redacted"))
        assertEquals(LatencyFailureLabel.Kind.TIMEOUT,LatencyFailureLabel.kind("超时"))
    }
}
