package io.nekohasekai.sagernet.group

import android.animation.ValueAnimator
import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
        host.addView(bar, FrameLayout.LayoutParams(584, 168))
        activity.setContentView(host)
        host.measure(View.MeasureSpec.makeMeasureSpec(584, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(168, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 584, 168)
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
    private fun assertCapsuleHasNoTouchFill(bar: ControlBar) {
        assertThemedCapsuleWithoutPressFill(bar)
    }
    private fun assertNoTransform(bar: ControlBar) {
        for (view in listOf(bar, bar.box(R.id.capsule), bar.box(R.id.capsule_pair),
            bar.box(R.id.capsule_icon), bar.box(R.id.capsule_label))) {
            assertNull(view.animation)
            assertEquals(0f, view.rotation, 0f)
            assertEquals(0f, view.rotationX, 0f)
            assertEquals(0f, view.rotationY, 0f)
            assertEquals(0f, view.translationX, 0f)
            assertEquals(0f, view.translationY, 0f)
            assertEquals(0f, view.translationZ, 0f)
            assertEquals(1f, view.scaleX, 0f)
            assertEquals(1f, view.scaleY, 0f)
            if (view.id != R.id.capsule_pair) assertEquals(1f, view.alpha, 0f)
        }
    }
    private fun assertVisual(bar: ControlBar, connected: Boolean) {
        assertCapsuleHasNoTouchFill(bar)
        // The 450ms presentation crossfade (spec 7.3) legitimately rotates/scales the
        // pair mid-flight; transform contracts still hold once the fade settles.
        if (field(bar, "fadeAnimator") == null) assertNoTransform(bar)
        assertEquals(bar.context.getString(if (connected) R.string.control_bar_connected
            else R.string.control_bar_disconnected), bar.text(R.id.capsule_label).text.toString())
        val icon = bar.findViewById<ImageView>(R.id.capsule_icon).drawable
        assertNotNull(icon)
        assertEquals(if (connected) R.drawable.ic_control_plane else R.drawable.ic_control_power,
            shadowOf(requireNotNull(icon)).createdFromResId)
    }
    private fun assertNoAnimation(bar: ControlBar) {
        assertNull(field(bar, "fadeAnimator"))
        assertCapsuleHasNoTouchFill(bar)
        assertNoTransform(bar)
        assertEquals(1f, bar.box(R.id.capsule_pair).alpha, 0f)
    }

    @Test fun connectingKeepsPowerVisualWithoutAnimationButRetainsAccessibilityAndServiceLock() = withBar { bar, _, _ ->
        var toggles = 0
        bar.onToggle = { toggles++ }
        repeat(3) {
            bar.changeState(BaseService.State.Connecting)
            assertVisual(bar, connected = false)
            assertNoAnimation(bar)
            assertEquals(bar.context.getString(R.string.control_bar_connecting),
                bar.box(R.id.capsule).contentDescription)
            bar.box(R.id.capsule).performClick()
            assertEquals(0, toggles)
            assertFalse(bar.box(R.id.capsule).isSelected)
            assertFalse(bar.box(R.id.capsule).isEnabled)
            advance(1000) // No startup spinner or optimistic plane, even after waiting.
            assertVisual(bar, connected = false)
            assertNoAnimation(bar)
        }
        bar.changeState(BaseService.State.Stopping)
        assertVisual(bar, connected = false)
        assertFalse(bar.box(R.id.capsule).isEnabled)
        assertNoAnimation(bar)
        bar.changeState(BaseService.State.Stopped)
        assertVisual(bar, connected = false)
        assertTrue(bar.box(R.id.capsule).isEnabled)
        assertNoAnimation(bar)
    }

    @Test fun eachDirectionFadesOnlyThePairWhileThemeCapsuleStaysFixedAndDuplicatesDoNotRestart() = withBar { bar, _, _ ->
        // Crossfade contract (spec 7.3): 450ms, icon swap at the midpoint.
        fun expectedAlpha(playTimeMs: Long): Float {
            val fraction = playTimeMs / 450f
            return if (fraction < .5f) 1f - fraction * 2f else (fraction - .5f) * 2f
        }
        for (connected in listOf(true, false)) {
            val state = if (connected) BaseService.State.Connected else BaseService.State.Stopped
            bar.changeState(state)
            val fade = field(bar, "fadeAnimator") as ValueAnimator
            val pair = bar.box(R.id.capsule_pair)
            assertEquals(450L, fade.duration)
            assertEquals(0, fade.repeatCount)
            assertVisual(bar, !connected)
            fade.currentPlayTime = 37
            assertEquals(expectedAlpha(37), pair.alpha, .001f)
            assertVisual(bar, !connected)
            bar.changeState(state)
            assertSame(fade, field(bar, "fadeAnimator"))
            assertEquals(expectedAlpha(37), pair.alpha, .001f)
            fade.currentPlayTime = 225
            assertEquals(0f, pair.alpha, .001f)
            assertVisual(bar, connected)
            fade.currentPlayTime = 337
            assertEquals(expectedAlpha(337), pair.alpha, .001f)
            assertVisual(bar, connected)
            bar.changeState(state)
            assertSame(fade, field(bar, "fadeAnimator"))
            advance(500)
            assertFalse(fade.isRunning)
            assertVisual(bar, connected)
            assertNoAnimation(bar)
            bar.changeState(state) // A settled duplicate must not start a second fade.
            assertVisual(bar, connected)
            assertNoAnimation(bar)
        }
    }

    @Test fun bluePinkAmberKeepTheirCapsuleColorWithoutTouchFillAcrossStatesAndThemes() {
        // Resolve by name so this regression coexists with the host-owned Pink palette.
        val pink = ApplicationProviderStyle.pink()
        for (theme in listOf(R.style.Theme_SagerNet_Blue, pink, R.style.Theme_SagerNet_Amber)) {
            for (night in listOf(false, true)) withBar(theme, night) { bar, _, context ->
                for (state in BaseService.State.values()) {
                    bar.changeState(state)
                    assertCapsuleHasNoTouchFill(bar)
                    advance(500)
                    assertVisual(bar, state == BaseService.State.Connected)
                    assertNoAnimation(bar)
                    val capsule = bar.box(R.id.capsule)
                    capsule.isPressed = true
                    assertCapsuleHasNoTouchFill(bar)
                    capsule.isPressed = false
                }
                context.theme.applyStyle(R.style.Theme_SagerNet_Amber, true)
                bar.changeState(BaseService.State.Stopped)
                advance(500)
                assertVisual(bar, connected = false)
                assertNoAnimation(bar)
            }
        }
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
            // Disconnected readouts are neutral now (spec 7.5), never green.
            assertEquals(bar.resources.getColor(R.color.control_bar_text,bar.context.theme),bar.text(R.id.latency).currentTextColor)
        }
    }

    @Test fun hiddenParentWindowAndDetachCancelFadesAndDiscardLateMeasurements() = withBar { bar, host, _ ->
        bar.changeState(BaseService.State.Connected)
        val parentFade = field(bar, "fadeAnimator") as ValueAnimator
        parentFade.currentPlayTime = 32
        host.visibility = View.INVISIBLE
        assertFalse(parentFade.isRunning)
        assertNoAnimation(bar)
        assertVisual(bar, connected = true)
        advance(1200)
        assertNoAnimation(bar)
        host.visibility = View.VISIBLE
        assertNoAnimation(bar) // Visibility must settle, not replay the interrupted fade.
        assertVisual(bar, connected = true)

        bar.changeState(BaseService.State.Stopping)
        val windowFade = field(bar, "fadeAnimator") as ValueAnimator
        windowFade.currentPlayTime = 100
        windowVisibility(bar, View.INVISIBLE)
        assertFalse(windowFade.isRunning)
        assertNoAnimation(bar)
        assertVisual(bar, connected = false)
        bar.changeState(BaseService.State.Connected) // Hidden service acknowledgements settle immediately.
        assertNoAnimation(bar)
        assertVisual(bar, connected = true)
        windowVisibility(bar, View.VISIBLE)
        assertNoAnimation(bar)

        bar.showTesting(); bar.updateSpeed(10_000, 20_000)
        bar.showLatency(42) // Pending minimum-duration completion must also be discarded.
        bar.visibility = View.GONE
        assertNoAnimation(bar)
        bar.showLatency(999); bar.showFailure("late"); bar.updateSpeed(1_000_000, 2_000_000); advance(250)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        assertEquals("▼ -- kB/s", bar.text(R.id.speed_down).text.toString())
        bar.visibility = View.VISIBLE
        assertTrue(bar.box(R.id.latency_box).isEnabled)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        assertVisual(bar, connected = true)
        assertNoAnimation(bar)

        bar.changeState(BaseService.State.Stopped); advance(180)
        bar.changeState(BaseService.State.Connected)
        val detachFade = field(bar, "fadeAnimator") as ValueAnimator
        detachFade.currentPlayTime = 32
        bar.showTesting(); bar.showLatency(77)
        host.removeView(bar)
        assertFalse(detachFade.isRunning)
        assertNoAnimation(bar)
        bar.showFailure("late detached failure"); bar.updateSpeed(1_000_000, 2_000_000)
        advance(1200)
        assertNoAnimation(bar)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        host.addView(bar, FrameLayout.LayoutParams(584, 168))
        advance(180)
        assertVisual(bar, connected = true)
        assertNoAnimation(bar)
        assertTrue(bar.box(R.id.capsule).isEnabled)
        assertTrue(bar.box(R.id.latency_box).isEnabled)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        assertEquals("▼ -- kB/s", bar.text(R.id.speed_down).text.toString())
        assertEquals("▲ -- kB/s", bar.text(R.id.speed_up).text.toString())
        assertEquals(View.GONE, bar.box(R.id.latency_progress).visibility)
    }

    @Test fun rapidReversalCancelsOldFadeAndClearPreservesPendingPermission() = withBar { bar, _, _ ->
        // Interrupt on both sides of the icon swap; no cancelled callback may restore an old state.
        for (playTime in listOf(32L, 100L)) {
            bar.changeState(BaseService.State.Connected)
            val oldFade = field(bar, "fadeAnimator") as ValueAnimator
            oldFade.currentPlayTime = playTime
            bar.changeState(BaseService.State.Connecting)
            val reverseFade = field(bar, "fadeAnimator") as ValueAnimator
            assertFalse(oldFade.isRunning)
            assertNotSame(oldFade, reverseFade)
            reverseFade.currentPlayTime = 32
            bar.changeState(BaseService.State.Stopping)
            bar.changeState(BaseService.State.Stopped)
            bar.changeState(BaseService.State.Idle)
            assertSame(reverseFade, field(bar, "fadeAnimator")) // Same disconnected visual, no restart.
            advance(500)
            assertFalse(reverseFade.isRunning)
            assertNoAnimation(bar)
            assertVisual(bar, connected = false)
            assertFalse(bar.box(R.id.capsule).isSelected)
            assertTrue(bar.box(R.id.capsule).isEnabled)
        }
        var toggles = 0; bar.onToggle = { toggles++ }
        bar.box(R.id.capsule).performClick()
        bar.clearMeasurements()
        bar.visibility = View.INVISIBLE
        bar.visibility = View.VISIBLE
        bar.box(R.id.capsule).performClick()
        assertEquals(1, toggles)
        assertFalse(bar.box(R.id.capsule).isEnabled)
        assertVisual(bar, connected = false)
        assertNoAnimation(bar)
        bar.changeState(BaseService.State.Idle)
        bar.box(R.id.capsule).performClick()
        assertEquals(2, toggles)
    }

    @Test fun disabledSystemAnimationsSettleImmediatelyAndVisibleSyncNeverClearsReadouts() = withBar { bar, _, _ ->
        val resolver = bar.context.contentResolver
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val previousScale = Settings.Global.getFloat(resolver, key, 1f)
        try {
            // Robolectric 4.14.1 ShadowSettings forwards this to ShadowValueAnimator.setDurationScale.
            // The latter is protected: do not call a nonexistent public shadow setter.
            Settings.Global.putFloat(resolver, key, 0f)
            assertFalse(ValueAnimator.areAnimatorsEnabled())
            for (state in listOf(BaseService.State.Connecting, BaseService.State.Connected,
                BaseService.State.Stopping, BaseService.State.Stopped, BaseService.State.Idle)) {
                bar.changeState(state) // Deliberately no clock advance before these assertions.
                assertVisual(bar, state == BaseService.State.Connected)
                assertNoAnimation(bar)
                assertEquals(state == BaseService.State.Connected, bar.box(R.id.capsule).isSelected)
                assertEquals(state != BaseService.State.Connecting && state != BaseService.State.Stopping,
                    bar.box(R.id.capsule).isEnabled)
            }
            bar.changeState(BaseService.State.Connected)
            bar.updateSpeed(1_000_000, 2_000_000)
            bar.showLatency(42)
            var rateWrites = 0
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { rateWrites++ }
            }
            bar.text(R.id.speed_down).addTextChangedListener(watcher)
            bar.text(R.id.speed_up).addTextChangedListener(watcher)
            try {
                repeat(3) {
                    // Exercises syncAnimationVisibility while actually visible: animation preference
                    // alone is not a reason to invalidate rates, latency, or the readout controls.
                    windowVisibility(bar, View.VISIBLE)
                    bar.changeState(BaseService.State.Connected)
                    assertVisual(bar, connected = true)
                    assertNoAnimation(bar)
                    assertEquals("▼ 2.00 MB/s", bar.text(R.id.speed_down).text.toString())
                    assertEquals("▲ 1.00 MB/s", bar.text(R.id.speed_up).text.toString())
                    assertEquals("42 ms", bar.text(R.id.latency).text.toString())
                    assertTrue(bar.box(R.id.speed_box).isEnabled)
                    assertTrue(bar.box(R.id.latency_box).isEnabled)
                }
                assertEquals("no transient reset of valid rates", 0, rateWrites)
                advance(250)
                assertNoAnimation(bar)
                assertEquals(0, rateWrites)
            } finally {
                bar.text(R.id.speed_down).removeTextChangedListener(watcher)
                bar.text(R.id.speed_up).removeTextChangedListener(watcher)
            }
        } finally {
            Settings.Global.putFloat(resolver, key, previousScale)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun oneRoundedFrameMaskExcludesCapsuleAndBothSidesDriveTheSameRipple() = withBar { bar, _, _ ->
        bar.changeState(BaseService.State.Connected); advance(180)
        assertTrue(bar.foreground is RippleDrawable)
        assertNull(bar.box(R.id.speed_box).background)
        assertNull(bar.box(R.id.latency_box).background)
        assertCapsuleHasNoTouchFill(bar)
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
        // Pixel-level guard: the capsule's gradient fill is present and pressing changes no pixels.
        val capsuleBitmap = Bitmap.createBitmap(capsule.width, capsule.height, Bitmap.Config.ARGB_8888)
        capsule.draw(Canvas(capsuleBitmap))
        val beforePress = IntArray(capsule.width * capsule.height)
        capsuleBitmap.getPixels(beforePress, 0, capsule.width, 0, 0, capsule.width, capsule.height)
        // Compare against the background drawable's OWN rendering: the gradient
        // interpolates between its stops, so a single palette constant is not a
        // valid expected pixel anywhere below the very first row.
        val background = capsule.background as android.graphics.drawable.GradientDrawable
        background.setBounds(0, 0, capsule.width, capsule.height)
        val reference = Bitmap.createBitmap(capsule.width, capsule.height, Bitmap.Config.ARGB_8888)
        background.draw(Canvas(reference))
        assertEquals("capsule paints its gradient fill",
            reference.getPixel(capsule.width / 2, 3), capsuleBitmap.getPixel(capsule.width / 2, 3))
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, cx.toFloat(), cy.toFloat(), 0)
        bar.dispatchTouchEvent(down); down.recycle()
        assertCapsuleHasNoTouchFill(bar)
        advance(64) // A held press must not add a touch overlay or trigger the side ripple.
        // The HTML contract intentionally scales the capsule to 0.90 while held;
        // verify that press feedback is transform-only, with no foreground/ripple tint.
        assertNull("capsule has no touch foreground", capsule.foreground)
        assertTrue("held press scales the capsule without recoloring it",
            capsule.scaleX < 1f && capsule.scaleX >= .89f)
        assertFalse(ripple.state.contains(android.R.attr.state_pressed))
        val cancel = MotionEvent.obtain(0, 80, MotionEvent.ACTION_CANCEL, cx.toFloat(), cy.toFloat(), 0)
        bar.dispatchTouchEvent(cancel); cancel.recycle()
        advance(400)
        assertCapsuleHasNoTouchFill(bar)
        assertNoTransform(bar)
        assertFalse(ripple.state.contains(android.R.attr.state_pressed))
        capsuleBitmap.recycle()
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
        // Connecting is not connected: the readout is neutral (spec 7.5).
        assertEquals(bar.resources.getColor(R.color.control_bar_text,bar.context.theme),bar.text(R.id.latency).currentTextColor)
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
