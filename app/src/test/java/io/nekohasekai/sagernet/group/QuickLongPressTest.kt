package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.nekohasekai.sagernet.widget.QuickLongPress
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class QuickLongPressTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var root: FrameLayout
    private lateinit var target: TextView
    private lateinit var binding: QuickLongPress
    private var downTime = 0L
    private var clicks = 0
    private var longClicks = 0
    private var downConsumed = true

    @Before fun setUp() {
        val activity = controller.setup().visible().get()
        root = FrameLayout(activity)
        target = TextView(activity).apply {
            text = "Group"
            setOnClickListener { clicks++ }
        }
        root.addView(target, FrameLayout.LayoutParams(600, 100))
        activity.setContentView(root)
        root.measure(exactly(800), exactly(600))
        root.layout(0, 0, 800, 600)
        binding = QuickLongPress(target) { longClicks++; true }
        assertTrue(target.isAttachedToWindow)
        assertTrue(target.isShown)
        // Observe the real dispatch path, including native View click/long-click.
        target.setOnTouchListener { view, event ->
            binding.onTouch(view, event).also {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) downConsumed = it
            }
        }
    }

    @After fun tearDown() {
        binding.unbind()
        controller.pause().stop().destroy()
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun touch(action: Int, x: Float = 40f, y: Float = 40f): Boolean {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        return try { target.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun exactly200msTriggersBut199msDoesNotAndDownIsNotConsumed() {
        assertEquals(200L, QuickLongPress.TIMEOUT_MILLIS)
        touch(MotionEvent.ACTION_DOWN)
        assertFalse(downConsumed)
        advance(199)
        assertEquals(0, longClicks)
        advance(1)
        assertEquals(1, longClicks)
        assertEquals(0, clicks)
    }

    @Test fun shortTapKeepsTheOriginalClickAndCancelsCallback() {
        touch(MotionEvent.ACTION_DOWN)
        advance(199)
        touch(MotionEvent.ACTION_UP)
        advance(1000)
        assertEquals(1, clicks)
        assertEquals(0, longClicks)
    }

    @Test fun quickTapInScrollingParentKeepsPrepressedClick() {
        root.removeView(target)
        val scroll = HorizontalScrollView(root.context)
        val row = LinearLayout(root.context)
        row.addView(target, LinearLayout.LayoutParams(600, 100))
        scroll.addView(row)
        root.addView(scroll, FrameLayout.LayoutParams(240, 100))
        root.measure(exactly(800), exactly(600))
        root.layout(0, 0, 800, 600)
        touch(MotionEvent.ACTION_DOWN)
        advance(20) // Before ViewConfiguration.getTapTimeout(), still PREPRESSED.
        touch(MotionEvent.ACTION_UP)
        advance(1000)
        assertEquals(1, clicks)
        assertEquals(0, longClicks)
    }

    @Test fun horizontalMoveBeyondSlopCancelsEvenIfFingerReturns() {
        val slop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()
        touch(MotionEvent.ACTION_DOWN)
        advance(100)
        touch(MotionEvent.ACTION_MOVE, 40f + slop + 1f)
        touch(MotionEvent.ACTION_MOVE)
        advance(1000)
        touch(MotionEvent.ACTION_UP)
        assertEquals(0, longClicks)
    }

    @Test fun smallMovementStillAllowsLongPress() {
        val slop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_MOVE, 40f + slop / 2)
        advance(200)
        assertEquals(1, longClicks)
    }

    @Test fun cancelRemovesPendingWorkAndAllowsNextGesture() {
        touch(MotionEvent.ACTION_DOWN)
        advance(199)
        touch(MotionEvent.ACTION_CANCEL)
        advance(1000)
        assertEquals(0, longClicks)
        assertEquals(0, clicks)
        touch(MotionEvent.ACTION_DOWN)
        advance(200)
        assertEquals(1, longClicks)
    }

    @Test fun secondPointerCancelsTheWholeGesture() {
        touch(MotionEvent.ACTION_DOWN)
        advance(100)
        val properties = Array(2) { id -> MotionEvent.PointerProperties().apply {
            this.id = id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(2) { id -> MotionEvent.PointerCoords().apply {
            x = 40f + id * 20f; y = 40f; pressure = 1f; size = 1f
        } }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { target.dispatchTouchEvent(event) } finally { event.recycle() }
        advance(1000)
        touch(MotionEvent.ACTION_UP)
        assertEquals(0, longClicks)
    }

    @Test fun triggeredPressSuppressesUpClickAndPlatformRepeat() {
        touch(MotionEvent.ACTION_DOWN)
        advance(200)
        assertEquals(1, longClicks)
        assertFalse(target.isPressed)
        advance(1000) // Past Android's ordinary long-press timeout.
        assertEquals(1, longClicks)
        assertTrue(target.performLongClick()) // Same touch stream, no second menu.
        assertEquals(1, longClicks)
        touch(MotionEvent.ACTION_UP)
        advance(1000)
        assertEquals(0, clicks)
        touch(MotionEvent.ACTION_DOWN)
        advance(20)
        touch(MotionEvent.ACTION_UP)
        advance(1000)
        assertEquals(1, clicks)
        assertEquals(1, longClicks)
    }

    @Test fun keyboardAndAccessibilityLongClickRemainAvailable() {
        assertTrue(target.isLongClickable)
        assertTrue(target.performLongClick())
        assertEquals(1, longClicks)
        assertEquals(0, clicks)
        assertTrue(target.performLongClick())
        assertEquals(2, longClicks)
    }

    @Test fun detachingCancelsPendingCallbackAndReattachCanStartFresh() {
        touch(MotionEvent.ACTION_DOWN)
        advance(199)
        root.removeView(target)
        advance(1000)
        assertEquals(0, longClicks)
        root.addView(target, FrameLayout.LayoutParams(600, 100))
        root.measure(exactly(800), exactly(600))
        root.layout(0, 0, 800, 600)
        advance(1000)
        assertEquals(0, longClicks)
        touch(MotionEvent.ACTION_DOWN)
        advance(200)
        assertEquals(1, longClicks)
    }

    @Test fun unbindRemovesCallbacksAndListenersWithoutLosingClick() {
        touch(MotionEvent.ACTION_DOWN)
        advance(199)
        binding.unbind()
        binding.unbind() // Idempotent cleanup for pooled views / Fragment teardown.
        advance(1000)
        assertEquals(0, longClicks)
        assertFalse(target.isLongClickable)
        assertFalse(target.performLongClick())
        touch(MotionEvent.ACTION_CANCEL)
        touch(MotionEvent.ACTION_DOWN)
        advance(20)
        touch(MotionEvent.ACTION_UP)
        advance(1000)
        assertEquals(1, clicks)
    }

    @Test fun tabStripCanInterceptHorizontalScrollingBeforeTimeout() {
        root.removeView(target)
        val scroll = HorizontalScrollView(root.context)
        val row = LinearLayout(root.context)
        row.addView(target, LinearLayout.LayoutParams(1200, 100))
        scroll.addView(row)
        root.addView(scroll, FrameLayout.LayoutParams(240, 100))
        root.measure(exactly(800), exactly(600))
        root.layout(0, 0, 800, 600)
        fun dispatch(action: Int, x: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, 40f, 0)
            try { scroll.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, 200f)
        advance(40)
        dispatch(MotionEvent.ACTION_MOVE, 100f)
        advance(40)
        dispatch(MotionEvent.ACTION_MOVE, 40f)
        assertTrue("Parent must remain free to scroll the tab strip", scroll.scrollX > 0)
        dispatch(MotionEvent.ACTION_UP, 40f)
        advance(1000)
        assertEquals(0, longClicks)
        assertEquals(0, clicks)
    }
}
