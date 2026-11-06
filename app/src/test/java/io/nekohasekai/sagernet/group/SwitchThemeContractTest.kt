package io.nekohasekai.sagernet.group

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户核验契约（2026-11-05）：
 * 1. 普通开关必须统一走 ReferenceSwitch：44x24dp、18dp thumb、300ms 双曲线。
 *    默认框架 widgetLayout 本地覆盖，保留 android:id/switch_widget 与行点击；
 *    三条主题命名空间是配色 fallback，不能替代真实 widget/绑定覆盖测试。
 * 2. 控制条中央按钮保留常驻主题胶囊，只有图标＋文字淡出淡入；
 *    点击/按压前景为空，两侧读数原有反馈不变。
 */
class SwitchThemeContractTest {
    private val app = sequenceOf(File("."), File("app")).first {
        File(it, "src/main/res/values/themes.xml").isFile
    }
    private val themes: Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder()
        .parse(File(app, "src/main/res/values/themes.xml")).documentElement

    private fun styleNamed(name: String): Element =
        themes.getElementsByTagName("style").let { list ->
            (0 until list.length).map { list.item(it) as Element }
        }.singleOrNull { it.getAttribute("name") == name }
            ?: error("style $name not found in themes.xml")

    private fun items(style: Element): Map<String, String> =
        style.getElementsByTagName("item").let { list ->
            (0 until list.length).map { list.item(it) as Element }
        }.associate { it.getAttribute("name") to it.textContent.trim() }

