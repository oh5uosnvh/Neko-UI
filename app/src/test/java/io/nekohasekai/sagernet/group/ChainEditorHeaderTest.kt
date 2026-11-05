package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.preference.EditTextPreference
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.widget.EditorHeaderAdapter
import moe.matsuri.nb4a.TempDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w400dp-h700dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class ChainEditorHeaderTest {
    /** Real activities/fragments/stores; only DAO I/O is replaced (no native SQLite). */
    private class Environment : AutoCloseable {
        class Preferences : io.nekohasekai.sagernet.database.preference.KeyValuePair.Dao {
            private val values = java.util.concurrent.ConcurrentHashMap<String, io.nekohasekai.sagernet.database.preference.KeyValuePair>()
            override fun all() = values.values.toList()
            override fun get(key: String) = values[key]
            override fun put(value: io.nekohasekai.sagernet.database.preference.KeyValuePair): Long { values[value.key] = value; return 1L }
            override fun delete(key: String) = if (values.remove(key) == null) 0 else 1
            override fun reset(): Int { val size = values.size; values.clear(); return size }
            override fun insert(list: List<io.nekohasekai.sagernet.database.preference.KeyValuePair>) { list.forEach { put(it) } }
        }
        val entities = java.util.concurrent.ConcurrentHashMap<Long, ProxyEntity>()
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java).allowMainThreadQueries().build()
        val preferences = Room.inMemoryDatabaseBuilder(context, PublicDatabase::class.java).allowMainThreadQueries().build()
        val cache = Room.inMemoryDatabaseBuilder(context, TempDatabase::class.java).allowMainThreadQueries().build()
        val restore = ArrayList<() -> Unit>()
        init {
            val field = SagerNet::class.java.getDeclaredField("application").apply { isAccessible = true }
            val oldApp = field.get(null)
            ReflectionHelpers.callInstanceMethod<Void>(SagerNet(), "attachBaseContext", ClassParameter.from(Context::class.java, context))
            restore += { field.set(null, oldApp) }
            for ((type, value) in listOf(SagerDatabase::class.java to database,
                PublicDatabase::class.java to preferences, TempDatabase::class.java to cache)) {
                val lazy = ReflectionHelpers.getStaticField<Any>(type, "instance\$delegate")
                val old = ReflectionHelpers.getField<Any>(lazy, "_value")
                val initializer = ReflectionHelpers.getField<Any?>(lazy, "initializer")
                ReflectionHelpers.setField(lazy, "_value", value)
                ReflectionHelpers.setField(lazy, "initializer", null)
                restore += { ReflectionHelpers.setField(lazy, "_value", old); ReflectionHelpers.setField(lazy, "initializer", initializer) }
            }
            val proxyDao = java.lang.reflect.Proxy.newProxyInstance(ProxyEntity.Dao::class.java.classLoader,
                arrayOf(ProxyEntity.Dao::class.java)) { _, method, args ->
                when (method.name) {
                    "getEntities" -> (args!![0] as List<*>).mapNotNull { entities[it] }
                    "getById" -> entities[args!![0]]
                    "getAll" -> entities.values.toList()
                    else -> error("Unexpected proxy DAO call: ${method.name}")
                }
            }
            ReflectionHelpers.setField(database, "_proxyEntity", proxyDao)
            val groupDao = java.lang.reflect.Proxy.newProxyInstance(io.nekohasekai.sagernet.database.ProxyGroup.Dao::class.java.classLoader,
                arrayOf(io.nekohasekai.sagernet.database.ProxyGroup.Dao::class.java)) { _, method, _ ->
                when (method.name) {
                    "getById" -> null
                    "allGroups" -> emptyList<io.nekohasekai.sagernet.database.ProxyGroup>()
                    else -> error("Unexpected group DAO call: ${method.name}")
                }
            }
            ReflectionHelpers.setField(database, "_proxyGroup", groupDao)
            for ((store, dao) in listOf(DataStore.configurationStore to Preferences(), DataStore.profileCacheStore to Preferences())) {
                val old = ReflectionHelpers.getField<Any>(store, "kvPairDao")
                ReflectionHelpers.setField(store, "kvPairDao", dao)
                restore += { ReflectionHelpers.setField(store, "kvPairDao", old) }
            }
        }
        override fun close() {
            restore.asReversed().forEach { it() }
            cache.close(); preferences.close(); database.close()
        }
    }

    private fun layout(activity: ChainSettingsActivity) {
        val root = activity.findViewById<View>(android.R.id.content)!!
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 600, 900)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
    }

    private fun await(activity: ChainSettingsActivity, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            layout(activity)
            Thread.yield()
        }
        assertTrue("async preferences/proxies must finish within five seconds", condition())
        layout(activity)
    }

    private fun profiles(environment: Environment, count: Int): List<ProxyEntity> = (1..count).map { n ->
        ProxyEntity(id = n.toLong()).putBean(SOCKSBean().apply {
            initializeDefaultValues(); name = "Proxy $n"; serverAddress = "127.0.0.1"; serverPort = 1080
        }).also { environment.entities[it.id] = it }
    }

    @Test fun asyncFragmentCanInitializeInDetachedHeaderAndSurviveRecyclingAndRefresh() {
        Environment().use { environment ->
            val data = profiles(environment, 80)
            DataStore.serverProtocol = data.joinToString(",") { it.id.toString() }
            DataStore.profileName = "Retained name"
            val controller = Robolectric.buildActivity(ChainSettingsActivity::class.java).create(Bundle()).start().resume().visible()
            val activity = controller.get()
            try {
                val list = activity.configurationList
                val header = activity.findViewById<View>(R.id.chain_settings_header)!!
                val container = activity.findViewById<ViewGroup>(R.id.settings)!!
                val concat = list.adapter as ConcatAdapter
                assertTrue(list.itemAnimator is DefaultItemAnimator)
                activity.proxyList.addAll(data)
                activity.configurationAdapter.notifyDataSetChanged()
                layout(activity)
                assertNotSame("real outer row must be a reusable shell", header, list.findViewHolderForAdapterPosition(0)!!.itemView)
                list.scrollToPosition(80)
                layout(activity)
                list.recycledViewPool.clear()
                assertFalse("header must actually leave the window", header.isAttachedToWindow)
                assertSame("FragmentManager host lookup must still work", container, activity.findViewById(R.id.settings))
                val fragment = ProfileSettingsActivity.MyPreferenceFragmentCompat()
                activity.supportFragmentManager.beginTransaction().replace(R.id.settings, fragment).commit()
                await(activity) { fragment.view != null && activity.proxyList.size == data.size }
                assertSame(container, fragment.requireView().parent)
                assertEquals("Retained name", fragment.findPreference<EditTextPreference>("profileName")!!.text)
                repeat(4) {
                    concat.notifyDataSetChanged()
                    layout(activity)
                    list.recycledViewPool.clear()
                    list.scrollToPosition(0)
                    layout(activity)
                    assertSame(container, activity.findViewById(R.id.settings))
                    assertSame(fragment.requireView(), container.getChildAt(0))
                    assertTrue(header.isAttachedToWindow)
                    assertEquals(1, container.childCount)
                    val shell = header.parent as View
                    val initialY = shell.y
                    list.scrollBy(0, 20)
                    assertTrue("name card must scroll with proxies", shell.y < initialY)
                    list.scrollToPosition(80)
                    layout(activity)
                }
                assertTrue("proxy list must remain virtualized", list.childCount < 30)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun recreationRestoresRealPreferencesAndProxyOrderWithoutManualFirstLayout() {
        Environment().use { environment ->
            val data = profiles(environment, 60)
            val chain = ProxyEntity(id = 1000L).putBean(io.nekohasekai.sagernet.fmt.internal.ChainBean().apply {
                initializeDefaultValues(); name = "Before rotation"; proxies = data.map { it.id }
            })
            environment.entities[chain.id] = chain
            val intent = android.content.Intent(environment.context, ChainSettingsActivity::class.java)
                .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, chain.id)
            // Real cold start: the unmodified base class loads and commits asynchronously.
            val controller = Robolectric.buildActivity(ChainSettingsActivity::class.java, intent).setup().visible()
            var activity = controller.get()
            try {
                await(activity) { activity.child?.view != null && activity.proxyList.size == data.size }
                val fragment = activity.child!!
                fragment.findPreference<EditTextPreference>("profileName")!!.text = "After edit"
                assertTrue(activity.configurationAdapter.move(1, 4))
                activity.configurationAdapter.remove(2)
                val order = activity.proxyList.map { it.id }
                activity.configurationList.scrollToPosition(55)
                layout(activity)
                controller.recreate()
                activity = controller.get()
                await(activity) { activity.child?.view != null && activity.proxyList.size == order.size }
                assertEquals(order, activity.proxyList.map { it.id })
                val restored = activity.child!!
                assertEquals("After edit", restored.findPreference<EditTextPreference>("profileName")!!.text)
                assertSame(activity.findViewById(R.id.settings), restored.requireView().parent)
                activity.configurationList.scrollToPosition(0)
                layout(activity)
                assertTrue(restored.requireView().isAttachedToWindow)
                assertEquals(1, activity.supportFragmentManager.fragments.size)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun concatKeepsLocalProxyIndicesAndNeverReusesOneHolderForTwoRows() {
        Environment().use { environment ->
            val data = profiles(environment, 6)
            val controller = Robolectric.buildActivity(ChainSettingsActivity::class.java).create(Bundle()).start().resume().visible()
            val activity = controller.get()
            try {
                val list = activity.configurationList
                val adapter = activity.configurationAdapter
                val concat = list.adapter as ConcatAdapter
                activity.proxyList.addAll(data)
                adapter.notifyDataSetChanged()
                layout(activity)
                val first = list.findViewHolderForAdapterPosition(2) as ChainSettingsActivity.ProfileHolder
                val fourth = list.findViewHolderForAdapterPosition(5) as ChainSettingsActivity.ProfileHolder
                assertEquals(1, first.bindingAdapterPosition)
                assertEquals(4, fourth.bindingAdapterPosition)
                assertEquals(0, list.findViewHolderForAdapterPosition(1)!!.bindingAdapterPosition)
                val notices = ArrayList<String>()
                concat.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { notices += "move:$fromPosition:$toPosition" }
                    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { notices += "remove:$positionStart" }
                })
                assertTrue(adapter.move(first.bindingAdapterPosition, fourth.bindingAdapterPosition))
                assertEquals(listOf(2L, 3L, 4L, 1L, 5L, 6L), activity.proxyList.map { it.id })
                assertEquals("move:2:5", notices.last())
                adapter.remove(2)
                assertEquals(listOf(2L, 4L, 1L, 5L, 6L), activity.proxyList.map { it.id })
                assertEquals("remove:3", notices.last())
                assertFalse(adapter.move(RecyclerView.NO_POSITION, 1))
                assertFalse(adapter.move(0, 1))
                adapter.remove(RecyclerView.NO_POSITION)
                adapter.remove(0)
                layout(activity)
                val edited = list.findViewHolderForAdapterPosition(3) as ChainSettingsActivity.ProfileHolder
                edited.editButton.performClick()
                assertEquals("selection must not include Concat header offset", 2, activity.replacing)
                val headerAdapter = concat.adapters.first() as EditorHeaderAdapter
                val old = list.findViewHolderForAdapterPosition(0)!!
                val next = headerAdapter.createViewHolder(list, 0)
                assertNotSame("refresh may request a second holder", old, next)
                headerAdapter.bindViewHolder(next, 0)
                assertEquals(1, (next.itemView as ViewGroup).childCount)
                assertEquals(0, (old.itemView as ViewGroup).childCount)
                repeat(4) { concat.notifyDataSetChanged(); layout(activity) }
                assertEquals(5, activity.proxyList.size)
            } finally { controller.pause().stop().destroy() }
        }
    }
}
