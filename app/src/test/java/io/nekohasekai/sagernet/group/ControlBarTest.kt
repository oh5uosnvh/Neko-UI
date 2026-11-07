package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Looper
import android.os.Parcelable
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
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
import java.time.Duration
import java.util.Locale
import kotlin.math.roundToInt

/** Inflates the real merge resource directly; deliberately independent of layout_main/binding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ControlBarTest {
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
    private fun ControlBar.text(id: Int) = findViewById<TextView>(id)
    private fun ControlBar.box(id: Int) = findViewById<View>(id)
    private fun dp(view: View, value: Int) = (view.resources.displayMetrics.density * value).roundToInt()

    private fun withBar(
        night: Boolean = false,
        locale: Locale = Locale.SIMPLIFIED_CHINESE,
        fontScale: Float = 1f,
        block: (ControlBar, FrameLayout) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        val configuration = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            this.fontScale = fontScale
            setLocale(locale)
        }
        val context = android.view.ContextThemeWrapper(activity.createConfigurationContext(configuration),
            R.style.Theme_SagerNet)
        activity.setTheme(R.style.Theme_SagerNet)
        controller.setup().visible()
        try {
            val host = FrameLayout(activity)
            val bar = ControlBar(context)
            host.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(bar, 84)))
            activity.setContentView(host)
            measure(host, dp(bar, 320 - 28), dp(bar, 84))
            block(bar, host)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun measure(host: View, width: Int, height: Int) {
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
        advance(180)
    }

    private fun assertUnknown(bar: ControlBar) {
        assertEquals("▼ -- kB/s", bar.text(R.id.speed_down).text.toString())
        assertEquals("▲ -- kB/s", bar.text(R.id.speed_up).text.toString())
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
    }

    private fun assertCapsuleHasNoTouchFill(bar: ControlBar) {
        assertThemedCapsuleWithoutPressFill(bar)
        assertEquals(0f, bar.box(R.id.capsule_icon).rotation, 0f)
        assertEquals(0f, bar.box(R.id.capsule_plane_icon).rotation, 0f)
    }

    private fun assertCapsuleVisual(bar: ControlBar, connected: Boolean) {
        assertCapsuleHasNoTouchFill(bar)
        assertEquals(bar.context.getString(if (connected) R.string.control_bar_connected
            else R.string.control_bar_disconnected), bar.text(R.id.capsule_label).text.toString())
        val power = bar.findViewById<ImageView>(R.id.capsule_icon).drawable
        val plane = bar.findViewById<ImageView>(R.id.capsule_plane_icon).drawable
        assertEquals(R.drawable.ic_control_power, shadowOf(requireNotNull(power)).createdFromResId)
        assertEquals(R.drawable.ic_control_plane, shadowOf(requireNotNull(plane)).createdFromResId)
        val powerLayer = bar.box(R.id.capsule_power_layer)
        val planeLayer = bar.box(R.id.capsule_plane_layer)
        assertEquals(if (connected) 0f else 1f, powerLayer.alpha, .001f)
        assertEquals(if (connected) 1f else 0f, planeLayer.alpha, .001f)
        assertEquals(1f, bar.box(R.id.capsule_pair).alpha, 0f)
    }

    @Test fun mergeInflatesOnceAndColdStateHasNoInventedMeasurements() = withBar { bar, _ ->
        assertTrue(bar is FrameLayout)
        assertEquals(1, bar.childCount)
        assertTrue(bar.getChildAt(0) is LinearLayout)
        assertEquals(3, (bar.getChildAt(0) as ViewGroup).childCount)
        assertEquals(dp(bar, 84), bar.height)
        assertUnknown(bar)
        assertFalse(bar.box(R.id.capsule).isSelected)
        assertEquals(bar.context.getString(R.string.control_bar_disconnected), bar.text(R.id.capsule_label).text)
        assertTrue(bar.box(R.id.capsule).isEnabled)
        assertFalse(bar.box(R.id.speed_box).isEnabled)
        assertFalse(bar.box(R.id.latency_box).isEnabled)
        assertCapsuleVisual(bar, connected = false)
    }

    @Test fun everyServiceStateUsesOnlyTheAuthoritativeConnectedOrDisconnectedVisual() = withBar { bar, _ ->
        for (state in BaseService.State.values()) {
            bar.changeState(state)
            assertCapsuleHasNoTouchFill(bar) // Persistent theme fill stays; pressed overlays never appear.
            advance(650)
            val connected = state == BaseService.State.Connected
            val capsule = bar.box(R.id.capsule)
            assertEquals("state=$state", connected, capsule.isSelected)
            assertCapsuleVisual(bar, connected)
            assertEquals(bar.context.getString(when (state) {
                BaseService.State.Connecting -> R.string.control_bar_connecting
                BaseService.State.Connected -> R.string.control_bar_disconnect_action
                else -> R.string.control_bar_connect_action
            }), capsule.contentDescription)
            assertEquals(!listOf(BaseService.State.Connecting, BaseService.State.Stopping).contains(state),
                capsule.isEnabled)
            assertEquals(connected, bar.box(R.id.speed_box).isEnabled)
            assertEquals(connected, bar.box(R.id.latency_box).isEnabled)
            assertNull(bar.animation)
            assertNull(capsule.animation)
            assertUnknown(bar)
            assertEquals(View.VISIBLE, bar.visibility)
            assertEquals(1f, bar.alpha, 0f)
            assertEquals(0f, bar.translationY, 0f)
        }
    }

    @Test fun repeatedToggleCannotStartPermissionTwiceAndNeverOptimisticallyConnects() = withBar { bar, _ ->
        var toggles = 0
        bar.onToggle = { toggles++ }
        val capsule = bar.box(R.id.capsule)
        repeat(3) { capsule.performClick() }
        assertEquals(1, toggles)
        assertFalse(capsule.isSelected)
        assertFalse(capsule.isEnabled)
        assertUnknown(bar)
        assertCapsuleVisual(bar, connected = false)
        advance(1000) // Waiting for permission/service startup cannot paint an optimistic connection.
        assertCapsuleVisual(bar, connected = false)
        // Permission cancelled: an authoritative same-state report explicitly re-arms.
        bar.changeState(BaseService.State.Idle)
        capsule.performClick()
        assertEquals(2, toggles)
        bar.changeState(BaseService.State.Connecting)
        advance(1000)
        assertCapsuleVisual(bar, connected = false)
        capsule.performClick()
        bar.changeState(BaseService.State.Stopping)
        advance(650)
        assertCapsuleVisual(bar, connected = false)
        capsule.performClick()
        assertEquals(2, toggles)
        bar.changeState(BaseService.State.Connected)
        advance(650)
        assertCapsuleVisual(bar, connected = true)
        capsule.performClick()
        capsule.performClick()
        assertEquals(3, toggles) // Connected can stop, but only once per state acknowledgement.
        assertTrue(capsule.isSelected) // The click did not fake a disconnected state either.
        assertCapsuleVisual(bar, connected = true)
    }

    @Test fun clickLocksBeforeCallbackEvenIfHostDelaysCallingShowTesting() = withBar { bar, _ ->
        var calls = 0
        bar.onTest = {
            calls++
            assertEquals(View.VISIBLE, bar.box(R.id.latency_progress).visibility)
            assertEquals("", bar.text(R.id.latency).text.toString())
            bar.box(R.id.latency_box).performClick() // Re-entrant click also must be ignored.
        }
        bar.box(R.id.speed_box).performClick()
        bar.box(R.id.latency_box).performClick()
        assertEquals(0, calls)
        bar.changeState(BaseService.State.Connected)
        bar.box(R.id.speed_box).performClick()
        repeat(3) { bar.box(R.id.latency_box).performClick(); bar.box(R.id.speed_box).performClick() }
        assertEquals(1, calls)
        assertFalse(bar.box(R.id.speed_box).isEnabled)
        assertFalse(bar.box(R.id.latency_box).isEnabled)
        assertTrue(bar.box(R.id.capsule).isEnabled) // Stopping remains available while testing.
        bar.showLatency(56); advance(250)
        assertEquals("56 ms", bar.text(R.id.latency).text.toString())
        bar.box(R.id.latency_box).performClick()
        assertEquals(2, calls)
        bar.showLatency(-1); advance(250)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        assertTrue(bar.box(R.id.speed_box).isEnabled)
        bar.box(R.id.speed_box).performClick()
        assertEquals(3, calls)
    }

    @Test fun showTestingIsIdempotentAndDisconnectedCannotTest() = withBar { bar, _ ->
        bar.showTesting()
        assertUnknown(bar)
        bar.changeState(BaseService.State.Connected)
        bar.showTesting()
        bar.showTesting()
        assertEquals(View.VISIBLE, bar.box(R.id.latency_progress).visibility)
            assertEquals("", bar.text(R.id.latency).text.toString())
        bar.showLatency(Int.MIN_VALUE); advance(250)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        assertTrue(bar.box(R.id.latency_box).isEnabled)
        bar.showTesting()
        bar.showLatency(0); advance(250)
        assertEquals("0 ms", bar.text(R.id.latency).text.toString())
    }

    @Test fun missingCallbacksDoNotAcquirePermanentLocks() = withBar { bar, _ ->
        bar.box(R.id.capsule).performClick()
        assertTrue(bar.box(R.id.capsule).isEnabled)
        bar.changeState(BaseService.State.Connected)
        bar.box(R.id.speed_box).performClick()
        assertTrue(bar.box(R.id.speed_box).isEnabled)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
    }

    @Test fun synchronousCallbackFailureReleasesTestAndToggleLocks() = withBar { bar, _ ->
        bar.onToggle = { throw IllegalStateException("permission error") }
        try { bar.box(R.id.capsule).performClick(); fail("expected callback exception") }
        catch (_: IllegalStateException) { /* Caller retains its original exception. */ }
        assertTrue(bar.box(R.id.capsule).isEnabled)
        bar.changeState(BaseService.State.Connected)
        bar.onTest = { throw IllegalStateException("URL test launch error") }
        try { bar.box(R.id.speed_box).performClick(); fail("expected callback exception") }
        catch (_: IllegalStateException) { }
        advance(250)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        assertTrue(bar.box(R.id.speed_box).isEnabled)
    }

    @Test fun speedMappingIsRxDownloadTxUploadAndNegativeRatesClamp() = withBar { bar, _ ->
        bar.updateSpeed(724_000L, 893_000L)
        assertUnknown(bar) // Ignore late speed callbacks after disconnection.
        bar.changeState(BaseService.State.Connected)
        bar.updateSpeed(724_000L, 893_000L)
        assertEquals("▼ 893 kB/s", bar.text(R.id.speed_down).text.toString())
        assertEquals("▲ 724 kB/s", bar.text(R.id.speed_up).text.toString())
        bar.updateSpeed(-1L, Long.MIN_VALUE)
        assertEquals("▼ 0.00 kB/s", bar.text(R.id.speed_down).text.toString())
        assertEquals("▲ 0.00 kB/s", bar.text(R.id.speed_up).text.toString())
        bar.changeState(BaseService.State.Stopped)
        bar.updateSpeed(Long.MAX_VALUE, Long.MAX_VALUE)
        assertUnknown(bar)
    }

    @Test fun duplicateConnectedKeepsMeasurementAndDoesNotUnlockTesting() = withBar { bar, _ ->
        bar.changeState(BaseService.State.Connected)
        bar.showLatency(1234); advance(250)
        bar.updateSpeed(1_000_000L, 2_000_000L)
        bar.changeState(BaseService.State.Connected)
        assertEquals("1234 ms", bar.text(R.id.latency).text.toString())
        assertEquals("▼ 2.00 MB/s", bar.text(R.id.speed_down).text.toString())
        bar.showTesting()
        bar.changeState(BaseService.State.Connected)
        assertEquals(View.VISIBLE, bar.box(R.id.latency_progress).visibility)
            assertEquals("", bar.text(R.id.latency).text.toString())
        assertFalse(bar.box(R.id.latency_box).isEnabled)
    }

    @Test fun disconnectClearsTestAndRejectsResultsUntilReconnected() = withBar { bar, _ ->
        bar.changeState(BaseService.State.Connected)
        bar.showTesting()
        bar.updateSpeed(724_000L, 893_000L)
        bar.changeState(BaseService.State.Stopping)
        assertUnknown(bar)
        bar.showLatency(8888); advance(250) // A late callback cannot paint a disconnected view.
        assertUnknown(bar)
        bar.changeState(BaseService.State.Stopped)
        bar.changeState(BaseService.State.Connecting)
        bar.changeState(BaseService.State.Connected)
        assertUnknown(bar)
        var calls = 0
        bar.onTest = { calls++ }
        bar.box(R.id.latency_box).performClick()
        assertEquals(1, calls)
    }

    @Test fun profileResetClearsRequestsWhilePreservingRealServiceState() = withBar { bar, _ ->
        bar.changeState(BaseService.State.Connected)
        bar.updateSpeed(1_000_000L, 2_000_000L)
        bar.showTesting()
        bar.onToggle = { }
        bar.box(R.id.capsule).performClick()
        bar.reset()
        assertUnknown(bar)
        assertTrue(bar.box(R.id.capsule).isSelected)
        assertTrue(bar.box(R.id.capsule).isEnabled)
        assertTrue(bar.box(R.id.speed_box).isEnabled)
        var calls = 0
        bar.onTest = { calls++ }
        bar.box(R.id.speed_box).performClick()
        assertEquals(1, calls)
        // Profile/generation cancellation belongs to MainActivity, not to this view-only test.
    }

    @Test fun detachedViewRejectsResultsAndReattachmentDoesNotKeepTesting() = withBar { bar, host ->
        bar.changeState(BaseService.State.Connected)
        bar.showTesting()
        host.removeView(bar)
        assertUnknown(bar)
        bar.showTesting()
        bar.showLatency(42); advance(250)
        bar.updateSpeed(1_000_000L, 2_000_000L)
        assertUnknown(bar)
        assertFalse(bar.box(R.id.latency_box).isEnabled)
        host.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(bar, 84)))
        measure(host, dp(bar, 292), dp(bar, 84))
        assertTrue(bar.box(R.id.latency_box).isEnabled)
        assertUnknown(bar)
        var calls = 0
        bar.onTest = { calls++ }
        bar.box(R.id.latency_box).performClick()
        assertEquals(1, calls)
    }

    @Test fun savingHierarchyNeverRestoresAStaleConnectedOrTestingPresentation() = withBar { bar, _ ->
        bar.id = 0x00fedcba
        bar.changeState(BaseService.State.Connected)
        bar.showTesting()
        val saved = SparseArray<Parcelable>()
        bar.saveHierarchyState(saved)
        bar.changeState(BaseService.State.Idle)
        bar.restoreHierarchyState(saved)
        assertFalse(bar.box(R.id.capsule).isSelected)
        assertTrue(bar.box(R.id.capsule).isEnabled)
        assertUnknown(bar)
    }

    @Test fun speedsLatencyAndTestingTextAreExactDayBlackNightWhite() {
        for (night in listOf(false,true)) withBar(night=night) { bar,_ ->
            val expected=if(night)Color.WHITE else Color.BLACK
            for(state in BaseService.State.values()) {
                bar.changeState(state)
                for(ms in listOf(-1,0,56,299,300,9999,Int.MAX_VALUE)) {
                    bar.showLatency(ms); advance(250)
                    for(id in listOf(R.id.speed_down,R.id.speed_up)) {
                        assertEquals(expected,bar.text(id).currentTextColor)
                    }
                }
                // Only a connected state may paint the green latency (spec 7.5).
                bar.changeState(BaseService.State.Connected)
                bar.showTesting()
                assertEquals(bar.resources.getColor(R.color.latency_green,bar.context.theme),bar.text(R.id.latency).currentTextColor)
            }
            assertEquals(expected,bar.text(R.id.capsule_label).currentTextColor)
        }
    }

    private fun assertTextFits(text: TextView) {
        assertEquals(1, text.maxLines)
        assertNull(text.ellipsize)
        assertEquals(View.VISIBLE, text.visibility)
        val available = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
        assertTrue("${text.text}: no horizontal room", available > 0)
        // The rendered line is the contract: unit spans render smaller than the
        // plain string, so measuring the plain chars would overestimate (and the
        // rendered width is what must never overlap or clip).
        val rendered = text.layout.getLineWidth(0)
        assertTrue("${text.text}: rendered=$rendered available=$available",
            rendered <= available + 1f)
        assertTrue("${text.text}: layout contains more than one line", text.layout.lineCount <= 1)
        assertEquals(text.text.length, text.layout.getLineEnd(0))
        assertEquals(0, text.layout.getEllipsisCount(0))
        val metrics = text.paint.fontMetrics
        assertTrue("${text.text}: vertical clipping", metrics.descent - metrics.ascent <=
            text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 1f)
        assertEquals("tnum", text.fontFeatureSettings)
        val minimumSp = if (text.id == R.id.capsule_label) 10f else 8f
        assertTrue("${text.text}: smaller than readable minimum", text.textSize + 0.5f >=
            minimumSp * text.resources.displayMetrics.scaledDensity)
    }

    private fun assertRegionsFit(bar: ControlBar) {
        val speed = bar.box(R.id.speed_box)
        val capsule = bar.box(R.id.capsule)
        val latency = bar.box(R.id.latency_box)
        assertTrue(speed.right <= capsule.left)
        assertTrue(capsule.right <= latency.left)
        assertTrue(latency.right <= (bar.getChildAt(0) as ViewGroup).width)
        assertTrue("the toggle keeps the HTML-fixed capsule width", capsule.width >= dp(bar, 118))
        assertEquals(dp(bar, 44), capsule.height)
        assertTrue(speed.width >= dp(bar, 48))
        assertTrue(latency.width >= dp(bar, 48))
        val label = bar.text(R.id.latency)
        assertEquals(Gravity.END, label.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        assertEquals(Gravity.CENTER_VERTICAL, label.gravity and Gravity.VERTICAL_GRAVITY_MASK)
        assertEquals(dp(bar, 4), latency.paddingEnd)
        assertEquals(dp(bar, 18), bar.getChildAt(0).paddingEnd)
        assertEquals(latency.height / 2f, (label.top + label.bottom) / 2f, 1f)
        for (id in listOf(R.id.speed_down, R.id.speed_up, R.id.capsule_label, R.id.latency)) {
            if (id != R.id.latency || bar.text(id).visibility == View.VISIBLE) assertTextFits(bar.text(id))
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun narrow320DpWithDoubleFontScaleHasNoOverlapsOrEllipsizedText() {
        for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.TAIWAN, Locale.ENGLISH)) {
            withBar(locale = locale, fontScale = 2f) { bar, host ->
                assertEquals(2f, bar.resources.configuration.fontScale, 0.01f)
                assertEquals(bar.resources.displayMetrics.density * 2f,
                    bar.resources.displayMetrics.scaledDensity, 0.05f)
                bar.changeState(BaseService.State.Connected)
                bar.updateSpeed(1_234_567_890L, 9_876_543_210L)
                bar.showLatency(9999); advance(250)
                measure(host, dp(bar, 320 - 28), dp(bar, 84))
                assertRegionsFit(bar)
                assertCapsulePair(bar)
                bar.showTesting()
                measure(host, dp(bar, 320 - 28), dp(bar, 84))
                assertRegionsFit(bar)
                assertCapsulePair(bar)
                bar.changeState(BaseService.State.Stopped)
                measure(host, dp(bar, 320 - 28), dp(bar, 84))
                assertRegionsFit(bar)
                assertCapsulePair(bar)
                // Long/Int maxima are formatter/state tests, not a realistic 320dp UI requirement.
                bar.changeState(BaseService.State.Connected)
                bar.updateSpeed(724_567L, 99_123_456L)
                bar.showLatency(9999); advance(250)
                measure(host, dp(bar, 320 - 28), dp(bar, 84))
                assertRegionsFit(bar)
                assertCapsulePair(bar)
                measure(host, dp(bar, 400 - 28), dp(bar, 84))
                assertRegionsFit(bar)
                assertCapsulePair(bar)
                measure(host, dp(bar, 320 - 28), dp(bar, 84))
                assertRegionsFit(bar)
                assertCapsulePair(bar)
            }
        }
    }

    @Test fun bothSpeedLinesUseNormalSansSerifInDayAndNight() {
        for (night in listOf(false, true)) {
            withBar(night = night) { bar, _ ->
                assertEquals(Typeface.create("sans-serif", Typeface.NORMAL),
                    bar.text(R.id.speed_down).typeface)
                assertEquals(Typeface.create("sans-serif", Typeface.NORMAL),
                    bar.text(R.id.speed_up).typeface)
                // Not connected yet: the readout is neutral, never green (spec 7.5).
                assertEquals(bar.resources.getColor(R.color.control_bar_text,bar.context.theme),
                    bar.text(R.id.latency).currentTextColor)
            }
        }
    }

    @Test fun chineseStatesAreLocalizedAndTouchTargetsExposeButtonSemantics() {
        for ((locale, connected, disconnected) in listOf(
            Triple(Locale.SIMPLIFIED_CHINESE, "已连接", "未连接"),
            Triple(Locale.TAIWAN, "已連線", "未連線"),
            Triple(Locale.ENGLISH, "Connected", "Disconnected"),
        )) {
            withBar(locale = locale) { bar, _ ->
                assertEquals(disconnected, bar.text(R.id.capsule_label).text.toString())
                bar.changeState(BaseService.State.Connected)
                advance(650)
                assertEquals(connected, bar.text(R.id.capsule_label).text.toString())
                for (id in listOf(R.id.speed_box, R.id.capsule, R.id.latency_box)) {
                    val view = bar.box(id)
                    assertTrue(view.isFocusable)
                    assertTrue(view.isClickable)
                    assertFalse(view.contentDescription.isNullOrBlank())
                    assertEquals("android.widget.Button", view.createAccessibilityNodeInfo().className)
                }
            }
        }
    }
    private fun assertCapsulePair(bar: ControlBar) {
        val capsule = bar.box(R.id.capsule)
        val pair = bar.box(R.id.capsule_pair)
        val powerLayer = bar.box(R.id.capsule_power_layer)
        val planeLayer = bar.box(R.id.capsule_plane_layer)
        val planeContent = bar.box(R.id.capsule_plane_content)
        val power = bar.findViewById<ImageView>(R.id.capsule_icon)
        val plane = bar.findViewById<ImageView>(R.id.capsule_plane_icon)
        val label = bar.text(R.id.capsule_label)
        assertTrue(pair is FrameLayout)
        assertEquals(capsule.width - capsule.paddingLeft - capsule.paddingRight, pair.width)
        assertEquals(capsule.height - capsule.paddingTop - capsule.paddingBottom, pair.height)
        // Pair bounds are capsule-local, including the capsule's horizontal padding.
        assertEquals(capsule.width / 2f, (pair.left + pair.right) / 2f, 1f)
        assertEquals(capsule.height / 2f, (pair.top + pair.bottom) / 2f, 1f)
        assertEquals(dp(bar, 18), power.width); assertEquals(dp(bar, 18), power.height)
        assertEquals(dp(bar, 18), plane.width); assertEquals(dp(bar, 18), plane.height)
        assertNotNull(power.drawable); assertNotNull(plane.drawable)
        assertFalse(pair.isClickable); assertFalse(power.isClickable); assertFalse(plane.isClickable)
        assertFalse(label.isClickable)
        assertEquals(pair.width, powerLayer.width); assertEquals(pair.height, powerLayer.height)
        assertEquals(pair.width, planeLayer.width); assertEquals(pair.height, planeLayer.height)
        assertEquals(LinearLayout.HORIZONTAL, (planeContent as LinearLayout).orientation)
        if (label.visibility == View.VISIBLE) {
            assertEquals(dp(bar, 6), label.left - plane.right)
            assertTextFits(label)
        } else {
            assertEquals(View.GONE, label.visibility)
            assertEquals(pair.width / 2f, (power.left + power.right) / 2f, 1f)
        }
    }
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun iconAndLabelStayAdjacentAsOneCenteredPairInDayNightAndLargeFonts() {
        for(night in listOf(false,true)) for(scale in listOf(1f,2f)) {
            for(locale in listOf(Locale.SIMPLIFIED_CHINESE,Locale.TAIWAN,Locale.ENGLISH)) {
                withBar(night=night,locale=locale,fontScale=scale) { bar,host ->
                    for(state in listOf(BaseService.State.Idle,BaseService.State.Connected)) {
                        bar.changeState(state)
                        measure(host,dp(bar,320-28),dp(bar,84))
                        assertCapsulePair(bar)
                    }
                }
            }
        }
    }
    @Test fun clearingMeasurementsDuringPermissionDoesNotUnlockPendingStart()=withBar {bar,_ ->
        var calls=0;bar.onToggle={calls++}
        val capsule=bar.box(R.id.capsule)
        capsule.performClick();assertEquals(1,calls);assertFalse(capsule.isEnabled)
        repeat(3){bar.clearMeasurements()}
        assertUnknown(bar);assertFalse(capsule.isEnabled)
        capsule.performClick();assertEquals(1,calls)
        bar.changeState(BaseService.State.Idle)
        assertTrue(capsule.isEnabled)
        capsule.performClick();assertEquals(2,calls)
    }
}
