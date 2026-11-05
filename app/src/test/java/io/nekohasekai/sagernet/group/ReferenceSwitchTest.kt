package io.nekohasekai.sagernet.group

import android.animation.ValueAnimator
import android.app.Application
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Switch
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ReferenceSwitch
import io.nekohasekai.sagernet.widget.ReferenceSwitchPreference
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ReferenceSwitchTest {
    @Before fun clock() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        durationScale(1f)
    }

    @After fun resetClock() {
        durationScale(1f)
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }

    private fun durationScale(scale: Float) {
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, scale)
    }

    private fun advance(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }

    private inner class Fixture : AutoCloseable {
        val controller = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet) }
        val root = FrameLayout(activity)
        val widget = ReferenceSwitch(activity)
        init {
            controller.setup().visible()
            activity.setContentView(root)
            root.addView(widget, FrameLayout.LayoutParams(-2, -2))
            layout()
            advance(32) // Flush actual window attachment/visibility before exercising live motion.
            // Legacy Robolectric manually lays out views without WindowManager relayout;
            // deliver its visible-window state, while retaining the widget's production guard.
            val attachInfo = org.robolectric.util.ReflectionHelpers.getField<Any>(
                activity.window.decorView, "mAttachInfo")
            org.robolectric.util.ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
            activity.window.decorView.dispatchWindowVisibilityChanged(View.VISIBLE)
            layout()
            assertTrue("test switch must be attached", widget.isAttachedToWindow)
            assertTrue("test switch must be shown", widget.isShown)
            assertEquals(View.VISIBLE, widget.windowVisibility)
        }
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 600, 900)
        }
        override fun close() { controller.pause().stop().destroy() }
    }

    private fun animator(widget: ReferenceSwitch): ValueAnimator =
        ReferenceSwitch::class.java.getDeclaredField("motion").apply { isAccessible = true }
            .get(widget) as ValueAnimator

    @Test fun measuredGeometryIs44By24Not48AndRtlMirrorsExactly20Dp() {
        Fixture().use { f ->
            val w = f.widget
            val d = w.resources.displayMetrics.density
            assertEquals((44 * d).toInt(), w.measuredWidth)
            assertEquals((48 * d).toInt(), w.measuredHeight)
            assertEquals(44 * d, w.trackBounds().width(), .01f)
            assertEquals(24 * d, w.trackBounds().height(), .01f)
            assertEquals(12 * d, w.thumbCenterX() - w.trackBounds().left, .01f)
            assertEquals(18f, ReferenceSwitch.THUMB_SIZE, 0f)
            assertNull(w.background)
            assertNull(w.thumbDrawable)
            assertFalse(w.showText)
            w.isChecked = true
            w.jumpDrawablesToCurrentState()
            assertEquals(32 * d, w.thumbCenterX() - w.trackBounds().left, .01f)
            w.layoutDirection = View.LAYOUT_DIRECTION_RTL
            assertEquals(12 * d, w.thumbCenterX() - w.trackBounds().left, .01f)
            val info = w.createAccessibilityNodeInfo()
            assertEquals("android.widget.Switch", info.className.toString())
            assertTrue(info.isCheckable)
            assertTrue(info.isChecked)
        }
    }

    @Test fun realTouchCommitsCheckedImmediatelyAndBoth300msCurvesAreDistinct() {
        Fixture().use { f ->
            val w = f.widget
            val values = mutableListOf<Boolean>()
            w.setOnCheckedChangeListener { _, value -> values += value }
            val time = android.os.SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(time, time, action, 20f, 24f, 0)
                assertTrue("native widget handles touch $action", w.dispatchTouchEvent(event))
                event.recycle()
            }
            // The real ViewRoot posts its click behind traversal; process it with paused frames.
            shadowOf(Looper.getMainLooper()).idle()
            advance(32)
            assertEquals(listOf(true), values)
            assertTrue(w.isChecked)
            assertEquals(0f, w.thumbFraction, .01f)
            val animation = animator(w)
            assertEquals(300L, animation.duration)
            animation.currentPlayTime = 150L
            assertEquals(.77556f, w.thumbFraction, .003f)
            assertEquals(.8024f, w.trackFraction, .003f)
            assertTrue(w.isAnimating)
            animation.end()
            assertEquals(1f, w.thumbFraction, 0f)
            assertEquals(listOf(true), values)
        }
    }

    @Test fun rapidReverseStartsAtLivePositionAndStaleEndCannotSnapIt() {
        Fixture().use { f ->
            val w = f.widget
            w.isChecked = true
            val old = animator(w)
            old.currentPlayTime = 120
            val thumb = w.thumbFraction
            val track = w.trackFraction
            w.isChecked = false
            assertFalse(w.isChecked)
            assertEquals(thumb, w.thumbFraction, .001f)
            assertEquals(track, w.trackFraction, .001f)
            val reverse = animator(w)
            old.end()
            assertEquals(thumb, w.thumbFraction, .001f)
            reverse.currentPlayTime = 150
            assertEquals(thumb * (1 - .77556f), w.thumbFraction, .003f)
            advance(400)
            assertFalse(w.isAnimating)
            assertEquals(0f, w.thumbFraction, 0f)
        }
    }

    @Test fun bindSameItemPreservesMotionButRecyclingAndInitialBindingNeverReplay() {
        Fixture().use { f ->
            val w = f.widget
            w.bind("app-a") { w.isChecked = true }
            assertFalse(w.isAnimating)
            assertEquals(1f, w.thumbFraction, 0f)
            w.bind("app-a") { w.isChecked = false }
            val live = animator(w)
            live.currentPlayTime = 100
            val before = w.thumbFraction
            w.bind("app-a") { w.isChecked = false }
            assertSame(live, animator(w))
            assertEquals(before, w.thumbFraction, 0f)
            w.bind("app-b") { w.isChecked = true }
            assertFalse(w.isAnimating)
            assertEquals(1f, w.thumbFraction, 0f)
        }
    }

    @Test fun detachHiddenDisabledAndAnimationsOffSettleToRealChecked() {
        Fixture().use { f ->
            val w = f.widget
            w.isChecked = true
            animator(w).currentPlayTime = 100
            f.root.visibility = View.INVISIBLE
            assertEquals(1f, w.thumbFraction, 0f)
            assertFalse(w.isAnimating)
            f.root.visibility = View.VISIBLE
            w.isChecked = false
            f.root.removeView(w)
            assertEquals(0f, w.thumbFraction, 0f)
            assertFalse(w.isAnimating)
            f.root.addView(w)
            f.layout()
            w.isChecked = true
            w.isEnabled = false
            assertEquals(1f, w.thumbFraction, 0f)
            assertFalse(w.performClick())
            assertTrue(w.isChecked)
            w.isEnabled = true
            durationScale(0f)
            w.isChecked = false
            assertFalse(w.isAnimating)
            assertEquals(0f, w.thumbFraction, 0f)
            durationScale(1f)
            w.isChecked = true
            animator(w).currentPlayTime = 100
            durationScale(0f)
            advance(32)
            assertFalse(w.isAnimating)
            assertEquals(1f, w.thumbFraction, 0f)
        }
    }

    @Test fun savedStateKeepsBooleanNotMidpointAndListenerVetoKeepsModelTruth() {
        Fixture().use { f ->
            val w = f.widget
            w.isChecked = true
            animator(w).currentPlayTime = 80
            val state = w.onSaveInstanceState()
            val restored = ReferenceSwitch(f.activity)
            restored.onRestoreInstanceState(state)
            assertTrue(restored.isChecked)
            assertEquals(1f, restored.thumbFraction, 0f)
            assertFalse(restored.isAnimating)
            w.isChecked = false
            w.jumpDrawablesToCurrentState()
            w.setOnCheckedChangeListener { button, checked -> if (checked) button.isChecked = false }
            w.performClick()
            assertFalse(w.isChecked)
            assertEquals(0f, w.thumbFraction, 0f)
            assertFalse(w.isAnimating)
        }
    }

    @Test fun actualPreferenceAdapterBindsNativeListenerPersistsRowClickAndRecycles() {
        Fixture().use { f ->
            f.root.removeView(f.widget)
            val manager = PreferenceManager(f.activity)
            val screen = manager.createPreferenceScreen(f.activity)
            manager.setPreferences(screen)
            val pref = ReferenceSwitchPreference(f.activity).apply { key = "switch-a"; title = "A" }
            val next = ReferenceSwitchPreference(f.activity).apply { key = "switch-b"; title = "B" }
            screen.addPreference(pref)
            screen.addPreference(next)
            val dependent = Preference(f.activity).apply { key = "dependent" }
            screen.addPreference(dependent)
            screen.onAttached()
            dependent.dependency = pref.key
            val adapter = PreferenceGroupAdapter(screen)
            val holder = adapter.onCreateViewHolder(f.root, adapter.getItemViewType(0))
            f.root.addView(holder.itemView)
            f.layout()
            adapter.onBindViewHolder(holder, 0)
            val w = holder.findViewById(android.R.id.switch_widget) as ReferenceSwitch
            assertTrue(w is Switch)
            assertFalse(w.isClickable)
            assertFalse(w.isFocusable)
            assertFalse(dependent.isEnabled)
            assertFalse(w.isAnimating)
            assertTrue(holder.itemView.performClick())
            assertTrue(pref.isChecked)
            assertTrue(dependent.isEnabled)
            assertTrue(manager.sharedPreferences!!.getBoolean(pref.key, false))
            adapter.onBindViewHolder(holder, 0)
            assertTrue(w.isChecked)
            assertTrue(w.isAnimating)
            animator(w).currentPlayTime = 100
            adapter.onBindViewHolder(holder, 1)
            assertFalse(w.isChecked)
            assertFalse(w.isAnimating)
            assertEquals(0f, w.thumbFraction, 0f)
            // SwitchPreference's `is Switch` listener must really have been installed.
            w.isClickable = true
            w.performClick()
            assertTrue(next.isChecked)
            assertTrue(manager.sharedPreferences!!.getBoolean(next.key, false))
            next.setOnPreferenceChangeListener { _, _ -> false }
            w.performClick()
            assertTrue(w.isChecked)
            assertTrue(next.isChecked)
            screen.onDetached()
        }
    }

    @Test fun allExplicitLayoutsActuallyInflateReferenceSwitch() {
        Fixture().use { f ->
            val inflater = LayoutInflater.from(f.activity)
            for ((layout, id) in listOf(
                R.layout.preference_widget_switch to android.R.id.switch_widget,
                R.layout.layout_route_item to R.id.enable,
                R.layout.layout_apps_item to R.id.itemcheck,
                R.layout.layout_local_proxy_dialog to R.id.allowAccessSwitch,
            )) {
                val view = inflater.inflate(layout, f.root, false)
                assertTrue("layout $layout must inflate the native shared widget",
                    view.findViewById<View>(id) is ReferenceSwitch)
            }
        }
    }
}
