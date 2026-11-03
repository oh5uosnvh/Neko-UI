package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.OutlinedSnackbar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class OutlinedSnackbarTest {
    @Test fun allMessagesKeepActionAndTimingButGainBlackWhiteEdgeAndCorrectFill() {
        for(night in listOf(false,true)) {
            val app=RuntimeEnvironment.getApplication()
            val config=Configuration(app.resources.configuration).apply {uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(night)Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO}
            val context=android.view.ContextThemeWrapper(app.createConfigurationContext(config),R.style.Theme_SagerNet)
            val root=FrameLayout(context).apply{id=android.R.id.content}
            var undo=0
            val snackbar=Snackbar.make(root,"已删除 1 个配置",Snackbar.LENGTH_LONG).setAction("还原"){undo++}
            OutlinedSnackbar.decorate(snackbar)
            val shape=snackbar.view.background as GradientDrawable
            assertEquals(if(night)0xFF252628.toInt() else Color.WHITE,shape.color!!.defaultColor)
            assertNull(androidx.core.view.ViewCompat.getBackgroundTintList(snackbar.view))
            assertEquals(Snackbar.LENGTH_LONG,snackbar.duration)
            val action=snackbar.view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_action)
            assertEquals("还原",action.text.toString())
            assertEquals(if(night)Color.WHITE else Color.BLACK,action.currentTextColor)
            action.performClick();assertEquals(1,undo)
            val state=org.robolectric.util.ReflectionHelpers.getField<Any>(shape,"mGradientState")
            val stroke=org.robolectric.util.ReflectionHelpers.getField<android.content.res.ColorStateList>(state,"mStrokeColors")
            assertEquals(if(night)Color.WHITE else Color.BLACK,stroke.defaultColor)
        }
    }
}
