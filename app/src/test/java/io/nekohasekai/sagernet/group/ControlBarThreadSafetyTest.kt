package io.nekohasekai.sagernet.group

import android.animation.ValueAnimator
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.widget.ControlBar
import org.junit.Assert.*
import org.junit.After
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

/** Preference listeners fire on Default workers (dns09 crash); the bar must marshal to main. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ControlBarThreadSafetyTest {
    @Before fun pauseFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }
    @After fun restoreFrames() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }
    private fun advance(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }
    private fun withBar(block: (ControlBar) -> Unit) {
        val c = Robolectric.buildActivity(AppCompatActivity::class.java); val a = c.get()
        a.setTheme(R.style.Theme_SagerNet); c.setup().visible()
        val host = FrameLayout(a); val bar = ControlBar(a); host.addView(bar, FrameLayout.LayoutParams(360, 76))
        a.setContentView(host)
        host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(76, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 360, 76); advance(32)
        try { bar.changeState(BaseService.State.Connected); advance(180); block(bar) } finally { c.pause().stop().destroy() }
    }
    private fun onWorker(bar: ControlBar, action: (ControlBar) -> Unit) {
        val failure = arrayOfNulls<Throwable>(1)
        val worker = Thread {
            try { action(bar) } catch (t: Throwable) { failure[0] = t }
        }
        worker.start(); worker.join(5000)
        assertFalse("worker threw ${failure[0]}", worker.isAlive)
        assertNull("worker threw: ${failure[0]?.javaClass} ${failure[0]?.message}", failure[0])
    }

    private fun fade(bar: ControlBar): ValueAnimator? =
        ControlBar::class.java.getDeclaredField("fadeAnimator").apply { isAccessible = true }
            .get(bar) as ValueAnimator?

    @Test fun workerStateReportsApplyOnMainAndDuplicateConnectingDoesNotRestartFade() = withBar { bar ->
        val capsule = bar.findViewById<View>(R.id.capsule)
        onWorker(bar) { it.changeState(BaseService.State.Connecting) }
        assertTrue("worker must not paint before the main queue runs", capsule.isSelected)
        assertNull(fade(bar))
        advance(16)
        val stoppingFade = requireNotNull(fade(bar))
        assertFalse(capsule.isSelected)
        assertFalse(capsule.isEnabled)
        assertEquals(bar.context.getString(R.string.control_bar_connecting), capsule.contentDescription)
        onWorker(bar) { it.changeState(BaseService.State.Connecting) }
        advance(16)
        assertSame(stoppingFade, fade(bar))
        advance(180)
        assertFalse(stoppingFade.isRunning)
        assertNull(fade(bar))
        assertEquals(bar.context.getString(R.string.control_bar_disconnected),
            bar.findViewById<TextView>(R.id.capsule_label).text.toString())
        assertEquals(1f, bar.findViewById<View>(R.id.capsule_pair).alpha, 0f)
        assertEquals(0f, bar.findViewById<View>(R.id.capsule_icon).rotation, 0f)
        assertNull(capsule.background)
        assertNull(capsule.foreground)
        onWorker(bar) { it.changeState(BaseService.State.Connected) }
        assertFalse(capsule.isSelected)
        advance(200)
        assertTrue(capsule.isSelected)
        assertTrue(capsule.isEnabled)
        assertNull(fade(bar))
        assertEquals(bar.context.getString(R.string.control_bar_connected),
            bar.findViewById<TextView>(R.id.capsule_label).text.toString())
        assertEquals(1f, bar.findViewById<View>(R.id.capsule_pair).alpha, 0f)
        assertEquals(0f, bar.findViewById<View>(R.id.capsule_icon).rotation, 0f)
        assertNull(capsule.background)
        assertNull(capsule.foreground)
    }

    @Test fun clearMeasurementsFromWorkerThreadDoesNotThrowAndAppliesOnMain() = withBar { bar ->
        bar.showTesting(); advance(32)
        assertEquals(View.VISIBLE, bar.findViewById<View>(R.id.latency_progress).visibility)
        onWorker(bar) { it.clearMeasurements() }
        // Deferred: nothing happened until the main looper runs the posted block.
        advance(16)
        assertEquals(View.GONE, bar.findViewById<View>(R.id.latency_progress).visibility)
        assertEquals("-- ms", (bar.findViewById<TextView>(R.id.latency) as TextView).text.toString())
        assertTrue(bar.findViewById<View>(R.id.latency_box).isEnabled)
    }

    @Test fun showLatencyFromWorkerThreadDefersResultToMain() = withBar { bar ->
        onWorker(bar) { it.showLatency(42) }
        advance(200)
        assertEquals("42 ms", (bar.findViewById<TextView>(R.id.latency) as TextView).text.toString())
        assertEquals(View.GONE, bar.findViewById<View>(R.id.latency_progress).visibility)
    }

    @Test fun resetFromWorkerThreadReleasesToggleLockOnMain() = withBar { bar ->
        val capsule = bar.findViewById<View>(R.id.capsule)
        var toggled = 0
        bar.onToggle = { toggled++ }
        capsule.performClick(); advance(16)
        assertEquals(1, toggled)
        assertFalse("togglePending must lock the capsule", capsule.isEnabled)
        onWorker(bar) { it.reset() }
        advance(32)
        assertTrue("reset from a worker must unlock on main", capsule.isEnabled)
    }
}
