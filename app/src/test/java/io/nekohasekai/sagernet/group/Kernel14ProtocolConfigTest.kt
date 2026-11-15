package io.nekohasekai.sagernet.group

import android.app.Application
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildSingBoxOutboundNaiveBean
import io.nekohasekai.sagernet.fmt.wireguard.migrateWireGuardEndpoints
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class Kernel14ProtocolConfigTest {
    @Test fun nativeNaiveKeepsTlsIdentityQuicHeadersAndStoredBytes() {
        val bean = NaiveBean().apply {
            initializeDefaultValues(); serverAddress = "192.0.2.10"; sni = "original.example"
            username = "user"; password = "password"; proto = "quic"; sUoT = true
            extraHeaders = "X-Test: value:with:colons\nX-Other: two"; insecureConcurrency = 2
            certificates = "-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----"
        }
        val before = KryoConverters.serialize(bean)
        val options = buildSingBoxOutboundNaiveBean(bean).asMap()
        assertEquals("naive", options["type"])
        assertEquals("192.0.2.10", options["server"])
        assertEquals(true, options["quic"])
        val tls = options["tls"] as Map<*, *>
        assertEquals(true, tls["enabled"]); assertEquals("original.example", tls["server_name"])
        assertEquals(listOf(bean.certificates), tls["certificate"])
        assertEquals("value:with:colons", (options["extra_headers"] as Map<*, *>)["X-Test"])
        assertEquals(true, (options["udp_over_tcp"] as Map<*, *>)["enabled"])
        assertFalse(ProxyEntity().putBean(bean).needExternal())
        assertArrayEquals(before, KryoConverters.serialize(bean))
    }

    @Test fun naiveAlwaysEnablesTlsWithoutOptionalFields() {
        val bean = NaiveBean().apply { initializeDefaultValues() }
        val options = buildSingBoxOutboundNaiveBean(bean).asMap()
        assertEquals(true, (options["tls"] as Map<*, *>)["enabled"])
        assertFalse(options.containsKey("quic")); assertFalse(options.containsKey("extra_headers"))
    }

    @Test fun legacyWireguardBecomesEndpointWithoutBreakingSelectorOrDetourTags() {
        val source = linkedMapOf<String, Any>("type" to "wireguard", "tag" to "wg",
            "server" to "vpn.example", "server_port" to 51820, "local_address" to listOf("10.0.0.2/32"),
            "private_key" to "private", "peer_public_key" to "public", "pre_shared_key" to "psk",
            "reserved" to "Af+A", "detour" to "front", "mtu" to 1400)
        val selector = mapOf("type" to "selector", "tag" to "proxy", "outbounds" to listOf("wg"))
        val config = linkedMapOf<String, Any>("outbounds" to listOf(selector, source),
            "endpoints" to listOf(mapOf("type" to "tailscale", "tag" to "ts")))
        migrateWireGuardEndpoints(config)
        assertEquals(listOf(selector), config["outbounds"])
        val endpoints = config["endpoints"] as List<*>
        assertEquals(2, endpoints.size)
        val wg = endpoints.last() as Map<*, *>
        assertEquals("wg", wg["tag"]); assertEquals("front", wg["detour"])
        assertEquals(listOf("10.0.0.2/32"), wg["address"]); assertFalse(wg.containsKey("server"))
        val peer = (wg["peers"] as List<*>).single() as Map<*, *>
        assertEquals("vpn.example", peer["address"]); assertEquals("public", peer["public_key"])
        assertEquals(listOf(1, 255, 128), peer["reserved"])
        assertEquals(listOf("0.0.0.0/0", "::/0"), peer["allowed_ips"])
        assertEquals("vpn.example", source["server"])
        val once = config.toString(); migrateWireGuardEndpoints(config); assertEquals(once, config.toString())
    }

    @Test fun endpointAndCloudflaredImportsKeepTheCompleteConfiguration() {
        for (type in listOf("openvpn-client", "openconnect", "tailscale", "wireguard")) {
            val json = JSONObject("""{"endpoints":[{"type":"$type","tag":"vpn"}],"outbounds":[{"type":"direct","tag":"direct"}],"dns":{"final":"local"},"route":{"final":"vpn"}}""")
            val bean = RawUpdater.parseJSON(json).single() as ConfigBean
            assertEquals(0, bean.type)
            assertEquals(json.toString(), JSONObject(bean.config).toString())
        }
        val cloudflare = JSONObject("""{"inbounds":[{"type":"cloudflared","tag":"tunnel","token":"fixture"}],"outbounds":[{"type":"direct","tag":"direct"}]}""")
        val bean = RawUpdater.parseJSON(cloudflare).single() as ConfigBean
        assertEquals(0, bean.type); assertTrue(bean.config.contains("cloudflared"))
    }
}
