package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.GroupListEdgeLayout
import io.nekohasekai.sagernet.widget.TitleBarLayout
import io.nekohasekai.sagernet.widget.GroupTabPill
import io.nekohasekai.sagernet.widget.ProfileNameTextView
import io.nekohasekai.sagernet.widget.TwoColumnNameWrap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real narrow-card native text shaping, not a character-count approximation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupFloatingHeaderTest {
    private fun context(night: Boolean = false, theme: Int = R.style.Theme_SagerNet): ContextThemeWrapper {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(app.createConfigurationContext(config), theme)
    }
    private fun measure(view: View, width: Int, height: Int = 640) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun color(context: android.content.Context, attr: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attr))
        return try { values.getColor(0, Color.MAGENTA) } finally { values.recycle() }
    }

    @Test fun narrowNativeCardsFillTwoLinesWithoutMovingTheWholeLatinToken() {
        val context = context()
        val grid = RecyclerView(context).apply { layoutManager = GridLayoutManager(context, 2) }
        val raw = listOf("美国03TX-LB4", "美国03TX-LB4NATIVE123456789ABCDEFGH",
            "🇺🇸美国03TX-LB4NODE123456789ABCDEFGH", "美国 ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        for (name in raw) {
            val card = LayoutInflater.from(context).inflate(R.layout.layout_profile, grid, false)
            val text = card.findViewById<ProfileNameTextView>(R.id.profile_name)
            card.findViewById<View>(R.id.edit).visibility = View.GONE
            card.findViewById<View>(R.id.share).visibility = View.GONE
            card.findViewById<View>(R.id.double_column_menu).visibility = View.VISIBLE
            text.text = name
            text.twoColumnDisplay = true
            val originalFont = text.textSize
            measure(card, 176, 180)
            val layout = text.layout
            assertNotNull(layout)
            assertTrue(layout.lineCount in 1..2)
            val first = layout.text.subSequence(0, layout.getLineEnd(0)).toString().replace("\u200B", "").trim()
            assertTrue("First line still strands country: $first", first.any { it in '0'..'9' || it in 'A'..'Z' })
            assertEquals("Raw TextView/entity-facing name must not change", name, text.text.toString())
            assertEquals(originalFont, text.textSize, 0f)
            assertEquals(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE,
                text.lineBreakWordStyle)
            if (layout.lineCount == 2) {
                assertTrue("First line should substantially use its real measured width: $first",
                    layout.getLineWidth(0) >= layout.width * 0.72f)
            }
        }
    }

    @Test fun displayBreaksPreserveEmojiFlagsCombiningZwjAndExplicitLines() {
        val raw = "🇺🇸👩🏽‍💻é#️⃣美国03TX-LB4\n🇭🇰香港A\u200DB\u2060C"
        val display = TwoColumnNameWrap.display(raw).toString()
        assertEquals(raw, display.replace("\u200B", ""))
        for (cluster in listOf("🇺🇸", "👩🏽‍💻", "é", "#️⃣", "A\u200DB", "B\u2060C")) {
            assertTrue("Unicode cluster split: $cluster", display.contains(cluster))
        }
        assertEquals(raw.count { it == '\n' }, display.count { it == '\n' })
        assertFalse(display.contains("\n\u200B"))
        val text = ProfileNameTextView(context()).apply { this.text = raw }
        assertNull(text.transformationMethod)
        text.twoColumnDisplay = true
        assertEquals(display, text.transformationMethod.getTransformation(text.text, text).toString())
        text.twoColumnDisplay = false
        assertEquals(raw, text.text.toString())
        assertNull(text.transformationMethod)
    }

    @Test fun pillsUsePrimaryAndThemeToneWithMutuallyExclusiveBlackWhiteText() {
        for (night in listOf(false, true)) for (theme in listOf(R.style.Theme_SagerNet,
            R.style.Theme_SagerNet_Amber, R.style.Theme_SagerNet_Blue)) {
            val context = context(night, theme)
            val tabs = TabLayout(context)
            val first = tabs.newTab()
            val second = tabs.newTab()
            GroupTabPill.bindTab(first, "Bitz Net")
            GroupTabPill.bindTab(second, "Other")
            tabs.addTab(first)
            tabs.addTab(second)
            val pill = first.customView as GroupTabPill
            assertTrue(pill.isSelected)
            assertEquals(if (night) Color.WHITE else Color.BLACK, pill.currentTextColor)
            assertEquals(color(context, R.attr.colorMaterial300),
                (pill.background as GroupTabPill.ProgressSurface).selected)
            assertEquals(1f,pill.selectionProgress,0f)
            val other = second.customView as GroupTabPill
            assertEquals(color(context, R.attr.colorLayerLow),
                (other.background as GroupTabPill.ProgressSurface).low)
            assertEquals(0f,other.selectionProgress,0f)
            assertEquals(if (night) Color.WHITE else Color.BLACK, other.currentTextColor)
            assertFalse(pill.isClickable)
            assertFalse(pill.isFocusable)
            assertTrue(pill.textSize >= 15f)
            second.select()
            assertFalse(pill.isSelected)
            assertTrue(other.isSelected)
            assertEquals(color(context, R.attr.colorMaterial300),
                (other.background as GroupTabPill.ProgressSurface).selected)
            assertEquals(1f,other.selectionProgress,0f)
        }
    }

    @Test fun foregroundSharesToolbarBaselineAndOnlyTopPaddingTracksActualTabs() {
        val context = context()
        val root = LayoutInflater.from(context).inflate(R.layout.layout_group_list,
            FrameLayout(context), false)
        val edge = root.findViewById<GroupListEdgeLayout>(R.id.group_list_foreground)
        val tabs = root.findViewById<TabLayout>(R.id.group_tab)
        val pager = root.findViewById<View>(R.id.group_pager)
        val list = LayoutInflater.from(context).inflate(R.layout.layout_profile_list,
            FrameLayout(context), false) as RecyclerView
        list.setPadding(4, 4, 4, 131)
        edge.bindList(list)
        measure(root, 360)
        assertEquals(0, pager.top)
        val titleGap = (root as TitleBarLayout).titleEdgeBandHeight().toInt()
        assertEquals(titleGap + 4, list.paddingTop)
        assertFalse(list.clipToPadding)
        assertEquals(131, list.paddingBottom)
        tabs.visibility = View.VISIBLE
        val tab = tabs.newTab()
        GroupTabPill.bindTab(tab, "Bitz Net")
        tabs.addTab(tab)
        measure(root, 360)
        assertEquals(titleGap + tabs.height + 4, list.paddingTop)
        assertEquals(0, tabs.top)
        assertEquals(0, pager.top)
        assertEquals((root as TitleBarLayout).titleGlyphBottom.toInt(), edge.top)
        assertEquals(131, list.paddingBottom)
        tabs.visibility = View.GONE
        measure(root, 360)
        assertEquals(titleGap + 4, list.paddingTop)
        assertEquals(131, list.paddingBottom)
    }
}
