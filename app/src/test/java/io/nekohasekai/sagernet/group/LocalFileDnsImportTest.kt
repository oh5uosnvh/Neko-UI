package io.nekohasekai.sagernet.group

import android.app.Application
import androidx.room.Room
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ui.ProfileBatchStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class LocalFileDnsImportTest {
    private fun node(host:String="a.example",snapshot:String="hosts: {a.example: b.example}") = VMessBean().apply {
        initializeDefaultValues();serverAddress=host;type="ws";security="tls";subscriptionDns=snapshot;subscriptionDnsSource="file"
    }
    @Test fun firstLocalFileNamesBasicGroupAndPersistsMetadataAndMappedNode() {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),SagerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val g=ProxyGroup(type=GroupType.BASIC,name="My group");g.id=db.groupDao().createGroup(g)
            ProfileBatchStore.append(db,g.id,listOf(node()),"file","n'jinli.YAML")
            val saved=db.groupDao().getById(g.id)!!
            assertEquals("n'jinli",saved.name);assertEquals("file",saved.subscription!!.importedDnsSource)
            assertTrue(saved.subscription!!.importedDnsConfig.contains("a.example"))
            val b=db.proxyDao().getByGroup(g.id).single().requireBean()
            assertEquals("b.example",b.serverAddress);assertEquals("a.example",b.mappedOriginalAddress)
            assertEquals("a.example",(b as VMessBean).sni)
            ProfileBatchStore.append(db,g.id,listOf(node("plain.example","")),"file","另一个.JSON")
            assertEquals("n'jinli",db.groupDao().getById(g.id)!!.name)
            val plain=db.proxyDao().getByGroup(g.id).last().requireBean()
            assertFalse(GroupDnsStore.config(plain,saved.subscription).hasNodeConfig())
            assertEquals(2L,db.proxyDao().countByGroup(g.id))
        } finally {db.close()}
    }
    @Test fun buttonActionRestoresAndReappliesAllNodesInsideTransaction() {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),SagerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val g=ProxyGroup(type=GroupType.BASIC,name="My group");g.id=db.groupDao().createGroup(g)
            ProfileBatchStore.append(db,g.id,listOf(node()),"file","哦去咯会哦.TXT")
            val settings=db.groupDao().getById(g.id)!!.subscription!!
            assertEquals("哦去咯会哦",db.groupDao().getById(g.id)!!.name)
            settings.applyHosts=false;assertEquals(1,GroupDnsStore.saveDnsAndMappings(db,g.id,settings))
            assertEquals("a.example",db.proxyDao().getByGroup(g.id).single().requireBean().serverAddress)
            settings.applyHosts=true;assertEquals(1,GroupDnsStore.saveDnsAndMappings(db,g.id,settings))
            assertEquals("b.example",db.proxyDao().getByGroup(g.id).single().requireBean().serverAddress)
        } finally {db.close()}
    }
}
