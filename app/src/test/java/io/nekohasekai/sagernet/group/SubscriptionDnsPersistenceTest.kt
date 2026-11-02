package io.nekohasekai.sagernet.group

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDnsPersistenceTest {
    @Test fun oldSubscriptionV3KeepsSettingsAndInitializesNewFields() {
        val data = ByteBufferOutput(1024, -1).apply {
            writeInt(3); writeInt(0); writeString("https://subscription.example/test")
            writeBoolean(true); writeBoolean(true); writeBoolean(false); writeString("agent")
            writeBoolean(true); writeInt(1440); writeInt(12345); writeString("sentinel")
            writeInt(1); writeString("include"); writeString("tls://192.0.2.53")
        }.toBytes()
        val bean = SubscriptionBean().apply { deserializeFromBuffer(ByteBufferInput(data)); initializeDefaultValues() }
        assertEquals("tls://192.0.2.53", bean.serverDnsResolver)
        assertEquals("include", bean.filterRegex)
        assertEquals("", bean.directDnsResolver)
        assertEquals("", bean.privateDnsConfig)
        assertEquals("", bean.hostsConfig)
        assertEquals("", bean.importedDnsConfig)
    }

    @Test fun newSubscriptionRoundTripsSeparateImportedAndManualData() {
        val bean = SubscriptionBean().apply {
            initializeDefaultValues()
            directDnsResolver = "https://dns.example/dns-query"
            privateDnsConfig = "+.private.example: tcp://192.0.2.53:8080"
            hostsConfig = "node.example: target.example"
            importedDnsConfig = "hosts: {node.example: 192.0.2.2}"
        }
        val restored = KryoConverters.deserialize(SubscriptionBean(), KryoConverters.serialize(bean))
        assertEquals(bean.directDnsResolver, restored.directDnsResolver)
        assertEquals(bean.privateDnsConfig, restored.privateDnsConfig)
        assertEquals(bean.hostsConfig, restored.hostsConfig)
        assertEquals(bean.importedDnsConfig, restored.importedDnsConfig)
    }

    @Test fun nodeClonePreservesDnsAndOriginalConnectionIdentity() {
        val bean = SOCKSBean().apply {
            initializeDefaultValues(); serverAddress = "node.example"; serverPort = 443
            subscriptionDns = "hosts: {node.example: target.example}"
        }
        val clone = bean.clone()
        assertEquals(bean.subscriptionDns, clone.subscriptionDns)
        assertEquals("node.example", clone.serverAddress)
        assertEquals(bean, clone)
        clone.subscriptionDns = "hosts: {node.example: other.example}"
        assertNotEquals(bean, clone)
    }

    @Test fun sharedSubscriptionRemainsLegacyV0AndDoesNotExposeDnsCredentials() {
        val bean = SubscriptionBean().apply {
            initializeDefaultValues(); privateDnsConfig = "private-sentinel"; importedDnsConfig = "automatic-sentinel"
        }
        val output = ByteBufferOutput(1024, -1)
        bean.serializeForShare(output)
        assertEquals(0, ByteBufferInput(output.toBytes()).readInt())
        assertFalse(String(output.toBytes()).contains("sentinel"))
    }
}
