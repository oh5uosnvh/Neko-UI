package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.esotericsoftware.kryo.io.ByteBufferInput
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.*
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
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
class CurrentVmessFullConfigTest {
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
            ReflectionHelpers.setField(db,"_proxyGroup",groupDao);ReflectionHelpers.setField(db,"_proxyEntity",proxyDao)
            for (store in listOf(DataStore.configurationStore,DataStore.profileCacheStore)) {
                val old=ReflectionHelpers.getField<Any>(store,"kvPairDao");ReflectionHelpers.setField(store,"kvPairDao",Preferences())
                restore += { ReflectionHelpers.setField(store,"kvPairDao",old) }
            }
        }
        override fun close() { restore.asReversed().forEach { it() }; cache.close();pref.close();db.close() }
    }
    @Test fun completeProbeBuildKeepsWebsocketPathAndAcceptsNativeSerialization() {
        Environment().use { e ->
            val privateInput=System.getenv("NEKO_FULL_BINARY").orEmpty()
            if(privateInput.isNotBlank()) DataInputStream(File(privateInput).inputStream()).use { input ->
                repeat(input.readInt()) { val bytes=ByteArray(input.readInt());input.readFully(bytes);val g=ProxyGroup();g.deserializeFromBuffer(ByteBufferInput(bytes));e.groups[g.id]=g }
                repeat(input.readInt()) { val bytes=ByteArray(input.readInt());input.readFully(bytes);val p=ProxyEntity();p.deserializeFromBuffer(ByteBufferInput(bytes));e.profiles[p.id]=p }
            } else {
                e.groups[1]=ProxyGroup(id=1,name="synthetic")
                e.profiles[1]=ProxyEntity(id=1,groupId=1).putBean(VMessBean().apply { initializeDefaultValues();name="synthetic-vmess";serverAddress="127.0.0.1";serverPort=18080;uuid="00000000-0000-4000-8000-000000000001";type="ws";path="/synthetic";host="synthetic.invalid" })
            }
            val recoverySource=System.getenv("NEKO_RECOVERY_SOURCE").orEmpty().takeIf(String::isNotBlank)?.let {
                kotlinx.coroutines.runBlocking { RawUpdater.parseRaw(File(it).readText()).orEmpty() }
            }
            val nodes=e.profiles.values.filter { (it.requireBean() as? VMessBean)?.let { b -> !b.isVLESS }==true }
            if(recoverySource!=null) {
                nodes.forEachIndexed { index,p ->
                    val recovered=LegacyVmessWsRecovery.replacement(p.vmessBean!!,recoverySource)
                    assertNotNull("Private legacy node ${index+1} could not be uniquely repaired",recovered)
                    p.putBean(recovered!!)
                }
            }
            assertTrue(nodes.isNotEmpty())
            val directory=System.getenv("NEKO_FULL_OUTPUT_DIR").orEmpty().takeIf(String::isNotBlank)?.let { File(it).apply { mkdirs() } }
            nodes.forEachIndexed { index,p ->
                val b=p.requireBean() as VMessBean
                // Real database converter round trip before TestInstance's builder.
                p.putBean(KryoConverters.vmessDeserialize(KryoConverters.serialize(b)))
                val before=KryoConverters.serialize(p)
                val instance=io.nekohasekai.sagernet.bg.proto.TestInstance(p,"http://example.invalid",3000)
                val build=instance.javaClass.getDeclaredMethod("buildConfig").apply { isAccessible=true }
                build.invoke(instance)
                val built=instance.config
                assertTrue("Latency config build mutated the live profile",before.contentEquals(KryoConverters.serialize(p)))
                val json=JSONObject(built.config)
                val out=json.getJSONArray("outbounds")
                val vm=(0 until out.length()).map { out.getJSONObject(it) }.single { it.optString("type")=="vmess" }
                assertTrue("Node ${index+1} full builder changed transport path",vm.getJSONObject("transport").getString("path")==b.path.ifBlank { "/" })
                directory?.let { File(it,"full-${index+1}.json").apply { createNewFile();setReadable(false,false);setReadable(true,true);setWritable(false,false);setWritable(true,true);writeText(built.config) } }
            }
        }
    }
}
