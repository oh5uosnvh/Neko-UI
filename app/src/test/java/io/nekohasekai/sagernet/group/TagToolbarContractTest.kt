package io.nekohasekai.sagernet.group

import android.app.Application
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.ToolbarIconPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TagToolbarContractTest {
    @Test fun menuKeepsAllOriginalActionsPlusTagAndEveryVectorGetsPageTint() {
        val ctx=androidx.appcompat.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_SagerNet_Blue)
        val bar=Toolbar(ctx);bar.inflateMenu(R.menu.add_group_menu)
        ToolbarIconPalette.apply(bar,ctx)
        for(id in listOf(R.id.action_group_search,R.id.action_update_all,R.id.action_tag_groups,R.id.action_new_group)) {
            assertTrue(bar.menu.findItem(id).isVisible)
            assertNotNull(bar.menu.findItem(id).icon)
        }
        fun src(path:String)=sequenceOf(File("src/main/$path"),File("app/src/main/$path")).first{it.exists()}.readText()
        val icon=src("res/drawable/ic_tag_group.xml")
        assertTrue(icon.contains("android:strokeWidth=\"2\""))
        assertTrue(icon.contains("android:fillColor=\"#00000000\""))
        assertFalse(icon.contains("#FF222222"))
        val fragment=src("java/io/nekohasekai/sagernet/ui/GroupFragment.kt")
        assertTrue(fragment.contains("ToolbarIconPalette.apply(toolbar, requireContext())"))
        assertTrue(fragment.contains("TagGroupNavigation.open(activity, forward = !inTag)"))
    }
}
