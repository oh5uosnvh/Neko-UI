package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * 配置页滚动条契约：节点列表的 track+thumb 必须钉在视口右缘（原始位置），
 * 绝不随分组页横移捆绑滑动 —— 因此由 GroupListEdgeLayout 在 pager 之外绘制
 * 固定滚动条，同时禁用会随页面平移的库滑块；分组页（不在 pager 内）继续用库滑块。
 */
class ConfigPageLibraryScrollbarTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }
    private val res = sequenceOf(File("."), File("app")).first { File(it, "src/main/res").isDirectory }

    private fun source(rel: String) = File(app, "src/main/java/$rel").readText()

    @Test
    fun edgeLayoutDrawsThePinnedScrollbarOutsideThePager() {
        val edge = source("io/nekohasekai/sagernet/widget/GroupListEdgeLayout.kt")
        // 五条生产接线必须存在：bind/draw/touch/attach/detach
        assertTrue(edge.contains("fixedScrollbar.bind("))
        assertTrue(edge.contains("fixedScrollbar.draw("))
        assertTrue(edge.contains("fixedScrollbar.touch("))
        assertTrue(edge.contains("fixedScrollbar.detach()"))
        assertTrue(edge.contains("override fun dispatchDraw"))
        assertTrue(edge.contains("override fun dispatchTouchEvent"))
        assertTrue(edge.contains("override fun onAttachedToWindow"))
        // 绘制发生在 pager 之外的 dispatchDraw，永不随页面平移
        assertTrue(edge.contains("NOT translated with ViewPager"))
        // paddingTop/顶栏模糊带同步保留
        assertTrue(edge.contains("fun bindList(list: RecyclerView)"))
        assertTrue(edge.contains("private fun syncTop(list: RecyclerView)"))
    }

    @Test
    fun pinnedScrollbarDisablesThePageTranslatingLibraryThumb() {
        val scrollbar = source("io/nekohasekai/sagernet/widget/FixedGroupScrollbar.kt")
        // bind 即禁用库滑块，否则双条并存（旧版截图问题）
        assertTrue(scrollbar.contains("useFixedScrollbar(true)"))
        // 每个滚动帧都刷新拇指几何（防分段跳动）
        assertTrue(scrollbar.contains("root.postInvalidateOnAnimation()"))
        assertTrue(scrollbar.contains("onScrollActivity()"))
    }

    @Test
    fun fixedScrollbarIsReferencedOnlyInsideTheWidgetPackage() {
        val offenders = File(app, "src/main/java").walkTopDown()
            .filter { it.isFile && it.path.endsWith(".kt") && !it.path.contains("/widget/") }
            .filter { it.readText().contains("FixedGroupScrollbar") }
            .map { it.path }
            .toList()
        assertTrue("production references outside widget/: $offenders", offenders.isEmpty())
    }

    @Test
    fun listsDeclareIdenticalLibraryFastScrollMetrics() {
        val profileList = File(res, "src/main/res/layout/layout_profile_list.xml").readText()
        val groupList = File(res, "src/main/res/layout/layout_group.xml").readText()
        for (attr in listOf(
            "android:scrollbarSize=\"0dp\"",
            "app:fastScrollAutoHide=\"true\"",
            "app:fastScrollThumbColor=\"?colorPrimary\"",
            "app:fastScrollThumbInactiveColor=\"?colorPrimary\"",
        )) {
            assertTrue(attr, profileList.contains(attr))
            assertTrue(attr, groupList.contains(attr))
        }
    }
}
