package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.CompactNavigationMenu
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MainNavigationUiTest {
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
    private class Fixture : AutoCloseable {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet_Blue) }
        val host = FrameLayout(activity)
        val anchor = View(activity)
        var selected: (Int) -> Unit = {}
        val menu: CompactNavigationMenu
        init {
            controller.setup()
            activity.setContentView(host)
            host.addView(anchor, FrameLayout.LayoutParams(48, 48))
            controller.visible()
            menu = CompactNavigationMenu(activity, host) { selected(it.itemId) }
            host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 320, 640)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertTrue("Anchor must attach before testing a real PopupWindow", anchor.isAttachedToWindow)
        }
        fun open(): PopupWindow {
            menu.show(anchor)
            val popup = menu.window!!
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            popup.contentView.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(popup.height, View.MeasureSpec.EXACTLY))
            popup.contentView.layout(0, 0, popup.width, popup.height)
            assertTrue(popup.contentView.isAttachedToWindow)
            return popup
        }
        override fun close() { menu.cancel(); controller.pause().stop().destroy() }
    }
    private val allIds = listOf(R.id.nav_configuration, R.id.nav_group, R.id.nav_route,
        R.id.nav_settings, R.id.nav_logcat, R.id.nav_traffic, R.id.nav_tools, R.id.nav_faq, R.id.nav_about)

    @Test fun actualWindowFitsRowsAndIncludesEveryXmlEntryWithConditionalTraffic() {
        Fixture().use { f ->
            f.menu.setSelected(R.id.nav_route)
            val popup = f.open()
            val content = popup.contentView as ScrollView
            val rows = content.getChildAt(0) as LinearLayout
            assertEquals(allIds, (0 until rows.childCount).map { rows.getChildAt(it).id })
            assertEquals(Color.TRANSPARENT, (popup.background as ColorDrawable).color)
            assertEquals(Color.WHITE, (content.background as GradientDrawable).color!!.defaultColor)
            assertEquals(Color.WHITE, f.menu.surfaceColor)
            val surface = content.background as GradientDrawable
            assertEquals(f.activity.resources.getDimensionPixelSize(R.dimen.card_stroke_width),
                shadowOf(surface).strokeWidth)
            assertEquals(f.activity.getColor(R.color.card_stroke), shadowOf(surface).strokeColor)
            assertEquals(16f, surface.cornerRadius, 0f)
            assertTrue(content.clipToOutline)
            assertTrue(popup.width in 1..304)
            val frame = Rect().also { f.anchor.getWindowVisibleDisplayFrame(it) }
            assertTrue(popup.height in 1..(frame.height() - 16))
            assertTrue("Compact menu must not occupy the full width", popup.width < f.host.width)
            assertEquals(0, popup.animationStyle)
            assertNull(popup.exitTransition)
            assertTrue(popup.isFocusable)
            for (id in allIds) {
                val row = content.findViewById<LinearLayout>(id)
                assertNotNull((row.getChildAt(0) as ImageView).drawable)
                assertEquals(f.menu.menu.findItem(id).title, (row.getChildAt(1) as TextView).text)
                assertEquals(id == R.id.nav_route, row.isSelected)
            }
            f.menu.menu.findItem(R.id.nav_traffic).isVisible = false
            f.menu.refresh()
            assertFalse(popup.isShowing)
            assertNull(f.menu.window!!.contentView.findViewById<View>(R.id.nav_traffic))
            f.menu.menu.findItem(R.id.nav_traffic).isVisible = true
            f.menu.refresh()
            assertNotNull(f.menu.window!!.contentView.findViewById<View>(R.id.nav_traffic))
        }
    }

    @Test fun allSelectionsWaitForWholeSurfaceExitAndDeliverExactlyOnceAfterDetach() {
        Fixture().use { f ->
            for (id in allIds) {
                val popup = f.open()
                frames()
                var calls = 0
                f.selected = { chosen ->
                    assertEquals(id, chosen)
                    assertFalse(popup.isShowing)
                    assertFalse(popup.contentView.isAttachedToWindow)
                    calls++
                }
                popup.contentView.findViewById<View>(id).performClick()
                assertEquals(0, calls)
                assertTrue(popup.isShowing)
                frames(3)
                assertTrue(popup.contentView.alpha in .001f.. .999f)
                assertEquals(0, calls)
                popup.contentView.findViewById<View>(id).performClick()
                frames()
                assertEquals(1, calls)
                assertNull(f.menu.window)
            }
        }
    }

    @Test fun stopSaveExternalDismissAndDisabledItemsNeverCommitPendingNavigation() {
        Fixture().use { f ->
            var calls = 0
            f.selected = { calls++ }
            var popup = f.open(); frames()
            popup.contentView.findViewById<View>(R.id.nav_group).performClick()
            f.menu.cancel(); frames()
            assertEquals(0, calls)
            assertEquals(1f, popup.contentView.alpha, 0f)
            popup = f.open(); frames()
            popup.contentView.findViewById<View>(R.id.nav_group).performClick()
            popup.dismiss(); frames()
            assertEquals(0, calls)
            f.menu.menu.findItem(R.id.nav_tools).isEnabled = false
            popup = f.open(); frames()
            popup.contentView.findViewById<View>(R.id.nav_tools).performClick()
            frames()
            assertTrue(popup.isShowing)
            assertEquals(0, calls)
        }
    }

    @Test fun keyboardCancelFromFocusedRowKeepsNavigationUncommitted() {
        Fixture().use { f ->
            val popup = f.open(); frames()
            var calls = 0
            f.selected = { calls++ }
            val row = popup.contentView.findViewById<View>(R.id.nav_group)
            row.isFocusableInTouchMode = true
            assertTrue(row.requestFocus())
            assertTrue(popup.contentView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)))
            assertTrue(popup.contentView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE)))
            assertTrue(popup.isShowing)
            frames()
            assertFalse(popup.isShowing)
            assertEquals(0, calls)
        }
    }

    @Test fun doubleFontScaleLongTitlesRemainScrollableInsideWindowBounds() {
        Fixture().use { f ->
            f.menu.cancel()
            val configured = f.activity.createConfigurationContext(Configuration(f.activity.resources.configuration).apply {
                fontScale = 2f
            })
            val themed = androidx.appcompat.view.ContextThemeWrapper(configured, R.style.Theme_SagerNet_Blue)
            val menu = CompactNavigationMenu(themed, f.host) {}
            try {
                menu.menu.findItem(R.id.nav_group).title =
                    "A long translated group destination\nSecond line at double font scale\nThird line must never be clipped"
                menu.show(f.anchor)
                val popup = menu.window!!
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                val content = popup.contentView
                assertTrue(content.isAttachedToWindow)
                content.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(popup.height, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, popup.width, popup.height)
                val row = content.findViewById<LinearLayout>(R.id.nav_group)
                val text = row.getChildAt(1) as TextView
                assertEquals(menu.menu.findItem(R.id.nav_group).title, row.contentDescription)
                assertTrue(text.textSize >= 29f)
                assertTrue(text.lineCount > 1)
                for (line in 0 until text.lineCount) assertEquals(0, text.layout.getEllipsisCount(line))
                assertTrue(text.right <= row.width - row.paddingRight)
                assertTrue(popup.width <= 304)
                val frame = Rect().also { f.anchor.getWindowVisibleDisplayFrame(it) }
                assertTrue(popup.height <= frame.height() - 16)
                assertTrue((content as ScrollView).getChildAt(0).measuredHeight >= content.measuredHeight)
            } finally { menu.cancel() }
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-night-mdpi")
    fun nightUsesExactSurfaceAndRtlRetainsEveryEntry() {
        Fixture().use { f ->
            f.anchor.layoutDirection = View.LAYOUT_DIRECTION_RTL
            val popup = f.open()
            assertEquals(Color.rgb(37, 38, 40), f.menu.surfaceColor)
            assertEquals(f.menu.surfaceColor, (popup.contentView.background as GradientDrawable).color!!.defaultColor)
            val surface = popup.contentView.background as GradientDrawable
            assertEquals(f.activity.resources.getDimensionPixelSize(R.dimen.card_stroke_width),
                shadowOf(surface).strokeWidth)
            assertEquals(f.activity.getColor(R.color.card_stroke), shadowOf(surface).strokeColor)
            assertEquals(16f, surface.cornerRadius, 0f)
            assertEquals(View.LAYOUT_DIRECTION_RTL, popup.contentView.layoutDirection)
            for (id in allIds) assertNotNull(popup.contentView.findViewById<View>(id))
        }
    }
}
