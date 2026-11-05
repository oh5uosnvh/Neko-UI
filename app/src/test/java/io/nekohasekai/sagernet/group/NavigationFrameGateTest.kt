package io.nekohasekai.sagernet.group

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentContainerView
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.NavigationFrameGate
import io.nekohasekai.sagernet.ui.PageSlideTransition
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class NavigationFrameGateTest {
    @Before fun animationClock() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, 1f)
    }
    @After fun restoreClock() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, 1f)
    }

    private class Fixture : AutoCloseable {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java)
        val host = FrameLayout(activity.get())
        val old = View(activity.get())
        val next = View(activity.get())
        private val canvas = Canvas()
        init {
            activity.get().setTheme(R.style.Theme_SagerNet_Blue)
            activity.setup()
            host.addView(old, FrameLayout.LayoutParams(-1, -1))
            host.addView(next, FrameLayout.LayoutParams(-1, -1))
            activity.get().setContentView(host, FrameLayout.LayoutParams(360, 640))
            activity.visible()
            frames(2)
            assertTrue("The window must attach before arming a frame gate", host.isAttachedToWindow)
            assertEquals(360, host.width)
            assertEquals(640, host.height)
        }
        fun layout() {
            host.requestLayout()
            frames(2)
        }
        fun frames(count: Int = 6) {
            repeat(count) {
                val before = SystemClock.uptimeMillis()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                // LEGACY has no rendering surface: finish the draw to clear View's dirty flags,
                // otherwise postInvalidateOnAnimation cannot request another traversal.
                // Let ViewRootImpl deliver pre-draw; never count a synthetic callback as a frame.
                host.draw(canvas)
                assertEquals("A frame must not auto-drain the animation or safety timeout",
                    16L, SystemClock.uptimeMillis() - before)
            }
        }
        override fun close() { activity.pause().stop().destroy() }
    }

    @Test fun coldPageDoesNotSpendAnimationTimeBeforeNestedContentAndFirstDraw() {
        Fixture().use { f ->
            var contentReady = false
            var settled = 0
            val slide = PageSlideTransition { settled++ }
            slide.prepare(f.host, { true }, { contentReady }, { it === f.old || it === f.next })
            val exit = slide.create(f.old, false, R.animator.page_slide_forward) as ValueAnimator
            val enter = slide.create(f.next, true, R.animator.page_slide_forward) as ValueAnimator
            exit.start(); enter.start()
            val waitingSince = SystemClock.uptimeMillis()
            repeat(20) { // Longer than the 260 ms motion, but well below the safety timeout.
                f.frames(1)
                assertFalse(exit.isStarted)
                assertFalse(enter.isStarted)
                assertEquals(0L, exit.currentPlayTime)
                assertEquals(0L, enter.currentPlayTime)
                assertEquals(0f, f.old.translationX, 0f)
                assertEquals(360f, f.next.translationX, 0f)
                assertTrue(slide.isRunning)
                assertEquals(0, settled)
            }
            assertEquals(320L, SystemClock.uptimeMillis() - waitingSince)
            contentReady = true
            repeat(NavigationFrameGate.CLEAN_FRAMES) {
                f.frames(1)
                assertFalse("A clean traversal must finish drawing before playback", exit.isStarted)
                assertFalse(enter.isStarted)
                assertEquals(0L, exit.currentPlayTime)
                assertEquals(0L, enter.currentPlayTime)
                assertEquals(0f, f.old.translationX, 0f)
                assertEquals(360f, f.next.translationX, 0f)
            }
            f.frames(4)
            assertTrue("exit started=${exit.isStarted}, playTime=${exit.currentPlayTime}, " +
                "settled=$settled, running=${slide.isRunning}", exit.isStarted)
            assertTrue(enter.isStarted)
            assertTrue(exit.currentPlayTime in 1 until PageSlideTransition.DURATION_MS)
            assertTrue(enter.currentPlayTime in 1 until PageSlideTransition.DURATION_MS)
            assertEquals(exit.currentPlayTime, enter.currentPlayTime)
            assertTrue(f.old.translationX > -360f && f.old.translationX < 0f)
            assertTrue(f.next.translationX > 0f && f.next.translationX < 360f)
            assertEquals(0, settled)
            slide.cancel(); slide.cancel()
            f.frames(20)
            assertFalse(slide.isRunning)
            assertEquals(1, settled)
            assertEquals(0f, f.old.translationX, 0f)
            assertEquals(0f, f.next.translationX, 0f)
        }
    }

    @Test fun cleanDrawsMustBeConsecutiveAndRecheckedAfterThePost() {
        Fixture().use { f ->
            var ready = false
            var calls = 0
            var aborted = 0
            val gate = NavigationFrameGate(f.host, { true }, { ready }, { calls++ }, { aborted++ })
            gate.await()
            ready = true
            f.frames(1)
            ready = false
            f.frames(1) // Invalidates the first clean frame.
            ready = true
            f.frames(1)
            assertEquals(0, calls)
            f.frames(1)
            assertEquals(0, calls)
            ready = false // A bind was queued between traversal and postOnAnimation.
            f.frames(2)
            assertEquals(0, calls)
            ready = true
            f.frames(1)
            assertEquals(0, calls)
            f.frames(1)
            assertEquals(0, calls)
            f.frames(1)
            assertEquals(1, calls)
            f.frames()
            assertEquals(1, calls)
            assertEquals(0, aborted)
            gate.cancel(); gate.cancel()
            assertEquals(0, aborted)
        }
    }

    @Test fun stateSaveDetachAndLateFrameworkStartCannotResurrectWaitingAnimation() {
        Fixture().use { f ->
            var valid = true
            var settled = 0
            val slide = PageSlideTransition { settled++ }
            slide.prepare(f.host, { valid }, { false }, { true })
            val enter = slide.create(f.next, true, R.animator.page_slide_forward) as ValueAnimator
            enter.start()
            valid = false
            f.frames(2)
            assertFalse(slide.isRunning)
            assertEquals(0f, f.next.translationX, 0f)
            assertEquals(1, settled)
            enter.start(); enter.end()
            f.frames()
            assertEquals(0f, f.next.translationX, 0f)
            assertEquals(1, settled)
            slide.cancel()
            var aborted = 0
            val gate = NavigationFrameGate(f.host, { true }, { false }, {}, { aborted++ })
            gate.await()
            f.activity.get().setContentView(FrameLayout(f.activity.get()))
            assertEquals(1, aborted)
            f.frames()
            assertEquals(1, aborted)
        }
    }

    @Test fun frameworkCancelBeforeStartCompletesEffectExactlyOnce() {
        Fixture().use { f ->
            var settled = 0
            val slide = PageSlideTransition { settled++ }
            slide.prepare(f.host, { true }, { false }, { true })
            val enter = slide.create(f.next, true, R.animator.page_slide_forward) as ValueAnimator
            var ended = 0
            enter.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { ended++ }
            })
            enter.start()
            enter.cancel()
            assertFalse(slide.isRunning)
            assertEquals(1, ended)
            assertEquals(1, settled)
            assertEquals(0f, f.next.translationX, 0f)
            f.frames()
            assertEquals(1, ended)
        }
    }

    @Test fun timeoutSettlesWithoutStartingAnUnreadyPageAndCancellationIsIdempotent() {
        Fixture().use { f ->
            var starts = 0
            var aborted = 0
            val gate = NavigationFrameGate(f.host, { true }, { false }, { starts++ }, { aborted++ })
            gate.await()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NavigationFrameGate.MAX_WAIT_MS + 16))
            assertEquals(0, starts)
            assertEquals(1, aborted)
            gate.cancel(); gate.cancel()
            f.frames()
            assertEquals(1, aborted)
        }
    }

    @Test fun emptyNestedFragmentAndPendingRecyclerBindAreNotFirstFrameReady() {
        Fixture().use { f ->
            val nested = FragmentContainerView(f.activity.get())
            f.host.addView(nested, FrameLayout.LayoutParams(360, 640))
            f.layout()
            assertFalse(NavigationFrameGate.isPageReady(f.host))
            f.host.removeView(nested)
            val list = RecyclerView(f.activity.get())
            f.host.addView(list, FrameLayout.LayoutParams(360, 640))
            f.layout()
            assertFalse(NavigationFrameGate.isPageReady(f.host))
            f.host.removeView(list)
            f.layout()
            assertTrue(NavigationFrameGate.isPageReady(f.host))
        }
    }
}
