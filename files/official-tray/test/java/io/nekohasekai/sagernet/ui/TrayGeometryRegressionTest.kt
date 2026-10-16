package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.app.Application
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.widget.StatsBar
import io.nekohasekai.sagernet.widget.TrayTapTargets
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TrayGeometryRegressionTest {
    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get().apply { setTheme(R.style.Theme_SagerNet) }
    @Test fun actualMainLayoutHasOfficialBottomPlacementAndNoExposedStrip() {
        val activity=activity()
        val root=CoordinatorLayout(activity)
        val parser=activity.resources.getLayout(R.layout.layout_main)
        var params: CoordinatorLayout.LayoutParams?=null
        parser.use {
            while (it.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (it.eventType==org.xmlpull.v1.XmlPullParser.START_TAG && it.name.endsWith("ConnectBar")) {
                    params=root.generateLayoutParams(android.util.Xml.asAttributeSet(it)); break
                }
            }
        }
        val lp=requireNotNull(params)
        assertEquals("Legacy 4dp margin must not survive",0,lp.bottomMargin)
        assertEquals(Gravity.BOTTOM,lp.gravity)
        val tray=StatsBar(activity)
        listOf(R.id.tx,R.id.rx,R.id.status).forEach { id -> tray.addView(TextView(activity).apply { this.id=id }) }
        tray.setOnClickListener { }
        root.addView(tray,lp.apply { height=180 })
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY))
        root.layout(0,0,1080,1920)
        tray.syncMainControls(true,BaseService.State.Stopped,false,false)
        assertTrue("Hidden tray top still inside screen",tray.top+tray.translationY >= root.height)
        tray.syncMainControls(true,BaseService.State.Connected,true,false)
        assertEquals("Visible tray bottom must touch container",root.height.toFloat(),tray.bottom+tray.translationY,0.01f)
    }
    @Test fun networkBlockHugsRightAndOnlyInfoOpensDetails() {
        val activity=activity(); val root=FrameLayout(activity)
        LayoutInflater.from(activity).inflate(R.layout.layout_connect_bar,root,true)
        val density=activity.resources.displayMetrics.density
        fun dp(n:Int)=(n*density+.5f).toInt()
        val content=root.getChildAt(0) as LinearLayout
        val ip=root.findViewById<TextView>(R.id.bar_ip_value);ip.text="140.245.48.68"
        val network=root.findViewById<View>(R.id.bar_network_card)
        var tests=0;var info=0
        TrayTapTargets.bind(root,{tests++},{info++})
        root.measure(View.MeasureSpec.makeMeasureSpec(dp(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(dp(200),View.MeasureSpec.AT_MOST))
        root.layout(0,0,root.measuredWidth,root.measuredHeight)
        assertEquals(dp(16),content.width-network.right)
        assertEquals("Left edge must keep official 16dp padding",dp(16),content.paddingLeft)
        assertEquals("IP address ends at network block edge",network.width,ip.right)
        assertEquals(0f,(network.layoutParams as LinearLayout.LayoutParams).weight,0.01f)
        root.performClick(); content.performClick()
        listOf(R.id.bar_speed_zone,R.id.bar_delay_zone,R.id.bar_network_card).forEach {
            assertFalse(root.findViewById<View>(it).isClickable)
            assertNull(root.findViewById<View>(it).background)
        }
        assertNotNull(content.background)
        assertEquals(2,tests);assertEquals(0,info)
        root.findViewById<View>(R.id.bar_ip_info).performClick()
        assertEquals(2,tests);assertEquals(1,info)
    }
}
