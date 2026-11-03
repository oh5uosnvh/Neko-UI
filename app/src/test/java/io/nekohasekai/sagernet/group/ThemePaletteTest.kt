package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.Theme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ThemePaletteTest {
    @Test fun thirdPinkPaletteSlotAndDialogRemainDistinctFromBlueGreyWithExactLayerColors() {
        val app=RuntimeEnvironment.getApplication()
        for(night in listOf(false,true)) {
            val configuration=Configuration(app.resources.configuration).apply {
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if(night)Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            for(dialog in listOf(false,true)) {
                val pink=ContextThemeWrapper(app.createConfigurationContext(configuration),
                    if(dialog)Theme.getDialogTheme(3) else Theme.getTheme(3))
                val grey=ContextThemeWrapper(app.createConfigurationContext(configuration),
                    if(dialog)Theme.getDialogTheme(20) else Theme.getTheme(20))
                fun color(ctx:ContextThemeWrapper,attr:Int):Int=ctx.obtainStyledAttributes(intArrayOf(attr)).let{a->
                    try{a.getColor(0,Color.MAGENTA)}finally{a.recycle()}}
                assertEquals(pink.resources.getColor(R.color.material_pink_500,pink.theme),color(pink,R.attr.colorPrimary))
                assertNotEquals(color(grey,R.attr.colorPrimary),color(pink,R.attr.colorPrimary))
                assertEquals(Color.parseColor(if(night)"#121315" else "#F0F0F0"),color(pink,R.attr.colorLayerLow))
                assertEquals(Color.parseColor(if(night)"#252628" else "#FFFFFF"),color(pink,R.attr.colorLayerHigh))
                assertEquals(22,(1..22).map{if(dialog)Theme.getDialogTheme(it) else Theme.getTheme(it)}.toSet().size)
            }
        }
    }
}
