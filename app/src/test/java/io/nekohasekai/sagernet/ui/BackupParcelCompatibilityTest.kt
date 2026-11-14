package io.nekohasekai.sagernet.ui

import android.os.Parcel
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class BackupParcelCompatibilityTest {
    @Test fun wsHeadersUseOriginalCustomOutboundSlotAndRespectCustomOverride() {
        val bean = VMessBean().apply {
            initializeDefaultValues(); type = "ws"; host = "host.example.invalid"
            wsExtraHeaders = """{"Host":"ignored.example.invalid","X-Synthetic":"added","X-Override":"base"}"""
            customOutboundJson = """{"transport":{"headers":{"X-Override":"custom"}},"tcp_fast_open":true}"""
        }
        val before = KryoConverters.serialize(bean)
        val out = com.esotericsoftware.kryo.io.ByteBufferOutput(512, -1)
        BackupCompatibility.bean(bean, out)
        val restored = VMessBean().apply {
            deserializeFromBuffer(com.esotericsoftware.kryo.io.ByteBufferInput(out.toBytes()))
            initializeDefaultValues()
        }
        val custom = JSONObject(restored.customOutboundJson)
        val headers = custom.getJSONObject("transport").getJSONObject("headers")
        assertEquals("added", headers.getString("X-Synthetic"))
        assertEquals("custom", headers.getString("X-Override"))
        assertFalse(headers.has("Host"))
        assertTrue(custom.getBoolean("tcp_fast_open"))
        assertEquals(bean.host, restored.host)
        assertEquals("", restored.wsExtraHeaders)
        assertArrayEquals(before, KryoConverters.serialize(bean))
    }

    @Test fun originalRuleRoundTripUsesOriginalParcelOrder() {
        val rule = RuleEntity(id = 42, name = "synthetic", config = "{}", userOrder = 7,
            enabled = true, domains = "example.invalid", outbound = -1,
            packages = setOf("invalid.synthetic.package"))
        val decoded = BackupCompatibility.decodeRule(BackupCompatibility.encode(rule))
        assertEquals(rule, decoded)
    }

    @Test fun stariflyRuleLayoutImportsWithoutReadingRulesetAsOutbound() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeLong(42); parcel.writeString("synthetic"); parcel.writeString("{}")
            parcel.writeLong(7); parcel.writeInt(1)
            repeat(7) { parcel.writeString("") }
            parcel.writeString("synthetic-ruleset"); parcel.writeLong(-2)
            parcel.writeInt(1); parcel.writeString("invalid.synthetic.package")
            val decoded = BackupCompatibility.decodeRule(Util.b64EncodeUrlSafe(parcel.marshall()))
            assertEquals(42, decoded.id); assertEquals(-2, decoded.outbound)
            assertEquals("synthetic-ruleset", decoded.ruleset)
            assertEquals(setOf("invalid.synthetic.package"), decoded.packages)
        } finally { parcel.recycle() }
    }
}
