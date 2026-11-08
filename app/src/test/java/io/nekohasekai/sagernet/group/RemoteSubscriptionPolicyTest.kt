package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test

class RemoteSubscriptionPolicyTest {
    @Test fun remoteClashRuleSelectorsDoNotRejectNodesOrBecomeCatchAllDns() {
        val doc = SubscriptionDns.loadDocument("""
            dns:
              enable: true
              nameserver-policy:
                'geosite:cn': ['https://dns.example/dns-query']
                'domain:node.example,another.example': ['1.1.1.1']
                'domain-suffix:private.example': ['8.8.8.8']
              proxy-server-nameserver: ['https://dns.example/dns-query#skip-cert-verify=true']
        """.trimIndent())
        val remote = SubscriptionDns.fromSubscriptionDocument(doc)
        assertEquals(setOf("node.example", "another.example", "+.private.example"), remote.policy.keys)
        assertEquals(listOf("https://dns.example/dns-query"), remote.serverDns)
        assertTrue(remote.warnings.any { it.contains("geosite") })
        assertFalse(remote.policy.containsKey("*"))
        assertEquals(remote.policy, SubscriptionDns.fromDocument(SubscriptionDns.loadDocument(remote.snapshot())).policy)
        assertThrows(IllegalArgumentException::class.java) { SubscriptionDns.fromDocument(doc) }
    }

    @Test fun oneUaPerRequestAndOnlyParseSuccessMayTerminateFallback() {
        val candidates = listOf("clash-verge/test", "mihomo/test", "NekoBox/test")
        assertEquals(candidates, SubscriptionUserAgents.attempts("", "", listOf(true, true, true), candidates))
        assertEquals(listOf("mihomo/test", "NekoBox/test"), SubscriptionUserAgents.attempts("", "", listOf(false, true, false), candidates))
        assertEquals(listOf("custom"), SubscriptionUserAgents.attempts("custom", "global", listOf(true, true, true), candidates))
        assertEquals(listOf("global"), SubscriptionUserAgents.attempts("", "global", listOf(true, true, true), candidates))
        assertEquals(listOf("NekoBox/test"), SubscriptionUserAgents.attempts("", "", listOf(false, false, false), candidates))
    }
}
