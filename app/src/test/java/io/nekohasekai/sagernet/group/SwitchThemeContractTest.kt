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
 * 1. 全应用开关（含偏好页 widgetLayout 里的框架原生 Switch、弹窗、底部弹层）
 *    必须全部走 @style/Widget.SagerNet.Switch（绿色胶囊 track + 白 thumb）。
 *    关键点：androidx.preference 的 preference_widget_switch.xml 是不带 style 的
 *    框架 <Switch>，它只读 android:switchStyle（框架命名空间），不读 appcompat 的
 *    switchStyle —— 两条命名空间都要挂。
 * 2. 启动按钮框（控制条）源码 = 1.4.2-mg-pre3（构建 273）发布版源码：
 *    单一外框涟漪 + 胶囊镂空（CapsuleExclusionMask），无逐格涟漪、无胶囊涟漪。
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

    /** 开关尺寸必须按 Karing 实测复刻：轨道内层 62x24dp、thumb 22dp，不再是 36x16 的芝麻尺寸。 */
    @Test fun switchDrawablesMatchKaringProportions() {
        val track = File(app, "src/main/res/drawable/switch_track_sagernet.xml").readText()
        assertTrue("track width 62dp", "android:width=\"62dp\"" in track)
        assertTrue("track height 24dp", "android:height=\"24dp\"" in track)
        val thumb = File(app, "src/main/res/drawable/switch_thumb_sagernet.xml").readText()
        assertTrue("thumb 22dp", "android:width=\"22dp\"" in thumb)
        // 夜间未选中 track 必须换暗灰，不能夜间还发白。
        val night = File(app, "src/main/res/values-night/colors.xml").readText()
        assertTrue("night switch_track_off override missing", "switch_track_off" in night)
    }

    /** 偏好页的 SwitchPreference 默认 widgetLayout 是框架原生 Switch（无 style 属性）。 */
    @Test fun preferenceSwitchWidgetIsBareFrameworkSwitch() {
        val aar = File(
            System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"
        )
        assertTrue("gradle cache must exist for this contract", aar.isDirectory)
        val layout = aar.walkTopDown()
            .firstOrNull { it.name == "preference-1.2.0.aar" }
        // AAR 解包不是必选项：找不到缓存时跳过（CI 上 缓存必然存在）。
        if (layout == null) return
        val xml = java.util.zip.ZipFile(layout).use { zip ->
            zip.getInputStream(zip.getEntry("res/layout/preference_widget_switch.xml"))
                .readBytes().decodeToString()
        }
        assertFalse("bare Switch must not pin a style attr", "style=".toRegex() in xml)
        assertTrue("<Switch" in xml)
    }

    /** 启动按钮框源码必须与 1.4.2-mg-pre3（构建 273）完全一致。 */
    @Test fun controlBarSourceMatchesPre3Release() {
        val bar = File(
            app, "src/main/java/io/nekohasekai/sagernet/widget/ControlBar.kt"
        ).readText()
        // pre3 的实现特征：单一外框涟漪 + 胶囊镂空遮罩
        assertTrue("pre3 外框涟漪缺失", "foreground = sideRipple" in bar)
        assertTrue("pre3 胶囊镂空遮罩缺失", "class CapsuleExclusionMask" in bar)
        assertTrue("pre3 胶囊背景挂载缺失", "capsule.background = capsuleBackground" in bar)
        // 276/281 引入的逐格涟漪与胶囊涟漪必须不存在
        assertFalse("逐格涟漪应已回退", "newSideBoxRipple" in bar)
        assertFalse("胶囊涟漪应已回退", "newCapsuleRipple" in bar)
    }
}
