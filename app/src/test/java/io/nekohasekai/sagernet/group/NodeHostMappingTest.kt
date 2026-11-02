package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import org.junit.Assert.*
import org.junit.Test

class NodeHostMappingTest {
    private val hosts = mapOf("a.example" to listOf("middle.example"), "middle.example" to listOf("b.example"))
    private fun node() = VMessBean().apply { initializeDefaultValues(); serverAddress="a.example";type="ws";security="tls" }
    @Test fun defaultsMapImmediatelyAndAreIdempotent() {
        val b=node()
        assertTrue(NodeHostMapping.apply(b,hosts,true));assertEquals("b.example",b.serverAddress)
        assertEquals("a.example",b.mappedOriginalAddress);assertEquals("a.example",b.sni);assertEquals("a.example",b.host)
        assertFalse(NodeHostMapping.apply(b,hosts,true));assertEquals("b.example",b.serverAddress)
        assertTrue(NodeHostMapping.apply(b,hosts,false));assertEquals("a.example",b.serverAddress)
        assertEquals("",b.sni);assertEquals("",b.host)
        assertFalse(NodeHostMapping.apply(b,hosts,false))
        assertTrue(NodeHostMapping.apply(b,hosts,true));assertEquals("b.example",b.serverAddress)
    }
    @Test fun explicitIdentityAndLaterUserChangesAreKept() {
        val b=node().apply {sni="cert.example";host="ws.example"}
        NodeHostMapping.apply(b,hosts,true);assertEquals("cert.example",b.sni);assertEquals("ws.example",b.host)
        NodeHostMapping.apply(b,hosts,false);assertEquals("cert.example",b.sni);assertEquals("ws.example",b.host)
        val other=node();NodeHostMapping.apply(other,hosts,true);other.sni="edited.example"
        NodeHostMapping.apply(other,hosts,false);assertEquals("edited.example",other.sni)
    }
    @Test fun sameTargetDoesNotConfuseReverseDirection() {
        val mappings=mapOf("a.example" to listOf("shared.example"),"second.example" to listOf("shared.example"))
        val a=node();val b=node().apply{serverAddress="second.example"}
        NodeHostMapping.apply(a,mappings,true);NodeHostMapping.apply(b,mappings,true)
        NodeHostMapping.apply(a,mappings,false);NodeHostMapping.apply(b,mappings,false)
        assertEquals("a.example",a.serverAddress);assertEquals("second.example",b.serverAddress)
    }
    @Test fun editedServerDoesNotRestoreAnObsoleteOrigin() {
        val b=node();NodeHostMapping.apply(b,hosts,true);b.serverAddress="manually-edited.example"
        NodeHostMapping.apply(b,hosts,false);assertEquals("manually-edited.example",b.serverAddress)
        assertEquals("",b.mappedOriginalAddress);assertEquals("",b.mappedTargetAddress)
    }
    @Test fun modifiedMappingsReapplyFromOriginNotPreviousTarget() {
        val b=node();NodeHostMapping.apply(b,hosts,true)
        NodeHostMapping.apply(b,mapOf("a.example" to listOf("c.example")),true)
        assertEquals("c.example",b.serverAddress);assertEquals("a.example",b.mappedOriginalAddress)
        NodeHostMapping.apply(b,emptyMap(),true);assertEquals("a.example",b.serverAddress);assertEquals("",b.mappedOriginalAddress)
    }
    @Test fun multipleIPsKeepWholeRrsetAvailableForRuntime() {
        val b=node();val h=mapOf("a.example" to listOf("2001:db8::1","192.0.2.1","192.0.2.2"))
        NodeHostMapping.apply(b,h,true);assertEquals("192.0.2.1",b.serverAddress)
        assertTrue(NodeHostMapping.hasMultipleAddresses(b,h));assertEquals(3,h["a.example"]!!.size)
    }
    @Test fun clonePreservesMappingAndRestorableIdentity() {
        val b=TrojanBean().apply{initializeDefaultValues();serverAddress="a.example"}
        NodeHostMapping.apply(b,hosts,true)
        val copy=b.clone();assertEquals(b.mappedOriginalAddress,copy.mappedOriginalAddress)
        assertEquals(b.mappedIdentityFields,copy.mappedIdentityFields)
        NodeHostMapping.apply(copy,hosts,false);assertEquals("a.example",copy.serverAddress);assertEquals("",copy.sni)
        assertEquals("b.example",b.serverAddress)
    }
}
