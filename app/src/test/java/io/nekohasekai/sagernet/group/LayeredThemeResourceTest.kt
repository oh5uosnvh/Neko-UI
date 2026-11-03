package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element

/** Source contracts run on a plain JVM; the second suite inflates the real Android resources. */
@RunWith(Enclosed::class)
class LayeredThemeResourceTest {
    class ResourceContract {
        private val app = sequenceOf(File("."), File("app")).first {
            File(it, "src/main/res/values/themes.xml").isFile
        }
        private val ns = "http://schemas.android.com/apk/res/android"
        private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(app, "src/main/res/$path")).documentElement
        private fun all(root: Element): List<Element> = buildList {
            add(root)
            val nodes = root.getElementsByTagName("*")
            for (i in 0 until nodes.length) add(nodes.item(i) as Element)
        }
        private fun styles(path: String) = all(xml(path)).filter { it.tagName == "style" }
        private fun bag(style: Element) = all(style).filter { it.tagName == "item" }
            .associate { it.getAttribute("name") to it.textContent.trim() }
        private fun value(path: String, name: String) = all(xml(path))
            .single { it.getAttribute("name") == name }.textContent.trim()

        @Test fun exactDayNightPaletteAndExistingAttributesArePreserved() {
            for ((folder, colors) in mapOf(
                "values" to listOf("#F0F0F0", "#FFFFFF", "#59000000"),
                "values-night" to listOf("#121315", "#252628", "#80FFFFFF"))) {
                for ((name, expected) in listOf("layer_low", "layer_high", "layer_stroke").zip(colors)) {
                    assertEquals(expected, value("$folder/colors.xml", name))
                }
                assertEquals("@color/layer_high", value("$folder/colors.xml", "preference_simple_menu_background"))
            }
            for ((name, expected) in mapOf("brand_primary" to "#607D8B",
                "brand_primary_light" to "#90A4AE", "latency_green" to "#4CAF50")) {
                assertEquals(expected, value("values/colors.xml", name))
            }
            val attrs = all(xml("values/attrs.xml")).filter { it.tagName == "attr" }
            assertEquals(attrs.size, attrs.map { it.getAttribute("name") }.toSet().size)
            for (name in listOf("colorLayerLow", "colorLayerHigh", "colorLayerStroke",
                "selectedColorPrimary", "cardElevatedSurfaceColor", "colorMaterial100")) {
                assertEquals("color", attrs.single { it.getAttribute("name") == name }.getAttribute("format"))
            }
        }

        @Test fun baseDialogAndApiQualifiedThemeKeepTheLayerContract() {
            val themes = styles("values/themes.xml")
            for (name in listOf("Theme.SagerNet.Base", "Theme.SagerNet.Dialog")) {
                val items = bag(themes.single { it.getAttribute("name") == name })
                assertEquals("@color/layer_low", items["colorLayerLow"])
                assertEquals("@color/layer_high", items["colorLayerHigh"])
                assertEquals("@color/layer_stroke", items["colorLayerStroke"])
                assertEquals("?attr/colorLayerLow", items["android:windowBackground"])
                assertEquals("?attr/colorLayerLow", items["android:colorBackground"])
                assertEquals("?attr/colorLayerLow", items["android:navigationBarColor"])
                assertEquals("?attr/colorLayerHigh", items["colorSurface"])
                assertEquals("@color/brand_primary", items["colorPrimary"])
                assertEquals("?attr/colorPrimary", items["tabSelectedTextColor"])
                assertEquals("?attr/colorPrimary", items["tabIndicatorColor"])
                assertEquals("?android:attr/textColorSecondary", items["tabTextColor"])
                assertFalse(items["actionBarTheme"].orEmpty().contains("Dark.ActionBar"))
            }
            assertEquals("Theme.SagerNet.Base", themes.single {
                it.getAttribute("name") == "Theme.SagerNet" }.getAttribute("parent"))
            val qualified = File(app, "src/main/res/values-v26/themes.xml")
            if (qualified.isFile) {
                val override = styles("values-v26/themes.xml").singleOrNull {
                    it.getAttribute("name") == "Theme.SagerNet" }
                if (override != null) {
                    assertEquals("API 26 must inherit the shared bag, not hide the layered attributes",
                        "Theme.SagerNet.Base", override.getAttribute("parent"))
                    assertEquals("@android:color/transparent", bag(override)["android:statusBarColor"])
                }
            }
            for (name in listOf("Theme.SagerNet.Blue", "Theme.SagerNet.Amber", "Theme.SagerNet.Black")) {
                val items = bag(themes.single { it.getAttribute("name") == name })
                assertNotEquals("@color/brand_primary", items["colorPrimary"])
                assertTrue(items.containsKey("cardElevatedSurfaceColor"))
            }
            assertEquals("@color/color_ng_black_accent", bag(themes.single {
                it.getAttribute("name") == "Theme.SagerNet.Black" })["selectedColorPrimary"])
        }

