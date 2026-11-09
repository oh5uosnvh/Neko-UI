package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.text.Layout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.GroupTabPill
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GroupTabRenameRegressionTest {
    private fun fixture(night: Boolean = false): Pair<View, TabLayout> {
        val app = RuntimeEnvironment.getApplication()
        val cfg = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val ctx = ContextThemeWrapper(app.createConfigurationContext(cfg), R.style.Theme_SagerNet)
        val page = LayoutInflater.from(ctx).inflate(R.layout.layout_group_list, null)
        val tabs = page.findViewById<TabLayout>(R.id.group_tab)
        repeat(16) { tabs.addTab(tabs.newTab().also { tab -> GroupTabPill.bindTab(tab, "分组${it + 1}") }) }
        return page to tabs
    }
    private fun layout(page: View) {
        page.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        page.layout(0, 0, 420, 700)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }
    @Test fun renameShrinksExistingFrameAndReexpandsWithoutRecreatingOrRestarting() {
        for (night in listOf(false, true)) {
            val (page, tabs) = fixture(night)
            val tab = tabs.getTabAt(8)!!
            GroupTabPill.bindTab(tab, "虎云-费费")
            tabs.selectTab(tab)
            layout(page)
            tabs.scrollTo(tabs.getTabAt(7)!!.view.left, 0)
            val beforeScroll = tabs.scrollX
            val pill = tab.customView as GroupTabPill
            val widthBefore = pill.width
            val parentBefore = tab.view.width
            GroupTabPill.bindTab(tab, "虎")
            assertTrue(pill.isLayoutRequested)
            assertTrue(tab.view.isLayoutRequested)
            layout(page)
            assertSame("rename must not rebuild selected tab", tab, tabs.getTabAt(8))
            assertSame("rename reuses the existing pill", pill, tab.customView)
            assertEquals(8, tabs.selectedTabPosition)
            assertTrue("short label must shrink its outline", pill.width < widthBefore)
            assertTrue("parent tab must also shrink", tab.view.width < parentBefore)
            assertEquals("no auto-scroll back to first", beforeScroll, tabs.scrollX)
            val expected = ceil(Layout.getDesiredWidth("虎", pill.paint).toDouble()).toInt() +
                pill.compoundPaddingLeft + pill.compoundPaddingRight
            assertEquals(expected, pill.width)
            GroupTabPill.bindTab(tab, "虎云-费费")
            layout(page)
            assertEquals(widthBefore, pill.width)
            assertEquals(parentBefore, tab.view.width)
        }
    }
    @Test fun capIsSevenMoGlyphWidthsWithEllipsisInsideAndNarrowDigitsNotCharacterLimited() {
        val (page, tabs) = fixture()
        val tab = tabs.getTabAt(0)!!
        GroupTabPill.bindTab(tab, "模".repeat(40))
        layout(page)
        val pill = tab.customView as GroupTabPill
        val cap = ceil(Layout.getDesiredWidth("模".repeat(7), pill.paint).toDouble()).toInt()
        val padding = pill.compoundPaddingLeft + pill.compoundPaddingRight
        assertEquals(cap, pill.labelWidthLimit())
        assertEquals(cap + padding, pill.width)
        assertTrue("overflow must use actual end ellipsis", pill.layout.getEllipsisCount(0) > 0)
        assertEquals("模".repeat(40), pill.text.toString())
        GroupTabPill.bindTab(tab, "1".repeat(80))
        layout(page)
        val visible = pill.layout.getEllipsisStart(0)
        assertTrue("more than seven narrow digits must fit", visible > 7)
        assertTrue(pill.layout.getEllipsisCount(0) > 0)
        assertEquals(cap + padding, pill.width)
        // Same view, larger actual TextPaint: cap must grow with real font metrics.
        val before = pill.labelWidthLimit()
        pill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pill.textSize * 1.3f)
        layout(page)
        assertTrue(pill.labelWidthLimit() > before)
        assertEquals(pill.labelWidthLimit() + padding, pill.maxWidth)
    }
}
