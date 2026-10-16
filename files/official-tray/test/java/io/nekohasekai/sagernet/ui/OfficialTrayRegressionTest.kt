package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.widget.StatsBar
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OfficialTrayRegressionTest {
    @Test fun officialTrayHiddenWhenStoppedAndRaisedOnlyWhenConnected() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_SagerNet)
        val root=CoordinatorLayout(activity)
        val tray=StatsBar(activity)
        listOf(R.id.tx,R.id.rx,R.id.status).forEach { id -> tray.addView(TextView(activity).apply { this.id=id }) }
        tray.setOnClickListener { }
        root.addView(tray,CoordinatorLayout.LayoutParams(800,180))
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY))
        root.layout(0,0,1080,1920)
        tray.syncMainControls(true,BaseService.State.Stopped,false,false)
        assertTrue("Official tray must be below screen at idle",tray.translationY >= tray.height)
        tray.syncMainControls(true,BaseService.State.Connecting,false,false)
        assertTrue("Connecting must not raise stats",tray.translationY >= tray.height)
        tray.syncMainControls(true,BaseService.State.Connected,true,false)
        assertEquals(0f,tray.translationY,0.01f)
        tray.useExternalScrollDriver = true
        tray.onListScrolled(100)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertTrue("Scroll down should lower official tray", tray.translationY > 0f)
        tray.onListScrolled(-100)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals("Scroll back should raise official tray", 0f, tray.translationY, 0.01f)
        tray.syncMainControls(true,BaseService.State.Stopped,false,false)
        assertTrue(tray.translationY >= tray.height)
        controller.pause().stop().destroy()
    }
    @Test fun fastestAddressDoesNotWaitForSlowSourcesAndCancelsLosers() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val started=System.nanoTime()
        val result=FirstReplyRace.first(listOf(
            suspend { delay(30); "203.0.113.8" },
            suspend { try { delay(5000); "slow" } finally { cancelled.set(true) } },
        ),1000)
        assertEquals("203.0.113.8",result)
        assertTrue("winner blocked on slow source",(System.nanoTime()-started)/1_000_000 < 900)
        assertTrue("slow source was not cancelled",cancelled.get())
    }
    @Test fun failedSourceDoesNotPreventNextSuccessfulReply() = runBlocking {
        assertEquals("ok",FirstReplyRace.first(listOf(suspend { error("unavailable") },suspend { delay(20); "ok" }),1000))
    }
    @Test fun oldNetworkCannotOverwriteNewIpAndFirstReplyIsImmediatelyPublished() {
        fun value(ip: String)=IpQualityLookup.IpQuality(ip,"test","residential",null,null,false,false,false,false,null,true)
        IpQualityLookup.invalidate()
        val oldEpoch=IpQualityLookup.epoch()
        assertTrue(IpQualityLookup.publishIfCurrent(value("203.0.113.8"),10,oldEpoch))
        assertEquals("203.0.113.8",IpQualityLookup.updates.value!!.quality.ip)
        IpQualityLookup.invalidate()
        assertFalse(IpQualityLookup.publishIfCurrent(value("203.0.113.9"),10,oldEpoch))
        assertNull(IpQualityLookup.cacheFor(10))
        assertTrue(IpQualityLookup.publishIfCurrent(value("203.0.113.10"),11,IpQualityLookup.epoch()))
        assertEquals("203.0.113.10",IpQualityLookup.updates.value!!.quality.ip)
    }
}
