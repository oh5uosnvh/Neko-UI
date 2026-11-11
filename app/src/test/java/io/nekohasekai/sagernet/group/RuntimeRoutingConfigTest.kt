package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.RuntimeRoutingConfig
import org.junit.Assert.*
import org.junit.Test

class RuntimeRoutingConfigTest {
    private fun config(port: Int = 12001, route: String = "a", dns: String = "one", password: String = "fixture") = mapOf<String, Any?>(
        "inbounds" to listOf(mapOf("type" to "tun", "mtu" to 1500)),
        "outbounds" to listOf(mapOf("type" to "socks", "tag" to "s-1", "server_port" to port, "password" to password)),
        "route" to mapOf("rules" to listOf(mapOf("outbound" to route)), "final" to route, "auto_detect_interface" to true),
        "dns" to mapOf("rules" to listOf(mapOf("server" to dns)), "final" to dns, "servers" to listOf(mapOf("tag" to "one", "type" to "local"))),
    )
    @Test fun routeAndDnsRuleChangesAreHotCompatible() {
        assertTrue(RuntimeRoutingConfig.compatible(config(), emptyMap(), config(route = "b", dns = "two"), emptyMap()))
    }
    @Test fun credentialsOrListenerChangesRequireRebuild() {
        assertFalse(RuntimeRoutingConfig.compatible(config(), emptyMap(), config(password = "different"), emptyMap()))
        assertFalse(RuntimeRoutingConfig.compatible(config(), emptyMap(), config(port = 80), emptyMap()))
        val changed = config() + ("inbounds" to listOf(mapOf("type" to "tun", "mtu" to 9000)))
        assertFalse(RuntimeRoutingConfig.compatible(config(), emptyMap(), changed, emptyMap()))
    }
    @Test fun allocatedPortsNormalizeAndRemapToRunningSockets() {
        assertTrue(RuntimeRoutingConfig.compatible(config(), mapOf(12001 to "external:1"), config(port = 25000), mapOf(25000 to "external:1")))
        val remapped = RuntimeRoutingConfig.remap(config(port = 25000), mapOf(25000 to "external:1"), mapOf(12001 to "external:1"))
        assertEquals(config(), remapped)
    }
    @Test fun dnsTransportMutationIsNeverClaimedAsHotRules() {
        val changed = config() + ("dns" to mapOf("servers" to listOf(mapOf("tag" to "one", "type" to "tls"))))
        assertFalse(RuntimeRoutingConfig.compatible(config(), emptyMap(), changed, emptyMap()))
    }
}
