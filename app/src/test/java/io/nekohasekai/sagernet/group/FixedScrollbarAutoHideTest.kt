package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.widget.ExactScrollRecyclerView
import io.nekohasekai.sagernet.widget.FixedGroupScrollbar
import io.nekohasekai.sagernet.widget.GroupListEdgeLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[35],application=Application::class)
class FixedScrollbarAutoHideTest {
    @Before fun frames(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    @After fun restore(){ShadowChoreographer.setPaused(false);ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))}
    private fun advance(ms:Long){var remaining=ms;while(remaining>0){val step=minOf(16L,remaining);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step));remaining-=step}}
    @Test fun defaultHiddenThenOfficial1500DelayAnd200RetractionWithDragCancelAndDetach() {
        val context=RuntimeEnvironment.getApplication()
        val root=GroupListEdgeLayout(context)
        val list=ExactScrollRecyclerView(context).apply {
            layoutManager=LinearLayoutManager(context)
            adapter=object:RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount()=300
                override fun onCreateViewHolder(parent:android.view.ViewGroup,type:Int)=object:RecyclerView.ViewHolder(View(context)){}
                override fun onBindViewHolder(holder:RecyclerView.ViewHolder,p:Int){holder.itemView.layoutParams=RecyclerView.LayoutParams(-1,60)}
            }
        }
        val controller=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        controller.get().setContentView(root)
        root.addView(list,FrameLayout.LayoutParams(-1,-1));root.bindList(list)
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));root.layout(0,0,360,640)
        advance(32) // View.postDelayed needs a real attached window, not a detached run queue.
        assertFalse(root.fixedScrollbar.isVisible)
        list.scrollBy(0,500);advance(170)
        assertTrue(root.fixedScrollbar.isVisible);assertEquals(1f,root.fixedScrollbar.revealProgress,.01f)
        advance(1320);assertTrue(root.fixedScrollbar.isVisible)
        advance(250);assertFalse(root.fixedScrollbar.isVisible)
        assertEquals(1500L,FixedGroupScrollbar.AUTO_HIDE_DELAY);assertEquals(200L,FixedGroupScrollbar.HIDE_DURATION)
        root.fixedScrollbar.onScrollActivity();advance(170);assertTrue(root.fixedScrollbar.isVisible)
        root.fixedScrollbar.detach();advance(2000);assertFalse(root.fixedScrollbar.isVisible)
        controller.pause().stop().destroy()
    }
}
