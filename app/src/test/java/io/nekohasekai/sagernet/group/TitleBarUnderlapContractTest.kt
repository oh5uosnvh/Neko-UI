package io.nekohasekai.sagernet.group

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/**
 * Route/group/logcat are the only TitleBarLayout pages whose scrolling surface sits
 * behind a container. Their scroll views must declare clipToPadding=false so rows can
 * actually slide under the toolbar and cross the title-gap blur band (the settings
 * pages already do this via their plain preference lists).
 */
class TitleBarUnderlapContractTest {
    private val app = sequenceOf(File("."), File("app")).firstOrNull {
        File(it, "src/main/res/layout/layout_route.xml").isFile
    } ?: error("Run from the app module or repository root")
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(app, "src/main/res/$path")).documentElement

    private fun elements(root: Element): List<Element> = buildList {
        add(root)
        val nodes = root.getElementsByTagName("*")
        for (i in 0 until nodes.length) add(nodes.item(i) as Element)
    }

    private fun single(root: Element, id: String): Element =
        elements(root).single { it.getAttributeNS(androidNs, "id").endsWith("/$id") }

    private fun clipToPadding(view: Element): Boolean? =
        view.getAttributeNS(androidNs, "clipToPadding").takeIf { it.isNotEmpty() }?.toBoolean()

    @Test fun routeListLetsRowsSlideUnderTheTitleGap() {
        assertEquals(false, clipToPadding(single(xml("layout/layout_route.xml"), "route_list")))
    }

    @Test fun groupListLetsRowsSlideUnderTheTitleGap() {
        assertEquals(false, clipToPadding(single(xml("layout/layout_group.xml"), "group_list")))
    }

    @Test fun logcatTextCanReachTheWholeTitleGap() {
        val root = xml("layout/layout_logcat.xml")
        // The content container must not pad the band away: top padding only in insets.
        val content = single(root, "title_bar_content")
        assertFalse(content.hasAttributeNS(androidNs, "padding"))
        assertEquals("8dp", content.getAttributeNS(androidNs, "paddingStart"))
        assertEquals("8dp", content.getAttributeNS(androidNs, "paddingEnd"))
        assertEquals("8dp", content.getAttributeNS(androidNs, "paddingBottom"))
        assertEquals(false, clipToPadding(single(root, "scroolview")))
    }

    @Test fun everyOtherTitleBarScrollSurfaceStillDeclaresUnderlap() {
        assertEquals(false, clipToPadding(single(xml("layout/layout_assets.xml"), "recycler_view")))
        assertEquals(false, clipToPadding(single(xml("layout/layout_about.xml"), "title_bar_content")))
        assertEquals(false, clipToPadding(single(xml("layout/layout_apps.xml"), "list")))
        assertEquals(false, clipToPadding(single(xml("layout/layout_app_list.xml"), "list")))
        assertEquals(false, clipToPadding(single(xml("layout/layout_chain_settings.xml"), "configuration_list")))
    }
}