    private fun colorNamed(name: String): String =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(app, "src/main/res/values/colors.xml"))
            .documentElement.getElementsByTagName("color").let { list ->
                (0 until list.length).map { list.item(it) as Element }
            }.single { it.getAttribute("name") == name }.textContent.trim()

    private val SWITCH_STYLE = "@style/Widget.SagerNet.Switch"

    /** 主题链上每个承载开关的主题都要同时挂三条命名空间。 */
    @Test fun everyThemeChainCarriesAllThreeSwitchStyles() {
        val carriers = listOf(
            "Theme.SagerNet.Base",
            "Theme.SagerNet.Dialog",
            "ThemeOverlay.MaterialComponents.MaterialAlertDialog.SagerNet",
            "ThemeOverlay.SagerNet.BottomSheetDialog",
        )
        for (name in carriers) {
            val items = items(styleNamed(name))
            assertEquals("$name switchStyle", SWITCH_STYLE, items["switchStyle"])
            assertEquals("$name materialSwitchStyle", SWITCH_STYLE, items["materialSwitchStyle"])
            assertEquals(
                "$name android:switchStyle（框架原生 Switch 只读这条）",
                SWITCH_STYLE, items["android:switchStyle"],
            )
        }
    }

    /** 绿色开关样式本身必须指向绿色胶囊资源，且颜色真的是绿色。 */
    @Test fun switchStylePointsAtGreenCapsuleResources() {
        val items = items(styleNamed("Widget.SagerNet.Switch"))
        assertEquals("@drawable/switch_thumb_sagernet", items["android:thumb"])
        assertEquals("@drawable/switch_track_sagernet", items["android:track"])
        assertEquals("@color/switch_thumb_color", items["android:thumbTint"])
        assertEquals("@color/switch_track_color", items["android:trackTint"])
        // 框架原生 Switch 会把 textOn/textOff 画进轨道（「已开启/已关闭」芝麻小字），
        // 必须置空 —— 样式里必须带这两个空 item。
        assertTrue("switch style must blank textOn", "android:textOn" in items)
        assertTrue("switch style must blank textOff", "android:textOff" in items)

        val green = colorNamed("switch_checked_green")
        val argb = green.removePrefix("#").toLong(16).let {
            if (green.length == 7) it or 0xff000000 else it
        }
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        assertTrue("checked track #$green must be green (r=$r g=$g b=$b)", g > r && g > b)
    }

    /** JS geometry resources are fallback only; runtime geometry is tested separately. */
    @Test fun switchDrawablesMatchReferenceProportions() {
        val track = File(app, "src/main/res/drawable/switch_track_sagernet.xml").readText()
        assertTrue("track width 44dp", "android:width=\"44dp\"" in track)
        assertTrue("track height 24dp", "android:height=\"24dp\"" in track)
        assertFalse("track must not add optical insets", "<inset" in track)
        val thumb = File(app, "src/main/res/drawable/switch_thumb_sagernet.xml").readText()
        assertTrue("thumb 18dp", "android:width=\"18dp\"" in thumb)
        assertEquals("#CBD5E1", colorNamed("switch_track_off"))
        val night = File(app, "src/main/res/values-night/colors.xml").readText()
        assertTrue("night JS border2", "#3D5175" in night)
    }

    @Test fun preferenceWidgetOverrideKeepsNativeIdAndRowInteraction() {
        val xml = File(app, "src/main/res/layout/preference_widget_switch.xml").readText()
        assertTrue("shared widget must override library layout", "<io.nekohasekai.sagernet.widget.ReferenceSwitch" in xml)
        assertTrue("native preference ID", "@android:id/switch_widget" in xml)
        assertTrue("row-owned click", "android:clickable=\"false\"" in xml)
        assertTrue("row-owned focus", "android:focusable=\"false\"" in xml)
    }

    @Test fun everyOrdinarySwitchEntryUsesSharedWidgetAndBindingScope() {
        val resources = File(app, "src/main/res")
        val oldTypes = Regex("<(?:Switch|SwitchPreference|androidx\\.appcompat\\.widget\\.SwitchCompat|com\\.google\\.android\\.material\\.(?:switchmaterial\\.SwitchMaterial|materialswitch\\.MaterialSwitch))(?=[\\s>])")
        resources.walkTopDown().filter { it.extension == "xml" }.forEach {
            assertFalse("legacy switch entry in ${it.name}", oldTypes.containsMatchIn(it.readText()))
        }
        val xml = File(resources, "xml").walkTopDown().filter { it.extension == "xml" }
            .joinToString("\n") { it.readText() }
        assertEquals("all 54 ordinary preference switches remain covered", 54,
            Regex("<io\\.nekohasekai\\.sagernet\\.widget\\.ReferenceSwitchPreference\\b").findAll(xml).count())
        for (name in listOf("layout_route_item", "layout_apps_item", "layout_local_proxy_dialog")) {
            assertTrue(name, "<io.nekohasekai.sagernet.widget.ReferenceSwitch" in
                File(resources, "layout/$name.xml").readText())
        }
        val ua = File(app, "src/main/java/io/nekohasekai/sagernet/widget/UserAgentPreference.kt").readText()
        assertTrue("UA dynamic switches", "ReferenceSwitch(context)" in ua)
        assertFalse("no material switch fallback", "SwitchMaterial(" in ua)
        val longClick = File(app, "src/main/java/moe/matsuri/nb4a/ui/LongClickSwitchPreference.kt").readText()
        assertTrue("long-click preferences share binding scope", ": ReferenceSwitchPreference(" in longClick)
    }

    /** 常驻主题胶囊必须保留；只取消按压反馈，图标文案仍淡出淡入。 */
    @Test fun controlBarKeepsThemeCapsuleWithoutTouchFill() {
        val bar = File(
            app, "src/main/java/io/nekohasekai/sagernet/widget/ControlBar.kt"
        ).readText()
        assertTrue("保留两侧读数的外框反馈", "foreground = sideRipple" in bar)
        assertTrue("反馈不能盖住连接按钮", "class CapsuleExclusionMask" in bar)
        assertTrue("常驻胶囊底色必须保留", "capsule.background = capsuleBackground" in bar)
        assertTrue("只移除按压前景底色", "capsule.foreground = null" in bar)
        assertTrue("连接态胶囊取主题色渐变",
            "capsuleOnGradient.setColors(intArrayOf(highlight, primary))" in bar)
        assertTrue("断开态胶囊为日夜间灰蓝渐变", "capsuleOffGradient" in bar)
        assertFalse("不得增加旋转中间态", "connectingAnimator" in bar)
        assertFalse("两侧反馈范围不变", "newSideBoxRipple" in bar)
        assertFalse("连接按钮不需要涟漪", "newCapsuleRipple" in bar)
        val layout = File(app, "src/main/res/layout/layout_control_bar.xml").readText()
            .substringAfter("android:id=\"@+id/capsule\"").substringBefore("<LinearLayout")
        assertTrue("XML 首帧保留主题胶囊", "android:background=\"@drawable/bg_capsule\"" in layout)
        assertTrue("XML 首帧无按压底色", "android:foreground=\"@null\"" in layout)
    }
}
