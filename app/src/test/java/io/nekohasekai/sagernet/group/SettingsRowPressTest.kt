package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.clearSettingsRowPress
import io.nekohasekai.sagernet.ui.withoutSettingsRowPress
import io.nekohasekai.sagernet.widget.ReferenceSwitch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsRowPressTest {
    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    @Test fun realConnectedOnlyXmlRowKeepsQuietBackgroundAfterRecyclerRebindInBothThemes() {
        for (night in listOf(false, true)) {
            val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
            val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet) }
            controller.setup().visible()
            try {
                val config = Configuration(activity.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = android.view.ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_SagerNet)
                val manager = PreferenceManager(context)
                val source = manager.inflateFromResource(context, R.xml.group_preferences, null)
                val target = source.findPreference<SwitchPreference>("subscriptionUpdateWhenConnectedOnly")!!
                target.isPersistent = false
                target.isChecked = false
                target.parent!!.removePreference(target)
                val screen = manager.createPreferenceScreen(context).apply { addPreference(target) }
                manager.setPreferences(screen)
                val list = RecyclerView(context).withoutSettingsRowPress()
                list.layoutManager = LinearLayoutManager(context)
                val adapter = PreferenceGroupAdapter(screen)
                list.adapter = adapter
                activity.setContentView(list)
                measure(list, 600, 200)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
                measure(list, 600, 200)
                list.viewTreeObserver.dispatchOnPreDraw()
                val holder = list.findViewHolderForAdapterPosition(0)!!
                val row = holder.itemView
                assertTrue(row.findViewById<View>(android.R.id.switch_widget) is ReferenceSwitch)
                assertTrue(row.isClickable)
                val expected = row.background?.state?.filter { it != android.R.attr.state_pressed }
                repeat(3) {
                    row.isPressed = true
                    list.viewTreeObserver.dispatchOnPreDraw()
                    assertFalse("night=$night row press must never reach drawable", row.background?.state?.contains(android.R.attr.state_pressed) == true)
                    row.isPressed = false
                    assertTrue(row.performClick())
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                    measure(list, 600, 200)
                    list.viewTreeObserver.dispatchOnPreDraw()
                    val current = list.findViewHolderForAdapterPosition(0)!!.itemView
                    assertFalse(current.background?.state?.contains(android.R.attr.state_pressed) == true)
                }
                assertTrue("cleaning visual feedback cannot disable preference persistence semantics", target.isChecked)
                assertNotNull(expected)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun filteringPressPreservesEnabledCheckedSelectedFocusAndInputBackground() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val normal = GradientDrawable().apply { setColor(Color.DKGRAY) }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.RED))
            addState(intArrayOf(android.R.attr.state_checked), ColorDrawable(Color.GREEN))
            addState(intArrayOf(), normal)
        }
        val row = View(context).apply { background = states; setPadding(11, 12, 13, 14) }
        val inputBackground = StateListDrawable().apply { addState(intArrayOf(), ColorDrawable(Color.BLUE)) }
        val input = EditText(context).apply { background = inputBackground }
        root.addView(row)
        root.addView(input)
        clearSettingsRowPress(root)
        assertSame(inputBackground, input.background)
        assertEquals(listOf(11, 12, 13, 14), listOf(row.paddingLeft, row.paddingTop, row.paddingRight, row.paddingBottom))
        row.background.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked,
            android.R.attr.state_selected, android.R.attr.state_focused, android.R.attr.state_pressed)
        assertFalse(row.background.state.contains(android.R.attr.state_pressed))
        for (state in listOf(android.R.attr.state_enabled, android.R.attr.state_checked,
            android.R.attr.state_selected, android.R.attr.state_focused)) assertTrue(row.background.state.contains(state))
        assertEquals(Color.GREEN, (states.current as ColorDrawable).color)
        val once = row.background
        clearSettingsRowPress(root)
        assertSame("draw-time cleanup must not allocate a wrapper every frame", once, row.background)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nightAndDayRipplePixelsStayIdenticalWhileHeldIncludingNestedPencil() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        for (surface in listOf(Color.WHITE, Color.rgb(18, 19, 21))) {
            val normal = GradientDrawable().apply { setColor(surface) }
            val ripple = RippleDrawable(ColorStateList.valueOf(Color.WHITE), normal,
                ColorDrawable(Color.WHITE))
            val root = FrameLayout(context).apply { background = ripple }
            root.addView(View(context).apply {
                background = RippleDrawable(ColorStateList.valueOf(Color.LTGRAY), null,
                    ColorDrawable(Color.WHITE))
                isClickable = true
            }, FrameLayout.LayoutParams(48, 48))
            measure(root, 180, 70)
            clearSettingsRowPress(root)
            fun pixels(): IntArray {
                val bitmap = Bitmap.createBitmap(180, 70, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                return IntArray(180 * 70).also {
                    bitmap.getPixels(it, 0, 180, 0, 0, 180, 70)
                    bitmap.recycle()
                }
            }
            val before = pixels()
            root.isPressed = true
            root.getChildAt(0).isPressed = true
            root.drawableHotspotChanged(120f, 35f)
            root.getChildAt(0).drawableHotspotChanged(24f, 24f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            clearSettingsRowPress(root)
            assertArrayEquals("day/night pressed ripple must not add noise pixels", before, pixels())
            assertEquals(surface, before[60 * 180 + 150])
        }
    }
}
