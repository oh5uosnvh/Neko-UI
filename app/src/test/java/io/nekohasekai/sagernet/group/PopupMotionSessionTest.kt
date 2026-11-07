package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.PopupEntrance
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PopupMotionSessionTest {
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 16L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }
    private fun fixture(block: (FrameLayout, View, View) -> Unit) {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet) }
        controller.setup().visible()
        val resolver = activity.contentResolver
        val scaleKey = android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
        val previousScale = android.provider.Settings.Global.getFloat(resolver, scaleKey, 1f)
        // Another state test deliberately exercises disabled system animations.
        // This motion contract explicitly requires a unit-duration test clock.
        android.provider.Settings.Global.putFloat(resolver, scaleKey, 1f)
        val host = FrameLayout(activity)
        activity.setContentView(host)
        val anchor = View(activity)
        host.addView(anchor, FrameLayout.LayoutParams(40, 40).apply { leftMargin = 300; topMargin = 30 })
        val surface = FrameLayout(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        try { block(host, anchor, surface) }
        finally {
            PopupEntrance.cancel(surface)
            android.provider.Settings.Global.putFloat(resolver, scaleKey, previousScale)
            controller.pause().stop().destroy()
            ShadowChoreographer.setPaused(false)
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
        }
    }
    private fun mount(host: FrameLayout, surface: View) {
        host.addView(surface, FrameLayout.LayoutParams(200, 240).apply { leftMargin = 140; topMargin = 72 })
        host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 360, 640)
    }
    @Test fun deferredAttachmentStartsARealSpringInsteadOfSkippingTheEntrance() = fixture { host, anchor, surface ->
        val businessTag = Any()
        surface.tag = businessTag
        PopupEntrance.animate(surface, false, anchor)
        assertEquals(0f, surface.alpha, 0f)
        mount(host, surface)
        // Seek the real session animator to an exact early frame. Other classes
        // exercise global animation scaling; wall-clock advances are not an exact
        // progress sampler when the whole suite shares the framework sandbox.
        val sessions = PopupEntrance::class.java.getDeclaredField("sessions")
            .apply { isAccessible = true }.get(PopupEntrance) as Map<*, *>
        val session = requireNotNull(sessions[surface])
        val animator = session.javaClass.getDeclaredField("animator")
            .apply { isAccessible = true }.get(session) as android.animation.ValueAnimator
        animator.currentPlayTime = 96L
        assertTrue("spring early frame alpha=${surface.alpha} scale=${surface.scaleX}",
            surface.alpha > 0f && surface.alpha < 1f)
        assertTrue("grows out of anchor, not a decorative .95 scale", surface.scaleX < .95f)
        advance(600)
        assertEquals(1f, surface.alpha, 0f)
        assertEquals(1f, surface.scaleX, 0f)
        assertEquals(0f, surface.translationX, 0f)
        assertEquals(0f, surface.translationY, 0f)
        assertSame(businessTag, surface.tag)
    }
    @Test fun closeShrinksAndFadesBeforeDeliveringExactlyOnce() = fixture { host, anchor, surface ->
        mount(host, surface)
        PopupEntrance.animate(surface, false, anchor)
        advance(600)
        var calls = 0
        PopupEntrance.exit(surface, false, anchor) { calls++ }
        assertEquals(0, calls)
        advance(96)
        assertEquals(0, calls)
        assertTrue(surface.alpha > 0f && surface.alpha < 1f)
        assertTrue(surface.scaleX < 1f && surface.scaleX > .79f)
        advance(240)
        assertEquals(1, calls)
        assertEquals(0f, surface.alpha, 0f)
        assertEquals(.8f, surface.scaleX, .001f)
        advance(300)
        assertEquals(1, calls)
    }
    @Test fun cancellingCloseForAReopenNeverDeliversAnObsoleteDismissCallback() = fixture { host, anchor, surface ->
        mount(host, surface)
        PopupEntrance.animate(surface, false, anchor)
        advance(600)
        var stale = 0
        PopupEntrance.exit(surface, false, anchor) { stale++ }
        advance(48)
        PopupEntrance.animate(surface, false, anchor)
        advance(600)
        assertEquals(0, stale)
        assertEquals(1f, surface.alpha, 0f)
        assertEquals(1f, surface.scaleX, 0f)
    }
    @Test fun detachCancelsWithoutDeliveringAPendingSelection() = fixture { host, anchor, surface ->
        mount(host, surface)
        PopupEntrance.animate(surface, false, anchor)
        advance(600)
        var calls = 0
        PopupEntrance.exit(surface, false, anchor) { calls++ }
        advance(48)
        host.removeView(surface)
        advance(600)
        assertEquals(0, calls)
    }
}
