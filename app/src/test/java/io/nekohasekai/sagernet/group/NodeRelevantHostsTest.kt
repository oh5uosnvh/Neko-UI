package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Domain mapping display must only contain entries related to node server domains. */
class NodeRelevantHostsTest {

    private val imported = """
        hosts:
          mtalk.google.com: 142.250.157.188
          alt1-mtalk.google.com: 74.125.200.188
          dl.google.com: 180.163.151.161
          dns.alidns.com:
            - 223.5.5.5
            - 223.6.6.6
          doh.pub:
            - 1.12.12.12
            - 120.53.53.53
          laura.akibalon.online: laura.akibalon-cdn.online
          +.akibalon.online: 192.0.2.11
    """.trimIndent()

    private fun importedHosts() =
        SubscriptionDns.fromDocument(SubscriptionDns.loadDocument(imported)).hosts

    @Test fun providerJunkWithoutNodeRelationIsDropped() {
        val auto = GroupDnsUi.automatic(imported, listOf("laura.akibalon.online"))
        assertTrue(auto.hosts.contains("laura.akibalon.online"))
        assertFalse(auto.hosts.contains("mtalk.google.com"))
        assertFalse(auto.hosts.contains("dl.google.com"))
        assertFalse(auto.hosts.contains("dns.alidns.com"))
        assertFalse(auto.hosts.contains("doh.pub"))
    }

    @Test fun exactNodeMappingIsKeptWithItsValue() {
        val hosts = SubscriptionDns.nodeRelevantHosts(
            importedHosts(), listOf("laura.akibalon.online"),
        )
        assertEquals(listOf("laura.akibalon-cdn.online"), hosts["laura.akibalon.online"])
        assertEquals(1, hosts.size)
    }

    @Test fun wildcardCoversNodeDomain() {
        val hosts = SubscriptionDns.nodeRelevantHosts(
            importedHosts(), listOf("clement.akibalon.online"),
        )
        assertEquals(setOf("+.akibalon.online"), hosts.keys)
    }

    @Test fun ipServerAddressesNeverMatchAnything() {
        // An IP-shaped node address must be skipped entirely, and a node whose
        // best match is the exact entry must not drag the wider wildcard in.
        val hosts = SubscriptionDns.nodeRelevantHosts(
            importedHosts(), listOf("laura.akibalon.online", "142.250.157.188"),
        )
        assertEquals(setOf("laura.akibalon.online"), hosts.keys)
        assertFalse(hosts.containsKey("+.akibalon.online"))
    }

    @Test fun explicitEmptyNodeListShowsNoMapping() {
        assertEquals("", GroupDnsUi.automatic(imported, emptyList<String>()).hosts)
    }

    @Test fun nullNodeDomainsKeepsLegacyFullDisplay() {
        val auto = GroupDnsUi.automatic(imported, null)
        assertTrue(auto.hosts.contains("mtalk.google.com"))
        assertTrue(auto.hosts.contains("doh.pub"))
    }

    @Test fun junkOnlySubscriptionShowsEmptyMappingForNodeGroups() {
        val junk = """
            hosts:
              mtalk.google.com: 142.250.157.188
              doh.pub:
                - 1.12.12.12
                - 120.53.53.53
        """.trimIndent()
        assertEquals("", GroupDnsUi.automatic(junk, listOf("laura.akibalon.online")).hosts)
    }
}
