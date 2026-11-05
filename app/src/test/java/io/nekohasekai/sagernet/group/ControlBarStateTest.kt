package io.nekohasekai.sagernet.group

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.widget.ControlBar
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.robolectric.shadows.ShadowChoreographer
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** dns07 regressions use the real widget/resources, not a duplicate model of its state machine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ControlBarStateTest {
    @Before fun pauseFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }
    @After fun restoreFrames() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }
    private fun withBar(theme: Int = R.style.Theme_SagerNet, night: Boolean = false,
        block: (ControlBar, FrameLayout, ContextThemeWrapper) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_SagerNet)
        controller.setup().visible()
        val config = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val context = ContextThemeWrapper(activity.createConfigurationContext(config), theme)
        val host = FrameLayout(activity)
        val bar = ControlBar(context)
        host.addView(bar, FrameLayout.LayoutParams(584, 152))
        activity.setContentView(host)
        host.measure(View.MeasureSpec.makeMeasureSpec(584, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(152, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 584, 152)
        advance(32) // Let the actual window attach/visibility callbacks settle before state assertions.
        try { block(bar, host, context) }
        finally { controller.pause().stop().destroy() }
    }

    private fun ControlBar.box(id: Int) = findViewById<View>(id)
    private fun ControlBar.text(id: Int) = findViewById<TextView>(id)
    private fun field(bar: ControlBar, name: String): Any? =
        ControlBar::class.java.getDeclaredField(name).apply { isAccessible = true }.get(bar)
    private fun windowVisibility(bar: ControlBar, visibility: Int) {
        ControlBar::class.java.getDeclaredMethod("onWindowVisibilityChanged", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(bar, visibility)
    }
    private fun advance(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }
    private fun color(bar: ControlBar) =
        (bar.box(R.id.capsule).background as GradientDrawable).color!!.defaultColor
    private fun attr(context: ContextThemeWrapper, id: Int): Int = TypedValue().let {
        assertTrue(context.theme.resolveAttribute(id, it, true))
        if (it.resourceId != 0) context.resources.getColor(it.resourceId, context.theme) else it.data
    }
    private fun assertNoAnimation(bar: ControlBar) {
        for (name in listOf("fadeAnimator", "colorAnimator", "connectingAnimator")) assertNull(field(bar, name))
        assertEquals(0f, bar.box(R.id.capsule_icon).rotation, 0f)
        assertEquals(1f, bar.box(R.id.capsule_pair).alpha, 0f)
    }

    @Test fun connectingIsRealAndDuplicateStateKeepsItsAnimatorAndLock() = withBar { bar, _, _ ->
        var toggles = 0
        bar.onToggle = { toggles++ }
        bar.changeState(BaseService.State.Connecting)
        val fade = field(bar, "fadeAnimator")
        bar.changeState(BaseService.State.Connecting)
        assertSame(fade, field(bar, "fadeAnimator"))
        bar.box(R.id.capsule).performClick()
        assertEquals(0, toggles)
        assertFalse(bar.box(R.id.capsule).isSelected)
        assertFalse(bar.box(R.id.capsule).isEnabled)
        advance(180)
        assertEquals(bar.context.getString(R.string.control_bar_connecting), bar.text(R.id.capsule_label).text)
        val spin = field(bar, "connectingAnimator") as ObjectAnimator
        assertTrue(spin.isRunning)
        spin.currentPlayTime = 225
        assertTrue(bar.box(R.id.capsule_icon).rotation > 0f)
        bar.changeState(BaseService.State.Connecting)
        assertSame(spin, field(bar, "connectingAnimator"))
        assertTrue(bar.box(R.id.capsule_icon).rotation > 0f)
        bar.changeState(BaseService.State.Stopping)
        advance(180)
        assertEquals(bar.context.getString(R.string.control_bar_disconnected), bar.text(R.id.capsule_label).text)
        assertFalse(bar.box(R.id.capsule).isEnabled)
        assertNoAnimation(bar)
    }

    @Test fun fadeHasRealIntermediateFramesWhileAllStatesKeepTheLightThemeFill() = withBar { bar, _, context ->
        val light=attr(context,R.attr.colorMaterial300)
        assertEquals(light,color(bar))
        bar.changeState(BaseService.State.Connected)
        val fade=field(bar,"fadeAnimator") as ValueAnimator
        fade.currentPlayTime=32
        assertTrue(bar.box(R.id.capsule_pair).alpha>0f && bar.box(R.id.capsule_pair).alpha<1f)
        assertEquals(light,color(bar))
        bar.changeState(BaseService.State.Connected)
        assertSame(fade,field(bar,"fadeAnimator"))
        advance(180)
        assertEquals(light,color(bar))
        assertEquals(bar.context.getString(R.string.control_bar_connected),bar.text(R.id.capsule_label).text)
        assertNoAnimation(bar)
    }

    @Test fun bluePinkAmberUseTheirOwnLivePrimaryAnd300WithoutCrossThemeColorLeakage() {
        // Resolve by name so this regression coexists with the host-owned new Pink palette.
        val pink = ApplicationProviderStyle.pink()
        val seenPrimary = mutableSetOf<Int>()
        val seenLight = mutableSetOf<Int>()
        for (theme in listOf(R.style.Theme_SagerNet_Blue, pink, R.style.Theme_SagerNet_Amber)) {
            for (night in listOf(false, true)) withBar(theme, night) { bar, _, context ->
                val light = attr(context, R.attr.colorMaterial300)
                val primary = attr(context, R.attr.colorPrimary)
                seenPrimary += primary; seenLight += light
                assertEquals(light, color(bar))
                bar.changeState(BaseService.State.Connecting); advance(180)
                assertEquals(light, color(bar))
                bar.changeState(BaseService.State.Connected); advance(180)
                assertEquals(light, color(bar))
                bar.changeState(BaseService.State.Stopped); advance(180)
                assertEquals(light, color(bar))
                context.theme.applyStyle(R.style.Theme_SagerNet_Amber, true)
                bar.changeState(BaseService.State.Stopped) // Same-state acknowledgement resolves a changed theme.
                advance(180)
                assertEquals(attr(context, R.attr.colorMaterial300), color(bar))
            }
        }
        assertEquals(3, seenPrimary.size)
        assertEquals(3, seenLight.size)
    }

    @Test fun failuresAreRedButConnectedReadoutsCanRetryWithoutDroppingVpn() {
        for (night in listOf(false, true)) withBar(night = night) { bar, _, _ ->
            bar.changeState(BaseService.State.Connected)
            var tests = 0; var errors = 0
            bar.onTest = { tests++ }; bar.onError = { errors++ }
            bar.box(R.id.speed_box).performClick()
            bar.showFailure(bar.context.getString(R.string.control_bar_failure)); advance(250)
            assertEquals(Color.rgb(244, 67, 54), bar.text(R.id.latency).currentTextColor)
            assertTrue(bar.box(R.id.speed_box).isEnabled)
            assertTrue(bar.box(R.id.latency_box).isEnabled)
            bar.box(R.id.latency_box).performClick()
            assertEquals(0, errors); assertEquals(2, tests)
            bar.showFailure("retry failed"); advance(250)
            bar.changeState(BaseService.State.Connected)
            assertEquals(Color.rgb(244, 67, 54), bar.text(R.id.latency).currentTextColor)
            bar.box(R.id.speed_box).performClick()
            assertEquals(0, errors); assertEquals(3, tests)
            bar.showTesting() // Only an explicit host action/clear starts a fresh test after failure.
            assertEquals(bar.resources.getColor(R.color.latency_green,bar.context.theme),bar.text(R.id.latency).currentTextColor)
            bar.showFailure(""); advance(250)
            assertEquals("-- ms", bar.text(R.id.latency).text.toString())
            assertEquals(Color.rgb(244, 67, 54), bar.text(R.id.latency).currentTextColor)
            bar.clearMeasurements()
            assertEquals(bar.resources.getColor(R.color.latency_green,bar.context.theme),bar.text(R.id.latency).currentTextColor)
            bar.showFailure("short\nfull sensitive detail is host-owned"); advance(250)
            assertEquals("short", bar.text(R.id.latency).text.toString())
            bar.showLatency(42); advance(250)
            assertEquals("42 ms", bar.text(R.id.latency).text.toString())
            bar.changeState(BaseService.State.Stopped)
            bar.showFailure("startup failed"); advance(250)
            assertEquals("startup failed", bar.text(R.id.latency).text.toString())
            assertEquals(Color.rgb(244, 67, 54), bar.text(R.id.latency).currentTextColor)
            bar.box(R.id.latency_box).performClick(); bar.box(R.id.speed_box).performClick()
            assertEquals(2, errors); assertEquals(3, tests)
            bar.showLatency(-1); advance(250)
            assertEquals("-- ms", bar.text(R.id.latency).text.toString())
            assertEquals(bar.resources.getColor(R.color.latency_green,bar.context.theme),bar.text(R.id.latency).currentTextColor)
        }
    }

    @Test fun hiddenParentWindowAndDetachCancelSpinAndDiscardLateMeasurements() = withBar { bar, host, _ ->
        bar.changeState(BaseService.State.Connecting); advance(180)
        val spin = field(bar, "connectingAnimator") as ObjectAnimator
        host.visibility = View.INVISIBLE
        assertFalse(spin.isRunning); assertNoAnimation(bar)
        advance(1200)
        assertNoAnimation(bar)
        host.visibility = View.VISIBLE
        assertNotNull(field(bar, "connectingAnimator"))
        windowVisibility(bar, View.INVISIBLE)
        assertNoAnimation(bar)
        windowVisibility(bar, View.VISIBLE)
        bar.changeState(BaseService.State.Connected); advance(180)
        bar.showTesting(); bar.updateSpeed(10_000, 20_000)
        bar.visibility = View.GONE
        assertNoAnimation(bar)
        bar.showLatency(999); bar.showFailure("late"); bar.updateSpeed(1_000_000, 2_000_000); advance(250)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        assertEquals("▼ -- kB/s", bar.text(R.id.speed_down).text.toString())
        bar.visibility = View.VISIBLE
        assertTrue(bar.box(R.id.latency_box).isEnabled)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        bar.changeState(BaseService.State.Connecting); advance(180)
        host.removeView(bar)
        assertNoAnimation(bar)
        advance(1200)
        assertNoAnimation(bar)
    }

    @Test fun rapidReversalCancelsOldFadeAndClearPreservesPendingPermission() = withBar { bar, _, _ ->
        bar.changeState(BaseService.State.Connecting)
        val oldFade = field(bar, "fadeAnimator") as ValueAnimator
        oldFade.currentPlayTime = 50
        bar.changeState(BaseService.State.Connected)
        assertFalse(oldFade.isRunning)
        bar.changeState(BaseService.State.Idle)
        advance(180)
        assertNoAnimation(bar)
        assertEquals(bar.context.getString(R.string.control_bar_disconnected), bar.text(R.id.capsule_label).text)
        var toggles = 0; bar.onToggle = { toggles++ }
        bar.box(R.id.capsule).performClick()
        bar.clearMeasurements()
        bar.visibility = View.INVISIBLE
        bar.visibility = View.VISIBLE
        bar.box(R.id.capsule).performClick()
        assertEquals(1, toggles)
        bar.changeState(BaseService.State.Idle)
        bar.box(R.id.capsule).performClick()
        assertEquals(2, toggles)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun oneRoundedFrameMaskExcludesCapsuleAndBothSidesDriveTheSameRipple() = withBar { bar, _, _ ->
        bar.changeState(BaseService.State.Connected); advance(180)
        assertTrue(bar.foreground is RippleDrawable)
        assertNull(bar.box(R.id.speed_box).background)
        assertNull(bar.box(R.id.latency_box).background)
        assertNotNull(bar.box(R.id.capsule).foreground)
        assertTrue(bar.clipToOutline)
        val mask = field(bar, "sideMask") as Drawable
        mask.setBounds(0, 0, bar.width, bar.height)
        val bitmap = Bitmap.createBitmap(bar.width, bar.height, Bitmap.Config.ARGB_8888)
        mask.draw(Canvas(bitmap))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        val capsule = bar.box(R.id.capsule)
        val parent = capsule.parent as View
        val cx = parent.left + capsule.left + capsule.width / 2
        val cy = parent.top + capsule.top + capsule.height / 2
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(cx, cy))
        assertEquals(Color.WHITE, bitmap.getPixel(4, bar.height / 2)) // Includes padding, not just boxes.
        assertEquals(Color.WHITE, bitmap.getPixel(bar.width - 5, bar.height / 2))
        assertEquals(Color.WHITE, bitmap.getPixel(cx, 4)) // Full frame above/below capsule also participates.
        val ripple = bar.foreground
        for (id in listOf(R.id.speed_box, R.id.latency_box)) {
            val side = bar.box(id)
            val x = parent.left + side.left + side.width / 2f
            val y = parent.top + side.top + side.height / 2f
            val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
            bar.dispatchTouchEvent(down); down.recycle()
            assertSame(ripple, bar.foreground)
            assertTrue(ripple.state.contains(android.R.attr.state_pressed))
            val cancel = MotionEvent.obtain(0, 10, MotionEvent.ACTION_CANCEL, x, y, 0)
            bar.dispatchTouchEvent(cancel); cancel.recycle()
            assertFalse(ripple.state.contains(android.R.attr.state_pressed))
        }
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, cx.toFloat(), cy.toFloat(), 0)
        bar.dispatchTouchEvent(down); down.recycle()
        assertFalse(ripple.state.contains(android.R.attr.state_pressed))
        val cancel = MotionEvent.obtain(0, 10, MotionEvent.ACTION_CANCEL, cx.toFloat(), cy.toFloat(), 0)
        bar.dispatchTouchEvent(cancel); cancel.recycle()
        bitmap.recycle()
    }

    @Test fun startupTestingDoesNotCallManualTestAndNewConnectingClearsOldFailure() = withBar { bar, _, _ ->
        var manualTests = 0
        bar.onTest = { manualTests++ }
        bar.showConnectionTesting()
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        bar.changeState(BaseService.State.Connecting)
        bar.showConnectionTesting(); bar.showConnectionTesting()
        bar.changeState(BaseService.State.Connecting)
        assertEquals(View.VISIBLE,bar.box(R.id.latency_progress).visibility)
        assertFalse(bar.box(R.id.speed_box).isEnabled)
        bar.box(R.id.speed_box).performClick(); bar.box(R.id.latency_box).performClick()
        assertEquals(0, manualTests)
        bar.changeState(BaseService.State.Stopped)
        bar.showFailure("startup failed"); advance(250)
        assertEquals(Color.rgb(244, 67, 54), bar.text(R.id.latency).currentTextColor)
        bar.changeState(BaseService.State.Connecting)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        assertEquals(bar.resources.getColor(R.color.latency_green,bar.context.theme),bar.text(R.id.latency).currentTextColor)
        bar.showConnectionTesting()
        bar.changeState(BaseService.State.Connected)
        bar.showLatency(28); advance(250)
        assertEquals("28 ms", bar.text(R.id.latency).text.toString())
        assertTrue(bar.box(R.id.speed_box).isEnabled)
        assertEquals(0, manualTests)
    }

    private object ApplicationProviderStyle {
        fun pink(): Int {
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            val style = app.resources.getIdentifier("Theme.SagerNet.Pink", "style", app.packageName)
            assertTrue("host-owned Pink theme must be defined", style != 0)
            return style
        }
    }
}
