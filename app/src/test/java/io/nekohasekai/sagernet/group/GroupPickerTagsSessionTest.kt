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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.nekohasekai.sagernet.database.PendingGroupDeletes
import io.nekohasekai.sagernet.ui.GroupPickerDragCallback
import io.nekohasekai.sagernet.ui.GroupPickerHeader
import io.nekohasekai.sagernet.ui.PageSlideTransition
import io.nekohasekai.sagernet.ui.pickerViewport
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
        var session: Any
        val popup: PopupWindow get() = ReflectionHelpers.getField(session, "popup")
        val navigation: GroupPickerNavigation get() = ReflectionHelpers.getField(session, "navigation")
        val list: GroupPickerRecyclerView get() = ReflectionHelpers.getField(session, "list")
        val adapter: GroupPickerAdapter get() = ReflectionHelpers.getField(session, "adapter")
        val tagList: GroupPickerRecyclerView get() = ReflectionHelpers.getField(session, "tagList")
        val tagAdapter: TagPickerAdapter get() = ReflectionHelpers.getField(session, "tagAdapter")
        val memberList: GroupPickerRecyclerView get() = ReflectionHelpers.getField(session, "memberList")
        val memberAdapter: GroupPickerAdapter get() = ReflectionHelpers.getField(session, "memberAdapter")
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
            session = newSession()
        }
        private fun newSession(): Any {
            val type = TopBarController::class.java.declaredClasses.single { it.simpleName == "PickerSession" }
            return type.declaredConstructors.single().apply { isAccessible = true }.newInstance(topbar, anchor, roots)
        }
        fun advanceMotion(millis: Long) {
            val slide = ReflectionHelpers.getField<PageSlideTransition>(session, "pageSlide")
            val running = ReflectionHelpers.getField<Map<android.animation.ValueAnimator, *>>(slide, "running")
            assertEquals("paired real incoming/outgoing animations", 2, running.size)
            running.keys.forEach { animator ->
                assertTrue("animation starts rather than an instant visibility swap", animator.isStarted)
                animator.currentPlayTime = millis
            }
            frame()
        }
        fun settleMotion() {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PageSlideTransition.DURATION_MS + 32L))
            frame()
        }
        fun reopen() {
            topbar.dismissGroupPicker(immediate = true)
            session = newSession()
            show()
            settleMotion()
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
        fun tags() { call("navigateTags"); frame(); settleMotion() }
        fun open(id: Long) {
            ReflectionHelpers.callInstanceMethod<Void>(session, "openTag", ClassParameter.from(Long::class.javaPrimitiveType!!, id))
            frame()
            await { ReflectionHelpers.getField<Job?>(session, "tagsRefreshing")?.isCompleted == true }
            settleMotion()
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
            ReflectionHelpers.getField<Job?>(session, "tagWriteTail")?.let { job -> await { job.isCompleted } }
            PendingGroupDeletes.unmark(PendingGroupDeletes.ids())
            controller.pause().stop().destroy()
            environment.close()
        }
    }

    @Test fun reopenRemembersMembersAndAllPixelViewportsDeletedTagFallsBack() {
        Fixture().use { f ->
            val tags = (0..23).map { TagGroupStore.create("Tag $it") }
            val tag = tags[15]
            TagGroupStore.add(tag.id, f.roots.map { it.id })
            f.show()
            (f.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(24, -11)
            f.frame()
            val root = pickerViewport(f.list, f.adapter.ids)
            f.tags()
            (f.tagList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(15, -7)
            f.frame()
            val tagsViewport = pickerViewport(f.tagList, f.tagAdapter.ids)
            f.open(tag.id)
            (f.memberList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(23, -13)
            f.frame()
            val members = pickerViewport(f.memberList, f.memberAdapter.ids)
            val oldSession = f.session
            f.reopen()
            assertNotSame(oldSession, f.session)
            assertEquals(GroupPickerNavigation.Page.Members(tag.id), f.navigation.page)
            assertEquals(members, pickerViewport(f.memberList, f.memberAdapter.ids))
            assertFalse(ReflectionHelpers.getField<PageSlideTransition>(f.session, "pageSlide").isRunning)
            f.popup.dismiss(); f.settleMotion()
            assertEquals(tagsViewport, pickerViewport(f.tagList, f.tagAdapter.ids))
            f.popup.dismiss(); f.settleMotion()
            assertEquals(root, pickerViewport(f.list, f.adapter.ids))
            f.tags(); f.open(tag.id)
            f.topbar.dismissGroupPicker(immediate = true)
            TagGroupStore.delete(tag.id)
            f.reopen()
            assertEquals(GroupPickerNavigation.Page.Tags, f.navigation.page)
            assertFalse(f.tagAdapter.ids.contains(tag.id))
            assertEquals(f.roots[19].id, DataStore.selectedGroup)
        }
    }

    @Test fun realPagesTranslateInBothDirectionsAndRootFirstCardIsBelowHeader() {
        Fixture().use { f ->
            val tag = TagGroupStore.create("Flower")
            TagGroupStore.add(tag.id, listOf(f.roots[19].id))
            f.show()
            val header = ReflectionHelpers.getField<GroupPickerHeader>(f.session, "header")
            val rootPage = ReflectionHelpers.getField<View>(f.session, "rootPage")
            val tagsPage = ReflectionHelpers.getField<View>(f.session, "tagsPage")
            val membersPage = ReflectionHelpers.getField<View>(f.session, "membersPage")
            val entries = header.getChildAt(0) as ViewGroup
            assertEquals(entries.getChildAt(0).width, entries.getChildAt(1).width)
            assertEquals(header.bottom, (f.list.parent as View).top)
            assertTrue("Root list is measured below, not overlaid by, the top buttons", f.list.height > 0)
            assertFalse(f.popup.contentView.clipToOutline)
            assertNull("No extra enclosing background strip", f.popup.contentView.background)
            val first = (f.list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            assertTrue(f.list.findViewHolderForAdapterPosition(first)!!.itemView.top >= f.list.paddingTop)
            f.call("navigateTags"); f.frame()
            assertEquals(View.VISIBLE, rootPage.visibility)
            assertEquals(View.VISIBLE, tagsPage.visibility)
            assertEquals(rootPage.width.toFloat(), tagsPage.translationX, 0.1f)
            f.advanceMotion(80L)
            assertTrue("Push parent exits left", rootPage.translationX < 0f)
            assertTrue("Push child enters from right", tagsPage.translationX > 0f)
            f.settleMotion()
            ReflectionHelpers.callInstanceMethod<Void>(f.session, "openTag",
                ClassParameter.from(Long::class.javaPrimitiveType!!, tag.id))
            f.frame()
            assertEquals(membersPage.width.toFloat(), membersPage.translationX, 0.1f)
            f.advanceMotion(80L)
            assertTrue(tagsPage.translationX < 0f)
            assertTrue(membersPage.translationX > 0f)
            f.settleMotion()
            f.popup.dismiss(); f.frame()
            assertEquals(-tagsPage.width.toFloat(), tagsPage.translationX, 0.1f)
            f.advanceMotion(80L)
            assertTrue("Back parent enters from left", tagsPage.translationX < 0f)
            assertTrue("Back child exits right", membersPage.translationX > 0f)
            f.settleMotion()
            f.popup.dismiss(); f.frame()
            assertEquals(-rootPage.width.toFloat(), rootPage.translationX, 0.1f)
            f.settleMotion()
            assertEquals(View.VISIBLE, rootPage.visibility)
            assertEquals(View.INVISIBLE, tagsPage.visibility)
            assertEquals(0f, rootPage.translationX, 0f)
            assertEquals(header.bottom, (f.list.parent as View).top)
            assertEquals(f.roots[19].id, DataStore.selectedGroup)
        }
    }

    @Test fun tagGroupCountsAndRealHandleDragPersistOnlyOnceAfterSettleWithoutRootMutation() {
        Fixture().use { f ->
            val tags = listOf("Flower", "Work", "Travel").map { TagGroupStore.create(it) }
            TagGroupStore.add(tags[0].id, listOf(f.roots[3].id, f.roots[19].id, f.roots[30].id))
            f.show()
            (f.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(25, -9)
            f.frame()
            val rootViewport = pickerViewport(f.list, f.adapter.ids)
            val rootOrder = f.environment.database.groupDao().allGroups().map { it.id }
            f.tags()
            fun label(id: Long): String {
                val holder = f.tagAdapter.onCreateViewHolder(f.tagList, 0)
                f.tagAdapter.onBindViewHolder(holder, f.tagAdapter.ids.indexOf(id))
                return holder.label.text.toString()
            }
            assertEquals("Flower·3", label(tags[0].id)) // groups, not Root node counts (all zero)
            PendingGroupDeletes.mark(f.roots[30].id)
            f.await { label(tags[0].id) == "Flower·2" }
            PendingGroupDeletes.unmark(listOf(f.roots[30].id))
            f.await { label(tags[0].id) == "Flower·3" }
            f.settleMotion()
            val callback = ReflectionHelpers.getField<GroupPickerDragCallback>(f.session, "tagDrag")
            val holder = f.tagList.findViewHolderForAdapterPosition(0) as TagPickerAdapter.Holder
            val target = f.tagList.findViewHolderForAdapterPosition(2) as TagPickerAdapter.Holder
            val x = (holder.row.left + holder.nameCard.left + holder.handle.left + holder.handle.width / 2).toFloat()
            val y = (holder.row.top + holder.nameCard.top + holder.handle.top + holder.handle.height / 2).toFloat()
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            try { f.tagList.dispatchTouchEvent(down) } finally { down.recycle() }
            assertTrue(callback.isDragging)
            assertFalse(callback.isLongPressDragEnabled())
            val revision = TagGroupStore.changes.value
            assertTrue(callback.onMove(f.tagList, holder, target))
            holder.row.performClick()
            assertEquals(GroupPickerNavigation.Page.Tags, f.navigation.page)
            assertEquals(tags.map { it.id }, TagGroupStore.tags().map { it.id })
            callback.clearView(f.tagList, holder)
            assertEquals("Nothing is written inside clearView before recovery/layout settles", revision, TagGroupStore.changes.value)
            holder.row.performClick() // handle suppressClick survives clearView
            assertEquals(GroupPickerNavigation.Page.Tags, f.navigation.page)
            val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0)
            try { f.tagList.dispatchTouchEvent(up) } finally { up.recycle() }
            val expected = listOf(tags[1].id, tags[2].id, tags[0].id)
            f.await { TagGroupStore.tags().map { it.id } == expected &&
                ReflectionHelpers.getField<Int>(f.session, "tagSaves") == 0 }
            callback.clearView(f.tagList, holder)
            f.settleMotion()
            assertEquals(revision + 1L, TagGroupStore.changes.value)
            assertEquals(expected, f.tagAdapter.ids)
            assertEquals(rootOrder, f.environment.database.groupDao().allGroups().map { it.id })
            assertEquals(f.roots[19].id, DataStore.selectedGroup)
            f.call("navigateConfiguration"); f.settleMotion()
            assertEquals(rootViewport, pickerViewport(f.list, f.adapter.ids))
            f.tags(); f.open(tags[0].id)
            assertEquals(listOf(f.roots[3].id, f.roots[19].id, f.roots[30].id), f.memberAdapter.ids)
            assertFalse(f.memberAdapter.reorderable)
            val selected = f.memberAdapter.onCreateViewHolder(f.memberList, 0)
            f.memberAdapter.onBindViewHolder(selected, 1)
            assertTrue(selected.row.isSelected)
            assertEquals(View.GONE, selected.handle.visibility)
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
