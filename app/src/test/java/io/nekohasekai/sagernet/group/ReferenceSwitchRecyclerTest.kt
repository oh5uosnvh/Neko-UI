package io.nekohasekai.sagernet.group

import android.animation.ValueAnimator
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.settingsRowsInflater
import io.nekohasekai.sagernet.ui.withoutSettingsRowPress
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
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Real Fragment/RecyclerView notification, payload rebind and pressed-release queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ReferenceSwitchRecyclerTest {
    class CountingSwitch(context: android.content.Context) : ReferenceSwitchPreference(context) {
        var binds = 0
        var temporaryDetachedBinds = 0
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            binds++
            val widget = holder.findViewById(android.R.id.switch_widget) as ReferenceSwitch
            if (widget.isAttachedToWindow && !widget.isShown) temporaryDetachedBinds++
            super.onBindViewHolder(holder)
        }
    }

    class Screen : PreferenceFragmentCompat() {
        lateinit var toggle: CountingSwitch
        lateinit var dependent: Preference
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
            toggle = CountingSwitch(requireContext()).apply {
                key = "live-switch"
                title = "Only update when connected"
            }
            preferenceScreen.addPreference(toggle)
            dependent = Preference(requireContext()).apply { key = "dependent"; title = "Dependent" }
            preferenceScreen.addPreference(dependent)
            dependent.dependency = toggle.key
            repeat(30) { index ->
                preferenceScreen.addPreference(ReferenceSwitchPreference(requireContext()).apply {
                    key = "other-$index"; title = key; isPersistent = false
                })
            }
        }
        override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup,
            savedInstanceState: Bundle?): RecyclerView =
            super.onCreateRecyclerView(settingsRowsInflater(inflater, parent), parent, savedInstanceState)
                .withoutSettingsRowPress()
    }

    @Before fun clock() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, 1f)
    }
    @After fun resetClock() {
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, 1f)
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

    private inner class Fixture : AutoCloseable {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet) }
        val root = FrameLayout(activity).apply { id = View.generateViewId() }
        val screen = Screen()
        val list get() = screen.listView
        val row get() = list.findViewHolderForAdapterPosition(0)!!.itemView
        val widget get() = row.findViewById<ReferenceSwitch>(android.R.id.switch_widget)
        init {
            controller.setup().visible()
            activity.setContentView(root)
            activity.supportFragmentManager.beginTransaction().replace(root.id, screen).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            layout()
            advance(32)
            val attachInfo = ReflectionHelpers.getField<Any>(activity.window.decorView, "mAttachInfo")
            ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
            activity.window.decorView.dispatchWindowVisibilityChanged(View.VISIBLE)
            layout()
            advance(32)
            assertTrue(list.adapter is PreferenceGroupAdapter)
            assertTrue(list.itemAnimator is DefaultItemAnimator)
            assertTrue(widget.isAttachedToWindow)
            assertTrue(widget.isLaidOut)
            assertTrue(widget.isShown)
            assertEquals(View.VISIBLE, widget.windowVisibility)
        }
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 600, 900)
        }
        fun clickRow() {
            val now = SystemClock.uptimeMillis()
            val x = row.left + 36f
            val y = row.top + row.height / 2f
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, x, y, 0)
                assertTrue("RecyclerView handles $action", list.dispatchTouchEvent(event))
                event.recycle()
            }
            // Real ACTION_UP posts performClick and pressed release behind traversal.
            shadowOf(Looper.getMainLooper()).idle()
            advance(32)
        }
        override fun close() { controller.pause().stop().destroy() }
    }

    private fun assertIntermediate(w: ReferenceSwitch) {
        assertTrue("live animator, thumb=${w.thumbFraction}, track=${w.trackFraction}", w.isAnimating)
        assertTrue("thumb must be between endpoints", w.thumbFraction > 0f && w.thumbFraction < 1f)
        assertTrue("track must be between endpoints", w.trackFraction > 0f && w.trackFraction < 1f)
    }

    @Test fun rowTouchNotificationRebindAndPressedReleaseKeepBothIntermediateFrames() {
        Fixture().use { f ->
            val w = f.widget
            val originalBinds = f.screen.toggle.binds
            assertFalse(w.isChecked)
            f.clickRow()
            assertTrue(f.screen.toggle.isChecked)
            assertTrue(f.screen.dependent.isEnabled)
            assertTrue(f.screen.preferenceManager.sharedPreferences!!.getBoolean("live-switch", false))
            advance(50)
            assertTrue("notifyChanged must actually rebind", f.screen.toggle.binds > originalBinds)
            assertSame("payload update reuses the live holder", w, f.widget)
            assertTrue(w.isChecked)
            assertIntermediate(w)
            val thumb50 = w.thumbFraction
            val color50 = w.trackColor()
            advance(100)
            assertFalse("pressed-release callback actually ran", f.row.isPressed)
            assertIntermediate(w)
            assertTrue(w.thumbFraction > thumb50)
            assertNotEquals(color50, w.trackColor())
            advance(200)
            assertFalse(w.isAnimating)
            assertEquals(1f, w.thumbFraction, 0f)
            assertEquals(1f, w.trackFraction, 0f)

            // Reverse via the real row as well: OFF must glide and fade back, not snap.
            val enabledColor = w.trackColor()
            f.clickRow()
            assertFalse(f.screen.toggle.isChecked)
            assertFalse(f.screen.dependent.isEnabled)
            assertFalse(f.screen.preferenceManager.sharedPreferences!!.getBoolean("live-switch", true))
            advance(50)
            assertSame(w, f.widget)
            assertIntermediate(w)
            assertNotEquals(enabledColor, w.trackColor())
            val reverseThumb = w.thumbFraction
            val reverseColor = w.trackColor()
            advance(100)
            assertIntermediate(w)
            assertTrue(w.thumbFraction < reverseThumb)
            assertNotEquals(reverseColor, w.trackColor())
            advance(200)
            assertFalse(w.isAnimating)
            assertEquals(0f, w.thumbFraction, 0f)
            assertEquals(0f, w.trackFraction, 0f)
        }
    }
}
