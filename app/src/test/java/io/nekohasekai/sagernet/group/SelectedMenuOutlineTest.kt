package io.nekohasekai.sagernet.group

import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.CompactNavigationMenu
import io.nekohasekai.sagernet.widget.ThemedChoiceStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SelectedMenuOutlineTest {
    private fun fixture(block: (AppCompatActivity, FrameLayout) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet_Blue) }
        controller.setup()
        val host = FrameLayout(activity)
        activity.setContentView(host)
        try { block(activity, host) }
        finally { controller.pause().stop().destroy() }
    }
    private fun assertOutline(activity: AppCompatActivity, drawable: GradientDrawable) {
        val color = TypedValue().also { activity.theme.resolveAttribute(R.attr.colorPrimary, it, true) }.data
        assertEquals(activity.resources.getDimensionPixelSize(R.dimen.card_stroke_width_selected),
            shadowOf(drawable).strokeWidth)
        assertEquals(color, shadowOf(drawable).strokeColor)
        assertTrue(drawable.cornerRadius > 0f)
    }

    @Test fun selectedChoicesHaveThemeStrokeAndUnselectedChoicesDoNot() = fixture { activity, _ ->
        assertOutline(activity, ThemedChoiceStyle.itemCard(activity, true))
        assertEquals(0, shadowOf(ThemedChoiceStyle.itemCard(activity, false)).strokeWidth)
    }

    @Test fun actualNavigationRowsKeepOutlineWhilePressedAndFocused() = fixture { activity, host ->
        val menu = CompactNavigationMenu(activity, host) {}
        menu.setSelected(R.id.nav_configuration)
        val content = ReflectionHelpers.callInstanceMethod<View>(menu, "buildContent")
        val selected = content.findViewById<View>(R.id.nav_configuration)
        assertTrue(selected.isSelected)
        assertOutline(activity, selected.background.current as GradientDrawable)
        selected.isPressed = true
        assertOutline(activity, selected.background.current as GradientDrawable)
        selected.isPressed = false
        selected.requestFocus()
        assertOutline(activity, selected.background.current as GradientDrawable)
        val other = content.findViewById<View>(R.id.nav_group)
        assertFalse(other.isSelected)
        assertEquals(0, shadowOf(other.background.current as GradientDrawable).strokeWidth)
    }

    @Test @Config(qualifiers = "night")
    fun selectedChoiceAlsoUsesNightThemeAccent() = fixture { activity, _ ->
        assertOutline(activity, ThemedChoiceStyle.itemCard(activity, true))
    }
}
