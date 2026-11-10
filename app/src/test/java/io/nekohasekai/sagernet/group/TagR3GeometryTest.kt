package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.TagMemberSearchBar
import io.nekohasekai.sagernet.widget.ThemedChoiceStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
open class TagR3GeometryTest {
 private fun ctx(night:Boolean,scale:Float):ContextThemeWrapper {
  val app=RuntimeEnvironment.getApplication()
  val config=Configuration(app.resources.configuration).apply {
   fontScale=scale
   uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
  }
  return ContextThemeWrapper(app.createConfigurationContext(config),R.style.Theme_SagerNet_Blue)
 }
 private fun layout(view:View,width:Int,height:Int?=null) {
  view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
   if(height==null) View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
   else View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
  view.layout(0,0,view.measuredWidth,view.measuredHeight)
 }
 private fun textCentre(text:TextView):Float {
  val l=text.layout!!
  val available=text.height-text.compoundPaddingTop-text.compoundPaddingBottom
  val vertical=if((text.gravity and Gravity.VERTICAL_GRAVITY_MASK)==Gravity.CENTER_VERTICAL) (available-l.height)/2f else 0f
  return text.top+text.compoundPaddingTop+vertical+(l.getLineTop(0)+l.getLineBottom(l.lineCount-1))/2f
 }
 @Test fun groupStatusAndUpdateTextHaveSameVerticalAxisInBothThemesAndTrafficStates() {
  for(night in listOf(false,true)) for(scale in listOf(1f,1.5f)) for(traffic in listOf(false,true)) {
   val context=ctx(night,scale)
   val root=LayoutInflater.from(context).inflate(R.layout.layout_group_item,null)
   val status=root.findViewById<TextView>(R.id.group_status)
   val update=root.findViewById<MaterialButton>(R.id.group_update)
   status.text="86 个配置 | 更新·10-01"
   val usage=root.findViewById<TextView>(R.id.group_traffic)
   usage.visibility=if(traffic)View.VISIBLE else View.GONE
   usage.text="已用 1GB / 100GB"
   layout(root,480)
   val row=status.parent as LinearLayout
   assertFalse(row.isBaselineAligned)
   assertEquals(Gravity.CENTER_VERTICAL,row.gravity and Gravity.VERTICAL_GRAVITY_MASK)
   val s=status.top+status.height/2f
   val u=update.top+update.height/2f
   assertEquals("status/update box axes night=$night scale=$scale traffic=$traffic",u,s,1f)
   assertEquals("actual text axes",textCentre(update),textCentre(status),1.5f)
  }
 }
 @Test fun manualTitleTextSharesArrowAndConfirmAxisNotTopGravity() {
  for(night in listOf(false,true)) for(scale in listOf(1f,1.5f)) {
   val context=ctx(night,scale)
   val bar=TagMemberSearchBar(Toolbar(context),context)
   bar.render(true,0,1)
   layout(bar.row,480,64)
   assertFalse(bar.row.isBaselineAligned)
   assertEquals(Gravity.CENTER_VERTICAL,bar.title.gravity and Gravity.VERTICAL_GRAVITY_MASK)
   val centre=bar.back.top+bar.back.height/2f
   assertEquals(centre,bar.done.top+bar.done.height/2f,0f)
   assertEquals(centre,bar.title.top+bar.title.height/2f,0f)
   assertEquals(centre,textCentre(bar.title),1.5f)
  }
 }
 @Test fun opaqueTopButtonsKeepSameVisibleColorsWithoutAlphaAndNormalRowsStayOriginal() {
  for(night in listOf(false,true)) for(selected in listOf(false,true)) {
   val context=ctx(night,1f)
   val original=ThemedChoiceStyle.itemCard(context,selected).color!!.defaultColor
   val attrs=context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
   val base=androidx.core.graphics.ColorUtils.setAlphaComponent(attrs.getColor(0,0),255);attrs.recycle()
   val opaque=ThemedChoiceStyle.opaqueItemCard(context,selected).color!!.defaultColor
   assertEquals(255,android.graphics.Color.alpha(opaque))
   assertEquals(androidx.core.graphics.ColorUtils.compositeColors(original,base),opaque)
  }
 }
}
