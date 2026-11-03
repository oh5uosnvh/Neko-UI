package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ui.LatencyFailureLabel
import io.nekohasekai.sagernet.widget.TwoColumnProtocolLabel
import org.junit.Assert.*
import org.junit.Test

class Dns09DisplayPolicyTest {
    @Test fun onlyTwoColumnCardsUseRequestedProtocolAliases() {
        val aliases=mapOf("WireGuard" to "WG","ShadowTLS" to "st","Hysteria1" to "hy",
            "Hysteria2" to "hy2","Shadowsocks" to "ss","ShadowsocksR" to "ssr")
        for((full,short) in aliases) {
            assertEquals(short,TwoColumnProtocolLabel.display(full,true))
            assertEquals(full,TwoColumnProtocolLabel.display(full,false))
        }
        assertEquals("hy",TwoColumnProtocolLabel.display("Hysteria",true))
        assertEquals("Trojan",TwoColumnProtocolLabel.display("Trojan",true))
        assertEquals("AnyTLS",TwoColumnProtocolLabel.display("AnyTLS",true))
    }
    @Test fun latencySlotClassifiesButNeverReturnsLongRawLogOrUrl() {
        assertEquals(LatencyFailureLabel.Kind.TIMEOUT,LatencyFailureLabel.kind("Get https://example.test: context deadline exceeded"))
        assertEquals(LatencyFailureLabel.Kind.RESET,LatencyFailureLabel.kind("read tcp: connection reset by peer"))
        assertEquals(LatencyFailureLabel.Kind.UNAVAILABLE,LatencyFailureLabel.kind("reality verification failed; upstream password redacted"))
        assertEquals(LatencyFailureLabel.Kind.TIMEOUT,LatencyFailureLabel.kind("超时"))
    }
}
