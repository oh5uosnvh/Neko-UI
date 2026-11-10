package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.TagGroupStore
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.GroupPickerAdapter
import io.nekohasekai.sagernet.ui.GroupPickerNavigation
import io.nekohasekai.sagernet.ui.GroupPickerRecyclerView
import io.nekohasekai.sagernet.ui.TagPickerAdapter
import io.nekohasekai.sagernet.ui.TopBarController
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

/** Real PickerSession + in-memory Room: navigation is not merely a source-string contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class GroupPickerTagsSessionTest {
    private class Fixture : AutoCloseable {
        val environment = QuickPopupTestEnvironment()
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val context = ContextThemeWrapper(controller.get(), R.style.Theme_SagerNet_Blue)
        val roots = (0..39).map { ProxyGroup(id = 700L + it * 13L, userOrder = it + 1L, name = "Root $it") }
        val host = ConfigurationFragment()
        val topbar = TopBarController(host)
        val anchor = TextView(context).apply { text = "groups" }
        val session: Any
        val popup: PopupWindow
        val navigation: GroupPickerNavigation
        val list: GroupPickerRecyclerView
        val adapter: GroupPickerAdapter
        val tagList: GroupPickerRecyclerView
        val tagAdapter: TagPickerAdapter
        val memberList: GroupPickerRecyclerView
        val memberAdapter: GroupPickerAdapter
        init {
            environment.database.groupDao().insert(roots)
            DataStore.selectedGroup = roots[19].id
            environment.attach(host, context)
            val pager = environment.allocate(ConfigurationFragment.GroupPagerAdapter::class.java)
            pager.groupList = ArrayList(roots)
            host.adapter = pager
            controller.get().setContentView(FrameLayout(context).apply {
                addView(anchor, FrameLayout.LayoutParams(48, 48))
                measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                layout(0, 0, 320, 640)
            })
            val type = TopBarController::class.java.declaredClasses.single { it.simpleName == "PickerSession" }
            session = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(topbar, anchor, roots)
            popup = ReflectionHelpers.getField(session, "popup")
            navigation = ReflectionHelpers.getField(session, "navigation")
            list = ReflectionHelpers.getField(session, "list")
            adapter = ReflectionHelpers.getField(session, "adapter")
            tagList = ReflectionHelpers.getField(session, "tagList")
            tagAdapter = ReflectionHelpers.getField(session, "tagAdapter")
            memberList = ReflectionHelpers.getField(session, "memberList")
            memberAdapter = ReflectionHelpers.getField(session, "memberAdapter")
        }
        fun call(name: String) = ReflectionHelpers.callInstanceMethod<Void>(session, name)
        fun frame() {
            popup.contentView.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(popup.height, View.MeasureSpec.EXACTLY))
            popup.contentView.layout(0, 0, popup.width, popup.height)
        }
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frame()
                Thread.yield()
            }
            assertTrue("Asynchronous picker update must complete", condition())
        }
        fun show() {
            call("show")
            frame()
            await { ReflectionHelpers.getField<Job?>(session, "refreshing")?.isCompleted == true &&
                ReflectionHelpers.getField<Boolean>(session, "tagsLoaded") }
        }
        fun tags() { call("navigateTags"); frame() }
        fun open(id: Long) {
            ReflectionHelpers.callInstanceMethod<Void>(session, "openTag", ClassParameter.from(Long::class.javaPrimitiveType!!, id))
            frame()
            await { ReflectionHelpers.getField<Job?>(session, "tagsRefreshing")?.isCompleted == true }
        }
        fun descendants(view: View = popup.contentView): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        override fun close() {
            val jobs = listOfNotNull(ReflectionHelpers.getField<Job?>(session, "refreshing"),
                ReflectionHelpers.getField<Job?>(session, "counting"), ReflectionHelpers.getField<Job?>(session, "tagsWatching"),
                ReflectionHelpers.getField<Job?>(session, "tagsRefreshing"))
            topbar.dismissGroupPicker(immediate = true)
            call("close")
            jobs.forEach { job -> await { job.isCompleted } }
            controller.pause().stop().destroy()
            environment.close()
        }
    }

    @Test fun platformDismissReturnsByLayerAndRetainsRootExactPixelViewport() {
        Fixture().use { f ->
            val tag = TagGroupStore.create("Flower")
            TagGroupStore.add(tag.id, listOf(f.roots[30].id, f.roots[19].id, f.roots[3].id))
            f.show()
            val layout = f.list.layoutManager as LinearLayoutManager
            layout.scrollToPositionWithOffset(27, -9)
            f.frame()
            val first = f.adapter.getItemId(layout.findFirstVisibleItemPosition())
            val top = layout.findViewByPosition(27)!!.top
            f.tags()
            f.open(tag.id)
            assertEquals(listOf(f.roots[3].id, f.roots[19].id, f.roots[30].id), f.memberAdapter.ids)
            assertFalse(f.memberAdapter.reorderable)
            f.popup.dismiss() // same virtual entry point used by PopupDecorView / predictive BACK
            f.frame()
            assertTrue(f.popup.isShowing)
            assertEquals(GroupPickerNavigation.Page.Tags, f.navigation.page)
            f.popup.dismiss()
            f.frame()
            assertTrue(f.popup.isShowing)
            assertEquals(GroupPickerNavigation.Page.Configuration, f.navigation.page)
            assertEquals(first, f.adapter.getItemId(layout.findFirstVisibleItemPosition()))
            assertEquals(top, layout.findViewByPosition(27)!!.top)
            assertEquals(f.roots[19].id, DataStore.selectedGroup)
            assertEquals(f.roots.map { it.id }, f.environment.database.groupDao().allGroups().map { it.id })
        }
    }

    @Test fun renameMembershipAndDeletionRefreshLiveWithoutReopening() {
        Fixture().use { f ->
            val tag = TagGroupStore.create("Flower")
            TagGroupStore.add(tag.id, listOf(f.roots[3].id))
            f.show()
            f.tags()
            f.open(tag.id)
            TagGroupStore.rename(tag.id, "Renamed")
            f.await { f.descendants().filterIsInstance<TextView>().any { it.text.toString() == "Renamed" } }
            TagGroupStore.add(tag.id, listOf(f.roots[0].id, f.roots[30].id))
            f.await { f.memberAdapter.ids == listOf(f.roots[0].id, f.roots[3].id, f.roots[30].id) }
            TagGroupStore.remove(tag.id, listOf(f.roots[3].id))
            f.await { f.memberAdapter.ids == listOf(f.roots[0].id, f.roots[30].id) }
            TagGroupStore.delete(tag.id)
            f.await { f.navigation.page == GroupPickerNavigation.Page.Tags && f.tagAdapter.itemCount == 0 }
            assertEquals(f.roots[19].id, DataStore.selectedGroup)
            assertEquals(40, f.environment.database.groupDao().allGroups().size)
        }
    }

    @Test fun outsideTouchClosesImmediatelyAndCancelsReadersNotJustPopOnePage() {
        Fixture().use { f ->
            val tag = TagGroupStore.create("Flower")
            f.show()
            f.tags()
            f.open(tag.id)
            val watcher = ReflectionHelpers.getField<Job>(f.session, "tagsWatching")
            val reader = ReflectionHelpers.getField<Job>(f.session, "tagsRefreshing")
            var decor: View = f.popup.contentView
            while (decor.parent is View) decor = decor.parent as View
            val now = SystemClock.uptimeMillis()
            val outside = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try { decor.dispatchTouchEvent(outside) } finally { outside.recycle() }
            assertTrue(ReflectionHelpers.getField(f.session, "closed"))
            assertTrue(watcher.isCancelled)
            assertTrue(reader.isCompleted || reader.isCancelled)
            assertNull(f.list.adapter)
            assertNull(f.tagList.adapter)
            assertNull(f.memberList.adapter)
            val listeners = ReflectionHelpers.getField<List<GroupManager.Listener>>(GroupManager, "listeners")
            assertFalse(listeners.any { it === f.session })
            f.await { !f.popup.isShowing }
        }
    }
}
