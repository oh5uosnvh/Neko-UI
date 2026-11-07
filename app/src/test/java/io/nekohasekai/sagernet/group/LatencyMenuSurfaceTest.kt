package io.nekohasekai.sagernet.group

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.FlMenu
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LatencyMenuSurfaceTest {
 @Test fun menuPerimeterHasOneVisibleStrokeAcrossThePageTree() {
  for(night in listOf(false,true)) {
   val controller=Robolectric.buildActivity(AppCompatActivity::class.java)
   val a=controller.get().apply { setTheme(R.style.Theme_SagerNet) }
   controller.setup().visible()
   val config=android.content.res.Configuration(a.resources.configuration).apply {
    uiMode=(uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
      if(night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
   }
   val c=ContextThemeWrapper(a.createConfigurationContext(config),R.style.Theme_SagerNet)
   val host=FrameLayout(a); val anchor=View(c)
   host.addView(anchor,FrameLayout.LayoutParams(48,48));a.setContentView(host)
   host.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));host.layout(0,0,360,640)
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
   val menu=MenuBuilder(c);SupportMenuInflater(c).inflate(R.menu.add_profile_menu,menu)
   val popup=FlMenu.show(anchor,menu.findItem(R.id.action_misc).subMenu!!){true}
   try {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    val surface=popup.contentView
    val background=surface.background
    assertNotNull(background)
    val image=Bitmap.createBitmap(240,180,Bitmap.Config.ARGB_8888)
    background.setBounds(0,0,240,180);background.draw(Canvas(image))
    val inside=image.getPixel(120,3)
    val perimeter=image.getPixel(120,0)
    assertNotEquals("visible menu perimeter in both themes",inside,perimeter)
    assertTrue(Color.alpha(perimeter)>0)
    image.recycle()
   } finally {popup.dismiss();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));controller.pause().stop().destroy()}
  }
 }
}
