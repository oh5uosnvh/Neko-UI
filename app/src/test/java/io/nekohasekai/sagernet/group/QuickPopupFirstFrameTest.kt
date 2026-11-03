package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentHostCallback
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.Room
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.GroupOrderWriter
import io.nekohasekai.sagernet.ui.GroupPickerAdapter
import io.nekohasekai.sagernet.ui.GroupPickerRecyclerView
import io.nekohasekai.sagernet.ui.TopBarController
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.TempDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

/** Only in-memory Room stores; no real app onCreate, service, or production database. */
internal class QuickPopupTestEnvironment : AutoCloseable {
    private val context = RuntimeEnvironment.getApplication()
    val database = Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java)
        .allowMainThreadQueries().build()
    private val preferences = Room.inMemoryDatabaseBuilder(context, PublicDatabase::class.java)
        .allowMainThreadQueries().build()
    private val cache = Room.inMemoryDatabaseBuilder(context, TempDatabase::class.java)
        .allowMainThreadQueries().build()
    private val restore = ArrayList<() -> Unit>()

    init {
        val appField = SagerNet::class.java.getDeclaredField("application").apply { isAccessible = true }
        val previousApp = appField.get(null)
        // attachBaseContext sets the resource context, but deliberately never calls native onCreate.
        ReflectionHelpers.callInstanceMethod<Void>(SagerNet(), "attachBaseContext",
            ClassParameter.from(Context::class.java, context))
        restore += { appField.set(null, previousApp) }
        install(SagerDatabase::class.java, database)
        install(PublicDatabase::class.java, preferences)
        install(TempDatabase::class.java, cache)
        // A reused sandbox may already have DataStore delegates; replace their DAO, not the delegates.
        for ((store, dao) in listOf(DataStore.configurationStore to preferences.keyValuePairDao(),
            DataStore.profileCacheStore to cache.profileCacheDao())) {
            val old = ReflectionHelpers.getField<Any>(store, "kvPairDao")
            ReflectionHelpers.setField(store, "kvPairDao", dao)
            restore += { ReflectionHelpers.setField(store, "kvPairDao", old) }
        }
    }

    private fun install(type: Class<*>, value: Any) {
        val lazy = ReflectionHelpers.getStaticField<Any>(type, "instance\$delegate")
        val old = ReflectionHelpers.getField<Any>(lazy, "_value")
        val initializer = ReflectionHelpers.getField<Any?>(lazy, "initializer")
        ReflectionHelpers.setField(lazy, "_value", value)
        ReflectionHelpers.setField(lazy, "initializer", null)
        restore += {
            ReflectionHelpers.setField(lazy, "_value", old)
            ReflectionHelpers.setField(lazy, "initializer", initializer)
        }
    }

    fun attach(fragment: Fragment, context: Context) {
        ReflectionHelpers.setField(fragment, "mHost",
            object : FragmentHostCallback<Context>(context, Handler(Looper.getMainLooper()), 0) {
                override fun onGetHost() = context
            })
    }

    /** Skip unrelated adapter initial loaders; real holders, adapter operations and session still run. */
    fun <T> allocate(type: Class<T>): T {
        val typeUnsafe = Class.forName("sun.misc.Unsafe")
        val unsafe = typeUnsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        return typeUnsafe.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    override fun close() {
        restore.asReversed().forEach { it() }
        cache.close()
        preferences.close()
        database.close()
    }
}

