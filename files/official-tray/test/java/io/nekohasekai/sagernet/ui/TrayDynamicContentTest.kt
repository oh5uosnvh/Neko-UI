package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.widget.TrayStatusText
import io.nekohasekai.sagernet.widget.TrayTapTargets
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class TrayDynamicContentTest {
    @Test fun statusAndIpChangesCannotChangeTrayHeightAtAnySupportedWidth() {
        val a=Robolectric.buildActivity(Activity::class.java).setup().get()
        for (width in listOf(320,360,412)) for (scale in listOf(1f,1.3f)) {
            val config=Configuration(a.resources.configuration).apply { fontScale=scale;setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
            val c=android.view.ContextThemeWrapper(a.createConfigurationContext(config),R.style.Theme_SagerNet)
            val root=FrameLayout(c)
            LayoutInflater.from(c).inflate(R.layout.layout_connect_bar,root,true)
            val status=root.findViewById<TextView>(R.id.status)
            val ip=root.findViewById<TextView>(R.id.bar_ip_value)
            fun measure(): Int {
                root.measure(View.MeasureSpec.makeMeasureSpec((width*c.resources.displayMetrics.density).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.AT_MOST))
                root.layout(0,0,root.measuredWidth,root.measuredHeight)
                return root.height
            }
            val height=measure()
            for (text in listOf("已连接 , 点击此处测试连接","握手耗时 测试中…","握手耗时 190ms","握手耗时 测试失败")) {
                status.text=text
                for (address in listOf("查询中…","80.251.211.247","2001:db8:1234:5678::1","*****")) {
                    ip.text=address
                    assertEquals("height changed at $width dp / $scale font / $text / $address",height,measure())
                }
            }
            TrayStatusText.apply(root,BaseService.State.Connected)
            assertEquals(c.getString(R.string.neko_handshake_testing),status.text.toString())
            assertEquals(height,measure())
            TrayStatusText.apply(root,BaseService.State.Stopped)
            assertEquals(height,measure())
        }
    }
    @Test fun visibleInfoCircleEndsAtExactlySameEdgeAsIpAndPressFeedbackSpansContent() {
        val a=Robolectric.buildActivity(Activity::class.java).setup().get();a.setTheme(R.style.Theme_SagerNet)
        val root=FrameLayout(a);LayoutInflater.from(a).inflate(R.layout.layout_connect_bar,root,true)
        TrayTapTargets.bind(root,{},{})
        root.findViewById<TextView>(R.id.bar_ip_value).text="80.251.211.247"
        root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.AT_MOST));root.layout(0,0,root.measuredWidth,root.measuredHeight)
        val icon=root.findViewById<ImageView>(R.id.bar_ip_info)
        val ip=root.findViewById<TextView>(R.id.bar_ip_value)
        val x1=IntArray(2);val x2=IntArray(2);icon.getLocationInWindow(x1);ip.getLocationInWindow(x2)
        assertEquals("visible icon edge not equal to address edge",(x2[0]+ip.width).toFloat(),(x1[0]+icon.width-icon.paddingRight).toFloat(),1f)
        assertEquals(0,icon.paddingRight)
        val content=root.findViewById<View>(R.id.tray_content)
        val now=android.os.SystemClock.uptimeMillis()
        val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,10f,content.height/2f,0)
        content.dispatchTouchEvent(down);down.recycle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150))
        assertTrue("entire content must show pressed state",content.isPressed)
        assertTrue(content.background.state.contains(android.R.attr.state_pressed))
        val up=MotionEvent.obtain(now,now+200,MotionEvent.ACTION_UP,10f,content.height/2f,0)
        content.dispatchTouchEvent(up);up.recycle()
    }
}
