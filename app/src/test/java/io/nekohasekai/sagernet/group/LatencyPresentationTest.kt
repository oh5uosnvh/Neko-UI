package io.nekohasekai.sagernet.group

import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.widget.ControlBar
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w360dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LatencyPresentationTest {
    @Before fun frames(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    @After fun restore(){ShadowChoreographer.setPaused(false);ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))}
    private fun advance(ms:Long){var remaining=ms;while(remaining>0){val step=minOf(16L,remaining);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step));remaining-=step}}
    private fun withBar(block:(ControlBar)->Unit){
        val c=Robolectric.buildActivity(AppCompatActivity::class.java);val a=c.get();a.setTheme(R.style.Theme_SagerNet);c.setup().visible()
        val host=FrameLayout(a);val bar=ControlBar(a);host.addView(bar,FrameLayout.LayoutParams(360,76));a.setContentView(host)
        host.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(76,View.MeasureSpec.EXACTLY));host.layout(0,0,360,76);advance(32)
        try{bar.changeState(BaseService.State.Connected);advance(180);block(bar)}finally{c.pause().stop().destroy()}
    }
    @Test fun fastTwentyMsResultWaitsFor150MsSpinnerAndStillDisplaysTwenty()=withBar{bar->
        val text=bar.findViewById<TextView>(R.id.latency);val circle=bar.findViewById<View>(R.id.latency_progress)
        bar.showTesting();advance(20);bar.showLatency(20)
        assertEquals(View.VISIBLE,circle.visibility);assertEquals("",text.text.toString())
        advance(129);assertEquals("",text.text.toString());assertFalse(bar.findViewById<View>(R.id.latency_box).isEnabled)
        advance(1);assertEquals("20 ms",text.text.toString());advance(132)
        assertEquals(View.GONE,circle.visibility);assertEquals(Color.rgb(76,175,80),text.currentTextColor)
        assertTrue(bar.findViewById<View>(R.id.latency_box).isEnabled)
    }
    @Test fun cancelOrNewNodeCannotRevivePendingShortMeasurement()=withBar{bar->
        bar.showTesting();bar.showLatency(21);bar.clearMeasurements();advance(300)
        assertEquals("-- ms",bar.findViewById<TextView>(R.id.latency).text.toString())
        assertEquals(View.GONE,bar.findViewById<View>(R.id.latency_progress).visibility)
        bar.showTesting();bar.showFailure("connection reset");bar.changeState(BaseService.State.Stopped);advance(300)
        assertEquals("-- ms",bar.findViewById<TextView>(R.id.latency).text.toString())
    }
    @Test fun longHoldReleaseAlwaysClearsPressedStateWithoutSecondClick()=withBar{bar->
        val side=bar.findViewById<View>(R.id.latency_box);val parent=side.parent as View
        val x=(parent.left+side.left+side.width/2).toFloat();val y=(parent.top+side.top+side.height/2).toFloat()
        val down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,x,y,0);bar.dispatchTouchEvent(down);down.recycle();advance(700)
        val up=MotionEvent.obtain(0,710,MotionEvent.ACTION_UP,x,y,0);bar.dispatchTouchEvent(up);up.recycle();advance(300)
        assertFalse(side.isPressed);assertFalse(bar.foreground.state.contains(android.R.attr.state_pressed))
    }
}
