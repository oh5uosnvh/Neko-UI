package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.LatencyTestOverlay
import io.nekohasekai.sagernet.widget.GroupListEdgeLayout
import io.nekohasekai.sagernet.widget.LatencyTestStrip
import org.junit.Assert.*
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
@Config(sdk=[28], application=Application::class, qualifiers="w360dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LatencyStripTimingTest {
    private fun advance(ms: Long) {
        var left=ms
        while(left>0) { val step=minOf(left,16L);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step));left-=step }
    }
    private fun fixture(test:(LatencyTestOverlay, FrameLayout, View)->Unit) {
        ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val c=Robolectric.buildActivity(AppCompatActivity::class.java)
        val a=c.get().apply {setTheme(R.style.Theme_SagerNet)}
        c.setup().visible()
        val root=FrameLayout(a)
        val edge=GroupListEdgeLayout(a).apply { id=R.id.group_list_foreground }
        val pager=View(a).apply { id=R.id.group_pager;setPadding(7,9,11,13) }
        edge.addView(pager,FrameLayout.LayoutParams(-1,-1));root.addView(edge,FrameLayout.LayoutParams(-1,-1))
        a.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));root.layout(0,0,360,640)
        advance(32)
        val overlay=LatencyTestOverlay(root){}
        try {test(overlay,root,pager)} finally {overlay.release();c.pause().stop().destroy();ShadowChoreographer.setPaused(false);ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))}
    }
    @Test fun completionStaysTwoSecondsAfterArrivalAndNewRunCancelsTheOldRetirement()=fixture {o,_,_->
        o.running(0,52,0);advance(600)
        o.completed(40,12)
        advance(300)
        assertEquals(LatencyTestStrip.State.RESULT,o.stripForTests.state)
        advance(500)
        assertEquals(View.VISIBLE,o.stripForTests.visibility)
        advance(1500)
        assertEquals(View.VISIBLE,o.stripForTests.visibility)
        // Before the old retirement, a fresh real run supersedes the result.
        o.running(0,10,0)
        advance(3200)
        assertEquals(LatencyTestStrip.State.RUNNING,o.stripForTests.state)
        assertEquals(View.VISIBLE,o.stripForTests.visibility)
        o.completed(6,4,true)
        advance(3200)
        assertEquals(LatencyTestStrip.State.HIDDEN,o.stripForTests.state)
        assertEquals(View.GONE,o.stripForTests.visibility)
    }
    @Test fun repeatedProgressChangesKeepPagerCoordinatesPaddingAndLayoutUnchanged()=fixture {o,root,pager->
        o.running(1,10000,0);advance(600)
        val top=pager.top;val bottom=pager.bottom;val padding=listOf(pager.paddingLeft,pager.paddingTop,pager.paddingRight,pager.paddingBottom)
        // Force a settled frame so this only checks requests caused by progress.
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));root.layout(0,0,360,640)
        repeat(100){o.running(it+990,10000,it%4)}
        assertFalse("counting must not request layout on pager",pager.isLayoutRequested)
        assertEquals(top,pager.top);assertEquals(bottom,pager.bottom)
        assertEquals(padding,listOf(pager.paddingLeft,pager.paddingTop,pager.paddingRight,pager.paddingBottom))
        assertEquals(1089,o.stripForTests.displayedDone)
    }
}
