package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Looper
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.ui.ConfigurationControlOverlay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w360dp-h640dp-mdpi")
/** Main layout remains a full-height content viewport; the configuration control floats over it. */
class StatsBarStateTest {
    private fun withLayout(block:(LayoutMainBinding,ConfigurationControlOverlay)->Unit) {
        val controller=Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity=controller.get().apply{setTheme(R.style.Theme_SagerNet)}
        controller.setup().visible()
        try {
            val binding=LayoutMainBinding.inflate(activity.layoutInflater)
            activity.setContentView(binding.root)
            layout(binding)
            block(binding,ConfigurationControlOverlay(binding.fragmentHolder,binding.controlBar))
        } finally {controller.pause().stop().destroy()}
    }
    private fun layout(binding:LayoutMainBinding) {
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY))
        binding.root.layout(0,0,360,640)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun assertControlVisible(binding:LayoutMainBinding) {
        val bar=binding.controlBar
        assertEquals(1f,bar.alpha,0f)
        assertEquals(View.VISIBLE,bar.visibility)
        assertEquals(0f,bar.translationY,0f)
        assertNull((bar.layoutParams as CoordinatorLayout.LayoutParams).behavior)
        assertTrue(bar.bottom<=binding.coordinator.height)
        assertTrue("The list must extend underneath the overlay, not stop above it",
            binding.fragmentHolder.bottom>bar.top)
    }
    @Test fun coldOtherPagesNeverShowAServiceControlOrReserveATray()=withLayout { b,overlay ->
        assertEquals(View.GONE,b.controlBar.visibility)
        assertEquals(0,(b.fragmentHolder.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin)
        for(state in BaseService.State.values()) {
            b.controlBar.changeState(state)
            overlay.show(null,false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            layout(b)
            assertEquals(View.GONE,b.controlBar.visibility)
            assertEquals(b.coordinator.height,b.fragmentHolder.height)
        }
    }
    @Test fun configurationDisconnectedTransitionsKeepOnlyTheFloatingControl()=withLayout { b,overlay ->
        overlay.show(b.fragmentHolder,true)
        b.controlBar.changeState(BaseService.State.Connected)
        b.controlBar.changeState(BaseService.State.Stopped)
        layout(b)
        assertControlVisible(b)
        val views=ArrayList<View>()
        fun collect(v:View) { views+=v;if(v is ViewGroup)for(i in 0 until v.childCount)collect(v.getChildAt(i)) }
        collect(b.coordinator)
        assertFalse(views.any{it is com.google.android.material.floatingactionbutton.FloatingActionButton})
        assertFalse(views.any{it is com.google.android.material.bottomappbar.BottomAppBar})
        assertFalse(views.any{it is com.google.android.material.progressindicator.CircularProgressIndicator})
        assertFalse(b.controlBar.findViewById<View>(R.id.capsule).isSelected)
    }
    @Test fun restoringHierarchyDoesNotReviveOldServiceOrWrongPageVisibility()=withLayout { b,overlay ->
        overlay.show(b.fragmentHolder,true)
        b.controlBar.changeState(BaseService.State.Connected)
        b.controlBar.showTesting()
        val saved=SparseArray<android.os.Parcelable>();b.controlBar.saveHierarchyState(saved)
        b.controlBar.changeState(BaseService.State.Idle)
        b.controlBar.restoreHierarchyState(saved)
        overlay.show(null,false)
        layout(b)
        assertEquals(View.GONE,b.controlBar.visibility)
        overlay.show(b.fragmentHolder,true);layout(b)
        assertControlVisible(b)
        assertFalse(b.controlBar.findViewById<View>(R.id.capsule).isSelected)
        assertEquals("-- ms",b.controlBar.findViewById<TextView>(R.id.latency).text.toString())
    }
    @Test fun connectedControlIsClickableAboveAnUncutViewport()=withLayout { b,overlay ->
        overlay.show(b.fragmentHolder,true)
        b.controlBar.changeState(BaseService.State.Connected)
        var clicked=false;b.controlBar.onToggle={clicked=true}
        val capsule=b.controlBar.findViewById<View>(R.id.capsule)
        assertTrue(capsule.isEnabled);capsule.performClick();assertTrue(clicked)
        layout(b);assertControlVisible(b)
        assertEquals(0,(b.fragmentHolder.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin)
        assertEquals(b.coordinator.height,b.fragmentHolder.height)
    }
    @Test fun safeAreaInsetsAreIndependentOfOverlayHeightAndNeverAccumulate()=withLayout { b,overlay ->
        overlay.applyInsets(8,12,24)
        overlay.show(b.fragmentHolder,true);layout(b)
        val bar=b.controlBar.layoutParams as CoordinatorLayout.LayoutParams
        val holder=b.fragmentHolder.layoutParams as CoordinatorLayout.LayoutParams
        assertEquals(22,bar.leftMargin);assertEquals(26,bar.rightMargin);assertEquals(38,bar.bottomMargin)
        assertEquals(24,holder.bottomMargin)
        assertEquals(b.coordinator.height-24,b.fragmentHolder.height)
        assertControlVisible(b)
        overlay.applyInsets(8,12,24);layout(b)
        assertEquals(38,(b.controlBar.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin)
        overlay.applyInsets(0,0,200);layout(b)
        assertEquals(214,(b.controlBar.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin)
        assertEquals(200,(b.fragmentHolder.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin)
        overlay.show(null,false);layout(b)
        assertEquals(View.GONE,b.controlBar.visibility)
        assertEquals(b.coordinator.height-200,b.fragmentHolder.height)
        overlay.applyInsets(0,0,0);layout(b)
        assertEquals(b.coordinator.height,b.fragmentHolder.height)
    }
    @Test fun mainConfigurationGateRejectsSelectHiddenPrewarmAndUnattachedPages() {
        fun show(configuration:Boolean=true,select:Boolean=false,added:Boolean=true,
                 hidden:Boolean=false,visible:Boolean=true,offscreen:Boolean=false,attached:Boolean=true)=
            ConfigurationControlOverlay.shouldShow(configuration,select,added,hidden,visible,offscreen,attached)
        assertTrue(show())
        assertFalse(show(configuration=false));assertFalse(show(select=true));assertFalse(show(added=false))
        assertFalse(show(hidden=true));assertFalse(show(visible=false));assertFalse(show(offscreen=true))
        assertFalse(show(attached=false))
    }
    @Test fun hidingCancelsPresentationButReshowPreservesPendingPermissionAndRearmsMeasurement()=withLayout { b,overlay ->
        b.controlBar.changeState(BaseService.State.Idle)
        overlay.show(b.fragmentHolder,true)
        var toggles=0;b.controlBar.onToggle={toggles++}
        val capsule=b.controlBar.findViewById<View>(R.id.capsule)
        capsule.performClick();assertEquals(1,toggles);assertFalse(capsule.isEnabled)
        overlay.show(null,false);overlay.show(b.fragmentHolder,true);overlay.applyInsets(0,0,24)
        assertFalse("Navigation/insets are not service acknowledgements",capsule.isEnabled)
        capsule.performClick();assertEquals(1,toggles)
        b.controlBar.changeState(BaseService.State.Connected)
        var tests=0;b.controlBar.onTest={tests++}
        b.controlBar.findViewById<View>(R.id.latency_box).performClick()
        assertEquals(1,tests)
        overlay.show(null,false);overlay.show(b.fragmentHolder,true)
        assertTrue(b.controlBar.findViewById<View>(R.id.speed_box).isEnabled)
        assertTrue(b.controlBar.findViewById<View>(R.id.latency_box).isEnabled)
        assertEquals("-- ms",b.controlBar.findViewById<TextView>(R.id.latency).text.toString())
        b.controlBar.showLatency(42)
        assertTrue(b.controlBar.findViewById<View>(R.id.latency_box).isEnabled)
    }
    @Test fun nodePaddingExtendsScrollRangeWhileCardsDrawBehindTheFloatingStrip()=withLayout { b,overlay ->
        val list=RecyclerView(b.root.context).apply{
            id=R.id.configuration_list
            layoutManager=LinearLayoutManager(context)
            setPadding(4,4,4,48)
            adapter=object:RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount()=24
                override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):RecyclerView.ViewHolder=
                    object:RecyclerView.ViewHolder(TextView(parent.context).apply{
                        layoutParams=RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,80)
                    }){}
                override fun onBindViewHolder(holder:RecyclerView.ViewHolder,position:Int){
                    (holder.itemView as TextView).text="Node $position"
                }
            }
        }
        b.fragmentHolder.addView(list,CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.show(b.fragmentHolder,true);layout(b)
        assertEquals(104,list.paddingBottom);assertFalse(list.clipToPadding)
        assertEquals(b.fragmentHolder.height,list.height)
        assertControlVisible(b)
        val layoutManager=list.layoutManager as LinearLayoutManager
        assertTrue("Visible nodes extend behind the overlay",list.getChildAt(list.childCount-1).bottom>b.controlBar.top)
        list.scrollToPosition(23);layout(b)
        val last=layoutManager.findViewByPosition(23)
        assertNotNull(last)
        assertTrue("The final node can scroll entirely above the strip",last!!.bottom<=b.controlBar.top)
        overlay.show(b.fragmentHolder,true);layout(b)
        assertEquals("Repeated show must not accumulate padding",104,list.paddingBottom)
    }
}
