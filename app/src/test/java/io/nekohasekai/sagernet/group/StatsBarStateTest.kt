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
/** Main-layout migration coverage: no old tray/FAB/progress may reappear after re-layout. */
class StatsBarStateTest {
    private fun withLayout(block:(LayoutMainBinding)->Unit) {
        val controller=Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity=controller.get().apply{setTheme(R.style.Theme_SagerNet)}
        controller.setup().visible()
        try {
            val binding=LayoutMainBinding.inflate(activity.layoutInflater)
            // Match MainActivity's theme selection: only one LEFT drawer may remain attached.
            binding.drawerLayout.removeView(binding.navViewBlack)
            activity.setContentView(binding.root)
            binding.root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY))
            binding.root.layout(0,0,1080,1920)
            shadowOf(Looper.getMainLooper()).idle()
            block(binding)
        } finally {controller.pause().stop().destroy()}
    }
    private fun assertControlVisible(binding:LayoutMainBinding) {
        val bar=binding.controlBar
        assertEquals(1f,bar.alpha,0f)
        assertEquals(View.VISIBLE,bar.visibility)
        assertEquals(0f,bar.translationY,0f)
        assertFalse((bar.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams).behavior != null)
        assertTrue(bar.bottom <= binding.coordinator.height)
    }

    @Test fun coldIdleAndStoppedHaveOnlyThePersistentControlBar()=withLayout { b ->
        for(state in listOf(BaseService.State.Idle,BaseService.State.Stopped,BaseService.State.Connecting,BaseService.State.Stopping)) {
            b.controlBar.changeState(state)
            assertControlVisible(b)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            b.root.requestLayout();b.root.layout(0,0,1080,1920)
            assertControlVisible(b)
            assertFalse(b.controlBar.findViewById<View>(R.id.capsule).isSelected)
        }
    }

    @Test fun disconnectedTransitionsDoNotHideOrSlideTheNewControlBar()=withLayout { b ->
        b.controlBar.changeState(BaseService.State.Connected)
        b.controlBar.changeState(BaseService.State.Stopped)
        b.controlBar.animate().cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertControlVisible(b)
        val views=ArrayList<View>()
        fun collect(view:View) {
            views+=view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(b.coordinator)
        assertFalse(views.any { it is com.google.android.material.floatingactionbutton.FloatingActionButton })
        assertFalse(views.any { it is com.google.android.material.bottomappbar.BottomAppBar })
        assertFalse(views.any { it is com.google.android.material.progressindicator.CircularProgressIndicator })
    }

    @Test fun restoreAndRelayoutDoNotReviveAnOldConnectedPresentation()=withLayout { b ->
        b.controlBar.changeState(BaseService.State.Connected)
        b.controlBar.showTesting()
        val saved=SparseArray<android.os.Parcelable>();b.controlBar.saveHierarchyState(saved)
        b.controlBar.changeState(BaseService.State.Idle)
        b.controlBar.restoreHierarchyState(saved)
        b.root.requestLayout();b.root.layout(0,0,1080,1920)
        assertControlVisible(b)
        assertFalse(b.controlBar.findViewById<View>(R.id.capsule).isSelected)
        assertEquals("-- ms",b.controlBar.findViewById<android.widget.TextView>(R.id.latency).text.toString())
    }

    @Test fun connectedControlRemainsClickableAndListsReserveItsFootprint()=withLayout { b ->
        b.controlBar.changeState(BaseService.State.Connected)
        var clicked=false
        b.controlBar.onToggle={clicked=true}
        val capsule=b.controlBar.findViewById<View>(R.id.capsule)
        assertTrue(capsule.isEnabled)
        capsule.performClick()
        assertTrue(clicked)
        assertControlVisible(b)
        val space=(b.fragmentHolder.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams).bottomMargin
        assertTrue(space >= b.controlBar.height)
        assertTrue(b.fragmentHolder.bottom <= b.controlBar.top)
    }
}
