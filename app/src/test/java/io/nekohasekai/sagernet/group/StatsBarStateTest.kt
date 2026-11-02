package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Looper
import android.util.SparseArray
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class StatsBarStateTest {
    private fun withLayout(block:(LayoutMainBinding)->Unit) {
        val controller=Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity=controller.get().apply{setTheme(R.style.Theme_SagerNet)}
        controller.setup().visible()
        try {
            val binding=LayoutMainBinding.inflate(activity.layoutInflater)
            // Match MainActivity's theme selection: only one LEFT drawer may remain attached.
            binding.drawerLayout.removeView(binding.navViewBlack)
            binding.fab.initProgress(binding.fabProgress)
            activity.setContentView(binding.root)
            binding.root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY))
            binding.root.layout(0,0,1080,1920)
            shadowOf(Looper.getMainLooper()).idle()
            block(binding)
        } finally {controller.pause().stop().destroy()}
    }
    private fun assertHidden(binding:LayoutMainBinding) {
        assertEquals(0f,binding.stats.alpha,0f)
        assertEquals(View.VISIBLE,binding.stats.visibility)
        assertFalse(binding.stats.isClickable)
        assertTrue(binding.stats.translationY>=binding.stats.height)
    }
    @Test fun coldIdleAndStoppedNeverFlashEvenWhenAnimatedRequested()=withLayout { b ->
        for(state in listOf(BaseService.State.Idle,BaseService.State.Stopped,BaseService.State.Connecting,BaseService.State.Stopping)) {
            b.stats.changeState(state)
            b.stats.syncMainControls(true,state,true,true)
            assertHidden(b)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            b.root.requestLayout();b.root.layout(0,0,1080,1920)
            assertHidden(b)
        }
        b.fab.changeState(BaseService.State.Stopped,BaseService.State.Idle,false)
        b.fab.show()
        var clicked=false;b.fab.setOnClickListener{clicked=true};b.fab.performClick()
        assertTrue(clicked);assertTrue(b.fab.isEnabled);assertEquals(View.VISIBLE,b.fab.visibility)
    }
    @Test fun staleShowAndBothBehaviorEntrypointsCannotReopenStoppedTray()=withLayout { b ->
        b.stats.syncMainControls(true,BaseService.State.Connected,true,true)
        b.stats.changeState(BaseService.State.Stopped)
        b.stats.syncMainControls(true,BaseService.State.Stopped,false,true)
        b.stats.performShow()
        b.stats.getBehavior().slideUp(b.stats)
        b.stats.getBehavior().slideUp(b.stats,false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertHidden(b)
    }
    @Test fun restoreAndRelayoutKeepDisconnectedTrayHidden()=withLayout { b ->
        b.stats.syncMainControls(true,BaseService.State.Connected,true,false)
        val state=SparseArray<android.os.Parcelable>();b.stats.saveHierarchyState(state)
        b.stats.changeState(BaseService.State.Idle)
        b.stats.restoreHierarchyState(state)
        b.root.requestLayout();b.root.layout(0,0,1080,1920)
        assertHidden(b)
    }
    @Test fun connectedStatisticsCanStillShowAndScroll()=withLayout { b ->
        b.stats.useExternalScrollDriver=true
        b.stats.changeState(BaseService.State.Connected)
        b.stats.syncMainControls(true,BaseService.State.Connected,true,false)
        assertEquals(1f,b.stats.alpha,0f);assertEquals(0f,b.stats.translationY,0f)
        b.stats.onListScrolled(200);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(b.stats.translationY>0f)
        b.stats.onListScrolled(-200);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(0f,b.stats.translationY,0.5f)
        b.stats.syncMainControls(false,BaseService.State.Connected,false,true)
        assertHidden(b)
    }
}
