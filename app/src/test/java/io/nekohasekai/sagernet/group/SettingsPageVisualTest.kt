package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.settingsRowsInflater
import io.nekohasekai.sagernet.ui.withoutSettingsRowPress
import io.nekohasekai.sagernet.widget.SettingsCardAdapter
import io.nekohasekai.sagernet.widget.TitleBarLayout
import io.nekohasekai.sagernet.widget.createSettingsCardAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import kotlin.math.ceil

/** Real editor XML + AndroidX Preferences + app card adapter + title-edge renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsPageVisualTest {
    class Editor : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val name = requireArguments().getInt("resource")
            setPreferencesFromResource(name, rootKey)
        }
        override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup,
            savedInstanceState: Bundle?): RecyclerView =
            super.onCreateRecyclerView(settingsRowsInflater(inflater, parent), parent, savedInstanceState)
                .withoutSettingsRowPress()
        override fun onCreateAdapter(preferenceScreen: PreferenceScreen) = createSettingsCardAdapter(preferenceScreen)
    }
    class Activity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_SagerNet_Red)
            super.onCreate(savedInstanceState)
        }
    }
    private fun setQualifiersSafely(value: String) {
        try { org.robolectric.RuntimeEnvironment.setQualifiers(value) } catch (_: IllegalArgumentException) { }
    }


        val value = it.getColor(0, Color.MAGENTA); it.recycle(); value
    }

    @Test fun actualEditorsRenderOutlinedGroupsOnTheSharedSurfaceAndScrollUnderTitleInk() {
        val output = File("build/reports/ui-pre4").apply { mkdirs() }
        val pages = listOf(
            Triple("route", R.xml.route_preferences, "路由设置"),
            Triple("group", R.xml.group_preferences, "分组设置"),
            Triple("server", R.xml.standard_v2ray_preferences, "服务器配置"),
            Triple("settings", R.xml.global_preferences, "设置"))
        for (night in listOf(false, true)) for ((name, resource, title) in pages) {
            setQualifiersSafely("zh-rCN-w360dp-h800dp-mdpi-" + if (night) "night" else "notnight")
            val environment = QuickPopupTestEnvironment()
            val controller = Robolectric.buildActivity(Activity::class.java)
            val activity = controller.setup().visible().get()
            try {
                val root = LayoutInflater.from(activity).inflate(R.layout.layout_settings_activity, null) as TitleBarLayout
                root.findViewById<Toolbar>(R.id.toolbar).apply {
                    this.title = title
                    setNavigationIcon(R.drawable.ic_navigation_close)
                }
                activity.setContentView(root)
                val fragment = Editor().apply { arguments = Bundle().apply { putInt("resource", resource) } }
                activity.supportFragmentManager.beginTransaction().replace(R.id.settings, fragment).commitNow()
                fun layout() {
                    root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 360, 800)
                }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48))
                layout()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48))
                layout()
                val list = fragment.listView
                val adapter = list.adapter as SettingsCardAdapter
                assertTrue(adapter.itemCount > adapter.index.sections.size)
                val header = root.findViewById<View>(R.id.appbar)
                assertTrue(root.titleGlyphBottom < header.bottom)
                assertEquals(color(activity, R.attr.colorLayerLow), (root.background as ColorDrawable).color)
                fun capture(suffix: String) {
                    val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    val low = color(activity, R.attr.colorLayerLow)
                    assertEquals(low, bitmap.getPixel(0, header.bottom + 80))
                    assertEquals(low, bitmap.getPixel(300, ceil(root.titleGlyphBottom).toInt() - 1))
                    File(output, "$name-${if (night) "night" else "day"}-$suffix.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    bitmap.recycle()
                }
                capture("top")
                list.scrollBy(0, 105)
                layout()
                capture("scroll")
            } finally {
                controller.pause().stop().destroy()
                environment.close()
            }
        }
    }
}
