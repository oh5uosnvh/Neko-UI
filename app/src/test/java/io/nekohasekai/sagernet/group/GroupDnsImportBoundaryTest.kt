package io.nekohasekai.sagernet.group

import android.app.Application
import androidx.room.Room
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ui.ProfileBatchStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GroupDnsImportBoundaryTest {
    private val legacy = """
        hosts:
          node.example: [192.0.2.1, '2001:db8::1']
          time.android.com: 192.0.2.2
        dns:
          nameserver-policy: {node.example: 192.0.2.53}
          proxy-server-nameserver: [9.9.9.9]
          direct-nameserver: [8.8.8.8]
    """.trimIndent()

    private fun node(snapshot: String = legacy) = SOCKSBean().apply {
        initializeDefaultValues()
        serverAddress = "node.example"
        subscriptionDns = snapshot
        subscriptionDnsSource = "file"
    }

    private fun settings() = SubscriptionBean().apply {
        initializeDefaultValues()
        serverDnsResolver = "tls://192.0.2.54"
        directDnsResolver = "https://192.0.2.55/dns-query"
        privateDnsConfig = "manual.example: 192.0.2.56"
        hostsConfig = "manual.example: 192.0.2.57"
    }

    @Test fun localAppendAndLegacyMigrationNeverWriteManualFields() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val manual = settings()
            val group = ProxyGroup(type = GroupType.BASIC, name = "Test", subscription = manual)
            group.id = db.groupDao().createGroup(group)
            ProfileBatchStore.append(db, group.id, listOf(node()), "file", "synthetic.yaml")
            val saved = db.groupDao().getById(group.id)!!
            val prepared = GroupDnsStore.prepare(saved, db)
            assertEquals(manual.serverDnsResolver, prepared.serverDnsResolver)
            assertEquals(manual.directDnsResolver, prepared.directDnsResolver)
            assertEquals(manual.privateDnsConfig, prepared.privateDnsConfig)
            assertEquals(manual.hostsConfig, prepared.hostsConfig)
            assertEquals("file", prepared.importedDnsSource)
            val imported = SubscriptionDns.fromDocument(SubscriptionDns.loadDocument(prepared.importedDnsConfig))
            assertEquals(setOf("node.example"), imported.hosts.keys)
            assertTrue(imported.serverDns.isEmpty()); assertTrue(imported.directDns.isEmpty())
            val persistedNode = db.proxyDao().getByGroup(group.id).single().requireBean()
            assertFalse(persistedNode.subscriptionDns.contains("time.android.com"))
            assertFalse(persistedNode.subscriptionDns.contains("proxy-server-nameserver:"))
            assertEquals("file", persistedNode.subscriptionDnsSource)
        } finally { db.close() }
    }

    @Test fun migrationUsesMappedOriginalAndKeepsManualFieldsAndSubscriptionSource() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val manual = settings().apply {
                link = "https://subscription.example/synthetic"
                importedDnsConfig = legacy
                importedDnsSource = "subscription"
            }
            val group = ProxyGroup(type = GroupType.SUBSCRIPTION, name = "Test", subscription = manual)
            group.id = db.groupDao().createGroup(group)
            val bean = node().apply {
                serverAddress = "192.0.2.1"
                mappedOriginalAddress = "node.example"
                mappedTargetAddress = "192.0.2.1"
                subscriptionDnsSource = "subscription"
            }
            db.proxyDao().addProxy(ProxyEntity(groupId = group.id, userOrder = 1).apply { putBean(bean) })
            val prepared = GroupDnsStore.prepare(group, db)
            assertTrue(prepared.importedDnsConfig.contains("node.example"))
            assertFalse(prepared.importedDnsConfig.contains("time.android.com"))
            assertEquals("subscription", prepared.importedDnsSource)
            assertEquals("tls://192.0.2.54", prepared.serverDnsResolver)
            assertEquals("https://192.0.2.55/dns-query", prepared.directDnsResolver)
            // A successful non-YAML refresh clears automatic metadata, but not user values.
            prepared.importedDnsConfig = ""
            val fresh = node("").apply { subscriptionDnsSource = "subscription" }
            assertEquals("", GroupDnsStore.importedForNode(fresh, prepared))
            val config = GroupDnsStore.config(fresh, prepared)
            assertEquals(listOf("tls://192.0.2.54"), config.serverDns)
            assertEquals(setOf("manual.example"), config.hosts.keys)
            assertEquals(setOf("manual.example"), config.policy.keys)
        } finally { db.close() }
    }

    @Test fun explicitNodeSourceDoesNotGetReinterpretedAsLegacyFileMetadata() {
        val bean = node().apply { subscriptionDnsSource = "subscription" }
        val file = settings().apply { importedDnsSource = "file"; importedDnsConfig = "" }
        assertEquals("", GroupDnsStore.importedForNode(bean, file))
        bean.subscriptionDnsSource = ""
        assertEquals(legacy, GroupDnsStore.importedForNode(bean, file))
        bean.subscriptionDnsSource = "file"
        bean.subscriptionDns = ""
        file.importedDnsConfig = legacy
        assertEquals("", GroupDnsStore.importedForNode(bean, file))
    }

    @Test fun snapshotMergeCannotReintroduceOldAutomaticResolverDefaults() {
        val merged = GroupDnsStore.mergeSnapshots(listOf(legacy, "dns: {proxy-server-nameserver: invalid, direct-nameserver: []}"))
        val config = SubscriptionDns.fromDocument(SubscriptionDns.loadDocument(merged))
        assertTrue(config.serverDns.isEmpty()); assertTrue(config.directDns.isEmpty())
        assertEquals(setOf("node.example"), config.policy.keys)
    }
}