/** Render the real PickerSession once BEFORE draining posted tasks, not just its eventual position. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class QuickPopupFirstFrameTest {
    @Before fun pauseFrames() {
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ShadowChoreographer.setPaused(true)
    }

    @After fun restoreFrames() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }

    private class Fixture(night: Boolean = false) : AutoCloseable {
        val environment = QuickPopupTestEnvironment()
        private val activityController = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = activityController.get()
        val context = ContextThemeWrapper(activity.createConfigurationContext(
            Configuration(activity.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }), R.style.Theme_SagerNet_Blue)
        // IDs are deliberately not positions: the selected ID is the 20th row (index 19).
        val groups = (0..39).map { ProxyGroup(id = 700L + it * 13L, userOrder = it + 1L, name = "Group $it") }
        val host = ConfigurationFragment()
        val topBar = TopBarController(host)
        val anchor = TextView(context).apply { text = "groups" }
        val session: Any
        val popup: PopupWindow
        val list: GroupPickerRecyclerView
        val adapter: GroupPickerAdapter
        val layout: LinearLayoutManager

        init {
            environment.database.groupDao().insert(groups)
            DataStore.selectedGroup = groups[19].id
            environment.attach(host, context)
            val pagerAdapter = environment.allocate(ConfigurationFragment.GroupPagerAdapter::class.java)
            pagerAdapter.groupList = ArrayList(groups)
            host.adapter = pagerAdapter
            activity.setContentView(FrameLayout(context).apply {
                addView(anchor, FrameLayout.LayoutParams(48, 48))
                measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                layout(0, 0, 320, 640)
            })
            val type = TopBarController::class.java.declaredClasses.single { it.simpleName == "PickerSession" }
            session = type.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(topBar, anchor, groups)
            popup = ReflectionHelpers.getField(session, "popup")
            list = ReflectionHelpers.getField(session, "list")
            adapter = ReflectionHelpers.getField(session, "adapter")
            layout = ReflectionHelpers.getField(session, "layout")
        }

        fun show() = ReflectionHelpers.callInstanceMethod<Void>(session, "show")

        fun frame(): Long {
            val content = popup.contentView
            content.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(popup.height, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, popup.width, popup.height)
            val bitmap = Bitmap.createBitmap(popup.width, popup.height, Bitmap.Config.ARGB_8888)
            try { content.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            return adapter.getItemId(layout.findFirstVisibleItemPosition())
        }

        fun advance(milliseconds: Long = 320L) {
            repeat((milliseconds / 16L).toInt()) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            }
        }

        fun awaitRefresh() {
            val job = ReflectionHelpers.getField<Job?>(session, "refreshing") ?: return
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!job.isCompleted && System.nanoTime() < deadline) {
                advance(16)
                Thread.yield()
            }
            assertTrue("The refresh must complete", job.isCompleted)
            advance()
        }

        override fun close() {
            val jobs = listOfNotNull(ReflectionHelpers.getField<Job?>(session, "refreshing"),
                ReflectionHelpers.getField<Job?>(session, "counting"))
            ReflectionHelpers.callInstanceMethod<Void>(session, "close")
            popup.dismiss()
            runBlocking { jobs.forEach { it.join() } }
            activityController.pause().stop().destroy()
            environment.close()
        }
    }

    @Test fun firstRenderedFrameIsAlreadyAtSelectedStableIdWithoutAnyPostedScroll() {
        Fixture().use { f ->
            f.show()
            assertTrue(f.popup.isShowing)
            // No idle/post/animation advance is allowed between show and this first draw.
            assertEquals(f.groups[19].id, f.frame())
            assertEquals(19, f.layout.findFirstVisibleItemPosition())
            assertNotNull(f.list.findViewHolderForItemId(f.groups[19].id))
            assertNull(f.list.findViewHolderForItemId(f.groups[0].id))
            assertEquals(View.VISIBLE, f.list.visibility)
            assertEquals(1f, f.list.alpha, 0f)
            assertEquals(1f, f.popup.contentView.alpha, 0f)
            val animator = f.list.itemAnimator as DefaultItemAnimator
            assertEquals(250L, animator.moveDuration)
            assertFalse(animator.supportsChangeAnimations)
            f.advance()
            assertEquals(f.groups[19].id, f.frame())
        }
    }

    @Test fun countsSaveResultAndGroupBroadcastKeepTheUsersScrolledViewport() {
        Fixture().use { f ->
            f.show()
            assertEquals(f.groups[19].id, f.frame())
            f.advance()
            f.awaitRefresh()
            // The user has scrolled away from selection: refresh must not reapply the opening anchor.
            f.layout.scrollToPositionWithOffset(27, -9)
            val first = f.frame()
            val top = f.layout.findViewByPosition(27)!!.top
            f.adapter.updateCounts(mapOf(f.groups[27].id to 42L))
            f.advance()
            assertEquals(first, f.frame())
            assertEquals(top, f.layout.findViewByPosition(27)!!.top)
            assertEquals("Group 27·42", (f.list.findViewHolderForItemId(first) as GroupPickerAdapter.Holder)
                .label.text.toString())
            ReflectionHelpers.callInstanceMethod<Void>(f.session, "accept",
                ClassParameter.from(GroupOrderWriter.Result::class.java,
                    GroupOrderWriter.Result(1L, f.groups.map { it.copy() }, null)))
            assertEquals(first, f.frame())
            assertEquals(top, f.layout.findViewByPosition(27)!!.top)
            f.advance()
            runBlocking { GroupManager.iterator { groupUpdated(f.groups[27]) } }
            f.awaitRefresh()
            assertEquals(first, f.frame())
            assertEquals(top, f.layout.findViewByPosition(27)!!.top)
        }
    }

    @Test fun dismissCancelsSessionWorkAndRemovesItsListener() {
        Fixture().use { f ->
            f.show()
            f.frame()
            val listeners = ReflectionHelpers.getField<List<GroupManager.Listener>>(GroupManager, "listeners")
            assertTrue(listeners.any { it === f.session })
            f.topBar.dismissGroupPicker()
            assertFalse(f.popup.isShowing)
            assertTrue(ReflectionHelpers.getField(f.session, "closed"))
            assertFalse(listeners.any { it === f.session })
            assertNull(f.list.adapter)
            assertNull(f.adapter.startDrag)
            assertNull(f.list.cancelDrag)
            f.advance()
            assertNull(f.list.adapter)
        }
    }

    @Test fun foregroundUsesPageThemeRatherThanWhiteActionBarOverlayInDayAndNight() {
        for (night in listOf(false, true)) Fixture(night).use { f ->
            val toolbar = Toolbar(ContextThemeWrapper(f.context,
                androidx.appcompat.R.style.ThemeOverlay_AppCompat_Dark_ActionBar))
            val attributes = f.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary,
                android.R.attr.colorBackground))
            val foreground = ReflectionHelpers.callInstanceMethod<Int>(f.topBar, "resolveForeground",
                ClassParameter.from(Toolbar::class.java, toolbar))
            try {
                assertEquals(attributes.getColor(0, 0), foreground)
                assertTrue("Foreground must remain readable on the theme's page surface",
                    ColorUtils.calculateContrast(foreground, attributes.getColor(1, 0)) >= 4.5)
            } finally { attributes.recycle() }
        }
    }
}
