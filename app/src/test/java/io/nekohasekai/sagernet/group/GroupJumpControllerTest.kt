package io.nekohasekai.sagernet.group

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.ui.GroupJumpController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class GroupJumpControllerTest {
    @Before fun pauseFrames() {
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ShadowChoreographer.setPaused(true)
    }
    @After fun restoreFrames() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }
    private class Fixture : AutoCloseable {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val list = RecyclerView(activity.get())
        val manager = LinearLayoutManager(activity.get())
        val ids = (1L..40L).toMutableList()
        var ready = true
        var clicks = 0
        var clears = 0
        val highlights = ArrayList<Long>()
        val times = ArrayList<Long>()
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            init { setHasStableIds(true) }
            override fun getItemCount() = ids.size
            override fun getItemId(position: Int) = ids[position]
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(-1, 80)
                    setOnClickListener { clicks++ }
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as TextView).text = "Group ${ids[position]}"
                holder.itemView.layoutParams.height = 64 + (position % 3) * 16
            }
        }
        val jump: GroupJumpController
        init {
            list.layoutManager = manager
            list.adapter = adapter
            list.itemAnimator = null
            activity.get().setContentView(list)
            draw()
            shadowOf(Looper.getMainLooper()).idle()
            jump = GroupJumpController(list, manager, { ids }, { ready }, {
                highlights.add(it); times.add(SystemClock.uptimeMillis())
            }, { clears++ })
        }
        fun draw() {
            list.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 320, 480)
            list.viewTreeObserver.dispatchOnPreDraw()
        }
        fun frames(count: Int) {
            repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); draw() }
        }
        fun animator(): ValueAnimator? = ReflectionHelpers.getField(jump, "animator")
        fun begin(id: Long) { jump.prepare(id); jump.start(); draw(); draw() }
        override fun close() { jump.close(); list.adapter = null; activity.pause().stop().destroy() }
    }

    @Test fun cachedBottomFirstDrawIsTopThenReal500MsScrollHighlightsStableIdOnce() {
        Fixture().use { f ->
            f.manager.scrollToPositionWithOffset(30, 0); f.draw()
            assertEquals(30, f.manager.findFirstVisibleItemPosition())
            f.jump.prepare(21); f.jump.start(); f.draw()
            assertEquals(0, f.manager.findFirstVisibleItemPosition())
            assertNull("First pre-draw must present card zero without starting scroll", f.animator())
            assertTrue(f.highlights.isEmpty())
            f.draw()
            assertEquals(500L, f.animator()!!.duration)
            val start = SystemClock.uptimeMillis()
            f.frames(15)
            assertTrue(f.manager.findFirstVisibleItemPosition() in 1..19)
            assertTrue("No highlight before the scroll's final laid-out frame", f.highlights.isEmpty())
            f.frames(24)
            assertEquals(20, f.manager.findFirstVisibleItemPosition())
            assertEquals(listOf(21L), f.highlights)
            assertTrue(f.times.single() - start >= GroupJumpController.SCROLL_DURATION_MS)
            assertEquals(21L, f.list.findViewHolderForItemId(21)!!.itemId)
            f.jump.start(); f.frames(20)
            assertEquals(listOf(21L), f.highlights)
            assertEquals("Feedback is visual, never a real card click", 0, f.clicks)
        }
    }

    @Test fun readinessNotificationWakesWaitWithoutBusyLoopOrEarlyScroll() {
        Fixture().use { f ->
            f.ready = false
            f.begin(18)
            f.frames(30)
            assertEquals(0, f.manager.findFirstVisibleItemPosition())
            assertNull(f.animator())
            assertTrue(f.highlights.isEmpty())
            f.ready = true
            f.jump.onReady()
            f.frames(40)
            assertEquals(listOf(18L), f.highlights)
        }
    }

    @Test fun latestTargetWinsAndCancellationDeletionReorderOrHiddenNeverHighlightsWrongId() {
        Fixture().use { f ->
            f.begin(30); f.frames(8)
            f.begin(12); f.frames(40)
            assertEquals(listOf(12L), f.highlights)
            f.highlights.clear()
            f.begin(25); f.frames(8)
            f.jump.cancel(); f.frames(40)
            assertTrue(f.highlights.isEmpty())
            f.begin(26); f.frames(8)
            val index = f.ids.indexOf(26L)
            f.ids.removeAt(index); f.adapter.notifyItemRemoved(index)
            f.frames(40)
            assertTrue(f.highlights.isEmpty())
            f.begin(27); f.frames(8)
            java.util.Collections.swap(f.ids, 0, 1)
            f.adapter.notifyItemMoved(0, 1); f.frames(40)
            assertTrue(f.highlights.isEmpty())
            f.begin(28); f.frames(8)
            f.ready = false; f.list.visibility = View.INVISIBLE; f.frames(40)
            assertTrue(f.highlights.isEmpty())
            assertNull(f.animator())
        }
    }

    @Test fun finalPredrawRejectsReplacementIdEvenAfterAnimatorHasEnded() {
        Fixture().use { f ->
            f.begin(20)
            f.animator()!!.end() // Queue final layout, but do not permit its pre-draw yet.
            f.ids[19] = 999L
            f.adapter.notifyItemChanged(19)
            f.draw(); f.frames(40)
            assertTrue(f.highlights.isEmpty())
            assertEquals(0, f.clicks)
            f.jump.prepare(-1); f.jump.start(); f.frames(4)
            assertTrue(f.highlights.isEmpty())
        }
    }

    @Test fun realTouchAndDetachCancelWithoutConsumingUserInteraction() {
        Fixture().use { f ->
            f.begin(30); f.frames(6)
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 30f, 30f, 0).let {
                f.list.dispatchTouchEvent(it); it.recycle()
            }
            f.frames(40)
            assertTrue(f.highlights.isEmpty())
            assertNull(f.animator())
            f.begin(25); f.frames(6)
            f.activity.get().setContentView(View(f.activity.get()))
            f.frames(40)
            assertTrue(f.highlights.isEmpty())
            assertNull(f.animator())
        }
    }
}
