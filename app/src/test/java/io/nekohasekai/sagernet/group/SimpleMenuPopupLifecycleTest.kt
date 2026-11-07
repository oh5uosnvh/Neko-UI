package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ChoiceMenuPopup
import moe.matsuri.nb4a.ui.LongClickMenuPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Exercise real PopupWindow attachment + animator frames, not a mocked completion callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SimpleMenuPopupLifecycleTest {
    @Before fun pauseFrames() {
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ShadowChoreographer.setPaused(true)
    }
    @After fun restoreFrames() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }

    private fun frames(count: Int = 16) {
        repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    class TestActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_SagerNet_Blue)
            theme.applyStyle(androidx.preference.R.style.PreferenceThemeOverlay, true)
            super.onCreate(savedInstanceState)
        }
    }

    private class Fixture(longClick: Boolean = false) : AutoCloseable {
        val controller = Robolectric.buildActivity(TestActivity::class.java)
        val activity = controller.get()
        val host: FrameLayout
        val preference: SimpleMenuPreference
        val row: View
        val spinner: Spinner
        init {
            controller.setup().visible()
            host = FrameLayout(activity)
            preference = (if (longClick) LongClickMenuPreference(activity) else SimpleMenuPreference(activity)).apply {
                key = "nightThemeTest"
                title = "Night theme"
                entries = arrayOf("Follow system", "Enabled", "Disabled", "Automatic")
                entryValues = arrayOf("0", "1", "2", "3")
                setDefaultValue("0")
            }
            val manager = PreferenceManager(activity)
            manager.sharedPreferences!!.edit().clear().commit()
            val screen = manager.createPreferenceScreen(activity)
            screen.addPreference(preference)
            manager.setPreferences(screen)
            row = LayoutInflater.from(activity).inflate(preference.layoutResource, host, false)
            host.addView(row)
            bind()
            activity.setContentView(host)
            host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 320, 640)
            spinner = row.findViewById(R.id.spinner)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertTrue("real Spinner anchor must be attached", spinner.isAttachedToWindow)
            assertNotNull("real popup requires a window token", spinner.windowToken)
        }
        fun bind() = preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row))
        fun stored() = preference.sharedPreferences!!.getString(preference.key, null)
        fun open(): ChoiceMenuPopup {
            preference.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val popup = ReflectionHelpers.getField<ChoiceMenuPopup?>(preference, "choicePopup")
            assertNotNull("popup must open: attached=${spinner.isAttachedToWindow} token=${spinner.windowToken} " +
                "enabled=${preference.isEnabled} selectable=${preference.isSelectable} entries=${preference.entries?.size} " +
                "count=${spinner.adapter?.count} value=${preference.value}", popup)
            assertTrue(popup!!.isShowing)
            val content = popup.contentView
            content.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(popup.height, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, popup.width, popup.height)
            assertTrue(content.isAttachedToWindow)
            return popup
        }
        override fun close() {
            preference.onDetached()
            controller.pause().stop().destroy()
        }
    }

    private fun choose(popup: ChoiceMenuPopup, position: Int) {
        val list = popup.listView
        assertTrue(list.performItemClick(list.adapter.getView(position, null, list), position,
            list.adapter.getItemId(position)))
    }

    @Test fun entireSurfaceExitsBeforeSelectionAndPersistenceAreDeliveredExactlyOnce() {
        Fixture().use { f ->
            val popup = f.open()
            // Test the visible, settled surface's exit. The 450ms entrance now
            // correctly waits for attachment/layout instead of being skipped.
            frames(32)
            val surface = popup.contentView
            assertEquals(Color.TRANSPARENT, (popup.background as ColorDrawable).color)
            assertTrue("Fill and border must fade WITH the rows", surface.background is GradientDrawable)
            assertEquals(0, popup.animationStyle)
            assertNull(popup.exitTransition)
            var calls = 0
            f.preference.setOnPreferenceChangeListener { _, value ->
                assertFalse(popup.isShowing)
                assertFalse("The old window must be detached before reload/recreate", surface.isAttachedToWindow)
                assertEquals(0f, surface.alpha, .001f)
                assertEquals("1", value)
                assertEquals("0", f.stored())
                calls++
                true
            }
            choose(popup, 1)
            assertEquals(0, calls)
            assertEquals("0", f.preference.value)
            assertEquals("0", f.stored())
            assertTrue(popup.isShowing)
            frames(3)
            assertTrue(surface.alpha > 0f && surface.alpha < 1f)
            assertEquals(0, calls)
            assertEquals("0", f.stored())
            // A second item click/dismiss during exit cannot replace or deliver the choice twice.
            choose(popup, 2)
            popup.dismiss()
            frames()
            assertEquals(1, calls)
            assertEquals("1", f.preference.value)
            assertEquals("1", f.stored())
            assertFalse(surface.isAttachedToWindow)
            assertNull(ReflectionHelpers.getField<Any?>(f.preference, "choicePopup"))
            f.bind()
            frames()
            popup.dismiss()
            assertEquals(1, calls)
        }
    }

    @Test fun vetoCancelAndSameValueNeverPersistOrRepeatSideEffects() {
        Fixture().use { f ->
            var calls = 0
            f.preference.setOnPreferenceChangeListener { _, _ -> calls++; false }
            var popup = f.open()
            choose(popup, 2)
            assertEquals(0, calls)
            frames()
            assertEquals(1, calls)
            assertEquals("0", f.stored())
            f.bind()
            frames()
            assertEquals(1, calls)
            popup = f.open()
            popup.dismiss()
            frames()
            assertEquals(1, calls)
            assertFalse(popup.isShowing)
            popup = f.open()
            choose(popup, 0)
            frames()
            assertEquals(1, calls)
            assertEquals("0", f.stored())
        }
    }

    @Test fun rebindOrAnchorDetachCancelsPendingSelectionWithoutALeakedWindow() {
        Fixture().use { f ->
            var calls = 0
            f.preference.setOnPreferenceChangeListener { _, _ -> calls++; true }
            var popup = f.open()
            choose(popup, 1)
            f.bind()
            assertFalse(popup.isShowing)
            assertFalse(popup.contentView.isAttachedToWindow)
            frames()
            assertEquals(0, calls)
            assertEquals("0", f.stored())
            popup = f.open()
            choose(popup, 2)
            f.host.removeView(f.row)
            frames()
            assertFalse(popup.isShowing)
            assertFalse(popup.contentView.isAttachedToWindow)
            assertEquals(0, calls)
            assertEquals("0", f.stored())
        }
    }

    @Test fun dynamicValuesCannotCommitAnIndexFromTheOldOpenMenu() {
        Fixture().use { f ->
            var calls = 0
            f.preference.setOnPreferenceChangeListener { _, _ -> calls++; true }
            val popup = f.open()
            choose(popup, 1)
            f.preference.entryValues = arrayOf("0", "3", "2", "1")
            frames()
            assertEquals(0, calls)
            assertEquals("0", f.stored())
            assertFalse(popup.isShowing)
        }
    }

    @Test fun recreationTriggeredByChoiceCannotRetainTheOldPopupOrRestoreItsListener() {
        Fixture().use { f ->
            val oldActivity = f.activity
            val popup = f.open()
            val surface = popup.contentView
            var calls = 0
            f.preference.setOnPreferenceChangeListener { preference, _ ->
                assertFalse(popup.isShowing)
                assertFalse(surface.isAttachedToWindow)
                calls++
                // Equivalent lifetime boundary to AppCompat day/night apply, without global theme state.
                preference.onDetached()
                val configuration = Configuration(oldActivity.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        Configuration.UI_MODE_NIGHT_YES
                }
                f.controller.configurationChange(configuration)
                true
            }
            choose(popup, 1)
            assertEquals(0, calls)
            frames()
            assertEquals(1, calls)
            assertTrue(oldActivity.isDestroyed)
            assertNotSame(oldActivity, f.controller.get())
            assertFalse(surface.isAttachedToWindow)
            assertNull(f.spinner.onItemSelectedListener)
            assertNull(ReflectionHelpers.getField<Any?>(f.preference, "boundSpinner"))
            assertNull(ReflectionHelpers.getField<Any?>(f.preference, "choicePopup"))
            assertEquals("1", f.stored())
        }
    }

    @Test fun externalRecreationDuringExitCancelsRatherThanCommittingTheOldChoice() {
        Fixture().use { f ->
            val popup = f.open()
            var calls = 0
            f.preference.setOnPreferenceChangeListener { _, _ -> calls++; true }
            choose(popup, 1)
            // A real configuration recreation interrupts the in-flight exit. Controller.recreate()
            // has an unrelated legacy window-focus limitation with an already open popup.
            val configuration = Configuration(f.activity.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    Configuration.UI_MODE_NIGHT_YES
            }
            f.controller.configurationChange(configuration)
            frames()
            assertFalse(popup.isShowing)
            assertFalse(popup.contentView.isAttachedToWindow)
            assertEquals(0, calls)
            assertEquals("0", f.stored())
        }
    }

    @Test fun longPressRemainsIndependentAndThePopupKeepsRtlBoundsAndSelectedCard() {
        Fixture(longClick = true).use { f ->
            var longCalls = 0
            (f.preference as LongClickMenuPreference).setOnLongClickListener {
                longCalls++
                true
            }
            assertTrue(f.row.performLongClick())
            assertEquals(1, longCalls)
            assertNull(ReflectionHelpers.getField<Any?>(f.preference, "choicePopup"))
            f.row.layoutDirection = View.LAYOUT_DIRECTION_RTL
            f.spinner.layoutDirection = View.LAYOUT_DIRECTION_RTL
            f.preference.value = "2"
            val popup = f.open()
            val list = popup.listView
            assertEquals(View.LAYOUT_DIRECTION_RTL, popup.contentView.layoutDirection)
            assertEquals(View.LAYOUT_DIRECTION_RTL, list.layoutDirection)
            assertTrue(popup.width <= f.activity.resources.displayMetrics.widthPixels)
            val bounds = Rect().also(f.spinner::getWindowVisibleDisplayFrame)
            assertTrue(popup.height <= bounds.height())
            val selected = list.adapter.getView(2, null, list)
            val label = selected.findViewById<TextView>(R.id.choice_menu_label)
            assertTrue(selected.isSelected)
            assertEquals(Typeface.BOLD, shadowOf(label.typeface).fontDescription.style)
            assertEquals(f.activity.resources.getDimensionPixelSize(R.dimen.card_stroke_width_selected),
                shadowOf(label.background as GradientDrawable).strokeWidth)
            popup.dismiss()
            frames()
            assertFalse(popup.isShowing)
            assertEquals(1, longCalls)
        }
    }
}
