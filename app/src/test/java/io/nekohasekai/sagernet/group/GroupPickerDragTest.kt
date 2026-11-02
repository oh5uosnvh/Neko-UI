package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ui.GroupPickerAdapter
import io.nekohasekai.sagernet.ui.GroupPickerDragCallback
import io.nekohasekai.sagernet.ui.GroupPickerRecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GroupPickerDragTest {
    private class Fixture : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var selectedId = 2L
        var selectedProxy = 700L
        var hostIds = listOf(1L, 2L, 3L)
        var clickedIndex: Int? = null
        val commits = ArrayList<List<Long>>()
        var cancelled = 0
        var downs = 0
        val rows = (1L..3L).map { ProxyGroup(id = it, userOrder = it, name = "Group $it") }
        val recycler = GroupPickerRecyclerView(activity)
        val adapter = GroupPickerAdapter(
            activity, rows, { selectedId }, { ColorDrawable(if (it) Color.RED else Color.GRAY) },
            Color.BLACK, Color.DKGRAY,
        ) { id -> clickedIndex = hostIds.indexOf(id) }
        val callback = GroupPickerDragCallback(adapter, { commits.add(it) }, { cancelled++ }, {})
        val touch = ItemTouchHelper(callback)

        init {
            recycler.layoutManager = LinearLayoutManager(activity)
            recycler.adapter = adapter
            recycler.itemAnimator = null
            recycler.cancelDrag = { callback.cancelGesture() }
            touch.attachToRecyclerView(recycler)
            adapter.startDrag = { downs++; touch.startDrag(it) }
            activity.setContentView(recycler)
            layout()
        }

        fun layout() {
            recycler.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            recycler.layout(0, 0, 500, 800)
        }

        fun holder(position: Int) = recycler.findViewHolderForAdapterPosition(position) as GroupPickerAdapter.Holder

        fun event(action: Int, x: Float, y: Float, elapsed: Long = 0L) {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now + elapsed, action, x, y, 0)
            recycler.dispatchTouchEvent(event)
            event.recycle()
        }

        fun downOnHandle(holder: GroupPickerAdapter.Holder) {
            event(MotionEvent.ACTION_DOWN,
                (holder.nameCard.left + holder.handle.left + holder.handle.width / 2).toFloat(),
                (holder.row.top + holder.nameCard.top + holder.handle.top + holder.handle.height / 2).toFloat())
        }

        override fun close() {
            callback.close()
            touch.attachToRecyclerView(null)
            controller.pause().stop().destroy()
        }
    }

    @Test fun handleDownStartsImmediatelyAndNeverClicksTheRow() {
        Fixture().use { f ->
            val holder = f.holder(0)
            assertEquals((48 * f.activity.resources.displayMetrics.density).toInt(), holder.handle.width)
            assertSame(holder.nameCard, holder.handle.parent)
            assertTrue(holder.nameCard.width - holder.handle.right <=
                (4 * f.activity.resources.displayMetrics.density).toInt())
            val started = SystemClock.uptimeMillis()
            f.downOnHandle(holder)
            assertEquals(started, SystemClock.uptimeMillis()) // No long-press time has elapsed.
            assertEquals(1, f.downs)
            assertTrue(f.callback.isDragging)
            assertFalse(f.callback.isLongPressDragEnabled())
            assertFalse(f.callback.isItemViewSwipeEnabled())
            assertTrue(f.callback.onMove(f.recycler, holder, f.holder(2)))
            assertTrue(f.commits.isEmpty())
            f.callback.clearView(f.recycler, holder)
            assertEquals(listOf(listOf(2L, 3L, 1L)), f.commits)
            assertEquals(2L, f.selectedId)
            assertEquals(700L, f.selectedProxy)
            assertNull(f.clickedIndex)
            assertEquals(listOf(1L, 2L, 3L), f.rows.map { it.userOrder })
        }
    }

    @Test fun stableIdClickResolvesCurrentHostPositionAfterReorderAndLongPressDoesNotEdit() {
        Fixture().use { f ->
            val holder = f.holder(0)
            assertFalse(holder.row.isLongClickable)
            assertFalse(holder.row.performLongClick())
            assertNull(shadowOf(f.activity).nextStartedActivity)
            assertTrue(f.adapter.move(0, 2))
            f.hostIds = listOf(2L, 3L, 1L)
            holder.row.performClick() // Deliberately reuse the pre-move holder without rebinding.
            assertEquals(2, f.clickedIndex)
            assertEquals(1L, holder.groupId)
            assertEquals(listOf(2L, 3L, 1L), f.adapter.ids)
        }
    }

    @Test fun rowLongPressHasNoEditAndDoesNotStartDrag() {
        Fixture().use { f ->
            val holder = f.holder(0)
            val x = (holder.nameCard.left + 12).toFloat()
            val y = (holder.row.top + holder.row.height / 2).toFloat()
            f.event(MotionEvent.ACTION_DOWN, x, y)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            assertEquals(0, f.downs)
            assertFalse(f.callback.isDragging)
            assertNull(shadowOf(f.activity).nextStartedActivity)
            f.event(MotionEvent.ACTION_CANCEL, x, y)
        }
    }

    @Test fun dismissCommitsOnceAndLateClearViewCannotDuplicateIt() {
        Fixture().use { f ->
            val holder = f.holder(0)
            f.downOnHandle(holder)
            f.callback.onMove(f.recycler, holder, f.holder(2))
            f.callback.close()
            f.callback.clearView(f.recycler, holder)
            f.callback.close()
            assertEquals(listOf(listOf(2L, 3L, 1L)), f.commits)
        }
    }

    @Test fun cancelledDragRestoresOriginalOrderAndReportsWithoutSaving() {
        Fixture().use { f ->
            val holder = f.holder(0)
            f.downOnHandle(holder)
            f.callback.onMove(f.recycler, holder, f.holder(2))
            f.event(MotionEvent.ACTION_CANCEL, 300f, 100f)
            f.callback.clearView(f.recycler, holder)
            assertEquals(listOf(1L, 2L, 3L), f.adapter.ids)
            assertEquals(1, f.cancelled)
            assertTrue(f.commits.isEmpty())
            assertEquals(2L, f.selectedId)
        }
    }

    @Test fun countLabelsStayWithStableIdAcrossRebinding() {
        Fixture().use { f ->
            f.adapter.updateCounts(mapOf(1L to 12L, 2L to 34L, 3L to 56L))
            f.adapter.move(0, 2)
            val holder = f.holder(0)
            f.adapter.onBindViewHolder(holder, 0)
            assertEquals(2L, holder.groupId)
            assertEquals("Group 2·34", holder.label.text.toString())
            assertTrue(holder.label.typeface.isBold)
            f.adapter.onBindViewHolder(holder, 2)
            assertEquals("Group 1·12", holder.label.text.toString())
            assertFalse(holder.label.typeface.isBold)
        }
    }
}
