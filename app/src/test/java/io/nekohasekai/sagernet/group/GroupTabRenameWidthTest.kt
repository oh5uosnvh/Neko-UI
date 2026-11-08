package io.nekohasekai.sagernet.group

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.GroupPillTabLayout
import io.nekohasekai.sagernet.widget.GroupTabPill
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupTabRenameWidthTest {
    private fun layout(v: View, width: Int = 360, height: Int = 80) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, width, height)
    }
    private fun tabs(): GroupPillTabLayout {
        val ctx = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SagerNet)
        val page = android.view.LayoutInflater.from(ctx).inflate(R.layout.layout_group_list, null)
        return page.findViewById<GroupPillTabLayout>(R.id.group_tab).apply {
            visibility = View.VISIBLE
            (parent as android.view.ViewGroup).removeView(this)
        }
    }
    @Test fun renameLongToShortShrinksBothPillAndTabWithoutRemovingOrRecreatingTab() {
        val tabs = tabs()
        val tab = tabs.newTab()
        GroupTabPill.bindTab(tab, "虎云-费费")
        tabs.addTab(tab)
        val other = tabs.newTab()
        GroupTabPill.bindTab(other, "轮询 (1)")
        tabs.addTab(other)
        layout(tabs)
        val pill = tab.customView as GroupTabPill
        val longWidth = pill.measuredWidth
        val longTabWidth = tab.view.measuredWidth
        GroupTabPill.bindTab(tab, "虎")
        assertTrue(tabs.isLayoutRequested)
        layout(tabs)
        assertSame(pill, tab.customView)
        assertEquals(2, tabs.tabCount)
        assertTrue("pill ${pill.width} must shrink from $longWidth", pill.width < longWidth)
        assertTrue("tab ${tab.view.width} must shrink from $longTabWidth", tab.view.width < longTabWidth)
        assertEquals(pill.width + tab.view.paddingLeft + tab.view.paddingRight, tab.view.width)
        GroupTabPill.bindTab(tab, "虎云-费费")
        layout(tabs)
        assertEquals(longWidth, pill.width)
        assertTrue(tab.isSelected)
    }
    @Test fun nameCapIsSevenMoGlyphWidthsIncludingEllipsisNotSevenCharacters() {
        val tabs = tabs()
        val tab = tabs.newTab()
        GroupTabPill.bindTab(tab, "模".repeat(40))
        tabs.addTab(tab)
        layout(tabs)
        val pill = tab.customView as GroupTabPill
        val budget = pill.labelWidthLimit()
        assertEquals(kotlin.math.ceil(pill.paint.measureText("模".repeat(7)).toDouble()).toInt(), budget)
        assertTrue(pill.width <= budget + pill.compoundPaddingLeft + pill.compoundPaddingRight)
        assertTrue(pill.layout.getEllipsisCount(0) > 0)
        GroupTabPill.bindTab(tab, "1".repeat(60))
        layout(tabs)
        val shown = pill.layout.getEllipsisStart(0)
        assertTrue("narrow digits shown=$shown", shown > 7)
        assertTrue(pill.layout.getEllipsisCount(0) > 0)
        assertTrue(pill.width <= budget + pill.compoundPaddingLeft + pill.compoundPaddingRight)
    }
    @Test fun changingFontSizeRecomputesGlyphWidthCap() {
        val tabs = tabs()
        val tab = tabs.newTab()
        GroupTabPill.bindTab(tab, "模".repeat(30))
        tabs.addTab(tab)
        layout(tabs)
        val pill = tab.customView as GroupTabPill
        val before = pill.labelWidthLimit()
        pill.textSize = 22f
        layout(tabs)
        assertTrue(pill.labelWidthLimit() > before)
        assertEquals(pill.labelWidthLimit() + pill.compoundPaddingLeft + pill.compoundPaddingRight, pill.maxWidth)
    }
}
