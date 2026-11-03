package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Dns09PerformanceContractTest {
    private val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}
    private fun source(name:String)=File(app,"src/main/java/io/nekohasekai/sagernet/ui/$name.kt").readText()
    @Test fun noEagerHiddenPageTreesAndBoundedAttachedNavigationRetainState() {
        val main=source("MainActivity")
        val create=main.substringAfter("override fun onCreate(").substringBefore("override fun onResume()")
        assertFalse(create.contains("postDelayed(prewarmRunnable"))
        assertFalse(create.contains("postDelayed(drawerWarmRunnable"))
        val nav=main.substringAfter("fun displayFragment(fragment:").substringBefore("private val drawerListener")
        assertTrue(nav.contains("cached !== fragment && cached !== old"));assertTrue(nav.contains("transaction.detach(cached)"))
        assertTrue(nav.contains("cached !is ConfigurationFragment"))
        assertTrue(nav.contains("transaction.attach(fragment)"));assertTrue(nav.contains("drawerWasOpen"))
        val config=source("ConfigurationFragment")
        assertTrue(config.contains("groupPager.offscreenPageLimit = 1"))
        val bind=config.substringAfter("fun bind(proxyEntity: ProxyEntity)").substringBefore("fun bindProfileState(")
        assertFalse(bind.contains("DataStore.profileCardStyle"));assertFalse(bind.contains("DataStore.alwaysShowAddress"))
        assertTrue(bind.contains("TwoColumnProtocolLabel.display"))
    }
    @Test fun pageSelectionNoSynchronousDbAndClickTransitionDifferentFromSwipe() {
        val config=source("ConfigurationFragment")
        val selected=config.substringAfter("override fun onPageSelected(position: Int)").substringBefore("override fun onQueryTextChange")
        assertFalse(selected.contains("DataStore.selectedGroup"));assertTrue(selected.contains("groupSelectionWriter.submit(newId)"))
        val transition=source("GroupPageTransition")
        assertTrue(transition.contains("pager.setCurrentItem(target, false)"));assertTrue(transition.contains("pager.alpha = fraction"))
        assertTrue(transition.contains("updateProgress(1f - offset, true)"))
        assertTrue(transition.contains("updateProgress(offset, false)"))
    }
}
