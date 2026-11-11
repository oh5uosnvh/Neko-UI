package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.SubscriptionDns
import io.nekohasekai.sagernet.group.SubscriptionDnsBuilder
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.*
import org.junit.Test

/** No Android SQLite required: inspect the actual emitted DNS transport graph. */
class SelectorDnsPolicyTest {
    private fun settings(server: String = "", direct: String = "", imported: String = "") =
        SubscriptionBean().apply {
            initializeDefaultValues()
            serverDnsResolver = server; directDnsResolver = direct; importedDnsConfig = imported
        }
    private fun config(s: SubscriptionBean) = SubscriptionDns.effective(s.importedDnsConfig,
        s.privateDnsConfig, s.hostsConfig, s.serverDnsResolver)
    @Test fun identicalFallbacksDeduplicateAndDifferentGroupsNeverShareActiveDirectTag() {
        val policy = SelectorDnsPolicy(listOf("udp://8.8.8.8"))
        val a = settings()
        val b = settings(direct = "udp://9.9.9.9")
        val ta = policy.nodeFallback(config(a), a)
        val tb = policy.nodeFallback(config(b), b)
        assertNotEquals(ta, tb)
        assertNotEquals("dns-direct", ta)
        assertEquals(ta, policy.nodeFallback(config(a), a))
        assertEquals(4, policy.servers.size)
        val emitted = policy.servers.map { it.asMap() }
        for (tag in listOf(ta, tb)) {
            val root = emitted.single { it["tag"] == tag }
            val fallback = root["fallback"] as List<*>
            assertEquals(1, fallback.size)
            assertTrue(emitted.any { it["tag"] == fallback.single() })
        }
    }
    @Test fun serverOnlyDifferencesKeepApplicationPolicyCompatible() {
        val policy = SelectorDnsPolicy(listOf("udp://8.8.8.8"))
        val a = settings(server = "udp://1.1.1.1")
        val b = settings(server = "udp://9.9.9.9")
        assertEquals(policy.applicationFingerprint(config(a), a), policy.applicationFingerprint(config(b), b))
        assertNotEquals(policy.applicationFingerprint(config(a), a),
            policy.applicationFingerprint(config(settings(direct = "udp://9.9.9.9")), settings(direct = "udp://9.9.9.9")))
    }
    @Test fun dispatchContainsAllGroupsWithoutUnioningTheirHostsOrResolverFallbacks() {
        val policy = SelectorDnsPolicy(listOf("udp://8.8.8.8"))
        val options = policy.dispatch("dns-direct", mapOf("node-b" to "resolver-b", "node-a" to "resolver-a")).asMap()
        assertEquals("neko-selector", options["type"])
        assertEquals("proxy", options["selector"])
        assertEquals(mapOf("node-a" to "resolver-a", "node-b" to "resolver-b"), options["choices"])
        assertNull(options["hosts"])
        assertNull(options["fallback"])
    }
    @Test fun canonicalNodeGraphKeepsServerPolicyButOrdinaryGraphDropsIt() {
        val policy = SelectorDnsPolicy(listOf("udp://8.8.8.8"))
        val a = settings(imported = "hosts: {b.example: 192.0.2.2, a.example: 192.0.2.1}\ndns: {proxy-server-nameserver: 1.1.1.1}")
        val b = settings(imported = "hosts: {a.example: 192.0.2.1, b.example: 192.0.2.2}\ndns: {proxy-server-nameserver: 1.1.1.1}")
        assertEquals(policy.nodeSubscription(config(a)).importedDnsConfig, policy.nodeSubscription(config(b)).importedDnsConfig)
        assertTrue(SubscriptionDns.fromImportedSnapshot(policy.nodeSubscription(config(a)).importedDnsConfig).hasNodeConfig())
        assertTrue(SubscriptionDns.fromImportedSnapshot(policy.generalSubscription(config(a)).importedDnsConfig).serverDns.isEmpty())
    }
    @Test fun generalPolicyCanonicalizationDropsNodeOnlyFieldsAndYamlOrdering() {
        val policy = SelectorDnsPolicy(listOf("udp://8.8.8.8"))
        val a = settings(imported = "hosts: {a.example: 192.0.2.1, b.example: 192.0.2.2}\ndns: {proxy-server-nameserver: 1.1.1.1}")
        val b = settings(imported = "hosts: {b.example: 192.0.2.2, a.example: 192.0.2.1}\ndns: {proxy-server-nameserver: 9.9.9.9}")
        val ca = config(a); val cb = config(b)
        assertEquals(policy.applicationFingerprint(ca, a), policy.applicationFingerprint(cb, b))
        assertEquals(policy.generalSubscription(ca).importedDnsConfig, policy.generalSubscription(cb).importedDnsConfig)
        val builder = SubscriptionDnsBuilder()
        val bean = SOCKSBean().apply { initializeDefaultValues(); serverAddress = "node.example" }
        val fallback = policy.nodeFallback(ca, a)
        val tag = builder.node(bean, a, fallback)!!
        val node = builder.servers.map { it.asMap() }.single { it["tag"] == tag }
        assertFalse((node["fallback"] as List<*>).contains("dns-direct"))
        assertTrue(builder.general(bean, policy.generalSubscription(ca), "dns-remote").isNotEmpty())
    }
}
