package io.nekohasekai.sagernet.group

import android.app.Application
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.PendingGroupDeletes
import io.nekohasekai.sagernet.ui.TagMemberPolicy
import io.nekohasekai.sagernet.ui.TagMemberSearchBar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TagMemberFlowTest {
    private val roots = listOf(ProxyGroup(1,name="花云 A"),ProxyGroup(2,name="工作"),ProxyGroup(3,name="花云 B"))
    @Test fun viewingFiltersRootObjectsWithoutCopyingOrChangingRootOrder() {
        val result = TagMemberPolicy.visible(roots,setOf(3,1),false,false,false,"")
        assertEquals(listOf(1L,3L), result.map { it.id })
        assertSame(roots[0],result[0]); assertSame(roots[2],result[1])
        assertEquals(listOf(1L,2L,3L), roots.map { it.id })
    }
    @Test fun searchEmptyIsEmptyManualLoadsAllAndAlreadyClassifiedIsNotAddedTwice() {
        assertTrue(TagMemberPolicy.visible(roots,setOf(1),true,false,false,"").isEmpty())
        assertEquals(roots,TagMemberPolicy.visible(roots,setOf(1),true,true,false,""))
        assertEquals(listOf(1L,3L),TagMemberPolicy.visible(roots,setOf(1),true,false,false," 花云 ").map { it.id })
        assertEquals(listOf(3L),TagMemberPolicy.newMatches(roots,setOf(1),"花云"))
        assertFalse(TagMemberPolicy.canSelect(1,setOf(1),false))
        assertTrue(TagMemberPolicy.canSelect(3,setOf(1),false))
        assertEquals(listOf(1L),TagMemberPolicy.visible(roots,setOf(1),true,true,true,"").map { it.id })
    }
    @Test fun permanentManualLeftOfCloseAndTickOnlyAfterManual() {
        val ctl=Robolectric.buildActivity(AppCompatActivity::class.java)
        val ctx=ctl.get().apply {setTheme(R.style.Theme_SagerNet_Blue)}; ctl.setup()
        val parent=ctx.layoutInflater.inflate(R.layout.layout_appbar,null) as com.google.android.material.appbar.AppBarLayout
        val toolbar=parent.findViewById<Toolbar>(R.id.toolbar)
        ctx.setContentView(parent)
        val bar=TagMemberSearchBar(toolbar,ctx)
        var closed=0;var confirmed=0;var manual=0
        bar.attach({}, {manual++}, {}, {closed++},{confirmed++})
        bar.render(false,0,0)
        assertEquals(View.VISIBLE,bar.manual.visibility)
        assertEquals(View.GONE,bar.classify.visibility)
        bar.manual.performClick();assertEquals(1,manual)
        bar.done.performClick();assertEquals(1,closed)
        bar.render(true,0,0);bar.done.performClick();assertEquals(0,confirmed)
        assertEquals(View.GONE,bar.search.visibility)
        bar.render(true,0,2);bar.done.performClick();assertEquals(1,confirmed)
        assertEquals(View.GONE,bar.manual.visibility)
        bar.detach();assertNull(bar.row.parent);assertEquals(View.VISIBLE,toolbar.visibility)
        ctl.pause().stop().destroy()
    }
    @Test fun pendingRootDeletionIsReversibleAndNotAnEntityOrRelationRewrite() {
        PendingGroupDeletes.mark(73001L)
        try { assertTrue(PendingGroupDeletes.contains(73001L)); assertTrue(73001L in PendingGroupDeletes.ids()) }
        finally { PendingGroupDeletes.unmark(listOf(73001L)) }
        assertFalse(PendingGroupDeletes.contains(73001L))
    }
    @Test fun integrationUsesExplicitAtomicTagCreationAndScopedUpdatesAndFiveSecondUndo() {
        fun source(path:String)=File("src/main/java/io/nekohasekai/sagernet/$path").takeIf{it.exists()}?.readText()
            ?:File("app/src/main/java/io/nekohasekai/sagernet/$path").readText()
        val fragment=source("ui/GroupFragment.kt")
        assertTrue(fragment.contains("GroupManager.createGroup(group, targetTag, entities)"))
        assertTrue(fragment.contains("TagGroupStore.members(tagId)"))
        assertTrue(fragment.contains("it.id in members && !PendingGroupDeletes.contains(it.id)"))
        assertTrue(fragment.contains("TagMemberPolicy.visible"))
        assertTrue(fragment.contains("requestExitTagSearch()"))
        assertTrue(fragment.contains("PendingGroupDeletes.mark(group.id)"))
        assertTrue(source("widget/UndoSnackbarManager.kt").contains("duration = 5000"))
    }
}
