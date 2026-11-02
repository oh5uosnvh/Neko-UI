package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ui.GroupPickerAdapter
import io.nekohasekai.sagernet.ui.GroupPickerDragCallback
import io.nekohasekai.sagernet.ui.GroupPickerRecyclerView
import io.nekohasekai.sagernet.ui.groupPickerItemAnimator
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GroupPickerAnimationTest {
    @Before fun pauseVsyncClock() {
        // LooperMode.PAUSED alone still auto-advances unpaused VSYNC through the whole animation.
        // Keep the real 250ms animator and advance frames at a realistic refresh cadence.
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
    }

    @After fun restoreVsyncClock() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }

    private class Fixture : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val recycler = GroupPickerRecyclerView(controller.get())
        val animator = groupPickerItemAnimator()
        val commits = ArrayList<List<Long>>()
        var cancellations = 0
        var changes = 0
        var moves = 0
        val adapter = GroupPickerAdapter(controller.get(), (1L..3L).map {
            ProxyGroup(id = it, userOrder = it, name = "Group $it")
        }, { 2L }, { ColorDrawable(Color.GRAY) }, Color.BLACK, Color.DKGRAY, {})
        val drag = GroupPickerDragCallback(adapter, { commits.add(it) }, { cancellations++ }, {})

        init {
            recycler.layoutManager = LinearLayoutManager(controller.get())
            recycler.itemAnimator = animator
            recycler.adapter = adapter
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { changes++ }
                override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) { changes++ }
                override fun onItemRangeMoved(from: Int, to: Int, count: Int) { moves++ }
            })
            controller.get().setContentView(recycler)
            layout()
            advance(300)
        }

        fun layout() {
            recycler.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            recycler.layout(0, 0, 500, 800)
        }
        fun holder(id: Long) = recycler.findViewHolderForItemId(id) as GroupPickerAdapter.Holder
        fun advance(ms: Long) {
            // A single large clock jump triggers only one paused VSYNC. Drive each real frame.
            var remaining = ms
            while (remaining > 0L) {
                val step = minOf(16L, remaining)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
                remaining -= step
            }
        }
        fun move() {
            drag.onSelectedChanged(holder(1), ItemTouchHelper.ACTION_STATE_DRAG)
            assertTrue(drag.onMove(recycler, holder(1), holder(3)))
            layout()
            animator.runPendingAnimations()
        }
        override fun close() {
            drag.close()
            animator.endAnimations()
            recycler.adapter = null
            controller.pause().stop().destroy()
        }
    }

    @Test fun neighbourMovesThroughIntermediateTranslationThenReturnsToZero() {
        Fixture().use { f ->
            assertFalse(f.animator.supportsChangeAnimations)
            assertEquals(250L, f.animator.moveDuration)
            val neighbour = f.holder(2)
            val originalTop = neighbour.itemView.top
            f.move()
            assertSame(neighbour, f.holder(2))
            assertTrue(f.animator.isRunning)
            assertTrue(neighbour.itemView.top < originalTop)
            val initial = abs(neighbour.itemView.translationY)
            assertTrue("The non-dragged row must start from its old visual position", initial > 0f)
            f.advance(100)
            val intermediate = abs(neighbour.itemView.translationY)
            assertTrue("Neighbour must interpolate rather than jump", intermediate > 0f && intermediate < initial)
            assertEquals(1f, neighbour.itemView.alpha, 0f)
            f.advance(400)
            assertEquals(0f, neighbour.itemView.translationY, 0.01f)
            assertEquals(0f, neighbour.itemView.translationX, 0.01f)
        }
    }

    @Test fun countsAndReleaseDoNotRebindWhileNeighbourIsMoving() {
        Fixture().use { f ->
            f.move()
            f.adapter.updateCounts(mapOf(2L to 34L))
            f.drag.clearView(f.recycler, f.holder(1))
            assertEquals(listOf(listOf(2L, 3L, 1L)), f.commits)
            assertEquals(0, f.changes)
            f.advance(100)
            assertEquals(0, f.changes)
            f.advance(400)
            f.layout()
            assertTrue(f.changes > 0)
            assertEquals("Group 2·34", f.holder(2).label.text.toString())
            f.drag.clearView(f.recycler, f.holder(1))
            assertEquals(1, f.commits.size)
        }
    }

    @Test fun cancelRollsBackWithMovesWithoutFullInvalidationOrCommit() {
        Fixture().use { f ->
            f.move()
            f.drag.cancelGesture()
            f.drag.clearView(f.recycler, f.holder(1))
            assertEquals(listOf(1L, 2L, 3L), f.adapter.ids)
            assertTrue(f.moves > 1)
            assertEquals(0, f.changes)
            assertTrue(f.commits.isEmpty())
            assertEquals(1, f.cancellations)
            f.layout()
            f.animator.runPendingAnimations()
            f.advance(500)
            for (id in 1L..3L) assertEquals(0f, f.holder(id).itemView.translationY, 0.01f)
        }
    }

    @Test fun newGestureAndLateRecoveryDoNotCommitOrRebindTheNewOrder() {
        Fixture().use { f ->
            f.move()
            val old = f.holder(1)
            val next = f.holder(2)
            f.drag.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE)
            f.drag.onSelectedChanged(next, ItemTouchHelper.ACTION_STATE_DRAG)
            assertEquals(0f, next.itemView.translationY, 0.01f)
            f.drag.clearView(f.recycler, old)
            assertTrue(f.drag.isDragging)
            assertTrue(f.drag.onMove(f.recycler, next, f.holder(3)))
            f.drag.clearView(f.recycler, next)
            assertEquals(listOf(listOf(2L, 3L, 1L), listOf(3L, 2L, 1L)), f.commits)
            assertEquals(0, f.changes)
            f.drag.close()
            f.drag.clearView(f.recycler, next)
            assertEquals(2, f.commits.size)
        }
    }
}
