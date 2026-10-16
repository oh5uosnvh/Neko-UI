package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.shape.MaterialShapeDrawable
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.TraySurfaceFeedback
import io.nekohasekai.sagernet.widget.TrayTapTargets
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TraySurfaceFeedbackTest {
    @Test fun pressedSurfacePaintsBottomInsetAndIconTouchStaysIndependent() {
        val a=Robolectric.buildActivity(Activity::class.java).setup().get()
        a.setTheme(R.style.Theme_SagerNet)
        val root=FrameLayout(a)
        root.background=MaterialShapeDrawable()
        LayoutInflater.from(a).inflate(R.layout.layout_connect_bar,root,true)
        root.setPadding(0,0,0,24)
        a.setContentView(root)
        var tests=0;var details=0
        TrayTapTargets.bind(root,{tests++},{details++})
        root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.AT_MOST))
        root.layout(0,0,root.measuredWidth,root.measuredHeight)
        val feedback=TraySurfaceFeedback(root)
        feedback.syncShape()
        val ripple=root.foreground as android.graphics.drawable.RippleDrawable
        root.draw(Canvas(Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)))
        assertEquals(root.height,ripple.bounds.bottom)
        assertNotNull(ripple.findDrawableByLayerId(android.R.id.mask))
        val attrs=a.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val official=attrs.getDrawable(0);attrs.recycle()
        assertEquals("same drawable implementation as official XML",official!!::class.java,ripple::class.java)
        fun touch(x:Float,y:Float,action:Int) {
            val now=android.os.SystemClock.uptimeMillis()
            val event=MotionEvent.obtain(now,now,action,x,y,0)
            root.dispatchTouchEvent(event);event.recycle()
            if (action==MotionEvent.ACTION_UP) org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        }
        touch(10f,(root.height-2).toFloat(),MotionEvent.ACTION_DOWN)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150))
        assertTrue(root.isPressed)
        touch(10f,(root.height-2).toFloat(),MotionEvent.ACTION_UP)
        assertEquals(1,tests)
        val icon=root.findViewById<View>(R.id.bar_ip_info)
        val rect=android.graphics.Rect();icon.getDrawingRect(rect);root.offsetDescendantRectToMyCoords(icon,rect)
        touch(rect.exactCenterX(),rect.exactCenterY(),MotionEvent.ACTION_DOWN)
        assertFalse("info touch must not press the entire tray",root.isPressed)
        touch(rect.exactCenterX(),rect.exactCenterY(),MotionEvent.ACTION_UP)
        assertEquals(1,tests);assertEquals(1,details)
    }
}
