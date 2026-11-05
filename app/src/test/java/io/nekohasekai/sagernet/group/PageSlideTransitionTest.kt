package io.nekohasekai.sagernet.group

import android.animation.ValueAnimator
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.PageSlideTransition
import io.nekohasekai.sagernet.ui.ToolbarFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PageSlideTransitionTest {
    @Test fun forwardMovesBothPagesLeftAndCancelRestoresCachedProperties() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java)
        activity.get().setTheme(R.style.Theme_SagerNet_Blue)
        activity.setup().visible()
        val host = FrameLayout(activity.get())
        val old = View(activity.get())
        val next = View(activity.get())
        host.addView(old); host.addView(next)
        activity.get().setContentView(host)
        host.layout(0, 0, 360, 640)
        var settled = 0
        val slide = PageSlideTransition { settled++ }
        try {
            val exit = slide.create(old, false, R.animator.page_slide_forward) as ValueAnimator
            val enter = slide.create(next, true, R.animator.page_slide_forward) as ValueAnimator
            assertEquals(0f, old.translationX, 0f)
            assertEquals(360f, next.translationX, 0f)
            assertEquals(PageSlideTransition.DURATION_MS, enter.duration)
            exit.start(); enter.start()
            exit.currentPlayTime = 130; enter.currentPlayTime = 130
            assertTrue(old.translationX < 0f)
            assertTrue(next.translationX > 0f && next.translationX < 360f)
            assertEquals(1f, next.alpha, 0f)
            assertTrue(slide.isRunning)
            slide.cancel()
            assertEquals(0f, old.translationX, 0f)
            assertEquals(0f, next.translationX, 0f)
            assertFalse(slide.isRunning)
            assertEquals(1, settled)
            slide.cancel()
            assertEquals(1, settled)
        } finally { slide.cancel(); activity.pause().stop().destroy() }
    }

    @Test fun backReversesDirectionAndUnstartedCancelCannotLeavePageOffscreen() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val view = View(context)
        view.layout(0, 0, 320, 640)
        var settled = 0
        val slide = PageSlideTransition { settled++ }
        val enter = slide.create(view, true, R.animator.page_slide_backward) as ValueAnimator
        assertEquals(-320f, view.translationX, 0f)
        slide.cancel() // Fragment state-save can happen between create and start.
        assertFalse(slide.isRunning)
        assertEquals(0f, view.translationX, 0f)
        assertEquals(1, settled)
        enter.start(); enter.end() // A late framework start cannot reapply stale translations.
        assertEquals(0f, view.translationX, 0f)
        val exit = slide.create(view, false, R.animator.page_slide_backward) as ValueAnimator
        exit.start(); exit.currentPlayTime = 130
        assertTrue(view.translationX > 0f)
        exit.end()
        assertEquals(0f, view.translationX, 0f)
        assertEquals(1f, view.alpha, 0f)
        assertFalse(slide.isRunning)
    }

    @Test fun startupRestoreAndOrdinaryHideHaveNoAnimationOrScrollReset() {
        val view = View(org.robolectric.RuntimeEnvironment.getApplication())
        val slide = PageSlideTransition()
        assertNull(slide.create(view, true, 0))
        assertFalse(slide.isRunning)
        var resets = 0
        val page = object : ToolbarFragment() {
            override fun resetScrollState() { resets++ }
        }
        page.onHiddenChanged(true); page.onHiddenChanged(false)
        assertEquals("Outgoing pages remain visible during slide; ordinary hide must not jump top", 0, resets)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
