package io.nekohasekai.sagernet.ui

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.*
import org.junit.Test

class BackupCompatibilityTest {
    // Minimal field readers transcribed from release sources (not our current deserializer):
    // MatsuriDayo/NekoBoxForAndroid 1.4.2 (StandardV2Ray v4, Subscription v1)
    // starifly/NekoBoxForAndroid 1.4.2-rev-24 (StandardV2Ray v10, Subscription v3).
    private fun legacyV2RayRead(input: ByteBufferInput, starifly: Boolean, vmess: Boolean): String {
        val version = input.readInt()
        input.readString(); input.readInt(); input.readString(); input.readString()
        if (starifly && version >= 5) input.readString()
        if (vmess) input.readInt()
        when (input.readString()) {
            "ws" -> { input.readString(); input.readString(); input.readInt(); input.readString() }
            "http" -> { input.readString(); input.readString() }
            "grpc" -> input.readString()
        }
        if (input.readString() == "tls") {
            repeat(3) { input.readString() }; input.readBoolean(); repeat(3) { input.readString() }
        }
        input.readBoolean(); input.readString(); input.readInt()
        repeat(2) { input.readBoolean() }; input.readInt(); input.readInt()
        if (starifly && version >= 7) repeat(3) { input.readInt() }
        if (starifly && version >= 8) { input.readBoolean(); input.readInt(); input.readInt() }
        if (!vmess) input.readString() // Trojan password follows its embedded StandardV2Ray.
        input.readInt() // AbstractBean extraVersion; upstream readers do not branch on it.
        val name = input.readString()
        input.readString(); input.readString()
        return name
    }

    @Test fun bothReleaseReadersConsumeSyntheticVmessAndTrojanWithoutFieldDrift() {
        for (starifly in listOf(false, true)) {
            val vmess = VMessBean().apply {
                initializeDefaultValues(); type = "ws"; security = "tls"; name = "vmess-sentinel"
            }
            val trojan = io.nekohasekai.sagernet.fmt.trojan.TrojanBean().apply {
                initializeDefaultValues(); type = "grpc"; security = "tls"; name = "trojan-sentinel"
            }
            for (bean in listOf(vmess, trojan)) {
                val output = ByteBufferOutput(512, -1)
                BackupCompatibility.bean(bean, output)
                val input = ByteBufferInput(output.toBytes())
                if (bean === trojan) assertEquals(2, input.readInt())
                assertEquals(bean.name, legacyV2RayRead(input, starifly, bean === vmess))
                // Only the five safe extra-tail strings remain after the upstream reader.
                repeat(5) { input.readString() }
                assertEquals(output.position(), input.position())
            }
        }
    }

    @Test fun subscriptionProjectionKeepsParentOrderAlignedForBothOldReaders() {
        val sub = SubscriptionBean().apply { initializeDefaultValues(); link = "https://example.invalid/sub" }
        val group = ProxyGroup(id = 42, name = "synthetic", type = GroupType.SUBSCRIPTION,
            subscription = sub, order = 2)
        val output = ByteBufferOutput(512, -1)
        BackupCompatibility.group(group, output)
        val input = ByteBufferInput(output.toBytes())
        assertEquals(0, input.readInt()); assertEquals(42L, input.readLong())
        input.readLong(); input.readBoolean(); assertEquals("synthetic", input.readString())
        assertEquals(GroupType.SUBSCRIPTION, input.readInt())
        // Official v1 reader and starifly v3 reader both consume precisely this v1 layout.
        assertEquals(1, input.readInt()); input.readInt()
        assertEquals(sub.link, input.readString())
        repeat(3) { input.readBoolean() }; input.readString(); input.readBoolean()
        input.readInt(); input.readInt(); input.readString()
        assertEquals(2, input.readInt())
        assertEquals(output.position(), input.position())
        val restored = ProxyGroup().apply { deserializeFromBuffer(ByteBufferInput(output.toBytes())) }
        assertEquals(group.id, restored.id); assertEquals(group.order, restored.order)
        assertEquals(sub.link, restored.subscription!!.link)
    }

    @Test fun vmessProjectionUsesActualOfficialV4LayoutWithoutChangingInternalV11() {
        val bean = VMessBean().apply {
            initializeDefaultValues(); serverAddress = "node.example.invalid"; serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000001"; type = "ws"; security = "tls"
            host = "host.example.invalid"; path = "/synthetic"; name = "synthetic node"
        }
        val before = KryoConverters.serialize(bean)
        val output = ByteBufferOutput(512, -1)
        BackupCompatibility.bean(bean, output)
        val input = ByteBufferInput(output.toBytes())
        assertEquals(4, input.readInt()); assertEquals(bean.serverAddress, input.readString())
        assertEquals(443, input.readInt()); assertEquals(bean.uuid, input.readString())
        assertEquals(bean.encryption, input.readString()); assertEquals(bean.alterId.toInt(), input.readInt())
        assertEquals("ws", input.readString()); assertEquals(bean.host, input.readString())
        assertEquals(bean.path, input.readString()); input.readInt(); input.readString()
        assertEquals("tls", input.readString())
        repeat(3) { input.readString() }; input.readBoolean(); repeat(3) { input.readString() }
        input.readBoolean(); input.readString(); input.readInt(); repeat(2) { input.readBoolean() }
        input.readInt(); input.readInt()
        assertEquals(3, input.readInt()); assertEquals(bean.name, input.readString())
        input.readString(); input.readString()
        // New DNS/mapping metadata is a safe, length-delimited tail, never inserted mid-stream.
        repeat(5) { input.readString() }; assertEquals(output.position(), input.position())
        val restored = VMessBean().apply { deserializeFromBuffer(ByteBufferInput(output.toBytes())) }
        assertEquals(bean.uuid, restored.uuid); assertEquals(bean.path, restored.path)
        assertEquals(bean.name, restored.name)
        assertArrayEquals(before, KryoConverters.serialize(bean))
    }

    @Test fun groupExtensionsProjectWithoutMutationButUnknownTransportsNeverSilentlyChange() {
        val group = ProxyGroup(type = GroupType.SUBSCRIPTION, subscription = SubscriptionBean().apply {
            initializeDefaultValues(); serverDnsResolver = "tls://192.0.2.1"
        })
        assertTrue(BackupCompatibility.hasGroupExtensions(group))
        val out = ByteBufferOutput(512, -1)
        BackupCompatibility.group(group, out)
        val projected = ProxyGroup().apply { deserializeFromBuffer(ByteBufferInput(out.toBytes())) }
        assertTrue(projected.subscription!!.serverDnsResolver.isNullOrBlank())
        assertEquals("tls://192.0.2.1", group.subscription!!.serverDnsResolver)
        val bean = VMessBean().apply { initializeDefaultValues(); type = "kcp" }
        try { BackupCompatibility.bean(bean, ByteBufferOutput(512, -1)); fail("must reject unsupported transport") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("transport")) }
    }

    @Test fun tagSettingsAreExcludedWithoutDroppingUnrelatedPreferences() {
        listOf("tagGroups", "tag_groups_json", "tag_memberships", "groupTagStore", "selectedTagId",
            "current_tag", "tagOrder").forEach { assertTrue(it, BackupCompatibility.isTagSetting(it)) }
        listOf("selectedGroup", "serverAddress", "globalCustomConfig", "webdavPath", "routingTag")
            .forEach { assertFalse(it, BackupCompatibility.isTagSetting(it)) }
    }
}
