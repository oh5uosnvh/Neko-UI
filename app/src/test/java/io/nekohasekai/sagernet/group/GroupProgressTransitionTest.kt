package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.GroupPageTransition
import io.nekohasekai.sagernet.widget.GroupTabPill
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
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GroupProgressTransitionTest {
    @Before fun frames(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    @After fun restore(){ShadowChoreographer.setPaused(false);ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))}
    private fun advance(ms:Long){var remaining=ms;while(remaining>0){val step=minOf(16L,remaining);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step));remaining-=step}}
    private val context get()=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_SagerNet)
    private fun stage():Triple<org.robolectric.android.controller.ActivityController<android.app.Activity>,ViewPager2,TabLayout> {
        val pager=ViewPager2(context)
        pager.adapter=object:RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount()=3
            override fun onCreateViewHolder(parent:android.view.ViewGroup,type:Int)=object:RecyclerView.ViewHolder(FrameLayout(context)){}
            override fun onBindViewHolder(holder:RecyclerView.ViewHolder,p:Int){holder.itemView.layoutParams=android.view.ViewGroup.LayoutParams(-1,-1)}
        }
        val tabs=TabLayout(context)
        for(name in listOf("前组","中组","慈善机场")){val tab=tabs.newTab();GroupTabPill.bindTab(tab,name);tabs.addTab(tab)}
        val host=FrameLayout(context).apply {addView(pager,FrameLayout.LayoutParams(-1,-1));addView(tabs,FrameLayout.LayoutParams(-1,48))}
        val controller=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        controller.get().setContentView(host)
        pager.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));pager.layout(0,0,360,640)
        tabs.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(48,View.MeasureSpec.EXACTLY));tabs.layout(0,0,360,48)
        (tabs.getTabAt(0)!!.customView as GroupTabPill).settleSelected(true)
        return Triple(controller,pager,tabs)
    }
    @Test fun halfSwipeFillsNeighbourByDistanceAndReverseReturnsPreviousPill() {
        val tabs=TabLayout(context)
        val pager=ViewPager2(context)
        for(name in listOf("分组1","分组2","分组3")){val tab=tabs.newTab();GroupTabPill.bindTab(tab,name);tabs.addTab(tab)}
        val transition=GroupPageTransition(pager,tabs,{listOf(11L,22L,33L)},{})
        transition.onPageScrolled(0,.5f,100)
        val first=tabs.getTabAt(0)!!.customView as GroupTabPill
        val second=tabs.getTabAt(1)!!.customView as GroupTabPill
        assertEquals(.5f,first.selectionProgress,0f);assertEquals(.5f,second.selectionProgress,0f)
        transition.onPageScrolled(0,.25f,50)
        assertEquals(.75f,first.selectionProgress,0f);assertEquals(.25f,second.selectionProgress,0f)
        transition.onPageScrolled(0,0f,0)
        assertEquals(1f,first.selectionProgress,0f);assertEquals(0f,second.selectionProgress,0f)
        transition.close()
    }
    @Test fun distantClickCutsToAlphaZeroSameFrameAndInjectsPillTowardTarget() {
        val (controller,pager,tabs)=stage()
        var settled=-1
        val transition=GroupPageTransition(pager,tabs,{listOf(11L,22L,33L)},{settled=it})
        pager.registerOnPageChangeCallback(transition)
        val origin=tabs.getTabAt(0)!!.customView as GroupTabPill
        val target=tabs.getTabAt(2)!!.customView as GroupTabPill
        // toRight distant click: same-frame hard cut, no fade-out frames at all
        transition.request(33)
        assertEquals(0f,pager.alpha,0f)
        assertEquals(2,pager.currentItem)
        assertFalse(target.fillFromRight);assertTrue(origin.fillFromRight)
        advance(64)
        assertTrue(target.selectionProgress>0f&&target.selectionProgress<1f)
        val mid=target.selectionProgress
        advance(64)
        assertTrue(target.selectionProgress>mid&&target.selectionProgress<1f)
        advance(400)
        assertEquals(1f,pager.alpha,.01f);assertEquals(2,settled)
        assertEquals(ViewPager2.SCROLL_STATE_IDLE,pager.scrollState);assertFalse(transition.clicking)
        assertEquals(1f,target.selectionProgress,0f);assertEquals(0f,origin.selectionProgress,0f)
        // reverse distant click: injection direction flips —— 反向（目标在左）：
        // 旧胶囊（右）向左缘收拢 fromRight=false，新胶囊（左）从右缘长出 fromRight=true
        transition.request(11)
        assertEquals(0f,pager.alpha,0f);assertEquals(0,pager.currentItem)
        assertFalse(target.fillFromRight);assertTrue(origin.fillFromRight)
        advance(64)
        assertTrue(origin.selectionProgress>0f&&origin.selectionProgress<1f)
        advance(400)
        assertEquals(1f,pager.alpha,.01f);assertEquals(0,settled)
        assertEquals(1f,origin.selectionProgress,0f);assertEquals(0f,target.selectionProgress,0f)
        transition.close();pager.unregisterOnPageChangeCallback(transition)
        controller.pause().stop().destroy()
    }
    @Test fun adjacentClickReallyScrollsAndNeverFades() {
        val (controller,pager,tabs)=stage()
        var settled=-1
        val transition=GroupPageTransition(pager,tabs,{listOf(11L,22L,33L)},{settled=it})
        pager.registerOnPageChangeCallback(transition)
        val origin=tabs.getTabAt(0)!!.customView as GroupTabPill
        val target=tabs.getTabAt(1)!!.customView as GroupTabPill
        transition.request(22)
        assertEquals(1,pager.currentItem);assertEquals(1f,pager.alpha,0f)
        advance(32)
        assertEquals(ViewPager2.SCROLL_STATE_SETTLING,pager.scrollState);assertEquals(1f,pager.alpha,0f)
        assertTrue((origin.selectionProgress+target.selectionProgress) in 0.99f..1.01f)
        advance(800)
        assertEquals(ViewPager2.SCROLL_STATE_IDLE,pager.scrollState);assertEquals(1,pager.currentItem)
        assertEquals(1f,pager.alpha,0f);assertFalse(transition.clicking)
        assertEquals(1,settled)
        assertEquals(1f,target.selectionProgress,0f);assertEquals(0f,origin.selectionProgress,0f)
        assertEquals(0f,(tabs.getTabAt(2)!!.customView as GroupTabPill).selectionProgress,0f)
        transition.close();pager.unregisterOnPageChangeCallback(transition)
        controller.pause().stop().destroy()
    }
}
