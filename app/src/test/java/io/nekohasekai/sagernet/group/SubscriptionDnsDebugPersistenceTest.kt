package io.nekohasekai.sagernet.group

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDnsDebugPersistenceTest {
    @Test fun basicGroupNowPersistsAllDnsSettingsAndMappingDirection() {
        val group=ProxyGroup(id=11,type=GroupType.BASIC,name="n'jinli").apply {
            subscription=SubscriptionBean().apply {initializeDefaultValues();serverDnsResolver="1.1.1.1";directDnsResolver="8.8.8.8";privateDnsConfig="+.test: 192.0.2.53";hostsConfig="a.test: b.test";importedDnsSource="file";importedDnsConfig="hosts: {a.test: b.test}";applyHosts=false}
        }
        val restored=KryoConverters.deserialize(ProxyGroup(),KryoConverters.serialize(group))
        assertEquals(group.name,restored.name);assertEquals(GroupType.BASIC,restored.type)
        val s=restored.subscription!!;assertEquals("file",s.importedDnsSource);assertFalse(s.applyHosts)
        assertEquals("1.1.1.1",s.serverDnsResolver);assertEquals("8.8.8.8",s.directDnsResolver)
        assertEquals("+.test: 192.0.2.53",s.privateDnsConfig);assertEquals("a.test: b.test",s.hostsConfig)
    }
    @Test fun v4SubscriptionReadStopsBeforeFollowingGroupFields() {
        val out=ByteBufferOutput(1024,-1).apply {
            writeInt(4);writeInt(0);writeString("https://sub.invalid/list");writeBoolean(false);writeBoolean(false);writeBoolean(false);writeString("")
            writeBoolean(false);writeInt(1440);writeInt(0);writeString("");writeInt(0);writeString("")
            writeString("");writeString("");writeString("");writeString("");writeString("hosts: {a.test: b.test}");writeLong(123456L)
        }
        val input=ByteBufferInput(out.toBytes());val s=SubscriptionBean().apply{deserializeFromBuffer(input);initializeDefaultValues()}
        assertEquals(123456L,input.readLong());assertTrue(s.applyHosts);assertEquals("",s.importedDnsSource)
        assertEquals("hosts: {a.test: b.test}",s.importedDnsConfig)
    }
    @Test fun mappingAndOriginSurviveNodeClone() {
        val b=SOCKSBean().apply {initializeDefaultValues();serverAddress="b.test";subscriptionDnsSource="file";mappedOriginalAddress="a.test";mappedTargetAddress="b.test";mappedIdentityFields="{}"}
        val c=b.clone();assertEquals("file",c.subscriptionDnsSource);assertEquals("a.test",c.mappedOriginalAddress)
        assertEquals("b.test",c.mappedTargetAddress);assertEquals("{}",c.mappedIdentityFields)
    }
    @Test fun fileNodeWithNoPrivateConfigDoesNotBorrowOtherFilesPolicy() {
        val settings=SubscriptionBean().apply{initializeDefaultValues();importedDnsSource="file";importedDnsConfig="hosts: {a.test: b.test}"}
        val plain=SOCKSBean().apply{initializeDefaultValues();subscriptionDnsSource="file";subscriptionDns=""}
        assertEquals("",GroupDnsStore.importedForNode(plain,settings))
        assertFalse(GroupDnsStore.config(plain,settings).hasNodeConfig())
    }
}
