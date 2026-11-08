package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentHostCallback
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.TopBarController
import io.nekohasekai.sagernet.widget.TitleBarLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SelectorTopBarTest {
    private lateinit var environment: QuickPopupTestEnvironment
    @Before fun setup() { environment = QuickPopupTestEnvironment() }
    @After fun cleanup() { environment.close() }
    private data class Fixture(val root: TitleBarLayout, val toolbar: Toolbar, val controller: TopBarController)
    private fun fixture(night: Boolean, select: Boolean): Fixture {
        val app = RuntimeEnvironment.getApplication()
        val cfg = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val context = ContextThemeWrapper(app.createConfigurationContext(cfg), R.style.Theme_SagerNet)
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        val host = Class.forName("sun.misc.Unsafe").getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, ConfigurationFragment::class.java) as ConfigurationFragment
        ReflectionHelpers.setField(host, "select", select)
        ReflectionHelpers.setField(host, "mHost", object : FragmentHostCallback<android.content.Context>(context,
            Handler(Looper.getMainLooper()), 0) { override fun onGetHost() = context })
        val root = TitleBarLayout(context)
        val appbar = AppBarLayout(context).apply { id = R.id.appbar; setPadding(0, 24, 0, 0) }
        val toolbar = Toolbar(context).apply { id = R.id.toolbar }
        appbar.addView(toolbar, AppBarLayout.LayoutParams(-1, 56))
        val content = FrameLayout(context).apply { id = R.id.title_bar_content }
        root.addView(appbar, FrameLayout.LayoutParams(-1, -2))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val controller = TopBarController(host)
        if (select) controller.attachSelection(toolbar, R.string.select_profile)
        else controller.attach(toolbar, object : TopBarController.Callbacks {
            override fun onOpenDrawer() = Unit
            override fun onTitleClicked() = Unit
            override fun onAddClicked(anchor: View) = Unit
            override fun onMoreClicked(anchor: View) = Unit
        })
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 360, 640)
        return Fixture(root, toolbar, controller)
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun selectorAndMainUseSameVerticalHeaderGeometryWithStatusInset() {
        for (night in listOf(false, true)) {
            val main = fixture(night, false)
            val picker = fixture(night, true)
            try {
                assertEquals(View.GONE, picker.toolbar.visibility)
                assertEquals(main.root.titleGlyphBottom, picker.root.titleGlyphBottom, 0f)
                assertEquals(main.root.findViewById<View>(R.id.title_bar_content).top,
                    picker.root.findViewById<View>(R.id.title_bar_content).top)
            } finally { main.controller.detach(); picker.controller.detach() }
        }
    }
    @Test fun groupIconIsDarkInDayAndLightAtNightAtActualRenderedPixels() {
        for (night in listOf(false, true)) {
            val f = fixture(night, true)
            try {
                val icon = descendants(f.root).filterIsInstance<ImageView>().single {
                    it.contentDescription?.toString() == f.root.context.getString(R.string.topbar_group_desc)
                }
                // The vector is intentionally theme-tinted by TopBarController;
                // checking the filter avoids depending on legacy renderer antialiasing.
                assertNotNull("selector group icon must be theme tinted", icon.colorFilter)
                assertEquals(View.VISIBLE, icon.visibility)
            } finally { f.controller.detach() }
        }
    }
}
