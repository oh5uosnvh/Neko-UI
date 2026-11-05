package io.nekohasekai.sagernet.group

import android.animation.ValueAnimator
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.settingsRowsInflater
import io.nekohasekai.sagernet.ui.withoutSettingsRowPress
import io.nekohasekai.sagernet.widget.ReferenceSwitch
import io.nekohasekai.sagernet.widget.ReferenceSwitchPreference
import io.nekohasekai.sagernet.widget.SettingsCardAdapter
import io.nekohasekai.sagernet.widget.createSettingsCardAdapter
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsCardInteractionTest {
    class Screen : PreferenceFragmentCompat() {
        lateinit var toggle: ReferenceSwitchPreference
        lateinit var dependent: Preference
        lateinit var category: PreferenceCategory
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
            category = PreferenceCategory(requireContext()).apply { title = "Updates" }
            preferenceScreen.addPreference(category)
            toggle = ReferenceSwitchPreference(requireContext()).apply {
                key = "card-switch"; title = "Connected only"
                setIcon(R.drawable.ic_baseline_security_24)
            }
            category.addPreference(toggle)
            toggle.isChecked = false
            dependent = Preference(requireContext()).apply { key = "dependent"; title = "Dependent" }
            category.addPreference(dependent)
            dependent.dependency = toggle.key
            repeat(40) { index -> category.addPreference(ReferenceSwitchPreference(requireContext()).apply {
                key = "other-$index"; title = key; isPersistent = false
            }) }
        }
        override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup,
            savedInstanceState: Bundle?): RecyclerView =
            super.onCreateRecyclerView(settingsRowsInflater(inflater, parent), parent, savedInstanceState)
                .withoutSettingsRowPress()
        override fun onCreateAdapter(preferenceScreen: PreferenceScreen) = createSettingsCardAdapter(preferenceScreen)
    }
    @Before fun clock() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
    }
    @After fun resetClock() {
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
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
    private inner class Fixture : AutoCloseable {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet) }
        val root = FrameLayout(activity).apply { id = View.generateViewId() }
        val screen = Screen()
        val list get() = screen.listView
        val adapter get() = list.adapter as SettingsCardAdapter
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
        }
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 600, 900)
            list.viewTreeObserver.dispatchOnPreDraw()
        }
        fun settleHierarchy() {
            // A real ViewRoot posts a sync barrier when a row requests layout. AndroidX's
            // hierarchy sync uses a normal Handler, so idle() alone cannot pass that barrier
            // while Choreographer is paused. Run two 16ms frames (traversal, then rebind),
            // rather than forcing the adapter to refresh or draining an unbounded queue.
            advance(32)
            layout()
        }
        fun row(preference: Preference) = list.findViewHolderForAdapterPosition(adapter.getPreferenceAdapterPosition(preference))!!.itemView
        override fun close() { controller.pause().stop().destroy() }
    }

    @Test fun payloadRebindKeepsAnimatedSwitchPersistenceDependenciesIconAndQuietPress() {
        Fixture().use { fixture ->
            val screen = fixture.screen
            val row = fixture.row(screen.toggle)
            val widget = row.findViewById<ReferenceSwitch>(android.R.id.switch_widget)
            assertTrue(fixture.list.itemAnimator is DefaultItemAnimator)
            assertTrue(widget.isAttachedToWindow && widget.isShown)
            assertFalse(screen.dependent.isEnabled)
            assertEquals(View.VISIBLE, row.findViewById<View>(android.R.id.icon).visibility)
            val index = fixture.adapter.index
            assertTrue(row.performClick())
            advance(64)
            fixture.layout()
            val rebound = fixture.row(screen.toggle)
            assertSame(row, rebound)
            assertSame(widget, rebound.findViewById(android.R.id.switch_widget))
            assertTrue(screen.toggle.isChecked)
            assertTrue(screen.dependent.isEnabled)
            assertTrue(screen.preferenceManager.sharedPreferences!!.getBoolean(screen.toggle.key, false))
            assertSame(index, fixture.adapter.index)
            assertTrue("same-holder payload must preserve 300ms switch animation", widget.isAnimating)
            assertTrue(widget.thumbFraction > 0f && widget.thumbFraction < 1f)
            rebound.isPressed = true
            fixture.list.viewTreeObserver.dispatchOnPreDraw()
            assertFalse(rebound.background?.state?.contains(android.R.attr.state_pressed) == true)
            rebound.isPressed = false
            advance(350)
            assertFalse(widget.isAnimating)
            assertEquals(1f, widget.thumbFraction, 0.001f)
            screen.toggle.setOnPreferenceChangeListener { _, _ -> false }
            assertTrue(rebound.performClick())
            advance(350)
            assertTrue("veto must still be honored", screen.toggle.isChecked)
            assertTrue(screen.dependent.isEnabled)
        }
    }

    @Test fun dynamicCategoryChildrenRefreshThroughTheAttachedPreferenceAdapter() {
        Fixture().use { fixture ->
            val adapter = fixture.adapter
            val originalCount = adapter.itemCount
            val category = PreferenceCategory(fixture.activity).apply { title = "Dynamic" }
            fixture.screen.preferenceScreen.addPreference(category)
            fixture.settleHierarchy()
            assertTrue(category.isVisible)
            assertEquals("an empty category must not leave a ghost card", originalCount, adapter.itemCount)
            assertEquals(RecyclerView.NO_POSITION, adapter.getPreferenceAdapterPosition(category))

            val child = Preference(fixture.activity).apply { key = "dynamic-child"; title = "Child" }
            category.addPreference(child)
            fixture.settleHierarchy()
            assertEquals(originalCount + 2, adapter.itemCount)
            assertEquals(2, adapter.index.sections.size)
            val childId = adapter.getItemId(adapter.getPreferenceAdapterPosition(child))
            repeat(2) {
                child.isVisible = false
                fixture.settleHierarchy()
                assertEquals(originalCount, adapter.itemCount)
                assertEquals(1, adapter.index.sections.size)
                assertEquals(RecyclerView.NO_POSITION, adapter.getPreferenceAdapterPosition(category))
                assertTrue("filtering must not change the category's own visibility", category.isVisible)
                child.isVisible = true
                fixture.settleHierarchy()
                assertEquals(originalCount + 2, adapter.itemCount)
                assertEquals(2, adapter.index.sections.size)
                assertSame(child, adapter.getItem(adapter.getPreferenceAdapterPosition(child)))
                assertEquals(childId, adapter.getItemId(adapter.getPreferenceAdapterPosition(child)))
            }
            category.removePreference(child)
            fixture.settleHierarchy()
            assertEquals(originalCount, adapter.itemCount)
            assertEquals(1, adapter.index.sections.size)
            category.addPreference(child)
            fixture.settleHierarchy()
            assertEquals(originalCount + 2, adapter.itemCount)
            fixture.screen.preferenceScreen.removePreference(category)
            fixture.settleHierarchy()
            assertEquals(originalCount, adapter.itemCount)
            assertEquals(1, adapter.index.sections.size)
            assertSame("hierarchy updates must keep the attached adapter", adapter, fixture.adapter)
        }
    }

    @Test fun recycledAndHiddenRowsKeepSingleCardAndCorrectEnabledState() {
        Fixture().use { fixture ->
            val screen = fixture.screen
            screen.toggle.isChecked = true
            advance(350)
            fixture.list.scrollToPosition(fixture.adapter.itemCount - 1)
            fixture.layout()
            advance(32)
            fixture.list.scrollToPosition(0)
            fixture.layout()
            advance(32)
            fixture.layout()
            val row = fixture.row(screen.toggle)
            row.isPressed = true
            fixture.list.viewTreeObserver.dispatchOnPreDraw()
            assertFalse(row.background?.state?.contains(android.R.attr.state_pressed) == true)
            assertTrue(row.findViewById<ReferenceSwitch>(android.R.id.switch_widget).isChecked)
            assertEquals(1, fixture.adapter.index.sections.size)
            screen.toggle.isEnabled = false
            fixture.layout()
            advance(32)
            fixture.layout()
            assertFalse(fixture.row(screen.toggle).isEnabled)
            assertFalse(screen.dependent.isEnabled)
            val originalAdapter = fixture.adapter
            val originalCount = originalAdapter.itemCount
            val toggleId = originalAdapter.getItemId(originalAdapter.getPreferenceAdapterPosition(screen.toggle))
            val source = ReflectionHelpers.getField<androidx.preference.PreferenceGroupAdapter>(originalAdapter, "source")
            assertSame("the active adapter must still own category notifications",
                source, ReflectionHelpers.getField<Any>(screen.category, "mListener"))
            screen.category.isVisible = false
            fixture.settleHierarchy()
            assertSame("hiding a category must not replace the preference adapter", originalAdapter, fixture.adapter)
            assertEquals("source must have consumed its queued visibility update", 0, source.itemCount)
            assertEquals(0, fixture.adapter.itemCount)
            assertTrue(fixture.adapter.index.sections.isEmpty())
            assertEquals(RecyclerView.NO_POSITION, fixture.adapter.getPreferenceAdapterPosition(screen.toggle))
            screen.category.isVisible = true
            fixture.settleHierarchy()
            assertSame(originalAdapter, fixture.adapter)
            assertEquals(originalCount, source.itemCount)
            assertEquals(originalCount, fixture.adapter.itemCount)
            assertEquals(1, fixture.adapter.index.sections.size)
            val togglePosition = fixture.adapter.getPreferenceAdapterPosition(screen.toggle)
            assertSame(screen.toggle, fixture.adapter.getItem(togglePosition))
            assertEquals(toggleId, fixture.adapter.getItemId(togglePosition))
            assertFalse(fixture.row(screen.toggle).isEnabled)
            assertFalse(screen.dependent.isEnabled)
            assertTrue(fixture.row(screen.toggle).findViewById<ReferenceSwitch>(android.R.id.switch_widget).isChecked)
        }
    }
}
