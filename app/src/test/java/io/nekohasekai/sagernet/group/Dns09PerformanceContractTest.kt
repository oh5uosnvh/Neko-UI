package io.nekohasekai.sagernet.group
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class Dns09PerformanceContractTest {
 private val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}; private fun source(n:String)=File(app,"src/main/java/io/nekohasekai/sagernet/ui/$n.kt").readText()
 @Test fun startupWarmsBoundedSidebarPagesAndDetachedViewsWaitForDrawerClose(){val m=source("MainActivity");assertTrue(m.contains("DRAWER_WARM_START_MS = 240L"));assertTrue(m.contains("DRAWER_WARM_INTERVAL_MS = 250L"));assertTrue(m.contains("val hot = fragment.isAdded && !fragment.isDetached && fragment.view != null"));assertTrue(m.contains("transaction.detach(cached)"))}
 @Test fun directClickIsCentredInjectedAndCrossFadedWithoutBlank(){val t=source("GroupPageTransition");for(v in listOf("TAB_CENTRE_MS = 180L","PILL_INJECTION_MS = 200L","OLD_FADE_MS = 160f","INCOMING_DELAY_MS = 80f","INCOMING_FADE_MS = 160f","pager.overlay.add","contentLayer()?.alpha = 1f","pager.setCurrentItem(target, false)"))assertTrue(v,t.contains(v));assertTrue(t.contains("updateProgress(1f - offset, true)"));assertTrue(t.contains("updateProgress(offset, false)"))}
}
