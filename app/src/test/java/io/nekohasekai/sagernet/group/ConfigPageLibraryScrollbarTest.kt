package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * 配置页滚动条契约：节点列表必须与分组页使用同一套库 FastScroller 滑块
 * （simplecityapps，?colorPrimary 圆角、autoHide），自绘 FixedGroupScrollbar
 * 的 track+thumb 双条与分段跳动体验已整体摘除。
 */
class ConfigPageLibraryScrollbarTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }
    private val res = sequenceOf(File("."), File("app")).first { File(it, "src/main/res").isDirectory }

    private fun source(rel: String) = File(app, rel).readText()

    @Test
    fun edgeLayoutNoLongerWiresTheFixedScrollbar() {
        val edge = source("io/nekohasekai/sagernet/widget/GroupListEdgeLayout.kt")
        // bind/draw/touch/attach/detach 五条生产接线全部不存在
        assertFalse(edge.contains("fixedScrollbar.bind("))
        assertFalse(edge.contains("fixedScrollbar.draw("))
        assertFalse(edge.contains("fixedScrollbar.touch("))
        assertFalse(edge.contains("fixedScrollbar.detach()"))
        assertFalse(edge.contains("override fun dispatchDraw"))
        assertFalse(edge.contains("override fun dispatchTouchEvent"))
        assertFalse(edge.contains("override fun onAttachedToWindow"))
        // 顶部模糊带与 paddingTop 同步功能保留
        assertTrue(edge.contains("fun bindList(list: RecyclerView)"))
        assertTrue(edge.contains("private fun syncTop(list: RecyclerView)"))
    }

    @Test
    fun configListUsesTheSameLibraryFastScrollerAsGroupsPage() {
        val profileList = File(res, "layout/layout_profile_list.xml").readText()
        val groupList = File(res, "layout/layout_group.xml").readText()
        for (attr in listOf(
            "android:scrollbarSize=\"0dp\"",
            "app:fastScrollAutoHide=\"true\"",
            "app:fastScrollThumbColor=\"?colorPrimary\"",
            "app:fastScrollThumbInactiveColor=\"?colorPrimary\"",
        )) {
            assertTrue(attr, profileList.contains(attr))
            assertTrue(attr, groupList.contains(attr))
        }
        // 列表根视图同源：ExactScrollRecyclerView 继承库 FastScrollRecyclerView
        assertTrue(profileList.contains("io.nekohasekai.sagernet.widget.ExactScrollRecyclerView"))
        assertTrue(groupList.contains("com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView"))
    }

    @Test
    fun fixedScrollbarHasNoProductionCallSites() {
        val scrollbar = source("io/nekohasekai/sagernet/widget/FixedGroupScrollbar.kt")
        assertTrue(scrollbar.contains("internal class FixedGroupScrollbar"))
        // 生产代码（widget 包外）不得再引用它
        File(app, "").walkTopDown()
            .filter { it.isFile && it.path.endsWith(".kt") && !it.path.contains("/widget/") && !it.path.contains("/test/") }
            .forEach { file ->
                assertFalse("production reference in ${file.path}", file.readText().contains("FixedGroupScrollbar"))
            }
    }
}
