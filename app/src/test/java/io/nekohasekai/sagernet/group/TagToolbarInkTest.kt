package io.nekohasekai.sagernet.group

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.ToolbarIconPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35], application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagToolbarInkTest {
    @Test fun allActionVectorsMatchPageInkAndTagStaysOpenOutlinedAtBothThemes() {
        val app=RuntimeEnvironment.getApplication()
        for (night in listOf(false,true)) {
            val config=android.content.res.Configuration(app.resources.configuration).apply {
                uiMode=(uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if(night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            }
            val ctx=ContextThemeWrapper(app.createConfigurationContext(config),R.style.Theme_SagerNet_Blue)
            val toolbar=Toolbar(ctx)
            toolbar.inflateMenu(R.menu.add_group_menu)
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            ToolbarIconPalette.apply(toolbar,ctx)
            val ink=ToolbarIconPalette.foreground(ctx)
            for (id in listOf(R.id.action_group_search,R.id.action_update_all,R.id.action_tag_groups,R.id.action_new_group)) {
                val icon=toolbar.menu.findItem(id).icon!!
                val bitmap=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888)
                icon.setBounds(0,0,48,48); icon.draw(Canvas(bitmap))
                val pixels=IntArray(48*48); bitmap.getPixels(pixels,0,48,0,0,48,48)
                assertTrue("actual opaque icon ink must exist for $id",pixels.any {android.graphics.Color.alpha(it)>240})
                assertTrue("page ink, not fixed white resource",pixels.filter{android.graphics.Color.alpha(it)>240}.all{
                    (it and 0xFFFFFF)==(ink and 0xFFFFFF)
                })
                if(id==R.id.action_tag_groups) {
                    assertEquals("interior must stay transparent after tint",0,android.graphics.Color.alpha(bitmap.getPixel(24,24)))
                    val occupied=pixels.indices.filter{android.graphics.Color.alpha(pixels[it])>40}
                    val minY=occupied.minOf{it/48};val maxY=occupied.maxOf{it/48}
                    assertTrue("tag enlarged to at least 30 pixels",maxY-minY>=30)
                }
                bitmap.recycle()
            }
        }
    }
}
