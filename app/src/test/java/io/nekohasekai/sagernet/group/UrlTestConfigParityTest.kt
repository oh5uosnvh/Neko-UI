package io.nekohasekai.sagernet.group

import android.app.Application
import kotlinx.coroutines.async
import android.content.Context
import androidx.room.Room
import com.esotericsoftware.kryo.io.ByteBufferInput
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.*
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.IPv6Mode
import moe.matsuri.nb4a.TempDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.DataInputStream
import java.io.File
import java.lang.reflect.Proxy

/** Exercises the WHOLE TestInstance builder, not just its outbound helper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UrlTestConfigParityTest {
    private class Preferences : KeyValuePair.Dao {
        val values = linkedMapOf<String, KeyValuePair>()
        override fun all() = values.values.toList()
        override fun get(key: String) = values[key]
        override fun put(value: KeyValuePair): Long { values[value.key] = value; return 1L }
        override fun delete(key: String) = if (values.remove(key) == null) 0 else 1
        override fun reset(): Int { val n=values.size; values.clear(); return n }
        override fun insert(list: List<KeyValuePair>) { list.forEach { put(it) } }
    }
    private class Environment : AutoCloseable {
        val groups = linkedMapOf<Long, ProxyGroup>()
        val profiles = linkedMapOf<Long, ProxyEntity>()
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java).allowMainThreadQueries().build()
        val pref = Room.inMemoryDatabaseBuilder(context, PublicDatabase::class.java).allowMainThreadQueries().build()
        val cache = Room.inMemoryDatabaseBuilder(context, TempDatabase::class.java).allowMainThreadQueries().build()
        val restore = arrayListOf<() -> Unit>()
        init {
            val field=SagerNet::class.java.getDeclaredField("application").apply { isAccessible=true }
            val app=field.get(null)
            ReflectionHelpers.callInstanceMethod<Void>(SagerNet(),"attachBaseContext",ClassParameter.from(Context::class.java,context))
            restore += { field.set(null,app) }
            for ((type,value) in listOf(SagerDatabase::class.java to db, PublicDatabase::class.java to pref, TempDatabase::class.java to cache)) {
                val lazy=ReflectionHelpers.getStaticField<Any>(type,"instance\$delegate")
                val old=ReflectionHelpers.getField<Any>(lazy,"_value")
                val init=ReflectionHelpers.getField<Any?>(lazy,"initializer")
                ReflectionHelpers.setField(lazy,"_value",value);ReflectionHelpers.setField(lazy,"initializer",null)
                restore += { ReflectionHelpers.setField(lazy,"_value",old);ReflectionHelpers.setField(lazy,"initializer",init) }
            }
            val groupDao=Proxy.newProxyInstance(ProxyGroup.Dao::class.java.classLoader,arrayOf(ProxyGroup.Dao::class.java)) { _,m,a ->
                when(m.name) { "getById" -> groups[a!![0]]; "allGroups" -> groups.values.toList(); else -> error("Unexpected group DAO ${m.name}") }
            }
            val proxyDao=Proxy.newProxyInstance(ProxyEntity.Dao::class.java.classLoader,arrayOf(ProxyEntity.Dao::class.java)) { _,m,a ->
                when(m.name) { "getById" -> profiles[a!![0]]; "getEntities" -> (a!![0] as List<*>).mapNotNull { profiles[it] }; "getByGroup" -> profiles.values.filter { it.groupId==a!![0] }; else -> error("Unexpected proxy DAO ${m.name}") }
            }
            val rulesDao=Proxy.newProxyInstance(RuleEntity.Dao::class.java.classLoader,arrayOf(RuleEntity.Dao::class.java)) { _,m,_ ->
                when(m.name) { "enabledRules" -> emptyList<RuleEntity>(); else -> error("Unexpected rules DAO ${m.name}") }
            }
            ReflectionHelpers.setField(db,"_ruleEntity",rulesDao)
            ReflectionHelpers.setField(db,"_proxyGroup",groupDao);ReflectionHelpers.setField(db,"_proxyEntity",proxyDao)
            for (store in listOf(DataStore.configurationStore,DataStore.profileCacheStore)) {
                val old=ReflectionHelpers.getField<Any>(store,"kvPairDao");ReflectionHelpers.setField(store,"kvPairDao",Preferences())
                restore += { ReflectionHelpers.setField(store,"kvPairDao",old) }
            }
        }
        override fun close() { restore.asReversed().forEach { it() }; cache.close();pref.close();db.close() }
    }
    private fun fixture(e: Environment): ProxyEntity {
        DataStore.globalCustomConfig = ""
        DataStore.remoteDns = "https://192.0.2.53/dns-query"
        DataStore.directDns = "local"
        DataStore.enableDnsRouting = true
        DataStore.enableFakeDns = true
        DataStore.globalMode = false
        DataStore.dnsHosts = "app.fixture.example 192.0.2.90"
        e.groups[1] = ProxyGroup(id=1, name="synthetic")
        val p = ProxyEntity(id=1,groupId=1).putBean(TrojanBean().apply {
            initializeDefaultValues(); name="fixture"; serverAddress="node.fixture.example"; serverPort=443
            password="synthetic-password"; sni="sni.fixture.example"; type="ws"; path="/fixture"
            host="ws.fixture.example"; enableMux=true; muxType=1; muxConcurrency=4
        })
        e.profiles[1] = p
        return p
    }

    private fun root(profile: ProxyEntity, forTest: Boolean): JSONObject {
        val snapshot=KryoConverters.deserialize(ProxyEntity(),KryoConverters.serialize(profile))
        return JSONObject(buildConfig(snapshot,forTest=forTest).config)
    }

    private fun objects(root: JSONObject, field: String): List<JSONObject> {
        val array=root.getJSONArray(field)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test fun ipv6ServerStrategyTlsTransportMuxAndFragmentMatchConnectedCore() {
        Environment().use { e ->
            val p=fixture(e)
            DataStore.enableTLSFragment=true
            DataStore.configurationStore.putString("domain_strategy_for_server","ipv6_only")
            for (mode in listOf(IPv6Mode.DISABLE,IPv6Mode.ENABLE,IPv6Mode.PREFER,IPv6Mode.ONLY)) {
                DataStore.ipv6Mode=mode
                val test=root(p,true);val normal=root(p,false)
                val t=objects(test,"outbounds").single { it.optString("type")=="trojan" }
                val n=objects(normal,"outbounds").single { it.optString("type")=="trojan" }
                for (key in listOf("tls","transport","multiplex","detour","domain_strategy")) {
                    assertEquals("test changed $key",n.get(key).toString(),t.get(key).toString())
                }
                assertEquals(normal.getJSONObject("route").getJSONObject("default_domain_resolver").getString("strategy"),
                    test.getJSONObject("route").getJSONObject("default_domain_resolver").getString("strategy"))
            }
        }
    }

    @Test fun testKeepsHostAndScopedSubscriptionDnsRulesWithoutFakeIpOrSharedListeners() {
        Environment().use { e ->
            val p=fixture(e)
            e.groups[1]!!.subscription=SubscriptionBean().apply {
                initializeDefaultValues()
                importedDnsConfig="""
                    hosts: {policy.fixture.example: 192.0.2.91}
                    dns:
                      proxy-server-nameserver: [192.0.2.53]
                      nameserver-policy: {'+.fixture.example': '192.0.2.54'}
                """.trimIndent()
            }
            val test=root(p,true);val normal=root(p,false)
            val dns=test.getJSONObject("dns")
            assertEquals("dns-direct",dns.getString("final"))
            assertTrue("probe should not create application listeners", objects(test,"inbounds").isEmpty())
            assertFalse(test.has("experimental"))
            assertFalse(objects(dns,"servers").any { it.optString("type")=="fakeip" })
            assertTrue(objects(dns,"rules").any { it.optString("server")=="dns-hosts" && it.toString().contains("app.fixture.example") })
            assertTrue("subscription general/hosts policy was dropped",objects(dns,"rules").any { it.has("domain_regex") })
            val t=objects(test,"outbounds").single { it.optString("type")=="trojan" }
            val n=objects(normal,"outbounds").single { it.optString("type")=="trojan" }
            assertEquals(n.getJSONObject("domain_resolver").toString(),t.getJSONObject("domain_resolver").toString())
            val nodeResolver=t.getJSONObject("domain_resolver").getString("server")
            assertTrue(objects(dns,"servers").any { it.optString("tag")==nodeResolver })
        }
    }

    @Test fun thirtySixConcurrentInstancesCannotOverwriteOrDeleteEachOthersPluginFiles() = kotlinx.coroutines.runBlocking {
        Environment().use { e ->
            val profile=fixture(e)
            val directory=File(e.context.cacheDir,"plugin-fixture").apply { mkdirs() }
            val create=io.nekohasekai.sagernet.bg.proto.BoxInstance::class.java
                .getDeclaredMethod("createCacheFile",File::class.java,String::class.java,String::class.java)
                .apply { isAccessible=true }
            val instances=List(36) { io.nekohasekai.sagernet.bg.proto.TestInstance(profile,"http://fixture.invalid",3000) }
            val files=kotlinx.coroutines.coroutineScope {
                instances.mapIndexed { index,instance ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        (create.invoke(instance,directory,"trojan_go_",".json") as File).apply { writeText("synthetic-$index") }
                    }
                }.map { it.await() }
            }
            assertEquals(36,files.map { it.absolutePath }.toSet().size)
            instances.first().close()
            assertFalse(files.first().exists())
            files.drop(1).forEachIndexed { index,file -> assertEquals("synthetic-${index+1}",file.readText()) }
            instances.drop(1).forEach { it.close() }
            assertTrue(files.none { it.exists() })
            directory.delete()
            Unit
        }
    }
}