        @Test fun allSixScreensAndBothSettingsHostsAreBoundedAndKeepTheirIds() {
            val targets = mapOf("group_list" to "group_pager", "group" to "group_list",
                "route" to "route_list", "assets" to "refresh_layout", "tools" to "tools_pager",
                "config_settings" to "settings", "settings_activity" to "settings")
            for ((layout, id) in targets) {
                val root = xml("layout/layout_$layout.xml")
                val child = all(root).single { it.getAttributeNS(ns, "id") == "@+id/$id" }
                val wrapper = child.parentNode as Element
                assertEquals("io.nekohasekai.sagernet.widget.LayeredContentFrameLayout", wrapper.tagName)
                assertEquals("@drawable/bg_layer_high", wrapper.getAttributeNS(ns, "background"))
                assertEquals("0dp", wrapper.getAttributeNS(ns, "layout_height"))
                assertEquals("1", wrapper.getAttributeNS(ns, "layout_weight"))
                assertEquals("10dp", wrapper.getAttributeNS(ns, "layout_marginTop"))
                assertEquals("false", wrapper.getAttributeNS(ns, "clipToOutline"))
                assertEquals("match_parent", child.getAttributeNS(ns, "layout_height"))
            }
            val settings = all(xml("layout/layout_settings_activity.xml"))
            val hosts = listOf("settings", "configuration_list").map { id -> settings.single {
                it.getAttributeNS(ns, "id") == "@+id/$id" } }
            assertSame(hosts[0].parentNode, hosts[1].parentNode)
            hosts.forEach { assertFalse(it.hasAttributeNS(ns, "visibility")) }
            assertTrue(File(app, "src/main/java/io/nekohasekai/sagernet/ui/SettingsFragment.kt")
                .readText().contains("ToolbarFragment(R.layout.layout_config_settings)"))
            val shape = xml("drawable/bg_layer_high.xml")
            val corners=all(shape).single { it.tagName == "corners" }
            assertEquals("24dp", corners.getAttributeNS(ns, "topLeftRadius"))
            assertEquals("24dp", corners.getAttributeNS(ns, "topRightRadius"))
            assertFalse(corners.hasAttributeNS(ns, "radius"))
            assertFalse(corners.hasAttributeNS(ns, "bottomLeftRadius"))
            assertFalse(corners.hasAttributeNS(ns, "bottomRightRadius"))
            val stroke = all(shape).single { it.tagName == "stroke" }
            assertEquals("1dp", stroke.getAttributeNS(ns, "width"))
            assertEquals("?attr/colorLayerStroke", stroke.getAttributeNS(ns, "color"))
        }
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    class RealResources {
        private fun context(night: Boolean, theme: Int): ContextThemeWrapper {
            val app = RuntimeEnvironment.getApplication()
            val config = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            return ContextThemeWrapper(app.createConfigurationContext(config), theme)
        }
        private fun color(context: Context, attr: Int): Int {
            val values = context.obtainStyledAttributes(intArrayOf(attr))
            return try {
                assertTrue("Missing theme attr $attr", values.hasValue(0))
                values.getColor(0, Color.MAGENTA)
            } finally { values.recycle() }
        }
        @Test fun dayNightAndBlackResolveIndependentWindowAndContentColors() {
            for (night in listOf(false, true)) {
                for (theme in listOf(R.style.Theme_SagerNet, R.style.Theme_SagerNet_Blue,
                    R.style.Theme_SagerNet_Black, R.style.Theme_SagerNet_Dialog,
                    R.style.Theme_SagerNet_Dialog_Black)) {
                    val context = context(night, theme)
                    val low = Color.parseColor(if (night) "#121315" else "#F0F0F0")
                    val high = Color.parseColor(if (night) "#252628" else "#FFFFFF")
                    assertEquals(low, color(context, R.attr.colorLayerLow))
                    assertEquals(low, color(context, android.R.attr.windowBackground))
                    assertEquals(low, color(context, android.R.attr.colorBackground))
                    assertEquals(low, color(context, android.R.attr.navigationBarColor))
                    assertEquals(high, color(context, R.attr.colorLayerHigh))
                    assertEquals(high, color(context, com.google.android.material.R.attr.colorSurface))
                    assertEquals(Color.parseColor(if (night) "#80FFFFFF" else "#59000000"),
                        color(context, R.attr.colorLayerStroke))
                }
            }
        }

        @Test fun realToolbarAndTabsHaveReadableDayNightForegroundAndPrimarySelection() {
            for (night in listOf(false, true)) {
                val context = context(night, R.style.Theme_SagerNet)
                val root = LayoutInflater.from(context).inflate(R.layout.layout_group_list,
                    FrameLayout(context), false)
                val toolbar = root.findViewById<MaterialToolbar>(R.id.toolbar)
                toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
                assertEquals(color(context, android.R.attr.textColorPrimary),
                    org.robolectric.util.ReflectionHelpers.getField<android.content.res.ColorStateList>(
                        toolbar, "mTitleTextColor").defaultColor)
                assertEquals(color(context, android.R.attr.textColorPrimary), toolbar.navigationIconTint)
                assertEquals(color(context, android.R.attr.textColorPrimary),
                    color(toolbar.context, androidx.appcompat.R.attr.colorControlNormal))
                assertEquals(color(context, R.attr.colorLayerLow),
                    (toolbar.background as com.google.android.material.shape.MaterialShapeDrawable)
                        .fillColor!!.defaultColor)
                val tabs = root.findViewById<TabLayout>(R.id.group_tab)
                tabs.addTab(tabs.newTab().setText("First"))
                tabs.addTab(tabs.newTab().setText("Second"))
                val colors = tabs.tabTextColors!!
                assertEquals(color(context, android.R.attr.textColorSecondary), colors.defaultColor)
                assertEquals(color(context, R.attr.colorPrimary), colors.getColorForState(
                    intArrayOf(android.R.attr.state_selected), Color.MAGENTA))
                assertEquals(color(context, R.attr.colorPrimary),
                    org.robolectric.util.ReflectionHelpers.getField<Int>(tabs, "tabSelectedIndicatorColor"))
                val popup = ContextThemeWrapper(toolbar.context, toolbar.popupTheme)
                assertEquals(color(context, R.attr.colorLayerHigh),
                    color(popup, android.R.attr.colorBackground))
                assertTrue("Unselected text cannot be nearly white on light chrome",
                    night || Color.red(colors.defaultColor) < 180)
            }
        }

        @Test fun sixInflatedScreensUseOneClippableHighLayerAndNeverOverflowAfterAppbar() {
            val targets = listOf(R.layout.layout_group_list to R.id.group_pager,
                R.layout.layout_group to R.id.group_list, R.layout.layout_route to R.id.route_list,
                R.layout.layout_assets to R.id.refresh_layout, R.layout.layout_tools to R.id.tools_pager,
                R.layout.layout_config_settings to R.id.settings,
                R.layout.layout_settings_activity to R.id.settings)
            for (night in listOf(false, true)) {
                val context = context(night, R.style.Theme_SagerNet)
                for ((layout, id) in targets) {
                    val root = LayoutInflater.from(context).inflate(layout, FrameLayout(context), false)
                    val child = root.findViewById<View>(id)
                    assertNotNull(child)
                    val wrapper = child.parent as FrameLayout
                    val params = wrapper.layoutParams as LinearLayout.LayoutParams
                    assertEquals(0, params.height)
                    assertEquals(1f, params.weight, 0f)
                    assertEquals(10, params.topMargin)
                    root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 360, 640)
                    assertTrue(wrapper.height > 0)
                    assertTrue(wrapper.bottom <= (wrapper.parent as View).height)
                    val background = wrapper.background as GradientDrawable
                    assertEquals(color(context, R.attr.colorLayerHigh), background.color!!.defaultColor)
                    assertTrue(wrapper is io.nekohasekai.sagernet.widget.LayeredContentFrameLayout)
                    assertFalse(wrapper.clipToOutline)
                    val radii=org.robolectric.util.ReflectionHelpers.getField<FloatArray>(
                        background.constantState,"mRadiusArray")
                    assertArrayEquals(floatArrayOf(24f,24f,24f,24f,0f,0f,0f,0f),radii,0.01f)
                    // The dedicated host clips Canvas on API21+, rather than relying on an
                    // Outline that cannot clip asymmetric corners before API33.
                    val path=org.robolectric.util.ReflectionHelpers.getField<android.graphics.Path>(wrapper,"clipPath")
                    val bounds=android.graphics.RectF()
                    path.computeBounds(bounds,true)
                    assertEquals(android.graphics.RectF(0f,0f,wrapper.width.toFloat(),wrapper.height.toFloat()),bounds)
                }
            }
        }

        @Test fun bothSettingsHostsCanBeHiddenWithoutRemovingTheHighContainer() {
            val context = context(false, R.style.Theme_SagerNet)
            val root = LayoutInflater.from(context).inflate(R.layout.layout_settings_activity,
                FrameLayout(context), false)
            val settings = root.findViewById<View>(R.id.settings)
            val list = root.findViewById<View>(R.id.configuration_list)
            assertSame(settings.parent, list.parent)
            val wrapper = settings.parent as FrameLayout
            for (showSettings in listOf(false, true)) {
                settings.visibility = if (showSettings) View.VISIBLE else View.GONE
                list.visibility = if (showSettings) View.GONE else View.VISIBLE
                assertEquals(View.VISIBLE, wrapper.visibility)
                assertNotNull(wrapper.background)
                val visible = if (showSettings) settings else list
                assertEquals(View.VISIBLE, visible.visibility)
                assertSame(wrapper, visible.parent)
            }
        }
    }
}
