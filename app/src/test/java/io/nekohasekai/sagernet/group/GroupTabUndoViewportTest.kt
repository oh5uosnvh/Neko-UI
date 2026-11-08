package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.ui.GroupPageTransition
import io.nekohasekai.sagernet.ui.GroupTabMutation
import io.nekohasekai.sagernet.ui.GroupTabPolicy
import io.nekohasekai.sagernet.widget.GroupPillTabLayout
import io.nekohasekai.sagernet.widget.GroupTabPill
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Real Material TabLayout + Mediator + ViewPager2 layout, not source-string assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GroupTabUndoViewportTest {
    private class Harness {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get().apply { setTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar) }
        val tabs = GroupPillTabLayout(activity).apply {
            tabMode = TabLayout.MODE_SCROLLABLE
            tabGravity = TabLayout.GRAVITY_START
        }
        val pager = ViewPager2(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabs, LinearLayout.LayoutParams(-1, 64))
            addView(pager, LinearLayout.LayoutParams(-1, 600))
        }
        val ids = (1L..16L).toMutableList()
        var blocked = false
        var selected = 1L
        var alive = true
        var completions = 0
        val pages = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            init { setHasStableIds(true) }
            override fun getItemCount() = ids.size
            override fun getItemId(position: Int) = ids[position]
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        val mediator: TabLayoutMediator
        var transition: GroupPageTransition? = null
        private fun resetTransition() {
            transition?.let { pager.unregisterOnPageChangeCallback(it); it.close() }
            transition = GroupPageTransition(pager, tabs, { ids.toList() },
                { position -> if (!blocked) selected = ids[position] })
                .also { pager.registerOnPageChangeCallback(it) }
        }
        fun enableClickTransition() = resetTransition()
        val mutation = GroupTabMutation(tabs, pager, { ids.toList() }, { alive },
            { value ->
                blocked = value
                if (value && transition != null) resetTransition()
            }, { id, _ -> selected = id }, { completions++ })

        init {
            pager.adapter = pages
            mediator = TabLayoutMediator(tabs, pager) { tab, position ->
                GroupTabPill.bindTab(tab, ids[position].toString())
            }.also { it.attach() }
            tabs.clearOnTabSelectedListeners()
            tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (!blocked) { pager.setCurrentItem(tab.position, false); selected = ids[tab.position] }
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) { if (!blocked) selected = ids[position] }
            })
            activity.setContentView(root)
            frames()
        }

        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 700)
            root.viewTreeObserver.dispatchOnPreDraw()
        }
        fun frames(count: Int = 6) {
            repeat(count) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                layout()
            }
        }
        fun prepare(id: Long, manual: Boolean = false): GroupTabMutation.Snapshot {
            val at = ids.indexOf(id)
            pager.setCurrentItem(at, false)
            tabs.selectTab(tabs.getTabAt(at))
            frames()
            tabs.manualCentering = manual
            val eighth = tabs.getTabAt(7)!!.view
            tabs.scrollTo(eighth.left, 0)
            assertTrue("fixture must actually be scrolled beyond tab 1", tabs.scrollX > 0)
            return mutation.capture()
        }
        fun delete(id: Long) {
            val snapshot = mutation.capture()
            val keep = GroupTabPolicy.afterRemoval(ids, id, snapshot.selectedId ?: 0L)
            mutation.change(snapshot.copy(selectedId = keep)) {
                val at = ids.indexOf(id)
                ids.removeAt(at)
                pages.notifyItemRemoved(at)
            }
        }
        fun undo(id: Long, before: List<Long>, snapshot: GroupTabMutation.Snapshot) {
            mutation.change(snapshot) {
                val at = GroupTabMutation.insertionIndex(before, id, ids)
                ids.add(at, id)
                pages.notifyItemInserted(at)
            }
        }
        fun assertRestored(snapshot: GroupTabMutation.Snapshot, before: List<Long>) {
            assertEquals(before, ids)
            assertEquals(snapshot.selectedId, ids[pager.currentItem])
            assertEquals(snapshot.selectedId, selected)
            assertEquals(pager.currentItem, tabs.selectedTabPosition)
            assertEquals(snapshot.scrollX, tabs.scrollX)
            assertFalse(blocked)
            assertFalse(mutation.isPending)
            assertFalse(tabs.isViewportLocked)
            assertEquals(1f, (tabs.getTabAt(pager.currentItem)!!.customView as GroupTabPill).selectionProgress, 0f)
        }
        fun close() {
            transition?.let { pager.unregisterOnPageChangeCallback(it); it.close() }
            transition = null
            mutation.cancel(); mediator.detach(); pager.adapter = null
            controller.pause().stop().destroy()
        }
    }

    @Test fun selected10UndoRestoresOrderSelectionAndScrolledViewportAfterRealLayout() {
        val h = Harness()
        try {
            val before = h.ids.toList()
            val snapshot = h.prepare(10)
            h.delete(10); h.frames()
            assertEquals(9L, h.selected)
            h.undo(10, before, snapshot); h.frames()
            h.assertRestored(snapshot, before)
            h.frames(30) // no late Mediator/pager scroll back to zero
            h.assertRestored(snapshot, before)
        } finally { h.close() }
    }

    @Test fun longPressNonselected10DoesNotStealSelected12OnUndo() {
        val h = Harness()
        try {
            val before = h.ids.toList()
            val snapshot = h.prepare(12)
            h.delete(10); h.frames()
            assertEquals(12L, h.ids[h.pager.currentItem])
            h.undo(10, before, snapshot); h.frames()
            h.assertRestored(snapshot, before)
        } finally { h.close() }
    }

    @Test fun fastUndoBeforeRemovalLayoutInvalidatesTheOldRestoreCallback() {
        val h = Harness()
        try {
            val before = h.ids.toList()
            val snapshot = h.prepare(10, manual = true)
            h.delete(10)
            h.undo(10, before, snapshot) // deliberately no looper/layout drain
            h.frames(20)
            h.assertRestored(snapshot, before)
            assertTrue(h.tabs.manualCentering)
            assertEquals(1, h.completions)
        } finally { h.close() }
    }

    @Test fun scrollOnlyWithSelected1StillKeepsViewportNear8RatherThanCenteringSelection() {
        val h = Harness()
        try {
            val before = h.ids.toList()
            val snapshot = h.prepare(1)
            h.delete(10); h.frames()
            h.undo(10, before, snapshot); h.frames()
            h.assertRestored(snapshot, before)
        } finally { h.close() }
    }

    @Test fun destroyedViewRemovesLayoutListenerAndCannotApplyLateSelection() {
        val h = Harness()
        try {
            h.prepare(10)
            h.delete(10)
            h.alive = false
            h.mutation.cancel()
            val selected = h.selected
            h.frames(10)
            assertEquals(selected, h.selected)
            assertFalse(h.mutation.isPending)
            assertFalse(h.tabs.isViewportLocked)
            assertFalse(h.blocked)
            assertEquals(0, h.completions)
            h.tabs.scrollTo(100, 0)
            assertEquals(100, h.tabs.scrollX)
        } finally { h.close() }
    }

    @Test fun oldSmoothScrollCannotOverwriteViewportDuringOrAfterUndo() {
        val h = Harness()
        try {
            val before = h.ids.toList()
            h.prepare(10)
            h.frames(20) // exceed ANIMATED_SCROLL_GAP; do not take smoothScroll's immediate branch
            val snapshot = h.mutation.capture()
            // Keep a real HorizontalScrollView scroller live at the mutation boundary.
            h.tabs.smoothScrollTo(0, 0)
            assertEquals(snapshot.scrollX, h.tabs.scrollX)
            h.delete(10)
            h.undo(10, before, snapshot)
            assertTrue(h.tabs.isViewportLocked)
            h.tabs.scrollTo(0, 0) // stale Material animator/property write
            h.tabs.computeScroll() // the framework path does not call scrollTo
            assertEquals(snapshot.scrollX, h.tabs.scrollX)
            h.frames(30)
            h.assertRestored(snapshot, before)
            h.tabs.computeScroll() // release must abort, not merely pause, the old scroller
            assertEquals(snapshot.scrollX, h.tabs.scrollX)
            h.frames(30)
            h.assertRestored(snapshot, before)
        } finally { h.close() }
    }

    @Test fun restoreReleasesLockForNormalTabScrollAndPageSelection() {
        val h = Harness()
        try {
            val before = h.ids.toList()
            val snapshot = h.prepare(10)
            h.delete(10); h.undo(10, before, snapshot); h.frames()
            h.assertRestored(snapshot, before)
            h.tabs.scrollTo(snapshot.scrollX - 100, 0)
            assertEquals(snapshot.scrollX - 100, h.tabs.scrollX)
            h.tabs.selectTab(h.tabs.getTabAt(h.ids.indexOf(12)))
            h.frames(30)
            assertEquals(12L, h.selected)
            assertEquals(12L, h.ids[h.pager.currentItem])
            assertFalse(h.tabs.isViewportLocked)
            // Mediator's swipe-position scroll must work again, not retain undo's lock.
            h.tabs.setScrollPosition(0, 0f, true)
            assertEquals(0, h.tabs.scrollX)
        } finally { h.close() }
    }

    @Test fun interruptedClickCannotSelectItsStaleIndexAfterUndoAndNextClickStillWorks() {
        val h = Harness()
        try {
            val before = h.ids.toList()
            val snapshot = h.prepare(10)
            h.enableClickTransition()
            val old = h.transition!!
            old.request(12)
            assertTrue(old.clicking)
            h.delete(10); h.undo(10, before, snapshot)
            assertFalse(old.clicking)
            h.frames(60)
            h.assertRestored(snapshot, before)
            h.transition!!.request(12)
            h.frames(80)
            assertEquals(12L, h.ids[h.pager.currentItem])
            assertEquals(12L, h.selected)
            assertFalse(h.transition!!.clicking)
            assertFalse(h.tabs.isViewportLocked)
            // Real user pager dragging clears click-owned centering as before.
            h.transition!!.onPageScrollStateChanged(ViewPager2.SCROLL_STATE_DRAGGING)
            assertFalse(h.tabs.manualCentering)
            h.tabs.setScrollPosition(0, 0f, true)
            assertEquals(0, h.tabs.scrollX)
        } finally { h.close() }
    }

    @Test fun mutationFailureReleasesLockAndSelectionBlock() {
        val h = Harness()
        try {
            val snapshot = h.prepare(10, manual = true)
            val failure = IllegalStateException("adapter mutation failed")
            try {
                h.mutation.change(snapshot) { throw failure }
                fail("mutation should propagate its failure")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }
            assertFalse(h.mutation.isPending)
            assertFalse(h.tabs.isViewportLocked)
            assertFalse(h.blocked)
            assertTrue(h.tabs.manualCentering)
            h.tabs.scrollTo(snapshot.scrollX - 100, 0)
            assertEquals(snapshot.scrollX - 100, h.tabs.scrollX)
            assertEquals(0, h.completions)
        } finally { h.close() }
    }

    @Test fun originalNeighboursKeepUndoPositionAfterAnotherGroupWasRemoved() {
        val before = (1L..12L).toList()
        val now = before.filter { it != 4L && it != 10L }
        assertEquals(now.indexOf(11), GroupTabMutation.insertionIndex(before, 10, now))
        val withoutSuccessor = before.filter { it < 10 }
        assertEquals(withoutSuccessor.size, GroupTabMutation.insertionIndex(before, 10, withoutSuccessor))
    }
}
