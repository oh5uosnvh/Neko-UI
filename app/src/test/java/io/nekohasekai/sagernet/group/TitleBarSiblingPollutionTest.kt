package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.TitleBarLayout
import io.nekohasekai.sagernet.widget.TitleGlyphMetrics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * MainActivity keeps cached pages attached (hidden) inside one fragment holder.
 * A hidden configuration page carries its own marked R.id.title_bar_title; the
 * marked-title lookup must never escape the page's own appbar subtree, or every
 * plain-title page silently loses its scroll edge band (blur/fade baseline).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class TitleBarSiblingPollutionTest {
    private fun context(): ContextThemeWrapper {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_SagerNet)
    }

    private fun measureHolder(holder: ViewGroup) {
        holder.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        holder.layout(0, 0, 360, 640)
    }

    /** Mimics the cached ConfigurationFragment: own appbar + marked title TextView. */
    private fun addHiddenConfigurationSibling(holder: ViewGroup) {
        val c = context()
        val sibling = FrameLayout(c)
        val appbar = AppBarLayout(c)
        val barRow = FrameLayout(c)
        val toolbar = Toolbar(c).apply { title = "" }
        val marked = TextView(c).apply {
            id = R.id.title_bar_title
            text = c.getString(R.string.app_name)
            visibility = View.VISIBLE
        }
        barRow.addView(toolbar, FrameLayout.LayoutParams(-1, -1))
        barRow.addView(marked, FrameLayout.LayoutParams(-2, -2))
        appbar.addView(barRow, AppBarLayout.LayoutParams(-1, -2))
        sibling.addView(appbar, FrameLayout.LayoutParams(-1, -2))
        holder.addView(sibling, FrameLayout.LayoutParams(-1, -1))
        sibling.visibility = View.GONE // Hidden but ATTACHED, exactly like FM hide().
    }

    private fun addLogPage(holder: ViewGroup): TitleBarLayout {
        val root = LayoutInflater.from(context())
            .inflate(R.layout.layout_logcat, holder, false) as TitleBarLayout
        holder.addView(root, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    @Test fun hiddenSiblingMarkedTitleCannotPoisonPlainTitlePage() {
        val holder = FrameLayout(context())
        addHiddenConfigurationSibling(holder)
        val logPage = addLogPage(holder)
        measureHolder(holder)

        val toolbar = logPage.findViewById<Toolbar>(R.id.toolbar)
        val title = TitleGlyphMetrics.titleView(toolbar)
        assertNotNull("Native title must still resolve", title)
        assertEquals(toolbar.title.toString(), title?.text.toString())

        val appbar = logPage.findViewById<AppBarLayout>(R.id.appbar)
        assertTrue(
            "Edge band must survive sibling pages: band=${logPage.titleEdgeBandHeight()}",
            logPage.titleEdgeBandHeight() > 0f)
        assertTrue(logPage.titleGlyphBottom < appbar.bottom)
    }

    @Test fun markedTitleInsideOwnAppbarIsStillPreferred() {
        val holder = FrameLayout(context())
        addHiddenConfigurationSibling(holder)
        val logPage = addLogPage(holder)
        // Own page installs a marked title next to its toolbar, like the group appbar.
        val appbar = logPage.findViewById<AppBarLayout>(R.id.appbar)
        val own = TextView(context()).apply {
            id = R.id.title_bar_title
            text = "own"
            visibility = View.VISIBLE
        }
        appbar.addView(own, AppBarLayout.LayoutParams(-2, -2))
        measureHolder(holder)

        val toolbar = logPage.findViewById<Toolbar>(R.id.toolbar)
        assertEquals("own", TitleGlyphMetrics.titleView(toolbar)?.text.toString())
    }
}
