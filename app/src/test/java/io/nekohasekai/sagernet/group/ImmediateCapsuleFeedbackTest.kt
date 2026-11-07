package io.nekohasekai.sagernet.group

import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.widget.ControlBar
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
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** LEGACY coverage for the tap-first capsule contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ImmediateCapsuleFeedbackTest {
    @Before fun pauseFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After fun restoreFrames() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    private fun field(bar: ControlBar, name: String): Any? =
        ControlBar::class.java.getDeclaredField(name).apply { isAccessible = true }.get(bar)

    private fun withBar(block: (ControlBar) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_SagerNet)
        controller.setup().visible()
        val host = FrameLayout(activity)
        val bar = ControlBar(activity)
        host.addView(bar, FrameLayout.LayoutParams(584, 168))
        activity.setContentView(host)
        host.measure(View.MeasureSpec.makeMeasureSpec(584, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(168, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 584, 168)
        advance(32)
        try { block(bar) } finally { controller.pause().stop().destroy() }
    }

    private fun ControlBar.box(id: Int) = findViewById<View>(id)
    private fun ControlBar.text(id: Int) = findViewById<TextView>(id)

    @Test fun tapStartsImmediateAnimationButNeverFakesConnectedReadouts() = withBar { bar ->
        var toggles = 0
        bar.onToggle = { toggles++ }
        val capsule = bar.box(R.id.capsule)

        capsule.performClick()
        assertEquals(1, toggles)
        assertNotNull(field(bar, "fadeAnimator"))
        assertFalse("isSelected is authoritative service state", capsule.isSelected)
        assertFalse(bar.box(R.id.speed_box).isEnabled)
        assertFalse(bar.box(R.id.latency_box).isEnabled)

        advance(550)
        assertEquals(1f, bar.box(R.id.capsule_plane_layer).alpha, .001f)
        assertFalse(capsule.isSelected)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        assertEquals(bar.resources.getColor(R.color.control_bar_text, bar.context.theme),
            bar.text(R.id.latency).currentTextColor)
        assertEquals(1, toggles)
    }

    @Test fun connectingKeepsTapAnimatorAndConnectedConfirmationDoesNotRestartIt() = withBar { bar ->
        var toggles = 0
        bar.onToggle = { toggles++ }
        val capsule = bar.box(R.id.capsule)

        capsule.performClick()
        val startupFade = field(bar, "fadeAnimator")
        assertNotNull(startupFade)
        bar.changeState(BaseService.State.Connecting)
        assertSame(startupFade, field(bar, "fadeAnimator"))
        assertFalse(capsule.isSelected)
        assertFalse(capsule.isEnabled)

        // Confirmation only changes the authoritative lock/selection; the existing
        // presentation is already connected and must not start a second fade.
        bar.changeState(BaseService.State.Connected)
        assertSame(startupFade, field(bar, "fadeAnimator"))
        assertTrue(capsule.isSelected)
        assertTrue(capsule.isEnabled)
        assertEquals(1, toggles)
    }

    @Test fun startupFailureOrPermissionIdleRollsBackAndResetClearsRequest() = withBar { bar ->
        var toggles = 0
        bar.onToggle = { toggles++ }
        val capsule = bar.box(R.id.capsule)

        capsule.performClick()
        assertEquals(true, field(bar, "requestedConnected"))
        bar.changeState(BaseService.State.Stopped)
        advance(550)
        assertNull(field(bar, "requestedConnected"))
        assertFalse(capsule.isSelected)
        assertTrue(capsule.isEnabled)

        capsule.performClick()
        assertEquals(2, toggles)
        bar.reset()
        assertNull(field(bar, "requestedConnected"))
        assertFalse(capsule.isSelected)
        assertNull(field(bar, "fadeAnimator"))
        assertTrue(capsule.isEnabled)
    }

    @Test fun repeatedClickAndPendingStopDoNotSendTwiceOrUndoStopPresentation() = withBar { bar ->
        bar.changeState(BaseService.State.Connected)
        advance(550)
        var toggles = 0
        bar.onToggle = { toggles++ }
        val capsule = bar.box(R.id.capsule)

        capsule.performClick()
        val stopFade = field(bar, "fadeAnimator")
        capsule.performClick()
        assertEquals(1, toggles)
        assertSame(stopFade, field(bar, "fadeAnimator"))
        assertTrue(capsule.isSelected) // actual service remains Connected
        assertFalse(capsule.isEnabled)

        bar.changeState(BaseService.State.Connected)
        assertSame(stopFade, field(bar, "fadeAnimator"))
        assertTrue(capsule.isSelected)
        assertFalse(capsule.isEnabled)
        advance(550)
        assertTrue(capsule.isSelected)
        assertFalse(capsule.isEnabled)
        assertEquals(1, toggles)
    }
}
