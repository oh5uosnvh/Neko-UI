package io.nekohasekai.sagernet.group

import android.app.Application
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real DB + real builder, not source-text or manufactured fingerprint assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class GlobalSelectorBuildTest {
    private lateinit var env: QuickPopupTestEnvironment
    @Before fun setup() {
        env = QuickPopupTestEnvironment()
        DataStore.globalSelector = true
        DataStore.globalCustomConfig = ""
        DataStore.remoteDns = "https://1.1.1.1/dns-query"
        DataStore.directDns = "https://8.8.8.8/dns-query"
    }
    @After fun cleanup() { env.close() }
    private fun node(groupName: String, name: String): ProxyEntity {
        val group = ProxyGroup(name = groupName)
        group.id = env.database.groupDao().createGroup(group)
        val bean = SOCKSBean().apply {
            initializeDefaultValues(); this.name = name; serverAddress = "192.0.2.1"; serverPort = 1080
        }
        return ProxyEntity(groupId = group.id).apply {
            putBean(bean); id = env.database.proxyDao().addProxy(this)
        }
    }
    @Test fun differentSelectedGroupsProduceTheSameCompiledMembershipAndPolicy() {
        val a = node("A", "same name")
        val b = node("B", "same name")
        val ca = buildConfig(a)
        val cb = buildConfig(b)
        assertTrue(ca.globalSelector)
        assertEquals(setOf(a.id, b.id), ca.profileTagMap.keys)
        assertEquals(ca.profileTagMap, cb.profileTagMap)
        assertEquals(ca.selectorFingerprint, cb.selectorFingerprint)
        DataStore.remoteDns = "https://9.9.9.9/dns-query"
        assertNotEquals(ca.selectorFingerprint, buildConfig(b).selectorFingerprint)
    }
    @Test fun changedMemberInvalidatesRunningPolicy() {
        val a = node("A", "one")
        val b = node("B", "two")
        val old = buildConfig(a).selectorFingerprint
        b.socksBean!!.serverPort = 2080
        env.database.proxyDao().updateProxy(b)
        assertNotEquals(old, buildConfig(a).selectorFingerprint)
    }
}
