package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDnsBuilderTest {
    private fun bean(snapshot: String = "") = SOCKSBean().apply {
        initializeDefaultValues(); serverAddress = "node.private.example"; subscriptionDns = snapshot
    }
    private fun subscription(imported: String) = SubscriptionBean().apply {
        initializeDefaultValues(); importedDnsConfig = imported
    }
    @Test fun sameDomainDifferentSubscriptionGetsIndependentResolvers() {
        val builder = SubscriptionDnsBuilder()
        val a = builder.node(bean(), subscription("hosts: {node.private.example: 192.0.2.1}"))
        val b = builder.node(bean(), subscription("hosts: {node.private.example: 192.0.2.2}"))
        assertNotNull(a); assertNotEquals(a, b)
        assertEquals(a, builder.node(bean(), subscription("hosts: {node.private.example: 192.0.2.1}")))
        assertEquals(2, builder.servers.size)
    }
    @Test fun fileImportCarriesSnapshotButUpdatedSubscriptionCanRemoveIt() {
        val builder = SubscriptionDnsBuilder()
        val profile = bean("hosts: {node.private.example: 192.0.2.1}")
        assertNotNull(builder.node(profile, null))
        assertNull(builder.node(profile, subscription("")))
    }
    @Test fun policyHasBothServersAndBootstrapDoesNotPointBackToPrivateResolver() {
        val builder = SubscriptionDnsBuilder()
        val subscription = subscription("""
            dns:
              default-nameserver: [223.5.5.5]
              nameserver-policy:
                +.private.example: [tcp://resolver.example:8080, https://192.0.2.53/dns-query]
        """.trimIndent())
        val tag = builder.node(bean(), subscription)
        val configs = builder.servers.map { it.asMap() }
        val root = configs.first { it["tag"] == tag }
        val rules = root["rules"] as List<*>
        assertEquals(2, ((rules.single() as Map<*, *>)["servers"] as List<*>).size)
        val tcp = configs.first { it["type"] == "neko-tcp" }
        assertEquals("resolver.example", tcp["server"])
        assertEquals(8080.0, (tcp["server_port"] as Number).toDouble(), 0.0)
        assertNotEquals(tag, tcp["domain_resolver"])
        assertEquals(listOf("dns-direct"), root["fallback"])
    }
    @Test fun serverPolicyNeverBecomesGeneralApplicationDnsRule() {
        val builder = SubscriptionDnsBuilder()
        val subscription = subscription("dns: {proxy-server-nameserver-policy: {'+.private.example': '192.0.2.53'}}")
        assertTrue(builder.general(bean(), subscription, "dns-remote").isEmpty())
        assertNotNull(builder.node(bean(), subscription))
    }
    @Test fun generalPolicyOnlyMatchesConfiguredDomains() {
        val builder = SubscriptionDnsBuilder()
        val subscription = subscription("dns: {nameserver-policy: {'+.private.example': '192.0.2.53'}}")
        val rules = builder.general(bean(), subscription, "dns-remote")
        assertEquals(1, rules.size)
        val pattern = rules.single().domain_regex.single().toRegex()
        assertTrue(pattern.matches("private.example")); assertTrue(pattern.matches("a.private.example"))
        assertFalse(pattern.matches("notprivate.example")); assertFalse(pattern.matches("example.org"))
    }
    @Test fun serverAddressSwapNeverDisablesHostsRules() {
        val builder = SubscriptionDnsBuilder()
        val settings = subscription("hosts: {node.private.example: target.example}")
        val profile = bean()
        NodeHostMapping.apply(profile, builder.config(profile, settings).hosts, true)
        assertEquals("target.example", profile.serverAddress)
        val replacedTag = builder.node(profile, settings)
        settings.applyHosts = false
        NodeHostMapping.apply(profile, builder.config(profile, settings).hosts, false)
        assertEquals("node.private.example", profile.serverAddress)
        val originalTag = builder.node(profile, settings)
        assertNotNull(originalTag)
        assertEquals(replacedTag, originalTag)
        val resolver = builder.servers.first { it.asMap()["tag"] == originalTag }.asMap()
        assertEquals(listOf("target.example"), (resolver["hosts"] as Map<*, *>)["node.private.example"])
        assertEquals(listOf("dns-direct"), resolver["fallback"])
    }

    @Test fun directOverrideUsesAllServersAndNeverChangesNodeIdentity() {
        val builder = SubscriptionDnsBuilder()
        assertEquals("dns-direct", builder.direct(listOf("223.5.5.5", "tls://1.1.1.1")))
        val root = builder.servers.last().asMap()
        assertEquals(2, (root["fallback"] as List<*>).size)
        val node = bean("hosts: {node.private.example: target.example}")
        builder.node(node, null)
        assertEquals("node.private.example", node.serverAddress)
    }
}
