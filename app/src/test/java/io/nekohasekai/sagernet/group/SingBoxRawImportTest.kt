package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.*
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.TempDatabase
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SingBoxRawImportTest {
    @Test fun fileClipboardAndSubscriptionSharedParserUseOutboundMode() = runBlocking {
        val text = SingBoxImportFixture.sharedProfile().toString()
        for (fileName in listOf("", "synthetic-profile.json")) {
            val nodes = RawUpdater.parseRaw("\uFEFF$text", fileName)!!
            assertEquals(44, nodes.size)
            assertTrue(nodes.all { it is ConfigBean && it.type == 1 })
            assertTrue(nodes.all { !JSONObject((it as ConfigBean).config).has("outbounds") || JSONObject(it.config).getString("type") == "urltest" })
            assertEquals("node-0", nodes.first().name)
        }
    }

    @Test fun jsonStringsCannotTriggerYamlOrIniHeuristics() = runBlocking {
        val text = """{"type":"future-protocol","tag":"opaque","server":"safe.example.invalid","server_port":443,"opaque":"proxies: [Interface]"}"""
        val bean = RawUpdater.parseRaw(text)!!.single() as ConfigBean
        assertEquals(1, bean.type)
        assertEquals("proxies: [Interface]", JSONObject(bean.config).getString("opaque"))
    }

    @Test fun legacyJsonClashWireguardLinksAndBase64StillImport() = runBlocking {
        assertEquals("ShadowsocksBean", RawUpdater.parseRaw("""{"server":"ss.example.invalid","server_port":443,"method":"aes-128-gcm","password":"synthetic"}""")!!.single().javaClass.simpleName)
        val typed = RawUpdater.parseRaw("""{"type":"shadowsocks","server":"ss.example.invalid","server_port":443,"method":"aes-128-gcm","password":"synthetic","future":true}""")!!.single() as ConfigBean
        assertEquals(1, typed.type); assertTrue(JSONObject(typed.config).getBoolean("future"))
        assertEquals("AnyTLSBean", RawUpdater.parseRaw("proxies:\n  - {name: any, type: anytls, server: any.example.invalid, port: 443, password: synthetic}")!!.single().javaClass.simpleName)
        val wg = "[Interface]\nAddress = 10.0.0.2/32\nPrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n[Peer]\nPublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\nEndpoint = wg.example.invalid:51820\n"
        assertEquals(1, RawUpdater.parseWireGuard(wg).size)
        assertEquals("WireGuardBean", RawUpdater.parseRaw(wg, "vpn.conf")!!.single().javaClass.simpleName)
        val links = "socks5://user:synthetic@node.example.invalid:1080#one\nsocks5://user:synthetic@other.example.invalid:1080#two"
        assertEquals(2, RawUpdater.parseRaw(links)!!.size)
        val encoded = android.util.Base64.encodeToString(links.toByteArray(), android.util.Base64.NO_WRAP)
        assertEquals(2, RawUpdater.parseRaw(encoded)!!.size)
        val jsonStrings = JSONArray().put("""{"type":"socks","tag":"s","server":"node.example.invalid","server_port":1080}""")
        assertEquals(1, RawUpdater.parseJSON(jsonStrings).size)
    }

    @Test fun brokenJsonDependencyIsExplicitAndDoesNotReturnPartialNodes() = runBlocking {
        val text = """{"outbounds":[{"type":"socks","tag":"good","server":"safe.example.invalid","server_port":1080},{"type":"socks","tag":"bad","detour":"sensitive-tag-must-not-leak"}]}"""
        try { RawUpdater.parseRaw(text); fail("Must reject entire import") }
        catch (e: SingBoxJsonImportException) { assertFalse(e.message!!.contains("sensitive-tag")) }
    }

    @Test fun configBeanOldBytesAndNewDependencyRoundTrip() {
        val bean = ConfigBean().applyDefaultValues().apply {
            type = 1; config = """{"type":"socks","tag":"leaf"}"""
            outboundContext = """{"outbounds":[{"type":"socks","tag":"leaf"}]}"""
        }
        val restored = KryoConverters.configDeserialize(KryoConverters.serialize(bean))
        assertEquals(bean.outboundContext, restored.outboundContext)
        assertEquals(bean.config, restored.config); assertEquals(1, restored.type)
        assertEquals(bean.outboundContext, bean.clone().outboundContext)
        // Version-0 payload (ConfigBean.serialize, without Serializable extras).
        val output = ByteBufferOutput(256, -1)
        output.writeInt(0); output.writeString(bean.serverAddress); output.writeInt(bean.serverPort)
        output.writeInt(1); output.writeString(bean.config)
        val legacy = ConfigBean().applyDefaultValues()
        legacy.deserialize(ByteBufferInput(output.toBytes()))
        assertEquals("", legacy.outboundContext); assertEquals(bean.config, legacy.config)
    }

    private class Preferences : KeyValuePair.Dao {
        val values = linkedMapOf<String, KeyValuePair>()
        override fun all() = values.values.toList()
        override fun get(key: String) = values[key]
        override fun put(value: KeyValuePair): Long { values[value.key] = value; return 1L }
        override fun delete(key: String) = if (values.remove(key) == null) 0 else 1
        override fun reset(): Int { val size = values.size; values.clear(); return size }
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
            val field = SagerNet::class.java.getDeclaredField("application").apply { isAccessible = true }
            val oldApp = field.get(null)
            ReflectionHelpers.callInstanceMethod<Void>(SagerNet(), "attachBaseContext", ClassParameter.from(Context::class.java, context))
            restore += { field.set(null, oldApp) }
            for ((type, value) in listOf(SagerDatabase::class.java to db, PublicDatabase::class.java to pref, TempDatabase::class.java to cache)) {
                val lazy = ReflectionHelpers.getStaticField<Any>(type, "instance\$delegate")
                val old = ReflectionHelpers.getField<Any>(lazy, "_value")
                val initializer = ReflectionHelpers.getField<Any?>(lazy, "initializer")
                ReflectionHelpers.setField(lazy, "_value", value); ReflectionHelpers.setField(lazy, "initializer", null)
                restore += { ReflectionHelpers.setField(lazy, "_value", old); ReflectionHelpers.setField(lazy, "initializer", initializer) }
            }
            val groupDao = Proxy.newProxyInstance(ProxyGroup.Dao::class.java.classLoader, arrayOf(ProxyGroup.Dao::class.java)) { _, method, args ->
                when (method.name) { "getById" -> groups[args!![0]]; "allGroups" -> groups.values.toList(); else -> error("Unexpected group DAO ${method.name}") }
            }
            val proxyDao = Proxy.newProxyInstance(ProxyEntity.Dao::class.java.classLoader, arrayOf(ProxyEntity.Dao::class.java)) { _, method, args ->
                when (method.name) { "getById" -> profiles[args!![0]]; "getEntities" -> (args!![0] as List<*>).mapNotNull { profiles[it] }; "getByGroup" -> profiles.values.filter { it.groupId == args!![0] }; else -> error("Unexpected proxy DAO ${method.name}") }
            }
            ReflectionHelpers.setField(db, "_proxyGroup", groupDao); ReflectionHelpers.setField(db, "_proxyEntity", proxyDao)
            val rulesDao = Proxy.newProxyInstance(RuleEntity.Dao::class.java.classLoader, arrayOf(RuleEntity.Dao::class.java)) { _, method, _ ->
                when (method.name) { "enabledRules" -> emptyList<RuleEntity>(); else -> error("Unexpected rules DAO ${method.name}") }
            }
            ReflectionHelpers.setField(db, "_ruleEntity", rulesDao)
            for (store in listOf(DataStore.configurationStore, DataStore.profileCacheStore)) {
                val old = ReflectionHelpers.getField<Any>(store, "kvPairDao")
                ReflectionHelpers.setField(store, "kvPairDao", Preferences())
                restore += { ReflectionHelpers.setField(store, "kvPairDao", old) }
            }
        }
        override fun close() { restore.asReversed().forEach { it() }; cache.close(); pref.close(); db.close() }
    }

    @Test fun realBuilderKeepsDnsTransportPortHoppingAndSelectorDependencies() = runBlocking {
        Environment().use { e ->
            val beans = RawUpdater.parseRaw(SingBoxImportFixture.sharedProfile().toString())!!.map { it as ConfigBean }
            e.groups[1] = ProxyGroup(id = 1, name = "synthetic")
            beans.forEachIndexed { index, bean -> e.profiles[index + 1L] = ProxyEntity(id = index + 1L, groupId = 1).putBean(bean) }
            for (id in listOf(1L, 29L, 44L)) {
                val proxy = e.profiles.getValue(id)
                val before = KryoConverters.serialize(proxy)
                val built = JSONObject(buildConfig(proxy, forTest = true).config)
                val outbounds = built.getJSONArray("outbounds")
                val all = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
                val selected = all.single { it.optString("tag") == proxy.displayName() }
                assertEquals(proxy.configBean!!.type, 1)
                if (id == 1L) assertEquals("/synthetic", selected.getJSONObject("transport").getString("path"))
                if (id == 29L) {
                    assertEquals(2, selected.getJSONArray("server_ports").length())
                    assertEquals("synthetic-pin", selected.getJSONObject("tls").getJSONArray("certificate_public_key_sha256").getString(0))
                }
                if (id == 44L) assertEquals(43, selected.getJSONArray("outbounds").length())
                val dns = built.getJSONObject("dns").getJSONArray("servers")
                val dnsTags = (0 until dns.length()).map { dns.getJSONObject(it).optString("tag") }.toSet()
                val node = if (id == 44L) all.first { it.optString("type") == "vless" } else selected
                assertTrue(dnsTags.contains(node.getString("domain_resolver")))
                assertTrue((0 until dns.length()).any { dns.getJSONObject(it).optString("type") == "batch" })
                assertArrayEquals(before, KryoConverters.serialize(proxy))
                val exported = JSONObject(buildConfig(proxy, forExport = true).config)
                val exportedOutbounds = exported.getJSONArray("outbounds")
                assertTrue((0 until exportedOutbounds.length()).any {
                    exportedOutbounds.getJSONObject(it).optString("tag") == proxy.displayName()
                })
            }
            e.groups.getValue(1).isSelector = true
            val grouped = JSONObject(buildConfig(e.profiles.getValue(1)).config)
            val groupedOut = grouped.getJSONArray("outbounds")
            val groupedTags = (0 until groupedOut.length()).map { groupedOut.getJSONObject(it).getString("tag") }
            assertEquals(groupedTags.size, groupedTags.toSet().size)
            val groupedDns = grouped.getJSONObject("dns").getJSONArray("servers")
            val groupedDnsTags = (0 until groupedDns.length()).map { groupedDns.getJSONObject(it).getString("tag") }
            assertEquals(groupedDnsTags.size, groupedDnsTags.toSet().size)
        }
    }
}
