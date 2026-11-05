package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.TitleBarContentInsets
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TitleBarLayoutTest {
    private fun context(night: Boolean = false, fontScale: Float = 1f): ContextThemeWrapper {
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
    private data class Fixture(val root: TitleBarLayout, val header: FrameLayout,
        val bar: Toolbar, val content: FrameLayout, val list: RecyclerView)
    private fun fixture(context: Context = context(), title: String = "Settings gyp"): Fixture {
        val root = TitleBarLayout(context)
        val header = FrameLayout(context).apply {
            id = R.id.appbar
            setBackgroundColor(Color.RED)
            elevation = 7f
        }
        val bar = Toolbar(context).apply {
            id = R.id.toolbar
            this.title = title
            setBackgroundColor(Color.RED)
            elevation = 3f
        }
        header.addView(bar, FrameLayout.LayoutParams(-1, 64))
        val content = FrameLayout(context).apply { id = R.id.title_bar_content }
        val list = RecyclerView(context).apply { setPadding(7, 9, 11, 13) }
        content.addView(list, FrameLayout.LayoutParams(-1, -1))
        root.addView(header, FrameLayout.LayoutParams(-1, -2))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        measure(root)
        return Fixture(root, header, bar, content, list)
    }

    @Test fun boundaryIsNativeGlyphBottomNotFontPaddingOrToolbarBottom() {
        val f = fixture()
        val title = TitleGlyphMetrics.titleView(f.bar)!!
        val ink = Rect()
        title.paint.getTextBounds(title.text.toString(), 0, title.text.length, ink)
        val expected = (f.header.top + f.bar.top + title.top + title.baseline + ink.bottom).toFloat()
        assertEquals(expected, f.root.titleGlyphBottom, 0f)
        assertTrue(f.root.titleGlyphBottom < f.bar.bottom)
        assertEquals(ceil(expected).toInt(), f.content.top)
        assertEquals(f.header.bottom + 9, f.content.top + f.list.paddingTop)
        assertEquals(640, f.content.bottom)
        assertFalse(f.list.clipToPadding)
    }

    @Test fun changingTitleDescendersFontPaddingRtlAndRootInsetsRecomputesInk() {
        val f = fixture(title = "HHHH")
        val title = TitleGlyphMetrics.titleView(f.bar)!!
        title.includeFontPadding = true
        title.setPadding(0, 5, 0, 15)
        title.textSize = 25f
        f.root.setPadding(6, 24, 8, 31)
        f.root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        f.bar.title = "gypq"
        measure(f.root)
        val ink = Rect()
        title.paint.getTextBounds("gypq", 0, 4, ink)
        assertEquals((f.header.top + f.bar.top + title.top + title.baseline + ink.bottom).toFloat(),
            f.root.titleGlyphBottom, 0f)
        assertEquals(f.header.bottom + 9, f.content.top + f.list.paddingTop)
        assertEquals(609, f.content.bottom)
        assertEquals(13, f.list.paddingBottom)
    }

    @Test fun dayNightLargeSystemFontUsesMeasuredTitleNotActionBarDimension() {
        for (night in listOf(false, true)) for (scale in listOf(1f, 1.5f, 2f)) {
            val f = fixture(context(night, scale), "设置 gyp")
            f.bar.layoutParams.height = 96
            measure(f.root)
            val title = TitleGlyphMetrics.titleView(f.bar)!!
            val ink = Rect()
            title.paint.getTextBounds(title.text.toString(), 0, title.text.length, ink)
            assertEquals((f.header.top + f.bar.top + title.top + title.baseline + ink.bottom).toFloat(),
                f.root.titleGlyphBottom, 0f)
            assertEquals(f.header.bottom + 9, f.content.top + f.list.paddingTop)
            assertTrue(f.root.titleGlyphBottom < 96f)
        }
    }

    @Test fun initialInsetsNeverAccumulateAndCurrentBottomAndRtlInsetsSurvive() {
        val f = fixture()
        repeat(8) { f.root.refreshContentInsets(); measure(f.root) }
        assertEquals(f.header.bottom + 9, f.content.top + f.list.paddingTop)
        f.list.setPaddingRelative(19, f.list.paddingTop, 23, 147)
        repeat(8) { f.root.refreshContentInsets(); measure(f.root) }
        assertEquals(147, f.list.paddingBottom)
        assertEquals(19, f.list.paddingStart)
        assertEquals(23, f.list.paddingEnd)
        assertEquals(f.header.bottom + 9, f.content.top + f.list.paddingTop)
        assertFalse("No recurring requestLayout once geometry settles", f.root.isLayoutRequested)
    }

    @Test fun dynamicPreferenceListTransfersTheGapAndNeverTouchesAdapterData() {
        val f = fixture()
        f.content.removeAllViews()
        val fragmentHost = FrameLayout(f.root.context)
        f.content.addView(fragmentHost, FrameLayout.LayoutParams(-1, -1))
        measure(f.root)
        val gap = f.header.bottom - f.content.top
        assertEquals(gap, fragmentHost.paddingTop)
        val newList = RecyclerView(f.root.context).apply { setPadding(0, 4, 0, 72) }
        fragmentHost.addView(newList, FrameLayout.LayoutParams(-1, -1))
        measure(f.root)
        assertEquals(0, fragmentHost.paddingTop)
        assertEquals(gap + 4, newList.paddingTop)
        assertEquals(72, newList.paddingBottom)
        assertEquals(9, f.list.paddingTop) // Removed list restored before reuse.
        f.root.refreshContentInsets()
        assertEquals(gap + 4, newList.paddingTop)
    }

    @Test fun chainVerticalStackOnlyInsetsItsLeadingPreferenceBranch() {
        val c = context()
        val content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val preferences = FrameLayout(c)
        val prefsList = RecyclerView(c).apply { setPadding(0, 4, 0, 7) }
        preferences.addView(prefsList)
        val nodes = RecyclerView(c).apply { setPadding(0, 6, 0, 9) }
        content.addView(preferences)
        content.addView(nodes)
        val insets = TitleBarContentInsets()
        insets.sync(content, 21)
        repeat(6) { insets.sync(content, 21) }
        assertEquals(25, prefsList.paddingTop)
        assertEquals(6, nodes.paddingTop)
        assertEquals(7, prefsList.paddingBottom)
        insets.release()
        assertEquals(4, prefsList.paddingTop)
    }

    @Test fun outerScrollViewOwnsInsetNotItsNestedRecycler() {
        for (outer in listOf(ScrollView(context()), NestedScrollView(context()))) {
            val nested = RecyclerView(outer.context).apply { setPadding(0, 5, 0, 8) }
            outer.addView(nested)
            val insets = TitleBarContentInsets()
            insets.sync(outer, 23)
            assertEquals(23, outer.paddingTop)
            assertEquals(5, nested.paddingTop)
            insets.release()
            assertEquals(0, outer.paddingTop)
        }
    }

    @Test fun footerAndImeResizeBoundTheViewportWithoutMovingInk() {
        val f = fixture()
        val footer = FrameLayout(f.root.context).apply { id = R.id.title_bar_footer }
        f.root.addView(footer, FrameLayout.LayoutParams(-1, 42))
        f.root.setPadding(0, 0, 0, 24)
        measure(f.root)
        val boundary = f.root.titleGlyphBottom
        assertEquals(574, footer.top)
        assertEquals(footer.top, f.content.bottom)
        measure(f.root, 380)
        assertEquals(314, footer.top)
        assertEquals(314, f.content.bottom)
        assertEquals(boundary, f.root.titleGlyphBottom, 0f)
        footer.visibility = View.GONE
        measure(f.root, 380)
        assertEquals(356, f.content.bottom)
    }

    @Test fun hardwareBlurIsOffDuringBothExplicitAndAncestorHorizontalAnimation() {
        val f = fixture()
        assertTrue(f.root.useLocalBlur(true))
        assertFalse(f.root.useLocalBlur(false))
        f.root.pageTransitionRunning = true
        assertFalse(f.root.useLocalBlur(true))
        f.root.pageTransitionRunning = false
        f.root.translationX = 0.5f
        assertFalse(f.root.useLocalBlur(true))
        f.root.translationX = 0f
        val parent = FrameLayout(f.root.context)
        parent.addView(f.root)
        parent.translationX = -18f
        assertFalse(f.root.useLocalBlur(true))
        parent.translationX = 0f
        assertTrue(f.root.useLocalBlur(true))
    }

    @Test fun dayNightSoftwarePixelsFadeAtInkAndLeaveTitleActionsCrisp() {
        for (night in listOf(false, true)) {
            val f = fixture(context(night))
            val color = (f.root.background as ColorDrawable).color
            val colors = f.root.context.obtainStyledAttributes(intArrayOf(R.attr.colorLayerLow))
            try { assertEquals(colors.getColor(0, Color.MAGENTA), color) } finally { colors.recycle() }
            assertNull(f.header.background)
            assertNull(f.bar.background)
            assertEquals(0f, f.header.elevation, 0f)
            assertEquals(0f, f.bar.elevation, 0f)
            f.content.setBackgroundColor(Color.RED)
            val action = View(f.root.context).apply { setBackgroundColor(Color.GREEN) }
            f.header.addView(action, FrameLayout.LayoutParams(12, 12).apply {
                gravity = android.view.Gravity.RIGHT
                topMargin = 18
            })
            measure(f.root)
            fun render(): Bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).also {
                f.root.draw(Canvas(it)) // Bitmap canvas MUST take fallback even on SDK33.
            }
            val before = render()
            val edge = ceil(f.root.titleGlyphBottom).toInt()
            assertEquals(color, before.getPixel(300, edge - 1))
            assertNotEquals(Color.RED, before.getPixel(300, edge + 1))
            assertEquals("Stationary first row is already fully opaque at toolbar bottom",
                Color.RED, before.getPixel(300, f.header.bottom))
            assertEquals(Color.RED, before.getPixel(300, f.header.bottom + 9))
            assertEquals(Color.RED, before.getPixel(300, edge + 55))
            assertEquals(Color.GREEN, before.getPixel(352, 23))
            f.content.visibility = View.INVISIBLE
            val titleOnly = render()
            // Byte-identical title and action pixels: no blur/fade ever samples the header.
            for (y in 0 until edge) for (x in 0 until 360) {
                assertEquals(titleOnly.getPixel(x, y), before.getPixel(x, y))
            }
            before.recycle()
            titleOnly.recycle()
        }
    }

    @Test fun sameDrawingTimeInvalidationsNeverReplayPreviousContent() {
        val f = fixture()
        val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        f.content.setBackgroundColor(Color.RED)
        f.root.draw(canvas)
        val y = ceil(f.root.titleGlyphBottom).toInt() + 24
        val old = bitmap.getPixel(300, y)
        f.content.setBackgroundColor(Color.BLUE)
        f.content.invalidate()
        f.root.draw(canvas)
        assertNotEquals(old, bitmap.getPixel(300, y))
        bitmap.recycle()
    }

    @Test fun editTextScrollSurfaceUsesTheSameInsetContract() {
        val c = context()
        val editor = android.widget.EditText(c).apply { setPadding(3, 5, 7, 11) }
        val content = FrameLayout(c)
        content.addView(editor)
        val overlay = View(c)
        content.addView(overlay)
        val insets = TitleBarContentInsets()
        insets.sync(content, 22)
        assertEquals(27, editor.paddingTop)
        assertEquals(0, overlay.paddingTop)
        editor.setPadding(3, editor.paddingTop, 7, 122)
        insets.release()
        assertEquals(5, editor.paddingTop)
        assertEquals(122, editor.paddingBottom)
        insets.sync(content, 22)
        assertEquals(27, editor.paddingTop)
    }
}
