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
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.TagGroup
import io.nekohasekai.sagernet.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Dispatch through the real RecyclerView/ItemTouchHelper, including its recover DOWN path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class GroupPickerHandleGateTest {
    @Before fun pauseFrames() {
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
    }
    @After fun restoreFrames() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }
    private class Fixture(val level: String) : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val recycler = GroupPickerRecyclerView(controller.get())
        val actual: RecyclerView.Adapter<*>
        val rows: GroupPickerReorderAdapter
        var prepares = 0
        var moves = 0
        var clicks = 0
        var scrolled = 0
        var cancellations = 0
        val commits = ArrayList<List<Long>>()
        val drag: GroupPickerDragCallback
        val touch: ItemTouchHelper
        var downTime = 0L
        init {
            val context = controller.get()
            if (level == "tags") {
                val adapter = TagPickerAdapter(context, Color.BLACK, { ColorDrawable(Color.GRAY) }, { clicks++ })
                actual = adapter
                rows = adapter
            } else {
                val ids = if (level == "members") (1L..79L step 2).toList() else (1L..40L).toList()
                val adapter = GroupPickerAdapter(context, ids.map { ProxyGroup(id = it, name = "Root $it", userOrder = it) },
                    { 1L }, { ColorDrawable(Color.GRAY) }, Color.BLACK, Color.DKGRAY, { clicks++ })
                actual = adapter
                rows = adapter
            }
            val counted = object : GroupPickerReorderAdapter by rows {
                override fun prepareDrag(holder: RecyclerView.ViewHolder) { prepares++; rows.prepareDrag(holder) }
            }
            drag = GroupPickerDragCallback(counted, { commits.add(it) }, { cancellations++ }, {})
            touch = ItemTouchHelper(drag)
            recycler.layoutManager = LinearLayoutManager(context)
            recycler.adapter = actual
            recycler.itemAnimator = groupPickerItemAnimator()
            recycler.cancelDrag = { drag.cancelGesture() }
            drag.attach(recycler, touch)
            when (val adapter = actual) {
                is TagPickerAdapter -> {
                    adapter.replace((1L..40L).map { TagGroup(id = it, name = "Tag $it", userOrder = it) }, recycler)
                    adapter.startDrag = { if (drag.armHandle(it)) touch.startDrag(it) }
                }
                is GroupPickerAdapter -> adapter.startDrag = { if (drag.armHandle(it)) touch.startDrag(it) }
            }
            actual.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeMoved(from: Int, to: Int, count: Int) { moves++ }
            })
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) { scrolled += dy }
            })
            controller.get().setContentView(recycler)
            layout()
            scrolled = 0
        }
        fun layout() {
            recycler.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
            recycler.layout(0, 0, 320, 360)
        }
        fun holder(position: Int = 0) = recycler.findViewHolderForAdapterPosition(position)!!
        fun handle(holder: RecyclerView.ViewHolder): View = when (holder) {
            is GroupPickerAdapter.Holder -> holder.handle
            is TagPickerAdapter.Holder -> holder.handle
            else -> error("Unknown holder")
        }
        fun point(holder: RecyclerView.ViewHolder, onHandle: Boolean): Pair<Float, Float> {
            val row = holder.itemView
            val target = if (onHandle) handle(holder) else row
            val rect = android.graphics.Rect(0, 0, target.width, target.height)
            recycler.offsetDescendantRectToMyCoords(target, rect)
            return (rect.left + if (onHandle) target.width / 2f else 32f) to
                (rect.top + target.height / 2f)
        }
        fun event(action: Int, x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
            try { recycler.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        fun advance(ms: Long) {
            repeat((ms / 16L).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        }
        override fun close() {
            drag.close()
            recycler.dragTouch = null
            recycler.cancelDrag = null
            touch.attachToRecyclerView(null)
            recycler.itemAnimator?.endAnimations()
            recycler.adapter = null
            controller.pause().stop().destroy()
        }
    }

    @Test fun bodyDownMoveScrollsAllThreeLevelsWithoutPreparingOrChangingOrderEvenImmediatelyAfterOpen() {
        for (level in listOf("root", "tags", "members")) for (openDelay in listOf(0L, 96L)) {
            Fixture(level).use { f ->
                f.advance(openDelay)
                val original = f.rows.ids
                // Start in the middle of the viewport so MOVE has ample space to scroll.
                f.event(MotionEvent.ACTION_DOWN, 40f, 260f)
                assertEquals(0, f.drag.getDragDirs(f.recycler, f.holder()))
                // Include an actual long press: it must not arm the body or start dragging.
                f.advance(704)
                f.event(MotionEvent.ACTION_MOVE, 40f, 210f)
                f.event(MotionEvent.ACTION_MOVE, 40f, 120f)
                f.event(MotionEvent.ACTION_MOVE, 40f, 40f)
                f.event(MotionEvent.ACTION_CANCEL, 40f, 40f)
                assertTrue("$level/$openDelay: body scroll remains responsive", f.scrolled > 0)
                assertEquals("$level: no prepareDrag on body gesture", 0, f.prepares)
                assertEquals(0, f.moves)
                assertFalse(f.drag.isDragging)
                assertEquals(original, f.rows.ids)
                assertTrue(f.commits.isEmpty())
            }
        }
    }

    @Test fun recoverBodyDownMoveCannotReselectOrPrepareDragOnAnyLevel() {
        for (level in listOf("root", "tags", "members")) Fixture(level).use { f ->
            val holder = f.holder(1)
            val (x, y) = f.point(holder, true)
            f.event(MotionEvent.ACTION_DOWN, x, y)
            assertTrue("$level handle at ($x,$y), size=${f.handle(holder).width}x${f.handle(holder).height}, prepares=${f.prepares}", f.drag.isSelected)
            assertEquals(1, f.prepares)
            assertTrue(f.drag.onMove(f.recycler, holder, f.holder(3)))
            f.layout()
            // Give the selected row a real offset so ACTION_UP starts a non-zero recover.
            f.event(MotionEvent.ACTION_MOVE, x, y + 45f)
            f.event(MotionEvent.ACTION_UP, x, y + 45f)
            assertFalse(f.drag.isSelected)
            val recovering = ReflectionHelpers.getField<List<*>>(f.touch, "mRecoverAnimations")
            assertTrue("Must hit the real ItemTouchHelper recovery interval", recovering.isNotEmpty())
            val afterDrop = f.rows.ids
            val movesAfterDrop = f.moves
            val (bodyX, bodyY) = f.point(holder, false)
            // No clock advance: the old recover is still present on this next DOWN.
            f.event(MotionEvent.ACTION_DOWN, bodyX, bodyY)
            assertNull("Body must not be intercepted as a selected recover row",
                ReflectionHelpers.getField<RecyclerView.ViewHolder?>(f.touch, "mSelected"))
            assertEquals(1, f.prepares)
            assertFalse(f.drag.isDragging)
            f.event(MotionEvent.ACTION_MOVE, bodyX, bodyY - 40f)
            f.event(MotionEvent.ACTION_MOVE, bodyX, bodyY - 110f)
            f.event(MotionEvent.ACTION_CANCEL, bodyX, bodyY - 110f)
            assertEquals(afterDrop, f.rows.ids)
            assertEquals(movesAfterDrop, f.moves)
            assertEquals(listOf(afterDrop), f.commits)
            f.drag.close()
            f.drag.clearView(f.recycler, holder)
            assertEquals(1, f.commits.size)
        }
    }

    @Test fun unarmedProgrammaticStartAndSelectionCannotPrepareOrMoveAnyLevel() {
        for (level in listOf("root", "tags", "members")) Fixture(level).use { f ->
            val holder = f.holder()
            f.touch.startDrag(holder)
            f.drag.onSelectedChanged(holder, ItemTouchHelper.ACTION_STATE_DRAG)
            assertFalse(f.drag.isDragging)
            assertEquals(0, f.prepares)
            assertFalse(f.drag.onMove(f.recycler, holder, f.holder(2)))
            f.event(MotionEvent.ACTION_DOWN, 32f, 100f)
            assertFalse(f.drag.onMove(f.recycler, holder, f.holder(2)))
            f.event(MotionEvent.ACTION_CANCEL, 32f, 100f)
            assertTrue(f.commits.isEmpty())
        }
    }

    @Test fun cancelAndCloseRemainSingleCommitOrRollbackOnAllThreeLevels() {
        for (level in listOf("root", "tags", "members")) for (cancel in listOf(false, true)) {
            Fixture(level).use { f ->
                val original = f.rows.ids
                val holder = f.holder()
                val (x, y) = f.point(holder, true)
                f.event(MotionEvent.ACTION_DOWN, x, y)
                assertTrue("$level handle at ($x,$y), size=${f.handle(holder).width}x${f.handle(holder).height}, prepares=${f.prepares}", f.drag.isDragging)
                assertTrue(f.drag.onMove(f.recycler, holder, f.holder(2)))
                val finalIds = f.rows.ids
                if (cancel) f.event(MotionEvent.ACTION_CANCEL, x, y)
                f.drag.close()
                f.drag.clearView(f.recycler, holder)
                f.drag.close()
                assertEquals(if (cancel) original else finalIds, f.rows.ids)
                assertEquals(if (cancel) 1 else 0, f.cancellations)
                assertEquals(if (cancel) emptyList<List<Long>>() else listOf(finalIds), f.commits)
                assertEquals(0, f.drag.getDragDirs(f.recycler, holder))
                assertFalse(f.drag.armHandle(holder))
            }
        }
    }
}
