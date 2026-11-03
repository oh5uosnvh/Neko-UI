package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.os.Parcelable
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.widget.ControlBar
import org.junit.Assert.*
import org.junit.Test
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
            host.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(bar, 76)))
            activity.setContentView(host)
            measure(host, dp(bar, 320 - 28), dp(bar, 76))
            block(bar, host)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun measure(host: View, width: Int, height: Int) {
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertUnknown(bar: ControlBar) {
        assertEquals("▼ -- kB/s", bar.text(R.id.speed_down).text.toString())
        assertEquals("▲ -- kB/s", bar.text(R.id.speed_up).text.toString())
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
    }

    private fun capsuleColor(bar: ControlBar): Int {
        val drawable = bar.text(R.id.capsule).background as StateListDrawable
        return (drawable.current as GradientDrawable).color!!.defaultColor
    }

    @Test fun mergeInflatesOnceAndColdStateHasNoInventedMeasurements() = withBar { bar, _ ->
        assertTrue(bar is FrameLayout)
        assertEquals(1, bar.childCount)
        assertTrue(bar.getChildAt(0) is LinearLayout)
        assertEquals(3, (bar.getChildAt(0) as ViewGroup).childCount)
        assertEquals(dp(bar, 76), bar.height)
        assertUnknown(bar)
        assertFalse(bar.text(R.id.capsule).isSelected)
        assertEquals(bar.context.getString(R.string.control_bar_disconnected), bar.text(R.id.capsule).text)
        assertTrue(bar.text(R.id.capsule).isEnabled)
        assertFalse(bar.box(R.id.speed_box).isEnabled)
        assertFalse(bar.box(R.id.latency_box).isEnabled)
        assertEquals(bar.resources.getColor(R.color.brand_primary_light, bar.context.theme), capsuleColor(bar))
        assertNotNull(bar.text(R.id.capsule).compoundDrawablesRelative[0])
    }

    @Test fun everyServiceStateUsesOnlyConnectedOrDisconnectedVisuals() = withBar { bar, _ ->
        for (state in BaseService.State.values()) {
            bar.changeState(state)
            val connected = state == BaseService.State.Connected
            val capsule = bar.text(R.id.capsule)
            assertEquals("state=$state", connected, capsule.isSelected)
            assertEquals(bar.context.getString(if (connected) R.string.control_bar_connected
                else R.string.control_bar_disconnected), capsule.text)
            assertEquals(!listOf(BaseService.State.Connecting, BaseService.State.Stopping).contains(state),
                capsule.isEnabled)
            assertEquals(connected, bar.box(R.id.speed_box).isEnabled)
            assertEquals(connected, bar.box(R.id.latency_box).isEnabled)
            assertEquals(bar.resources.getColor(if (connected) R.color.brand_primary
                else R.color.brand_primary_light, bar.context.theme), capsuleColor(bar))
            assertNotNull(capsule.compoundDrawablesRelative[0])
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
        val capsule = bar.text(R.id.capsule)
        repeat(3) { capsule.performClick() }
        assertEquals(1, toggles)
        assertFalse(capsule.isSelected)
        assertFalse(capsule.isEnabled)
        assertUnknown(bar)
        // Permission cancelled: an authoritative same-state report explicitly re-arms.
        bar.changeState(BaseService.State.Idle)
        capsule.performClick()
        assertEquals(2, toggles)
        bar.changeState(BaseService.State.Connecting)
        capsule.performClick()
        bar.changeState(BaseService.State.Stopping)
        capsule.performClick()
        assertEquals(2, toggles)
        bar.changeState(BaseService.State.Connected)
        capsule.performClick()
        capsule.performClick()
        assertEquals(3, toggles) // Connected can stop, but only once per state acknowledgement.
        assertTrue(capsule.isSelected) // The click did not fake a disconnected state either.
    }

    @Test fun clickLocksBeforeCallbackEvenIfHostDelaysCallingShowTesting() = withBar { bar, _ ->
        var calls = 0
        bar.onTest = {
            calls++
            assertEquals(bar.context.getString(R.string.control_bar_testing), bar.text(R.id.latency).text)
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
        assertTrue(bar.text(R.id.capsule).isEnabled) // Stopping remains available while testing.
        bar.showLatency(56)
        assertEquals("56 ms", bar.text(R.id.latency).text.toString())
        bar.box(R.id.latency_box).performClick()
        assertEquals(2, calls)
        bar.showLatency(-1)
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
        assertEquals(bar.context.getString(R.string.control_bar_testing), bar.text(R.id.latency).text)
        bar.showLatency(Int.MIN_VALUE)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
        assertTrue(bar.box(R.id.latency_box).isEnabled)
        bar.showTesting()
        bar.showLatency(0)
        assertEquals("0 ms", bar.text(R.id.latency).text.toString())
    }

    @Test fun missingCallbacksDoNotAcquirePermanentLocks() = withBar { bar, _ ->
        bar.text(R.id.capsule).performClick()
        assertTrue(bar.text(R.id.capsule).isEnabled)
        bar.changeState(BaseService.State.Connected)
        bar.box(R.id.speed_box).performClick()
        assertTrue(bar.box(R.id.speed_box).isEnabled)
        assertEquals("-- ms", bar.text(R.id.latency).text.toString())
    }

    @Test fun synchronousCallbackFailureReleasesTestAndToggleLocks() = withBar { bar, _ ->
        bar.onToggle = { throw IllegalStateException("permission error") }
        try { bar.text(R.id.capsule).performClick(); fail("expected callback exception") }
        catch (_: IllegalStateException) { /* Caller retains its original exception. */ }
        assertTrue(bar.text(R.id.capsule).isEnabled)
        bar.changeState(BaseService.State.Connected)
        bar.onTest = { throw IllegalStateException("URL test launch error") }
        try { bar.box(R.id.speed_box).performClick(); fail("expected callback exception") }
        catch (_: IllegalStateException) { }
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
        bar.showLatency(1234)
        bar.updateSpeed(1_000_000L, 2_000_000L)
        bar.changeState(BaseService.State.Connected)
        assertEquals("1234 ms", bar.text(R.id.latency).text.toString())
        assertEquals("▼ 2.00 MB/s", bar.text(R.id.speed_down).text.toString())
        bar.showTesting()
        bar.changeState(BaseService.State.Connected)
        assertEquals(bar.context.getString(R.string.control_bar_testing), bar.text(R.id.latency).text)
        assertFalse(bar.box(R.id.latency_box).isEnabled)
    }

    @Test fun disconnectClearsTestAndRejectsResultsUntilReconnected() = withBar { bar, _ ->
        bar.changeState(BaseService.State.Connected)
        bar.showTesting()
        bar.updateSpeed(724_000L, 893_000L)
        bar.changeState(BaseService.State.Stopping)
        assertUnknown(bar)
        bar.showLatency(8888) // A late callback cannot paint a disconnected view.
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
        bar.text(R.id.capsule).performClick()
        bar.reset()
        assertUnknown(bar)
        assertTrue(bar.text(R.id.capsule).isSelected)
        assertTrue(bar.text(R.id.capsule).isEnabled)
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
        bar.showLatency(42)
        bar.updateSpeed(1_000_000L, 2_000_000L)
        assertUnknown(bar)
        assertFalse(bar.box(R.id.latency_box).isEnabled)
        host.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(bar, 76)))
        measure(host, dp(bar, 292), dp(bar, 76))
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
        assertFalse(bar.text(R.id.capsule).isSelected)
        assertTrue(bar.text(R.id.capsule).isEnabled)
        assertUnknown(bar)
    }

    @Test fun allLatencyValuesAndStatesStayExactlyGreen() = withBar { bar, _ ->
        val green = bar.resources.getColor(R.color.latency_green, bar.context.theme)
        val primary = bar.resources.getColor(R.color.brand_primary, bar.context.theme)
        assertEquals(primary, bar.text(R.id.speed_down).currentTextColor)
        assertEquals(green, bar.text(R.id.speed_up).currentTextColor)
        for (state in BaseService.State.values()) {
            bar.changeState(state)
            for (ms in listOf(-1, 0, 56, 299, 300, 9999, Int.MAX_VALUE)) {
                bar.showLatency(ms)
                assertEquals(green, bar.text(R.id.latency).currentTextColor)
            }
            bar.showTesting()
            assertEquals(green, bar.text(R.id.latency).currentTextColor)
        }
    }

    private fun assertTextFits(text: TextView) {
        assertEquals(1, text.maxLines)
        assertNull(text.ellipsize)
        assertEquals(View.VISIBLE, text.visibility)
        val available = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
        assertTrue("${text.text}: no horizontal room", available > 0)
        assertTrue("${text.text}: measured=${text.paint.measureText(text.text.toString())} available=$available",
            text.paint.measureText(text.text.toString()) <= available + 1f)
        assertTrue("${text.text}: layout contains more than one line", text.layout.lineCount <= 1)
        assertEquals(text.text.length, text.layout.getLineEnd(0))
        assertEquals(0, text.layout.getEllipsisCount(0))
        val metrics = text.paint.fontMetrics
        assertTrue("${text.text}: vertical clipping", metrics.descent - metrics.ascent <=
            text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 1f)
        assertEquals("tnum", text.fontFeatureSettings)
        val minimumSp = if (text.id == R.id.capsule) 10f else 8f
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
        assertTrue(capsule.width >= dp(bar, 48))
        assertEquals(dp(bar, 48), capsule.height)
        assertTrue(speed.width >= dp(bar, 48))
        assertTrue(latency.width >= dp(bar, 48))
        val label = bar.text(R.id.latency)
        assertEquals(Gravity.END, label.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        assertEquals(Gravity.CENTER_VERTICAL, label.gravity and Gravity.VERTICAL_GRAVITY_MASK)
        assertEquals(dp(bar, 4), latency.paddingEnd)
        assertEquals(dp(bar, 18), bar.getChildAt(0).paddingEnd)
        assertEquals(latency.height / 2f, (label.top + label.bottom) / 2f, 1f)
        for (id in listOf(R.id.speed_down, R.id.speed_up, R.id.capsule, R.id.latency)) {
            assertTextFits(bar.text(id))
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
                bar.showLatency(9999)
                measure(host, dp(bar, 320 - 28), dp(bar, 76))
                assertRegionsFit(bar)
                bar.showTesting()
                measure(host, dp(bar, 320 - 28), dp(bar, 76))
                assertRegionsFit(bar)
                bar.changeState(BaseService.State.Stopped)
                measure(host, dp(bar, 320 - 28), dp(bar, 76))
                assertRegionsFit(bar)
                // Long/Int maxima are formatter/state tests, not a realistic 320dp UI requirement.
                bar.changeState(BaseService.State.Connected)
                bar.updateSpeed(724_567L, 99_123_456L)
                bar.showLatency(9999)
                measure(host, dp(bar, 320 - 28), dp(bar, 76))
                assertRegionsFit(bar)
                measure(host, dp(bar, 400 - 28), dp(bar, 76))
                assertRegionsFit(bar)
                measure(host, dp(bar, 320 - 28), dp(bar, 76))
                assertRegionsFit(bar)
            }
        }
    }

    @Test fun downUpTypefaceWeightsReverseBetweenDayAndNight() {
        for (night in listOf(false, true)) {
            withBar(night = night) { bar, _ ->
                assertEquals(Typeface.create(if (night) "sans-serif-light" else "sans-serif-black", Typeface.NORMAL),
                    bar.text(R.id.speed_down).typeface)
                assertEquals(Typeface.create(if (night) "sans-serif-black" else "sans-serif-light", Typeface.NORMAL),
                    bar.text(R.id.speed_up).typeface)
                assertEquals(bar.resources.getColor(R.color.latency_green, bar.context.theme),
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
                assertEquals(disconnected, bar.text(R.id.capsule).text.toString())
                bar.changeState(BaseService.State.Connected)
                assertEquals(connected, bar.text(R.id.capsule).text.toString())
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
}
