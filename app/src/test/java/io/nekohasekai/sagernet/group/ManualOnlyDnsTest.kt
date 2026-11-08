package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test

class ManualOnlyDnsTest {
    private val yaml = """
        hosts: {node.example: 192.0.2.10}
        dns:
          proxy-server-nameserver: [1.1.1.1]
          direct-nameserver: [8.8.8.8]
          nameserver-policy: {'+.private.example': 192.0.2.53}
          proxy-server-nameserver-policy: {node.example: 192.0.2.54}
          default-nameserver: [9.9.9.9]
    """.trimIndent()

    @Test fun oldSavedAndNewSubscriptionYamlCannotPopulateCustomDns() {
        val old = SubscriptionDns.effective(yaml, "", "", "")
        assertTrue(old.serverDns.isEmpty())
        assertTrue(old.directDns.isEmpty())
        val fresh = SubscriptionDns.fromSubscriptionDocument(SubscriptionDns.loadDocument(yaml))
        assertTrue(fresh.serverDns.isEmpty())
        assertTrue(fresh.directDns.isEmpty())
        assertEquals(old.policy, fresh.policy)
        assertEquals(old.serverPolicy, fresh.serverPolicy)
        assertEquals(old.hosts, fresh.hosts)
        assertEquals(listOf("9.9.9.9"), old.bootstrap)
        val auto = GroupDnsUi.automatic(yaml)
        assertEquals("", auto.server)
        assertEquals("", auto.direct)
        assertTrue(auto.privateDns.isNotBlank())
        assertTrue(auto.hosts.isNotBlank())
        assertEquals(GroupDnsUi.Summary.UNSET, GroupDnsUi.state("", auto.server, "subscription", true).summary)
        assertEquals(GroupDnsUi.Summary.UNSET, GroupDnsUi.state("", auto.direct, "subscription", true).summary)
    }
    @Test fun manualServerValueWinsAndPrivateAndHostsBehaviorIsUnchanged() {
        val original = SubscriptionDns.fromDocument(SubscriptionDns.loadDocument(yaml))
        val manual = SubscriptionDns.effective(yaml, "", "", "tls://192.0.2.99:8853")
        assertEquals(listOf("tls://192.0.2.99:8853"), manual.serverDns)
        assertEquals(original.hosts, manual.hosts)
        assertEquals(original.policy, manual.policy)
        assertEquals(original.serverPolicy, manual.serverPolicy)
        assertEquals(GroupDnsUi.Summary.CUSTOM,
            GroupDnsUi.state("8.8.4.4", GroupDnsUi.automatic(yaml).direct, "subscription", true).summary)
    }
    @Test fun ignoredYamlRegularDnsCannotInvalidateUnrelatedPrivateAndHosts() {
        val invalidOrdinary = yaml.replace("[1.1.1.1]", "[not-a-valid-DNS://]")
            .replace("[8.8.8.8]", "{invalid: syntax-for-this-field}")
        val config = SubscriptionDns.effective(invalidOrdinary, "", "", "")
        assertEquals(listOf("192.0.2.10"), config.hosts["node.example"])
        assertEquals(listOf("192.0.2.53"), config.policy["+.private.example"])
        assertTrue(config.serverDns.isEmpty())
        assertTrue(config.directDns.isEmpty())
    }
}
