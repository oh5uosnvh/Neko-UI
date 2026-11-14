package io.nekohasekai.sagernet.group

import android.app.Application
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStreamSettings
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.SingBoxOptions.Outbound_VMessOptions
import moe.matsuri.nb4a.SingBoxOptions.V2RayTransportOptions_WebsocketOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class Pre22VmessCompatibilityTest {
    private fun bean() = VMessBean().apply {
        initializeDefaultValues()
        serverAddress = "node.example"
        serverPort = 443
        uuid = "00000000-0000-4000-8000-000000000001"
        type = "ws"
        security = "tls"
        sni = "tls.example"
        // Avoid the unrelated global DataStore in this focused cipher test.
        // TLS's boolean OR short-circuits; production defaults are unchanged.
        allowInsecure = true
    }
    private fun outbound(bean: VMessBean) =
        buildSingBoxOutboundStandardV2RayBean(bean) as Outbound_VMessOptions
    private fun ws(bean: VMessBean) =
        buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_WebsocketOptions
    private fun parse(options: String): VMessBean = runBlocking {
        RawUpdater.parseRaw("""
            proxies:
              - name: generated
                type: vmess
                server: node.example
                port: 443
                uuid: 00000000-0000-4000-8000-000000000001
                alterId: 0
                cipher: auto
                tls: true
                network: ws
        """.trimIndent() + "\n" + options.prependIndent("    "))!!.single() as VMessBean
    }

    @Test fun autoTlsUsesRealAeadCipher() {
        for (cipher in listOf("", "auto", "AUTO")) {
            assertEquals("aes-128-gcm", outbound(bean().apply { encryption = cipher }).security)
        }
    }
    @Test fun explicitCipherIsNotRewritten() {
        for (cipher in listOf("none", "zero", "chacha20-poly1305", "aes-128-gcm")) {
            assertEquals(cipher, outbound(bean().apply { encryption = cipher }).security)
        }
    }
    @Test fun autoWithoutTlsRemainsAuto() {
        assertEquals("auto", outbound(bean().apply { security = "none"; encryption = "auto" }).security)
    }
    @Test fun capitalizedModernWsOptionsAndCustomHeadersReachOutbound() {
        val b = parse("""
            ws-opts:
              Path: /modern
              Headers:
                hOsT: gateway.example
                User-Agent: SyntheticTest/1
                X-Test: generated-value
        """.trimIndent())
        val transport = ws(b)
        assertEquals("/modern", transport.path)
        assertEquals("gateway.example", transport.headers["Host"])
        assertEquals("SyntheticTest/1", transport.headers["User-Agent"])
        assertEquals("generated-value", transport.headers["X-Test"])
    }
    @Test fun legacyWsPathAndHeadersRemainSupported() {
        val transport = ws(parse("""
            ws-path: /legacy
            ws-headers: {Host: legacy.example, X-Legacy: yes-value}
        """.trimIndent()))
        assertEquals("/legacy", transport.path)
        assertEquals("legacy.example", transport.headers["Host"])
        assertEquals("yes-value", transport.headers["X-Legacy"])
    }
    @Test fun caseInsensitiveDuplicateHeadersMergeWithoutLosingOtherFields() {
        val b = parse("""
            ws-headers: {Host: old.example, X-Test: old-value, X-Keep: keep-value}
            ws-opts:
              path: /merged
              headers: {Host: new.example, x-test: new-value}
        """.trimIndent())
        val h = ws(b).headers
        assertEquals("new.example", h["Host"])
        assertEquals("new-value", h["x-test"])
        assertFalse(h.containsKey("X-Test"))
        assertEquals("keep-value", h["X-Keep"])
    }
    @Test fun databaseRoundTripRetainsHeadersTlsAndPath() {
        val original = bean().apply {
            path = "/persisted"
            host = "ws.example"
            wsExtraHeaders = "{\"X-Test\":\"generated-value\"}"
        }
        val copy = KryoConverters.vmessDeserialize(KryoConverters.serialize(original))
        assertEquals(original.wsExtraHeaders, copy.wsExtraHeaders)
        assertEquals(original.sni, copy.sni)
        assertEquals(original.security, copy.security)
        assertEquals(original.path, copy.path)
        assertEquals("generated-value", ws(copy).headers["X-Test"])
    }
    @Test fun earlyDataAndDefaultPathStillWork() {
        assertEquals("/", ws(bean()).path)
        val transport = ws(bean().apply { path = "/ed?ed=2048" })
        assertEquals("/ed", transport.path)
        assertEquals(2048, transport.max_early_data)
        assertEquals("Sec-WebSocket-Protocol", transport.early_data_header_name)
    }
}
