package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.GroupListEdgeLayout
import io.nekohasekai.sagernet.widget.GroupTabPill
import io.nekohasekai.sagernet.widget.TitleBarEdgeRenderer
import io.nekohasekai.sagernet.widget.TitleBarLayout
import io.nekohasekai.sagernet.widget.TitleGlyphMetrics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

/** Real inflated hierarchy + real ViewPager2/page/list clipping, not source contracts.
 * The custom sibling title and GONE menu Toolbar reproduce TopBarController.attach(). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigurationNodeEdgeTest {
    private fun context(night: Boolean = false, fontScale: Float = 1f): Context {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply {
            this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_SagerNet)
    }

    private fun measure(view: View, height: Int = 640) {
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 360, height)
    }

    private data class Fixture(val root: TitleBarLayout, val edge: GroupListEdgeLayout,
        val pager: ViewPager2, val header: AppBarLayout, val bar: Toolbar,
        val row: LinearLayout, val title: TextView, val tabs: TabLayout,
        val lists: MutableList<RecyclerView>)

    private class PageHolder(val list: RecyclerView, page: View) : RecyclerView.ViewHolder(page)

    private fun fixture(night: Boolean = false, scale: Float = 1f): Fixture {
        val c = context(night, scale)
        val root = LayoutInflater.from(c).inflate(R.layout.layout_group_list,
            FrameLayout(c), false) as TitleBarLayout
        root.setPadding(0, 24, 0, 31)
        val edge = root.findViewById<GroupListEdgeLayout>(R.id.group_list_foreground)
        val pager = root.findViewById<ViewPager2>(R.id.group_pager)
        val header = root.findViewById<AppBarLayout>(R.id.appbar)
        val bar = root.findViewById<Toolbar>(R.id.toolbar)
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(c).apply {
            id = R.id.title_bar_title
            text = "Neko"
            textSize = 20f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            setTextColor(Color.WHITE)
        }
        row.addView(title, LinearLayout.LayoutParams(-2, -1))
        // TopBarController installs its row into the Toolbar's FrameLayout parent.
        (bar.parent as ViewGroup).addView(row, FrameLayout.LayoutParams(-1,
            bar.parent.let { (it as View).layoutParams.height }))
        bar.title = ""
        bar.visibility = View.GONE
        val tabs = root.findViewById<TabLayout>(R.id.group_tab).apply {
            visibility = View.VISIBLE
            repeat(3) { n -> addTab(newTab().also { GroupTabPill.bindTab(it, "Group $n") }) }
        }
        val lists = mutableListOf<RecyclerView>()
        pager.adapter = object : RecyclerView.Adapter<PageHolder>() {
            override fun getItemCount() = 2
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
                // FragmentStateAdapter's full-size FrameLayout page, containing the
                // actual ConfigurationFragment.GroupFragment list root.
                val page = FrameLayout(c).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }
                val list = LayoutInflater.from(c).inflate(R.layout.layout_profile_list,
                    page, false) as RecyclerView
                list.itemAnimator = null
                list.setPaddingRelative(4, 4, 4, 131)
                list.layoutManager = LinearLayoutManager(c)
                list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 40
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                        object : RecyclerView.ViewHolder(View(c).apply {
                            setBackgroundColor(Color.RED)
                            layoutParams = RecyclerView.LayoutParams(-1, 160)
                        }) {}
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                }
                page.addView(list, FrameLayout.LayoutParams(-1, -1))
                lists += list
                edge.bindList(list)
                return PageHolder(list, page)
            }
            override fun onBindViewHolder(holder: PageHolder, position: Int) = Unit
        }
        repeat(2) { measure(root) }
        assertTrue("Actual pager pages must have been created", lists.isNotEmpty())
        return Fixture(root, edge, pager, header, bar, row, title, tabs, lists)
    }

    private fun render(view: View): Bitmap = Bitmap.createBitmap(360, 640,
        Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    @Test fun hiddenNativeToolbarStillUsesVisibleCustomInkAndKeepsEveryPagerAncestorAboveTabs() {
        for (night in listOf(false, true)) for (scale in listOf(1f, 1.5f, 2f)) {
            val f = fixture(night, scale)
            val expected = TitleGlyphMetrics.bottom(f.title, f.root)!!
            assertEquals(View.GONE, f.bar.visibility)
            assertEquals(expected, f.root.titleGlyphBottom, 0f)
            assertEquals(ceil(expected).toInt(), f.edge.top)
            assertTrue("Clip origin must be above the group names", f.edge.top < f.header.bottom - f.tabs.height)
            assertEquals(0, f.pager.top)
            for (list in f.lists) {
                var ancestor = list.parent as View
                while (ancestor !== f.edge) {
                    assertEquals("${ancestor.javaClass.simpleName} clips at title, not tabs", 0, ancestor.top)
                    ancestor = ancestor.parent as View
                }
                assertFalse(list.clipToPadding)
                assertEquals(f.header.bottom + 4, f.edge.top + list.paddingTop)
                assertEquals(131, list.paddingBottom)
            }
            assertNull(f.header.background)
            assertNull(f.row.background)
            assertEquals(0f, f.header.elevation, 0f)
            assertEquals(609, f.edge.bottom)
        }
    }

    @Test fun actualScrolledCardsReachUnderGroupNamesAndFadeOnlyBetweenInkAndTabBottom() {
        for (night in listOf(false, true)) {
            val f = fixture(night)
            f.lists.forEach { it.scrollBy(0, f.header.bottom + 160) }
            // INVISIBLE preserves real tab geometry but exposes card pixels that
            // are otherwise covered by the crisp pill surfaces drawn last.
            f.tabs.visibility = View.INVISIBLE
            val pixels = render(f.root)
            val low = (f.root.background as ColorDrawable).color
            val ink = ceil(f.root.titleGlyphBottom).toInt()
            val middle = (f.root.titleGlyphBottom + f.root.titleEdgeBandHeight() * .55f).toInt()
            assertEquals("Nothing may leak above Neko ink", low, pixels.getPixel(300, ink - 1))
            assertNotEquals("Cards must draw before tab bottom, not be ancestor-clipped", low,
                pixels.getPixel(300, ink + 2))
            assertNotEquals(Color.RED, pixels.getPixel(300, ink + 2))
            assertNotEquals(low, pixels.getPixel(300, middle))
            assertNotEquals("Fade must still be active underneath names", Color.RED,
                pixels.getPixel(300, middle))
            assertEquals("Fade starts at the measured tabs bottom when scrolling up", Color.RED,
                pixels.getPixel(300, f.header.bottom))
            assertEquals(Color.RED, pixels.getPixel(300, f.header.bottom + 8))
            assertEquals(low, pixels.getPixel(300, 610)) // Current bottom/system inset.
            pixels.recycle()
        }
    }

    @Test fun configurationSoftwareMaskMatchesCanonicalGroupsRendererNotAnotherApproximation() {
        val f = fixture()
        f.lists.forEach { it.scrollBy(0, f.header.bottom + 160) }
        f.tabs.visibility = View.INVISIBLE
        val actual = render(f.root)
        val expected = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(expected)
        canvas.drawColor((f.root.background as ColorDrawable).color)
        val paint = Paint().apply { color = Color.RED }
        // The only configuration-specific difference is band geometry; the
        // Groups renderer's gradients/alpha composition are the reference.
        TitleBarEdgeRenderer().draw(canvas, 360, f.root.titleGlyphBottom, 609f,
            f.header.bottom.toFloat(), 1f, false, edgeBandLimit = f.root.titleEdgeBandHeight()) {
            it.drawRect(4f, f.edge.top.toFloat(), 356f, 609f, paint)
            false
        }
        for (y in ceil(f.root.titleGlyphBottom).toInt() until 609) {
            assertEquals("Groups reference mismatch at root y=$y",
                expected.getPixel(300, y), actual.getPixel(300, y))
        }
        expected.recycle()
        actual.recycle()
    }

    @Test fun tabsAndTitleStayCrispWhileStationaryFirstRowIsOutsideTheMask() {
        val f = fixture()
        val list = f.lists.first()
        val first = list.layoutManager!!.findViewByPosition(0)!!
        assertEquals(f.header.bottom + 4, f.edge.top + first.top)
        val stationary = render(f.root)
        assertEquals(Color.RED, stationary.getPixel(300, f.header.bottom + 8))
        f.lists.forEach { it.scrollBy(0, f.header.bottom + 160) }
        val scrolled = render(f.root)
        f.edge.visibility = View.INVISIBLE
        val headerOnly = render(f.root)
        val ink = ceil(f.root.titleGlyphBottom).toInt()
        for (y in 0 until ink) for (x in 0 until 360) {
            assertEquals("Header pixel must never enter card blur/fade",
                headerOnly.getPixel(x, y), scrolled.getPixel(x, y))
        }
        // Every pill's opaque interior remains in the header layer, above cards.
        val pill = f.tabs.getTabAt(0)!!.customView as GroupTabPill
        val rect = android.graphics.RectF()
        assertTrue(TitleGlyphMetrics.bounds(pill, f.root, rect))
        val x = rect.centerX().toInt()
        val y = (rect.top + 4).toInt()
        assertEquals(headerOnly.getPixel(x, y), scrolled.getPixel(x, y))
        stationary.recycle()
        scrolled.recycle()
        headerOnly.recycle()
    }

    @Test fun hidingResizingTabsAndSearchFallbackNeverDoublePadOrMoveTheTitleClipOrigin() {
        val f = fixture()
        val ink = f.root.titleGlyphBottom
        f.tabs.visibility = View.GONE
        repeat(3) { measure(f.root); f.root.refreshContentInsets() }
        assertEquals(ink, f.root.titleGlyphBottom, 0f)
        f.lists.forEach { assertEquals(f.header.bottom + 4, f.edge.top + it.paddingTop) }
        f.tabs.visibility = View.VISIBLE
        f.tabs.layoutParams.height = 82
        repeat(3) { measure(f.root); f.root.refreshContentInsets() }
        assertEquals(ink, f.root.titleGlyphBottom, 0f)
        f.root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        f.lists.forEach { it.setPaddingRelative(9, it.paddingTop, 13, 147) }
        repeat(4) { measure(f.root); f.root.refreshContentInsets() }
        f.lists.forEach {
            assertEquals(f.header.bottom + 4, f.edge.top + it.paddingTop)
            assertEquals(9, it.paddingStart)
            assertEquals(13, it.paddingEnd)
            assertEquals(147, it.paddingBottom)
        }
        // Search removes the marked title. Fallback is the custom top-bar row,
        // not AppBarLayout.bottom (which includes the group names).
        f.row.removeView(f.title)
        repeat(2) { measure(f.root) }
        val rowBounds = android.graphics.RectF()
        assertTrue(TitleGlyphMetrics.bounds(f.row, f.root, rowBounds))
        assertEquals(rowBounds.bottom, f.root.titleGlyphBottom, 0f)
        assertTrue(f.root.titleGlyphBottom < f.header.bottom)
        f.row.addView(f.title, LinearLayout.LayoutParams(-2, -1))
        repeat(2) { measure(f.root) }
        assertEquals(ink, f.root.titleGlyphBottom, 0f)
    }

    @Test fun transparentHeaderGapRoutesToCardsButPillActionsStillWin() {
        val f = fixture()
        var listDown = false
        val list = f.lists.first()
        list.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) listDown = true
            true
        }
        val y = (ceil(f.root.titleGlyphBottom) + 2f).coerceAtMost(f.tabs.top + f.header.top - 1f)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 300f, y, 0)
        try { assertTrue(f.root.dispatchTouchEvent(down)) } finally { down.recycle() }
        assertTrue("Transparent title-to-tabs strip must remain scrollable", listDown)
        val up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, 300f, y, 0)
        try { f.root.dispatchTouchEvent(up) } finally { up.recycle() }
        listDown = false
        var pillDown = false
        val tab = f.tabs.getTabAt(0)!!.view
        tab.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) pillDown = true
            true
        }
        val bounds = android.graphics.RectF()
        assertTrue(TitleGlyphMetrics.bounds(tab, f.root, bounds))
        val pillEvent = MotionEvent.obtain(0, 2, MotionEvent.ACTION_DOWN,
            bounds.centerX(), bounds.centerY(), 0)
        try { assertTrue(f.root.dispatchTouchEvent(pillEvent)) } finally { pillEvent.recycle() }
        assertTrue("Group-name action must retain header hit priority", pillDown)
        assertFalse("Pill touches must not be rerouted to underlying cards", listDown)
    }

    @Test fun groupsPageCanonicalGeometryAndDefaultBandStayUnchanged() {
        val c = context()
        val root = LayoutInflater.from(c).inflate(R.layout.layout_group, FrameLayout(c), false)
            as TitleBarLayout
        val bar = root.findViewById<Toolbar>(R.id.toolbar)
        repeat(2) { measure(root) }
        val title = TitleGlyphMetrics.titleView(bar)!!
        assertEquals(TitleGlyphMetrics.bottom(title, root)!!, root.titleGlyphBottom, 0f)
        val content = root.findViewById<View>(R.id.title_bar_content)
        val list = root.findViewById<RecyclerView>(R.id.group_list)
        val header = root.findViewById<View>(R.id.appbar)
        assertEquals(header.bottom + 4, content.top + list.paddingTop)
        content.setBackgroundColor(Color.RED)
        val bitmap = render(root)
        assertEquals(Color.RED, bitmap.getPixel(300, header.bottom))
        assertNotEquals(Color.RED, bitmap.getPixel(300, ceil(root.titleGlyphBottom).toInt() + 1))
        bitmap.recycle()
    }
}
